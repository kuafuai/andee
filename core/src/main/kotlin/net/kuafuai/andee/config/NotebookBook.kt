package net.kuafuai.andee.config

import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where a [NotebookBook] keeps its one blob. The phone encrypts it with a key in
 * the Android Keystore; the desktop with a key in the macOS Keychain. The book
 * itself never sees either.
 */
interface NotebookStore {
    /** The stored plaintext, or null when there is none or it cannot be read. */
    fun read(): String?

    /** Persist [plain]. False when nothing was written. */
    fun write(plain: String): Boolean

    /** Forget everything, durably, before returning. */
    fun wipe()
}

/** One remembered fact, way, preference or failure. */
data class Memory(
    val name: String,
    val description: String,
    val content: String,
    val type: String,
    val importance: Double,
    val source: String,
    val doNotDo: List<String>,
    val failureSignature: String,
    val validUntil: String,
    val layer: String,
    val createdAt: Long,
    val updatedAt: Long,
    val hits: Int,
    val lastUsedAt: Long,
    val history: List<String>,
) {
    /** Expired memories stay on disk but are never served. See [validUntil]. */
    fun expired(now: Long = System.currentTimeMillis()): Boolean {
        if (validUntil.isBlank()) return false
        return runCatching {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            (fmt.parse(validUntil)?.time ?: Long.MAX_VALUE) + 86_400_000L <= now
        }.getOrDefault(false)
    }
}

/**
 * The device's own notebook — what it remembers about this person, the
 * promises it made, and the scenes it learned — as logic over one JSON blob.
 * See the Android `Notebook` for the history and the reasoning behind each
 * rule; this is the same code with the storage taken out.
 */
class NotebookBook(private val store: NotebookStore) {

    private val TAG = "Body"

    /** Where wiki articles in the notebook are kept (the local folder). Filled by each body. */
    @Volatile var localWikiStore: net.kuafuai.andee.wiki.WikiStore? = null

    /** Andee's self-image, cares, diary — everything that forms who it is. One instance. */
    val selfhood: Selfhood by lazy { Selfhood(this) }

    /** The people it has met. One instance. */
    val people: People by lazy { People(this) }

    /** The articles it has written and the documents it knows of. One instance. */
    val wiki: net.kuafuai.andee.wiki.WikiBook by lazy {
        net.kuafuai.andee.wiki.WikiBook(this, mapOf("local" to (localWikiStore ?: throw IllegalStateException("localWikiStore not set"))))
    }

    /** Old versions kept per memory when the same `name` is written again. */
    private val MAX_HISTORY = 5

    /** Finished one-shot todos are kept this long, then dropped on the next write. */
    private val DONE_TODO_TTL_MS = 30L * 24 * 60 * 60 * 1000

    /** Ceiling on closed-todo tombstones, newest kept. See [prune]. */
    private val MAX_CLOSED_TODOS = 50

    // ------------------------------------------------------------------
    // process-wide cache
    // ------------------------------------------------------------------

    /**
     * Decrypting a few hundred KB of JSON on every turn to render an index of
     * ten lines would be silly, and the sweep timer reads this file on a
     * background thread while the loop reads it on its own. One cached copy,
     * invalidated by every write, keeps that simple — the file is small and
     * single-process, so there is no cross-process staleness to worry about.
     */
    @Volatile
    private var cached: JSONObject? = null

