package net.kuafuai.andee.config

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Who this Andee is, what it cares about, and what it did today — the three things a person has that
 * the notebook never held. See `docs/design/inner-drive.zh-CN.md` §4, §5 and §8.6.
 *
 *  * **narrative** — a short first-person text it rewrites as it goes. A subject, not a persona skin.
 *  * **cares** — a few specific things it cannot put down. Not tasks. Each carries an *expected interval*,
 *    and what orders them is [overdue], the time since it last looked divided by that interval, so a
 *    community checked every 12 hours and a friend not heard from in a fortnight sit on the same scale.
 *    (The first draft used one 0–100 "pressure" for every kind; whichever curve climbed fastest then
 *    outranked everything for ever.) All of it is computed here, never reported by the model.
 *  * **diary** — one page a night, in its own voice.
 *
 * Stored in the notebook under `self`, through the book itself (like [Gear] and [Plans]), so wipe, factory
 * reset and export cover it. One Andee per device: this is *this* Andee's, and nothing here is shared.
 */
class Selfhood(
    private val book: NotebookBook,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    data class Care(
        val id: String,
        val what: String,
        val why: String,
        /** `place` / `person` / `promise` / `curiosity` / `habit`. */
        val kind: String,
        /** How often this one should be looked at, ms. A promise uses [due] instead. */
        val intervalMs: Long,
        /** Promise only: when it is due, ms; 0 otherwise. */
        val due: Long,
        val lastTouched: Long,
        /** Where it came from: the user's words, the date. Never blank. */
        val source: String,
        /** `active` / `paused` / `done` / `dropped`. */
        val status: String,
        /** Its own words about where it stands, written when it last worked on it. */
        val note: String,
        /** Looked at but nothing done, in a row. Reset by a real action. */
        val idleRuns: Int,
        /** The user removed or paused it; the model may not undo that. */
        val byUser: Boolean,
        val createdAt: Long,
        /** The user pointed at it ("you should look after this") and it has not been worked on since. */
        val pinnedAt: Long,
        /** Links to wiki articles / people, `wiki:<id>` or `person:<id>`. */
        val links: List<String>,
    )

    data class DiaryPage(val date: String, val text: String, val at: Long, val calls: List<String>)

    private val lock = Any()

    // ---- storage -------------------------------------------------------------

    private fun load(): JSONObject = book.section(SECTION)
    private fun save(o: JSONObject) = book.putSection(SECTION, o)

    // ---- narrative -------------------------------------------------------------

    data class Narrative(val text: String, val updatedAt: Long, val byUser: Boolean, val version: Int)

    fun narrative(): Narrative? = synchronized(lock) {
        load().optJSONObject("narrative")?.let {
            Narrative(it.optString("text"), it.optLong("at"), it.optBoolean("by_user"), it.optInt("version", 1))
        }?.takeIf { it.text.isNotBlank() }
    }

    /** Past versions, newest first; the user can roll back to one. */
    fun narrativeHistory(): List<Narrative> = synchronized(lock) {
        val a = load().optJSONArray("narrative_history") ?: return emptyList()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            Narrative(it.optString("text"), it.optLong("at"), it.optBoolean("by_user"), it.optInt("version", 1))
        }.reversed()
    }

    /**
     * Rewrite the narrative. The model may not overwrite a text the user wrote or edited; it has to say so
     * and ask. [byUser] is for the settings page.
     *
     * @return null on success, else a sentence for the model.
     */
    fun setNarrative(text: String, byUser: Boolean): String? = synchronized(lock) {
        val t = text.trim()
        if (t.isEmpty()) return "The narrative cannot be empty."
        if (t.length > MAX_NARRATIVE) {
            return "Too long (${t.length} characters; the limit is $MAX_NARRATIVE). Keep what is true and matters now; the rest belongs in the diary or the wiki."
        }
        val root = load()
        val cur = root.optJSONObject("narrative")
        if (!byUser && cur != null && cur.optBoolean("by_user")) {
            return "The user wrote or edited this narrative, so it is theirs to change. Say what you would change and let them decide."
        }
        if (cur != null) {
            val h = root.optJSONArray("narrative_history") ?: JSONArray()
            h.put(cur)
            while (h.length() > MAX_NARRATIVE_VERSIONS) h.remove(0)
            root.put("narrative_history", h)
        }
        val v = (cur?.optInt("version", 1) ?: 0) + 1
        root.put("narrative", JSONObject().put("text", t).put("at", clock()).put("by_user", byUser).put("version", v))
        save(root)
        null
    }

    fun rollbackNarrative(version: Int): Boolean = synchronized(lock) {
        val root = load()
        val h = root.optJSONArray("narrative_history") ?: return false
        for (i in 0 until h.length()) {
            val o = h.optJSONObject(i) ?: continue
            if (o.optInt("version", 1) == version) {
                val cur = root.optJSONObject("narrative")
                if (cur != null) h.put(cur)
                root.put("narrative", JSONObject().put("text", o.optString("text")).put("at", clock()).put("by_user", true).put("version", (cur?.optInt("version", 1) ?: 0) + 1))
                root.put("narrative_history", h)
                save(root)
                return true
            }
        }
        false
    }

    // ---- cares -----------------------------------------------------------------

    fun cares(includeClosed: Boolean = false): List<Care> = synchronized(lock) {
        val a = load().optJSONArray("cares") ?: return emptyList()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(::toCare)
            .filter { includeClosed || it.status == "active" || it.status == "paused" }
    }

    fun care(id: String): Care? = cares(true).firstOrNull { it.id == id }

    private fun putCares(root: JSONObject, list: List<Care>) {
        root.put("cares", JSONArray().apply { list.forEach { put(toJson(it)) } })
    }

    /**
     * Take on a new care. Refuses what the user removed, a seventh active one, and anything with no
     * [Care.source] — a care that cannot say where it came from is the model's own invention, and it must not
     * decide by itself what to be anxious about.
     */
    fun addCare(
        kind: String, what: String, why: String, source: String,
        intervalHours: Double? = null, due: Long = 0L, links: List<String> = emptyList(),
    ): String? = synchronized(lock) {
        require(kind in KINDS) { "kind is one of $KINDS; got $kind" }
        require(what.isNotBlank()) { "what is required" }
        if (source.isBlank()) return "A care needs a source: whose words, or what you saw, and when. One you cannot source is not yours yet."
        val root = load()
        val list = cares(true).toMutableList()
        list.firstOrNull { it.what == what && it.byUser && (it.status == "dropped" || it.status == "paused") }?.let {
            return "The user ${if (it.status == "dropped") "removed" else "paused"} \"$what\" themselves. Do not take it back up unless they ask for it in so many words."
        }
        list.firstOrNull { it.what == what && it.status == "active" }?.let { return "\"$what\" is already one of your cares (${it.id}); use update." }
        if (list.count { it.status == "active" } >= MAX_ACTIVE) {
            return "You already hold $MAX_ACTIVE cares. More than that is not caring, it is a list: finish, pause or let one go first."
        }
        if (kind == "promise") require(due > 0) { "a promise needs a due time" }
        val now = clock()
        val c = Care(
            id = "c" + now.toString(36) + list.size,
            what = what.trim(), why = why.trim(), kind = kind,
            intervalMs = ((intervalHours ?: DEFAULT_HOURS[kind] ?: 24.0) * HOUR_MS).toLong().coerceAtLeast(HOUR_MS / 2),
            due = due, lastTouched = now, source = source.trim(), status = "active", note = "",
            idleRuns = 0, byUser = false, createdAt = now, pinnedAt = 0L, links = links,
        )
        list += c
        putCares(root, list); save(root)
        "id:${c.id}"
    }

    /** `update` fields are all optional; `status` moves it along. Returns null on success, else a sentence. */
    fun updateCare(id: String, status: String?, note: String?, intervalHours: Double?, links: List<String>?, due: Long?): String? = synchronized(lock) {
        val root = load()
        val list = cares(true).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return "No care with id $id; list first."
        val old = list[i]
        if (old.byUser && status != null && status != old.status) {
            return "The user set this one to ${old.status} themselves. Leave it; ask them if you think it should change."
        }
        if (status != null) require(status in setOf("active", "paused", "done", "dropped")) { "status is active, paused, done or dropped" }
        list[i] = old.copy(
            status = status ?: old.status,
            note = note?.trim()?.take(MAX_NOTE) ?: old.note,
            intervalMs = intervalHours?.let { (it * HOUR_MS).toLong().coerceAtLeast(HOUR_MS / 2) } ?: old.intervalMs,
            links = links ?: old.links,
            due = due ?: old.due,
        )
        putCares(root, list); save(root)
        null
    }

    /** The user paused, resumed or removed one in settings; the model may not undo it. */
    fun setCareByUser(id: String, status: String) = synchronized(lock) {
        val root = load()
        val list = cares(true).map { if (it.id == id) it.copy(status = status, byUser = status != "active") else it }
        putCares(root, list); save(root)
    }

    /** The user said "you should look after this one": it counts as overdue until it has been worked on. */
    fun pin(id: String) = synchronized(lock) {
        val root = load()
        putCares(root, cares(true).map { if (it.id == id) it.copy(pinnedAt = clock()) else it })
        save(root)
    }

    // ---- the ratio -------------------------------------------------------------

    /**
     * How overdue [c] is: 1.0 is "the time I meant to look has come". Comparable across kinds, which is the
     * point. A promise counts down to its due time over the lead it was given, and past due it keeps growing.
     */
    fun overdue(c: Care, now: Long = clock()): Double {
        val base = if (c.kind == "promise" && c.due > 0) {
            val lead = (c.due - c.createdAt).coerceAtLeast(HOUR_MS)
            1.0 - (c.due - now).toDouble() / lead
        } else {
            (now - c.lastTouched).toDouble() / c.intervalMs.coerceAtLeast(1)
        }
        val bump = if (c.pinnedAt > c.lastTouched) PIN_BUMP else 0.0
        return base.coerceAtLeast(0.0) * (1.0 + bump)
    }

    /** Active cares that are due, most overdue first. [threshold] 1.0 = the moment it was meant to be looked at. */
    fun due(threshold: Double = THRESHOLD, now: Long = clock()): List<Pair<Care, Double>> =
        cares().filter { it.status == "active" }.map { it to overdue(it, now) }.filter { it.second >= threshold }.sortedByDescending { it.second }

    /** What the heartbeat shows: the few most overdue, with their ratios (device-computed). */
    fun top(n: Int = 5, now: Long = clock()): List<Pair<Care, Double>> =
        cares().filter { it.status == "active" }.map { it to overdue(it, now) }.sortedByDescending { it.second }.take(n)

    // ---- what a work turn did, and what it counts for -----------------------------

    /** What a finished work turn did on a care, judged by the device from its tool calls (§5.2). */
    enum class Effect { ACTED, LOOKED, NOTHING }

    /**
     * Book a finished work turn on [careId]. The device decides [effect] from the turn's own tool calls; the
     * model's say-so is not an input. Acting resets the clock; looking only moves it half an interval; doing
     * nothing changes nothing and is counted, so a care that keeps being "worked on" without result is visible.
     */
    fun settle(careId: String, effect: Effect, note: String?): Care? = synchronized(lock) {
        val root = load()
        val list = cares(true).toMutableList()
        val i = list.indexOfFirst { it.id == careId }
        if (i < 0) return null
        val c = list[i]
        val now = clock()
        list[i] = when (effect) {
            Effect.ACTED -> c.copy(lastTouched = now, idleRuns = 0, pinnedAt = 0L, note = note?.trim()?.take(MAX_NOTE) ?: c.note)
            Effect.LOOKED -> c.copy(lastTouched = maxOf(c.lastTouched, now - c.intervalMs / 2), idleRuns = c.idleRuns, note = note?.trim()?.take(MAX_NOTE) ?: c.note)
            // Nothing came of it: count it, and push it back half an interval as a look would, so the same care
            // cannot take every work turn of the day in a row.
            Effect.NOTHING -> c.copy(idleRuns = c.idleRuns + 1, lastTouched = maxOf(c.lastTouched, now - c.intervalMs / 2))
        }
        putCares(root, list); save(root)
        list[i]
    }

    // ---- diary -------------------------------------------------------------------

    fun today(): String = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate().toString()

    fun diary(limit: Int = 7): List<DiaryPage> = synchronized(lock) {
        val a = load().optJSONArray("diary") ?: return emptyList()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            DiaryPage(it.optString("date"), it.optString("text"), it.optLong("at"), strings(it.optJSONArray("calls")))
        }.takeLast(limit).reversed()
    }

    /**
     * Write tonight's page. A page that reads like a status report is refused with the reason, and so is one
     * that claims work with nothing behind it: [calls] are the tool-call ids of what it did today, supplied
     * by the device, and a page may mention doing something only if there is at least one.
     *
     * @return null on success, else a sentence for the model.
     */
    fun writeDiary(text: String, calls: List<String>): String? = synchronized(lock) {
        val t = text.trim()
        if (t.length < MIN_DIARY) return "A diary page is a few sentences in your own voice; this is too short to be one."
        if (t.length > MAX_DIARY) return "Too long (${t.length}; the limit is $MAX_DIARY). A page, not a report."
        reportVoice(t)?.let { return it }
        val root = load()
        val a = root.optJSONArray("diary") ?: JSONArray()
        val date = today()
        // One page per day: rewriting tonight's replaces it.
        val keep = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("date") != date }?.let { keep.put(it) }
        keep.put(JSONObject().put("date", date).put("text", t).put("at", clock()).put("calls", JSONArray(calls)))
        while (keep.length() > MAX_DIARY_PAGES) keep.remove(0)
        root.put("diary", keep)
        save(root)
        null
    }

    // ---- the prompt line -----------------------------------------------------------

    /** One line that rides on every user message: who I am in a breath and what is pulling at me. */
    fun indexForPrompt(now: Long = clock()): String = synchronized(lock) {
        val n = narrative()
        val due = due(THRESHOLD, now).take(2)
        if (n == null && due.isEmpty()) return ""
        buildString {
            if (n != null) append("You: ").append(n.text.lineSequence().first { it.isNotBlank() }.take(120))
            if (due.isNotEmpty()) {
                if (isNotEmpty()) append(' ')
                append("On your mind: ").append(due.joinToString("; ") { (c, r) -> "${c.what} (${"%.1f".format(r)}× overdue)" }).append('.')
            }
        }
    }

    // ---- json ------------------------------------------------------------------------

    private fun toCare(o: JSONObject) = Care(
        o.optString("id"), o.optString("what"), o.optString("why"), o.optString("kind", "curiosity"),
        o.optLong("interval_ms", 24 * HOUR_MS), o.optLong("due"), o.optLong("last_touched"), o.optString("source"),
        o.optString("status", "active"), o.optString("note"), o.optInt("idle_runs"), o.optBoolean("by_user"),
        o.optLong("created_at"), o.optLong("pinned_at"), strings(o.optJSONArray("links")),
    )

    private fun toJson(c: Care) = JSONObject().put("id", c.id).put("what", c.what).put("why", c.why).put("kind", c.kind)
        .put("interval_ms", c.intervalMs).put("due", c.due).put("last_touched", c.lastTouched).put("source", c.source)
        .put("status", c.status).put("note", c.note).put("idle_runs", c.idleRuns).put("by_user", c.byUser)
        .put("created_at", c.createdAt).put("pinned_at", c.pinnedAt).put("links", JSONArray(c.links))

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    companion object {
        const val SECTION = "self"
        const val MAX_ACTIVE = 7
        const val MAX_NARRATIVE = 600
        const val MAX_NARRATIVE_VERSIONS = 20
        const val MAX_NOTE = 400
        const val MIN_DIARY = 40
        const val MAX_DIARY = 1200
        const val MAX_DIARY_PAGES = 60
        const val HOUR_MS = 3_600_000L
        /** A care this overdue is worth waking for. A setting, not a law. */
        const val THRESHOLD = 1.0
        const val PIN_BUMP = 0.5
        val KINDS = setOf("place", "person", "promise", "curiosity", "habit")
        val DEFAULT_HOURS = mapOf("place" to 12.0, "person" to 24.0 * 7, "curiosity" to 72.0, "habit" to 24.0, "promise" to 24.0)

        /**
         * The diary is a person's page, not a report. Turns the worst offenders back with the reason: a line
         * that is only "完成了 N 项" / "completed N tasks", or a page made of bullet points and no sentence.
         */
        fun reportVoice(t: String): String? {
            val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val bullets = lines.count { it.startsWith("-") || it.startsWith("*") || it.startsWith("•") || Regex("""^\d+[.、)]""").containsMatchIn(it) }
            if (lines.size >= 3 && bullets * 2 > lines.size) {
                return "This reads as a list of items done. A diary page is in your own voice: what stayed with you, what you are unsure about, what you think of someone now, why you want to do X tomorrow. Write sentences."
            }
            val tally = Regex("""(完成了?\s*\d+\s*(项|件|个)|共\s*\d+\s*(项|件|个)\s*(工作|任务)|completed\s+\d+\s+(tasks?|items?)|\d+\s+tasks?\s+(done|completed))""", RegexOption.IGNORE_CASE)
            if (tally.containsMatchIn(t)) {
                return "\"Completed N items\" is a status report, not a diary. Say what it was like, what you noticed, what you are not sure of."
            }
            return null
        }
    }
}
