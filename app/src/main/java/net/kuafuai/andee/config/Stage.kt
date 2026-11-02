package net.kuafuai.andee.config

import android.content.Context
import android.util.Log
import net.kuafuai.andee.stage.StageSlots
import org.json.JSONObject
import java.io.File

/**
 * The stage: the middle of the home screen, which is Andee's own (`set_stage` / `update_stage` /
 * `get_stage`; LocalPrompt §7, "Your stage").
 *
 * What is stored is only the *fragment* Andee wrote — the inside of the page. The shell around it
 * (charset, viewport, theme colours, the house classes, the `andee` bridge, the security policy) is the
 * device's, built at draw time by [net.kuafuai.andee.ui.StageView], so it follows the theme without a
 * rewrite and cannot be left out or overridden by the model.
 *
 * `filesDir/stage/current.html` + `meta.json`, and the last [MAX_VERSIONS] fragments as `v<n>.html`, so a
 * stage the user liked is not one rewrite away from gone. Writes are capped at [MAX_WRITES_PER_HOUR]: the
 * stage is a board, not a feed, and a loop rewriting it is a bill.
 */
object Stage {
    private const val TAG = "Stage"
    const val MAX_BYTES = 60_000
    const val MAX_VERSIONS = 20
    const val MAX_WRITES_PER_HOUR = 30

    data class State(val fragment: String, val title: String, val version: Int, val updatedAt: Long)

    private val lock = Any()
    private val writes = ArrayDeque<Long>()
    @Volatile private var listener: (() -> Unit)? = null

    /** The one view that draws it. Called after every write, on the writer's thread. */
    fun setListener(l: (() -> Unit)?) { listener = l }

    private fun dir(ctx: Context) = File(ctx.filesDir, "stage").apply { mkdirs() }

    fun current(ctx: Context): State? = synchronized(lock) {
        val f = File(dir(ctx), "current.html")
        if (!f.exists()) return null
        val meta = runCatching { JSONObject(File(dir(ctx), "meta.json").readText()) }.getOrNull() ?: JSONObject()
        State(f.readText(), meta.optString("title"), meta.optInt("version", 1), meta.optLong("updated", f.lastModified()))
    }

    /** Replace the whole stage. Returns null on success, or a sentence saying why not. */
    fun set(ctx: Context, fragment: String, title: String?): String? {
        val err = synchronized(lock) {
            refuse(fragment) ?: run {
                val prev = currentLocked(ctx)
                write(ctx, fragment, title?.trim()?.takeIf { it.isNotEmpty() } ?: prev?.title.orEmpty(), (prev?.version ?: 0) + 1)
                null
            }
        }
        if (err == null) changed(ctx)
        return err
    }

    /** Replace one `data-slot`'s inside. Returns null on success, or why not. */
    fun update(ctx: Context, slot: String, inner: String): String? {
        val err = synchronized(lock) {
            val prev = currentLocked(ctx) ?: return@synchronized "The stage is empty; put something up with set_stage first."
            when (val r = StageSlots.replace(prev.fragment, slot, inner)) {
                is StageSlots.Result.Error -> r.reason
                is StageSlots.Result.Ok -> refuse(r.fragment) ?: run {
                    write(ctx, r.fragment, prev.title, prev.version + 1)
                    null
                }
            }
        }
        if (err == null) changed(ctx)
        return err
    }

    /**
     * After a write: redraw, and mark the stage's care as looked after — a stage changed in conversation is
     * up to date, and the heartbeat must not get up an hour later to change it again.
     */
    private fun changed(ctx: Context) {
        listener?.invoke()
        runCatching { Notebook.selfhood(ctx).stageTouched() }.onFailure { Log.w(TAG, "could not touch the stage's care", it) }
    }

    /** Factory reset. */
    fun wipe(ctx: Context) {
        synchronized(lock) { File(ctx.filesDir, "stage").deleteRecursively() }
        listener?.invoke()
    }

    /** The line that rides in each turn ([net.kuafuai.andee.brain.BrainHost.stageIndex]). */
    fun indexForPrompt(ctx: Context, now: Long = System.currentTimeMillis()): String {
        val s = current(ctx)
            ?: return "Empty. It is your place and it is waiting for you: the first time you have a free moment, " +
                "put up a page about yourself with set_stage."
        val ago = (now - s.updatedAt) / 60_000
        val age = when {
            ago < 1 -> "just now"
            ago < 60 -> "${ago} min ago"
            ago < 48 * 60 -> "${ago / 60} h ago"
            else -> "${ago / (24 * 60)} days ago"
        }
        val slots = StageSlots.names(s.fragment)
        return "Up now: «${s.title.ifEmpty { "untitled" }}» (v${s.version}, changed $age)" +
            (if (slots.isEmpty()) "; no slots" else "; slots: ${slots.joinToString()}") +
            ". update_stage changes one slot; get_stage reads it back."
    }

    private fun currentLocked(ctx: Context): State? = current(ctx)

    /** Size and rate. Caller holds [lock]. */
    private fun refuse(fragment: String): String? {
        if (fragment.isBlank()) return "The stage content is empty."
        if (fragment.toByteArray().size > MAX_BYTES) {
            return "That is ${fragment.toByteArray().size / 1024} KB; the stage takes up to ${MAX_BYTES / 1000} KB. Trim it."
        }
        val now = System.currentTimeMillis()
        while (writes.isNotEmpty() && now - writes.first() > 3_600_000L) writes.removeFirst()
        if (writes.size >= MAX_WRITES_PER_HOUR) {
            return "The stage has been changed $MAX_WRITES_PER_HOUR times in the last hour, which is the limit. " +
                "Leave it as it is for now; it is a board, not a feed."
        }
        writes.addLast(now)
        return null
    }

    /** Caller holds [lock]. */
    private fun write(ctx: Context, fragment: String, title: String, version: Int) {
        val d = dir(ctx)
        runCatching {
            File(d, "v$version.html").writeText(fragment)
            File(d, "current.html").writeText(fragment)
            File(d, "meta.json").writeText(
                JSONObject().put("title", title).put("version", version).put("updated", System.currentTimeMillis()).toString(),
            )
            d.listFiles { f -> f.name.matches(Regex("v\\d+\\.html")) }
                ?.sortedBy { it.name.removePrefix("v").removeSuffix(".html").toInt() }
                ?.dropLast(MAX_VERSIONS)
                ?.forEach { it.delete() }
        }.onFailure { Log.w(TAG, "could not write the stage", it) }
    }
}
