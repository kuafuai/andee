package net.kuafuai.andee.wiki

import net.kuafuai.andee.config.NotebookBook
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The rules of the knowledge base, written once; backends ([WikiStore]) only carry bytes.
 *
 * What it keeps in the notebook is the **index of [Meta]** — one entry per document this Andee knows of,
 * wherever the body is. The bodies are not here: local ones are files in a folder the user can open, online
 * ones stay online (see [Wiki.kt] on why nothing is mirrored).
 *
 * Rules that make it a wiki rather than a pile:
 *  * [search] before [write]: a write that does not name an existing article is refused when a close one exists,
 *    with that article's id, so "a second plan for the same community" cannot happen by accident;
 *  * a change carries a [changeNote] and a [source] — where this came from (looked up / he said / I infer);
 *  * `archived` articles leave the search, `stale` ones say why, `lost` ones are listed apart;
 *  * hard limits on count and length; going over means tidying, not silently losing the oldest.
 */
class WikiBook(
    private val book: NotebookBook,
    private val stores: Map<String, WikiStore>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    private fun load(): JSONObject = book.section(SECTION)
    private fun save(o: JSONObject) = book.putSection(SECTION, o)

    // ---- reading the index --------------------------------------------------------

    fun all(includeArchived: Boolean = false): List<Meta> = synchronized(lock) {
        val a = load().optJSONArray("articles") ?: return emptyList()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(::toMeta)
            .filter { includeArchived || it.status != Status.ARCHIVED }
    }

    fun meta(id: String): Meta? = all(true).firstOrNull { it.id == id }

    /**
     * Find articles. Loose, on title, summary, kind and links; archived ones never match. A query that is a
     * known id returns that one. Ordered by how many words matched, then by how recently it was touched.
     */
    fun search(query: String, kind: String? = null, limit: Int = 8): List<Meta> {
        val words = query.lowercase().split(Regex("[\\s,，、;；]+")).filter { it.isNotEmpty() }
        val pool = all().filter { kind == null || it.kind == kind }
        meta(query.trim())?.takeIf { it.status != Status.ARCHIVED }?.let { return listOf(it) }
        if (words.isEmpty()) return pool.sortedByDescending { it.updated }.take(limit)
        return pool.map { m ->
            val hay = (m.title + " " + m.summary + " " + m.kind + " " + m.links.joinToString(" ")).lowercase()
            m to words.count { hay.contains(it) }
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Meta, Int>> { it.second }.thenByDescending { it.first.updated }).map { it.first }.take(limit)
    }

    // ---- reading a body -----------------------------------------------------------

    /**
     * The body, from its source, now. A read that works refreshes the index entry (summary, verified, back to
     * `active` if it was `lost`); one that cannot reach it marks the entry `lost` and says so — it does not
     * fall back to the old summary as if it were the article.
     */
    fun read(id: String): Result<Article> = runCatching {
        val m = meta(id) ?: throw IllegalArgumentException("No article with id $id; search first.")
        val store = stores[m.where] ?: throw IllegalStateException("There is no backend \"${m.where}\" on this device, so ${m.title} cannot be read here.")
        val body = try {
            store.read(m.ref)
        } catch (e: WikiUnavailable) {
            update(m.copy(status = Status.LOST, reason = e.message.orEmpty().take(200)))
            throw IllegalStateException("${m.title} could not be reached (${e.message}). I have marked it lost; my old summary may be out of date, so do not rely on it.")
        }
        val fresh = m.copy(summary = summarize(body), verified = clock(), status = if (m.status == Status.LOST) Status.ACTIVE else m.status, reason = if (m.status == Status.LOST) "" else m.reason)
        update(fresh)
        Article(fresh, body)
    }

    // ---- writing ---------------------------------------------------------------------

    /**
     * Create or revise. [id] null = a new article, refused when a close one exists (revise that one), or when the
     * limits are hit. [byUser] is for the settings page. Returns the new [Meta] or an error sentence.
     *
     * [madeBy] is the tool-call id behind this version — recorded so a later "I wrote that" can point at it.
     */
    fun write(
        id: String?, title: String, kind: String, body: String, source: String, changeNote: String,
        level: Level?, links: List<String>?, where: String = "local", madeBy: String = "", byUser: Boolean = false,
        status: Status? = null,
    ): Result<Meta> = runCatching {
        require(kind in KINDS) { "kind is one of $KINDS" }
        require(title.isNotBlank()) { "title is required" }
        require(body.isNotBlank()) { "the body is empty" }
        require(body.length <= MAX_BODY) { "Too long: ${body.length} characters, the limit is $MAX_BODY. Split it into articles that link to each other." }
        if (!byUser) {
            require(source.isNotBlank()) { "Say where this comes from — looked up (with the source), he said, or my inference. An article without one cannot be trusted later." }
            require(changeNote.isNotBlank()) { "Say what you changed and why (changeNote)." }
        }
        val store = stores[where] ?: throw IllegalStateException("No backend \"$where\".")
        val existing = id?.let { meta(it) ?: throw IllegalArgumentException("No article with id $id.") }
        if (existing == null) {
            // The way it stays a wiki: a near-duplicate means revise that one.
            search(title, kind, 3).firstOrNull { sameSubject(it.title, title) }?.let {
                throw IllegalStateException("There is already an article on this: \"${it.title}\" (id ${it.id}, ${it.status.key}, v${it.version}). Revise it — pass id=${it.id} — rather than starting a second one. If it really is a different subject, change the title so it says how.")
            }
            if (all().size >= MAX_ARTICLES) {
                throw IllegalStateException("The wiki holds $MAX_ARTICLES articles already. Tidy first: merge duplicates, archive what is finished or out of date (wiki_archive).")
            }
        } else if (existing.byUser && !byUser) {
            throw IllegalStateException("The user edited \"${existing.title}\", so say what you would change and why, and let them decide.")
        }
        val now = clock()
        val ref = store.write(existing?.ref, title, withHeader(title, kind, level ?: existing?.level ?: Level.INTERNAL, source, existing?.version?.plus(1) ?: 1, body), changeNote)
        val m = Meta(
            id = existing?.id ?: ("w" + now.toString(36) + all(true).size),
            title = title.trim(), kind = kind, status = status ?: existing?.status?.takeIf { it != Status.LOST } ?: Status.DRAFT,
            level = level ?: existing?.level ?: Level.INTERNAL, where = where, ref = ref,
            source = source.trim().ifEmpty { existing?.source.orEmpty() }, summary = summarize(body),
            version = (existing?.version ?: 0) + 1, updated = now, links = links ?: existing?.links ?: emptyList(),
            verified = now, byUser = byUser || (existing?.byUser ?: false), reason = "", madeBy = madeBy,
        )
        put(m)
        m
    }

    /**
     * Record that a document exists somewhere — a shared Feishu doc, say — without copying it. This is how
     * Andee "knows" an outside document: a lead (title, where, link), to be read from the source when needed.
     */
    fun know(title: String, kind: String, where: String, ref: String, source: String, level: Level = Level.INTERNAL, summary: String = "", links: List<String> = emptyList()): Result<Meta> = runCatching {
        require(kind in KINDS) { "kind is one of $KINDS" }
        require(ref.isNotBlank()) { "ref is required" }
        all(true).firstOrNull { it.where == where && it.ref == ref }?.let { return@runCatching it }
        if (all().size >= MAX_ARTICLES) throw IllegalStateException("The wiki holds $MAX_ARTICLES articles already; tidy first.")
        val now = clock()
        val m = Meta("w" + now.toString(36) + all(true).size, title.trim(), kind, Status.ACTIVE, level, where, ref, source.trim().ifEmpty { "a document shared with me" },
            summary.take(MAX_SUMMARY), 0, now, links, 0L, false, "", "")
        put(m)
        m
    }

    fun setStatus(id: String, status: Status, reason: String, byUser: Boolean = false): String? = synchronized(lock) {
        val m = meta(id) ?: return "No article with id $id."
        if (status == Status.STALE && reason.isBlank()) return "Say why it is out of date — the next reader needs to know what changed."
        if (m.byUser && !byUser && status == Status.ARCHIVED) return "The user edited this article, so archiving it is theirs to decide."
        if (status == Status.ARCHIVED) stores[m.where]?.let { runCatching { it.archive(m.ref) } }
        update(m.copy(status = status, reason = reason.trim().take(200), updated = clock()))
        null
    }

    fun setLevel(id: String, level: Level, byUser: Boolean = false): String? = synchronized(lock) {
        val m = meta(id) ?: return "No article with id $id."
        update(m.copy(level = level, byUser = m.byUser || byUser, updated = clock()))
        null
    }

    fun link(id: String, to: String): String? = synchronized(lock) {
        val m = meta(id) ?: return "No article with id $id."
        require(Regex("^(care|person|wiki):.+").matches(to)) { "a link is care:<id>, person:<id> or wiki:<id>" }
        update(m.copy(links = (m.links + to).distinct()))
        null
    }

    /** The user deleted it. The entry goes; a local file goes with it. */
    fun deleteByUser(id: String) = synchronized(lock) {
        val m = meta(id) ?: return@synchronized
        stores[m.where]?.let { runCatching { it.archive(m.ref) } }
        val root = load()
        val a = root.optJSONArray("articles") ?: return@synchronized
        val keep = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { it.optString("id") != id }?.let { keep.put(it) }
        root.put("articles", keep); save(root)
    }

    /** Share through the backend. A level of [Level.SECRET] or above comes back with a reminder; it does not stop. */
    fun share(id: String, to: String, permission: String): Result<String> = runCatching {
        val m = meta(id) ?: throw IllegalArgumentException("No article with id $id.")
        val store = stores[m.where] ?: throw IllegalStateException("No backend \"${m.where}\".")
        val r = store.share(m.ref, to, permission)
        if (!r.ok) throw IllegalStateException(r.note)
        val warn = if (m.level.rank >= Level.SECRET.rank) " Note: this article is marked ${m.level.key}." else ""
        r.note + warn
    }

    // ---- the prompt line ----------------------------------------------------------------

    /** One line plus the few most relevant titles; the body is never in it. Empty when there is nothing. */
    fun indexForPrompt(max: Int = 3): String = synchronized(lock) {
        val live = all().filter { it.status == Status.ACTIVE || it.status == Status.DRAFT }
        if (live.isEmpty()) return ""
        val top = live.sortedByDescending { it.updated }.take(max)
        "Your wiki: ${live.size} article(s). Newest: " + top.joinToString("; ") { "「${it.title}」(${it.kind}, ${it.status.key}, v${it.version}, ${it.level.key}) #${it.id}" } + ". wiki_search before you write anything."
    }

    /** The auto-maintained contents page: all live articles by kind. Written to the wiki folder as `INDEX.md`. */
    fun contents(): String = buildString {
        append("# 目录\n\n")
        val live = all()
        for (k in KINDS) {
            val g = live.filter { it.kind == k }.sortedByDescending { it.updated }
            if (g.isEmpty()) continue
            append("## $k\n")
            g.forEach { append("- [${it.title}](${it.ref}) — ${it.status.key} · ${it.level.key} · v${it.version} · ${iso(it.updated)}${if (it.summary.isNotBlank()) "  \n  ${oneLine(it.summary, 120)}" else ""}\n") }
            append('\n')
        }
        all(true).filter { it.status == Status.LOST }.takeIf { it.isNotEmpty() }?.let { lost ->
            append("## 失联\n"); lost.forEach { append("- ${it.title} (${it.where}) — ${it.reason}\n") }
        }
    }

    // ---- internals --------------------------------------------------------------------------

    private fun update(m: Meta) = synchronized(lock) { put(m) }

    private fun put(m: Meta) {
        val root = load()
        val a = root.optJSONArray("articles") ?: JSONArray()
        val out = JSONArray()
        var done = false
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.optString("id") == m.id) { out.put(toJson(m)); done = true } else out.put(o)
        }
        if (!done) out.put(toJson(m))
        root.put("articles", out); save(root)
    }

    private fun toMeta(o: JSONObject) = Meta(
        o.optString("id"), o.optString("title"), o.optString("kind", "note"), Status.of(o.optString("status")), Level.of(o.optString("level")),
        o.optString("where", "local"), o.optString("ref"), o.optString("source"), o.optString("summary"), o.optInt("version"),
        o.optLong("updated"), (o.optJSONArray("links") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) } },
        o.optLong("verified"), o.optBoolean("by_user"), o.optString("reason"), o.optString("made_by"),
    )

    private fun toJson(m: Meta) = JSONObject().put("id", m.id).put("title", m.title).put("kind", m.kind).put("status", m.status.key)
        .put("level", m.level.key).put("where", m.where).put("ref", m.ref).put("source", m.source).put("summary", m.summary)
        .put("version", m.version).put("updated", m.updated).put("links", JSONArray(m.links)).put("verified", m.verified)
        .put("by_user", m.byUser).put("reason", m.reason).put("made_by", m.madeBy)

    companion object {
        const val SECTION = "wiki"
        const val MAX_ARTICLES = 200
        const val MAX_BODY = 40_000
        const val MAX_SUMMARY = 240

        /**
         * A short digest for the index and the notebook's preview: the article's prose, without its markup —
         * headings, tables and code dropped (a flattened table is a row of pipes and dashes). Same parser that
         * draws the article ([net.kuafuai.andee.text.Md]).
         */
        fun summarize(body: String): String = net.kuafuai.andee.text.Md.preview(body, MAX_SUMMARY)

        /** Two titles name the same subject if one contains the other or they share most of their characters. */
        fun sameSubject(a: String, b: String): Boolean {
            val x = norm(a); val y = norm(b)
            if (x.isEmpty() || y.isEmpty()) return false
            if (x == y || x.contains(y) || y.contains(x)) return true
            val sa = x.toSet(); val sb = y.toSet()
            return sa.intersect(sb).size.toDouble() / maxOf(sa.size, sb.size) >= 0.8
        }

        private fun norm(s: String) = s.lowercase().replace(Regex("[\\s\\p{Punct}，。、：；！？（）《》「」『』【】—·v\\d]+"), "")

        fun withHeader(title: String, kind: String, level: Level, source: String, version: Int, body: String): String =
            "---\ntitle: $title\nkind: $kind\nconfidentiality: ${level.key}\nsource: ${oneLine(source, 160)}\nversion: $version\n---\n\n${body.trim()}\n"
    }
}

