package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.config.Notebook
import net.kuafuai.andee.config.Scheduler
import net.kuafuai.andee.i18n.AppLocale
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 它记得的事: what the device remembers, browsable and deletable per item.
 *
 * The header's 复制全部 copies [Notebook.dumpText] — every field, untruncated,
 * export-shaped. That is the only route to the raw text; the rows here answer
 * a different question: "what is it allowed to remember about me".
 *
 * Eight tabs, the same eight and in the same order as the Mac's 小本本:
 * **自己** (its self-description), **在意** (its cares, most overdue first),
 * **日记** (the last week of diary pages), **打算** (today's plan), **人** (who it
 * knows), **维基** (its articles — tap one to read it from the file, now),
 * **记忆** ([net.kuafuai.andee.config.Memory] by type, tap to read the content),
 * **待办** (active and paused [net.kuafuai.andee.config.Todo]s, soonest first).
 * What the model wrote is Markdown and is drawn as such ([MarkdownView]).
 *
 * No editing: the brain writes these, not the person. Delete (memories and
 * todos) is the only mutation, and it is **destructive** — gone from the
 * encrypted blob, not archived — because a delete that merely hides something
 * does not answer the question this page exists for.
 *
 * Same window shape as [VaultUi], its sibling on top of the settings card:
 * focusable, hidden from accessibility, counted by [OwnCard] — a focused
 * window that dumps as zero nodes is exactly what [OwnCard] explains.
 */
