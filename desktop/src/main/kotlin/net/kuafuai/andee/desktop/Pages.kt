package net.kuafuai.andee.desktop

import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The desktop's pages — the phone's `ChatHistory` artifact shelf, the page half.
 *
 * `show_html` builds a page and the device keeps it, versioned the same way:
 * one artifact = one [groupId], each revision a monotonically increasing
 * [version], append-only (even a rollback is filed as the next version). The
 * newest 20 revisions of an artifact and the newest 100 page files survive;
 * a row whose file is gone is still a fact (the brain must say "it was made,
 * the bytes are not there", never "I never made that").
 *
 * Ownership of "which artifact is this a revision of" is decided here, never by
 * the model — the same rule as the phone. `get_page` arms the target, `show_html`
 * consumes it (explicit `revise` wins), and a turn boundary clears it, so a
 * turn that reads a page back and then draws two of them gets a revision and
 * then a new artifact, not two revisions of the first.
 */
object Pages {

    private const val TAG = "Pages"
    private const val MAX_PAGES = 100
    private const val MAX_VERSIONS = 20

    data class Page(val id: Long, val ts: Long, val title: String, val groupId: Long, val version: Int, val file: File?)

    private val lock = Any()
    private val entries = mutableListOf<Page>()
    private var nextId = 1L

    /** Overridable for tests; the shelf's files live under here. */
    @Volatile var base: File = AppDirs.root

    private val dir get() = File(base, "pages").apply { mkdirs() }
    private val indexFile get() = File(base, "pages.json")

    /** 清空聊天记录 / 恢复出厂: every page and version goes, and the shelf starts again from 1. */
    fun wipe() {
        synchronized(lock) {
            entries.clear()
            nextId = 1L
        }
        dir.listFiles()?.forEach { it.delete() }
        indexFile.delete()
        onChange()
    }

    init { load() }

    /** The id a `show_html` in this turn should file under; null = new artifact. Set by get_page, cleared each turn. */
    @Volatile private var armedGroup: Long? = null

    /** Fired on the UI thread's behalf after the shelf changes; the panel reads [artifacts] on it. */
    @Volatile var onChange: () -> Unit = {}

    /** For tests: point the shelf at a fresh directory and drop everything in memory. */
    fun resetForTest(b: File) {
        base = b
        synchronized(lock) { entries.clear(); nextId = 1L }
        armedGroup = null
        carriedGroup = null
    }

    /** A 继续修改 tap names the artifact before the turn exists; the turn's own start must not wipe it. */
    @Volatile private var carriedGroup: Long? = null
    fun armNextTurn(groupId: Long) { carriedGroup = groupId }

    /** For tests: re-read the index from [base]. */
    internal fun reloadForTest() { synchronized(lock) { entries.clear(); nextId = 1L }; load() }

    fun beginTurn() { armedGroup = carriedGroup; carriedGroup = null }
    fun armGroup(id: Long) { armedGroup = id }

    /** The phone's resolveRevisionTarget: explicit wins, and either way the target is consumed. */
    fun armOrResolve(explicit: Long?): Long? = resolve(explicit)

    /** The phone's resolveRevisionTarget: explicit wins, and either way the target is consumed. */
    private fun resolve(explicit: Long?): Long? {
        val g = explicit ?: armedGroup
        armedGroup = null
        return g
    }

    fun add(title: String, html: String, groupId: Long?): Page? {
        val (id, group, version) = synchronized(lock) {
            val id = nextId++
            val g = groupId ?: id
            Triple(id, g, nextVersionOf(g))
        }
        val file = runCatching { File(dir, "page_$id.html").also { it.writeText(html) } }.getOrElse {
            Log.w(TAG, "could not archive page: ${it.message}")
            return null
        }
        synchronized(lock) {
            entries.add(Page(id, System.currentTimeMillis(), title.trim(), group, version, file))
            trim()
            save()
        }
        onChange()
        return Page(id, System.currentTimeMillis(), title, group, version, file)
    }

    /** One row per artifact, newest available revision first — the model's list. */
    fun pages(): List<Page> = synchronized(lock) {
        entries.groupBy { it.groupId }.values
            .mapNotNull { revs -> revs.filter { it.file != null }.maxByOrNull { it.version } ?: revs.maxByOrNull { it.version } }
            .sortedByDescending { it.ts }
    }

    /** The panel's list: one row per artifact, only ones whose bytes are still there (a row that opens nothing is worse than a shorter list). */
    fun artifacts(): List<Page> = synchronized(lock) {
        entries.groupBy { it.groupId }.values
            .mapNotNull { revs -> revs.filter { it.file != null }.maxByOrNull { it.version } }
            .sortedByDescending { it.ts }
    }

    /** Any revision is addressable, not just the current one. */
    fun pageById(id: Long): Page? = synchronized(lock) { entries.firstOrNull { it.id == id } }

    fun versionsOf(groupId: Long): List<Page> = synchronized(lock) {
        entries.filter { it.groupId == groupId }.sortedByDescending { it.version }
    }

    fun currentOf(groupId: Long): Page? = synchronized(lock) {
        entries.filter { it.groupId == groupId && it.file != null }.maxByOrNull { it.version }
    }

    /** Make an older revision current again — as a new revision, so the history stays append-only. */
    fun restoreVersion(id: Long): Page? {
        val source = synchronized(lock) { entries.firstOrNull { it.id == id } } ?: return null
        val file = source.file ?: return null
        val html = runCatching { file.readText() }.getOrNull() ?: return null
        return add(source.title, html, groupId = source.groupId)
    }

    internal fun nextVersionOf(groupId: Long): Int = synchronized(lock) {
        entries.filter { it.groupId == groupId }.maxOfOrNull { it.version }?.plus(1) ?: 1
    }

    fun indexForPrompt(max: Int = 5): String {
        val recent = pages().take(max)
        if (recent.isEmpty()) return ""
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        return recent.joinToString("\n") { e ->
            val bytes = e.file?.length() ?: 0L
            val state = if (bytes > 0) "${(bytes + 1023) / 1024} KB" else "file gone"
            "- #${e.id} ${e.title} (v${e.version} · ${fmt.format(Date(e.ts))} · $state)"
        }
    }

    /** Caller holds [lock]. */
    private fun trim() {
        val overVersions = entries.groupBy { it.groupId }
            .flatMap { (_, revs) -> revs.sortedBy { it.version }.dropLast(MAX_VERSIONS) }
        for (old in overVersions) {
            old.file?.let { runCatching { it.delete(); PageThumbs.fileFor(it).delete() } }
            entries.removeIf { it.id == old.id }
        }
        var pages = 0
        for (i in entries.indices.reversed()) {
            val e = entries[i]
            if (e.file == null) continue
            pages++
            if (pages > MAX_PAGES) {
                runCatching { e.file.delete(); PageThumbs.fileFor(e.file).delete() }
                entries[i] = e.copy(file = null)
            }
        }
    }

    /**
     * An early build read `show_html`'s `revise` (a *page* id) as a *group* id, so a revision of
     * page 2 was filed under a group named "2" that nothing else belonged to, and the shelf showed
     * one artifact as two. A real group is always named after its own first page, so a group whose
     * name is the id of a page in a *different* group is exactly that mistake. Fold it into the
     * group it was meant for and renumber, so the history reads v1..vN in the order it was made.
     * Returns whether anything changed.
     */
    internal fun repairMisfiled(): Boolean = synchronized(lock) {
        var changed = false
        var moved = true
        while (moved) {
            moved = false
            val ownerOf = entries.associate { it.id to it.groupId }
            val stray = entries.groupBy { it.groupId }.keys.firstOrNull { g ->
                val owner = ownerOf[g]
                owner != null && owner != g && entries.none { it.id == g && it.groupId == g }
            } ?: break
            val target = ownerOf.getValue(stray)
            for (i in entries.indices) if (entries[i].groupId == stray) entries[i] = entries[i].copy(groupId = target)
            moved = true; changed = true
        }
        if (changed) {
            for ((_, revs) in entries.groupBy { it.groupId }) {
                revs.sortedWith(compareBy({ it.ts }, { it.id })).forEachIndexed { n, r ->
                    val i = entries.indexOfFirst { it.id == r.id }
                    entries[i] = entries[i].copy(version = n + 1)
                }
            }
            Log.i(TAG, "re-filed revisions that an earlier build had split into a second artifact")
        }
        changed
    }

    private fun save() = synchronized(lock) {
        val arr = JSONArray()
        for (e in entries) arr.put(
            JSONObject().put("id", e.id).put("ts", e.ts).put("title", e.title)
                .put("group", e.groupId).put("version", e.version).put("file", e.file != null),
        )
        runCatching {
            val tmp = File(indexFile.parentFile, "${indexFile.name}.tmp")
            tmp.writeText(JSONObject().put("pages", arr).put("next", nextId).toString())
            tmp.renameTo(indexFile)
        }.onFailure { Log.w(TAG, "save failed: ${it.message}") }
    }

    private fun load() {
        if (!indexFile.exists()) return
        runCatching {
            val o = JSONObject(indexFile.readText())
            val arr = o.optJSONArray("pages") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val id = e.getLong("id")
                val file = if (e.optBoolean("file")) File(dir, "page_$id.html").takeIf { it.exists() } else null
                entries.add(Page(id, e.getLong("ts"), e.getString("title"), e.getLong("group"), e.optInt("version", 1), file))
            }
            nextId = maxOf(o.optLong("next", 1L), (entries.maxOfOrNull { it.id } ?: 0L) + 1)
            if (repairMisfiled()) save()
        }.onFailure { Log.e(TAG, "could not read the page shelf; starting empty", it) }
    }
}
