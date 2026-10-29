package net.kuafuai.andee.ui

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What has been said and shown on this device, in order, so the card has
 * something to scroll.
 *
 * Deliberately *this* side's record and not the brain's transcript. The brain
 * has the real conversation — tool calls, reasoning, the lot — but it lives
 * behind a socket that drops, and asking it for scrollback would mean the list
 * is empty exactly when the network is the thing that broke. What the device
 * saw with its own eyes and said with its own speaker is always available, and
 * it is also the only part the user actually witnessed, which makes it the part
 * they would be scrolling back to find.
 *
 * Three kinds of entry, because three things happen in front of the user: they
 * speak ([Role.USER]), the assistant answers out loud ([Role.ASSISTANT]), and
 * the assistant draws a page ([Role.PAGE]). Only the third is re-openable, and
 * it is the reason this class owns files at all. [Role.ERROR] is the fourth and
 * differs in kind: it is not something anyone said, it is the device failing,
 * and it is here because the card that announces a failure times out while the
 * question "what went wrong a minute ago" does not.
 *
 * **A page is a *version* of an artifact, not an artifact.** Revising a page
 * used to archive the result as a brand-new row, so 继续修改 twice left three
 * near-identical entries on the shelf and no way to tell which one was current
 * — the user asked for "a new version", not "another page". Rows now carry
 * [Entry.groupId] (which artifact) and [Entry.version] (which revision of it),
 * one page = one artifact in the panel, and the older revisions stay on disk
 * behind it. See [artifacts] / [versionsOf] / [restoreVersion].
 *
 * Singleton with an [init] rather than a constructor-injected dependency,
 * matching [CardUi]: the appenders are scattered across the service, the
 * dispatcher and the hub client, and threading one object through all of them
 * buys nothing when there is exactly one device.
 *
 * Thread-safe. Appends arrive from the ASR callback, the hub receive thread and
 * dispatcher workers; reads come from the UI thread.
 */
object ChatHistory {

    /**
     * [EVENT] is something that happened that is not conversation — a todo woke it, the review ran, it wrote its
     * diary, it got up by itself ([PhoneTimeline]). Shown in the scrollback, **never** given to the brain:
     * `AndroidBrainHost.scrollback` leaves these rows out, so a review summary cannot come back as something said.
     */
    enum class Role { USER, ASSISTANT, PAGE, ERROR, EVENT }

    /**
     * @param page for [Role.PAGE], the archived HTML — null once it has been
     *   evicted, which is how a row knows to stop offering to re-open itself.
     * @param turn which run of the brain produced this row, named the way
     *   [net.kuafuai.andee.brain.BrainTrace] names it, or null when there is no
     *   log to open — see [addAssistant].
     * @param groupId which artifact this row is a revision of. For the first
     *   version it is the row's own [id]; every later version carries the
     *   first one's, so the rows of one artifact can be gathered back together
     *   (see [artifacts]). Meaningless for the three non-page roles, which
     *   carry their own id and are never grouped.
     * @param version 1-based, within [groupId]. Only ever increases, including
     *   across a [restoreVersion] — going back to an older revision makes a
     *   *new* one, so nothing already written is silently rewritten.
     * @param topicId the topic this row has been folded into, or null while it
     *   still stands on its own. Set only by [foldTopic]; the conversation list
     *   reads it to decide what to collapse. Orthogonal to [groupId]: a page
     *   drawn while talking about something belongs both to its own artifact
     *   and to that conversation.
     */
    data class Entry(
        val id: Long,
        val ts: Long,
        val role: Role,
        val text: String,
        val page: File?,
        val turn: String? = null,
        val groupId: Long = id,
        val version: Int = 1,
        val topicId: Long? = null,
        /** [Role.EVENT] only: what kind of event, the line shown when opened, and what it opens (a wiki id). */
        val kind: String = "",
        val detail: String = "",
        val ref: String = "",
    )

    /**
     * A stretch of conversation that has finished and been put away.
     *
     * **The other half of the 产物 idea, on the time axis.** An artifact groups
     * the revisions of one *thing*; a topic groups a stretch of *talking*. They
     * are orthogonal on purpose — a page drawn while planning a trip belongs to
     * its own artifact and to that conversation at once.
     *
     * The point of folding is the context window, not tidiness: a finished
     * conversation is the most expensive thing in the request and the least
     * likely to be needed again, so it is replaced by [summary] and the brain
     * reads the whole thing back with `get_topic` when the user actually
     * returns to it. `artifact`'s `get_page` is the same trade.
     *
     * @param summary what the brain wrote when it folded: where it got to, what
     *   was left, and **what was left out** — that last part is what keeps the
     *   read-back door in use instead of merely available. See `TOPIC_RULES`.
     * @param startedAt/[endedAt] the stretch this covers, which is also how the
     *   conversation list decides which rows to collapse.
     * @param parentId the topic this one continues, or null. Continuing a
     *   finished topic starts a *new* one rather than reopening it — a topic
     *   that has ended is a fact about the past, and a chain ("we went back to
     *   the price comparison three times") reads better as three than as one
     *   endless thread.
     */
    data class Topic(
        val id: Long,
        val title: String,
        val summary: String,
        val startedAt: Long,
        val endedAt: Long,
        val createdAt: Long,
        val parentId: Long? = null,
    )