class NotebookUi(
    private val context: Context,
    private val onDismiss: () -> Unit = {},
) {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null

    /** Index into [TABS]. */
    private var tab = 0

    /** The wiki article being read in the 维基 tab, or null for the list. */
    private var wikiOpen: String? = null

    /** What reading [wikiOpen] came back with; null while it is still being read. */
    private var wikiArticle: Result<net.kuafuai.andee.wiki.Article>? = null

    /** Memories whose content is unfolded. */
    private val expanded = mutableSetOf<String>()

    /** Whether the compositor agreed to blur. See [Glass.frost]. */
    private var frosted = false

    /**
     * Strings in the user's language, refreshed at the top of [build].
     *
     * Refreshed there and not in [show] on purpose: [rebuild] goes straight to
     * [build] without passing through [show], so rewrapping in the one function
     * that consumes it means no future caller has to remember to do it.
     *
     * Views are still built on [context]; only text comes from here. See
     * [AppLocale] for why those two are deliberately different Contexts.
     */
    private var lctx: Context = context

    fun show() {
        if (root != null) return
        val p = WindowManager.LayoutParams(
            cardWidth(), cardHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        frosted = Glass.frost(context, wm, p)
        val v = build()
        runCatching {
            v.hideFromAccessibility()
            wm.addView(v, p)
            root = v
            OwnCard.shown()
            Glass.enter(v)
        }
    }

    fun hide() {
        val v = root ?: return
        root = null
        Glass.exit(v) {
            runCatching { wm.removeView(v) }
            OwnCard.hidden()
            onDismiss()
        }
    }

    private fun rebuild() {
        val old = root ?: return
        val v = build()
        runCatching {
            v.hideFromAccessibility()
            val p = old.layoutParams as WindowManager.LayoutParams
            wm.removeView(old)
            wm.addView(v, p)
            root = v
            Glass.fadeIn(v)
        }
    }

    private fun cardWidth(): Int =
        minOf(dp(560), (wm.currentWindowMetrics.bounds.width() * 0.96f).toInt())

    private fun cardHeight(): Int =
        minOf(dp(720), (wm.currentWindowMetrics.bounds.height() * 0.90f).toInt())

    // ---- build ----

    @SuppressLint("SetTextI18n")
    private fun build(): View {
        lctx = AppLocale.wrap(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.card(context, dp(28), frosted)
            clipToOutline = true
        }

        card.addView(header(), matchWrap())
        divider(card)
        card.addView(tabBar(), matchWrap())

        val scroll = ScrollView(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        when (TABS[tab]) {
            Tab.SELF -> buildSelf(body)
            Tab.CARES -> buildCares(body)
            Tab.DIARY -> buildDiary(body)
            Tab.PLAN -> buildPlan(body)
            Tab.PEOPLE -> buildPeople(body)
            Tab.WIKI -> if (wikiOpen != null) buildArticle(body) else buildWiki(body)
            Tab.MEMORIES -> buildMemories(body)
            Tab.TODOS -> buildTodos(body)
        }
        scroll.addView(body, matchWrap())
        card.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        return card
    }

    private fun header(): LinearLayout {
        val h = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(14), dp(12))
        }
        h.addView(
            TextView(context).apply {
                text = lctx.getString(R.string.notebook_title)
                textSize = Glass.Type.HEADLINE
                setTextColor(Color.parseColor(Glass.TITLE))
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        h.addView(copyButton())
        h.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        h.addView(closeButton())
        return h
    }

    private fun copyButton(): TextView = TextView(context).apply {
        val label = lctx.getString(R.string.notebook_copy_all)
        text = label
        textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(Glass.SECONDARY))
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        background = Glass.panel(context, dp(16))
        isClickable = true
        Glass.pressable(this)
        setOnClickListener {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val ok = runCatching {
                cm.setPrimaryClip(
                    ClipData.newPlainText(lctx.getString(R.string.notebook_title), Notebook.dumpText(context)),
                )
            }.isSuccess
            if (!ok) return@setOnClickListener
            text = lctx.getString(R.string.notebook_copied)
            setTextColor(Color.parseColor(Glass.OK))
            postDelayed({
                text = label
                setTextColor(Color.parseColor(Glass.SECONDARY))
            }, 1500)
        }
        layoutParams = LinearLayout.LayoutParams(WRAP, dp(32))
    }

    private fun closeButton(): TextView = TextView(context).apply {
        text = "✕"
        textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(Glass.SECONDARY))
        gravity = Gravity.CENTER
        background = Glass.panel(context, dp(16))
        isClickable = true
        Glass.pressable(this)
        setOnClickListener { hide() }
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
    }

    /** Eight tabs do not fit a phone's width at a readable size, so the row scrolls sideways. */
    private fun tabBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        TABS.forEachIndexed { i, t ->
            if (i > 0) bar.addView(View(context), LinearLayout.LayoutParams(dp(4), 1))
            bar.addView(tabButton(i, lctx.getString(t.label)))
        }
        return android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
    }

    private fun tabButton(index: Int, label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = Glass.Type.BODY
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val active = tab == index
            setTextColor(
                Color.parseColor(if (active) Glass.TITLE else Glass.SECONDARY),
            )
            background = if (active) {
                Glass.panel(context, dp(12))
            } else {
                null
            }
            isClickable = !active
            if (!active) {
                Glass.pressable(this)
                setOnClickListener { tab = index; wikiOpen = null; rebuild() }
            }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }

    // ---- 自己 · 在意 · 日记 · 打算 · 人 · 维基 ----

    private fun md(text: String, color: String = Glass.LABEL, size: Float = Glass.Type.BODY): View =
        MarkdownView.build(context, text, color, size, onLink = ::openLink)

    /** A link opens in the browser, which an overlay would sit on top of — so the card goes first. */
    private fun openLink(url: String) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            hide()
        }
    }

    private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(ms)

    private fun panel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = Glass.panel(context, dp(14))
    }

    private fun label(text: String, size: Float, color: String, bold: Boolean = false, top: Int = 0): TextView =
        TextView(context).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(top), 0, 0)
        }

    private fun addRow(body: LinearLayout, v: View) = body.addView(v, matchWrap().apply { bottomMargin = dp(10) })

    private fun buildSelf(body: LinearLayout) {
        val n = Notebook.selfhood(context).narrative()
        if (n == null) {
            body.addView(emptyState(lctx.getString(R.string.notebook_empty_self), lctx.getString(R.string.notebook_empty_self_hint)))
            return
        }
        val p = panel()
        p.addView(label(lctx.getString(R.string.notebook_self_heading), Glass.Type.TITLE, Glass.TITLE, bold = true), matchWrap())
        p.addView(md(n.text, Glass.PHOTO_INK), matchWrap().apply { topMargin = dp(10) })
        p.addView(label(lctx.getString(R.string.notebook_updated_at, stamp(n.updatedAt)), Glass.Type.MICRO, Glass.MUTED, top = 8), matchWrap())
        addRow(body, p)
    }

    private fun buildCares(body: LinearLayout) {
        val self = Notebook.selfhood(context)
        val cares = self.cares().filter { it.status == "active" }.sortedByDescending { self.overdue(it) }
        if (cares.isEmpty()) {
            body.addView(emptyState(lctx.getString(R.string.notebook_empty_cares), lctx.getString(R.string.notebook_empty_cares_hint)))
            return
        }
        for (c in cares) {
            val p = panel()
            p.addView(label(c.what, Glass.Type.BODY, Glass.TITLE, bold = true), matchWrap())
            if (c.why.isNotBlank()) p.addView(md(c.why, Glass.PHOTO_INK, Glass.Type.CAPTION), matchWrap().apply { topMargin = dp(4) })
            if (c.note.isNotBlank()) p.addView(label(c.note, Glass.Type.CAPTION, Glass.SECONDARY, top = 6), matchWrap())
            val foot = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            foot.addView(label(c.kind, Glass.Type.MICRO, Glass.MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
            if (self.overdue(c) > 0.5) foot.addView(label(lctx.getString(R.string.notebook_care_attention), Glass.Type.MICRO, Glass.ACCENT))
            p.addView(foot, matchWrap())
            addRow(body, p)
        }
    }

    private fun buildDiary(body: LinearLayout) {
        onItsOwn(body)
        val pages = Notebook.selfhood(context).diary(7)
        if (pages.isEmpty()) {
            body.addView(emptyState(lctx.getString(R.string.notebook_empty_diary), lctx.getString(R.string.notebook_empty_diary_hint)))
            return
        }
        for (page in pages) {
            val p = panel()
            p.addView(label(page.date, Glass.Type.CAPTION, Glass.SECONDARY), matchWrap())
            p.addView(md(page.text), matchWrap().apply { topMargin = dp(10) })
            addRow(body, p)
        }
    }

    /** Today's thoughts and the last thing it did by itself — the device's record, so nothing done unasked is invisible. */
    private fun onItsOwn(body: LinearLayout) {
        val drive = Notebook.drive(context)
        val thoughts = drive.thoughtsToday()
        val last = drive.lastWork()
        if (thoughts.isEmpty() && last == null) return
        val p = panel()
        p.addView(label(lctx.getString(R.string.notebook_own_heading), Glass.Type.CAPTION, Glass.SECONDARY), matchWrap())
        if (last != null) {
            val did = lctx.getString(when (last.effect) { "acted" -> R.string.notebook_own_acted; "looked" -> R.string.notebook_own_looked; else -> R.string.notebook_own_thought })
            p.addView(label("${stamp(last.at)} · $did「${last.what}」", Glass.Type.BODY, Glass.LABEL, top = 8), matchWrap())
            if (last.summary.isNotBlank()) p.addView(md(last.summary, Glass.PHOTO_INK, Glass.Type.CAPTION), matchWrap().apply { topMargin = dp(4) })
        }
        if (thoughts.isNotEmpty()) {
            p.addView(label(lctx.getString(R.string.notebook_own_thoughts), Glass.Type.CAPTION, Glass.SECONDARY, top = 10), matchWrap())
            for (t in thoughts.reversed()) p.addView(label("· " + t.text, Glass.Type.CAPTION, Glass.PHOTO_INK, top = 4), matchWrap())
        }
        addRow(body, p)
    }

    private fun buildPlan(body: LinearLayout) {
        val plans = Notebook.plans(context)
        val plan = plans.day(plans.today())
        if (plan == null) {
            body.addView(emptyState(lctx.getString(R.string.notebook_empty_plan), lctx.getString(R.string.notebook_empty_plan_hint)))
            return
        }
        val p = panel()
        p.addView(label(lctx.getString(R.string.notebook_plan_today), Glass.Type.TITLE, Glass.TITLE, bold = true), matchWrap())
        if (plan.intent.isNotBlank()) p.addView(md(plan.intent, Glass.SECONDARY, Glass.Type.CAPTION), matchWrap().apply { topMargin = dp(4) })
        val items = plan.items.filter { it.status == "planned" }
        if (items.isNotEmpty()) p.addView(View(context), LinearLayout.LayoutParams(1, dp(10)))
        for (item in items) {
            p.addView(label("•  " + item.what, Glass.Type.BODY, Glass.LABEL, top = 6), matchWrap())
            val whenTxt = item.time.ifEmpty { item.slot }
            if (whenTxt.isNotEmpty()) p.addView(label("    $whenTxt", Glass.Type.CAPTION, Glass.SECONDARY), matchWrap())
        }
        addRow(body, p)
    }

    private fun buildPeople(body: LinearLayout) {
        val people = Notebook.people(context).all().filter { it.status == "active" }
        if (people.isEmpty()) {
            body.addView(emptyState(lctx.getString(R.string.notebook_empty_people), lctx.getString(R.string.notebook_empty_people_hint)))
            return
        }
        for (person in people) {
            val p = panel()
            p.addView(label(person.name, Glass.Type.TITLE, Glass.TITLE, bold = true), matchWrap())
            if (person.relation.isNotBlank()) p.addView(label(person.relation, Glass.Type.CAPTION, Glass.SECONDARY, top = 4), matchWrap())
            person.impressions.firstOrNull()?.let {
                p.addView(md(it.text, Glass.PHOTO_INK, Glass.Type.CAPTION), matchWrap().apply { topMargin = dp(8) })
            }
            addRow(body, p)
        }
    }

    private fun wikiLine(m: net.kuafuai.andee.wiki.Meta): String =
        listOf(m.kind, "v${m.version}", m.status.key, stamp(m.updated)).joinToString(" · ")

    private fun buildWiki(body: LinearLayout) {
        val entries = Notebook.wiki(context).all().sortedByDescending { it.updated }
        if (entries.isEmpty()) {
            body.addView(emptyState(lctx.getString(R.string.notebook_empty_wiki), lctx.getString(R.string.notebook_empty_wiki_hint)))
            return
        }
        for (m in entries) {
            val p = panel().apply {
                isClickable = true
                Glass.pressable(this)
                setOnClickListener { openArticle(m.id) }
            }
            val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(label(m.title, Glass.Type.BODY, Glass.TITLE, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            top.addView(label(lctx.getString(R.string.notebook_wiki_open), Glass.Type.CAPTION, Glass.ACCENT))
            p.addView(top, matchWrap())
            p.addView(label(wikiLine(m), Glass.Type.MICRO, Glass.MUTED, top = 4), matchWrap())
            if (m.summary.isNotBlank()) {
                // The summary is the start of the body, markup and all; a two-line preview reads only its prose.
                p.addView(label(net.kuafuai.andee.text.Md.preview(m.summary), Glass.Type.CAPTION, Glass.PHOTO_INK, top = 6).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, matchWrap())
            }
            addRow(body, p)
        }
    }

    /** Reads the article from its file — off the main thread, since a backend other than the folder may be remote. */
    private fun openArticle(id: String) {
        wikiOpen = id
        wikiArticle = null
        rebuild()
        Thread({
            val r = Notebook.wiki(context).read(id)
            root?.post {
                if (wikiOpen != id) return@post
                wikiArticle = r
                rebuild()
            }
        }, "WikiRead").start()
    }

    private fun buildArticle(body: LinearLayout) {
        val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(label(lctx.getString(R.string.notebook_wiki_back), Glass.Type.BODY, Glass.ACCENT).apply {
            setPadding(dp(2), dp(6), dp(12), dp(6))
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { wikiOpen = null; wikiArticle = null; rebuild() }
        })
        bar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val article = wikiArticle?.getOrNull()
        if (article != null) {
            bar.addView(label(lctx.getString(R.string.notebook_wiki_copy), Glass.Type.CAPTION, Glass.ACCENT).apply {
                setPadding(dp(12), dp(6), dp(2), dp(6))
                isClickable = true
                Glass.pressable(this)
                setOnClickListener {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    if (runCatching { cm.setPrimaryClip(ClipData.newPlainText(article.meta.title, article.body)) }.isSuccess) {
                        text = lctx.getString(R.string.notebook_copied)
                    }
                }
            })
        }
        body.addView(bar, matchWrap().apply { bottomMargin = dp(10) })
        val r = wikiArticle
        when {
            r == null -> body.addView(label(lctx.getString(R.string.notebook_wiki_reading), Glass.Type.BODY, Glass.SECONDARY), matchWrap())
            r.isFailure -> body.addView(label(r.exceptionOrNull()?.message.orEmpty(), Glass.Type.BODY, Glass.DANGER), matchWrap())
            else -> {
                val a = r.getOrThrow()
                val p = panel().apply { setPadding(dp(16), dp(14), dp(16), dp(16)) }
                p.addView(label(a.meta.title, Glass.Type.TITLE, Glass.TITLE, bold = true), matchWrap())
                p.addView(label(wikiLine(a.meta), Glass.Type.MICRO, Glass.MUTED, top = 4), matchWrap())
                if (a.meta.source.isNotBlank()) {
                    p.addView(label(lctx.getString(R.string.notebook_wiki_source, a.meta.source), Glass.Type.MICRO, Glass.MUTED, top = 2), matchWrap())
                }
                p.addView(md(a.body), matchWrap().apply { topMargin = dp(14) })
                addRow(body, p)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildMemories(body: LinearLayout) {
        val all = Notebook.memories(context).filter { !it.expired() }
        if (all.isEmpty()) {
            body.addView(emptyState(lctx.getString(R.string.notebook_empty_memories)))
            return
        }

        val byType = all.groupBy { it.type }
        body.addView(TextView(context).apply {
            text = lctx.getString(R.string.notebook_delete_note)
            textSize = Glass.Type.MICRO
            setTextColor(Color.parseColor(Glass.MUTED))
        }, matchWrap())
        for (type in Notebook.TYPES) {
            val group = byType[type] ?: continue
            val sorted = group.sortedWith(
                compareByDescending<net.kuafuai.andee.config.Memory> { it.importance }
                    .thenByDescending { it.updatedAt },
            )
            body.addView(sectionHeader(typeLabel(type)))
            for (m in sorted) {
                body.addView(memoryRow(m), matchWrap().apply { bottomMargin = dp(10) })
            }
        }
    }

    private fun typeLabel(type: String): String = when (type) {
        "user" -> lctx.getString(R.string.notebook_type_user)
        "feedback" -> lctx.getString(R.string.notebook_type_feedback)
        "way" -> lctx.getString(R.string.notebook_type_way)
        "reference" -> lctx.getString(R.string.notebook_type_reference)
        "failure" -> lctx.getString(R.string.notebook_type_failure)
        else -> type
    }

    @SuppressLint("SetTextI18n")
    private fun memoryRow(m: net.kuafuai.andee.config.Memory): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Glass.panel(context, dp(14))
        }

        val title = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(
            TextView(context).apply {
                text = m.name
                textSize = Glass.Type.BODY
                setTextColor(Color.parseColor(Glass.TITLE))
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        title.addView(deleteButton {
            Notebook.forget(context, m.name)
            rebuild()
        })
        row.addView(title, matchWrap())

        // Tap to read what it actually wrote down; the description is only the line it recalls it by.
        if (m.content.isNotBlank()) {
            row.isClickable = true
            Glass.pressable(row)
            row.setOnClickListener {
                if (!expanded.remove(m.name)) expanded += m.name
                rebuild()
            }
        }

        if (m.description.isNotBlank()) {
            row.addView(
                TextView(context).apply {
                    text = m.description
                    textSize = Glass.Type.CAPTION
                    setTextColor(Color.parseColor(Glass.SECONDARY))
                    setPadding(0, dp(4), 0, 0)
                },
                matchWrap(),
            )
        }

        if (m.name in expanded && m.content.isNotBlank()) {
            row.addView(md(m.content, Glass.PHOTO_INK, Glass.Type.CAPTION), matchWrap().apply { topMargin = dp(8) })
        }

        val meta = buildString {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(m.updatedAt)
            append(stamp)
            if (m.hits > 0) {
                append(" · ")
                append(lctx.getString(R.string.notebook_hits, m.hits))
            }
        }
        row.addView(
            TextView(context).apply {
                text = meta
                textSize = Glass.Type.MICRO
                setTextColor(Color.parseColor(Glass.MUTED))
                setPadding(0, dp(6), 0, 0)
            },
            matchWrap(),
        )

        return row
    }

    @SuppressLint("SetTextI18n")
    private fun buildTodos(body: LinearLayout) {
        val active = Notebook.todos(context, "active")
        if (active.isEmpty()) {
            body.addView(emptyState(lctx.getString(R.string.notebook_empty_todos)))
            return
        }

        val sorted = active.sortedBy { it.nextRunAt }
        for (t in sorted) {
            body.addView(todoRow(t), matchWrap().apply { bottomMargin = dp(10) })
        }
    }

    @SuppressLint("SetTextI18n")
    private fun todoRow(t: net.kuafuai.andee.config.Todo): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Glass.panel(context, dp(14))
        }

        val title = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(
            TextView(context).apply {
                text = t.what
                textSize = Glass.Type.BODY
                setTextColor(Color.parseColor(Glass.TITLE))
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        title.addView(deleteButton {
            // Same three steps as the brain's own `todo drop`: without the
            // cancel, the alarm still fires for a row the user just deleted.
            Notebook.patchTodo(context, t.id, status = "dropped", nextRunAt = 0L)
            Scheduler.cancelTodo(context, t.id)
            rebuild()
        })
        row.addView(title, matchWrap())

        val detail = buildString {
            if (t.mode == "cron") {
                append(lctx.getString(R.string.notebook_todo_cron, t.cronExpr))
            } else {
                val rel = DateUtils.getRelativeTimeSpanString(
                    t.at,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                )
                append(rel)
            }
            if (t.status == "paused") {
                append(" · ")
                append(lctx.getString(R.string.notebook_todo_paused))
            }
        }
        row.addView(
            TextView(context).apply {
                text = detail
                textSize = Glass.Type.CAPTION
                setTextColor(Color.parseColor(Glass.SECONDARY))
                setPadding(0, dp(4), 0, 0)
            },
            matchWrap(),
        )

        return row
    }

    private fun sectionHeader(label: String): TextView = TextView(context).apply {
        text = label
        textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(Glass.MUTED))
        setPadding(0, dp(16), 0, dp(8))
        layoutParams = matchWrap()
    }

    private fun emptyState(msg: String, hint: String? = null): TextView = TextView(context).apply {
        text = if (hint == null) msg else "$msg\n$hint"
        textSize = Glass.Type.BODY
        setTextColor(Color.parseColor(Glass.MUTED))
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(40), dp(20), dp(40))
        layoutParams = matchWrap()
    }

    private fun deleteButton(onClick: () -> Unit): TextView = TextView(context).apply {
        text = lctx.getString(R.string.common_delete)
        textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(Glass.DANGER))
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        background = Glass.panel(context, dp(10))
        isClickable = true
        Glass.pressable(this)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
    }

    private fun divider(parent: LinearLayout) {
        parent.addView(
            View(context).apply { setBackgroundColor(Color.parseColor(Glass.PANEL_EDGE)) },
            LinearLayout.LayoutParams(MATCH, dp(1)),
        )
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)

    private enum class Tab(@androidx.annotation.StringRes val label: Int) {
        SELF(R.string.notebook_tab_self), CARES(R.string.notebook_tab_cares), DIARY(R.string.notebook_tab_diary),
        PLAN(R.string.notebook_tab_plan), PEOPLE(R.string.notebook_tab_people), WIKI(R.string.notebook_tab_wiki),
        MEMORIES(R.string.notebook_tab_memories), TODOS(R.string.notebook_tab_todos),
    }

    private companion object {
        val TABS = Tab.values()
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
