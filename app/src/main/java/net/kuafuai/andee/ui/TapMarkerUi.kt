package net.kuafuai.andee.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.max

/**
 * Where the last injected tap landed: a ring, a dot, and the coordinate.
 *
 * Two audiences, which is why it lingers instead of flashing. The user gets to
 * see that something was pressed and where — otherwise an agent driving the
 * tablet is a screen that changes for no visible reason. The brain gets it in
 * the next screenshot: [ScreenController] speaks 0-1000 relative coordinates
 * into a device it cannot see, and the ring is the only feedback anywhere in
 * the loop that closes that circle — "you asked for 500,500 and here is what
 * 500,500 turned out to be". It is worth the most after `tap_screen_element`, where
 * nobody knew the pixel location in advance, and after a tap that appeared to
 * do nothing: a ring sitting next to the button rather than on it is a
 * different problem from a ring on the button that did not respond.
 *
 * ## Why this window is small
 *
 * It is the third overlay this app puts on the screen, and the first two
 * already spend the whole budget. Android stops delivering a touch to the app
 * underneath when untrusted overlays obscure it by more than 0.80 combined
 * opacity, counting the *window's* opacity over the *window's bounds* — not
 * its pixels. A visible full-screen overlay is billed at the 0.80 cap on its
 * own, so this one plus a breathing [EdgeRippleUi] would compute to 0.96 and
 * silently eat every touch on the device, the user's included. Sized to the
 * mark, it can only ever affect the ~144dp box it is drawn in, and only until
 * it expires.
 *
 * It still has to be out of the way for *injected* taps, which land at exactly
 * the coordinate it is sitting on — see [setVisibleForGesture], called from
 * the same passthrough dance as the glow.
 *
 * ## Why it is NOT hidden for screenshots
 *
 * [FloatingWindowUi] and [EdgeRippleUi] both blank themselves during a display
 * capture so the brain never receives a picture of our own UI. This one is the
 * deliberate exception: being in the screenshot is half of what it is for.
 */
class TapMarkerUi(private val context: Context) {

