package net.kuafuai.andee.peer

import org.json.JSONObject
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A device's agent, reached over the link as if it were local (the phone using the Mac's dsh).
 *
 * The owner's choice, per paired device and off until they turn it on ([PeerStore.dshAllowed]): the Mac carries the
 * phone's `delegate_task` and its siblings straight to its own dsh, with **no Andee in between** — the Mac does not
 * judge them, because the phone's Andee already did. The phone lists the same tools it would with a dsh of its own.
 *
 * ### The wire (inside the authenticated, encrypted channel; one request, one reply)
 * - `remote.call` `{id, method, args}` → `remote.result` `{id, ok, result | error}` — one of the five `dsh.*` methods.
 * - `remote.ls` `{id, worker, path}` → `remote.result` — a folder's entries: name, kind, size, modified.
 * - `remote.get` `{id, worker, path, offset, length}` → `remote.result` `{data (base64), size, eof}` — one chunk of
 *   one file, like a cloud drive: nothing is sent until the phone asks, and a file is read piece by piece, never whole.
 * - `remote.event` `{worker, kind, text, detail}` — the Mac telling the phone a worker finished (the report), so it
 *   arrives as a turn of its own, like `task.result`.
 *
 * What the owner's switch does **not** allow: any path outside a worker's own folder (see [Agent.resolve]), and anything
 * but the five methods. The files a worker made are data, never instructions, on the phone as on the Mac.
 */
object PeerRemote {

    /** Methods the Mac will carry. Everything else is refused, whatever the phone asks. */
    val METHODS = setOf("dsh.delegate", "dsh.status", "dsh.cancel", "dsh.read", "dsh.open")

    /** The Mac's side: what runs a call, lists a folder, reads a chunk. Implemented by the platform that has a dsh. */
    interface Agent {
        /** Whether this device has an agent at all right now (dsh installed and not switched off). */
        fun available(): Boolean
        /** Run one of [METHODS]. Throws with a reason the phone's model reads. */
        fun call(method: String, args: JSONObject): JSONObject
        /** The entries of [path] inside worker [worker]'s folder (empty path = the folder). Throws when outside it. */
        fun list(worker: String, path: String): JSONObject
        /** [length] bytes of [path] from [offset]. Throws when outside the folder or not a file. */
        fun read(worker: String, path: String, offset: Long, length: Int): Chunk
    }

    class Chunk(val bytes: ByteArray, val size: Long, val eof: Boolean)

    /** Largest piece one `remote.get` returns: well under [PeerWire.MAX_FRAME] once base64'd. */
    const val CHUNK = 1024 * 1024

    // ======================================================================================================
    // the carrying side (the Mac)
    // ======================================================================================================

    /** Serves requests from paired devices. [allowed] is the owner's switch for a device. */
    class Server(private val agent: () -> Agent?, private val allowed: (String) -> Boolean) {

        /** True when [msg] was one of ours (handled, answered or refused). */
        fun onMessage(channel: PeerChannel, msg: JSONObject): Boolean {
            val type = msg.optString("type")
            if (!type.startsWith("remote.")) return false
            val id = msg.optString("id").take(64)
            fun refuse(why: String) { channel.send(result(id, ok = false, error = why)) }
            if (type == "remote.result" || type == "remote.event") return false   // ours to receive, not to serve
            if (!allowed(channel.peer.id)) return true.also { refuse("This computer has not allowed ${channel.peer.name} to use its agent.") }
            val a = agent()?.takeIf { it.available() } ?: return true.also { refuse("There is no agent on this computer right now (dsh is not installed or is switched off).") }
            // Off the socket's thread: a read of a big file or a worker starting must not hold the link's reader.
            Thread({
                val reply = runCatching {
                    when (type) {
                        "remote.call" -> {
                            val method = msg.optString("method")
                            require(method in METHODS) { "not a method this computer carries: $method" }
                            result(id, ok = true, value = a.call(method, msg.optJSONObject("args") ?: JSONObject()))
                        }
                        "remote.ls" -> result(id, ok = true, value = a.list(msg.optString("worker"), msg.optString("path")))
                        "remote.get" -> {
                            val length = msg.optInt("length", CHUNK).coerceIn(1, CHUNK)
                            val c = a.read(msg.optString("worker"), msg.optString("path"), msg.optLong("offset", 0).coerceAtLeast(0), length)
                            result(id, ok = true, value = JSONObject().put("data", Base64.getEncoder().encodeToString(c.bytes))
                                .put("size", c.size).put("eof", c.eof))
                        }
                        else -> result(id, ok = false, error = "unknown request $type")
                    }
                }.getOrElse { e -> result(id, ok = false, error = e.message ?: e.javaClass.simpleName) }
                channel.send(reply)
            }, "peer-remote").apply { isDaemon = true }.start()
            return true
        }

