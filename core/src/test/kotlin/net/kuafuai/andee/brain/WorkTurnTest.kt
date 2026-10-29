package net.kuafuai.andee.brain

import com.sun.net.httpserver.HttpServer
import net.kuafuai.andee.config.BrainConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A turn Andee starts itself: it is handed the same tools as any turn (the device is its own), nothing is spoken,
 * and the device gets back every call it made.
 */
class WorkTurnTest {
    private val requests = mutableListOf<JSONObject>()
    private val spoken = mutableListOf<String>()
    private val dispatched = mutableListOf<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/chat/completions") { ex ->
            val body = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val n = synchronized(requests) { requests += body; requests.size }
            fun call(id: String, name: String, args: String) = JSONObject().put("id", id).put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", args))
            val msg = if (n == 1) {
                JSONObject().put("role", "assistant").put("content", "")
                    .put("tool_calls", JSONArray().put(call("c1", "tap", "{}")).put(call("c2", "remember", """{"name":"x"}""")))
            } else {
                JSONObject().put("role", "assistant").put("content", "写下了下一步")
            }
            val out = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg)))
                .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1)).toString().toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }
    private val trace = File.createTempFile("trace", "").also { it.delete(); it.mkdirs() }

    private fun tool(name: String, method: String) = JSONObject().put("name", name).put("method", method).put("description", name)
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))

    private val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BrainHost::class.java)) { proxy, m, args ->
        when (m.name) {
            "text" -> (args[0] as BrainText).name
            "locale" -> Locale.ENGLISH
            "maxSteps" -> 10
            "persona", "platformPrompt", "notebookIndex", "scenesIndex", "pagesIndex", "topicsIndex",
            "gearIndex", "planIndex", "friendsIndex", "selfIndex", "wikiIndex", "reviewRecord" -> ""
            "scrollback" -> emptyList<ScrollRow>()
            "toolLists" -> listOf(JSONArray().put(tool("tap", "screen.tap")).put(tool("remember", "note.remember")))
            // A proxy intercepts default methods too, so answer this one the way the default would.
            "workTools" -> (proxy as BrainHost).toolLists()
            "methodOf" -> mapOf("tap" to "screen.tap", "remember" to "note.remember")[args[0] as String]
            "dispatch" -> { synchronized(dispatched) { dispatched += args[0] as String }; JSONObject().put("ok", true) }
            "todoStillActive" -> true
            "traceDir" -> trace
            else -> null
        }
    } as BrainHost

    private val brain = LocalBrain(
        BrainConfig(BrainConfig.BRAIN_LOCAL, "key", "http://127.0.0.1:${server.address.port}", "m", false, "low"),
        host, onProgress = { spoken += it }, onFinal = { spoken += it },
    )

    @After fun tearDown() {
        brain.stop()
        server.stop(0)
    }

    @Test fun aWorkTurnHasEveryToolAndSaysNothing() {
        val done = LinkedBlockingQueue<LocalBrain.WorkResult>()
        brain.work("[You woke yourself: 社区]", "place") { done += it }
        val r = done.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("no result")

        val offered = requests.first().getJSONArray("tools").let { a -> (0 until a.length()).map { a.getJSONObject(it).getJSONObject("function").getString("name") } }
        assertEquals(listOf("tap", "remember"), offered)
        assertEquals(listOf("screen.tap", "note.remember"), dispatched)
        assertEquals(listOf("screen.tap", "note.remember"), r.calls.map { it.method })
        assertEquals("写下了下一步", r.summary)
        assertTrue("a work turn spoke: $spoken", spoken.isEmpty())
    }
}