    /**
     * Two separate caps, because the two costs are unrelated: a line of text is
     * a few hundred bytes and the list is only worth scrolling if it goes back
     * a while, whereas a generated page is routinely 50–100 KB of inlined CSS,
     * JS and base64 images — 100 of them is up to ~10 MB, the price of the 产物
     * panel being a shelf rather than a recent-items strip.
     *
     * A live page does not count against [MAX_ENTRIES]. It used to, and then the
     * page cap was fiction: 80 rows of chatter pushed pages out (file and all)
     * long before [MAX_PAGES] was reached.
     *
     * A page evicted from disk leaves its row behind with `page = null`. The
     * row stays because "you showed me a 贪吃蛇 at 3pm" is still true and still
     * worth scrolling past; it just stops claiming to be re-openable.
     */
    private const val MAX_ENTRIES = 80
    private const val MAX_PAGES = 100

    /**
     * How much of a message is kept.
     *
     * It used to be 400 — "long enough to identify a row at a glance, short
     * enough to stay cheap" — and that was right while a row *was* the message:
     * six lines on screen, and the rest never seen. It stopped being right when
     * a row gained a reader: double-tap opens the message fullscreen
     * ([TextPage]), and a 400-char cap would mean the reader showed the same
     * sentence and a half the row already showed, with the rest gone.
     *
     * So the cap is now about how long an answer can be rather than about how
     * much fits in a bubble. 4000 characters is roughly three screens of
     * Chinese — past what any spoken answer gets to — and it costs 40 KB per
     * row at [MAX_ENTRIES], which is the JSON rewritten on each message.
     */
    private const val MAX_TEXT = 4000

    private const val TAG = "Body"

    private var appContext: Context? = null

    /** For callers with no Context of their own that need one to word a row (see [PhoneTimeline]). */
    fun appContextOrNull(): Context? = appContext

    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private var nextId = 1L

    /**
     * Folded conversations, oldest first.
     *
     * Held here rather than in the notebook, and deliberately **not** encrypted:
     * a topic points at a stretch of [entries], the rows it covers live in this
     * file, and splitting the two across two stores would guarantee they drift
     * — the same reasoning that put the artifact `group`/`version` keys in this
     * file. The text a topic folds is the plaintext conversation that was
     * already here, so a second layer over the copy would protect nothing the
     * original did not already expose.
     */
    private val topics = ArrayList<Topic>()
    private var nextTopicId = 1L

    /**
     * A folded stretch's conversation, verbatim, as the brain will read it back.
     *
     * **A snapshot in its own file, not a reference to row ids.** The rows it was
     * folded from are ordinary [entries] and are subject to [MAX_ENTRIES] — the
     * oldest are dropped as the conversation grows. Reconstructing the text from
     * ids would therefore work right up until the day it silently returned half a
     * conversation, which is the worst possible failure for the feature the
     * read-back door exists to serve. The file is the record; the rows in the
     * list are the *view* of it.
     *
     * **`files/topics/topic_<id>.txt`, exactly like `files/pages/page_<id>.html`.**
     * It was briefly going to live inside `chat_history.json` with the rest, and
     * that was wrong for a measurable reason: that file is rewritten in full on
     * *every* append, and a folded conversation can be a few hundred KB. One big
     * blob per topic, written once when it folds and read only when someone asks
     * for it, is the shape that was already proven here for generated pages.
     */
    private fun topicFile(id: Long): File? =
        appContext?.let { File(File(it.filesDir, "topics").apply { mkdirs() }, "topic_$id.txt") }

