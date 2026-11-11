package net.kuafuai.andee.brain

import org.json.JSONArray
import org.json.JSONObject

/**
 * Where a conversation *can* be cut into topics, **on the device**.
 *
 * The split between the two halves of topic-folding is the whole design: the
 * model decides *which stretch is a finished topic*, and the device decides
 * *what that stretch is made of*. The model never names a message range — it
 * cannot count messages, and a range it invents can start inside a tool call,
 * which the API answers with a 400. It names two numbers from a list this file
 * builds: the user turns. Every cut lands on a user message, so no turn is ever
 * cut in half.
 *
 * An earlier version cut only at long silences (≥ [GAP_MS]) and a change of day,
 * and offered the model just those stretches. On the tablet that meant a person
 * who made a timer and then asked about the weather a minute later had one
 * stretch, nothing to fold, and a feature that looked broken. A change of
 * subject is a judgement about content, and content is the model's job; the gap
 * survives only as a hint printed in the list.
 *
 * Pure: no Android, no state, so the rules can be tested directly; see
 * `TopicSplitterTest`.
 */
object TopicSplitter {

    /**
     * A silence at least this long is marked in the turn list as a likely seam.
     * A hint for the model, never a cut by itself.
     */
    const val GAP_MS = 15 * 60_000L

    /**
     * Fewer messages than this is not worth folding: a two-message exchange
     * folded into a summary saves nothing and costs the same call.
     */
    const val MIN_SEGMENT_MESSAGES = 4

    /**
     * Indices in [history] of the messages the user actually said, oldest first
     * — the only places a topic may start or end.
     *
     * A user message counts only when it carries the device's `[now …]` stamp.
     * The other `user`-role messages are the device talking (a page was closed, a
     * meeting transcript arrived): they have no row of their own on the device,
     * so the fold could not find them there, and they are not where a person
     * changed the subject.
     */
    fun userTurns(history: List<JSONObject>): List<Int> =
        (1 until history.size).filter {
            isBoundary(history[it]) && (history[it].opt("content") as String).startsWith("[now ")
        }

    /** Whether a silence long enough to mark sits between two stamped messages. */
    fun isSeam(prev: JSONObject, next: JSONObject): Boolean {
        val a = stampOf(prev)
        val b = stampOf(next)
        if (a <= 0 || b <= 0) return false
        return b - a >= GAP_MS || dayOf(a) != dayOf(b)
    }

    /**
     * Whether [history] contains a message the brain asked a question into and
     * never got an answer for — an unclosed tool call.
     *
     * A stretch is only foldable when nothing in it is still waiting. This is
     * the mechanical half of "明确话题已经结束": a `tool_calls` with no matching
     * `tool` result behind it means the turn was interrupted, and folding a
     * stretch whose last act was an unanswered call would bury the one thing the
     * next turn needs to see.
     *
     * Counted rather than paired, because the only two states that matter are
     * "every call answered" and "not every call answered".
     */
    fun hasDanglingCall(history: List<JSONObject>, from: Int, until: Int): Boolean {
        var open = 0
        for (i in from.coerceAtLeast(0) until until.coerceAtMost(history.size)) {
            val m = history[i]
            when (m.optString("role")) {
                "assistant" -> open += (m.optJSONArray("tool_calls")?.length() ?: 0)
                "tool" -> open--
            }
        }
        return open > 0
    }

    /**
     * The `[now …]` clock out of a user message, or 0.
     *
     * The one place a wall-clock time exists in the history is that bracket —
     * see [LocalBrain.stamp] — and **it is not in a fixed format**: the stamp is
     * `yyyy-MM-dd EEE HH:mm` rendered in the *interface language*
     * (`AppLocale.localeOf`), so a Chinese device writes `2026-10-15 周四 18:30`
     * and an English one `2026-10-15 Thu 18:30`, and the weekday is three
     * characters of whatever the locale says. Parsing it with one pattern is
     * therefore wrong on half the installs, which is the kind of bug that only
     * shows up as "topics never split" on someone else's tablet.
     *
     * So only the two locale-independent fields are read: the `yyyy-MM-dd` date
     * and the `HH:mm` clock. Both are fixed-width and fixed-position, which makes
     * the split mechanical; the weekday between them is skipped by index rather
     * than understood.
     */
    fun stampOf(message: JSONObject): Long = stampOfText(message.opt("content") as? String)

