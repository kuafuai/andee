package net.kuafuai.andee.brain

import net.kuafuai.andee.config.Drive
import net.kuafuai.andee.config.Selfhood
import org.json.JSONArray

/**
 * A turn Andee starts itself, on a care that came due (`docs/design/inner-drive.zh-CN.md` §8): what it may use,
 * what it is told when it wakes, and how the device books what it did. The heartbeat ([Heartbeat]) decides
 * *whether*; this is the *what*.
 *
 * **What it may use is the first phase's boundary, and it is narrow on purpose.** No screen, no device, no
 * shell, nothing sent outward, nothing that interrupts: it can think, read and write its notebook, its cares,
 * its diary, the people it knows and its wiki, and leave a bubble. A heartbeat runs while the user is in front
 * of the device — so a work turn that drove the screen would take the phone out of their hand to go and look at
 * something nobody asked about. Reading a community, posting a draft and the rest need the errand's permission
 * table (§8.2), which does not exist yet; until then the turn is told plainly what it cannot do.
 *
 * **What it did is decided by the device, from its own tool calls (§5.2)**: a write that landed is
 * [Selfhood.Effect.ACTED], only reads is [Selfhood.Effect.LOOKED], nothing is [Selfhood.Effect.NOTHING]. Writing
 * back the care's own note is bookkeeping, not work, and does not count as acting on it.
 */
object SelfWork {

    /** Every `note.*` except the review-only fold, the destructive forget and sharing outward. */
    private val NOTE_EXCLUDED = setOf("note.fold", "note.forget", "note.wiki_share")

    /** Outside `note.*`: the bubble, and the read-only shelves. */
    private val OTHER_ALLOWED = setOf(
        "ui.nudge", "ui.list_pages", "ui.get_page", "ui.list_topics", "ui.get_topic", "gear.list", "scene.list", "scene.get",
    )

    fun allowed(method: String): Boolean =
        (method.startsWith("note.") && method !in NOTE_EXCLUDED) || method in OTHER_ALLOWED

    /** [lists] cut down to what a work turn may hold. The hosts' default for [BrainHost.workTools]. */
    fun tools(lists: List<JSONArray>): List<JSONArray> = lists.map { a ->
        JSONArray().apply {
            for (i in 0 until a.length()) {
                val t = a.optJSONObject(i) ?: continue
                if (allowed(t.optString("method"))) put(t)
            }
        }
    }.filter { it.length() > 0 }

    /** Reads, whatever their action. */
    private val READS = setOf(
        "note.recall", "note.people", "note.wiki_search", "note.wiki_read",
        "ui.list_pages", "ui.get_page", "ui.list_topics", "ui.get_topic", "gear.list", "scene.list", "scene.get",
    )

    /** For the tools that both read and write, the actions that only read. */
    private val READ_ACTIONS = mapOf(
        "note.self" to setOf("", "get"),
        "note.cares" to setOf("", "list"),
        "note.diary" to setOf("", "read"),
        "note.person" to setOf("", "find", "may_reach"),
        "note.day_plan" to setOf("", "get"),
        "note.directions" to setOf("", "list"),
        "note.todos" to setOf("", "list"),
    )

    private fun isRead(c: LocalBrain.WorkCall): Boolean =
        c.method in READS || READ_ACTIONS[c.method]?.contains(c.action) == true

    /** The device's verdict on a finished work turn about [careId]. Its own care's write-back does not count. */
    fun effect(calls: List<LocalBrain.WorkCall>, careId: String): Selfhood.Effect {
        val real = calls.filter { it.effective && !(it.method == "note.cares" && it.action == "update") }
        return when {
            real.any { !isRead(it) } -> Selfhood.Effect.ACTED
            real.isNotEmpty() -> Selfhood.Effect.LOOKED
            else -> Selfhood.Effect.NOTHING
        }
    }

    /** Whether the turn wrote the care's own note back itself (so the device does not overwrite it). */
    private fun wroteBack(calls: List<LocalBrain.WorkCall>) = calls.any { it.effective && it.method == "note.cares" && it.action == "update" }

    /** The opening message, built by the device (§8). In English, like every instruction to the model; it answers in the user's language. */
    fun opening(care: Selfhood.Care, ratio: Double, why: String, now: Long = System.currentTimeMillis()): String = buildString {
        val since = if (care.lastTouched > 0) "${hours(now - care.lastTouched)} since you last touched it" else "you have not touched it yet"
        val pace = if (care.kind == "promise" && care.due > 0) "it is due ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(care.due)}"
        else "you mean to look at this about every ${hours(care.intervalMs)}"
        append("[You woke yourself: ").append(care.what).append(" — ").append("%.1f".format(ratio)).append("× overdue (")
            .append(since).append("; ").append(pace).append("). Care id ").append(care.id).append(", kind ").append(care.kind).append('.')
        if (care.why.isNotBlank()) append("\nWhy it matters to you: ").append(care.why)
        if (care.note.isNotBlank()) append("\nYour own words from last time: \"").append(care.note).append('"')
        if (care.idleRuns > 0) append("\nThe last ").append(care.idleRuns).append(" time(s) you woke for this, nothing came of it — try something different, or say why it should wait.")
        if (why.isNotBlank()) append("\nWhy you decided to get up for it now: ").append(why)
        if (care.links.isNotEmpty()) append("\nLinked: ").append(care.links.joinToString(", ")).append(" (read them before you write).")
        append("]\n\n")
        append(
            "Nobody asked for this and nobody is waiting: nothing you say is read aloud, and you may not ask or alert. " +
                "This turn has no screen, device or shell tools — you can think, read and write your notebook, cares, diary, " +
                "the people you know and your wiki, and leave one bubble (nudge) if something is genuinely worth their attention now. " +
                "Do the piece of this you can do with those: a plan or a piece of research written into the wiki (search first, revise " +
                "rather than duplicate, say where each claim comes from), a person's record brought up to date, a thought worth " +
                "keeping. If the real next step needs the screen or the user, say so in a bubble or leave it for when they talk to you. " +
                "Before you finish, write back: cares(action=update, id=${care.id}, note=…) with one sentence on where this stands " +
                "now, in your own voice. Your closing words are for your own record, not for anyone's ears.",
        )
    }

    private fun hours(ms: Long): String {
        val h = ms / 3_600_000.0
        return if (h < 48) "%.0f h".format(h.coerceAtLeast(1.0)) else "%.0f days".format(h / 24)
    }

    /**
     * Start a work turn on [care]: count it, run it, and book what it did. [onBooked] runs on the brain's loop
     * thread once the turn has ended, with the verdict — for the body's log and its scrollback row.
     */
    fun start(
        brain: LocalBrain,
        self: Selfhood,
        drive: Drive,
        care: Selfhood.Care,
        ratio: Double,
        why: String,
        onBooked: (Selfhood.Effect, String?) -> Unit = { _, _ -> },
    ) {
        drive.markWork()
        brain.work(opening(care, ratio, why), care.kind) { r ->
            val effect = effect(r.calls, care.id)
            val summary = r.summary?.trim()?.takeIf { it.isNotEmpty() }
            self.settle(care.id, effect, if (wroteBack(r.calls)) null else summary)
            drive.recordWork(care.id, care.what, effect.name.lowercase(), summary.orEmpty(), r.calls.filter { it.effective }.map { it.callId })
            onBooked(effect, summary)
        }
    }
}
