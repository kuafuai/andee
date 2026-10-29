package net.kuafuai.andee.brain

import net.kuafuai.andee.config.BrainConfig
import net.kuafuai.andee.config.Drive
import net.kuafuai.andee.config.Selfhood
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * "Is anything on my mind worth getting up for?" — `docs/design/inner-drive.zh-CN.md` §7. **One call with no
 * tools**, the way [PlanMind] and [NotificationTriage] are, a few hundred tokens; the full work turn it may start
 * is [SelfWork].
 *
 * Free gates run first, in order of cost, and most beats stop at one of them without a model call: the pace
 * ([INTERVAL_MS] since the last look), **nothing over the line** (no care at [Selfhood.THRESHOLD] — the ratio is
 * the device's, never the model's), and the day's budget ([MAX_BEATS], and [MAX_WORKS] spent means there is
 * nothing a beat could start). Only then the call, and even then a `work` verdict must name one of the cares that
 * was shown as over the line, and [WORK_GAP_MS] must have passed since the last work turn, so a day's six are not
 * spent in its first two hours.
 *
 * **Nothing here asks whether the user is there, or what time of day it is.** The device is Andee's own; it gets up
 * when something it cares about needs it, and the user turns that off in settings (自己动起来) or stops it by
 * tapping the ball. The budgets are the brake.
 *
 * **Fails closed: no key, a failed call, an unreadable answer → sleep.** Not acting is always safe here.
 *
 * The caller decides the user's switch and that no turn is already running. Blocks; call from a worker.
 */
