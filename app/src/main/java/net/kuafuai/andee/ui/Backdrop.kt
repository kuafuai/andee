package net.kuafuai.andee.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The fullscreen card's backdrop: one fixed image, blurred and darkened.
 *
 * The card used to be a window onto whatever was behind it. That was the whole
 * point of [Glass.frost] — `FLAG_BLUR_BEHIND` defocuses the home screen or the
 * app underneath and the card's 60%-black fill lays the glass on top of it — and
 * it is the right answer for a modal that appears over a document. It is the
 * wrong answer for this card: it is fullscreen, it stays up for minutes at a
 * time, and what shows through is the user's home screen with their own icons
 * dissolved behind the text. The card read as *transparent* rather than as
 * *ours*.
 *
 * So the card brings its own background. Three consequences worth stating,
 * because each one is a decision rather than a side effect:
 *
 *  * **The compositor blur is off while this is showing** (`FloatingWindowUi.
 *    applyWindow`). There is no longer anything behind the card to defrost — the
 *    backdrop covers the whole window — so asking for it would be a full-screen
 *    defocus of the GPU, several times a second, for a result nobody can see.
 *    It is also what makes the card's look independent of
 *    `isCrossWindowBlurEnabled`, which is false on battery saver, on devices
 *    that cannot afford it, and whenever the developer option is off.
 *  * **The blur here is ours**, and it is a plain box blur run three times. Not
 *    `RenderEffect` (API 31+, and it blurs a View's *own* content — the same
 *    tool that cannot help the pills, see `CLAUDE.md`), and not a downscale-
 *    upscale, which is bilinear and leaves the image looking smeared rather than
 *    frosted. Three box passes approximate a Gaussian closely enough that the
 *    result reads as glass, and on a [TARGET_W]-wide bitmap the whole thing is
 *    a few milliseconds.
 *  * **It runs off the main thread, at build time.** The window is built folded
 *    and the backdrop is `GONE` while that runs, so nothing ever blocks the
 *    main thread for it.
 *
 *    It used to also say "finished long before the first unfold", on the
 *    grounds that the service starts folded so nothing is shown until the user
 *    long-presses. The service no longer starts folded — it unfolds at birth
 *    (`ScreenBodyService.onServiceConnected`) — so on a cold start the first
 *    unfold can *beat* this. The card is already built for that: until [ready]
 *    flips, `backdropUp()` is false, `paintCard` gives the card its own fill,
 *    and [warm]'s callback re-runs `applyWindow` so the real backdrop takes
 *    over the moment it exists. The cost is a frame or two of the fallback
 *    look instead of a card with a transparent hole in it — the same trade
 *    `backdropUp` was written for.
 *
 * The image itself lives in `res/drawable-nodpi/backdrop.jpg`. Any photo will
 * do: the scrim is derived from the image's own brightness rather than being a
 * constant, so swapping the file is the whole procedure. See [scrim].
 */
object Backdrop {

    private const val TAG = "Body"

    /**
     * The blurred copy's width, in pixels. Fixed rather than screen-relative on
     * purpose: it is the *blur* that is being sized here, and every screen this
     * runs on stretches it further than this anyway (`CENTER_CROP` on a phone
     * scales it up ~3x), so a larger source would only cost work to produce
     * detail the frost then removes.
     */
    private const val TARGET_W = 512

    /** Blur radius in [TARGET_W]-space. ~8 dp once the view stretches it. */
    private const val BLUR_RADIUS = 8

    /**
     * Three passes. One box pass is a visibly square blur — its kernel is a
     * rectangle, and on a photo with any structure in it the directions read as
     * a cross. Three is where the difference stops being visible.
     */
    private const val BLUR_PASSES = 3

    /**
     * What the backdrop's mean luminance should end up at, once the scrim is on.
     *
     * Started at 0.13 — what `CARD_FROSTED` (60% black over a blurred desktop)
     * composites to on average — and was raised to 0.22 after use: on a card
     * that stays up for minutes, 0.13 read as a black wall with a photo buried
     * in it. 0.22 is about the ceiling: past it the pale ball and the
     * `SECONDARY` text start to lose contrast against the image.
     */
    private const val TARGET_LUMA = 0.22f