    private fun book(): JSONObject {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val fresh = readBook()
            cached = fresh
            return fresh
        }
    }

    private fun readBook(): JSONObject {
        val plain = store.read() ?: return emptyBook()
        return runCatching { JSONObject(plain) }.getOrElse {
            Log.e(TAG, "notebook: blob is not valid JSON, starting empty", it)
            emptyBook()
        }
    }

    private fun emptyBook(): JSONObject = JSONObject()
        .put("memories", JSONArray())
        .put("todos", JSONArray())
        .put("scenes", JSONArray())
        .put("meta", JSONObject())

    /**
     * Throws when the store refuses. Every caller is a tool the model called,
     * and the throw becomes the tool's error: a save that did not happen must
     * not come back as "Saved." — the model would tell the user it remembered.
     */
    private fun write(next: JSONObject) {
        prune(next)
        if (!store.write(next.toString())) {
            Log.e(TAG, "notebook: the store refused the write, nothing written")
            throw IllegalStateException(
                "The notebook could not be saved on this device, so nothing was stored. " +
                    "Tell the user plainly that you could not remember this, rather than saying you did.",
            )
        }
        cached = next
    }

    /**
     * A named section of the notebook that something else owns ([Gear] is the first). Read and written
     * **through this book**, so there is one cache and one writer: a second reader of the same file would
     * hold its own copy and overwrite the memories and todos the next time it saved.
     */
    fun section(name: String): JSONObject = JSONObject(book().optJSONObject(name)?.toString() ?: "{}")

    fun putSection(name: String, value: JSONObject) {
        val b = JSONObject(book().toString())
        b.put(name, value)
        write(b)
    }

    fun removeSection(name: String) {
        val b = JSONObject(book().toString())
        if (b.has(name)) { b.remove(name); write(b) }
    }

    /**
     * Drop what has aged out. Called on every write, so it costs nothing extra
     * — this is the *only* housekeeping the notebook does; nothing is evicted
     * for being unimportant or unused.
     *
     * Closed todos are the one thing that accumulates without ever being asked
     * for: every `done` / `drop` leaves a row behind (deliberately — "I did
     * that one" is worth being able to see). Two limits keep them from becoming
     * the whole book: age, and a ceiling on how many tombstones are kept.
     *
     * Note the stamp: a row that was dropped without ever firing has no
     * `last_run_at`, and reading only that field meant such rows were *never*
     * pruned. Fall back to when it was created.
     */
    private fun prune(b: JSONObject) {
        val cutoff = System.currentTimeMillis() - DONE_TODO_TTL_MS
        val kept = JSONArray()
        val closed = mutableListOf<Pair<Long, JSONObject>>()
        val todos = b.optJSONArray("todos") ?: JSONArray()
        for (i in 0 until todos.length()) {
            val t = todos.optJSONObject(i) ?: continue
            val isClosed = t.optString("status") in listOf("done", "dropped")
            if (!isClosed) {
                kept.put(t)
                continue
            }
            val stamp = t.optLong("last_run_at").takeIf { it > 0 } ?: t.optLong("created_at")
            if (stamp in 1 until cutoff) continue
            closed += stamp to t
        }
        // Newest tombstones survive; a tombstone is only interesting while it is
        // recent enough to be recognised. The rest are dropped.
        closed.sortedByDescending { it.first }
            .take(MAX_CLOSED_TODOS)
            .forEach { (_, t) -> kept.put(t) }
        b.put("todos", kept)
    }

    // ------------------------------------------------------------------
    // memories
    // ------------------------------------------------------------------

    /**
     * Importance is derived, never asked for.
     *
     * The cloud's `save_memory` takes an explicit 0–1 float. Here the model
     * types four fields and gets on with it: a personal notebook's entries are
     * far less differentiated than a dev agent's (which distinguishes "RLS
     * rule" from "temp note"), and asking for a number invites the model to
     * spend tokens on a decision nobody downstream can check.
     */

    fun memories(): List<Memory> = memories(book())

    private fun memories(b: JSONObject): List<Memory> {
        val arr = b.optJSONArray("memories") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::toMemory) }
    }

    private fun toMemory(o: JSONObject): Memory = Memory(
        name = o.optString("name"),
        description = o.optString("description"),
        content = o.optString("content"),
        type = o.optString("type", "user"),
        importance = o.optDouble("importance", 0.5),
        source = o.optString("source", SOURCE_HUMAN),
        doNotDo = o.optJSONArray("do_not_do")?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
        } ?: emptyList(),
        failureSignature = o.optString("failure_signature"),
        validUntil = o.optString("valid_until"),
        layer = o.optString("layer", "user"),
        createdAt = o.optLong("created_at"),
        updatedAt = o.optLong("updated_at"),
        hits = o.optInt("hits", 0),
        lastUsedAt = o.optLong("last_used_at"),
        history = o.optJSONArray("history")?.let { a ->
            (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
        } ?: emptyList(),
    )

    private fun toJson(m: Memory): JSONObject {
        val arr = JSONArray()
        for (h in m.history) arr.put(h)
        val dnd = JSONArray()
        for (d in m.doNotDo) dnd.put(d)
        return JSONObject()
            .put("name", m.name)
            .put("description", m.description)
            .put("content", m.content)
            .put("type", m.type)
            .put("importance", m.importance)
            .put("source", m.source)
            .put("do_not_do", dnd)
            .put("failure_signature", m.failureSignature)
            .put("valid_until", m.validUntil)
            .put("layer", m.layer)
            .put("created_at", m.createdAt)
            .put("updated_at", m.updatedAt)
            .put("hits", m.hits)
            .put("last_used_at", m.lastUsedAt)
            .put("history", arr)
    }

    /**
     * Insert or overwrite by [name]. Overwriting is the *only* revision path
     * the model has: it is how "I don't like spicy food any more" replaces
     * "likes spicy food", and how a playbook improves in place. The previous
     * version is pushed into `history` rather than dropped, so an overwrite
     * stays auditable even though nobody can see it from the UI.
     *
     * Throws IllegalArgumentException on a bad [type] or an empty [name] — the
     * caller turns that into an error the model can read and retry.
     */
    fun remember(
        name: String,
        description: String,
        content: String,
        type: String,
        source: String = SOURCE_HUMAN,
    ): Pair<Memory, Boolean> {
        val key = name.trim()
        require(key.isNotEmpty()) { "name cannot be empty" }
        val n = key.lowercase()
        val t = type.trim().lowercase()
        require(t in TYPES) { "type must be one of ${TYPES.joinToString(" / ")}" }

        val b = book().let { JSONObject(it.toString()) }
        val arr = b.optJSONArray("memories") ?: JSONArray()
        val now = System.currentTimeMillis()

        var found: JSONObject? = null
        var overwrote = false
        var index = -1
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name").lowercase() == n) {
                found = o
                index = i
                overwrote = true
                break
            }
        }
        if (found == null && arr.length() >= MAX_MEMORIES) {
            throw IllegalStateException(
                "The notebook is full ($MAX_MEMORIES entries) — forget one with `forget` " +
                    "before saving another.",
            )
        }

        val body = content.trim().ifEmpty { description.trim() }
        // Newest first, capped: an overwrite chain is a changelog, not an archive.
        val hist = JSONArray()
        if (found != null) {
            val old = toMemory(found)
            val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(old.updatedAt))
            hist.put("[$stamp] ${old.description}")
            for (h in old.history) if (hist.length() < MAX_HISTORY) hist.put(h)
        }

        val merged = Memory(
            name = key,
            description = description.trim(),
            content = body,
            type = t,
            importance = importanceFor(t),
            source = if (found != null) found.optString("source", source) else source,
            doNotDo = found?.let { toMemory(it).doNotDo } ?: emptyList(),
            failureSignature = found?.optString("failure_signature") ?: "",
            validUntil = found?.optString("valid_until") ?: "",
            layer = "user",
            createdAt = found?.optLong("created_at") ?: now,
            updatedAt = now,
            hits = found?.optInt("hits", 0) ?: 0,
            lastUsedAt = found?.optLong("last_used_at") ?: 0L,
            history = (0 until hist.length()).mapNotNull { hist.optString(it).ifBlank { null } },
        )
        if (index >= 0) arr.put(index, toJson(merged)) else arr.put(toJson(merged))
        b.put("memories", arr)
        write(b)
        return merged to overwrote
    }

    /**
     * Keyword search, newest-and-most-important first.
     *
     * Substring matching is enough at this scale (tens to hundreds of entries)
     * and it is honest about its limits: no stemming, no synonyms, no
     * embeddings. What it does have is a bias toward *recently used* entries,
     * which is what makes a small notebook feel like it knows you.
     */
    fun recall(query: String, type: String, limit: Int): List<Memory> {
        val q = query.trim().lowercase()
        val want = type.trim().lowercase()
        val now = System.currentTimeMillis()
        val all = memories().filter { !it.expired(now) }
            .filter { want.isBlank() || it.type == want }
        val hits = if (q.isEmpty()) {
            all
        } else {
            all.filter {
                it.name.lowercase().contains(q) ||
                    it.description.lowercase().contains(q) ||
                    it.content.lowercase().contains(q)
            }
        }
        val ranked = hits.sortedWith(
            compareByDescending<Memory> { it.importance }
                .thenByDescending { it.updatedAt },
        )
        val take = ranked.take(limit.coerceIn(1, 30))
        // Bookkeeping only: a recall still answers when the counter cannot be saved.
        if (take.isNotEmpty()) runCatching { bumpHits(take.map { it.name }) }
        return take
    }

    private fun bumpHits(names: List<String>) {
        val wanted = names.map { it.lowercase() }.toSet()
        val b = book().let { JSONObject(it.toString()) }
        val arr = b.optJSONArray("memories") ?: return
        val now = System.currentTimeMillis()
        var touched = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name").lowercase() !in wanted) continue
            o.put("hits", o.optInt("hits", 0) + 1)
            o.put("last_used_at", now)
            touched = true
        }
        if (touched) write(b)
    }

    /** Hard delete by name. The model's `forget`. Returns false when absent. */
    fun forget(name: String): Boolean {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return false
        val b = book().let { JSONObject(it.toString()) }
        val arr = b.optJSONArray("memories") ?: return false
        val kept = JSONArray()
        var hit = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name").lowercase() == n) {
                hit = true
                continue
            }
            kept.put(o)
        }
        if (!hit) return false
        b.put("memories", kept)
        write(b)
        return true
    }

    /**
     * Erase the book, and forget it was ever read.
     *
     * The in-process cache is the part that is easy to miss: clearing prefs
     * alone would leave the decrypted copy sitting in [cached], so the next
     * `recall` would keep answering out of a notebook that no longer exists on
     * disk. That is a factory reset that looks like it worked and did not, and
     * nobody would notice until much later.
     *
     * `commit()`, not `apply()`: the caller is about to report success and the
     * device may lose power a second later. This is the one write in this file
     * where the difference matters.
     */
    fun wipe() {
        store.wipe()
        cached = null
    }

    /**
     * The index that rides along with every turn: `name — description`, one
     * line each.
     *
     * Nothing calls this yet, and that is deliberate. It is the P3 step, and
     * it lands on the **user message**, not in the system prompt: `LocalPrompt`
     * is a constant on purpose so DeepSeek's prefix cache survives, and the
     * system message is the head of the request — anything injected there
     * invalidates the whole conversation behind it. The clock already rides in
     * on the user message for exactly this reason.
     */
    fun indexForPrompt(max: Int = 10): String {
        val now = System.currentTimeMillis()
        val top = memories().filter { !it.expired(now) }
            .sortedWith(
                compareByDescending<Memory> { it.importance }
                    .thenByDescending { it.lastUsedAt }
                    .thenByDescending { it.updatedAt },
            )
            .take(max)
        if (top.isEmpty()) return ""
        return top.joinToString("\n") { "- ${it.name}：${it.description}" }
    }

    /**
     * Everything already written down, for a review to compare against: every memory (name and description) and
     * every pending todo (id, when, what). A review that could not see these wrote near-duplicates of both — an
     * ordinary turn's index carries only the top ten memories and no todos at all.
     */
    fun recordForReview(now: Long = System.currentTimeMillis()): String = buildString {
        val mem = memories().filter { !it.expired(now) }.sortedBy { it.type }
        if (mem.isNotEmpty()) {
            append("Memories (").append(mem.size).append("):\n")
            mem.forEach { append("- [").append(it.type).append("] ").append(it.name).append("：").append(it.description.take(100)).append('\n') }
        }
        val td = todos("pending")
        if (td.isNotEmpty()) {
            if (isNotEmpty()) append('\n')
            append("Pending todos (").append(td.size).append("):\n")
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
            td.forEach { t ->
                append("- ").append(t.id).append(" · ").append(if (t.mode == "cron") t.cronExpr else fmt.format(java.util.Date(t.nextRunAt.takeIf { it > 0 } ?: t.at)))
                if (t.status == "paused") append(" · paused")
                append(" · ").append(t.what.take(120)).append('\n')
            }
        }
    }.trimEnd()

    // ------------------------------------------------------------------
    // todos
    // ------------------------------------------------------------------

    fun todos(filter: String): List<Todo> {
        val arr = book().optJSONArray("todos") ?: return emptyList()
        val all = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::toTodo) }
        return when (filter) {
            "all" -> all
            "cron" -> all.filter { it.mode == "cron" && it.status == "active" }
            else -> all.filter { it.status == "active" || it.status == "paused" }
        }
    }

    /**
     * The todos a notebook starts with: today one, the nightly picture ([NIGHTLY_PICTURE_CRON]). Added once per
     * notebook — the `meta` flag is what makes it once, so a user who drops or pauses it is not overruled at the
     * next start, and a wipe (清空小本本, 恢复出厂设置) takes the flag with it and a fresh notebook gets it again.
     * Not added beside an open todo on the same schedule, which is how it would be set by hand.
     *
     * Returns what was added, for the caller to arm; empty when there was nothing to do. Throws when the store
     * refuses, like every other write here.
     */
    @Synchronized
    fun seedDefaults(zh: Boolean, now: Long = System.currentTimeMillis()): List<Todo> {
        val b = JSONObject(book().toString())
        val meta = b.optJSONObject("meta") ?: JSONObject()
        if (meta.optBoolean(META_SEEDED)) return emptyList()
        val open = todos("pending")
        val added = mutableListOf<Todo>()
        if (open.none { it.mode == "cron" && it.cronExpr == NIGHTLY_PICTURE_CRON }) {
            val next = CronExpr.nextAfter(NIGHTLY_PICTURE_CRON, now)
            if (next != null) {
                added += Todo(
                    newTodoId(), if (zh) NIGHTLY_PICTURE_ZH else NIGHTLY_PICTURE_EN, "cron", 0L,
                    NIGHTLY_PICTURE_CRON, next, 0L, "", "", "active", now, false,
                )
            }
        }
        val arr = b.optJSONArray("todos") ?: JSONArray()
        added.forEach { arr.put(toJson(it)) }
        b.put("todos", arr)
        b.put("meta", meta.put(META_SEEDED, true))
        write(b)
        if (added.isNotEmpty()) Log.i(TAG, "notebook: seeded ${added.size} default todo(s)")
        return added
    }

    fun todoById(id: String): Todo? {
        val arr = book().optJSONArray("todos") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) return toTodo(o)
        }
        return null
    }

    fun addTodo(todo: Todo) {
        val b = book().let { JSONObject(it.toString()) }
        val arr = b.optJSONArray("todos") ?: JSONArray()
        arr.put(toJson(todo))
        b.put("todos", arr)
        write(b)
    }

    /** Merge-patch one todo. Null when the id is unknown. */
    fun patchTodo(
        id: String,
        status: String? = null,
        nextRunAt: Long? = null,
        lastRunAt: Long? = null,
        lastStatus: String? = null,
        lastError: String? = null,
    ): Todo? {
        val b = book().let { JSONObject(it.toString()) }
        val arr = b.optJSONArray("todos") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") != id) continue
            if (status != null) o.put("status", status)
            if (nextRunAt != null) o.put("next_run_at", nextRunAt)
            if (lastRunAt != null) o.put("last_run_at", lastRunAt)
            if (lastStatus != null) o.put("last_status", lastStatus)
            if (lastError != null) o.put("last_error", lastError)
            b.put("todos", arr)
            write(b)
            return toTodo(o)
        }
        return null
    }

    // ------------------------------------------------------------------
    // scenes
    // ------------------------------------------------------------------

    private val SCENE_NAME = Regex("[a-z0-9_]{1,40}")

    fun scenes(): List<Scene> {
        val arr = book().optJSONArray("scenes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::toScene) }
    }

    fun scene(name: String): Scene? =
        scenes().firstOrNull { it.name == name }

    private fun toScene(o: JSONObject): Scene {
        val apps = o.optJSONArray("trigger_apps")
        return Scene(
            name = o.optString("name"),
            title = o.optString("title"),
            summary = o.optString("summary"),
            prompt = o.optString("prompt"),
            triggerApps = if (apps == null) emptyList()
            else (0 until apps.length()).map { apps.optString(it) }.filter { it.isNotBlank() },
            createdAt = o.optLong("created_at"),
            updatedAt = o.optLong("updated_at"),
            lastUsedAt = o.optLong("last_used_at"),
            uses = o.optInt("uses"),
        )
    }

    private fun toJson(s: Scene): JSONObject = JSONObject()
        .put("name", s.name)
        .put("title", s.title)
        .put("summary", s.summary)
        .put("prompt", s.prompt)
        .put("trigger_apps", JSONArray(s.triggerApps))
        .put("created_at", s.createdAt)
        .put("updated_at", s.updatedAt)
        .put("last_used_at", s.lastUsedAt)
        .put("uses", s.uses)

    /** Create or overwrite. The second value is true when it was new. */
    fun saveScene(
        name: String,
        title: String,
        summary: String,
        prompt: String,
        triggerApps: List<String>,
    ): Pair<Scene, Boolean> {
        require(SCENE_NAME.matches(name)) { "name must be 1-40 chars of a-z 0-9 _ (got \"$name\")" }
        require(title.isNotBlank()) { "title is required" }
        require(prompt.isNotBlank()) { "prompt is required" }
        require(prompt.length <= MAX_SCENE_PROMPT) {
            "prompt is ${prompt.length} chars; keep it under $MAX_SCENE_PROMPT"
        }
        val b = book().let { JSONObject(it.toString()) }
        val arr = b.optJSONArray("scenes") ?: JSONArray()
        val now = System.currentTimeMillis()
        var old: Scene? = null
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") == name) old = toScene(o) else kept.put(o)
        }
        check(old != null || kept.length() < MAX_SCENES) {
            "already $MAX_SCENES scenes; delete one first"
        }
        val s = Scene(
            name = name,
            title = title.trim().take(40),
            summary = summary.trim().take(160),
            prompt = prompt.trim(),
            triggerApps = triggerApps.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            createdAt = old?.createdAt ?: now,
            updatedAt = now,
            lastUsedAt = old?.lastUsedAt ?: 0L,
            uses = old?.uses ?: 0,
        )
        kept.put(toJson(s))
        b.put("scenes", kept)
        write(b)
        return s to (old == null)
    }

    fun deleteScene(name: String): Boolean {
        val b = book().let { JSONObject(it.toString()) }
        val arr = b.optJSONArray("scenes") ?: return false
        val kept = JSONArray()
        var found = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") == name) found = true else kept.put(o)
        }
        if (!found) return false
        b.put("scenes", kept)
        write(b)
        return true
    }

    fun markSceneUsed(name: String) {
        val b = book().let { JSONObject(it.toString()) }
        val arr = b.optJSONArray("scenes") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") != name) continue
            o.put("uses", o.optInt("uses") + 1)
            o.put("last_used_at", System.currentTimeMillis())
            b.put("scenes", arr)
            write(b)
            return
        }
    }

    /** One line per scene for the per-turn user message; empty when there are none. */
    fun scenesForPrompt(): String =
        scenes().sortedByDescending { it.lastUsedAt }.joinToString("\n") { s ->
            val auto = if (s.triggerApps.isEmpty()) "" else "  [auto-enters on ${s.triggerApps.joinToString(",")}]"
            "- ${s.name}（${s.title}）：${s.summary}$auto"
        }

    companion object {
        private const val META_SEEDED = "defaults_seeded"

        /** Every evening at nine. */
        const val NIGHTLY_PICTURE_CRON = "0 21 * * *"

        /**
         * Model-facing (it is what the wake-up message carries) and shown in 待办, so in the user's language.
         * It says what to do and leaves how to the model: a market image tool, a page, its own drawing code —
         * whichever this body has.
         */
        const val NIGHTLY_PICTURE_ZH =
            "每晚回顾：把今天的事过一遍（对话、做过的事、日记），据此画一张「你和我」的图，要有意境。" +
                "画好放到屏幕上给用户看，再用一两句话说画的是什么。今天没什么事就画安静的一天，不要硬凑。"
        const val NIGHTLY_PICTURE_EN =
            "Evening look-back: go over today (the conversation, what you did, your diary) and from it make a " +
                "picture of \"you and me\" with some mood to it. Put it on the screen for the user and say in a " +
                "sentence or two what it shows. If nothing much happened, paint a quiet day rather than inventing one."

        /**
         * A todo's `what` that numbers several items ("1) … 2) …", "①…②…"): refused with the reason, because one todo
         * cannot be half done — the part that was not is lost when it is closed. Null when it is one thing.
         */
        fun bundledTodo(what: String): String? {
            val marks = Regex("""(?:^|[\s；;。，,:：])(?:\(?\d{1,2}[).、．.]|[①②③④⑤⑥⑦⑧⑨])""").findAll(what).count()
            return if (marks >= 2) "This todo lists several separate things. Set one todo per thing — each comes due, " +
                "is done and closed on its own." else null
        }

        fun importanceFor(type: String): Double = when (type) {
            "failure" -> 0.9
            "user" -> 0.7
            "feedback" -> 0.7
            "way" -> 0.6
            else -> 0.5
        }

        const val MAX_MEMORIES = 500
        val TYPES = listOf("user", "feedback", "way", "reference", "failure")
        const val SOURCE_HUMAN = "human"
        const val SOURCE_INFERRED = "agent_inference"
        const val SOURCE_FAILURE_REVIEW = "failure_review"
        const val MAX_SCENES = 30
        const val MAX_SCENE_PROMPT = 3000

        fun newTodoId(): String = "t" + System.currentTimeMillis().toString(36)

        fun toTodo(o: JSONObject): Todo = Todo(
            id = o.optString("id"),
            what = o.optString("what"),
            mode = o.optString("mode", "once"),
            at = o.optLong("at"),
            cronExpr = o.optString("cron_expr"),
            nextRunAt = o.optLong("next_run_at"),
            lastRunAt = o.optLong("last_run_at"),
            lastStatus = o.optString("last_status"),
            lastError = o.optString("last_error"),
            status = o.optString("status", "active"),
            createdAt = o.optLong("created_at"),
            untrusted = o.optBoolean("untrusted", false),
            nudge = o.optString("nudge"),
        )

        fun toJson(t: Todo): JSONObject = JSONObject()
            .put("id", t.id)
            .put("what", t.what)
            .put("mode", t.mode)
            .put("at", t.at)
            .put("cron_expr", t.cronExpr)
            .put("next_run_at", t.nextRunAt)
            .put("last_run_at", t.lastRunAt)
            .put("last_status", t.lastStatus)
            .put("last_error", t.lastError)
            .put("status", t.status)
            .put("created_at", t.createdAt)
            .put("untrusted", t.untrusted)
            .apply { if (t.nudge.isNotEmpty()) put("nudge", t.nudge) }
    }
}
