package net.kuafuai.andee.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import net.kuafuai.andee.pet.PetCharacters
import net.kuafuai.andee.pet.PetMind
import net.kuafuai.andee.pet.PetPose

/**
 * The desk pet on the phone: the folded ball's body when the user picked 桌宠 (`pet = on`), drawn inside the ball's
 * own window in place of the GL sphere ([FloatingWindowUi] hides the sphere while this shows). No window of its
 * own — every overlay counts toward the 0.80 opacity ceiling above which the platform drops injected taps.
 *
 * Two shapes. Standing ([docked] false): the pose for Andee's state ([setStatePose], from the same rule the Mac
 * uses, `PetMind.statePose`), or — while that is null — her own idle life: the breathing loop, an act every 7-14 s,
 * and after three quiet minutes a nap she wakes from by herself. Docked into the right edge: the peek drawing, its
 * right side (the "wall" she holds) on the centre line of this view, which [FloatingWindowUi.applyDockedGeometry]
 * puts on the screen's edge — so she holds on round the edge of the screen.
 *
 * Pictures come from `assets/pet` (`tools/pet_phone_assets.py`, scaled down from the Mac's). Main thread only.
 */
class PhonePetView(context: Context) : View(context) {

    var look: String = PetCharacters.PRESETS.first().id
        set(v) {
            val id = PetCharacters.canonical(v)
            if (id == field) return
            field = id
            cache.clear(); frames = null
            asleep = false; act = null
            touchedAt = SystemClock.uptimeMillis()
            invalidate()
        }

    var docked = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /**
     * The sphere this stands in for. Its scale and drop are copied every frame while she shows, so every place that
     * sizes or moves the ball — the card's scale, the dock's shrink, the drop under the control cluster — moves her
     * too without knowing she exists. Not while docked: the peek has its own size.
     */
    var follow: View? = null

    /**
     * In the unfolded card the area is the whole top of the card, far taller than the sphere drawn in it, so she
     * stands where the sphere is — centred, a little taller than its diameter — rather than on the area's floor.
     */
    var inCard = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val mirror = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val f = follow
            if (f != null && isShown) {
                val sc = if (docked) 1f else f.scaleX
                if (scaleX != sc) { scaleX = sc; scaleY = sc }
                val ty = if (docked) 0f else f.translationY
                if (translationY != ty) translationY = ty
            }
            if (isAttachedToWindow) android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** [hit] for a point in the parent's coordinates, through this view's own scale and translation. */
    fun hitFromParent(px: Float, py: Float): Boolean {
        val inv = android.graphics.Matrix()
        if (!matrix.invert(inv)) return false
        val pts = floatArrayOf(px - left, py - top)
        inv.mapPoints(pts)
        return hit(pts[0], pts[1])
    }

    private var statePose: PetPose? = null
    private var act: PetPose? = null
    private var asleep = false
    private var touchedAt = SystemClock.uptimeMillis()
    private var napEnds = 0L
    private var lastAct: PetPose? = null

    private val cache = HashMap<PetPose, Bitmap?>()
    private var frames: List<Bitmap>? = null
    private var frameIndex = 0

    private var leaving: Bitmap? = null
    private var fade = 1f
    private var fadeAnim: ValueAnimator? = null
    private var shownPose: PetPose? = null
    private var hop = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    /** Andee's state as a pose, or null when idle (see `PetMind.statePose`). */
    fun setStatePose(p: PetPose?) {
        if (p == statePose) return
        statePose = p
        if (p != null) {
            // Busy: whatever she was doing on her own stops, and a nap ends.
            asleep = false; act = null
            touchedAt = SystemClock.uptimeMillis()
        }
        invalidate()
    }

