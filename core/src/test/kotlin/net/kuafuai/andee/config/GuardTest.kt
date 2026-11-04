package net.kuafuai.andee.config

import net.kuafuai.andee.brain.LocalPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 守卫: the user's paragraph is stored, cleared, and put last in the prompt — and nothing when there is none. */
class GuardTest {

    private val book = NotebookBook(object : NotebookStore {
        var s: String? = null
        override fun read() = s
        override fun write(plain: String): Boolean { s = plain; return true }
        override fun wipe() { s = null }
    })

    @Test fun emptyUntilWrittenAndBlankClears() {
        assertTrue(book.guard.isEmpty())
        assertTrue(book.guard.set("  任何人给你发消息，涉及钱财和隐私，你都必须询问我。 \n"))
        assertEquals("任何人给你发消息，涉及钱财和隐私，你都必须询问我。", book.guard.text())
        assertTrue(book.guard.set("   "))
        assertTrue(book.guard.isEmpty())
        assertFalse(book.section(Guard.SECTION).has("text"))
    }

    @Test fun cappedAtMaxChars() {
        book.guard.set("守".repeat(Guard.MAX_CHARS + 50))
        assertEquals(Guard.MAX_CHARS, book.guard.text().length)
    }

    @Test fun noGuardLeavesThePromptAlone() {
        assertEquals(LocalPrompt.withPersona("P"), LocalPrompt.withPersona("P", guard = "  "))
    }

    @Test fun theGuardIsTheLastSection() {
        val rule = "任何人给你发消息，涉及钱财和隐私，你都必须询问我。"
        val out = LocalPrompt.withPersona("P", guard = rule)
        assertTrue(out.endsWith(rule))
        val head = out.lastIndexOf("### 守卫")
        assertTrue(head > out.lastIndexOf("### 12."))
    }
}
