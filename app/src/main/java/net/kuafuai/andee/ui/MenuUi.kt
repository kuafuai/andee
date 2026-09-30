package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Long-press menu overlay. A rounded white card with clickable items.
 * Auto-dismisses after [AUTO_DISMISS_MS]. Position anchored by the caller.
 */
class MenuUi(private val context: Context) {

    data class Item(val label: String, val onClick: () -> Unit)

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val autoDismiss = Runnable { dismiss() }

    fun isShowing(): Boolean = view != null

    fun show(items: List<Item>, anchorX: Int, anchorY: Int) {
        if (view != null) return
        val container = buildView(items)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchorX; y = anchorY
        }
        try {
            wm.addView(container, params)
            view = container
            ui.postDelayed(autoDismiss, AUTO_DISMISS_MS)
        } catch (_: Throwable) {
            // ignore
        }
    }

    fun dismiss() {
        ui.removeCallbacks(autoDismiss)
        val v = view ?: return
        runCatching { wm.removeView(v) }
        view = null
    }

    private fun buildView(items: List<Item>): View {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#FFFFFF"))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.parseColor("#E5E7EB"))
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for (item in items) container.addView(makeItem(item))
        return container
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeItem(item: Item): View =
        TextView(context).apply {
            text = item.label
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextColor(Color.parseColor("#111827"))
            textSize = 14f
            isClickable = true
            isFocusable = true
            minWidth = dp(160)
            setOnClickListener { item.onClick() }
        }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val AUTO_DISMISS_MS = 5_000L
    }
}