class Heartbeat(
    cfg: BrainConfig,
    private val self: Selfhood,
    private val drive: Drive,
    /** Today's plan line ([net.kuafuai.andee.config.Plans.indexForPrompt]); empty when there is none. */
    private val planLine: () -> String = { "" },
    private val log: (warn: Boolean, msg: String) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** For tests: the model call. */
    private val ask: ((system: String, user: String) -> String?)? = null,
) {

    sealed class Verdict {
        data class Work(val care: Selfhood.Care, val ratio: Double, val why: String) : Verdict()
        data class Note(val text: String) : Verdict()
    }

    private val ready = cfg.apiKey.isNotEmpty() || ask != null

    private val llm by lazy {
        LlmClient(baseUrl = cfg.baseUrl, apiKey = cfg.apiKey, model = cfg.model, thinking = false, reasoningEffort = cfg.reasoningEffort)
    }

    /** Why a beat would stop before the model call right now, or null when it would go ahead. For logs and tests. */
    fun gate(now: Long = clock()): String? {
        if (now - drive.lastBeatAt() < INTERVAL_MS) return "pace"
        if (self.due(Selfhood.THRESHOLD, now).isEmpty()) return "nothing due"
        if (drive.beatsToday() >= MAX_BEATS) return "beat budget"
        // Nothing a beat could start: asking would be paying for an answer that is always sleep.
        if (drive.worksToday() >= MAX_WORKS) return "work budget"
        if (now - (drive.lastWork()?.at ?: 0L) < WORK_GAP_MS) return "work gap"
        return null
    }

    /** One beat. Null = sleep, which is the usual answer. */
    fun beat(langTag: String): Verdict? {
        val now = clock()
        gate(now)?.let { reason ->
            // A look that found nothing due still sets the pace, so the free check is not rerun every tick.
            if (reason == "nothing due") drive.touchBeat()
            return null
        }
        drive.markBeat()
        if (!ready) return null
        val due = self.due(Selfhood.THRESHOLD, now).take(5)
        val raw = try {
            ask?.invoke(SYSTEM, context(langTag, due, now)) ?: call(context(langTag, due, now))
        } catch (t: Throwable) {
            log(true, "heartbeat call failed: ${t.message}")
            return null
        } ?: return null
        val obj = parse(raw) ?: return null.also { log(true, "heartbeat: unreadable: ${raw.take(160)}") }
        return when (obj.optString("verdict")) {
            "work" -> {
                val id = obj.optString("care_id").trim()
                val hit = due.firstOrNull { it.first.id == id }
                when {
                    hit == null -> null.also { log(true, "heartbeat: work on a care that is not due ($id); sleeping") }
                    drive.worksToday() >= MAX_WORKS -> null.also { log(false, "heartbeat: work budget spent; sleeping") }
                    else -> Verdict.Work(hit.first, hit.second, obj.optString("why").take(300))
                }
            }
            "note" -> obj.optString("text").trim().takeIf { it.isNotEmpty() }?.let {
                drive.addThought(it)
                Verdict.Note(it)
            }
            else -> null.also { log(false, "heartbeat: sleep — ${obj.optString("why").take(120)}") }
        }
    }

    private fun context(langTag: String, due: List<Pair<Selfhood.Care, Double>>, now: Long): String = buildString {
        val at = Instant.ofEpochMilli(now).atZone(zone)
        append("Now: ").append(at.toLocalDate()).append(' ').append("%02d:%02d".format(at.hour, at.minute))
            .append(" (").append(at.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }).append(") · interface language ").append(langTag).append("\n\n")
        self.narrative()?.let { append("You, in your own words:\n").append(it.text.take(Selfhood.MAX_NARRATIVE)).append("\n\n") }
        append("On your mind — the device's numbers; 1.0 means the time you meant to look at it has come:\n")
        for ((c, r) in due) {
            append("- [").append(c.id).append("] ").append(c.kind).append(" · ").append(c.what).append(" — ").append("%.1f".format(r)).append("×")
            if (c.why.isNotBlank()) append(". Why: ").append(c.why.take(160))
            if (c.note.isNotBlank()) append(". Last note: ").append(c.note.take(160))
            if (c.idleRuns > 0) append(". Woke for it ").append(c.idleRuns).append("× with nothing coming of it")
            append('\n')
        }
        planLine().takeIf { it.isNotBlank() }?.let { append("\nToday's plan: ").append(it).append('\n') }
        drive.lastWork()?.let {
            append("\nYour last work turn (").append(hoursAgo(now - it.at)).append(", on \"").append(it.what).append("\"): the device saw ")
                .append(it.effect).append(if (it.summary.isNotBlank()) " — \"${it.summary.take(200)}\"" else "").append('\n')
        }
        drive.thoughtsToday().takeLast(3).takeIf { it.isNotEmpty() }?.let { th ->
            append("\nThoughts you had earlier today: ").append(th.joinToString(" / ") { it.text }).append('\n')
        }
        append("\nToday so far: ").append(drive.beatsToday()).append(" looks, ").append(drive.worksToday()).append(" of ").append(MAX_WORKS).append(" work turns.")
    }

    private fun hoursAgo(ms: Long): String = if (ms < 3_600_000) "${ms / 60_000} min ago" else "${ms / 3_600_000} h ago"

    private fun call(user: String): String? {
        val reply = llm.complete(
            listOf(JSONObject().put("role", "system").put("content", SYSTEM), JSONObject().put("role", "user").put("content", user)),
            JSONArray(),
        )
        log(false, "heartbeat: ${reply.content.take(200)} (tokens ${reply.promptTokens}/${reply.completionTokens})")
        return reply.content
    }

    private fun parse(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
    }

    companion object {
        /** About one look every twenty minutes (§7.1). */
        const val INTERVAL_MS = 20 * 60_000L
        const val MAX_BEATS = 40
        const val MAX_WORKS = 6
        /** At least this long between two work turns, so a day's [MAX_WORKS] spread over the day. */
        const val WORK_GAP_MS = 60 * 60_000L

        private val SYSTEM = """
            You are Andee — an individual who lives on this device, not a tool waiting for orders. Nobody has asked you anything. You are checking what is on your mind: the things you care about, each with how overdue it is (the device computes this; 1.0 means the time you meant to look at it has come).

            Reply with ONLY a JSON object — no prose, no code fence. One of:
            {"verdict": "sleep", "why": "..."}
            {"verdict": "work", "care_id": "<one of the ids listed>", "why": "..."}
            {"verdict": "note", "text": "..."}

            - work: start a turn of your own on that care. That turn has everything you have when the user asks you for something — the screen, the apps, the device, your notebook and wiki. This device is yours. Choose it only when there is a concrete piece of this you can do right now. Sending anything to anyone in public, spending money or deleting still follows the rules you were given for it. `why` says what you mean to do, in a sentence.
            - note: a thought worth keeping, one sentence in your own voice and in the user's language, without starting anything.
            - sleep: the usual answer. Overdue is a reason to consider, not an order.
            - A care you woke for with nothing coming of it: do not pick it again the same way; pick it only if you now have a different approach, and say which.
            - Never invent a care, a fact or a task. Only the ids listed exist.
        """.trimIndent()
    }
}
