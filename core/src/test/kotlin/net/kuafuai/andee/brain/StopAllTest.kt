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

/**
 * A tap on the ball stops everything: the turn running, the turns queued behind it, and a wake still waiting to
 * start — and says what it stopped. Before, only the running turn stopped and the queue came back on its own.
 */
class StopAllTest {
    private val turns = mutableListOf<String>()
    private val events = mutableListOf<BrainEvent>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/chat/completions") { ex ->
            val body = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val msgs = body.getJSONArray("messages")
            synchronized(turns) { turns += msgs.getJSONObject(msgs.length() - 1).optString("content").take(40) }
            Thread.sleep(800) // slow enough to be stopped mid-way
            val out = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", "ok"))))
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
            "persona", "platformPrompt", "notebookIndex", "scenesIndex", "pagesIndex", "topicsIndex",
            "gearIndex", "planIndex", "friendsIndex", "selfIndex", "wikiIndex", "reviewRecord" -> ""
            "scrollback" -> emptyList<ScrollRow>()
            "toolLists" -> listOf(JSONArray())
            "event" -> { synchronized(events) { events += args[0] as BrainEvent }; null }
            "todoStillActive" -> true
            "traceDir" -> trace
            else -> null
        }
    } as BrainHost
    private val brain = LocalBrain(BrainConfig(BrainConfig.BRAIN_LOCAL, "key", "http://127.0.0.1:${server.address.port}", "m", false, "low"), host, onProgress = {}, onFinal = {})

    @After fun tearDown() { brain.stop(); server.stop(0) }

    @Test fun theQueueAndAWaitingWakeDoNotComeBack() {
        brain.submit("first job")
        Thread.sleep(200)
        brain.wake(WakeText.due("t1", "queued reminder", false))
        val epoch = brain.stopEpochNow()           // a wake that is still waiting to start
        Thread.sleep(200)
        brain.stopAll()
        brain.wake(WakeText.due("t2", "late reminder", false), epoch = epoch)
        Thread.sleep(2500)
        assertEquals(listOf("first job"), synchronized(turns) { turns.map { it.substringAfter("] ").take(9) } })
        val stopped = synchronized(events) { events.filter { it.kind == BrainEvent.Kind.STOPPED } }
        assertTrue(stopped.any { it.subject == "first job" && it.detail.contains("queued reminder") })
        assertTrue(stopped.any { it.detail.contains("late reminder") })
    }
}
