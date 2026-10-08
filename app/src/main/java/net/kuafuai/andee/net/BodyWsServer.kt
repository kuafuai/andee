package net.kuafuai.andee.net

import android.util.Log
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

/**
 * The body's WebSocket server. Two very different clients arrive here:
 *
 *  - **Drivers** — the brain (via `adb forward`), a debug/viewer client, top-bar
 *    tests. They send `request` frames and receive `response`s and broadcast
 *    `event`s. This is the original shape.
 *
 *  - **Call extension** — a peripheral (see whatsapp-agent's `bodyClient.ts`)
 *    that dials in, sends `{"type":"register","role":"call_extension",...}`,
 *    and hands us a batch of tool schemas it can serve. The body then reverses
 *    the arrow: request/response goes body → extension for those tool names,
 *    and the extension pushes `event` frames back up. It is not a driver, so
 *    it never sees broadcast events and does not sit in [active].
 *
 * Envelopes:
 *
 *   Driver → Server:
 *     {"type":"request","id":"...","method":"screen.tap","params":{...}}
 *
 *   Server → Driver:
 *     {"type":"response","id":"...","result":{...}}
 *     {"type":"response","id":"...","error":"..."}
 *     {"type":"event","kind":"asr.final","data":{...}}
 *
 *   Extension → Server:
 *     {"type":"register","role":"call_extension",
 *      "device_name":"...","scene":"...","tools":[{...}, ...]}
 *     {"type":"response","id":"...","result":{...}|"error":"..."}
 *     {"type":"event","kind":"call.state|call.subtitle|call.summary","data":{...}}
 *
 *   Server → Extension:
 *     {"type":"request","id":"...","method":"pickup_call","params":{...}}
 *
 * [onCommand] returns a JSON-serializable value (usually JSONObject) or throws.
 * Thrown exceptions become error responses; nothing else propagates.
 *
 * Binding to 0.0.0.0 by default so the brain running on another host on the
 * LAN can reach it. Loopback-only is also fine when driven via adb forward.
 */
