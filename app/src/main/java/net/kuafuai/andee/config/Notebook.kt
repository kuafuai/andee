package net.kuafuai.andee.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The tablet's own notebook — what it remembers about this person, and what it
 * promised to do later.
 *
 * Two things live here that used to live only in the cloud brain (agentworld's
 * `crm_*` tools): durable facts about the user, and a scheduler that can wake
 * the brain up at a future moment. This device has neither a database nor a
 * cron service, so the notebook is one encrypted JSON blob in SharedPreferences
 * plus [Scheduler] on top of AlarmManager.
 *
 * Storage is a hand-rolled AES/GCM blob whose key lives in the Android
 * Keystore — deliberately the same shape as [Vault], for the same reasons:
 * no dependency, and the key material dies with the app. It is a separate
 * prefs file and a separate key alias on purpose; a corrupted notebook must
 * never be able to take the passwords with it.
 *
 * **This is a private notebook.** Its tools are marked `localOnly` in
 * [net.kuafuai.andee.net.ToolSchemas] and are filtered out of the hub
 * `register` payload — the person's habits and promises do not leave the
 * device. There is no cloud copy and no export path; the only way a human
 * sees this is the read-only viewer in the settings screen.
 *
 * Field names track agentworld's memory model (`name` / `description` /
 * `content` / `type` / `importance` / `source` / `do_not_do` /
 * `failure_signature` / `valid_until` / `layer`) so the two can be compared
 * later. But only four of them are ever typed by the model — see
 * [importanceFor]; everything else is bookkeeping this file fills in itself.
 */
object Notebook {

    private const val TAG = "Body"
    private const val PREFS = "notebook_prefs"
    private const val META_PREFS = "notebook_meta_prefs"
    private const val KEY_BLOB = "book"
    private const val KEY_ALIAS = "body_notebook_key"
    private const val KEY_LAST_ACTIVITY = "last_activity_at"
    private const val KEY_SWEEP_DAY = "sweep_day"
    private const val KEY_SWEEP_COUNT = "sweep_count"
    private const val KEY_SWEEP_TOKENS = "sweep_tokens"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /**
     * Refuse new memories past this. Not a token budget — the context side is
     * already capped by [indexForPrompt] and by recall's `limit`. This is only
     * a backstop so a model looping on `remember` cannot grow the blob without
     * bound. At the cap, writes are refused *loudly* (the tool returns an
     * error) rather than silently evicting something.
     */
    const val MAX_MEMORIES = 500

    /** Old versions kept per memory when the same `name` is written again. */
    private const val MAX_HISTORY = 5

    /** Finished one-shot todos are kept this long, then dropped on the next write. */
    private const val DONE_TODO_TTL_MS = 30L * 24 * 60 * 60 * 1000

    /** Ceiling on closed-todo tombstones, newest kept. See [prune]. */
    private const val MAX_CLOSED_TODOS = 50

    val TYPES = listOf("user", "feedback", "way", "reference", "failure")

    /**
     * Where a memory came from — the difference that will matter later is
     * `human` (the user said it) versus `agent_inference` (the brain worked it
     * out during a 复盘). Nothing reads this yet; it is written now so that a
     * later "trust the user's own words over its own guesses" rule has the
     * data to stand on.
     */
    const val SOURCE_HUMAN = "human"
    const val SOURCE_INFERRED = "agent_inference"
    const val SOURCE_FAILURE_REVIEW = "failure_review"

    // ------------------------------------------------------------------
    // models
    // ------------------------------------------------------------------

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
     * A situation the assistant can step into: a goal, a voice, rules and steps
     * that ride in the system prompt while it is active. Learned, not shipped —
     * the model proposes one and the user says yes before it is written.
     */
    data class Scene(
        val name: String,
        val title: String,
        val summary: String,
        val prompt: String,
        /** Package names whose arrival in the foreground enters this scene. */
        val triggerApps: List<String>,
        val createdAt: Long,
        val updatedAt: Long,
        val lastUsedAt: Long,
        val uses: Int,
    )

    data class Todo(
        val id: String,
        val what: String,
        /** `once` or `cron`. */
        val mode: String,
        /** Absolute millis, one-shot only. 0 for cron rows. */
        val at: Long,
        /** Standard 5-field expression, cron rows only. */
        val cronExpr: String,
        val nextRunAt: Long,
        val lastRunAt: Long,
        val lastStatus: String,
        val lastError: String,
        /** `active` / `paused` / `done` / `dropped`. */
        val status: String,
        val createdAt: Long,
    )

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

