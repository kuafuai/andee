package net.kuafuai.andee.brain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NudgeBookTest {

    private var now = 1_000_000_000L
    private val book = NudgeBook { now }

    private fun nudge(kind: String = "water", vararg buttons: String, dismiss: String? = null): Nudge =
        book.parse(
            JSONObject().put("text", "该喝水啦").put("kind", kind)
                .apply { if (buttons.isNotEmpty()) put("buttons", JSONArray(buttons.toList())) }
                .apply { if (dismiss != null) put("dismiss", dismiss) },
            untrusted = false,
        )

    @Test fun parsesDefaults() {
        val n = book.parse(JSONObject().put("text", " 该喝水啦 "), untrusted = true)
        assertEquals("该喝水啦", n.text)
        assertEquals(emptyList<String>(), n.buttons)
        assertEquals("other", n.kind)
        assertEquals(now + 30 * 60_000L, n.expiresAt)
        assertTrue(n.untrusted)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsDismissNotAmongButtons() {
        nudge("meal", "好", "不用", dismiss = "算了")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsFourButtons() {
        nudge("meal", "a", "b", "c", "d")
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsLongText() {
        book.parse(JSONObject().put("text", "字".repeat(NudgeBook.MAX_TEXT + 1)), false)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTtlOutOfRange() {
        book.parse(JSONObject().put("text", "x").put("ttl_minutes", 721), false)
    }

    @Test fun offRefusesEverything() {
        assertNotNull(book.admit(nudge(), NudgeBook.Level.OFF, emptySet()))
    }

    @Test fun mutedKindRefused() {
        assertNotNull(book.admit(nudge("water"), NudgeBook.Level.NORMAL, setOf("water")))
        assertNull(book.admit(nudge("meal"), NudgeBook.Level.NORMAL, setOf("water")))
    }

    @Test fun hourlyBudgetAtFew() {
        assertNull(book.admit(nudge("a"), NudgeBook.Level.FEW, emptySet()))
        assertNotNull(book.admit(nudge("b"), NudgeBook.Level.FEW, emptySet()))
        now += NudgeBook.HOUR_MS
        assertNull(book.admit(nudge("b"), NudgeBook.Level.FEW, emptySet()))
    }

    @Test fun dailyBudgetAtFew() {
        repeat(4) {
            assertNull(book.admit(nudge("k$it"), NudgeBook.Level.FEW, emptySet()))
            now += NudgeBook.HOUR_MS
        }
        assertNotNull(book.admit(nudge("k9"), NudgeBook.Level.FEW, emptySet()))
        now += NudgeBook.DAY_MS
        assertNull(book.admit(nudge("k9"), NudgeBook.Level.FEW, emptySet()))
    }

    @Test fun sameKindCoolsDown() {
        assertNull(book.admit(nudge("water"), NudgeBook.Level.NORMAL, emptySet()))
        now += 40 * 60_000L
        assertNotNull(book.admit(nudge("water"), NudgeBook.Level.NORMAL, emptySet()))
        assertNull(book.admit(nudge("meal"), NudgeBook.Level.NORMAL, emptySet()))
        now += 10 * 60_000L
        // Two in the last hour at NORMAL (water at -50, meal at -10) — the hourly cap, not the cooldown.
        assertNotNull(book.admit(nudge("water"), NudgeBook.Level.NORMAL, emptySet()))
    }

    @Test fun refusalDoesNotSpendBudget() {
        assertNotNull(book.admit(nudge("water"), NudgeBook.Level.NORMAL, setOf("water")))
        assertNull(book.admit(nudge("a"), NudgeBook.Level.NORMAL, emptySet()))
        assertNull(book.admit(nudge("b"), NudgeBook.Level.NORMAL, emptySet()))
    }

    @Test fun levelFallbacks() {
        assertEquals(NudgeBook.Level.FEW, NudgeBook.Level.of(null))
        assertEquals(NudgeBook.Level.FEW, NudgeBook.Level.of(""))
        assertEquals(NudgeBook.Level.NORMAL, NudgeBook.Level.of("normal"))
        assertEquals(NudgeBook.Level.OFF, NudgeBook.Level.of("chatty"))
    }

    @Test fun answerTextNamesTheButton() {
        val n = nudge("meal", "好", "不用", dismiss = "不用")
        assertTrue(NudgeBook.answerText(n, "好").contains("tapped \"好\""))
        assertTrue(NudgeBook.answerText(n, null).contains("bubble itself"))
    }
}
