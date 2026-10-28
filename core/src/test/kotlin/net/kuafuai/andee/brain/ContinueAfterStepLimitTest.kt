package net.kuafuai.andee.brain

import com.sun.net.httpserver.HttpServer
import net.kuafuai.andee.config.BrainConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The 10-22 11:30 failure, replayed against a scripted model: a task stops at the step limit, a todo fires
 * before the user answers, and then the user says 继续. The request that 继续 sends must still carry the
 * task, what the user was told when it stopped, and a line saying it is unfinished.
 */
class ContinueAfterStepLimitTest {

    private val steps = 35
    private val requests = mutableListOf<JSONObject>()
    private val finals = LinkedBlockingQueue<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/chat/completions") { ex ->
            val body = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val n = synchronized(requests) { requests += body; requests.size }
            val msg = if (n <= steps) {
                JSONObject().put("role", "assistant").put("content", "").put(
                    "tool_calls",
                    JSONArray().put(
                        JSONObject().put("id", "c$n").put("type", "function").put(
                            "function", JSONObject().put("name", "tap").put("arguments", "{\"x\":$n}"),
                        ),
                    ),
                )
            } else {
                JSONObject().put("role", "assistant").put("content", "reply $n")
            }
            val out = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg)))
                .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1)).toString().toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }

    private val trace = File.createTempFile("trace", "").also { it.delete(); it.mkdirs() }

    private val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BrainHost::class.java)) { _, m, args ->
        when (m.name) {
            "text" -> (args[0] as BrainText).name
            "locale" -> Locale.ENGLISH
            "maxSteps" -> steps
            "persona", "platformPrompt", "notebookIndex", "scenesIndex", "pagesIndex", "topicsIndex",
            "gearIndex", "planIndex", "friendsIndex", "selfIndex", "wikiIndex" -> ""
            "scrollback" -> emptyList<ScrollRow>()
            "toolLists" -> listOf(
                JSONArray().put(
                    JSONObject().put("name", "tap").put("method", "screen.tap").put("description", "tap")
                        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())),
                ),
            )
            "methodOf" -> "screen.tap"
            "dispatch" -> JSONObject().put("ok", true)
            "traceDir" -> trace
            else -> null
        }
    } as BrainHost

    private val brain = LocalBrain(
        BrainConfig(BrainConfig.BRAIN_LOCAL, "key", "http://127.0.0.1:${server.address.port}", "m", false, "low"),
        host, onProgress = {}, onFinal = { finals += it },
    )

    @After fun tearDown() {
        brain.stop()
        server.stop(0)
    }

    private fun final(): String = finals.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("no answer")

    @Test fun continueStillSeesTheStoppedTask() {
        brain.submit("打开朋友圈，把动态整理成清单")
        assertEquals("MAX_STEPS", final())
        // A todo comes due before the user answers, and becomes the newest turn.
        brain.wake("(time's up) ask about the article")
        final()
        brain.submit("继续")
        final()

        val messages = requests.last().getJSONArray("messages")
        val all = (0 until messages.length()).map { messages.getJSONObject(it) }
        val user = all.filter { it.optString("role") == "user" && it.opt("content") is String }.map { it.getString("content") }
        assertTrue("the task's request is gone", user.any { it.contains("打开朋友圈，把动态整理成清单") })
        assertTrue("its steps are gone", all.count { it.optString("role") == "tool" } >= steps)
        assertTrue("what the user was told is missing", all.any { it.optString("role") == "assistant" && it.optString("content") == "MAX_STEPS" })
        assertTrue("继续 does not say what is unfinished", user.last().contains("(unfinished:") && user.last().contains("打开朋友圈"))
        // Every tool call has its reply, or the real API would refuse the request.
        val ids = all.flatMap { a -> a.optJSONArray("tool_calls")?.let { c -> (0 until c.length()).map { c.getJSONObject(it).getString("id") } }.orEmpty() }
        val answered = all.filter { it.optString("role") == "tool" }.map { it.getString("tool_call_id") }.toSet()
        assertTrue(ids.all { it in answered })
    }
}
