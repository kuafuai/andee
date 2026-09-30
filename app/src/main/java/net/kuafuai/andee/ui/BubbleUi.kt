package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import kotlin.math.abs

/**
 * Floating bubble overlay with three visual modes (BALL / HALF / FULL) and
 * five semantic states (IDLE / RECORDING / THINKING / PLAYING / SPEAKING / ERROR).
 * Emits tap and long-press to [Listeners]. Only BALL mode is draggable.
 */
class BubbleUi(
    private val context: Context,
    private val listeners: Listeners,
) {

    enum class Mode { BALL, HALF, FULL }

    enum class State { IDLE, RECORDING, THINKING, PLAYING, SPEAKING, ERROR }

    interface Listeners {
        fun onTap()
        fun onLongPress()
    }

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())

    /** Strings in the user's language; views are still built on [context]. */
    private val lctx: Context = AppLocale.wrap(context)

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var label: TextView? = null

    @Volatile
    private var mode: Mode = Mode.BALL

    @Volatile
    private var state: State = State.IDLE

    fun currentMode(): Mode = mode
    fun isShown(): Boolean = view != null
    fun params(): WindowManager.LayoutParams? = params

    fun show(): Boolean {
        if (view != null) return true
        val (v, p) = when (mode) {
            Mode.BALL -> createBallView()
            Mode.HALF -> createHalfView()
            Mode.FULL -> createFullView()
        }
        return try {
            wm.addView(v, p)
            view = v
            params = p
            attachDragAndTap(v, p)
            applyState()
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun hide(): Boolean {
        val v = view ?: return true
        return try {
            wm.removeView(v)
            view = null
            params = null
            label = null
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun setMode(newMode: Mode) {
        if (newMode == mode && view != null) return
        mode = newMode
        ui.post {
            hide()
            show()
        }
    }

    fun setState(newState: State) {
        state = newState
        ui.post { applyState() }
    }

    fun setLabel(text: String) {
        ui.post { label?.text = text }
    }

    // ---- View creation per mode ----

    private fun createBallView(): Pair<View, WindowManager.LayoutParams> {
        val v = View(context)
        val fill = colorFor(state)
        v.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(2), Color.WHITE)
        }
        v.layoutParams = ViewGroup.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP))
        label = null
        val p = WindowManager.LayoutParams(
            dp(BUBBLE_DP), dp(BUBBLE_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(24); y = dp(200)
        }
        return v to p
    }

    private fun createHalfView(): Pair<View, WindowManager.LayoutParams> {
        val panel = buildPanel(lctx.getString(R.string.bubble_tap_to_talk))
        val screenH = context.resources.displayMetrics.heightPixels
        val screenW = context.resources.displayMetrics.widthPixels
        val p = WindowManager.LayoutParams(
            screenW, screenH / 2,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = 0; y = 0
        }
        return panel to p
    }

    private fun createFullView(): Pair<View, WindowManager.LayoutParams> {
        val panel = buildPanel(lctx.getString(R.string.bubble_tap_to_talk))
        val screenH = context.resources.displayMetrics.heightPixels
        val screenW = context.resources.displayMetrics.widthPixels
        val p = WindowManager.LayoutParams(
            screenW, screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        return panel to p
    }

    private fun buildPanel(labelText: String): View {
        val fill = colorFor(state)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            setStroke(dp(2), Color.WHITE)
        }
        val text = TextView(context).apply {
            text = labelText
            textSize = 48f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        label = text
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = bg
            addView(
                text,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun applyState() {
        val v = view ?: return
        (v.background as? GradientDrawable)?.setColor(colorFor(state))
    }

    private fun colorFor(s: State): Int = when (s) {
        State.IDLE -> Color.parseColor("#4F46E5")       // indigo
        State.RECORDING -> Color.parseColor("#DC2626")  // red
        State.THINKING -> Color.parseColor("#0EA5E9")   // sky
        State.PLAYING -> Color.parseColor("#F59E0B")    // amber
        State.SPEAKING -> Color.parseColor("#06B6D4")   // cyan
        State.ERROR -> Color.parseColor("#DC2626")      // red
    }

    // ---- Touch handling (drag only in BALL mode) ----

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTap(view: View, params: WindowManager.LayoutParams) {
        var startParamsX = 0
        var startParamsY = 0
        var startTouchX = 0f
        var startTouchY = 0f
        var moved = false
        var longPressed = false
        val slop = dp(6)
        val longPressMs = 500L
        val longPress = Runnable {
            if (!moved) {
                longPressed = true
                listeners.onLongPress()
            }
        }
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startParamsX = params.x
                    startParamsY = params.y
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    moved = false
                    longPressed = false
                    ui.postDelayed(longPress, longPressMs)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - startTouchX).toInt()
                    val dy = (event.rawY - startTouchY).toInt()
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        ui.removeCallbacks(longPress)
                    }
                    if (moved && mode == Mode.BALL) {
                        params.x = startParamsX + dx
                        params.y = startParamsY + dy
                        wm.updateViewLayout(view, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(longPress)
                    if (!moved && !longPressed) listeners.onTap()
                    true
                }

                else -> false
            }
        }
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    companion object {
        const val BUBBLE_DP = 100
    }
}
