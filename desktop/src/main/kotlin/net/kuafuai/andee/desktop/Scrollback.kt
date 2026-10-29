package net.kuafuai.andee.desktop

import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The desktop's conversation record: what was said, by whom, and which
 * stretches were folded away as topics. The same shape as the phone's
 * `ChatHistory` for everything the brain reads; pages come later with
 * `show_html`.
 *
 * One JSON file, rewritten whole on each change — a conversation is tens of
 * kilobytes and the write is off the UI thread.
 */
object Scrollback {

    private const val TAG = "Scrollback"
    private const val MAX_ROWS = 400
    private const val MAX_TOPICS = 60
    private const val MAX_TEXT = 4000

    /**
     * [EVENT] is something that happened that is not conversation — a todo woke it, the review ran, it wrote its
     * diary, it got up by itself ([TimelineEvents]). Shown in the timeline, **never** given to the brain:
     * [DesktopBrainHost.scrollback] leaves these rows out, so a review summary cannot come back as something said.
     */
    enum class Role { USER, ASSISTANT, ERROR, EVENT }

    /** [kind] and [detail] are set on [Role.EVENT] rows only: what kind of event, and the line shown when opened. */
    data class Row(
        val id: Long, val ts: Long, val role: Role, val text: String, val topicId: Long? = null,
        val kind: String = "", val detail: String = "",
        /** What an event row opens: a wiki article's id. */
        val ref: String = "",
    )

    data class Topic(val id: Long, val title: String, val summary: String, val startedAt: Long, val endedAt: Long)

    private val lock = Any()
    private val rows = mutableListOf<Row>()
    private val topics = mutableListOf<Topic>()
    private var nextId = 1L
    private var nextTopicId = 1L
    private val listeners = mutableListOf<() -> Unit>()

    private val file get() = AppDirs.file("scrollback.json")
    private val topicDir get() = File(AppDirs.root, "topics").apply { mkdirs() }

    init {
        load()
    }

    fun addListener(l: () -> Unit) = synchronized(lock) { listeners += l }
    fun removeListener(l: () -> Unit) = synchronized(lock) { listeners -= l }

    fun snapshot(): List<Row> = synchronized(lock) { rows.toList() }

    fun add(role: Role, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        synchronized(lock) {
            rows += Row(nextId++, System.currentTimeMillis(), role, t.take(MAX_TEXT))
            while (rows.size > MAX_ROWS) rows.removeAt(0)
        }
        saveAndNotify()
    }

    /** An event row (see [Role.EVENT]). */
    fun event(kind: String, text: String, detail: String = "", ref: String = "") {
        val t = text.trim()
        if (t.isEmpty()) return
        synchronized(lock) {
            rows += Row(nextId++, System.currentTimeMillis(), Role.EVENT, t.take(MAX_TEXT), kind = kind, detail = detail.trim().take(MAX_TEXT), ref = ref)
            while (rows.size > MAX_ROWS) rows.removeAt(0)
        }
        saveAndNotify()
    }

    /** Event rows standing between the first and last of [members] (by id): they go into the same topic. */
    fun eventsWithin(members: List<Long>): List<Long> = synchronized(lock) {
        if (members.isEmpty()) return emptyList()
        val lo = members.min(); val hi = members.max()
        rows.filter { it.role == Role.EVENT && it.topicId == null && it.id in lo..hi }.map { it.id }
    }

    fun topicsIndex(max: Int = 5): String = synchronized(lock) {
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        topics.sortedByDescending { it.endedAt }.take(max).joinToString("\n") { t ->
            val n = rows.count { it.topicId == t.id }
            "- #${t.id} ${t.title} (${fmt.format(Date(t.endedAt))} · $n msgs)"
        }
    }

    fun topicText(id: Long): String? = File(topicDir, "topic_$id.txt").takeIf { it.exists() }?.readText()

    fun topics(): List<Topic> = synchronized(lock) { topics.sortedByDescending { it.endedAt } }

    /** Archive [members] (by row id) as one topic. Null when nothing was written. */
    fun fold(title: String, summary: String, members: List<Long>, text: String): Topic? {
        if (members.isEmpty()) return null
        val t = synchronized(lock) {
            val picked = rows.filter { it.id in members }
            if (picked.isEmpty() || picked.any { it.topicId != null }) return null
            val id = nextTopicId++
            runCatching { File(topicDir, "topic_$id.txt").writeText(text) }.onFailure {
                Log.w(TAG, "could not archive topic: ${it.message}")
                return null
            }
            val topic = Topic(
                id = id,
                title = title.trim().ifEmpty { picked.first().text.take(40) },
                summary = summary.trim(),
                startedAt = picked.first().ts,
                endedAt = picked.last().ts,
            )
            topics += topic
            while (topics.size > MAX_TOPICS) topics.removeAt(0)
            for (i in rows.indices) if (rows[i].id in members) rows[i] = rows[i].copy(topicId = id)
            topic
        }
        saveAndNotify()
        return t
    }

    fun wipe() {
        synchronized(lock) {
            rows.clear()
            topics.clear()
        }
        topicDir.listFiles()?.forEach { it.delete() }
        saveAndNotify()
    }

    private fun saveAndNotify() {
        val (json, ls) = synchronized(lock) {
            val arr = JSONArray()
            for (r in rows) arr.put(
                JSONObject().put("id", r.id).put("ts", r.ts).put("role", r.role.name).put("text", r.text)
                    .apply { r.topicId?.let { put("topic", it) } }
                    .apply { if (r.kind.isNotEmpty()) put("kind", r.kind); if (r.detail.isNotEmpty()) put("detail", r.detail); if (r.ref.isNotEmpty()) put("ref", r.ref) },
            )
            val tarr = JSONArray()
            for (t in topics) tarr.put(
                JSONObject().put("id", t.id).put("title", t.title).put("summary", t.summary)
                    .put("start", t.startedAt).put("end", t.endedAt),
            )
            JSONObject().put("rows", arr).put("topics", tarr).put("next", nextId).put("nextTopic", nextTopicId)
                .toString() to listeners.toList()
        }
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json)
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "save failed: ${it.message}") }
        ls.forEach { runCatching { it() } }
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            val arr = o.optJSONArray("rows") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                rows += Row(
                    id = r.getLong("id"),
                    ts = r.getLong("ts"),
                    role = runCatching { Role.valueOf(r.getString("role")) }.getOrDefault(Role.ASSISTANT),
                    text = r.getString("text"),
                    topicId = if (r.has("topic")) r.getLong("topic") else null,
                    kind = r.optString("kind"),
                    detail = r.optString("detail"),
                    ref = r.optString("ref"),
                )
            }
            val tarr = o.optJSONArray("topics") ?: JSONArray()
            for (i in 0 until tarr.length()) {
                val t = tarr.getJSONObject(i)
                topics += Topic(t.getLong("id"), t.getString("title"), t.optString("summary"), t.getLong("start"), t.getLong("end"))
            }
            nextId = maxOf(o.optLong("next", 1L), (rows.maxOfOrNull { it.id } ?: 0L) + 1)
            nextTopicId = maxOf(o.optLong("nextTopic", 1L), (topics.maxOfOrNull { it.id } ?: 0L) + 1)
        }.onFailure { Log.e(TAG, "could not read the scrollback; starting empty", it) }
    }
}