        /** A worker ended on this computer: tell [to] so its Andee hears the report. False when there is no link. */
        fun event(to: PeerChannel, worker: String, text: String, detail: String): Boolean =
            to.send(JSONObject().put("type", "remote.event").put("worker", worker).put("text", text.take(MAX_EVENT)).put("detail", detail.take(MAX_EVENT)))
    }

    // ======================================================================================================
    // the asking side (the phone)
    // ======================================================================================================

    /** The phone's view of the Mac's agent. One call blocks for the reply, off any UI thread. */
    class Client(private val link: (String) -> PeerChannel?) {
        private val waiting = ConcurrentHashMap<String, Pending>()

        private class Pending(val latch: CountDownLatch = CountDownLatch(1), @Volatile var reply: JSONObject? = null)

        /** Fed by the platform's [PeerEvents]; true when [msg] was a reply to one of ours. */
        fun onMessage(msg: JSONObject): Boolean {
            if (msg.optString("type") != "remote.result") return false
            val p = waiting[msg.optString("id")] ?: return true
            p.reply = msg
            p.latch.countDown()
            return true
        }

        fun call(device: String, method: String, args: JSONObject, timeoutMs: Long = CALL_MS): JSONObject =
            request(device, JSONObject().put("type", "remote.call").put("method", method).put("args", args), timeoutMs)

        fun list(device: String, worker: String, path: String): JSONObject =
            request(device, JSONObject().put("type", "remote.ls").put("worker", worker).put("path", path), CALL_MS)

        /** One chunk. Returns the bytes and whether the file ended. */
        fun get(device: String, worker: String, path: String, offset: Long, length: Int = CHUNK): Chunk {
            val r = request(device, JSONObject().put("type", "remote.get").put("worker", worker).put("path", path)
                .put("offset", offset).put("length", length), GET_MS)
            return Chunk(Base64.getDecoder().decode(r.optString("data")), r.optLong("size"), r.optBoolean("eof"))
        }

        private fun request(device: String, msg: JSONObject, timeoutMs: Long): JSONObject {
            val ch = link(device)?.takeIf { it.isOpen } ?: throw IllegalStateException(
                "The computer cannot be reached now — it is off, asleep, or on another network (it is only reachable on the same Wi-Fi). " +
                    "Nothing was done. Tell the user; do not say it was started.",
            )
            val id = UUID.randomUUID().toString()
            val p = Pending()
            waiting[id] = p
            try {
                if (!ch.send(msg.put("id", id))) throw IllegalStateException("The link to the computer dropped while sending. Nothing was done.")
                if (!p.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw IllegalStateException("The computer did not answer in time. It may still have done it; ask for its status before doing it again.")
                val r = p.reply ?: throw IllegalStateException("The computer sent no answer.")
                if (!r.optBoolean("ok")) throw IllegalStateException(r.optString("error").ifEmpty { "The computer refused." })
                return r.optJSONObject("result") ?: JSONObject()
            } finally {
                waiting.remove(id)
            }
        }
    }

    private fun result(id: String, ok: Boolean, value: JSONObject? = null, error: String? = null): JSONObject =
        JSONObject().put("type", "remote.result").put("id", id).put("ok", ok)
            .apply { if (value != null) put("result", value); if (error != null) put("error", error.take(600)) }

    private const val MAX_EVENT = 8_000
    private const val CALL_MS = 60_000L
    private const val GET_MS = 120_000L
}
