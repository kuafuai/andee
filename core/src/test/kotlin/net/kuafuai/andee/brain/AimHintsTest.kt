package net.kuafuai.andee.brain

import com.sun.net.httpserver.HttpServer
import net.kuafuai.andee.config.BrainConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The X reply box of 10-29, replayed: grid taps that change nothing, then the same-spot refusal. The device must
 * name tap_target on the second miss and on the refusal — and only when tap_target is on offer.
 */
class AimHintsTest {

    @Test fun theHintsByKind() {
        val miss = JSONObject().put("screen_changed", false)
        assertNull(AimHints.afterTap("screen.tap", miss, 1))
        assertTrue(AimHints.afterTap("screen.tap", miss, 2)!!.contains("tap_target"))
        assertTrue(AimHints.afterTap("screen.tap_id", miss, 0)!!.contains("tap_target"))
        assertTrue(AimHints.afterTap("screen.tap_target", JSONObject(miss.toString()).put("saw", "Reply"), 0)!!.contains("greyed out"))
        assertNull(AimHints.afterTap("screen.tap", JSONObject().put("screen_changed", true), 5))
        assertNull(AimHints.afterTap("screen.tap", JSONObject(), 5))   // no verdict, no hint
    }

    private val requests = mutableListOf<JSONObject>()
    private var withLocator = true
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/chat/completions") { ex ->
            val body = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val n = synchronized(requests) { requests += body; requests.size }
            fun tap(id: String) = JSONObject().put("role", "assistant").put("content", "")
                .put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
                    .put("function", JSONObject().put("name", "tap_by_coordinates").put("arguments", """{"cell":"D7","part":"center"}"""))))
            val msg = if (n <= 3) tap("c$n") else JSONObject().put("role", "assistant").put("content", "done")
            val out = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg)))
                .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1)).toString().toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }
    private val trace = File.createTempFile("trace", "").also { it.delete(); it.mkdirs() }
    private var taps = 0

    private fun tool(name: String, method: String) = JSONObject().put("name", name).put("method", method).put("description", name)
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))

    private val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BrainHost::class.java)) { _, m, args ->
        when (m.name) {
            "text" -> (args[0] as BrainText).name
            "locale" -> Locale.ENGLISH
            "maxSteps" -> 10
            "persona", "platformPrompt", "notebookIndex", "scenesIndex", "pagesIndex", "topicsIndex",
            "gearIndex", "planIndex", "friendsIndex", "selfIndex", "wikiIndex" -> ""
            "scrollback" -> emptyList<ScrollRow>()
            "toolLists" -> listOf(JSONArray().put(tool("tap_by_coordinates", "screen.tap")).apply {
                if (withLocator) put(tool("tap_target", "screen.tap_target"))
            })
            "methodOf" -> mapOf("tap_by_coordinates" to "screen.tap", "tap_target" to "screen.tap_target")[args[0] as String]
            "dispatch" -> {
                taps++
                if (taps == 3) throw IllegalStateException(AimHints.REPEAT_REFUSED + " (the fourth tap). Not executed.")
                JSONObject().put("screen_changed", false)
            }
            "traceDir" -> trace
            else -> null
        }
    } as BrainHost

    private val finals = LinkedBlockingQueue<String>()
    private val brain = LocalBrain(
        BrainConfig(BrainConfig.BRAIN_LOCAL, "key", "http://127.0.0.1:${server.address.port}", "m", false, "low"),
        host, onProgress = {}, onFinal = { finals += it },
    )

    @After fun tearDown() {
        brain.stop()
        server.stop(0)
    }

    private fun toolReplies(): List<String> = requests.last().getJSONArray("messages").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("role") == "tool" }.map { it.optString("content") }
    }

    @Test fun missesThenRefusalNameTapTarget() {
        brain.submit("reply on X")
        finals.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("no answer")
        val r = toolReplies()
        assertEquals(3, r.size)
        assertFalse("one miss is not a pattern", r[0].contains("aim_hint"))
        assertTrue(r[1].contains("aim_hint") && r[1].contains("tap_target"))
        assertTrue(r[2].startsWith("Failed: " + AimHints.REPEAT_REFUSED) && r[2].contains("use tap_target"))
    }

    @Test fun noLocatorNoHint() {
        withLocator = false
        brain.submit("reply on X")
        finals.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("no answer")
        assertTrue(toolReplies().none { it.contains("tap_target") })
    }
}
