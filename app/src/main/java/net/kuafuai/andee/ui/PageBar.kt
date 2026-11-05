package net.kuafuai.andee.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale

/**
 * The thin glass bar across the top of a page ([HtmlActivity]): the page's name, centred and cut with an ellipsis
 * when it is long, and a close on the right. The page starts under it, so nothing of the page is covered.
 *
 * **Glass without a blur behind it.** Nothing scrolls under this bar — the page begins below it — so there is no
 * content to blur, and `RenderEffect` could only blur the bar's own letters. What reads as glass here is the same
 * thing that does on the cards ([Glass]): a translucent fill, a light sheen down from the top, and a hairline at the
 * bottom edge. The fill takes the page's own background once it is known ([tintFrom]), lifted toward white on a
 * dark page and toward a cool grey on a light one, so the bar belongs to the page the way Safari's toolbar does,
 * and the letters pick black or white by the tint's luminance.
 */
class PageBar(context: Context, name: String, onClose: () -> Unit) : FrameLayout(context) {

    private val title = TextView(context)
    private val close = TextView(context)
    private val hairline = View(context)
    private var topInset = 0

    init {
        title.apply {
            text = name
            textSize = 15f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }
        // The close: a ✕ in a small round well, the cards' own close control in shape.
        close.apply {
            text = "✕"
            contentDescription = AppLocale.str(context, R.string.cam_close)
            textSize = 13f
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { onClose() }
        }
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, dp(HEIGHT_DP), Gravity.BOTTOM).apply {
            // Room for the close on the right, and the same on the left so the name stays centred.
            leftMargin = dp(CLOSE_DP + 24); rightMargin = dp(CLOSE_DP + 24)
        })
        addView(close, LayoutParams(dp(CLOSE_DP), dp(CLOSE_DP), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dp(12); bottomMargin = dp((HEIGHT_DP - CLOSE_DP) / 2)
        })
        addView(hairline, LayoutParams(LayoutParams.MATCH_PARENT, 1, Gravity.BOTTOM))
        paint(null)
    }

    /** Below the status bar: the window is edge-to-edge, and the bar runs under it. */
    fun setTopInset(top: Int) {
        if (top == topInset && layoutParams?.height == top + dp(HEIGHT_DP)) return
        topInset = top
        layoutParams = (layoutParams as? LayoutParams ?: LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
            .apply { height = top + dp(HEIGHT_DP) }
    }

    fun setTitle(t: String) { title.text = t }

    /** The page's background as CSS reports it (`rgb(…)` / `rgba(…)`), or anything unreadable for the default. */
    fun tintFrom(css: String) {
        val m = Regex("""rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)(?:\s*,\s*([\d.]+))?""").find(css)
        val c = m?.let {
            val a = it.groupValues[4].toFloatOrNull() ?: 1f
            if (a < 0.5f) null else Color.rgb(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        post { paint(c) }
    }

    private fun paint(page: Int?) {
        // No page colour yet: the palette's own surface, so the bar is never a different thing from the cards.
        val base = page ?: Color.parseColor(if (Theme.night) "#FF141821" else "#FFF4F5F7")
        val dark = luminance(base) < 0.5f
        // Glass: the page's colour, lifted a little (toward white on dark, toward a cool grey on light), at 88%.
        val fill = mix(base, if (dark) Color.WHITE else Color.rgb(226, 230, 238), if (dark) 0.10f else 0.14f)
        val glass = Color.argb(224, Color.red(fill), Color.green(fill), Color.blue(fill))
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(if (dark) 34 else 120, 255, 255, 255), Color.argb(0, 255, 255, 255)),
        )
        background = LayerDrawable(arrayOf(GradientDrawable().apply { setColor(glass) }, sheen))
        hairline.setBackgroundColor(if (dark) Color.argb(46, 255, 255, 255) else Color.argb(30, 0, 0, 0))
        val ink = if (dark) Color.argb(240, 255, 255, 255) else Color.argb(222, 0, 0, 0)
        title.setTextColor(ink)
        close.setTextColor(ink)
        close.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (dark) Color.argb(36, 255, 255, 255) else Color.argb(20, 0, 0, 0))
        }
    }

    private fun luminance(c: Int): Float = (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** The bar's height under the status bar. The page starts this far below the status bar. */
        const val HEIGHT_DP = 44
        private const val CLOSE_DP = 28
    }
}
