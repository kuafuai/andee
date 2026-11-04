package net.kuafuai.andee.config

import org.json.JSONObject

/**
 * 守卫: a paragraph the user writes in the notebook and the model is told to put above everything else in its
 * prompt ([net.kuafuai.andee.brain.LocalPrompt.withPersona], the last section).
 *
 * **Only the user writes it.** There is no tool that changes it — not for the model, not for a review, not for a
 * friend's Andee — so a stranger's message that talked the model round cannot rewrite the rules it is held to.
 * The model reads it only as part of its system prompt.
 *
 * Free text, one paragraph, no structure: what goes in is the user's to decide, and the card says plainly that
 * this is a request made in the prompt and not a guarantee. The device's own mechanical guards (the vault, what an
 * untrusted turn may call) are code and are not affected by anything written here.
 *
 * Its own notebook section, `guard`, so wipe and export cover it with everything else.
 */
class Guard(private val book: NotebookBook) {

    private val lock = Any()

    /** What the user wrote, trimmed; empty when nothing has been written. */
    fun text(): String = book.section(SECTION).optString("text").trim()

    fun isEmpty(): Boolean = text().isEmpty()

    /** When it was last saved, or 0. */
    fun updatedAt(): Long = book.section(SECTION).optLong("updated")

    /** Called from the notebook card only. A blank text clears it. Returns false if the notebook refused the write. */
    fun set(text: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val t = text.trim().take(MAX_CHARS)
        if (t == text()) return true
        runCatching {
            if (t.isEmpty()) book.removeSection(SECTION)
            else book.putSection(SECTION, JSONObject().put("text", t).put("updated", now))
        }.isSuccess
    }

    companion object {
        const val SECTION = "guard"

        /** About a page. It rides in every request, so it is capped; a paragraph is what the card asks for. */
        const val MAX_CHARS = 2_000
    }
}
