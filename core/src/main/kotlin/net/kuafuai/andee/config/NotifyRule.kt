package net.kuafuai.andee.config

import org.json.JSONObject

/**
 * How the owner wants notifications handled, in their own words — 「消息都帮我处理一下」, 「群消息别管，私聊都回」.
 *
 * It exists because the notification triage ([net.kuafuai.andee.brain.NotificationTriage]) is a separate, tool-less
 * call that sees none of the conversation: an instruction said to the brain never reached it, so the user asked for
 * every message to be handled and the triage kept answering `ignore`. The rule is the bridge: written by the brain
 * when the user says it ([NotifyRuleTool]), read by the triage on every notification and put in its system message
 * as the owner's instruction.
 *
 * Only a turn the user started may write it ([net.kuafuai.andee.net.TurnGuards]): a stranger's message that could
 * set "reply to everything, pay what is asked" would have bought itself standing permission. The user can also edit
 * or clear it in Settings. Notebook section `notify_rule`, so wipe and export cover it.
 */
class NotifyRule(private val book: NotebookBook) {

    private val lock = Any()

    /** The rule, trimmed; empty when there is none. */
    fun text(): String = book.section(SECTION).optString("text").trim()

    fun updatedAt(): Long = book.section(SECTION).optLong("updated")

    /** Blank clears it. Returns false if the notebook refused the write. */
    fun set(text: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val t = text.trim().take(MAX_CHARS)
        if (t == text()) return true
        runCatching {
            if (t.isEmpty()) book.removeSection(SECTION)
            else book.putSection(SECTION, JSONObject().put("text", t).put("updated", now))
        }.isSuccess
    }

    companion object {
        const val SECTION = "notify_rule"

        /** It rides in every triage call, which is meant to be cheap. A few sentences. */
        const val MAX_CHARS = 600
    }
}

/**
 * `notify_rule` (`note.notify_rule`), one handler for both bodies. [scope] reads the body's notification setting
 * (`off` / `chat` / `all`) and [enable] turns it on to `chat` when it is off — a rule about handling messages is
 * worth nothing while the device is not reading them, and the user asking for it is the user asking for that.
 */
class NotifyRuleTool(
    private val rule: NotifyRule,
    private val scope: () -> String,
    private val enable: () -> Unit,
) {
    fun handle(p: JSONObject?): JSONObject {
        val a = p ?: JSONObject()
        return when (a.optString("action", "get")) {
            "get" -> JSONObject().put("rule", rule.text()).put("notifications", scope())
            "set" -> {
                val text = a.optString("text").trim()
                require(text.isNotEmpty()) { "set needs text: the rule, in the user's own words" }
                val before = rule.text()
                if (!rule.set(text)) return JSONObject().put("written", false).put("reason", "the notebook refused the write")
                val r = JSONObject().put("written", true).put("rule", rule.text())
                    .put("replaced", before.isNotEmpty()).put("unchanged", before == rule.text())
                if (scope() == "off") {
                    enable()
                    r.put("notifications", scope()).put(
                        "note",
                        "Notifications were switched off on this device, so the rule could not have done anything; " +
                            "they are now on for chat apps. Tell the user in a sentence.",
                    )
                } else {
                    r.put("notifications", scope())
                }
                r
            }
            "clear" -> {
                val had = rule.text().isNotEmpty()
                rule.set("")
                JSONObject().put("cleared", had)
            }
            else -> throw IllegalArgumentException("action is get, set or clear")
        }
    }

    companion object {
        const val METHOD = "note.notify_rule"
    }
}