/** The folder backend: articles are `.md` files in a directory the user can open, edit and share. */
class LocalWikiStore(private val dir: File) : WikiStore {
    override val key = "local"
    override val capabilities = WikiStore.Capabilities(versionHistory = true, sharing = setOf("file"))

    init { dir.mkdirs(); File(dir, ".history").mkdirs(); File(dir, "archive").mkdirs() }

    override fun read(ref: String): String {
        val f = resolve(ref)
        if (!f.isFile) throw WikiUnavailable("the file ${f.name} is not in the wiki folder any more")
        return f.readText().substringAfter("\n---\n", f.readText()).trim()
    }

    override fun write(ref: String?, title: String, body: String, changeNote: String): String {
        val name = ref ?: unique(slug(title))
        val f = resolve(name)
        // Every version stays: the previous file is moved to .history with its version number, never overwritten.
        if (f.isFile) {
            val v = Regex("""\nversion: (\d+)""").find(f.readText())?.groupValues?.get(1) ?: "0"
            f.copyTo(File(File(dir, ".history"), f.nameWithoutExtension + ".v$v.md"), overwrite = true)
        }
        f.writeText(body)
        return name
    }

    override fun share(ref: String, to: String, permission: String) =
        WikiStore.Share(true, "The file is ${resolve(ref).absolutePath}; send it to $to the way you would any file.")

    override fun archive(ref: String) {
        val f = resolve(ref)
        if (f.isFile) f.renameTo(File(File(dir, "archive"), f.name))
    }

    fun folder(): File = dir

    private fun resolve(ref: String): File {
        val f = File(dir, ref).canonicalFile
        // A ref comes from a model: it must stay inside the wiki folder.
        require(f.path.startsWith(dir.canonicalPath + File.separator)) { "that path is outside the wiki folder" }
        return f
    }

    private fun slug(title: String): String =
        title.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").trim('-').take(60).ifEmpty { "untitled" } + ".md"

    private fun unique(name: String): String {
        if (!File(dir, name).exists()) return name
        val base = name.removeSuffix(".md")
        var i = 2
        while (File(dir, "$base-$i.md").exists()) i++
        return "$base-$i.md"
    }
}
