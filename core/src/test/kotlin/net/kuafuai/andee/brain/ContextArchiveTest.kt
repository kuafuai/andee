package net.kuafuai.andee.brain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class ContextArchiveTest {

    private val dir = File.createTempFile("ctx", "").also { it.delete() }

    private fun call(id: String, name: String) = JSONObject().put("role", "assistant").put("content", "我查一下").put(
        "tool_calls",
        JSONArray().put(JSONObject().put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", "{\"q\":1}"))),
    )

    @Test fun renderKeepsWordsAndResultsButNotSecrets() {
        val u = JSONObject().put("role", "user").put("content", "[now 2026-11-11 Wed 10:00] 扫地机多少钱\n\n(your plan — …)\nplan")
        val pruned = JSONObject().put("role", "tool").put("tool_call_id", "a").put("content", ContextPruner.stub("market_search", 9))
        val sms = JSONObject().put("role", "tool").put("tool_call_id", "b").put("content", "验证码 482913")
        val text = ContextArchive.render(
            listOf(u, call("a", "market_search"), pruned, call("b", "read_sms"), sms),
            { m -> if (m === pruned) "石头 P10 2999 元" else null },
            { it == "read_sms" },
        )
        assertTrue(text.contains("[now 2026-11-11 Wed 10:00] 我：扫地机多少钱"))
        assertFalse("the index block is not the user's words", text.contains("(your plan"))
        assertTrue("a pruned result is archived from its original", text.contains("石头 P10 2999 元"))
        assertTrue(text.contains("（调用 market_search"))
        assertFalse("a code read from a text message is never written down", text.contains("482913"))
        assertTrue(text.contains("read_sms 的结果不留存"))
    }

    @Test fun writeListReadAndPage() {
        val a = ContextArchive(dir)
        val long = "[now 2026-11-11 Wed 10:00] 我：第一句\n" + "z".repeat(ContextArchive.READ_CHARS + 10)
        val id = a.write(long, "harvest", 1L, 2L, 30)!!
        assertTrue(a.list(Locale.CHINA).contains("#$id"))
        assertTrue(a.list(Locale.CHINA).contains("第一句"))
        val first = a.read(id, 0)!!
        assertEquals(ContextArchive.READ_CHARS, first.getString("text").length)
        val rest = a.read(id, first.getInt("next_offset"))!!
        assertTrue(rest.isNull("next_offset"))
        assertNull(a.read(999, 0))
    }

    @Test fun oldestGoFirst() {
        val a = ContextArchive(dir)
        repeat(ContextArchive.MAX_ARCHIVES + 3) { a.write("我：$it", "trim", 0, 0, 1) }
        assertNull(a.read(1, 0))
        assertTrue(a.read((ContextArchive.MAX_ARCHIVES + 3).toLong(), 0) != null)
        assertEquals(ContextArchive.MAX_ARCHIVES, dir.listFiles { f -> f.name.startsWith("ctx_") }!!.size)
    }

    @Test fun sessionRoundTrips() {
        val a = ContextArchive(dir)
        assertNull(a.loadSession())
        a.saveSession(ContextArchive.Session("summary #3", 42L))
        assertEquals(ContextArchive.Session("summary #3", 42L), ContextArchive(dir).loadSession())
    }
}