class BodyWsServer(
    port: Int,
    bindHost: String = "0.0.0.0",
    private val registry: CallExtensionRegistry,
    /** Extension-pushed event; the service turns these into hub broadcasts + UI updates. */
    private val onExtensionEvent: (kind: String, data: JSONObject) -> Unit = { _, _ -> },
    private val onCommand: (method: String, params: JSONObject?) -> Any?,
    /**
     * `debug.say {"text"}` — hand a sentence to the brain as if the user typed
     * it, so a task can be reproduced from a computer. Loopback only (that is
     * what `adb forward` arrives as): a LAN peer could already drive the
     * screen through this socket, but it should not get to spend the user's
     * API key.
     */
    private val onSay: (text: String) -> Unit = {},
) : WebSocketServer(InetSocketAddress(bindHost, port)) {

    /** Driver connections only — the ones broadcasts go to. Extensions never join. */
    private val active = CopyOnWriteArraySet<WebSocket>()

    /**
     * In-flight reverse-requests we sent to the extension, keyed by envelope
     * id. Reused for their responses — a SynchronousQueue of length one so a
     * late response after timeout has nowhere to sit and drops harmlessly.
     */
    private val pending = ConcurrentHashMap<String, SynchronousQueue<PendingResult>>()

    init {
        isReuseAddr = true
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        // Provisionally treat every new connection as a driver. If a register
        // frame arrives naming role=call_extension, we lift it back out.
        active.add(conn)
        Log.d(TAG, "onOpen ${conn.remoteSocketAddress} drivers=${active.size}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        active.remove(conn)
        if (registry.unregister(conn)) {
            Log.i(TAG, "call extension disconnected: ${conn.remoteSocketAddress} code=$code reason=$reason")
            // Nothing to talk to for any request we sent out — wake them all
            // with an error so brain gets a proper failure instead of the hub
            // request timing out at 30s.
            failAllPending("call extension disconnected")
        }
        Log.d(TAG, "onClose ${conn.remoteSocketAddress} code=$code reason=$reason remote=$remote drivers=${active.size}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val msg = runCatching { JSONObject(message) }.getOrNull()
        if (msg == null) {
            Log.w(TAG, "non-JSON message ignored: ${message.take(100)}")
            return
        }
        when (msg.optString("type")) {
            "request" -> handleDriverRequest(conn, msg)
            "register" -> handleRegister(conn, msg)
            "response" -> handleExtensionResponse(msg)
            "event" -> handleExtensionEvent(conn, msg)
            else -> Log.d(TAG, "ignoring type=${msg.optString("type")}")
        }
    }

    private fun handleDriverRequest(conn: WebSocket, req: JSONObject) {
        val id = req.optString("id", "")
        val method = req.optString("method", "")
        val params = req.optJSONObject("params")
        Log.d(TAG, "request ${conn.remoteSocketAddress} $method")
        // Dispatch OFF the WS thread: blocking tools (ui.ask waits for a
        // human, scan waits for a code, look waits for a frame) would
        // otherwise stall ping/pong handling and the client drops us.
        dispatchPool.execute {
            val response = try {
                val result = if (method == "debug.say") {
                    if (conn.remoteSocketAddress?.address?.isLoopbackAddress != true) {
                        throw IllegalStateException("debug.say is loopback only — use adb forward")
                    }
                    val text = params?.optString("text").orEmpty().trim()
                    require(text.isNotEmpty()) { "debug.say needs {\"text\": …}" }
                    onSay(text)
                    JSONObject().put("submitted", text)
                } else {
                    onCommand(method, params)
                }
                JSONObject()
                    .put("type", "response")
                    .put("id", id)
                    .put("result", result ?: JSONObject.NULL)
            } catch (t: Throwable) {
                Log.w(TAG, "command '$method' failed: ${t.message}")
                JSONObject()
                    .put("type", "response")
                    .put("id", id)
                    .put("error", t.message ?: t.javaClass.simpleName)
            }
            runCatching { conn.send(response.toString()) }
        }
    }

    private fun handleRegister(conn: WebSocket, msg: JSONObject) {
        val role = msg.optString("role")
        if (role != "call_extension") {
            Log.w(TAG, "register with unknown role='$role', ignoring")
            return
        }
        val data = msg.optJSONObject("data") ?: msg  // tolerate either shape
        val tools = data.optJSONArray("tools") ?: JSONArray()
        val deviceName = data.optString("device_name").ifEmpty { null }
        val scene = data.optString("scene").ifEmpty { null }
        // A registered extension is a service peer, not a broadcast recipient.
        active.remove(conn)
        val previous = registry.register(conn, tools, deviceName, scene)
        if (previous != null && previous !== conn) {
            // One slot; a second extension replaces the first. Fail its
            // outstanding requests before closing, or their callers hang.
            failAllPending("call extension replaced by ${conn.remoteSocketAddress}")
            runCatching { previous.close(1000, "replaced by newer registration") }
        }
        Log.i(
            TAG,
            "call extension registered: ${conn.remoteSocketAddress} " +
                    "name='${deviceName ?: "-"}' tools=${tools.length()}"
        )
    }

    private fun handleExtensionResponse(msg: JSONObject) {
        val id = msg.optString("id")
        if (id.isEmpty()) return
        val slot = pending.remove(id) ?: run {
            // Late (post-timeout) or unknown; nothing to wake.
            return
        }
        val err = msg.optString("error").ifEmpty { null }
        val result = if (msg.has("result")) msg.opt("result") else null
        // offer, not put: if the receiver already gave up, this drops instead
        // of pinning the WS thread on a queue nobody is reading.
        slot.offer(PendingResult(result, err), 0, TimeUnit.MILLISECONDS)
    }

    private fun handleExtensionEvent(conn: WebSocket, msg: JSONObject) {
        // Only accept events from the currently-registered extension. A stray
        // driver that pushes `event` frames has no channel to publish on.
        if (registry.connection() !== conn) {
            Log.d(TAG, "event from non-extension ${conn.remoteSocketAddress}, ignoring")
            return
        }
        val kind = msg.optString("kind")
        if (kind.isEmpty()) return
        val data = msg.optJSONObject("data") ?: JSONObject()
        runCatching { onExtensionEvent(kind, data) }
            .onFailure { Log.w(TAG, "onExtensionEvent threw for kind=$kind", it) }
    }

    private val dispatchPool = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "BodyWsServer-dispatch").apply { isDaemon = true }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "onError ${conn?.remoteSocketAddress}", ex)
        if (conn != null) {
            active.remove(conn)
            if (registry.unregister(conn)) failAllPending("call extension error: ${ex.message}")
        }
    }

    override fun onStart() {
        Log.i(TAG, "listening on ${address}")
    }

    /** Broadcast an event to all driver clients. Extensions never receive it. */
    fun broadcastEvent(kind: String, data: JSONObject) {
        if (active.isEmpty()) return
        val envelope = JSONObject()
            .put("type", "event")
            .put("kind", kind)
            .put("data", data)
        val json = envelope.toString()
        for (c in active) runCatching { c.send(json) }
    }

    fun clientCount(): Int = active.size

    /**
     * Send [method] to the registered call extension and block for its response.
     *
     * Throws [IllegalStateException] when no extension is connected — the caller
     * (dispatcher) turns that into a proper `error` response to the brain, same
     * as any other tool failure. Also throws on timeout: `body_hub` gives a
     * request 30s, so [timeoutMs] defaults well under that.
     *
     * Runs on whatever thread the caller is on (usually a dispatch worker).
     */
    fun forwardToExtension(
        method: String,
        params: JSONObject?,
        timeoutMs: Long = DEFAULT_EXTENSION_TIMEOUT_MS,
    ): Any? {
        val conn = registry.connection()
            ?: throw IllegalStateException(
                "call extension is not connected — is the call box on and reachable?"
            )
        val id = UUID.randomUUID().toString()
        val slot = SynchronousQueue<PendingResult>()
        pending[id] = slot
        val envelope = JSONObject()
            .put("type", "request")
            .put("id", id)
            .put("method", method)
        if (params != null) envelope.put("params", params)
        try {
            if (!conn.isOpen) {
                throw IllegalStateException("call extension socket is closed")
            }
            conn.send(envelope.toString())
        } catch (t: Throwable) {
            pending.remove(id)
            throw t
        }
        val out = try {
            slot.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            pending.remove(id)
        }
            ?: throw IllegalStateException(
                "call extension did not respond within ${timeoutMs}ms for $method"
            )
        if (out.error != null) throw IllegalStateException(out.error)
        return out.result
    }

    /** Wake every pending reverse-request with an error. Called on extension loss. */
    private fun failAllPending(reason: String) {
        // Drain snapshot to avoid CME while other threads try to install more.
        val ids = pending.keys.toList()
        for (id in ids) {
            val slot = pending.remove(id) ?: continue
            slot.offer(PendingResult(null, reason), 0, TimeUnit.MILLISECONDS)
        }
    }

    private data class PendingResult(val result: Any?, val error: String?)

    companion object {
        private const val TAG = "BodyWs"

        /**
         * Ceiling for a body→extension request. Under the hub's 30s so the
         * failure surfaces here with a real message ("did not respond") rather
         * than as an anonymous hub timeout.
         */
        const val DEFAULT_EXTENSION_TIMEOUT_MS = 25_000L
    }
}
