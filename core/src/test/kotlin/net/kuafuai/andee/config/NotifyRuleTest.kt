package net.kuafuai.andee.config

import com.sun.net.httpserver.HttpServer
import net.kuafuai.andee.brain.NotificationTriage
import net.kuafuai.andee.net.TurnGuards
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * The owner's rule for notifications: stored, guarded, switches notifications on, and reaches the triage — which is
 * the whole point, since the triage sees none of the conversation.
 */
class NotifyRuleTest {

    private val book = NotebookBook(object : NotebookStore {
        var s: String? = null
        override fun read() = s
        override fun write(plain: String): Boolean { s = plain; return true }
        override fun wipe() { s = null }
    })

    private var scope = "off"
    private val tool = NotifyRuleTool(book.notifyRule, scope = { scope }, enable = { scope = "chat" })

    private var sentSystem = ""
    private var sentUser = ""
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/chat/completions") { ex ->
            val msgs = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8)).getJSONArray("messages")
            sentSystem = msgs.getJSONObject(0).getString("content")
            sentUser = msgs.getJSONObject(1).getString("content")
            val msg = JSONObject().put("role", "assistant").put("content", """{"action":"act","line":"","why":"owner asked"}""")
            val out = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg)))
                .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1)).toString().toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }

    @After fun tearDown() = server.stop(0)

    @Test fun setStoresTheRuleAndTurnsNotificationsOn() {
        val r = tool.handle(JSONObject().put("action", "set").put("text", "  消息都帮我处理一下 "))
        assertTrue(r.getBoolean("written"))
        assertEquals("消息都帮我处理一下", book.notifyRule.text())
        assertEquals("chat", scope)
        assertTrue(r.has("note"))
        // Already on: nothing to say about it.
        assertFalse(tool.handle(JSONObject().put("action", "set").put("text", "群消息别管，私聊都回")).has("note"))
        tool.handle(JSONObject().put("action", "clear"))
        assertEquals("", book.notifyRule.text())
    }

    @Test fun onlyATurnTheUserStartedMayWriteIt() {
        TurnGuards.check("note.notify_rule", silent = false, untrusted = false)
        for ((silent, untrusted) in listOf(true to false, false to true)) {
            try {
                TurnGuards.check("note.notify_rule", silent = silent, untrusted = untrusted)
                throw AssertionError("allowed silent=$silent untrusted=$untrusted")
            } catch (_: IllegalStateException) {}
        }
    }

    @Test fun theTriageHearsTheRuleAndTheGuardAndWhetherItIsAGroup() {
        val cfg = BrainConfig(BrainConfig.BRAIN_LOCAL, "key", "http://127.0.0.1:${server.address.port}", "m", false, "low")
        val n = JSONObject().put("app", "微信").put("pkg", "com.tencent.xinWeChat").put("title", "").put("latest", "你收到了一条消息")
            .put("chat", "private")
        val v = NotificationTriage(cfg).decide(n, "zh", rule = "消息都帮我处理一下", guard = "涉及钱的先问我")
        assertEquals(NotificationTriage.Action.ACT, v?.action)
        assertTrue(sentSystem.contains("THE OWNER'S RULE FOR NOTIFICATIONS") && sentSystem.contains("消息都帮我处理一下"))
        assertTrue(sentSystem.contains("涉及钱的先问我"))
        assertTrue(sentUser.contains("Chat: one-to-one"))
        // And the owner's words never sit in the stranger's fence.
        assertFalse(sentUser.contains("消息都帮我处理一下"))
    }

    @Test fun withNoRuleTheTriagePromptIsUnchanged() {
        val cfg = BrainConfig(BrainConfig.BRAIN_LOCAL, "key", "http://127.0.0.1:${server.address.port}", "m", false, "low")
        NotificationTriage(cfg).decide(JSONObject().put("app", "x").put("latest", "y"), "zh")
        assertFalse(sentSystem.contains("THE OWNER'S RULE"))
        assertFalse(sentSystem.contains("THE OWNER'S RULES"))
    }

    @Test fun theToldNoteNamesTheAppAndTheKindOfChat() {
        val note = NotificationTriage.toldNote(JSONObject().put("app", "微信").put("chat", "group"), "微信有人发消息")
        assertTrue(note.contains("微信") && note.contains("a group chat") && note.contains("微信有人发消息"))
    }
}
