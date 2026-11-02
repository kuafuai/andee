package net.kuafuai.andee.brain

import net.kuafuai.andee.config.BrainConfig
import net.kuafuai.andee.config.Plans
import org.json.JSONArray
import org.json.JSONObject

/**
 * The moment it thinks about its plan without being asked — `docs/design/proactive-nudge.zh-CN.md` §6.2: the
 * [muse], while they are around: of today's items that have only a loose time ("before lunch"), is now the
 * moment for one? At most one, usually none. **One call with no tools**, the way [NotificationTriage] is: the
 * system prompt plus ~55 tool descriptions of a full turn would cost forty times as much for a question this
 * small.
 *
 * Writing today's plan used to live here too (`morning`, a no-tool call on the plan tick). It is now the
 * morning default todo ([net.kuafuai.andee.config.NotebookBook.MORNING_TODO]): one ordinary turn that writes the
 * plan with `day_plan` and puts today on the stage, at a fixed time the user can see, move or pause.
 *
 * Fails closed: no key, a failed call, an unreadable answer → nothing changes. Not thinking is always safe here,
 * unlike triage, where it meant raising the signboard.
 *
 * Blocks; call from a worker.
 */
class PlanMind(
    cfg: BrainConfig,
    private val plans: Plans,
    private val log: (warn: Boolean, msg: String) -> Unit = { _, _ -> },
) {

    private val ready = cfg.apiKey.isNotEmpty()

    private val llm = LlmClient(
        baseUrl = cfg.baseUrl,
        apiKey = cfg.apiKey,
        model = cfg.model,
        thinking = false,
        reasoningEffort = cfg.reasoningEffort,
    )

    /**
     * @return the id of the loose-time item whose moment is now, or null (the usual answer). The caller turns
     *   it into a bubble through [Plans.bubbleForItem], so the user's limits still apply.
     */
    fun muse(langTag: String): String? {
        if (!plans.museDue()) return null
        val context = plans.museContext() ?: return null
        plans.markMuse()
        if (!ready) return null
        val obj = ask(MUSE, "Interface language: $langTag\n\n$context", "muse") ?: return null
        return if (obj.optString("verdict") == "nudge") obj.optString("id").trim().ifEmpty { null } else null
    }

    private fun ask(system: String, user: String, what: String): JSONObject? {
        val reply = try {
            llm.complete(
                listOf(
                    JSONObject().put("role", "system").put("content", system),
                    JSONObject().put("role", "user").put("content", user),
                ),
                JSONArray(),
            )
        } catch (t: Throwable) {
            log(true, "$what call failed: ${t.message}")
            return null
        }
        val raw = reply.content.trim()
        log(false, "$what: ${raw.take(200)} (tokens ${reply.promptTokens}/${reply.completionTokens})")
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
    }

    companion object {
        private val MUSE = """
            You are Andee, deciding whether now is the moment to speak to the person you look after. You will be given the time, today's intent, what already happened today, and the plan items that have only a loose time ("before lunch", "in the afternoon").

            Reply with ONLY a JSON object — no prose, no code fence: {"verdict": "hold"} or {"verdict": "nudge", "id": "<item id>"}.

            Choose "nudge" only when the item's loose time clearly fits now and nothing that already happened today makes it wrong (they just declined something similar, it is past its moment, or today's "not today" covers it). Otherwise "hold" — holding is the usual answer, and a missed moment costs less than an interruption at the wrong one.
        """.trimIndent()
    }
}