    /** A tap that became a "talk": she hops, and wakes if she was napping. */
    fun poke() {
        asleep = false; act = null
        touchedAt = SystemClock.uptimeMillis()
        ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 360
            interpolator = DecelerateInterpolator()
            addUpdateListener { hop = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun pose(): PetPose = when {
        docked -> PetPose.PEEK
        statePose != null -> statePose!!
        asleep -> PetPose.SLEEP
        act != null -> act!!
        else -> PetPose.IDLE
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        val p = pose()
        val bmp = still(p) ?: return
        if (p != shownPose) {
            val old = shownPose?.let { still(it) }
            shownPose = p
            if (old != null && old !== bmp && !docked) crossfade(old)
        }
        val leave = leaving
        if (leave != null && fade < 1f) draw(canvas, leave, 1f - fade)
        val idleFrame = if (p == PetPose.IDLE && leave == null) idleFrames().getOrNull(frameIndex) else null
        draw(canvas, idleFrame ?: bmp, if (leave != null) fade else 1f)
    }

    private fun draw(canvas: Canvas, bmp: Bitmap, alpha: Float) {
        rectFor(bmp, dst)
        paint.alpha = (alpha * 255).toInt()
        canvas.drawBitmap(bmp, null, dst, paint)
    }

    /** Where [bmp] is drawn, in this view's coordinates. */
    private fun rectFor(bmp: Bitmap, out: RectF) {
        val aspect = bmp.width.toFloat() / bmp.height
        if (docked) {
            // The peek's right edge (the wall she holds) on the centre line, which is the screen's edge.
            val h = peekHeight()
            val right = width / 2f
            val cy = height / 2f
            out.set(right - h * aspect, cy - h / 2f, right, cy + h / 2f)
        } else if (inCard) {
            // The sphere's diameter is 0.64 of the shorter side (see FloatingWindowUi.isOnBall).
            // As tall as a person a little taller than the sphere, but never wider than it: a sitting kitten is
            // wider than tall, and sized by height alone it reached the toolbar and the speaker key.
            val side = minOf(width, height)
            val h = minOf(side * (if (look == "andee") 0.66f else 0.80f), side * 0.50f / aspect)
            val cx = width / 2f
            val cy = height / 2f - hop * h * 0.10f
            out.set(cx - h * aspect / 2f, cy - h / 2f, cx + h * aspect / 2f, cy + h / 2f)
        } else {
            val h = height * standShare()
            val cx = width / 2f
            val bottom = height - height * 0.04f - hop * height * 0.10f
            out.set(cx - h * aspect / 2f, bottom - h, cx + h * aspect / 2f, bottom)
        }
    }

    /** Standing height as a share of the box: the round Andee is a ball's size, a person the box's height. */
    private fun standShare(): Float = if (look == "andee") 0.68f else 0.96f

    /** Docked height in px. */
    fun peekHeight(): Float = dp(if (look == "andee") PEEK_BALL_DP else PEEK_PERSON_DP)

    /** Docked width in px: the part of her that leans out past the edge. */
    fun peekWidth(): Float {
        val b = still(PetPose.PEEK) ?: return peekHeight() * 0.5f
        return peekHeight() * b.width / b.height
    }

    private fun crossfade(from: Bitmap) {
        leaving = from
        fadeAnim?.cancel()
        fade = 0f
        fadeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CROSSFADE_MS
            addUpdateListener { fade = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { leaving = null; fade = 1f; invalidate() }
            })
            start()
        }
    }

