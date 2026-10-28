package net.kuafuai.andee.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import net.kuafuai.andee.text.Md

/**
 * The phone's renderer for [Md]: what the model writes in the notebook and the wiki, drawn as Views in the
 * cards' palette instead of a page of asterisks and pipes. The parsing is shared with the Mac (`Markdown.kt`
 * there), so the two bodies show one article the same way.
 *
 * [onLink] receives an http(s) URL when one is tapped. The caller opens it — and folds its card first, because
 * an overlay sits above the browser it would open.
 */
object MarkdownView {

    fun build(
        context: Context,
        text: String,
        color: String = Glass.LABEL,
        size: Float = Glass.Type.BODY,
        onLink: (String) -> Unit = {},
    ): View {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        Md.blocks(text).forEachIndexed { i, b ->
            val v = block(context, b, color, size, onLink)
            // A rule is an empty View: WRAP would let it take whatever height its parent offers.
            col.addView(v, LinearLayout.LayoutParams(MATCH, if (b is Md.Block.Rule) dp(context, 1) else WRAP).apply {
                if (i > 0) topMargin = dp(context, if (b is Md.Block.Heading) 14 else 8)
            })
        }
        return col
    }

    private fun block(c: Context, b: Md.Block, color: String, size: Float, onLink: (String) -> Unit): View = when (b) {
        is Md.Block.Heading -> text(c, b.text, Glass.TITLE, size + when (b.level) { 1 -> 5f; 2 -> 3f; 3 -> 1f; else -> 0f }, onLink).apply {
            setTypeface(typeface, if (b.level <= 3) Typeface.BOLD else Typeface.NORMAL)
        }
        is Md.Block.Para -> text(c, b.text, color, size, onLink)
        is Md.Block.Item -> LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(c, b.depth * 16), 0, 0, 0)
            addView(TextView(c).apply {
                this.text = b.marker
                textSize = size
                setTextColor(Color.parseColor(Glass.SECONDARY))
                minWidth = dp(c, 18)
                setLineSpacing(0f, LINE)
            }, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(text(c, b.text, color, size, onLink), LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        is Md.Block.Quote -> LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(View(c).apply { setBackgroundColor(Color.parseColor(Glass.HAIRLINE)) },
                LinearLayout.LayoutParams(dp(c, 3), LinearLayout.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(c, 10) })
            val inner = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            b.inner.forEachIndexed { i, ib ->
                inner.addView(block(c, ib, Glass.SECONDARY, size, onLink), LinearLayout.LayoutParams(MATCH, if (ib is Md.Block.Rule) dp(c, 1) else WRAP).apply {
                    if (i > 0) topMargin = dp(c, 6)
                })
            }
            addView(inner, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        is Md.Block.Code -> HorizontalScrollView(c).apply {
            background = Glass.well(c, dp(c, 8))
            setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8))
            isHorizontalScrollBarEnabled = false
            addView(TextView(c).apply {
                this.text = b.text
                textSize = size - 1f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.parseColor(color))
                setLineSpacing(0f, LINE)
                setHorizontallyScrolling(true)
            })
        }
        is Md.Block.Table -> table(c, b, color, size, onLink)
        Md.Block.Rule -> View(c).apply { setBackgroundColor(Color.parseColor(Glass.HAIRLINE)) }
    }

    /** Columns share the width equally and wrap within it; a phone is too narrow for a table that scrolls sideways. */
    private fun table(c: Context, t: Md.Block.Table, color: String, size: Float, onLink: (String) -> Unit): View {
        val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
        val s = size - 1f
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.well(c, dp(c, 8))
            setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4))
        }
        fun row(cells: List<String>, head: Boolean) {
            val r = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(c, 6), 0, dp(c, 6)) }
            for (k in 0 until cols) {
                r.addView(text(c, cells.getOrElse(k) { "" }, if (head) Glass.TITLE else color, s, onLink).apply {
                    if (head) setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, 0, dp(c, 8), 0)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            box.addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        fun line() = box.addView(View(c).apply { setBackgroundColor(Color.parseColor(Glass.HAIRLINE)) }, LinearLayout.LayoutParams(MATCH, dp(c, 1)))
        row(t.header, true)
        line()
        t.rows.forEachIndexed { i, r -> if (i > 0) line(); row(r, false) }
        return box
    }

    private fun text(c: Context, md: String, color: String, size: Float, onLink: (String) -> Unit): TextView = TextView(c).apply {
        val (spanned, links) = inline(md, onLink)
        text = spanned
        textSize = size
        setTextColor(Color.parseColor(color))
        setLineSpacing(0f, LINE)
        // Only where there is something to tap: LinkMovementMethod also takes over the view's touch handling.
        if (links) movementMethod = LinkMovementMethod.getInstance()
    }

    /** [Md.runs] as spans. Returns whether any of them can be tapped. */
    private fun inline(md: String, onLink: (String) -> Unit): Pair<CharSequence, Boolean> {
        val sb = SpannableStringBuilder()
        var links = false
        for (r in Md.runs(md)) {
            val start = sb.length
            sb.append(r.text)
            val end = sb.length
            fun span(o: Any) = sb.setSpan(o, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            when {
                r.bold && r.italic -> span(StyleSpan(Typeface.BOLD_ITALIC))
                r.bold -> span(StyleSpan(Typeface.BOLD))
                r.italic -> span(StyleSpan(Typeface.ITALIC))
            }
            if (r.code) { span(TypefaceSpan("monospace")); span(BackgroundColorSpan(Color.parseColor(Glass.WELL))) }
            if (r.strike) span(StrikethroughSpan())
            if (r.link) span(ForegroundColorSpan(Color.parseColor(Glass.ACCENT)))
            val url = r.url
            if (url != null) {
                links = true
                span(object : ClickableSpan() {
                    override fun onClick(widget: View) = onLink(url)
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = Color.parseColor(Glass.ACCENT)
                        ds.isUnderlineText = true
                    }
                })
            }
        }
        return sb to links
    }

    private fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density).toInt()

    private const val LINE = 1.35f
    private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
}
