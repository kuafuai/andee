package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The scrollback under the ball in the fullscreen card: what was said, and
 * every page the assistant drew, each one re-openable.
 *
 * ## Why it looks the way it does
 *
 * The brief was Apple's frosted glass. There *is* now a real blur behind all
 * of this — [FloatingWindowUi] asks the compositor for it at the window level
 * (see [Glass.frost]) — but it happens once, for the whole card, and these
 * rows are children of that window. A child View cannot blur what is behind
 * it; `RenderEffect` blurs a view's own content. So the panes here are made
 * the same way they were when the card was opaque, and the blur underneath is
 * simply what they now sit on.
 *
 * What reads as glass on a dark surface isn't the blur anyway, it's the three
 * things around it: a translucent white *film* rather than a solid fill, a
 * top-to-bottom gradient standing in for light falling across a pane, and a
 * hairline border a shade brighter than the film so the pane has an edge where
 * it catches that light. Those are three cheap drawables and they composite in
 * one pass. Colours come from [Glass] so the scrollback, the cards and the
 * dialogs are one palette.
 *
 * ## The fade
 *
 * "上下渐渐隐藏" is Android's own fading edge, not a pair of gradient views
 * laid over the top and bottom. The platform one fades the *content* to
 * transparent and — the part a hand-rolled overlay always gets wrong — only on
 * the sides that actually have more content to scroll to, so the list doesn't
 * sit there with a phantom shadow above a first row that is already at the top.
 */
class HistoryListView(private val context: Context) : ScrollView(context) {

    /** Tapping a page row. Only ever fired for entries that still have a file. */
    var onPageClick: ((ChatHistory.Entry) -> Unit)? = null

    /**
     * Double-tapping a message — read it fullscreen. See [attachDoubleTap].
     *
     * Handed out rather than opened here for the same reason page rows are: the
     * reader is a fullscreen screen, so the card has to fold out of its way
     * first, and that sequencing belongs to whoever owns the dispatcher.
     */
    var onTextOpen: ((ChatHistory.Entry) -> Unit)? = null

