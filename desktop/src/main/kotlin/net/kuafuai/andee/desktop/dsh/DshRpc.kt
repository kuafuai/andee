package net.kuafuai.andee.desktop.dsh

import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * The newline-framed JSON-RPC 2.0 client for one `dsh --profile sdk` process (`dsh-sdk-protocol`).
 *
 * One line per message on stdin/stdout; a frame with `id` and `method` is a request from the runtime (here:
 * `approval/request`), `id` alone a response, `method` alone a notification (`session.event`, `session.status`).
 * Requests from the runtime are answered on their own thread, never on the reader: an approval waits on the
 * user for minutes, and a reader that blocked would stop every event behind it.
 *
 * The protocol has no cancel and no session-close method — "a client abandons a turn by closing the runtime
 * process" (`dsh-sdk-jsonrpc-server`) — which is why [DshWorkers] runs one process per job.
 */
class DshRpc(
    private val process: Process,
    private val onNotify: (method: String, params: JSONObject) -> Unit,
    /** Answer a runtime request, or null for "unsupported" (`-32601`). Runs on a worker thread. */
    private val onRequest: (method: String, params: JSONObject) -> JSONObject?,
) : Closeable {

    class Failure(message: String, val code: Int) : Exception(message)

    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()
    private val ids = AtomicLong(0)
    private val answers = Executors.newCachedThreadPool { r -> Thread(r, "dsh-rpc-answer").apply { isDaemon = true } }

    init {
        Thread({ readLoop() }, "dsh-rpc-read").apply { isDaemon = true }.start()
    }

    /** Send a request and wait for its result. A JSON-RPC error, a timeout or a dead process is a [Failure]. */
    fun call(method: String, params: JSONObject, timeoutMs: Long): JSONObject {
        val id = ids.incrementAndGet()
        val f = CompletableFuture<JSONObject>()
        pending[id] = f
        try {
            write(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
        } catch (e: Exception) {
            pending.remove(id)
            throw Failure("could not write to dsh: ${e.message}", -2)
        }
        val reply = try {
            f.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            pending.remove(id)
            throw Failure("$method did not answer in ${timeoutMs / 1000}s", -1)
        } catch (e: ExecutionException) {
            throw (e.cause as? Failure) ?: Failure(e.cause?.message ?: "failed", -2)
        }
        reply.optJSONObject("error")?.let { throw Failure(it.optString("message").ifEmpty { "error" }, it.optInt("code")) }
        return reply.optJSONObject("result") ?: JSONObject()
    }

    @Synchronized
    private fun write(o: JSONObject) {
        writer.write(o.toString())
        writer.write("\n")
        writer.flush()
    }

    private fun readLoop() {
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val t = line.trim()
                if (t.isEmpty()) return@forEachLine
                // stdout is the protocol, and a line that is not JSON is dropped, as the spec says.
                val m = runCatching { JSONObject(t) }.getOrNull() ?: return@forEachLine
                val method = m.optString("method").ifEmpty { null }
                val hasId = m.has("id") && !m.isNull("id")
                when {
                    method != null && hasId -> answers.execute { answer(m.get("id"), method, m.optJSONObject("params") ?: JSONObject()) }
                    method != null -> runCatching { onNotify(method, m.optJSONObject("params") ?: JSONObject()) }
                    hasId -> pending.remove(m.optLong("id"))?.complete(m)
                }
            }
        } catch (_: Exception) {
            // The process closed its stdout: it ended or was killed. Everything waiting fails below.
        } finally {
            val dead = Failure("dsh ended", -2)
            pending.values.forEach { it.completeExceptionally(dead) }
            pending.clear()
        }
    }

    private fun answer(id: Any, method: String, params: JSONObject) {
        val result = runCatching { onRequest(method, params) }.getOrNull()
        val reply = JSONObject().put("jsonrpc", "2.0").put("id", id)
        if (result != null) reply.put("result", result)
        else reply.put("error", JSONObject().put("code", -32601).put("message", "not supported by this host"))
        runCatching { write(reply) }
    }

    override fun close() {
        runCatching { writer.close() }
        answers.shutdownNow()
    }
}
