package net.kuafuai.andee.ui

import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale

/**
 * Chrome shared by the two camera screens (`LookActivity`, `ScanActivity`).
 *
 * It exists because both had grown their own `FrameLayout + PreviewView +
 * TextView`, and both sized that TextView in **raw pixels** — a readable
 * banner on the phone it was written on, a postage stamp on the tablet.
 * Everything here is dp.
 *
 * The viewfinder is not decoration. The camera is the most invasive thing
 * this device does, so the user has to be able to tell, without asking,
 * when it is on and the moment a frame is actually taken — that is what
 * [ViewfinderView.flash] is for.
 */

fun Context.dpF(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpI(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/**
 * Taken from [Glass] rather than written out, so the camera screens, the
 * cards and the assistant's dialogs are one palette — these are the only
 * surfaces where the user sees our chrome over content we did not draw.
 *
 * [PILL_FILL] is [Glass.CARD_SOLID] for the reason the name gives: a pill is
 * a floating child, nothing blurs behind it (see [closePill]), so it takes
 * the fill the cards fall back to when the compositor refuses them a blur.
 */
private val IDLE_STROKE = 0x66FFFFFF.toInt()
private val ACCENT = Color.parseColor(Glass.ACCENT)
private val PILL_FILL = Color.parseColor(Glass.CARD_SOLID)
private val PILL_EDGE = Color.parseColor(Glass.HAIRLINE)
private val PILL_TEXT = Color.parseColor(Glass.TITLE)

/**
 * Four corner brackets around the frame. Idle is a quiet hairline; [flash]
 * pushes it to the ball's accent colour and lets it fall back.
 */
class ViewfinderView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpF(2f)
        strokeCap = Paint.Cap.ROUND
        color = IDLE_STROKE
    }
    private val arm = context.dpF(26f)
    private var anim: ValueAnimator? = null

    /** Pulse the brackets — call the moment a frame is actually captured. */
    fun flash(color: Int = ACCENT) {
        anim?.cancel()
        anim = ValueAnimator.ofObject(ArgbEvaluator(), IDLE_STROKE, color, IDLE_STROKE).apply {
            duration = 280
            addUpdateListener { paint.color = it.animatedValue as Int; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val l = w * 0.08f
        val t = h * 0.08f
        val r = w - l
        val b = h - t
        // top-left
        canvas.drawLine(l, t, l + arm, t, paint)
        canvas.drawLine(l, t, l, t + arm, paint)
        // top-right
        canvas.drawLine(r - arm, t, r, t, paint)
        canvas.drawLine(r, t, r, t + arm, paint)
        // bottom-left
        canvas.drawLine(l, b - arm, l, b, paint)
        canvas.drawLine(l, b, l + arm, b, paint)
        // bottom-right
        canvas.drawLine(r - arm, b, r, b, paint)
        canvas.drawLine(r, b - arm, r, b, paint)
    }
}

/**
 * Bottom-centred status line: a slowly breathing dot (the session is open)
 * plus whatever the screen wants to say. [setStatus] is the only way to
 * change the text.
 */
class StatusPill(context: Context) : LinearLayout(context) {

    private val dot = View(context)
    private val label = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dpI(16), context.dpI(9), context.dpI(18), context.dpI(9))
        background = GradientDrawable().apply {
            setColor(PILL_FILL)
            cornerRadius = context.dpF(22f)
            setStroke(context.dpI(1), PILL_EDGE)
        }
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ACCENT)
        }
        addView(dot, LayoutParams(context.dpI(8), context.dpI(8)).apply {
            marginEnd = context.dpI(9)
        })
        label.apply {
            textSize = 15f
            setTextColor(PILL_TEXT)
        }
        addView(label)
        ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.3f).apply {
            duration = 1100
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    fun setStatus(text: String) {
        label.text = text
    }

    fun setDotColor(color: Int) {
        (dot.background as GradientDrawable).setColor(color)
    }
}

/**
 * The close control, identical on every surface the brain can put in front
 * of the user — camera screens and `HtmlActivity` alike.
 *
 * Text, not a bare ✕: on an unfamiliar generated page a lone glyph reads as
 * part of the page. The 1dp stroke is not decoration either — a dark pill on
 * a dark page disappears, and the brain picks the page's background, not us.
 *
 * (No frosted glass: `RenderEffect` blurs a View's *own* content, and
 * blurring what is behind needs `Window.setBackgroundBlurRadius`, which is
 * window-level and cannot be scoped to one floating child. Translucency plus
 * a stroke is the honest approximation.)
 */
fun closePill(context: Context, onClose: () -> Unit): TextView =
    TextView(context).apply {
        text = AppLocale.str(context, R.string.cam_close)
        textSize = 15f
        setTextColor(PILL_TEXT)
        gravity = Gravity.CENTER
        setPadding(context.dpI(18), context.dpI(8), context.dpI(18), context.dpI(8))
        background = GradientDrawable().apply {
            setColor(PILL_FILL)
            cornerRadius = context.dpF(22f)
            setStroke(context.dpI(1), PILL_EDGE)
        }
        isClickable = true
        stateListAnimator = pressAnimator()
        setOnClickListener { onClose() }
    }

private fun pressAnimator(): StateListAnimator = StateListAnimator().apply {
    addState(
        intArrayOf(android.R.attr.state_pressed),
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(null, "scaleX", 0.94f),
                ObjectAnimator.ofFloat(null, "scaleY", 0.94f),
                ObjectAnimator.ofFloat(null, "alpha", 0.85f),
            )
            duration = 90
        },
    )
    addState(
        IntArray(0),
        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(null, "scaleX", 1f),
                ObjectAnimator.ofFloat(null, "scaleY", 1f),
                ObjectAnimator.ofFloat(null, "alpha", 1f),
            )
            duration = 120
        },
    )
}

/**
 * Keep [view]'s margins clear of the status bar / gesture bar. Needed because
 * the camera screens go edge-to-edge for a full-bleed preview, which puts our
 * own chrome under the system clock unless we ask.
 */
fun avoidSystemBars(view: View, topDp: Int = 0, bottomDp: Int = 0) {
    val ctx = view.context
    ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            if (topDp > 0) lp.topMargin = bars.top + ctx.dpI(topDp)
            if (bottomDp > 0) lp.bottomMargin = bars.bottom + ctx.dpI(bottomDp)
            v.layoutParams = lp
        }
        insets
    }
}

/** Full-bleed layout params, for the preview underneath the chrome. */
fun matchParent(): FrameLayout.LayoutParams = FrameLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
)
