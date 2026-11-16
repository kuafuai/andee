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

/**
 * A report from another computer arrives as a wake turn while the phone is reading the previous answer aloud. If the user's
 * next words (or the follow-up mic that opens when the reading ends) start a turn of their own while the wake turn is
 * running, the wake turn used to be abandoned at its next checkpoint — no answer, no error, and nothing queued to run it
 * again, so the report was never dealt with. It now goes back to the queue and runs after the user's turn.
 *
 * Tapping the ball is the other thing and stays what it was: stopAll drops it for good.
 */
class SupersededWakeTest {
    /** The last user message of each request, in order, so a test can read which turns the model actually saw. */
    private val seen = mutableListOf<String>()
    private val answers = mutableListOf<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/chat/completions") { ex ->
            val body = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val msgs = body.getJSONArray("messages")
            var last = ""
            for (i in msgs.length() - 1 downTo 0) {
                val m = msgs.getJSONObject(i)
                if (m.optString("role") == "user") { last = m.optString("content"); break }
            }
            synchronized(seen) { seen += last }
            // The wake turn's request is slow, so a user turn can land while it is in flight.
            if (last.contains("REPORT")) Thread.sleep(700)
            // "ACT" turns first ask for a tool (which does something), and answer in prose once it has run.
            val lastRole = msgs.getJSONObject(msgs.length() - 1).optString("role")
            val msg = if (last.contains("ACT") && lastRole != "tool") {
                JSONObject().put("role", "assistant").put("content", "")
                    .put("tool_calls", JSONArray().put(JSONObject().put("id", "c" + System.nanoTime()).put("type", "function")
                        .put("function", JSONObject().put("name", "tap").put("arguments", "{}"))))
            } else {
                JSONObject().put("role", "assistant").put("content", "reply to: " + last.substringAfter("] ").take(14))
            }
            val out = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg)))
                .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1)).toString().toByteArray()
            runCatching { ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) } }
        }
        start()
    }
    private val trace = File.createTempFile("trace", "").also { it.delete(); it.mkdirs() }
    private val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BrainHost::class.java)) { _, m, args ->
        when (m.name) {
            "text" -> (args[0] as BrainText).name
            "locale" -> Locale.ENGLISH
            "maxSteps" -> 5
            "persona", "platformPrompt", "notebookIndex", "scenesIndex", "pagesIndex", "topicsIndex", "flowsIndex",
            "gearIndex", "planIndex", "friendsIndex", "agentIndex", "selfIndex", "stageIndex", "wikiIndex", "tasksIndex", "peopleIndex", "reviewRecord", "guard" -> ""
            "scrollback" -> emptyList<ScrollRow>()
            "toolLists" -> listOf(JSONArray().put(JSONObject().put("name", "tap").put("method", "screen.tap").put("description", "tap")
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))))
            "methodOf" -> if (args[0] == "tap") "screen.tap" else null
            "dispatch" -> { Thread.sleep(600); JSONObject().put("ok", true) }   // a tool that takes a moment, so the user can land in it
            "todoStillActive" -> true
            "traceDir" -> trace
            else -> null
        }
    } as BrainHost
    private val brain = LocalBrain(
        BrainConfig(BrainConfig.BRAIN_LOCAL, "key", "http://127.0.0.1:${server.address.port}", "m", false, "low"), host,
        onProgress = {}, onFinal = { synchronized(answers) { answers += it } },
    )

    @After fun tearDown() { brain.stop(); server.stop(0) }

    private fun reportTurnsSeen() = synchronized(seen) { seen.count { it.contains("REPORT") } }

    @Test fun aReportCutOffByTheUsersNextWordsRunsAfterThem() {
        brain.wake("REPORT: the worker finished", untrusted = true)
        Thread.sleep(250)                       // the report's request is in flight
        brain.submit("and one more thing")      // the follow-up mic heard something
        Thread.sleep(3500)
        val order = synchronized(seen) { seen.map { it.substringAfter("] ").take(6) } }
        assertTrue("the user's words were answered: $order", order.any { it.startsWith("and on") })
        assertEquals("the report ran again after the user's turn, once more: $order", 2, reportTurnsSeen())
        assertTrue("and it ran last: $order", synchronized(seen) { seen.last().contains("REPORT") })
        assertTrue("its answer was spoken", synchronized(answers) { answers.any { it.startsWith("reply to: REPORT") } })
    }

    @Test fun theBallsStopStillDropsItForGood() {
        brain.wake("REPORT: the worker finished", untrusted = true)
        Thread.sleep(250)
        brain.stopAll()
        Thread.sleep(2500)
        assertEquals("a stopped report does not come back", 1, reportTurnsSeen())
    }

    @Test fun aTurnThatAlreadyActedIsNotRunAgainFromTheTop() {
        // It tapped something before the user's words arrived: running it again would tap again.
        brain.wake("ACT on the report", untrusted = true)
        Thread.sleep(500)                       // inside the tool
        brain.submit("and one more thing")
        Thread.sleep(3500)
        val acts = synchronized(seen) { seen.count { it.contains("ACT on the report") } }
        assertEquals("the turn that had acted is not started again", 1, acts)
    }

    @Test fun aReportThatWasNotInterruptedRunsOnce() {
        brain.wake("REPORT: the worker finished", untrusted = true)
        Thread.sleep(2000)
        assertEquals(1, reportTurnsSeen())
        assertFalse(synchronized(answers) { answers.isEmpty() })
    }
}