    /** Notified on every change; the card re-reads [snapshot] and redraws. */
    @Volatile
    private var listener: (() -> Unit)? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (appContext != null) return
            appContext = context.applicationContext
            load()
        }
        listener?.invoke()
    }

    fun setListener(l: (() -> Unit)?) {
        listener = l
    }

    /** Oldest first — the order the card lays rows out in. */
    fun snapshot(): List<Entry> = synchronized(lock) { ArrayList(entries) }

    /** How many revisions one artifact is allowed to keep. */
    private const val MAX_VERSIONS = 20

    /**
     * Fold a page list down to **one row per artifact**, newest first.
     *
     * Pure, and separated from [artifacts] / [pages] so the rule can be tested
     * without a device: the two callers differ only in what they do about a
     * revision whose file is gone, and having that difference be one argument
     * is what keeps "the panel and the brain agree about what exists" true by
     * construction rather than by review.
     *
     * @param keepMissing when true, an artifact whose every revision has lost
     *   its file still gets a row — its newest revision. The brain's list wants
     *   that (a page that is gone is still a page that was made); the panel
     *   does not (a row that offers to open nothing is worse than an empty
     *   shelf, and [artifacts] shows the newest revision *that is there*).
     */
    internal fun collapseToArtifacts(
        all: List<Entry>,
        keepMissing: Boolean,
    ): List<Entry> = all
        .filter { it.role == Role.PAGE }
        .groupBy { it.groupId }
        .values
        .mapNotNull { revs ->
            revs.filter { it.page != null }.maxByOrNull { it.version }
                ?: if (keepMissing) revs.maxByOrNull { it.version } else null
        }
        .sortedByDescending { it.ts }

    /**
     * The version number a new revision of [groupId] should get: one past the
     * highest already used, or 1 when the artifact is new.
     *
     * Pure for the same reason as [collapseToArtifacts]. Monotonic on purpose —
     * nothing here reuses a number, so [restoreVersion] can file old bytes as a
     * new version and leave the history append-only.
     */
    internal fun nextVersionOf(all: List<Entry>, groupId: Long): Int =
        all.filter { it.role == Role.PAGE && it.groupId == groupId }
            .maxOfOrNull { it.version }?.plus(1) ?: 1

    /**
     * What a `show_html` should be filed under, and the target to carry into
     * the rest of the turn.
     *
     * `explicit` is the model naming a page it wants to revise; `armed` is what
     * the turn has established so far (a `get_page`, or the panel's 继续修改).
     * Explicit wins. **Either way the target comes back cleared**, and that is
     * the load-bearing half: a target is good for *one* page, so a turn that
     * reads a page back and then draws two of them gets a revision and then a
     * new artifact — not two revisions of the same thing.
     *
     * Found the hard way: `get_page` followed by two `show_html` calls left the
     * second, unrelated page numbered 「v5」 of the first, because the target was
     * read without being consumed.
     *
     * @return the group to file under (null = a new artifact), and the target
     *   the caller should keep for the next call in this turn.
     */
    internal fun resolveRevisionTarget(
        explicit: Long?,
        armed: Long?,
    ): Pair<Long?, Long?> = if (explicit != null) explicit to null else armed to null

    /**
     * One row per **artifact**, newest first — what the 产物 panel lists.
     *
     * Not one row per page file: revising a page appends a new revision rather
     * than replacing the old one, so a raw filter would show 番茄钟 three times
     * after two rounds of 继续修改, with nothing saying which is current. This
     * gathers each artifact's revisions and keeps the **newest available** one
     * — available, not merely newest, because the 100-page cap can take a
     * revision's file while an older one survives, and a panel row that offers
     * to open something which is not there is worse than one showing an older
     * revision that is.
     *
     * The row still carries its own [Entry.version], which is what the panel
     * prints as 「v3」 and what [versionsOf] takes to list the rest.
     */
    fun artifacts(): List<Entry> = synchronized(lock) {
        collapseToArtifacts(entries, keepMissing = false)
    }

    /**
     * Every revision of one artifact, **newest version first**, including the
     * ones whose file is gone.
     *
     * For [net.kuafuai.andee.ui.ArtifactsUi]'s version list. Unlike [artifacts]
     * this does not drop a missing file: a version whose bytes are gone is
     * still a fact about the artifact's history, and the list has to be able to
     * say "v2 was here, it is not any more" rather than quietly skip from v3
     * to v1.
     */
    fun versionsOf(groupId: Long): List<Entry> = synchronized(lock) {
        entries.filter { it.role == Role.PAGE && it.groupId == groupId }
            .sortedByDescending { it.version }
    }

    /** The artifact's newest revision that still has bytes, or null if none do. */
    fun currentOf(groupId: Long): Entry? = synchronized(lock) {
        entries.filter { it.role == Role.PAGE && it.groupId == groupId && it.page != null }
            .maxByOrNull { it.version }
    }

    /**
     * Every page ever archived, newest first — **including the rows whose file
     * is gone**.
     *
     * [artifacts] cannot serve the brain: it filters on `page != null`, which is
     * right for a panel that offers to open one and wrong for a question about
     * what was made. A row whose file the 100-page cap has taken still records
     * that the page existed and when — the honest answer to 「上次那个番茄钟」 for
     * a page that is actually gone — and a brain that cannot see the row answers
     * "I never made that", which is a failure the user can check against the
     * panel in front of them.
     *
     * **Collapsed to one row per artifact, the newest available revision** — the
     * same shape [artifacts] gives the panel, for the same reason. The brain
     * asks "what have I made"; twelve rows of one timer's revision history is
     * not an answer to that. A superseded revision is not lost — `get_page`
     * takes any id [versionsOf] would show — but it does not crowd the list.
     */
    fun pages(): List<Entry> = synchronized(lock) {
        collapseToArtifacts(entries, keepMissing = true)
    }

    // ---- topics: finished stretches of conversation ----

    /** How much of a folded conversation `get_topic` hands over in one go. */
    const val TOPIC_TEXT_MAX = ScrollbackLimits.TOPIC_TEXT_MAX

    /** How many folded conversations are kept. Oldest files go first. */
    private const val MAX_TOPICS = 60

    /** Every folded topic, **newest first** — how `list_topics` and the panel read them. */
    fun topics(): List<Topic> = synchronized(lock) { topics.sortedByDescending { it.endedAt } }

    /** One topic by id, or null. */
    fun topicById(id: Long): Topic? = synchronized(lock) { topics.firstOrNull { it.id == id } }

    /**
     * The verbatim conversation a topic covers, or null when its file is gone.
     *
     * Never throws and never guesses: a missing file is reported as missing, the
     * same way `get_page` reports a page whose bytes the 100-page cap took.
     * Reconstructing it from the rows still in the list would be worse than
     * failing — it would hand back a plausible, partial conversation.
     */
    fun topicText(id: Long): String? =
        topicFile(id)?.takeIf { it.exists() }?.let { f -> runCatching { f.readText() }.getOrNull() }

    /**
     * How many rows carry [topicId] — what the conversation list collapses.
     *
     * Read off the live rows rather than stored on the topic, because rows are
     * dropped by [MAX_ENTRIES] as the conversation grows and a stored count
     * would drift away from what is actually on screen.
     */
    fun rowCountOf(topicId: Long): Int = synchronized(lock) {
        entries.count { it.topicId == topicId }
    }

    /** Rows belonging to one topic, oldest first. */
    fun rowsOf(topicId: Long): List<Entry> = synchronized(lock) {
        entries.filter { it.topicId == topicId }
    }

    /**
     * The artifacts made during a topic, one per artifact, newest first — the
     * topic card's 产出物 list.
     *
     * Each is the artifact's **current** version, wherever it was made: a page
     * started in this topic and revised later is still this topic's output, and
     * reopening the version from back then would show the user something they
     * have since changed. Only artifacts with a file left are listed — a row that
     * offers to open nothing is worse than a shorter list.
     */
    fun outputsOf(topicId: Long): List<Entry> = synchronized(lock) {
        val groups = entries.filter { it.topicId == topicId && it.role == Role.PAGE }.map { it.groupId }.toSet()
        collapseToArtifacts(entries.filter { it.groupId in groups }, keepMissing = false)
    }

    /**
     * Put a finished stretch of conversation away.
     *
     * Marks the rows with [Topic.id] (which is what makes the list collapse
     * them, and is why the rows are not deleted — the user still scrolls past
     * "we talked about this at 3pm") and writes the conversation to its own file
     * so `get_topic` can read it back verbatim later.
     *
     * **@param rows the entries this covers, oldest first**, already decided by
     *   the caller — the device decides *which* rows, the model only decided
     *   that the stretch had ended. See the topic-folding notes on
     *   `note.fold`.
     * **@param text the conversation verbatim**, as the brain will read it
     *   back. Passed in rather than rebuilt from [rows] because the brain's own
     *   history is the real one — the rows here are the device's summary view of
     *   it, with each message already capped at [MAX_TEXT].
     *
     * Returns the new topic, or null when there is nothing to fold.
     */
    fun foldTopic(
        title: String,
        summary: String,
        rows: List<Entry>,
        text: String,
        parentId: Long? = null,
    ): Topic? {
        if (rows.isEmpty()) return null
        val ctx = appContext ?: return null
        val now = System.currentTimeMillis()
        val topic = synchronized(lock) {
            val id = nextTopicId++
            runCatching {
                val dir = File(ctx.filesDir, "topics").apply { mkdirs() }
                File(dir, "topic_$id.txt").writeText(text)
            }.onFailure {
                Log.w(TAG, "history: could not archive topic: ${it.message}")
                return null
            }
            val t = Topic(
                id = id,
                title = title.trim().ifEmpty { rows.first().text.take(40) },
                summary = summary.trim(),
                startedAt = rows.first().ts,
                endedAt = rows.last().ts,
                createdAt = now,
                parentId = parentId,
            )
            topics.add(t)
            // Stamp the rows so the list collapses them and the panel can count.
            val ids = rows.map { it.id }.toSet()
            for (i in entries.indices) {
                if (entries[i].id in ids) entries[i] = entries[i].copy(topicId = id)
            }
            evictTopics()
            save()
            t
        }
        listener?.invoke()
        return topic
    }

    /** Caller holds [lock]. Oldest topics — and their files — go first. */
    private fun evictTopics() {
        while (topics.size > MAX_TOPICS) {
            val gone = topics.minByOrNull { it.endedAt } ?: return
            topics.remove(gone)
            topicFile(gone.id)?.let { runCatching { it.delete() } }
            // Its rows stay, but they are no longer covered by anything — read
            // as "never folded" rather than as a topic id that resolves to
            // nothing, which the list would render as an empty collapse.
            for (i in entries.indices) {
                if (entries[i].topicId == gone.id) entries[i] = entries[i].copy(topicId = null)
            }
        }
    }

    /**
     * One line per recent artifact for the per-turn user message; empty when
     * there are none.
     *
     * **Why this rides on the user message and not in the system prompt.**
     * Same reason the notebook index does (see [LocalBrain]): `LocalPrompt` is a
     * constant precisely so DeepSeek's prefix cache survives, and the system
     * message is the head of the request — anything injected there invalidates
     * the whole conversation behind it. The user message is the one part of the
     * request that is already new every turn (it carries the clock), so this
     * costs nothing that was not already being paid.
     *
     * **Why an index as well as `list_pages`.** A tool answers a question the
     * model thought to ask. The failure this is here to prevent is the one
     * where it does not think to ask — 「上次那个表格改一下」 drawing a brand new
     * table, because nothing in front of it said the old one still existed. A
     * line in the context is the difference between "there is a tool for that"
     * and knowing the thing is there.
     *
     * One line per artifact, and the version is printed: a model revising
     * 「番茄钟 (v3)」 knows it is working on a page that has already been through
     * three rounds, which is the difference between continuing someone's work
     * and starting over on top of it.
     */
    fun indexForPrompt(max: Int = 5): String {
        val recent = pages().take(max)
        if (recent.isEmpty()) return ""
        // `MM-dd HH:mm` and not `yyyy-MM-dd`: the year is noise on a shelf of
        // things made on this device, and the model reads a month-day fine.
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        return recent.joinToString("\n") { e ->
            val bytes = e.page?.length() ?: 0L
            val state = if (bytes > 0) "${(bytes + 1023) / 1024} KB" else "file gone"
            "- #${e.id} ${e.text} (v${e.version} · ${fmt.format(Date(e.ts))} · $state)"
        }
    }

    /**
     * One page by the id [pages] reported, or null when nothing has that id.
     *
     * Filtered on [Role.PAGE] and not merely on the id: ids are shared with the
     * chatter rows, and a caller that read an id off a stale list would
     * otherwise be handed someone's sentence as if it were a page.
     *
     * **Any revision is addressable, not only the current one.** Once a
     * revision has an id the brain may hold it across turns, and the user can
     * ask for "the version before this one" — so an id that names a superseded
     * revision has to keep working rather than answer "no such page".
     */
    fun pageById(id: Long): Entry? = synchronized(lock) {
        entries.firstOrNull { it.id == id && it.role == Role.PAGE }
    }

    /**
     * One line per recent topic for the per-turn user message; empty when there
     * are none.
     *
     * Same position and same reason as [indexForPrompt]: this list changes the
     * moment anything folds, and the system prompt is the one place it must never
     * go. `list_topics` has the full list; this is the newest few, and its whole
     * job is to make the shelf *visible* — 「上次那个比价」 answered from memory
     * is the failure, and a model that can see the topic knows to read it back.
     *
     * The counts are printed for the reason the version is printed on a page: it
     * tells the model how much is behind the door before it decides to open it.
     */
    fun topicsForPrompt(max: Int = 5): String {
        val recent = topics().take(max)
        if (recent.isEmpty()) return ""
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        return recent.joinToString("\n") { t ->
            val rows = rowCountOf(t.id)
            "- #${t.id} ${t.title} (${fmt.format(Date(t.endedAt))} · $rows msgs)"
        }
    }

    /**
     * How much of a page's source is handed to the brain in one go.
     *
     * Two callers, so it lives here rather than at either one: the 继续修改
     * prompt ([net.kuafuai.andee.BodyCoreService.artifactEditQuery], which
     * inlines a page so a revision is a revision) and `ui.get_page` (which
     * reads one back so the brain can revise or answer about it unaided).
     * They must not drift — the same page has to come back the same size
     * whichever door it went out of.
     *
     * A generated page is routinely 10–60 KB of inline CSS and JS, which is
     * nothing to the request; one with a base64 image can be several hundred
     * KB, which is most of a context window for a picture the brain already
     * drew and cannot usefully redraw from text.
     */
    const val ARTIFACT_SOURCE_MAX = ScrollbackLimits.ARTIFACT_SOURCE_MAX

    /**
     * Delete one artifact — every revision of it — and keep one row behind in
     * its place, the same end state eviction leaves: "you showed me a page at
     * 3pm" stays true after the page is gone.
     *
     * **It takes a group, not a revision.** Deleting from the panel means
     * "get this off my shelf", and leaving v2 and v1 of the thing the user just
     * deleted still sitting in the version list would be the opposite of that.
     * A single revision is not something the panel offers to remove, which is
     * why this reads the group off [id] rather than trusting a caller to pass
     * the right one.
     */
    fun deletePage(id: Long) {
        synchronized(lock) {
            val hit = entries.firstOrNull { it.id == id && it.role == Role.PAGE } ?: return
            val group = hit.groupId
            // Newest first, so the row left standing is the current version —
            // the one whose title and date the user recognises.
            val doomed = entries.filter { it.role == Role.PAGE && it.groupId == group }
                .sortedByDescending { it.version }
            for (e in doomed) {
                e.page?.let { runCatching { it.delete() } }
                entries.remove(e)
            }
            entries.add(hit.copy(page = null, version = hit.version))
            save()
        }
        listener?.invoke()
    }

    /**
     * Delete everything and forget it. For 恢复出厂设置.
     *
     * Two halves, and both are needed: the files on disk, and the list this
     * singleton is holding in memory. Clearing only the files would leave the
     * card still scrolling a conversation whose every row had been deleted —
     * and the next append would write that list back out.
     *
     * The listener is invoked so an open card redraws empty instead of holding
     * rows whose backing pages no longer exist.
     */
    fun wipe(context: Context) {
        synchronized(lock) {
            entries.clear()
            topics.clear()
            nextId = 1L
            nextTopicId = 1L
        }
        runCatching {
            File(context.filesDir, "chat_history.json").delete()
            File(context.filesDir, "pages").deleteRecursively()
            // Under the same lock and the same call as the rows: a topic file
            // that outlived a factory reset would be a conversation the user
            // asked to be rid of, sitting on disk with nothing pointing at it.
            File(context.filesDir, "topics").deleteRecursively()
        }.onFailure { Log.w(TAG, "history: wipe failed: ${it.message}") }
        listener?.invoke()
    }

    fun addUser(text: String) = add(Role.USER, text, null)

    /**
     * The assistant's finished answer.
     *
     * @param turn the run of the local brain this came out of, so the row can be
     *   long-pressed into its own log (see [net.kuafuai.andee.ui.TracePage]).
     *   **Null is the normal case, not an oversight.** Most answers in the
     *   scrollback were not produced by a turn at all — a scene being entered, a
     *   notification read out, a line from `ScreenController` — and the hub
     *   brain writes no trace even when it did answer. A row with no [Entry.turn]
     *   simply has no log to offer, and must not pretend otherwise: an entry
     *   point that opens an empty page is worse than no entry point.
     *
     *   The caller reads it from [net.kuafuai.andee.brain.BrainTrace.currentTurn]
     *   *while the turn is still in flight*, which is safe because this is
     *   called from `onFinal` on the loop thread and the next turn cannot have
     *   started.
     */
    fun addAssistant(text: String, turn: String? = null) = add(Role.ASSISTANT, text, null, turn)

    /** Call through [CardUi.error], which de-dups bursts before they get here. */
    fun addError(text: String) = add(Role.ERROR, text, null)

    /** An event row — see [Role.EVENT]. Any thread. */
    fun addEvent(kind: String, text: String, detail: String = "", ref: String = "") =
        add(Role.EVENT, text, null, kind = kind, detail = detail.trim().take(MAX_TEXT), ref = ref)

    /** Event rows standing between the first and last of [rows]: they go into the same topic when it is folded. */
    fun eventsWithin(rows: List<Entry>): List<Entry> = synchronized(lock) {
        if (rows.isEmpty()) return emptyList()
        val lo = rows.minOf { it.id }; val hi = rows.maxOf { it.id }
        entries.filter { it.role == Role.EVENT && it.topicId == null && it.id in lo..hi }
    }

    /**
     * Archive a page the brain just composed and record it as a revision of an
     * artifact.
     *
     * Returns the archived file for the caller to hand to [HtmlActivity], so
     * the copy being displayed and the copy in the history are the same bytes
     * in the same place — an earlier split (cache dir for display, files dir
     * for the archive) meant re-opening from history could show something
     * subtly different from what was on screen a minute ago.
     *
     * **@param groupId which artifact this is a revision of.** Omitted (or
     *   null) means a brand-new artifact: the row becomes its own group and
     *   starts at v1. Pass an existing artifact's group — the panel's 继续修改
     *   does, and so does `ui.get_page`'s follow-up `show_html` — and this
     *   lands as its next revision instead of a near-identical neighbour on the
     *   shelf. Callers get the group from [Entry.groupId].
     *
     * Falls back to null on IO failure; the caller still has the HTML string
     * and can stage it itself, it just won't be in the list.
     */
    fun addPage(title: String, html: String, groupId: Long? = null): File? {
        val ctx = appContext ?: return null
        val (id, group, version) = synchronized(lock) {
            val g = groupId ?: nextId
            Triple(nextId, g, nextVersionOf(entries, g))
        }
        val file = runCatching {
            val dir = File(ctx.filesDir, "pages").apply { mkdirs() }
            File(dir, "page_$id.html").also { it.writeText(html) }
        }.getOrElse {
            Log.w(TAG, "history: could not archive page: ${it.message}")
            return null
        }
        add(Role.PAGE, title, file, groupId = group, version = version)
        return file
    }

    /**
     * Make an older revision current again — **as a new revision**, not by
     * rewriting the old one.
     *
     * Nothing already written is touched: the returned entry is the previous
     * version's bytes filed as the next version number, so the artifact's
     * history stays append-only and 撤销 is just another 回退. That is what
     * makes this safe to expose on a tap — a rollback that edited history in
     * place would leave no way back to the thing being rolled back from.
     *
     * Returns the new revision's file, or null when [id] names nothing, is not
     * a page, or has no bytes left to copy.
     */
    fun restoreVersion(id: Long): File? {
        val ctx = appContext ?: return null
        val source = synchronized(lock) { entries.firstOrNull { it.id == id && it.role == Role.PAGE } }
            ?: return null
        val bytes = source.page ?: return null
        val html = runCatching { bytes.readText() }.getOrNull() ?: return null
        return addPage(source.text, html, groupId = source.groupId)
    }

    private fun add(
        role: Role,
        text: String,
        page: File?,
        turn: String? = null,
        groupId: Long? = null,
        version: Int = 1,
        kind: String = "",
        detail: String = "",
        ref: String = "",
    ) {
        if (appContext == null) return
        val clean = text.trim().let {
            if (it.length > MAX_TEXT) it.take(MAX_TEXT) + "…" else it
        }
        if (clean.isEmpty()) return
        synchronized(lock) {
            val id = nextId++
            entries.add(
                Entry(
                    id = id,
                    ts = System.currentTimeMillis(),
                    role = role,
                    text = clean,
                    page = page,
                    turn = turn,
                    // A non-page row is never grouped, so it is its own group:
                    // that keeps `groupId` a total function over the list rather
                    // than a field that is meaningful for one role and garbage
                    // for the other three.
                    groupId = groupId ?: id,
                    version = version,
                    kind = kind,
                    detail = detail,
                    ref = ref,
                ),
            )
            trim()
            save()
        }
        listener?.invoke()
    }

    /** Caller holds [lock]. */
    private fun trim() {
        // Versions of one artifact first: a page revised twenty times should
        // not be able to push every other artifact off the shelf, and the
        // oldest revisions are the ones nobody is coming back for. Oldest
        // first, so the current version and the one before it always survive.
        val overVersions = entries.filter { it.role == Role.PAGE }
            .groupBy { it.groupId }
            .flatMap { (_, revs) ->
                revs.sortedBy { it.version }.dropLast(MAX_VERSIONS)
            }
        for (old in overVersions) {
            val i = entries.indexOfFirst { it.id == old.id }
            if (i < 0) continue
            old.page?.let { runCatching { it.delete() } }
            entries.removeAt(i)
        }
        // Pages next, so one that just lost its file is an ordinary row below.
        var pages = 0
        for (i in entries.indices.reversed()) {
            val e = entries[i]
            if (e.page == null) continue
            pages++
            if (pages > MAX_PAGES) {
                runCatching { e.page.delete() }
                entries[i] = e.copy(page = null)
            }
        }
        var over = entries.count { it.page == null } - MAX_ENTRIES
        val it = entries.iterator()
        while (over > 0 && it.hasNext()) {
            if (it.next().page == null) {
                it.remove()
                over--
            }
        }
    }

    // ---- Persistence ----
    //
    // A plain JSON file rather than SharedPreferences: this is an append-only
    // log with a per-row shape, and VoiceConfig's prefs file is the *settings*,
    // which are hand-edited and whitelisted (see its ALLOWED_KEYS). Rewriting
    // the whole log on every append is fine at 180 rows and saves having to
    // reason about partial writes.

    private fun file(): File? = appContext?.let { File(it.filesDir, "chat_history.json") }

    /**
     * The shape on disk.
     *
     * It used to be a bare array of rows. It is an object with `entries` and
     * `topics` now, because a topic is not a row and cannot be expressed as one
     * — but **the bare-array form is still read** ([load] sniffs which it has),
     * so a device updating from a build that predates topics opens its
     * conversation instead of an empty one.
     */
    private const val KEY_ENTRIES = "entries"
    private const val KEY_TOPICS = "topics"

    /** Caller holds [lock]. */
    private fun save() {
        val f = file() ?: return
        runCatching {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("ts", e.ts)
                        .put("role", e.role.name)
                        .put("text", e.text)
                        .put("page", e.page?.name ?: JSONObject.NULL)
                        .put("turn", e.turn ?: JSONObject.NULL)
                        .put("group", e.groupId)
                        .put("version", e.version)
                        .put("topic", e.topicId ?: JSONObject.NULL)
                        .apply { if (e.kind.isNotEmpty()) put("kind", e.kind); if (e.detail.isNotEmpty()) put("detail", e.detail); if (e.ref.isNotEmpty()) put("ref", e.ref) }
                )
            }
            val tarr = JSONArray()
            for (t in topics) {
                tarr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("title", t.title)
                        .put("summary", t.summary)
                        .put("started", t.startedAt)
                        .put("ended", t.endedAt)
                        .put("created", t.createdAt)
                        .put("parent", t.parentId ?: JSONObject.NULL)
                )
            }
            f.writeText(JSONObject().put(KEY_ENTRIES, arr).put(KEY_TOPICS, tarr).toString())
        }.onFailure { Log.w(TAG, "history: save failed: ${it.message}") }
    }

    /** Caller holds [lock]. */
    private fun load() {
        val f = file() ?: return
        if (!f.exists()) return
        runCatching {
            val dir = File(appContext!!.filesDir, "pages")
            val raw = f.readText().trim()
            // Two shapes on disk: the bare array everything up to the topics
            // change wrote, and the `{entries, topics}` object written since.
            // Sniffed rather than versioned — a device that has never folded
            // anything has no topics to lose, and rewriting every existing file
            // just to add an empty list would be a migration for nobody.
            val bare = if (raw.startsWith("[")) JSONArray(raw) else null
            val root = if (bare == null) JSONObject(raw) else null
            val arr = bare ?: root!!.optJSONArray(KEY_ENTRIES) ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val role = runCatching { Role.valueOf(o.optString("role")) }.getOrNull() ?: continue
                // Re-checked against the filesystem rather than trusted: the
                // log and the pages directory are written separately and the
                // process can die between them, and a row that offers to open
                // a file that isn't there is worse than one that doesn't.
                val page = o.optString("page").takeIf { it.isNotEmpty() && it != "null" }
                    ?.let { File(dir, it) }?.takeIf { it.exists() }
                // Same treatment as `page`: a row written before this field
                // existed, or by a build that never had a turn for it, reads as
                // absent rather than as a turn named "null".
                val turn = o.optString("turn").takeIf { it.isNotEmpty() && it != "null" }
                val id = o.optLong("id")
                // Rows written before versions existed have no `group`, and
                // reading them as absent must not regroup them under id 0 —
                // that would collapse every pre-existing page into one artifact
                // the first time the panel was opened. Absent means "its own
                // group", which is exactly what those rows are: one page each,
                // v1, because nothing could revise them back then.
                val group = if (o.has("group")) o.optLong("group") else id
                val version = if (o.has("version")) o.optInt("version", 1) else 1
                // Null and absent are the same thing here and both mean "not
                // folded", so there is no default to get wrong.
                val topic = if (o.has("topic")) o.optLong("topic", -1L).takeIf { it > 0 } else null
                entries.add(
                    Entry(
                        id, o.optLong("ts"), role, o.optString("text"), page, turn,
                        group, version, topic,
                        kind = o.optString("kind"), detail = o.optString("detail"), ref = o.optString("ref"),
                    ),
                )
                if (id >= nextId) nextId = id + 1
            }
            val tarr = root?.optJSONArray(KEY_TOPICS) ?: JSONArray()
            for (i in 0 until tarr.length()) {
                val o = tarr.optJSONObject(i) ?: continue
                val tid = o.optLong("id")
                if (tid <= 0) continue
                topics.add(
                    Topic(
                        id = tid,
                        title = o.optString("title"),
                        summary = o.optString("summary"),
                        startedAt = o.optLong("started"),
                        endedAt = o.optLong("ended"),
                        createdAt = o.optLong("created"),
                        parentId = if (o.has("parent")) o.optLong("parent", -1L).takeIf { it > 0 } else null,
                    ),
                )
                if (tid >= nextTopicId) nextTopicId = tid + 1
            }
            // A row may name a topic that is no longer on the list — its file
            // was deleted, or it was evicted. Clear it here, at load, so the
            // list never collapses around a heading it cannot draw. Skipping
            // this would leave a stale id on disk forever, and every open would
            // have to re-decide the same thing.
            val live = topics.map { it.id }.toSet()
            for (i in entries.indices) {
                val t = entries[i].topicId
                if (t != null && t !in live) entries[i] = entries[i].copy(topicId = null)
            }
        }.onFailure {
            Log.w(TAG, "history: load failed, starting empty: ${it.message}")
            entries.clear()
            topics.clear()
        }
    }
}
