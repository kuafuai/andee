package net.kuafuai.andee.brain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPrunerTest {

    private fun user(text: String) = JSONObject().put("role", "user").put("content", text)
    private fun call(id: String, name: String) = JSONObject().put("role", "assistant").put("content", "").put(
        "tool_calls",
        JSONArray().put(JSONObject().put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", "{}"))),
    )
    private fun result(id: String, text: String) = JSONObject().put("role", "tool").put("tool_call_id", id).put("content", text)
    private fun said(text: String) = JSONObject().put("role", "assistant").put("content", text)

    @Test fun oldBulkGoesTheNewestTurnAndTheWordsStay() {
        val big = "x".repeat(5_000)
        val u1 = user("[now 2026-11-11 Wed 10:00] 看看屏幕\n\n(your plan — …)\n" + "p".repeat(2_000))
        val u2 = user("[now 2026-11-11 Wed 10:05] 再看一次\n\n(your plan — …)\n" + "p".repeat(2_000))
        val h = listOf(
            JSONObject().put("role", "system").put("content", "sys"),
            u1, call("a", "get_screen_element"), result("a", big), result("a", "short"), said("看到了"),
            u2, call("b", "get_screen_element"), result("b", big), said("又看到了"),
        )
        val cores = mapOf(u1 to "[now 2026-11-11 Wed 10:00] 看看屏幕", u2 to "[now 2026-11-11 Wed 10:05] 再看一次")
        val kept = HashMap<JSONObject, String>()
        val saved = ContextPruner.prune(h, h.indexOf(u2), { m -> cores.entries.firstOrNull { it.key === m }?.value }) { m, o -> kept[m] = o }

        assertTrue(saved > 5_000)
        assertTrue(h[3].getString("content").startsWith(ContextPruner.STUB_PREFIX))
        assertTrue("the stub names the tool", h[3].getString("content").contains("get_screen_element"))
        assertEquals("a short result is left alone", "short", h[4].getString("content"))
        assertEquals("the old message keeps its clock and words", "[now 2026-11-11 Wed 10:00] 看看屏幕", u1.getString("content"))
        assertEquals("the assistant's words are never touched", "看到了", h[5].getString("content"))
        assertEquals("the newest turn is intact", big, h[8].getString("content"))
        assertTrue(u2.getString("content").contains("(your plan"))
        assertEquals("the originals are handed over first", big, kept[h[3]])
        assertTrue(kept[u1]!!.contains("(your plan"))
    }

    @Test fun pruningTwiceDoesNothingTheSecondTime() {
        val h = listOf(JSONObject().put("role", "system"), user("u"), call("a", "recall"), result("a", "y".repeat(3_000)), user("v"))
        assertTrue(ContextPruner.prune(h, 4, { null }) { _, _ -> } > 0)
        assertEquals(0, ContextPruner.prune(h, 4, { null }) { _, _ -> })
        assertFalse(h[3].getString("content").contains("yyyy"))
    }
}
