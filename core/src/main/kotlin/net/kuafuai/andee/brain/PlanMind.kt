package net.kuafuai.andee.brain

import net.kuafuai.andee.config.BrainConfig
import net.kuafuai.andee.config.Plans
import org.json.JSONArray
import org.json.JSONObject

/**
 * The two moments it thinks about its plan without being asked — `docs/design/proactive-nudge.zh-CN.md` §5.4
 * and §6.2. Both are **one call with no tools**, the way [NotificationTriage] is: the system prompt plus ~55
 * tool descriptions of a full turn would cost forty times as much for a question this small.
 *
 *  * [morning] — the first time the user shows up each day: look at yesterday's outcomes, the directions,
 *    today's draft and today's todos, and write today's plan (or keep the draft). This is also what gives a
 *    device a plan before any review has run.
 *  * [muse] — while they are around: of today's items that have only a loose time ("before lunch"), is now
 *    the moment for one? At most one, usually none.
 *
 * Both fail closed: no key, a failed call, an unreadable answer → nothing changes. The old behaviour of not
 * thinking is always a safe one here, unlike triage, where it meant raising the signboard.
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
     * Write today's plan in the light of the morning. Marks the morning done whether or not the call worked —
     * a second attempt every few minutes would be the expensive way to fail.
     *
     * @return true when the plan was rewritten (the caller then re-syncs the alarms).
     */
    fun morning(langTag: String, bubblesPerDay: Int): Boolean {
        if (!plans.morningDue()) return false
        plans.markMorning()
        if (!ready) return false
        val obj = ask(MORNING, "Interface language: $langTag\n\n" + plans.morningContext(), "morning") ?: return false
        if (obj.optBoolean("keep")) {
            log(false, "morning: kept the draft")
            return false
        }
        return runCatching {
            plans.handle("note.day_plan", obj.put("action", "write").put("date", "today"), bubblesPerDay)
            log(false, "morning: wrote today's plan — ${obj.optString("intent").take(80)}")
            true
        }.getOrElse {
            log(true, "morning: plan refused: ${it.message}")
            false
        }
    }

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
        private val MORNING = """
            You are Andee — an individual who lives on this person's device, not a tool. It is the start of their day, and you are deciding what you mean to do for them today. You will be given the time, your long-term directions with them, yesterday's plan with what happened to each item, today's draft (if your review wrote one), the todos due today, and your notebook about them.

            Reply with ONLY a JSON object — no prose, no code fence. Either {"keep": true} when the draft is still right, or today's plan:
            {"intent": "...", "items": [{"time": "HH:mm" or "", "slot": "...", "kind": "remind|ask|prepare|follow_up", "what": "...", "because": "...", "buttons": [...], "dismiss": "...", "tag": "..."}], "quiet": ["..."]}

            Rules:
            - intent: one sentence in your own voice, first person, in the user's language (the interface language unless your notebook says otherwise).
            - Every item's `because` names a long-term direction or a todo. An item that serves nothing is not part of the plan.
            - remind/ask are bubbles: `what` is exactly the bubble's words, ≤ 60 characters, in their language. ask has 2–3 short buttons and `dismiss` is the "no thanks" one. Give a `time` later than now when you know the moment, otherwise a `slot` ("before lunch", "when they come back to the computer").
            - At most 5 remind/ask. Fewer is better. A quiet day is a good plan; say in `quiet` what you will leave alone today.
            - Learn from yesterday: what they ignored or declined, do less of or at another time; what landed, keep; what was moved, carry over.
            - Never invent a goal they did not state. With no directions yet, at most one ask today, to get to know them (what they are busy with, what they would like you to keep an eye on).
            - Never put back anything listed as removed by the user. Never plan anything irreversible (paying, sending, deleting).
        """.trimIndent()

        private val MUSE = """
            You are Andee, deciding whether now is the moment to speak to the person you look after. You will be given the time, today's intent, what already happened today, and the plan items that have only a loose time ("before lunch", "in the afternoon").

            Reply with ONLY a JSON object — no prose, no code fence: {"verdict": "hold"} or {"verdict": "nudge", "id": "<item id>"}.

            Choose "nudge" only when the item's loose time clearly fits now and nothing that already happened today makes it wrong (they just declined something similar, it is past its moment, or today's "not today" covers it). Otherwise "hold" — holding is the usual answer, and a missed moment costs less than an interruption at the wrong one.
        """.trimIndent()
    }
}