    /** Whether ([x], [y]) — this view's coordinates — is on a drawn pixel of her, not the air beside her. */
    fun hit(x: Float, y: Float): Boolean {
        val bmp = still(pose()) ?: return false
        rectFor(bmp, dst)
        val slop = dp(6)
        if (x < dst.left - slop || x > dst.right + slop || y < dst.top - slop || y > dst.bottom + slop) return false
        val bx = ((x - dst.left) / dst.width() * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val by = ((y - dst.top) / dst.height() * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        // A few pixels around too: a finger is wider than the antialiased edge of a hair.
        val r = (bmp.width / 30).coerceAtLeast(2)
        for (dy in -r..r step r) for (dx in -r..r step r) {
            val px = (bx + dx).coerceIn(0, bmp.width - 1)
            val py = (by + dy).coerceIn(0, bmp.height - 1)
            if ((bmp.getPixel(px, py) ushr 24) > 60) return true
        }
        return false
    }

    /** The drawn body's bounds on screen, for whoever anchors to her (the signboard). */
    fun bodyRect(out: RectF): Boolean {
        val bmp = still(pose()) ?: return false
        rectFor(bmp, out)
        return true
    }

    // ---- her own life ----

    private val tick = object : Runnable {
        override fun run() {
            live()
            postDelayed(this, LIFE_TICK_MS)
        }
    }

    private val breathe = object : Runnable {
        override fun run() {
            if (pose() == PetPose.IDLE && leaving == null) {
                val n = idleFrames().size
                if (n > 0) { frameIndex = (frameIndex + 1) % n; invalidate() }
            }
            postDelayed(this, FRAME_MS)
        }
    }

    private fun live() {
        if (!isShown) return
        val now = SystemClock.uptimeMillis()
        if (statePose != null || docked) { touchedAt = now; return }
        if (asleep) {
            if (napEnds == 0L) napEnds = now + PetMind.NAP_MIN_MS + (Math.random() * PetMind.NAP_SPAN_MS).toLong()
            if (now > napEnds) {
                // Up again by herself, with a yawn.
                asleep = false; napEnds = 0L; touchedAt = now
                showAct(PetPose.SLEEPY, 1_800)
            }
            return
        }
        napEnds = 0L
        if (act != null) return
        val quiet = now - touchedAt
        if (quiet > PetMind.SLEEP_AFTER_MS) {
            asleep = true; invalidate(); return
        }
        if (now - lastActAt > nextActAfter) {
            val choice = PetMind.IDLE_ACTS.filter { it != lastAct }.random()
            lastAct = choice
            showAct(choice, if (choice == PetPose.TEA || choice == PetPose.HOTPOT) 3_200 else 1_600)
            lastActAt = now
            nextActAfter = 7_000L + (Math.random() * 7_000).toLong()
        }
    }

    /** Separate from [touchedAt]: her own acts must not keep pushing the nap away. */
    private var lastActAt = SystemClock.uptimeMillis()
    private var nextActAfter = 9_000L

    private fun showAct(p: PetPose, ms: Long) {
        act = p; invalidate()
        postDelayed({ if (act == p) { act = null; invalidate() } }, ms)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(tick, LIFE_TICK_MS)
        post(breathe)
        android.view.Choreographer.getInstance().postFrameCallback(mirror)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick); removeCallbacks(breathe)
        android.view.Choreographer.getInstance().removeFrameCallback(mirror)
        fadeAnim?.cancel()
        super.onDetachedFromWindow()
    }

    // ---- pictures ----

    private fun still(p: PetPose): Bitmap? {
        cache[p]?.let { return it }
        if (cache.containsKey(p)) return p.fallback?.let { still(it) } ?: if (p != PetPose.IDLE) still(PetPose.IDLE) else null
        val b = decode("pet/$look-${p.file}.webp")
        cache[p] = b
        return b ?: (p.fallback?.let { still(it) } ?: if (p != PetPose.IDLE) still(PetPose.IDLE) else null)
    }

    private fun idleFrames(): List<Bitmap> {
        frames?.let { return it }
        val list = ArrayList<Bitmap>()
        for (i in 0 until 32) list.add(decode("pet/anim/$look-idle-$i.webp") ?: break)
        frames = list
        return list
    }

    private fun decode(path: String): Bitmap? = runCatching {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    }.onFailure { if (it !is java.io.FileNotFoundException) Log.w(TAG, "cannot read $path", it) }.getOrNull()

    private fun dp(v: Int): Float = v * resources.displayMetrics.density

    companion object {
        private const val TAG = "Pet"
        private const val CROSSFADE_MS = 120L
        private const val LIFE_TICK_MS = 1_000L
        /** The idle loop: the phone's 12 frames, about a second round. */
        private const val FRAME_MS = 85L
        /** Docked height: a person tall enough to read as one, the round Andee the ball's docked size. */
        const val PEEK_PERSON_DP = 76
        const val PEEK_BALL_DP = 54
    }
}
