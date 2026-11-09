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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `[quiet]` (LocalPrompt §10): a turn the model marks quiet says nothing from the marker on — not the line before
 * a tool, not the answer — and the answer becomes one timeline row that opens to the whole of it.
 */
class QuietTurnTest {
    private val spoken = mutableListOf<String>()
    private val events = mutableListOf<BrainEvent>()
    private var calls = 0
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/chat/completions") { ex ->
            ex.requestBody.readBytes()
            val n = synchronized(this@QuietTurnTest) { ++calls }
            val msg = if (n == 1) {
                JSONObject().put("role", "assistant").put("content", "[quiet]我把今天放到舞台上")
                    .put("tool_calls", JSONArray().put(JSONObject().put("id", "c1").put("type", "function")
                        .put("function", JSONObject().put("name", "tap").put("arguments", "{}"))))
            } else {
                JSONObject().put("role", "assistant").put("content", "[happy]今天的安排放上去了。三件事，下午那件最要紧。")
            }
            val out = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg)))
                .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1)).toString().toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }
    private val trace = File.createTempFile("trace", "").also { it.delete(); it.mkdirs() }
    private val quietEnded = CountDownLatch(1)

    private val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BrainHost::class.java)) { proxy, m, args ->
        when (m.name) {
            "text" -> (args[0] as BrainText).name
            "locale" -> Locale.ENGLISH
            "maxSteps" -> 10
            "persona", "platformPrompt", "notebookIndex", "scenesIndex", "pagesIndex", "topicsIndex",
            "gearIndex", "planIndex", "friendsIndex", "selfIndex", "stageIndex", "wikiIndex", "tasksIndex", "reviewRecord", "guard" -> ""
            "scrollback" -> emptyList<ScrollRow>()
            "toolLists" -> listOf(JSONArray().put(JSONObject().put("name", "tap").put("method", "screen.tap").put("description", "tap")
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))))
            "methodOf" -> if (args[0] == "tap") "screen.tap" else null
            "dispatch" -> JSONObject().put("ok", true)
            "todoStillActive" -> true
            "event" -> { synchronized(events) { events += args[0] as BrainEvent }; Unit }
            "traceDir" -> trace
            else -> null
        }
    } as BrainHost

    private val brain = LocalBrain(
        BrainConfig(BrainConfig.BRAIN_LOCAL, "key", "http://127.0.0.1:${server.address.port}", "m", false, "low"),
        host, onProgress = { spoken += it }, onFinal = { spoken += it },
        onQuietFinal = { quietEnded.countDown() },
    )

    @After fun tearDown() {
        brain.stop()
        server.stop(0)
    }

    @Test fun aQuietTurnSaysNothingAndLeavesARow() {
        brain.wake("[Reminder] morning")
        assertTrue("the quiet ending never came", quietEnded.await(20, TimeUnit.SECONDS))
        assertTrue("a quiet turn spoke: $spoken", spoken.isEmpty())
        val q = synchronized(events) { events.filter { it.kind == BrainEvent.Kind.QUIET } }
        assertEquals(1, q.size)
        assertEquals("今天的安排放上去了。", q[0].subject)
        assertEquals("今天的安排放上去了。三件事，下午那件最要紧。", q[0].detail)
        assertFalse(q[0].detail.contains("["))
    }

    @Test fun theMarkerIsStrippedInBothLanguages() {
        assertTrue(SpeechMood.isQuiet("好的 [quiet]"))
        assertTrue(SpeechMood.isQuiet("【安静】放好了"))
        assertFalse(SpeechMood.isQuiet("[happy]放好了 [END]"))
        assertEquals("放好了", SpeechMood.strip("【安静】放好了"))
        assertEquals("放好了", SpeechMood.strip("[quiet] 放好了"))
    }
}