    /**
     * Bounds on the derived scrim, so a very dark or very bright photo cannot
     * walk the card's base colour out of the range the palette assumes. The floor
     * keeps text legible over a near-black image; the ceiling keeps the image
     * from disappearing under a near-white one.
     */
    private const val SCRIM_MIN = 0.38f
    private const val SCRIM_MAX = 0.75f

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "backdrop").apply { isDaemon = true }
    }

    @Volatile private var ready = false

    /** The blurred bitmap, whole. `CENTER_CROP` does the cropping, not this. */
    @Volatile private var bitmap: Bitmap? = null

    /** ARGB fill that goes over [bitmap] and under everything else. */
    @Volatile private var scrim = 0

    /** Whether [bitmap] and [scrim] are worth reading yet. */
    fun ready(): Boolean = ready

    fun bitmap(): Bitmap? = bitmap

    /**
     * The fill the card's own background should be while the backdrop is up.
     * `0` before [warm] finishes — callers should read [ready] first.
     */
    fun scrim(): Int = scrim

    /**
     * Decode, blur and measure the backdrop, then hand the result to [onReady] on
     * the main thread. Safe to call more than once; only the first does work.
     */
    fun warm(context: Context, onReady: (Bitmap) -> Unit) {
        if (ready) {
            ui.post { onReady(bitmap!!) }
            return
        }
        val app = context.applicationContext
        worker.execute {
            val out = runCatching { build(app) }
                .onFailure { Log.e(TAG, "backdrop failed; card keeps its own fill", it) }
                .getOrNull() ?: return@execute
            bitmap = out.first
            scrim = out.second
            ready = true
            ui.post { onReady(out.first) }
        }
    }

    /** Decode → shrink → blur → measure. Worker thread only. */
    private fun build(context: Context): Pair<Bitmap, Int> {
        val src = decode(context)
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        src.recycle()

        boxBlur(px, w, h)

        val luma = meanLuma(px)
        val alpha = (1f - TARGET_LUMA / max(luma, 0.02f)).coerceIn(SCRIM_MIN, SCRIM_MAX)
        Log.i(
            TAG,
            "backdrop %dx%d  mean luma %.3f → scrim %.0f%% black".format(
                w, h, luma, alpha * 100,
            ),
        )
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888) to
            Color.argb((alpha * 255).roundToInt(), 0, 0, 0)
    }

    /**
     * The image, at [TARGET_W] wide.
     *
     * Two steps rather than one: `inSampleSize` is only cheap *and* predictable
     * in powers of two, so it takes the width down to somewhere at or above the
     * target and a scaled copy finishes the job. A single non-power-of-two
     * `inSampleSize` is honoured on API 26+ but not exact, and the arithmetic
     * that decides it is more code than the scaled copy it saves.
     */
    private fun decode(context: Context): Bitmap {
        val id = net.kuafuai.andee.R.drawable.backdrop
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, id, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_W) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = requireNotNull(BitmapFactory.decodeResource(context.resources, id, opts)) {
            "backdrop.jpg did not decode"
        }
        if (raw.width == TARGET_W) return raw
        val h = max(1, (raw.height * TARGET_W.toFloat() / raw.width).roundToInt())
        val scaled = Bitmap.createScaledBitmap(raw, TARGET_W, h, true)
        if (scaled != raw) raw.recycle()
        return scaled
    }

    /**
     * Three separable box passes — horizontal then vertical, three times.
     *
     * A running sum per row and per column is what makes this linear in the
     * pixel count rather than quadratic in the radius: the window slides one
     * pixel at a time and each step adds one sample and drops one. Edges sample
     * the border pixel rather than wrapping or darkening, because a dark rim
     * around the image would read as a vignette the user did not ask for.
     */
    private fun boxBlur(px: IntArray, w: Int, h: Int) {
        val tmp = IntArray(px.size)
        val r = BLUR_RADIUS
        val n = r * 2 + 1
        repeat(BLUR_PASSES) {
            // Horizontal: px → tmp
            for (y in 0 until h) {
                val row = y * w
                var sr = 0; var sg = 0; var sb = 0
                for (i in -r..r) {
                    val c = px[row + i.coerceIn(0, w - 1)]
                    sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                }
                for (x in 0 until w) {
                    tmp[row + x] = -0x1000000 or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                    val add = px[row + (x + r + 1).coerceIn(0, w - 1)]
                    val sub = px[row + (x - r).coerceIn(0, w - 1)]
                    sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                    sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                    sb += (add and 0xFF) - (sub and 0xFF)
                }
            }
            // Vertical: tmp → px
            for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0
                for (i in -r..r) {
                    val c = tmp[i.coerceIn(0, h - 1) * w + x]
                    sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                }
                for (y in 0 until h) {
                    px[y * w + x] = -0x1000000 or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                    val add = tmp[(y + r + 1).coerceIn(0, h - 1) * w + x]
                    val sub = tmp[(y - r).coerceIn(0, h - 1) * w + x]
                    sr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                    sg += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                    sb += (add and 0xFF) - (sub and 0xFF)
                }
            }
        }
    }

    /** Rec. 709 relative luminance, averaged. Worker thread only. */
    private fun meanLuma(px: IntArray): Float {
        var sum = 0.0
        for (c in px) {
            sum += (0.2126 * ((c shr 16) and 0xFF) +
                0.7152 * ((c shr 8) and 0xFF) +
                0.0722 * (c and 0xFF)) / 255.0
        }
        return (sum / px.size).toFloat()
    }
}
