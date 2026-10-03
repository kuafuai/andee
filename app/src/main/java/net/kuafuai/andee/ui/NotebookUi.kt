package net.kuafuai.andee.ui

import android.annotation.SuppressLint
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
 * [Notebook.dumpText] is the other view into the book, and it is
 * export-shaped — every field, as text, for copying out. This page answers a
 * different question: "what is it allowed to remember about me".
 *
 * **记忆** groups [Notebook.Memory] by type, most important first within each.
 * **待办** lists active and paused [Notebook.Todo]s, soonest first.
 *
 * No editing: the brain writes these, not the person. Delete is the only
 * mutation, and it is **destructive** — gone from the encrypted blob, not
 * archived — because a delete that merely hides something does not answer the
 * question this page exists for.
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

    /** 0 = memories, 1 = todos. */
    private var tab = 0

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
        if (tab == 0) buildMemories(body) else buildTodos(body)
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
        h.addView(closeButton())
        return h
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

    private fun tabBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        bar.addView(tabButton(0, lctx.getString(R.string.notebook_tab_memories)))
        bar.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), 1)
        })
        bar.addView(tabButton(1, lctx.getString(R.string.notebook_tab_todos)))
        return bar
    }

    private fun tabButton(index: Int, label: String): TextView =
        TextView(context).apply {
            text = label
            textSize = Glass.Type.BODY
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(10))
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
                setOnClickListener { tab = index; rebuild() }
            }
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
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
                compareByDescending<Notebook.Memory> { it.importance }
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
    private fun memoryRow(m: Notebook.Memory): View {
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
    private fun todoRow(t: Notebook.Todo): View {
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

    private fun emptyState(msg: String): TextView = TextView(context).apply {
        text = msg
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

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