    private fun book(context: Context): JSONObject {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val fresh = readBook(context)
            cached = fresh
            return fresh
        }
    }

    private fun readBook(context: Context): JSONObject {
        val blob = prefs(context).getString(KEY_BLOB, null) ?: return emptyBook()
        val plain = decrypt(blob) ?: return emptyBook()
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

    private fun write(context: Context, next: JSONObject) {
        prune(next)
        val blob = encrypt(next.toString())
        if (blob == null) {
            // Never report a save that did not happen — see the same guard in
            // Vault.write.
            Log.e(TAG, "notebook: encrypt failed, nothing written")
            return
        }
        prefs(context).edit().putString(KEY_BLOB, blob).apply()
        cached = next
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
    fun importanceFor(type: String): Double = when (type) {
        "failure" -> 0.9
        "user" -> 0.7
        "feedback" -> 0.7
        "way" -> 0.6
        else -> 0.5
    }

    fun memories(context: Context): List<Memory> = memories(book(context))

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
        context: Context,
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

        val b = book(context).let { JSONObject(it.toString()) }
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
        write(context, b)
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
    fun recall(context: Context, query: String, type: String, limit: Int): List<Memory> {
        val q = query.trim().lowercase()
        val want = type.trim().lowercase()
        val now = System.currentTimeMillis()
        val all = memories(context).filter { !it.expired(now) }
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
        if (take.isNotEmpty()) bumpHits(context, take.map { it.name })
        return take
    }

    private fun bumpHits(context: Context, names: List<String>) {
        val wanted = names.map { it.lowercase() }.toSet()
        val b = book(context).let { JSONObject(it.toString()) }
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
        if (touched) write(context, b)
    }

    /** Hard delete by name. The model's `forget`. Returns false when absent. */
    fun forget(context: Context, name: String): Boolean {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return false
        val b = book(context).let { JSONObject(it.toString()) }
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
        write(context, b)
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
    fun wipe(context: Context) {
        prefs(context).edit().clear().commit()
        metaPrefs(context).edit().clear().commit()
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
    fun indexForPrompt(context: Context, max: Int = 10): String {
        val now = System.currentTimeMillis()
        val top = memories(context).filter { !it.expired(now) }
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
     * Plain-text view for the settings screen. Read-only, and the only way out.
     *
     * **Labels are translated; the entries are not.** What is appended from
     * [Memory] / [Todo] — a name, a description, a cron expression, a
     * timestamp — is stored data and is printed exactly as written. Only the
     * scaffolding around it (headings, "expired", "source", separators) comes
     * from resources, which is why this builds the string in pieces instead of
     * one big template: the data would be un-escapable inside a resource.
     *
     * The signature stays `(Context)` on purpose. The caller passes whatever
     * Context it has and the wrap happens here, so no call site can forget it
     * and there is one place to look when a label comes out in the wrong
     * language. See [AppLocale] for why a Service's Context cannot simply be
     * used for the strings.
     */
    fun dumpText(context: Context): String {
        val ctx = AppLocale.wrap(context)
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder()
        val mem = memories(context)
        sb.append(ctx.getString(R.string.notebook_dump_header, mem.size))
        sb.append(ctx.getString(R.string.notebook_dump_sort_note))
        if (mem.isEmpty()) {
            sb.append(ctx.getString(R.string.notebook_dump_empty_memories))
        }
        for (m in mem.sortedWith(compareByDescending<Memory> { it.updatedAt })) {
            sb.append("· ").append(m.name).append("  [").append(m.type).append("]")
            if (m.expired(now)) sb.append(ctx.getString(R.string.notebook_dump_expired))
            sb.append('\n')
            sb.append("   ").append(m.description).append('\n')
            if (m.content.isNotBlank() && m.content != m.description) {
                sb.append("   ").append(m.content.replace("\n", "\n   ")).append('\n')
            }
            sb.append("   ").append(fmt.format(Date(m.updatedAt)))
                .append(ctx.getString(R.string.notebook_dump_source)).append(m.source)
                .append(ctx.getString(R.string.notebook_dump_hits, m.hits))
        }
        val todos = todos(context, "all")
        sb.append(ctx.getString(R.string.notebook_dump_todos_header, todos.size))
        if (todos.isEmpty()) sb.append(ctx.getString(R.string.notebook_dump_empty_todos))
        for (t in todos.sortedBy { it.nextRunAt }) {
            sb.append("· ").append(t.what).append("  [").append(t.status).append("]\n")
            sb.append("   ")
                .append(
                    if (t.mode == "cron") {
                        ctx.getString(R.string.notebook_dump_recurring, t.cronExpr)
                    } else {
                        fmt.format(Date(t.at))
                    }
                )
                .append(ctx.getString(R.string.notebook_dump_next))
                .append(if (t.nextRunAt > 0) fmt.format(Date(t.nextRunAt)) else "—")
                .append('\n')
            if (t.lastError.isNotBlank()) {
                sb.append(ctx.getString(R.string.notebook_dump_last_error))
                    .append(t.lastError).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // todos
    // ------------------------------------------------------------------

    fun todos(context: Context, filter: String): List<Todo> {
        val arr = book(context).optJSONArray("todos") ?: return emptyList()
        val all = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::toTodo) }
        return when (filter) {
            "all" -> all
            "cron" -> all.filter { it.mode == "cron" && it.status == "active" }
            else -> all.filter { it.status == "active" || it.status == "paused" }
        }
    }

    fun todoById(context: Context, id: String): Todo? {
        val arr = book(context).optJSONArray("todos") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) return toTodo(o)
        }
        return null
    }

    private fun toTodo(o: JSONObject): Todo = Todo(
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
    )

    private fun toJson(t: Todo): JSONObject = JSONObject()
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

    fun newTodoId(): String = "t" + System.currentTimeMillis().toString(36)

    fun addTodo(context: Context, todo: Todo) {
        val b = book(context).let { JSONObject(it.toString()) }
        val arr = b.optJSONArray("todos") ?: JSONArray()
        arr.put(toJson(todo))
        b.put("todos", arr)
        write(context, b)
    }

    /** Merge-patch one todo. Null when the id is unknown. */
    fun patchTodo(
        context: Context,
        id: String,
        status: String? = null,
        nextRunAt: Long? = null,
        lastRunAt: Long? = null,
        lastStatus: String? = null,
        lastError: String? = null,
    ): Todo? {
        val b = book(context).let { JSONObject(it.toString()) }
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
            write(context, b)
            return toTodo(o)
        }
        return null
    }

    // ------------------------------------------------------------------
    // scenes
    // ------------------------------------------------------------------

    const val MAX_SCENES = 30
    const val MAX_SCENE_PROMPT = 3000
    private val SCENE_NAME = Regex("[a-z0-9_]{1,40}")

    fun scenes(context: Context): List<Scene> {
        val arr = book(context).optJSONArray("scenes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::toScene) }
    }

    fun scene(context: Context, name: String): Scene? =
        scenes(context).firstOrNull { it.name == name }

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
        context: Context,
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
        val b = book(context).let { JSONObject(it.toString()) }
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
        write(context, b)
        return s to (old == null)
    }

    fun deleteScene(context: Context, name: String): Boolean {
        val b = book(context).let { JSONObject(it.toString()) }
        val arr = b.optJSONArray("scenes") ?: return false
        val kept = JSONArray()
        var found = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") == name) found = true else kept.put(o)
        }
        if (!found) return false
        b.put("scenes", kept)
        write(context, b)
        return true
    }

    fun markSceneUsed(context: Context, name: String) {
        val b = book(context).let { JSONObject(it.toString()) }
        val arr = b.optJSONArray("scenes") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") != name) continue
            o.put("uses", o.optInt("uses") + 1)
            o.put("last_used_at", System.currentTimeMillis())
            b.put("scenes", arr)
            write(context, b)
            return
        }
    }

    /** One line per scene for the per-turn user message; empty when there are none. */
    fun scenesForPrompt(context: Context): String =
        scenes(context).sortedByDescending { it.lastUsedAt }.joinToString("\n") { s ->
            val auto = if (s.triggerApps.isEmpty()) "" else "  [auto-enters on ${s.triggerApps.joinToString(",")}]"
            "- ${s.name}（${s.title}）：${s.summary}$auto"
        }

    // ------------------------------------------------------------------
    // meta: activity stamp + sweep budget
    //
    // Plain prefs, deliberately NOT in the encrypted blob. These are touched
    // on every turn (the activity stamp) and are not secret — none of them
    // says anything about the user. Routing them through the notebook would
    // mean re-encrypting the whole book on every single message.
    // ------------------------------------------------------------------

    /** When the user last said something. */
    fun touchActivity(context: Context) {
        metaPrefs(context).edit()
            .putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis())
            .apply()
    }

    fun lastActivityAt(context: Context): Long = metaPrefs(context).getLong(KEY_LAST_ACTIVITY, 0L)

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** True when today's sweep count is still under [cap]. */
    fun sweepAllowed(context: Context, cap: Int): Boolean = sweepCountToday(context) < cap

    fun sweepCountToday(context: Context): Int {
        val p = metaPrefs(context)
        return if (p.getString(KEY_SWEEP_DAY, "") == today()) p.getInt(KEY_SWEEP_COUNT, 0) else 0
    }

    fun sweepTokensToday(context: Context): Int {
        val p = metaPrefs(context)
        return if (p.getString(KEY_SWEEP_DAY, "") == today()) p.getInt(KEY_SWEEP_TOKENS, 0) else 0
    }

    fun noteSweep(context: Context, tokens: Int) {
        val day = today()
        val count = sweepCountToday(context)
        val toks = sweepTokensToday(context)
        metaPrefs(context).edit()
            .putString(KEY_SWEEP_DAY, day)
            .putInt(KEY_SWEEP_COUNT, count + 1)
            .putInt(KEY_SWEEP_TOKENS, toks + tokens)
            .apply()
    }

    private fun metaPrefs(context: Context) =
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)

    // ---- crypto ----------------------------------------------------
    // Same construction as Vault: base64(iv || ciphertext), key in the
    // Android Keystore, no user-authentication requirement (a notebook write
    // happens mid-conversation, not at the lock screen).

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(c.iv + body, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(blob: String): String? = runCatching {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_BYTES),
        )
        String(c.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    }.getOrElse {
        // A restored backup or a wiped Keystore leaves an undecryptable blob.
        // Same trap as Vault: from the outside it looks like an empty notebook.
        Log.e(TAG, "notebook: decrypt failed — the notebook is unreadable on this install", it)
        null
    }
}
