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
import java.net.InetSocketAddress

/** The three-state setting on the wire, including the history thinking models require back. */
class LlmClientThinkingTest {
    private val bodies = mutableListOf<JSONObject>()
    @Volatile private var requireCompleteReasoning = false
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/chat/completions") { ex ->
            val body = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            synchronized(bodies) { bodies += body }
            val n = synchronized(bodies) { bodies.size }
            val messages = body.optJSONArray("messages") ?: JSONArray()
            val incomplete = (0 until messages.length()).any { i ->
                val message = messages.optJSONObject(i)
                message?.optString("role") == "assistant" &&
                    message.optString("reasoning_content").isEmpty()
            }
            val thinkingOff = body.optJSONObject("thinking")?.optString("type") == "disabled"
            if (requireCompleteReasoning && body.optJSONArray("tools") != null && incomplete && !thinkingOff) {
                val error = JSONObject().put("error", JSONObject()
                    .put("message", "reasoning_content is required for every assistant message with tools"))
                    .toString().toByteArray()
                ex.sendResponseHeaders(400, error.size.toLong())
                ex.responseBody.use { it.write(error) }
                return@createContext
            }
            val msg = JSONObject()
                .put("role", "assistant")
                .put("content", "done")
                .put("reasoning_content", "reasoning-$n")
            val out = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg)))
                .toString().toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }

    @After fun tearDown() = server.stop(0)

    private fun client(thinking: String, effort: String = "high") = LlmClient(
        "http://127.0.0.1:${server.address.port}", "key", "model", thinking, effort,
    )

    private fun call(
        client: LlmClient,
        messages: List<JSONObject> = listOf(JSONObject().put("role", "user").put("content", "hi")),
        tools: JSONArray = JSONArray(),
    ) = client.complete(messages, tools)

    @Test fun autoOmitsBothControlsRatherThanInventingAWireValue() {
        call(client(BrainConfig.THINKING_AUTO))
        val body = synchronized(bodies) { bodies.single() }
        assertFalse(body.has("thinking"))
        assertFalse(body.has("reasoning_effort"))
    }

    @Test fun enabledCanLeaveEffortToTheModel() {
        call(client(BrainConfig.THINKING_ENABLED, BrainConfig.EFFORT_AUTO))
        val body = synchronized(bodies) { bodies.single() }
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertFalse(body.has("reasoning_effort"))
    }

    @Test fun enabledSendsAnExplicitEffort() {
        call(client(BrainConfig.THINKING_ENABLED, "medium"))
        val body = synchronized(bodies) { bodies.single() }
        assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        assertEquals("medium", body.getString("reasoning_effort"))
    }

    @Test fun disabledStillForcesThinkingOff() {
        call(client(BrainConfig.THINKING_DISABLED))
        val body = synchronized(bodies) { bodies.single() }
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertFalse(body.has("reasoning_effort"))
    }

    @Test fun retuneTakesEffectWithoutANewClient() {
        val c = client(BrainConfig.THINKING_DISABLED)
        call(c)
        c.retune("model", BrainConfig.THINKING_AUTO, "low")
        call(c)
        val second = synchronized(bodies) { bodies[1] }
        assertFalse(second.has("thinking"))
        assertFalse(second.has("reasoning_effort"))
    }

    @Test fun assistantReasoningIsReplayedOnTheNextRequest() {
        val c = client(BrainConfig.THINKING_AUTO)
        val first = call(c)
        assertEquals("reasoning-1", first.assistantMessage.getString("reasoning_content"))
        val tools = LlmClient.toolSpecs(JSONArray().put(JSONObject()
            .put("name", "tap")
            .put("description", "tap")
            .put("parameters", JSONObject().put("type", "object"))))
        call(c, listOf(
            JSONObject().put("role", "user").put("content", "hi"),
            first.assistantMessage,
            JSONObject().put("role", "user").put("content", "and then?"),
        ), tools)
        val second = synchronized(bodies) { bodies[1] }
        assertTrue(second.getJSONArray("tools").length() > 0)
        val history = second.getJSONArray("messages")
        assertEquals("reasoning-1", history.getJSONObject(1).getString("reasoning_content"))
    }

    @Test fun restoredAndHarvestedHistoryKeepsItsWordsAndToolsOnAProtocolFallback() {
        requireCompleteReasoning = true
        val tools = LlmClient.toolSpecs(JSONArray().put(JSONObject()
            .put("name", "read_earlier")
            .put("description", "read the archive")
            .put("parameters", JSONObject().put("type", "object"))))
        val messages = listOf(
            JSONObject().put("role", "system").put("content", "system"),
            JSONObject().put("role", "user").put("content", "before the restart"),
            JSONObject().put("role", "assistant").put("content", "The device restarted; earlier tool results are gone."),
            JSONObject().put("role", "assistant").put("content", "Earlier conversation is archived as #4."),
            JSONObject().put("role", "user").put("content", "continue"),
        )
        val reply = call(client(BrainConfig.THINKING_AUTO), messages, tools)
        val requests = synchronized(bodies) { bodies.toList() }
        assertEquals(2, requests.size)
        assertFalse(requests[0].has("thinking"))
        assertEquals("disabled", requests[1].getJSONObject("thinking").getString("type"))
        assertEquals(requests[0].getJSONArray("messages").toString(), requests[1].getJSONArray("messages").toString())
        assertEquals(requests[0].getJSONArray("tools").toString(), requests[1].getJSONArray("tools").toString())
        assertTrue(reply.thinkingFallback)
        assertEquals("done", reply.content)
    }

    @Test fun forcedThinkingUsesTheSameOneRequestFallbackAndDropsEffort() {
        requireCompleteReasoning = true
        val tools = LlmClient.toolSpecs(JSONArray().put(JSONObject()
            .put("name", "tap")
            .put("description", "tap")
            .put("parameters", JSONObject().put("type", "object"))))
        val reply = call(client(BrainConfig.THINKING_ENABLED, "high"), listOf(
            JSONObject().put("role", "assistant").put("content", "stopped by the step guard"),
            JSONObject().put("role", "user").put("content", "continue"),
        ), tools)
        val requests = synchronized(bodies) { bodies.toList() }
        assertEquals(2, requests.size)
        assertEquals("enabled", requests[0].getJSONObject("thinking").getString("type"))
        assertEquals("high", requests[0].getString("reasoning_effort"))
        assertEquals("disabled", requests[1].getJSONObject("thinking").getString("type"))
        assertFalse(requests[1].has("reasoning_effort"))
        assertTrue(reply.thinkingFallback)
    }

    @Test fun oldExplicitSettingsSurviveAndUnknownValuesFallBackToAuto() {
        assertEquals(BrainConfig.THINKING_ENABLED, BrainConfig.normalizeThinking(" enabled "))
        assertEquals(BrainConfig.THINKING_DISABLED, BrainConfig.normalizeThinking("disabled"))
        assertEquals(BrainConfig.THINKING_AUTO, BrainConfig.normalizeThinking(""))
        assertEquals(BrainConfig.THINKING_AUTO, BrainConfig.normalizeThinking("something-new"))
        assertEquals(BrainConfig.EFFORT_AUTO, BrainConfig.normalizeEffort(""))
        assertEquals("high", BrainConfig.normalizeEffort("HIGH"))
    }
}
