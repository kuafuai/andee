package net.kuafuai.andee.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * What the turn in progress is doing ([PhoneWorkLog]), in two sizes.
 *
 * [Card]: the unfolded card's version, at the foot of the conversation — a pulsing dot and a ticking status over
 * the newest [CARD_LINES] lines, each rising into place, the older ones dimmer. The Mac's `WorkCard`.
 *
 * [Tiny]: the caption under the Andee docked into the right edge — the status and the newest two lines in very small
 * type, centred under it, with a dark halo so it reads on a white page and a black one alike.
 *
 * Both repaint from [PhoneWorkLog] on its change and on a half-second tick while a turn is active, and hide
 * themselves when none is. Main thread only.
 */
object WorkCardView {

    /** Lines the card shows at once; older ones scroll away. */
    const val CARD_LINES = 4

    /** Lines under the docked ball, after the status. */
    const val TINY_LINES = 2

    class Card(context: Context) : LinearLayout(context) {
        private val dot = View(context)
        private val status = TextView(context)
        private val rows = List(CARD_LINES) { TextView(context) }
        private var shownIds = emptyList<Long>()
        private val tick = object : Runnable {
            override fun run() { paintStatus(); if (PhoneWorkLog.active) postDelayed(this, 500) }
        }
        private val listener: () -> Unit = { repaint() }
        private var pulse: ValueAnimator? = null

        /** It appeared or grew a line: the list it sits at the foot of should show it. */
        var onGrew: (() -> Unit)? = null

        init {
            orientation = VERTICAL
            visibility = GONE
            setPadding(dp(14), dp(9), dp(14), dp(10))
            val head = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            head.addView(dot, LayoutParams(dp(7), dp(7)))
            status.textSize = 12f
            head.addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(8) })
            addView(head)
            rows.forEach { r ->
                r.textSize = 12f
                r.maxLines = 1
                r.ellipsize = TextUtils.TruncateAt.END
                r.setPadding(0, dp(2), 0, 0)
                addView(r, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            repaintChrome()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            PhoneWorkLog.addListener(listener)
            repaint()
        }

        override fun onDetachedFromWindow() {
            PhoneWorkLog.removeListener(listener)
            removeCallbacks(tick)
            pulse?.cancel()
            super.onDetachedFromWindow()
        }

        /** Colours from the palette; called again on a theme change. */
        fun repaintChrome() {
            background = Glass.panel(context, dp(14))
            (dot.background as GradientDrawable).setColor(Color.parseColor(Glass.ACCENT))
            status.setTextColor(Color.parseColor(Glass.SECONDARY))
        }

        private fun paintStatus() { status.text = PhoneWorkLog.status(context) }

        private fun repaint() {
            val on = PhoneWorkLog.active
            if (!on) {
                visibility = GONE
                removeCallbacks(tick)
                pulse?.cancel(); pulse = null
                shownIds = emptyList()
                return
            }
            if (visibility != VISIBLE) {
                visibility = VISIBLE
                onGrew?.invoke()
                removeCallbacks(tick); post(tick)
                pulse = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.35f).apply {
                    duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
                }
            }
            paintStatus()
            val tail = PhoneWorkLog.lines.takeLast(CARD_LINES)
            // Bottom-aligned: the newest line is always the last row.
            val pad = CARD_LINES - tail.size
            rows.forEachIndexed { i, r ->
                val l = if (i < pad) null else tail[i - pad]
                if (l == null) { r.text = ""; r.visibility = if (tail.isEmpty()) GONE else INVISIBLE; return@forEachIndexed }
                r.visibility = VISIBLE
                r.text = if (l.kind == PhoneWorkLog.Kind.ACTION) "› " + l.text else l.text
                r.setTextColor(Color.parseColor(if (l.kind == PhoneWorkLog.Kind.THOUGHT) Glass.SECONDARY else Glass.LABEL))
                r.setTypeface(null, if (l.kind == PhoneWorkLog.Kind.THOUGHT) Typeface.ITALIC else Typeface.NORMAL)
                val age = CARD_LINES - 1 - i
                r.alpha = listOf(1f, 0.72f, 0.5f, 0.32f)[age.coerceIn(0, 3)]
            }
            // The newest row rises into place when it is new.
            val newest = tail.lastOrNull()?.id
            if (newest != null && newest !in shownIds) {
                onGrew?.invoke()
                rows.last().translationY = dp(8).toFloat()
                rows.last().animate().translationY(0f).setDuration(200).start()
            }
            shownIds = tail.map { it.id }
        }

        private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    }

    /**
     * The caption under the ball while it works. Not a pane: white letters with a dark halo, centred under the
     * ball, the status a touch brighter than the two lines under it.
     */
    class Tiny(context: Context) : LinearLayout(context) {
        private val status = text(true)
        private val rows = List(TINY_LINES) { text(false) }
        private val tick = object : Runnable {
            override fun run() { status.text = PhoneWorkLog.status(context); if (PhoneWorkLog.active) postDelayed(this, 500) }
        }
        private var lastNewest = -1L

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(status)
            rows.forEach { addView(it) }
        }

        private fun text(head: Boolean) = TextView(context).apply {
            textSize = if (head) TEXT_SP + 0.5f else TEXT_SP
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (head) Color.WHITE else Color.argb(225, 255, 255, 255))
            if (head) setTypeface(typeface, Typeface.BOLD)
            // The halo: there is no pane, so the letters carry their own contrast.
            setShadowLayer(3.5f, 0f, 0.6f, Color.argb(235, 0, 0, 0))
            includeFontPadding = false
            setPadding(0, 0, 0, (1 * resources.displayMetrics.density).toInt())
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        /** Called by the window whenever [PhoneWorkLog] changes while this is shown. */
        fun repaint() {
            removeCallbacks(tick)
            if (!PhoneWorkLog.active) return
            post(tick)
            val tail = PhoneWorkLog.lines.takeLast(TINY_LINES)
            val pad = TINY_LINES - tail.size
            rows.forEachIndexed { i, r ->
                val l = if (i < pad) null else tail[i - pad]
                r.text = l?.let { if (it.kind == PhoneWorkLog.Kind.ACTION) "› " + it.text else it.text } ?: ""
                r.alpha = if (i == TINY_LINES - 1) 1f else 0.6f
            }
            val newest = tail.lastOrNull()?.id ?: -1L
            if (newest != lastNewest && newest != -1L) {
                rows.last().translationY = (5 * resources.displayMetrics.density)
                rows.last().animate().translationY(0f).setDuration(180).start()
            }
            lastNewest = newest
        }

        fun stop() { removeCallbacks(tick) }
    }

    /** The caption's letter size, sp. Small on purpose: it sits under a clinging ball, not in a card. */
    const val TEXT_SP = 9f
}
