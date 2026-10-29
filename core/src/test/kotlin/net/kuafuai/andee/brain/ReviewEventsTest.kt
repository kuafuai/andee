package net.kuafuai.andee.brain

import com.sun.net.httpserver.HttpServer
import net.kuafuai.andee.config.BrainConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * A review is one row in the timeline, not one per write (10-29: a sweep and the harvest after it made eighteen
 * rows out of one quiet afternoon). Rewrites that changed nothing are not events at all.
 */
class ReviewEventsTest {

    @Test fun anUnchangedRewriteIsNotAnEventAndARewriteSaysSo() {
        assertNull(BrainEvent.fromTool("note.diary", JSONObject().put("action", "write"), JSONObject().put("written", true).put("unchanged", true)))
        val upd = BrainEvent.fromTool("note.remember", JSONObject().put("name", "x"), JSONObject().put("saved", true).put("overwrote", true))!!
        assertTrue(upd.updated)
        val fresh = BrainEvent.fromTool("note.remember", JSONObject().put("name", "y"), JSONObject().put("saved", true).put("overwrote", false))!!
        assertEquals(false, fresh.updated)
    }

    private val requests = mutableListOf<JSONObject>()
    private val events = mutableListOf<BrainEvent>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/chat/completions") { ex ->
            val n = synchronized(requests) { requests += JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8)); requests.size }
            fun call(id: String, args: String) = JSONObject().put("id", id).put("type", "function")
                .put("function", JSONObject().put("name", "remember").put("arguments", args))
            val msg = if (n == 1) {
                JSONObject().put("role", "assistant").put("content", "")
                    .put("tool_calls", JSONArray().put(call("a", """{"name":"m1"}""")).put(call("b", """{"name":"m2"}""")))
            } else JSONObject().put("role", "assistant").put("content", "done")
            val out = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg)))
                .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1)).toString().toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }
    private val trace = File.createTempFile("trace", "").also { it.delete(); it.mkdirs() }
    private val rows = (1..6).map { i -> ScrollRow(isUser = i % 2 == 1, isAssistant = i % 2 == 0, text = "line $i", ts = 1_000L * i, topicId = null, handle = i) }

    private val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BrainHost::class.java)) { _, m, args ->
        when (m.name) {
            "text" -> (args[0] as BrainText).name
            "locale" -> Locale.ENGLISH
            "maxSteps" -> 10
            "persona", "platformPrompt", "notebookIndex", "scenesIndex", "pagesIndex", "topicsIndex",
            "gearIndex", "planIndex", "friendsIndex", "selfIndex", "wikiIndex" -> ""
            "scrollback" -> rows
            "toolLists" -> listOf(JSONArray().put(JSONObject().put("name", "remember").put("method", "note.remember").put("description", "r")
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))))
            "methodOf" -> "note.remember"
            "dispatch" -> JSONObject().put("saved", true).put("overwrote", false)
            "event" -> { synchronized(events) { events += args[0] as BrainEvent }; null }
            "traceDir" -> trace
            else -> null
        }
    } as BrainHost

    private val brain = LocalBrain(
        BrainConfig(BrainConfig.BRAIN_LOCAL, "key", "http://127.0.0.1:${server.address.port}", "m", false, "low"),
        host, onProgress = {}, onFinal = {},
    )

    @After fun tearDown() { brain.stop(); server.stop(0) }

    @Test fun aReviewIsOneRowListingWhatItWrote() {
        brain.runSweep()
        val until = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20)
        while (synchronized(events) { events.isEmpty() } && System.currentTimeMillis() < until) Thread.sleep(50)
        Thread.sleep(300)
        val e = synchronized(events) { events.toList() }
        assertEquals(listOf(BrainEvent.Kind.REVIEWED), e.map { it.kind })
        assertEquals(listOf("m1", "m2"), e.single().items.map { it.subject })
    }
}
