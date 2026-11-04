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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The step stream a body shows while a turn works ([WorkStep]): waiting, the thought, what it said, each action,
 * and Done — before the answer, so the folded row can sit above it.
 */
class WorkStepTest {
    private val log = mutableListOf<String>()
    private var calls = 0
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/chat/completions") { ex ->
            ex.requestBody.readBytes()
            val n = synchronized(this@WorkStepTest) { ++calls }
            val msg = if (n == 1) {
                JSONObject().put("role", "assistant").put("content", "[curious]我看一下屏幕")
                    .put("reasoning_content", "用户要查天气。先看屏幕上有什么。")
                    .put("tool_calls", JSONArray().put(JSONObject().put("id", "c1").put("type", "function")
                        .put("function", JSONObject().put("name", "tap").put("arguments", "{}"))))
            } else {
                JSONObject().put("role", "assistant").put("content", "明天晴，18 度。")
            }
            val out = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg)))
                .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1)).toString().toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }
    private val trace = File.createTempFile("trace", "").also { it.delete(); it.mkdirs() }
    private val answered = CountDownLatch(1)

    private val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BrainHost::class.java)) { _, m, args ->
        when (m.name) {
            "text" -> (args[0] as BrainText).name
            "locale" -> Locale.ENGLISH
            "maxSteps" -> 10
            "persona", "platformPrompt", "notebookIndex", "scenesIndex", "pagesIndex", "topicsIndex",
            "gearIndex", "planIndex", "friendsIndex", "selfIndex", "stageIndex", "wikiIndex", "reviewRecord", "guard" -> ""
            "scrollback" -> emptyList<ScrollRow>()
            "toolLists" -> listOf(JSONArray().put(JSONObject().put("name", "tap").put("method", "screen.tap").put("description", "tap")
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))))
            "methodOf" -> if (args[0] == "tap") "screen.tap" else null
            "dispatch" -> JSONObject().put("ok", true)
            "todoStillActive" -> true
            "traceDir" -> trace
            else -> null
        }
    } as BrainHost

    private val brain = LocalBrain(
        BrainConfig(BrainConfig.BRAIN_LOCAL, "key", "http://127.0.0.1:${server.address.port}", "m", false, "low"),
        host, onProgress = {}, onFinal = { synchronized(log) { log += "ANSWER" }; answered.countDown() },
        onStep = { s ->
            synchronized(log) {
                log += when (s) {
                    is WorkStep.Start -> "start"
                    is WorkStep.Waiting -> "wait ${s.step}"
                    is WorkStep.Thought -> "think ${s.text}"
                    is WorkStep.Said -> "said ${s.text}"
                    is WorkStep.Action -> "act NARRATE_TAP"
                    is WorkStep.Done -> "done ${s.steps}/${s.actions}"
                }
            }
        },
    )

    @After fun tearDown() {
        brain.stop()
        server.stop(0)
    }

    @Test fun aTurnShowsItsStepsAndClosesThemBeforeTheAnswer() {
        brain.submit("明天天气")
        assertTrue(answered.await(20, TimeUnit.SECONDS))
        val got = synchronized(log) { log.toList() }
        assertEquals(
            listOf("start", "wait 1", "think 用户要查天气。先看屏幕上有什么。", "said 我看一下屏幕", "act NARRATE_TAP", "wait 2", "done 2/1", "ANSWER"),
            got,
        )
    }
}
