package net.kuafuai.andee.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Top-center transcript pill. Lazily attached on first [show]; stays visible
 * until [hide] is called (optionally after a delay).
 */
class PillUi(private val context: Context) {

    enum class Kind { LISTENING, FINAL, SPEAKING, ERROR }

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var text: TextView? = null
    private val hideRunnable = Runnable { hideNow() }

    fun show(msg: String, kind: Kind) {
        ui.post {
            ui.removeCallbacks(hideRunnable)
            ensurePill()
            text?.text = msg
            text?.setTextColor(textColor(kind))
            (view?.background as? GradientDrawable)?.setStroke(dp(2), strokeColor(kind))
        }
    }

    fun hide(delayMs: Long = 0) {
        if (delayMs <= 0) ui.post { hideNow() } else ui.postDelayed(hideRunnable, delayMs)
    }

    private fun hideNow() {
        val v = view ?: return
        runCatching { wm.removeView(v) }
        view = null
        text = null
    }

    private fun ensurePill() {
        if (view != null) return
        val tv = TextView(context).apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            textSize = 18f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(textColor(Kind.LISTENING))
        }
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#EE0F172A"))
            cornerRadius = dp(20).toFloat()
            setStroke(dp(2), strokeColor(Kind.LISTENING))
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg
            addView(tv)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40)
            width = context.resources.displayMetrics.widthPixels - dp(80)
        }
        try {
            wm.addView(container, params)
            view = container
            text = tv
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun textColor(kind: Kind): Int = when (kind) {
        Kind.LISTENING -> Color.parseColor("#CBD5E1")
        Kind.FINAL -> Color.parseColor("#FBBF24")
        Kind.SPEAKING -> Color.parseColor("#67E8F9")
        Kind.ERROR -> Color.parseColor("#F87171")
    }

    private fun strokeColor(kind: Kind): Int = when (kind) {
        Kind.LISTENING -> Color.parseColor("#DC2626")
        Kind.FINAL -> Color.parseColor("#F59E0B")
        Kind.SPEAKING -> Color.parseColor("#06B6D4")
        Kind.ERROR -> Color.parseColor("#DC2626")
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
