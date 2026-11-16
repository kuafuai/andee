package net.kuafuai.andee.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale

/**
 * The recording indicator: a small pill pinned under the status bar while a
 * flow is being recorded — 「● 录制中 · N 步」. The one answer to "is it
 * remembering this?" that survives the card being folded: recording happens
 * exactly when the card folds out of the way, so an indicator that lives on
 * the card ([ScenesUi]) would be invisible for the whole recording.
 *
 * A window of its own, like [TapMarkerUi], for the same reasons: small (the
 * opacity budget), NOT_FOCUSABLE so the system never asks it anything,
 * NOT_TOUCHABLE so every touch passes through to the app being operated, and
 * alpha-folded by [CommandDispatcher] for gestures and captures — an injected
 * tap must not land under an untrusted overlay (payment apps drop obscured
 * taps), and a screenshot the brain reads must not show our chrome.
 *
 * The red dot is the one colour still free on this device: the tap marker owns
 * magenta, WeChat owns green, every Chinese app owns a red *badge* — but this
 * pill is a red *dot with words*, which is the screen-recorder convention the
 * user already knows.
 */
class RecordChipUi(private val context: Context) {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())

    private var view: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var steps = 0
    private var visible = false

    /** Main thread only; folded into the window alpha by [applyWindow]. */
    private var captureHidden = false
    private var gestureHidden = false

    fun show(stepsSoFar: Int = 0) {
        steps = stepsSoFar
        visible = true
        ui.post {
            if (view == null) addView()
            paint()
            applyWindow()
        }
    }

    fun hide() {
        visible = false
        ui.post {
            applyWindow()
            val v = view ?: return@post
            runCatching { wm.removeView(v) }
            view = null
            params = null
        }
    }

    fun setSteps(n: Int) {
        steps = n
        ui.post {
            paint()
        }
    }

    /** Hide for an injected gesture — see the class KDoc. */
    fun setVisibleForGesture(show: Boolean) {
        ui.post { gestureHidden = !show; applyWindow() }
    }

    /** Hide for a display capture, so the brain never sees our chrome. */
    fun setVisibleForCapture(show: Boolean) {
        ui.post { captureHidden = !show; applyWindow() }
    }

    private fun addView() {
        val lctx = AppLocale.wrap(context)
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(5), dp(14), dp(5))
            background = Glass.well(context, dp(18))
            addView(TextView(context).apply {
                text = "●"
                textSize = Glass.Type.CAPTION
                setTextColor(Color.parseColor(Glass.ACCENT))
                setPadding(0, 0, dp(7), 0)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            addView(TextView(context).apply {
                text = ""
                textSize = Glass.Type.CAPTION
                setTextColor(Color.parseColor(Glass.LABEL))
                maxLines = 1
                tag = "chip_text"
            })
        }
        // The text view is found by tag so [paint] can write it without
        // holding a second field that can dangle after [hide].
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(12)
            alpha = 0f
        }
        pill.hideFromAccessibility()
        val text: TextView = (0 until pill.childCount)
            .mapNotNull { i -> pill.getChildAt(i) as? TextView }
            .first { it.tag == "chip_text" }
        text.text = lctx.getString(R.string.flows_recording_chip, steps)
        try {
            wm.addView(pill, p)
            view = pill
            params = p
        } catch (t: Throwable) {
            android.util.Log.e("Body", "record chip addView failed", t)
        }
    }

    /** Main thread only. */
    private fun paint() {
        val pill = view ?: return
        val text: TextView = (0 until pill.childCount)
            .mapNotNull { i -> pill.getChildAt(i) as? TextView }
            .firstOrNull { it.tag == "chip_text" } ?: return
        text.text = AppLocale.str(context, R.string.flows_recording_chip, steps)
    }

    /** Main thread only. */
    private fun applyWindow() {
        val v = view ?: return
        val p = params ?: return
        val want = if (visible && !captureHidden && !gestureHidden) 1f else 0f
        if (p.alpha == want) return
        p.alpha = want
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
