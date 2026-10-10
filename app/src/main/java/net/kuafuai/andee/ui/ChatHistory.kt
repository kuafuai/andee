package net.kuafuai.andee.ui

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
 * Singleton with an [init] rather than a constructor-injected dependency,
 * matching [CardUi]: the appenders are scattered across the service, the
 * dispatcher and the hub client, and threading one object through all of them
 * buys nothing when there is exactly one device.
 *
 * Thread-safe. Appends arrive from the ASR callback, the hub receive thread and
 * dispatcher workers; reads come from the UI thread.
 */
object ChatHistory {

    enum class Role { USER, ASSISTANT, PAGE, ERROR }

    /**
     * @param page for [Role.PAGE], the archived HTML — null once it has been
     *   evicted, which is how a row knows to stop offering to re-open itself.
     * @param turn which run of the brain produced this row, named the way
     *   [net.kuafuai.andee.brain.BrainTrace] names it, or null when there is no
     *   log to open — see [addAssistant].
     */
    data class Entry(
        val id: Long,
        val ts: Long,
        val role: Role,
        val text: String,
        val page: File?,
        val turn: String? = null,
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
    private val lock = Any()
    private val entries = ArrayList<Entry>()
    private var nextId = 1L

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

    /** Pages still on disk, newest first — what the 产物 panel lists. */
    fun artifacts(): List<Entry> = synchronized(lock) {
        entries.filter { it.role == Role.PAGE && it.page != null }.reversed()
    }

    /**
     * Delete one page's file and keep its row, the same end state eviction
     * leaves: "you showed me a page at 3pm" stays true after the page is gone.
     */
    fun deletePage(id: Long) {
        synchronized(lock) {
            val i = entries.indexOfFirst { it.id == id }
            if (i < 0) return
            val e = entries[i]
            e.page?.let { runCatching { it.delete() } }
            entries[i] = e.copy(page = null)
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
            nextId = 1L
        }
        runCatching {
            File(context.filesDir, "chat_history.json").delete()
            File(context.filesDir, "pages").deleteRecursively()
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

    /**
     * Archive a page the brain just composed and record it.
     *
     * Returns the archived file for the caller to hand to [HtmlActivity], so
     * the copy being displayed and the copy in the history are the same bytes
     * in the same place — an earlier split (cache dir for display, files dir
     * for the archive) meant re-opening from history could show something
     * subtly different from what was on screen a minute ago.
     *
     * Falls back to null on IO failure; the caller still has the HTML string
     * and can stage it itself, it just won't be in the list.
     */
    fun addPage(title: String, html: String): File? {
        val ctx = appContext ?: return null
        val id = synchronized(lock) { nextId }
        val file = runCatching {
            val dir = File(ctx.filesDir, "pages").apply { mkdirs() }
            File(dir, "page_$id.html").also { it.writeText(html) }
        }.getOrElse {
            Log.w(TAG, "history: could not archive page: ${it.message}")
            return null
        }
        add(Role.PAGE, title, file)
        return file
    }

    private fun add(role: Role, text: String, page: File?, turn: String? = null) {
        if (appContext == null) return
        val clean = text.trim().let {
            if (it.length > MAX_TEXT) it.take(MAX_TEXT) + "…" else it
        }
        if (clean.isEmpty()) return
        synchronized(lock) {
            entries.add(Entry(nextId++, System.currentTimeMillis(), role, clean, page, turn))
            trim()
            save()
        }
        listener?.invoke()
    }

    /** Caller holds [lock]. */
    private fun trim() {
        // Pages first, so one that just lost its file is an ordinary row below.
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
                )
            }
            f.writeText(arr.toString())
        }.onFailure { Log.w(TAG, "history: save failed: ${it.message}") }
    }

    /** Caller holds [lock]. */
    private fun load() {
        val f = file() ?: return
        if (!f.exists()) return
        runCatching {
            val dir = File(appContext!!.filesDir, "pages")
            val arr = JSONArray(f.readText())
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
                entries.add(Entry(id, o.optLong("ts"), role, o.optString("text"), page, turn))
                if (id >= nextId) nextId = id + 1
            }
        }.onFailure {
            Log.w(TAG, "history: load failed, starting empty: ${it.message}")
            entries.clear()
        }
    }
}