    fun stampOfText(text: String?): Long {
        if (text.isNullOrEmpty()) return 0L
        val open = text.indexOf("[now ")
        if (open < 0) return 0L
        val close = text.indexOf(']', open)
        if (close < 0) return 0L
        // `2026-10-15 周四 18:30` / `2026-10-15 Thu 18:30`, optionally followed
        // by ` · ui-language zh`.
        val body = text.substring(open + 5, close)
        val date = body.substringBefore(' ').trim()
        val d = DATE_RE.matchEntire(date) ?: return 0L
        // The weekday sits between the date and the clock in every locale the
        // stamp is rendered in; dropping the first token of the remainder gets
        // past it without having to know what language it is in.
        val clock = body.removePrefix(date).trim()
            .substringAfter(' ', "")
            .trim()
            .substringBefore(" · ")
            .trim()
        if (clock.length != 5 || clock[2] != ':') return 0L
        val cal = java.util.Calendar.getInstance()
        cal.clear()
        cal.set(
            d.groupValues[1].toInt(),
            d.groupValues[2].toInt() - 1,
            d.groupValues[3].toInt(),
            clock.substring(0, 2).toInt(),
            clock.substring(3, 5).toInt(),
            0,
        )
        return cal.timeInMillis
    }

    private val DATE_RE = Regex("(\\d{4})-(\\d{2})-(\\d{2})")

    private fun dayOf(stamp: Long): Int {
        if (stamp <= 0) return -1
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = stamp
        return cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
    }

    private fun isBoundary(msg: JSONObject): Boolean =
        msg.optString("role") == "user" && msg.opt("content") is String

    /**
     * Opening words of every index section the device appends to a user message.
     *
     * Used by [anchorText] to cut them off. Kept in one place because a new
     * section that forgets to register here would silently become part of an
     * anchor and stop a fold from matching — a failure with no symptom until
     * someone notices a conversation was never put away.
     */
    private val INDEX_MARKERS = listOf(
        "(what your notebook holds",
        // Every section `LocalBrain.runTurn` appends after the words. The list stopped at the first four while the
        // message grew, so a folded topic's text carried the plan, the tasks and the people line of every turn.
        "(you, and your shelf",
        "(your plan",
        "(scenes you can enter",
        "(pages you have made",
        "(your stage",
        "(conversations you have finished",
        "(installed here and worth reaching for",
        "(other Andees you can ask",
        "(things you are in the middle of",
        "(people this may involve",
        "(where this turn came from",
        "(unfinished:",
    )

    /**
     * The words of a message with the device's own decorations stripped — the
     * one thing the brain's history and the device's row list have in common.
     *
     * **Why a fold needs this at all.** The two records of one conversation
     * share no id, no length and no clock: the brain's history carries tool
     * traffic the device never saw, and its timestamp rides in the message text
     * as `[now …]` rendered only to the minute while a row carries
     * `System.currentTimeMillis()`. The first version of the fold matched them
     * on those timestamps and silently found nothing, which is how it was found:
     * the tool reported success and no conversation was ever put away. The text
     * is the only field that means the same thing on both sides.
     *
     * Public and pure rather than private in `LocalBrain` so it can be tested;
     * see `TopicAnchorTest` for the matching it exists to make possible.
     */
    fun anchorText(message: JSONObject): String = anchorText(message.opt("content") as? String)

    fun anchorText(raw: String?): String =
        spokenText(raw).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /**
     * Everything the user said in a message, with the device's decorations — the
     * `[now …]` stamp and the index sections appended after the words — removed.
     * Unlike [anchorText] it keeps every line; it is what a folded topic keeps.
     */
    fun spokenText(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var t: String = raw.trim()
        if (t.startsWith("[now ")) {
            val close = t.indexOf(']')
            if (close >= 0) t = t.substring(close + 1)
        }
        // Everything from the first index section on is ours, not theirs.
        for (marker in INDEX_MARKERS) {
            val i = t.indexOf(marker)
            if (i >= 0) t = t.substring(0, i)
        }
        return t.trim()
    }

    /**
     * What the matcher needs of a device row, and nothing more — kept apart from
     * `ChatHistory.Entry` so [locateRows] stays pure and testable.
     */
    data class RowKey(val isUser: Boolean, val text: String, val ts: Long)

    /**
     * How far a row's clock may sit from the `[now …]` of the message it
     * corresponds to. The stamp is floored to the minute and is taken a little
     * after the row was written, so exact equality can never hold; five minutes
     * is generous for that and still far below [GAP_MS], so two neighbouring
     * stretches cannot be mistaken for each other.
     */
    const val MATCH_TOLERANCE_MS = 5 * 60_000L