    enum class Kind(val label: String) {
        TAP("tap"),
        HOLD("hold"),
    }

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())

    private var view: MarkView? = null
    private var params: WindowManager.LayoutParams? = null

    /** Main thread only, folded into the window alpha by [applyWindow]. */
    private var gestureHidden = false
    private var showing = false

    private val density = context.resources.displayMetrics.density
    private val box = (BOX_DP * density).toInt()

    /**
     * Where the last mark was placed, in screen pixels, or null if nothing has
     * been marked yet. For the grounding log: the ring is deliberately NOT
     * blanked for captures (see the class KDoc), so anything comparing two
     * screenshots has to mask the box it occupies — otherwise our own ring is
     * the biggest difference between them.
     */
    @Volatile private var lastMark: IntArray? = null
    @Volatile private var lastMarkAt = 0L

    fun lastMarkPx(): IntArray? = lastMark

    /** Milliseconds since [lastMarkPx] was drawn — past [MarkView.HOLD_MS] it is gone. */
    fun lastMarkAgeMs(): Long? =
        if (lastMark == null) null else SystemClock.uptimeMillis() - lastMarkAt

    fun show(): Boolean {
        if (view != null) return true
        val v = MarkView(context) { onIdle() }
        val p = WindowManager.LayoutParams(
            box, box,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                // Without this the box is clamped inside the display and a tap
                // near an edge would be marked somewhere it did not happen.
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            alpha = 0f
        }
        return try {
            v.hideFromAccessibility()
            wm.addView(v, p)
            view = v
            params = p
            true
        } catch (t: Throwable) {
            android.util.Log.e("Body", "tap marker addView failed", t)
            false
        }
    }

    fun hide() {
        val v = view ?: return
        runCatching { wm.removeView(v) }
        view = null
        params = null
    }

    /**
     * Mark a gesture that has already been dispatched. Screen pixels, from
     * whichever path resolved them — normalized coordinates, an eid's live
     * bounds, or a top-bar button.
     *
     * Call it *after* the gesture, never before: this window sits on the exact
     * point being touched, and see the class KDoc for what that costs.
     */
    fun mark(x: Int, y: Int, kind: Kind) {
        lastMark = intArrayOf(x, y)
        lastMarkAt = SystemClock.uptimeMillis()
        ui.post {
            val v = view ?: return@post
            val p = params ?: return@post
            p.x = x - box / 2
            p.y = y - box / 2
            runCatching { wm.updateViewLayout(v, p) }
            // The label is the 0-1000 coordinate rather than the pixel one: that
            // is the only space the brain writes in, and the one that survives a
            // downscaled screenshot.
            val d = net.kuafuai.andee.screen.ScreenController.displaySize(context)
            val nx = x * NORM_MAX / max(1, d.x)
            val ny = y * NORM_MAX / max(1, d.y)
            v.mark(kind.label, "$nx,$ny")
            showing = true
            applyWindow()
        }
    }

    /**
     * Step out of the way of an injected gesture. Not cosmetic: a mark left
     * over from the previous tap is an untrusted overlay sitting on the next
     * one, and the platform drops the touch without reporting anything.
     */
    fun setVisibleForGesture(visible: Boolean) {
        ui.post { gestureHidden = !visible; applyWindow() }
    }

    /**
     * Reveal the marker RIGHT NOW, from any thread — used before an
     * effect-snapshot capture inside the tap itself.
     *
     * [CommandDispatcher.passthroughForGesture] hides this window for the
     * whole duration of a tap, and the effect snapshot is taken *inside*
     * that window — so without this, every after_shot/miss_shot ships with
     * the ring invisible, and the "here is where your tap landed" evidence
     * the whole mechanism exists for is missing from its own picture
     * (measured: adb capture after the tap showed the ring, after_shot did
     * not). The dispatcher's own restore afterwards is idempotent, so no
     * race to worry about.
     */
    fun revealNow() {
        ui.post {
            gestureHidden = false
            applyWindow()
        }
    }

    /** Main thread only. */
    private fun onIdle() {
        showing = false
        applyWindow()
    }

    /** Main thread only. Opacity is billed per window, not per pixel. */
    private fun applyWindow() {
        val v = view ?: return
        val p = params ?: return
        val want = if (showing && !gestureHidden) 1f else 0f
        if (p.alpha == want) return
        p.alpha = want
        runCatching { wm.updateViewLayout(v, p) }
    }

    /**
     * Ring, reticle, caption — drawn against whatever the app underneath
     * happens to be, which is the whole design problem. Every bright stroke is
     * laid over a dark one of its own so the mark survives a white background,
     * a cyan background, and the 1280px downscale on the way to the model.
     *
     * The shapes are borrowed from a camera's focus reticle rather than from a
     * debug overlay: a soft pool of light to seat it on the content, an open
     * ring, four bracket arcs standing off it, and the coordinate set in a
     * monospace face on a glass chip. The brackets sit on the diagonals so the
     * pixel row and column through the centre dot stay clear — a mark that
     * covers what it is pointing at is no use to either audience.
     */
    private class MarkView(
        context: Context,
        private val onIdle: () -> Unit,
    ) : View(context) {

        private val d = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            textSize = 9.5f * d
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val coord = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11.5f * d
            letterSpacing = 0.04f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        private val chip = RectF()
        private val arc = RectF()
        private val ease = DecelerateInterpolator(1.6f)
        private val settle = OvershootInterpolator(2.2f)

        private var labelText = ""
        private var coordText = ""
        private var startedAt = 0L
        private var running = false

        fun mark(kind: String, coords: String) {
            labelText = kind.uppercase()
            coordText = coords
            startedAt = SystemClock.uptimeMillis()
            running = true
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            if (w == 0 || h == 0) return
            // A pool of light under the mark, so it reads as sitting on the
            // content rather than pasted over it. Rebuilt only on resize —
            // allocating a shader per frame would show up as jank.
            glow.shader = RadialGradient(
                w / 2f, h / 2f, GLOW_DP * d,
                intArrayOf(0x33000000, 0x1E000000, 0x00000000),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            if (!running) return
            val age = SystemClock.uptimeMillis() - startedAt
            if (age > STRIKE_MS + HOLD_MS + FADE_MS) {
                running = false
                // Posted, not called: the listener touches window layout, which
                // has no business happening inside a draw pass.
                post(onIdle)
                return
            }

            val cx = width / 2f
            val cy = height / 2f
            // Fade the whole mark out at the end rather than removing it, so it
            // never blinks off in the middle of a capture.
            val life = if (age > STRIKE_MS + HOLD_MS) {
                1f - (age - STRIKE_MS - HOLD_MS) / FADE_MS.toFloat()
            } else {
                1f
            }.coerceIn(0f, 1f)

            // The strike: two rings leaving at a stagger, decelerating on the
            // way out the way a real impact does. Only the user ever sees this
            // part — it is long over before any screenshot.
            drawPulse(canvas, cx, cy, age, 0L)
            drawPulse(canvas, cx, cy, age, PULSE_STAGGER_MS)

            // Everything below is the resting mark, which is what ends up in
            // the screenshot. It settles in with a small overshoot instead of
            // appearing at full size — the difference between a thing that
            // arrived and a thing that was always there.
            val grow = if (age < SETTLE_MS) {
                0.4f + 0.6f * settle.getInterpolation(age / SETTLE_MS.toFloat())
            } else {
                1f
            }

            glow.alpha = (255 * life).toInt()
            canvas.drawCircle(cx, cy, GLOW_DP * d, glow)

            val r = RING_DP * d * grow
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND

            paint.strokeWidth = 4.5f * d
            paint.color = Color.BLACK
            paint.alpha = (85 * life).toInt()
            canvas.drawCircle(cx, cy, r, paint)

            paint.strokeWidth = 2.5f * d
            paint.color = ACCENT
            paint.alpha = (255 * life).toInt()
            canvas.drawCircle(cx, cy, r, paint)

            // Four bracket arcs standing off the ring on the diagonals — a
            // camera's focus frame rather than a crosshair. On the diagonals
            // on purpose: they leave the pixel row and column through the
            // centre dot unobstructed, which is the part anyone is trying to
            // look at.
            val br = BRACKET_DP * d * grow
            arc.set(cx - br, cy - br, cx + br, cy + br)
            for (pass in 0..1) {
                paint.strokeWidth = if (pass == 0) 3.5f * d else 2f * d
                paint.color = if (pass == 0) Color.BLACK else ACCENT
                paint.alpha = ((if (pass == 0) 80 else 225) * life).toInt()
                for (k in 0..3) {
                    canvas.drawArc(arc, 45f + 90f * k - BRACKET_SWEEP / 2f, BRACKET_SWEEP, false, paint)
                }
            }

            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            paint.alpha = (110 * life).toInt()
            canvas.drawCircle(cx, cy, 4.5f * d, paint)
            paint.color = Color.WHITE
            paint.alpha = (255 * life).toInt()
            canvas.drawCircle(cx, cy, 3f * d, paint)

            drawCaption(canvas, cx, cy, life)

            // Idle frames cost nothing while the mark is just sitting there, but
            // it still has to expire, so keep asking until `life` runs out.
            postInvalidateOnAnimation()
        }

        private fun drawPulse(canvas: Canvas, cx: Float, cy: Float, age: Long, delay: Long) {
            val t = (age - delay) / PULSE_MS.toFloat()
            if (t < 0f || t >= 1f) return
            val e = ease.getInterpolation(t)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            // Thinning as it grows, so the ring dissolves rather than stopping.
            paint.strokeWidth = (3.2f - 2.4f * e) * d
            paint.color = ACCENT
            paint.alpha = ((1f - e) * (1f - e) * 190).toInt()
            canvas.drawCircle(cx, cy, (RING_DP + (PULSE_MAX_DP - RING_DP) * e) * d, paint)
        }

        /**
         * Kind and coordinate on a glass chip, hung off the ring by a hairline.
         * Two typefaces on purpose: the kind is a label, the numbers are data,
         * and a monospace figure set is the thing that keeps "100,100" from
         * being read as "1OO,1OO" after the screenshot is downscaled.
         */
        private fun drawCaption(canvas: Canvas, cx: Float, cy: Float, life: Float) {
            if (coordText.isEmpty()) return
            val lw = label.measureText(labelText)
            val nw = coord.measureText(coordText)
            val gap = 6f * d
            val pad = 10f * d
            val w = lw + gap + nw
            val h = 22f * d
            val top = cy + CHIP_TOP_DP * d

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * d
            paint.color = ACCENT
            paint.alpha = (110 * life).toInt()
            canvas.drawLine(cx, cy + (RING_DP + 4f) * d, cx, top, paint)

            chip.set(cx - w / 2 - pad, top, cx + w / 2 + pad, top + h)
            val rad = h / 2f

            // Lift: one darker copy nudged down behind the chip. Cheaper than a
            // shadow layer, which would force this View into software rendering.
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            paint.alpha = (70 * life).toInt()
            chip.offset(0f, 1.5f * d)
            canvas.drawRoundRect(chip, rad, rad, paint)
            chip.offset(0f, -1.5f * d)

            paint.color = CHIP_BG
            paint.alpha = (233 * life).toInt()
            canvas.drawRoundRect(chip, rad, rad, paint)

            // The hairline is what makes it read as glass rather than as a
            // black box — same trick as the history bubbles.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f * d
            paint.color = CHIP_EDGE
            paint.alpha = (90 * life).toInt()
            canvas.drawRoundRect(chip, rad, rad, paint)

            val base = top + h / 2f - (coord.descent() + coord.ascent()) / 2f
            label.alpha = (215 * life).toInt()
            canvas.drawText(labelText, cx - w / 2, base, label)
            coord.alpha = (255 * life).toInt()
            canvas.drawText(coordText, cx - w / 2 + lw + gap, base, coord)
        }

        companion object {
            private val ACCENT = Color.parseColor("#2DE2FF")
            private val CHIP_BG = Color.parseColor("#0B1220")
            private val CHIP_EDGE = Color.WHITE

            private const val RING_DP = 14f
            private const val GLOW_DP = 30f
            private const val BRACKET_DP = 21f
            private const val BRACKET_SWEEP = 34f
            private const val PULSE_MAX_DP = 48f
            private const val CHIP_TOP_DP = 33f

            private const val PULSE_MS = 480L
            private const val PULSE_STAGGER_MS = 110L
            private const val STRIKE_MS = PULSE_MS + PULSE_STAGGER_MS
            private const val SETTLE_MS = 260L
            private const val FADE_MS = 400L

            /**
             * How long the mark stays put. Long enough that a brain which taps,
             * waits for the app to settle and then screenshots still finds it;
             * short enough that it is gone before the user wonders what it is.
             */
            private const val HOLD_MS = 6_000L
        }
    }

    companion object {
        /**
         * Side of the window, in dp. Big enough for the outgoing pulse and the
         * widest caption ("HOLD 1000,1000"), small enough that a stale mark
         * cannot obscure much — see the class KDoc.
         */
        private const val BOX_DP = 144

        private const val NORM_MAX = 1000
    }
}
