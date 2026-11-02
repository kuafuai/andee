package net.kuafuai.andee.stage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The stage: the middle of the home screen, which is Andee's own (`set_stage` / `update_stage` / `get_stage`;
 * LocalPrompt §7, "Your stage"). One store for both bodies — the phone's `filesDir/stage`, the Mac's
 * `Application Support/Andee/stage`.
 *
 * What is stored is only the *fragment* Andee wrote — the inside of the page. The shell around it is the
 * device's ([StageShell]), built at draw time, so it follows the theme without a rewrite and cannot be left out
 * or overridden by the model.
 *
 * `current.html` + `meta.json`, and the last [MAX_VERSIONS] fragments as `v<n>.html`, so a stage the user liked
 * is not one rewrite away from gone. Writes are capped at [MAX_WRITES_PER_HOUR]: the stage is a board, not a
 * feed, and a loop rewriting it is a bill.
 *
 * [onChanged] runs after every successful write, on the writer's thread: the body redraws, and the stage's care
 * is marked looked after ([net.kuafuai.andee.config.Selfhood.stageTouched]).
 */
class StageStore(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    data class State(val fragment: String, val title: String, val version: Int, val updatedAt: Long)

    @Volatile var onChanged: (() -> Unit)? = null

    private val lock = Any()
    private val writes = ArrayDeque<Long>()

    private fun d() = dir.apply { mkdirs() }

    fun current(): State? = synchronized(lock) {
        val f = File(d(), "current.html")
        if (!f.exists()) return null
        val meta = runCatching { JSONObject(File(d(), "meta.json").readText()) }.getOrNull() ?: JSONObject()
        State(f.readText(), meta.optString("title"), meta.optInt("version", 1), meta.optLong("updated", f.lastModified()))
    }

    /** Replace the whole stage. Returns null on success, or a sentence saying why not. */
    fun set(fragment: String, title: String?): String? {
        val err = synchronized(lock) {
            refuse(fragment) ?: run {
                val prev = current()
                write(fragment, title?.trim()?.takeIf { it.isNotEmpty() } ?: prev?.title.orEmpty(), (prev?.version ?: 0) + 1)
                null
            }
        }
        if (err == null) onChanged?.invoke()
        return err
    }

    /** Replace one `data-slot`'s inside. Returns null on success, or why not. */
    fun update(slot: String, inner: String): String? {
        val err = synchronized(lock) {
            val prev = current() ?: return@synchronized "The stage is empty; put something up with set_stage first."
            when (val r = StageSlots.replace(prev.fragment, slot, inner)) {
                is StageSlots.Result.Error -> r.reason
                is StageSlots.Result.Ok -> refuse(r.fragment) ?: run {
                    write(r.fragment, prev.title, prev.version + 1)
                    null
                }
            }
        }
        if (err == null) onChanged?.invoke()
        return err
    }

    /** Factory reset. */
    fun wipe() {
        synchronized(lock) { dir.deleteRecursively() }
        onChanged?.invoke()
    }

    /** The line that rides in each turn ([net.kuafuai.andee.brain.BrainHost.stageIndex]). */
    fun indexForPrompt(now: Long = clock()): String {
        val s = current()
            ?: return "Empty. It is your place and it is waiting for you: the first time you have a free moment, " +
                "put up a page about yourself with set_stage."
        val ago = (now - s.updatedAt) / 60_000
        val age = when {
            ago < 1 -> "just now"
            ago < 60 -> "$ago min ago"
            ago < 48 * 60 -> "${ago / 60} h ago"
            else -> "${ago / (24 * 60)} days ago"
        }
        val slots = StageSlots.names(s.fragment)
        return "Up now: «${s.title.ifEmpty { "untitled" }}» (v${s.version}, changed $age)" +
            (if (slots.isEmpty()) "; no slots" else "; slots: ${slots.joinToString()}") +
            ". update_stage changes one slot; get_stage reads it back."
    }

    /**
     * The three tools, for either body's dispatcher. Not under `screen.`: writing the stage drives no app and must
     * not fold anything. A refusal comes back as `error`, a sentence the model can act on.
     */
    fun handle(method: String, params: JSONObject?): JSONObject = when (method) {
        "stage.set" -> {
            val html = requireNotNull(params?.optString("html")?.ifEmpty { null }) { "stage.set requires html" }
            val err = set(html, params?.optString("title"))
            if (err != null) {
                JSONObject().put("error", err)
            } else {
                JSONObject().put("set", true).put("version", current()?.version ?: 0)
                    .put("slots", JSONArray(StageSlots.names(html)))
                    .put("note", "It is up on your stage now. Do not describe it again in your reply; say in a sentence that it is there if it is news to them.")
            }
        }
        "stage.update" -> {
            val slot = requireNotNull(params?.optString("slot")?.ifEmpty { null }) { "stage.update requires slot" }
            val err = update(slot, params?.optString("html") ?: "")
            if (err != null) JSONObject().put("error", err)
            else JSONObject().put("updated", slot).put("version", current()?.version ?: 0)
        }
        "stage.get" -> {
            val s = current()
            if (s == null) {
                JSONObject().put("empty", true).put("note", "Nothing is on your stage yet.")
            } else {
                JSONObject().put("empty", false).put("title", s.title).put("version", s.version)
                    .put("changed", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(s.updatedAt)))
                    .put("slots", JSONArray(StageSlots.names(s.fragment)))
                    .put("html", s.fragment)
            }
        }
        else -> throw IllegalArgumentException("unknown method $method")
    }

    /** Size and rate. Caller holds [lock]. */
    private fun refuse(fragment: String): String? {
        if (fragment.isBlank()) return "The stage content is empty."
        val bytes = fragment.toByteArray().size
        if (bytes > MAX_BYTES) return "That is ${bytes / 1024} KB; the stage takes up to ${MAX_BYTES / 1000} KB. Trim it."
        val now = clock()
        while (writes.isNotEmpty() && now - writes.first() > 3_600_000L) writes.removeFirst()
        if (writes.size >= MAX_WRITES_PER_HOUR) {
            return "The stage has been changed $MAX_WRITES_PER_HOUR times in the last hour, which is the limit. " +
                "Leave it as it is for now; it is a board, not a feed."
        }
        writes.addLast(now)
        return null
    }

    /** Caller holds [lock]. */
    private fun write(fragment: String, title: String, version: Int) {
        val d = d()
        runCatching {
            File(d, "v$version.html").writeText(fragment)
            File(d, "current.html").writeText(fragment)
            File(d, "meta.json").writeText(
                JSONObject().put("title", title).put("version", version).put("updated", clock()).toString(),
            )
            d.listFiles { f -> f.name.matches(Regex("v\\d+\\.html")) }
                ?.sortedBy { it.name.removePrefix("v").removeSuffix(".html").toInt() }
                ?.dropLast(MAX_VERSIONS)
                ?.forEach { it.delete() }
        }.onFailure { log("could not write the stage", it) }
    }

    companion object {
        const val MAX_BYTES = 60_000
        const val MAX_VERSIONS = 20
        const val MAX_WRITES_PER_HOUR = 30
        val METHODS = setOf("stage.set", "stage.update", "stage.get")
    }
}