    private const val ANCHOR_LEN = 40

    /**
     * Which device rows a stretch of the brain's history covers, as an index
     * range into [rows] — or null when it cannot be said with confidence.
     *
     * **Only the two *user* messages that bound the stretch are looked up:** the
     * one that opens it ([firstSaid]) and the one that opens the stretch after
     * it ([nextSaid]). Everything between them is the stretch. That is the whole
     * trick, and the reason it is not "first message to last message": the last
     * thing said in a stretch is usually the assistant's 「好的。」, a sentence
     * that occurs all over a conversation. Searching for it matches the final
     * one anywhere — in later stretches, in the one still going — and the fold
     * would mark live rows as belonging to the topic. A user sentence is far
     * more distinctive, and a pair of them bracketing the span leaves nothing to
     * search for in the middle.
     *
     * A user sentence can still repeat (「继续」, 「好的」), so ties are broken by
     * the clock: the row nearest the stamp, within [MATCH_TOLERANCE_MS]. With no
     * clock to go on ([firstAt]/[nextAt] ≤ 0, e.g. an unstamped note) the match
     * is accepted only when it is unique.
     *
     * Null is the safe answer and the caller treats it as "leave the
     * conversation as it was". A wrong range is not recoverable by anyone.
     */
    fun locateRows(
        rows: List<RowKey>,
        firstSaid: String,
        firstAt: Long,
        nextSaid: String,
        nextAt: Long,
    ): IntRange? {
        val start = pickUserRow(rows, firstSaid, firstAt, 0) ?: return null
        val next = pickUserRow(rows, nextSaid, nextAt, start + 1) ?: return null
        val end = next - 1
        return if (end >= start) start..end else null
    }

    private fun pickUserRow(rows: List<RowKey>, said: String, at: Long, from: Int): Int? {
        if (said.isEmpty()) return null
        val head = said.take(ANCHOR_LEN)
        val hits = (from until rows.size).filter { rows[it].isUser && rows[it].text.startsWith(head) }
        if (hits.isEmpty()) return null
        if (at <= 0) return hits.singleOrNull()
        val best = hits.minByOrNull { kotlin.math.abs(rows[it].ts - at) } ?: return null
        return if (kotlin.math.abs(rows[best].ts - at) <= MATCH_TOLERANCE_MS) best else null
    }
}

/**
 * Render [from] until [until] of a history as the text a topic keeps.
 *
 * Plain and complete on purpose: this is what `get_topic` hands back when the
 * user returns to a finished conversation, so it carries the words and nothing
 * else — no tool call ids, no JSON, no role prefixes the reader would have to
 * skip past. Tool traffic is summarised as a bracketed line because "it looked
 * something up here" is worth knowing and the arguments are not: they are
 * device chatter, and a screenshot payload inside a folded topic would be a
 * megabyte of base64 with the user's sentence nowhere in it.
 */
fun renderTopicText(history: List<JSONObject>, from: Int, until: Int): String {
    val sb = StringBuilder()
    for (i in from.coerceAtLeast(0) until until.coerceAtMost(history.size)) {
        val m = history[i]
        when (m.optString("role")) {
            "user" -> sb.append("我：").append(TopicSplitter.spokenText(m.opt("content") as? String)).append('\n')
            "assistant" -> {
                val text = m.opt("content") as? String
                if (!text.isNullOrBlank()) sb.append("他：").append(text.trim()).append('\n')
                val calls = m.optJSONArray("tool_calls")
                if (calls != null && calls.length() > 0) {
                    val names = (0 until calls.length()).mapNotNull {
                        calls.optJSONObject(it)?.optJSONObject("function")?.optString("name")
                    }.filter { it.isNotBlank() }
                    if (names.isNotEmpty()) sb.append("（用了：").append(names.joinToString("、")).append("）\n")
                }
            }
            "tool" -> {
                // Deliberately not the content: a tool result may be a base64
                // image or 24 000 characters of screen dump.
                val c = m.opt("content")
                val len = when (c) {
                    is String -> c.length
                    is JSONArray -> (0 until c.length()).sumOf { k ->
                        c.optJSONObject(k)?.optString("text")?.length ?: 0
                    }
                    else -> 0
                }
                if (len > 0) sb.append("（工具返回 ").append(len).append(" 字）\n")
            }
        }
        sb.append('\n')
    }
    return sb.toString().trim()
}
