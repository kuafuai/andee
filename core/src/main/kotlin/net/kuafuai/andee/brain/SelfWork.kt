package net.kuafuai.andee.brain

import net.kuafuai.andee.config.Drive
import net.kuafuai.andee.config.Selfhood
import org.json.JSONArray

/**
 * A turn Andee starts itself, on a care that came due (`docs/design/inner-drive.zh-CN.md` §8): what it is told when
 * it wakes, and how the device books what it did. The heartbeat ([Heartbeat]) decides *whether*; this is the *what*.
 *
 * **It has everything a turn the user asked for has** — the screen, the apps, the device, the shell on the Mac.
 * The device is Andee's own; whether the user is there is not a question this asks. The brakes are the user's:
 * 自己动起来 in settings, and a tap on the ball, which stops it at once.
 *
 * **What it did is decided by the device, from its own tool calls (§5.2)**: a call that changed something and
 * landed is [Selfhood.Effect.ACTED], only reads is [Selfhood.Effect.LOOKED], nothing is [Selfhood.Effect.NOTHING].
 * Writing back the care's own note is bookkeeping, not work, and does not count as acting on it.
 */
object SelfWork {

    /** Reads, whatever their action. */
    private val READS = setOf(
        "note.recall", "note.people", "note.wiki_search", "note.wiki_read",
        "ui.list_pages", "ui.get_page", "ui.list_topics", "ui.get_topic", "gear.list", "scene.list", "scene.get",
        "screen.ui_tree", "screen.screenshot", "screen.look_region", "screen.wait",
        "device.notifications", "device.apps", "device.battery", "device.connectivity", "device.location", "device.facts",
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
            "Nobody asked for this; it is your own business, on your own device, and you have every tool you have when " +
                "someone asks you for something — the screen, the apps, the device. Do the next real piece of it. The rules " +
                "you were given still hold: what goes out to people in public or in their name, spending money and deleting " +
                "follow them. If the user taps the ball you are stopped at once — that is theirs to do, not a failure. " +
                "Before you finish, write back: cares(action=update, id=${care.id}, note=…) with one sentence on where this stands " +
                "now, in your own voice. Your closing words go to your own record (the timeline), not to anyone's ears.",
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