    /**
     * The rows themselves.
     *
     * It used to end with a permanent dim line — "点击小球跟我对话" — as a hint
     * at the bottom of every conversation. It reads well and it scrolls away,
     * which is the one thing a hint about *how to ask* must not do: the moment
     * the user wants it is the moment they have scrolled up looking for what
     * happened. The hint is pinned along the bottom of the card now (see
     * `FloatingWindowUi.build`), and this column is back to being only the
     * conversation.
     */
    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // The bottom padding clears the gesture bar. It used to be 30dp because
        // the hint was the last row and the navigation strip ate it; the pinned
        // tips bar owns that edge now and reserves it, so all this needs to be
        // is a little air between the last bubble and the bar.
        setPadding(dp(14), dp(2), dp(14), dp(10))
    }

    /**
     * Strings in the user's language. Views below are still built on the raw
     * [context]; only text comes from here. See [AppLocale].
     *
     * A `var` rather than a `val` because the language can change while this
     * list is on screen — the settings card is opened on top of it. See
     * [onLocaleChanged].
     */
    private var lctx: Context = AppLocale.wrap(context)

    private val clock = SimpleDateFormat("HH:mm", AppLocale.localeOf(context))

    /**
     * Rows currently laid out, by entry id. Rebuilding every row on every
     * append would cancel the ripple on the row the user is touching and reset
     * the scroll offset mid-drag; appends are by far the common case.
     */
    private val laid = LinkedHashSet<Long>()

    /** Laid rows drawn as re-openable pages; one losing its file forces a relayout. */
    private val laidOpen = HashSet<Long>()

    private val empty = TextView(context).apply {
        text = lctx.getString(R.string.history_empty)
        textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(Glass.MUTED))
        gravity = Gravity.CENTER
        setPadding(0, dp(28), 0, 0)
    }

    /**
     * What is happening right now — a partial transcript, the tool being run,
     * the sentence being read out. Lives as the list's last row rather than in
     * a band of its own, so speech, answers, pages and failures are one
     * chronological column instead of two surfaces the user has to watch.
     *
     * Always the last child, and pushed back there after every append: entries
     * settle *behind* the live line, which is by definition still in progress.
     */
    private val live = TextView(context).apply {
        textSize = Glass.Type.BODY
        visibility = View.GONE
        setPadding(dp(4), dp(8), dp(4), dp(2))
        maxLines = 4
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    init {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(44))
        // Without this the fade is drawn over the scrollbar's gutter and the
        // rows lose 6dp of width to a bar nobody needs on a touch-scrolled list.
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        // The card is the whole screen; the list is the part of it that scrolls,
        // and it must not eat the ball's drag above it. ScrollView already
        // claims only vertical drags that start inside itself.
        addView(
            column,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        column.addView(empty)
        column.addView(live)
    }

    /**
     * Rows appended while the card was folded were laid out into a view with no
     * height, so the [refresh] that queued them scrolled a list that had nowhere
     * to scroll. The card is folded for most of its life — this is the common
     * case, not an edge one — so the unfold's first real measurement is where
     * the bottom has to be re-taken.
     */
    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        if (h > 0 && h != oldH) post { fullScroll(FOCUS_DOWN) }
    }

    /**
     * Show (or with an empty string, hide) the live row.
     *
     * Main thread only.
     */
    fun setLive(text: String, kind: FloatingWindowUi.SubtitleKind) {
        removeCallbacks(clearLive)
        if (text.isEmpty()) {
            live.visibility = View.GONE
            return
        }
        live.text = text
        live.setTextColor(
            when (kind) {
                // Dim white for something still being said; the accent for an
                // announcement that is finished and will not be superseded.
                FloatingWindowUi.SubtitleKind.PARTIAL -> Color.parseColor(Glass.LABEL)
                FloatingWindowUi.SubtitleKind.FINAL -> Color.parseColor(Glass.ACCENT)
            }
        )
        live.visibility = View.VISIBLE
        // FINAL is an announcement nothing will supersede — "已停止", a tool's
        // result. PARTIAL and SPEAKING are always followed by something that
        // replaces or clears them, but a FINAL left alone would sit at the foot
        // of the scrollback forever, under the hint, reading as the newest thing
        // said long after it stopped being true.
        if (kind == FloatingWindowUi.SubtitleKind.FINAL) postDelayed(clearLive, FINAL_LINGER_MS)
        post { fullScroll(FOCUS_DOWN) }
    }

    private val clearLive = Runnable { live.visibility = View.GONE }

    /**
     * Pull the current log in and lay out anything new, then stick to the
     * bottom — the newest line is the one worth reading, and a list that held
     * its old offset while the assistant talked would need scrolling before it
     * showed you the answer you just heard.
     *
     * Main thread only.
     */
    fun refresh() {
        val all = ChatHistory.snapshot()
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE

        val ids = all.map { it.id }.toSet()
        val open = all.filter { it.page != null }.map { it.id }.toSet()
        if (laid.any { it !in ids } || laidOpen.any { it !in open }) {
            // A row we laid out is gone from the history — evicted from the
            // middle, or the whole list wiped. Cheaper and less error-prone to
            // start over than to reconcile. This deliberately does not also
            // require something to have been *added*: 清空聊天记录 removes
            // every row and adds none, and pairing the two conditions left the
            // card scrolling a conversation that no longer existed.
            column.removeAllViews()
            column.addView(empty)
            column.addView(live)
            laid.clear()
            laidOpen.clear()
        }
        // Inserted above the live row rather than appended, so a settled entry
        // lands behind the thing that is still in progress.
        for (e in all) {
            if (laid.add(e.id)) {
                if (e.page != null) laidOpen.add(e.id)
                column.addView(buildRow(e), column.indexOfChild(live))
            }
        }
        post { fullScroll(FOCUS_DOWN) }
    }

    /**
     * The language changed under us. Re-wrap, then lay the list out again from
     * scratch.
     *
     * Rows carry their language in their own text — there is nothing to
     * translate in place, because the row that says 我 · 15:31 has to become
     * its English twin, not just swap a label. So forget which rows are laid
     * out and rebuild them, which is the same path an eviction takes.
     *
     * The scroll position goes with them. Acceptable: the only way to get here
     * is changing the language in 设置, which is opened over this list, and the
     * bottom of the conversation is where the user was heading anyway.
     *
     * Main thread only.
     */
    fun onLocaleChanged() {
        lctx = AppLocale.wrap(context)
        empty.text = lctx.getString(R.string.history_empty)
        laid.clear()
        laidOpen.clear()
        column.removeAllViews()
        column.addView(empty)
        column.addView(live)
        refresh()
    }

    private fun buildRow(e: ChatHistory.Entry): View {
        val mine = e.role == ChatHistory.Role.USER
        val bad = e.role == ChatHistory.Role.ERROR

        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = glass(mine, bad)
        }

        val head = TextView(context).apply {
            text = when (e.role) {
                ChatHistory.Role.USER ->
                    lctx.getString(R.string.history_role_you, clock.format(Date(e.ts)))
                ChatHistory.Role.ASSISTANT ->
                    lctx.getString(R.string.history_role_assistant, clock.format(Date(e.ts)))
                ChatHistory.Role.ERROR ->
                    lctx.getString(R.string.history_role_error, clock.format(Date(e.ts)))
                ChatHistory.Role.PAGE ->
                    if (e.page != null) {
                        lctx.getString(R.string.history_role_page_open, clock.format(Date(e.ts)))
                    } else {
                        // Said plainly rather than by greying the row out: a row
                        // that looks disabled reads as a bug in the list, not as
                        // a page that has aged out.
                        lctx.getString(R.string.history_role_page_cleared, clock.format(Date(e.ts)))
                    }
            }
            textSize = Glass.Type.MICRO
            letterSpacing = 0.05f
            setTextColor(
                when (e.role) {
                    ChatHistory.Role.USER -> Color.parseColor(Glass.SECONDARY)
                    ChatHistory.Role.ASSISTANT -> Color.parseColor(Glass.SECONDARY)
                    ChatHistory.Role.ERROR -> Color.parseColor(Glass.DANGER)
                    ChatHistory.Role.PAGE ->
                        if (e.page != null) Color.parseColor(Glass.ACCENT)
                        else Color.parseColor(Glass.MUTED)
                }
            )
        }
        bubble.addView(head)

        val body = TextView(context).apply {
            text = e.text
            textSize = Glass.Type.BODY
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(Color.parseColor(if (bad) Glass.DANGER else Glass.LABEL))
            setPadding(0, dp(3), 0, 0)
            // Long answers are for glancing at here and listening to in full;
            // the row is a handle, not the document.
            maxLines = 6
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bubble.addView(body)

        if (e.role == ChatHistory.Role.PAGE && e.page != null) {
            bubble.isClickable = true
            bubble.background = RippleDrawable(
                ColorStateList.valueOf(RIPPLE),
                glass(false, false),
                null,
            )
            bubble.setOnClickListener { onPageClick?.invoke(e) }
        } else {
            attachDoubleTap(bubble, e)
        }

        // The bubble is the row's whole width minus a shoulder on the side it
        // is *not* speaking from — which is what makes two voices readable
        // without drawing a tail or a portrait on anything.
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            addView(
                bubble,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (mine) leftMargin = dp(40) else rightMargin = dp(40)
                },
            )
        }
    }

    /**
     * Open a message fullscreen on a double tap.
     *
     * **Double, not single.** A single tap inside a scrollable list is how you
     * stop a fling and how you start a drag, and a chat where one stray tap
     * replaces the whole screen with a reader is a chat you learn to tap
     * carefully. Nothing else on a text row wants a tap, so the gesture is free
     * — and it is the same one the ball uses for "the other thing", which is one
     * less thing to learn.
     *
     * Page rows are excluded at the call site, not here: their single tap
     * already means "open the page again", and a page is not text to be read out
     * of a bubble.
     *
     * The touch listener deliberately swallows the DOWN (the detector returns
     * true while it waits to see whether a second tap follows). That does not
     * cost the list its scrolling: a ScrollView intercepts on movement, and the
     * child only ever sees the events before that — a drag cancels the detector
     * exactly as it cancels any other child gesture.
     *
     * Main thread only.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDoubleTap(v: View, e: ChatHistory.Entry) {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            /**
             * **Must return true, and the default is false.**
             *
             * `SimpleOnGestureListener` is documented as a no-op listener, and
             * `onDown` is the one place where that is not harmless: declining the
             * DOWN means this view declines the gesture, so the list becomes the
             * touch target for everything after it and the detector never sees
             * the UP — never mind the second tap. The double tap then does
             * nothing at all, silently, with no state anywhere to say why.
             */
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(ev: MotionEvent): Boolean {
                onTextOpen?.invoke(e)
                return true
            }
        })
        v.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
    }

    /**
     * The pane: a translucent film with light falling down it, under a hairline
     * that is one step brighter than the film so the edge reads as a lit rim
     * rather than as a drawn border.
     *
     * The user's own rows get a faint blue cast. Two panes of the same glass
     * with the same alignment would be one long column of identical rectangles
     * — the tint is doing the same job the alignment is, for the case where a
     * row is long enough to reach both margins anyway.
     *
     * Failures get a red one, in the same glass rather than a solid alarm
     * colour: an error that already came and went as a card is being recalled
     * here, not raised again.
     */
    private fun glass(mine: Boolean, bad: Boolean = false): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        when {
            bad -> intArrayOf(TINT_TOP_BAD, TINT_BOTTOM_BAD)
            mine -> intArrayOf(TINT_TOP_MINE, TINT_BOTTOM_MINE)
            else -> intArrayOf(TINT_TOP, TINT_BOTTOM)
        },
    ).apply {
        cornerRadius = dp(18).toFloat()
        setStroke(
            Math.max(1, dp(1) / 2),
            when {
                bad -> EDGE_BAD
                mine -> EDGE_MINE
                else -> EDGE
            },
        )
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** How long a [FloatingWindowUi.SubtitleKind.FINAL] live line stays up. */
        const val FINAL_LINGER_MS = 5_000L

        /**
         * A [Glass] token seen through the film: its hue at the film's alpha.
         * Derived rather than written out so the tinted panes cannot drift
         * away from the accent and danger colours everything else uses.
         */
        fun film(hex: String, alpha: Int): Int =
            (alpha shl 24) or (Color.parseColor(hex) and 0x00FFFFFF)

        // Alpha in the low teens is the whole trick: high enough to lift the
        // pane off the card, low enough that the card's colour still shows
        // through it, which is the only thing making it read as translucent.
        // These three are the neutral pane and match Glass's panel family.
        val TINT_TOP = Color.parseColor("#1FFFFFFF")
        val TINT_BOTTOM = Color.parseColor("#0FFFFFFF")
        val EDGE = Color.parseColor("#2EFFFFFF")

        val TINT_TOP_MINE = film(Glass.ACCENT, 0x2C)
        val TINT_BOTTOM_MINE = film(Glass.ACCENT, 0x14)
        val EDGE_MINE = film(Glass.ACCENT, 0x40)

        val TINT_TOP_BAD = film(Glass.DANGER, 0x2C)
        val TINT_BOTTOM_BAD = film(Glass.DANGER, 0x14)
        val EDGE_BAD = film(Glass.DANGER, 0x4D)

        /** The tap on a page row, in the accent it is labelled with. */
        val RIPPLE = film(Glass.ACCENT, 0x33)
    }
}
