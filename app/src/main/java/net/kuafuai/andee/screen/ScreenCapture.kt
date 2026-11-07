package net.kuafuai.andee.screen
import net.kuafuai.andee.compat.WindowMetricsCompat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * The screen's pixels, without accessibility.
 *
 * **Why this exists.** Everything else about reading a screen went through the
 * accessibility service: `rootInActiveWindow` for the element list,
 * `takeScreenshot` for the picture. So when a Chinese ROM revoked Andee's
 * accessibility — the failure this whole split is about — the assistant could
 * still talk, still remember, still be reached, and was completely **blind**.
 * "What is this screen?" is the one question a device assistant answers with no
 * ability to touch anything, and it was the one it could not answer.
 *
 * `MediaProjection` is the platform's other way in: one consent dialog, granted
 * by the user, and after that the app can capture the display with no
 * accessibility service anywhere in the picture.
 *
 * ## What this is not
 *
 * **It is not a replacement, and it must not be dressed up as one.** The
 * `screen.screenshot` the brain normally gets is deliberately *rich*: an aim
 * grid drawn for a finger it is about to move, the element list piggybacked on
 * it, and a `shot_id` tying the picture to whatever it taps next. All three of
 * those serve tapping, and there is no tapping without accessibility —
 * `dispatchGesture` has no non-root substitute. So the honest shape of a
 * projection capture is "here are the pixels, at this size": enough to answer
 * a question about what is on screen, and nothing to aim with. The caller says
 * which source it used ([SOURCE]) so the brain is never told it is holding a
 * picture it can act on.
 *
 * ## The consent, and why it is the caller's problem
 *
 * `MediaProjectionManager.createScreenCaptureIntent()` must be launched **from
 * an Activity** and its result handed back here — see
 * `ui/SelfCheckActivity.requestScreenCapture`. There is no way to ask for this
 * from a service, and no way to ask silently: since Android 14 the grant is
 * per-session, so a device that reboots needs the user again. That is a real
 * cost of this fallback and the reason it is a fallback.
 */
class ScreenCapture(private val context: Context) {

    private val lock = Any()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var width = 0
    private var height = 0

    /** Whether a capture will work right now. Free to call. */
    fun isAvailable(): Boolean = synchronized(lock) { reader != null && projection != null }

    /**
     * Take the consent result, and build a capture from it.
     *
     * Returns false rather than throwing: the two ways this fails — the user
     * refused, or the platform refused to hand over a projection for a result
     * code we liked — are both "no capture today", and neither is a fault the
     * caller can act on differently.
     */
    fun start(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.i(TAG, "screen capture not granted (resultCode=$resultCode)")
            return false
        }
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as? MediaProjectionManager ?: return false
        val fresh = runCatching { mgr.getMediaProjection(resultCode, data) }
            .onFailure { Log.w(TAG, "getMediaProjection refused", it) }
            .getOrNull() ?: return false
        // Registered before anything is created from it. Android 14 requires a
        // callback (an app that stops the projection without one is killed),
        // and without a callback here a projection the *user* stops — from the
        // privacy chip — would leave us holding a dead one and reporting
        // "available" forever.
        fresh.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "projection stopped by the system or the user")
                release()
            }
        }, Handler(Looper.getMainLooper()))

        val bounds = WindowMetricsCompat.bounds(context.getSystemService(WindowManager::class.java))
        val w = bounds.width()
        val h = bounds.height()
        val dpi = context.resources.displayMetrics.densityDpi
        synchronized(lock) {
            releaseLocked()
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, IMAGE_BUFFER)
            val d = runCatching {
                fresh.createVirtualDisplay(
                    "andee-capture", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    r.surface, null, Handler(Looper.getMainLooper()),
                )
            }.onFailure { Log.w(TAG, "createVirtualDisplay failed", it) }.getOrNull()
            if (d == null) {
                runCatching { r.close() }
                runCatching { fresh.stop() }
                return false
            }
            projection = fresh
            display = d
            reader = r
            width = w
            height = h
        }
        return true
    }

    /**
     * The display as it is now, or null if it could not be read.
     *
     * **Blocking — call it from a worker, never the main thread.** The first
     * frame after a projection starts is not there yet (the display needs a
     * beat to produce one), so this waits up to [timeoutMs] for an image rather
     * than returning an empty screen that the brain would describe as blank.
     */
    fun grab(timeoutMs: Long = GRAB_TIMEOUT_MS): Bitmap? {
        val r: ImageReader
        val w: Int
        val h: Int
        synchronized(lock) {
            r = reader ?: return null
            w = width
            h = height
        }
        val image = awaitImage(r, timeoutMs) ?: run {
            Log.w(TAG, "no frame within ${timeoutMs}ms")
            return null
        }
        return try {
            image.use { toBitmap(it, w, h) }
        } catch (t: Throwable) {
            Log.w(TAG, "frame could not be read", t)
            null
        }
    }

    private fun awaitImage(r: ImageReader, timeoutMs: Long): Image? {
        r.acquireLatestImage()?.let { return it }
        // Poll rather than listen: ImageReader.OnImageAvailableListener fires on
        // a handler we do not own, and a latch here would have to be armed
        // before the frame arrived to not lose it. Polling at this interval
        // costs nothing next to the frame the display is producing.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            r.acquireLatestImage()?.let { return it }
        }
        return null
    }

    /**
     * One `Image` as an ARGB bitmap.
     *
     * The row stride is not `width * 4`: the producer pads rows, so the buffer
     * is copied into a bitmap that is *at least* as wide as the padding
     * demands and then cropped. Copying straight into `w x h` would shear the
     * image diagonally, which is the kind of wrong that still looks like a
     * screenshot until someone reads text off it.
     */
    private fun toBitmap(image: Image, w: Int, h: Int): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * w
        val padded = Bitmap.createBitmap(
            w + rowPadding / pixelStride,
            h,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        if (padded.width == w) return padded
        val exact = Bitmap.createBitmap(padded, 0, 0, w, h)
        padded.recycle()
        return exact
    }

    fun release() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        runCatching { reader?.close() }
        runCatching { display?.release() }
        runCatching { projection?.stop() }
        reader = null
        display = null
        projection = null
    }

    companion object {
        private const val TAG = "ScreenCapture"

        /**
         * Longest side of the PNG the brain is handed, whatever the source.
         *
         * Shared with `ScreenController`, which had the number to itself until
         * the projection fallback arrived — two capture paths with two different
         * picture sizes for the same tool is a difference the brain would have
         * to learn for no reason.
         */
        const val SHOT_MAX_SIDE = 1280

        /** How many frames the reader may hold before the producer stalls. */
        private const val IMAGE_BUFFER = 2

        /** A frame is not instant after `start`; this is how long we give it. */
        private const val GRAB_TIMEOUT_MS = 2_000L
        private const val POLL_MS = 25L

        /**
         * Which source a capture came from, in the tool result.
         *
         * Spelled out rather than implied, because the two are not
         * interchangeable: an `accessibility` shot is one the brain may aim
         * with, a `media_projection` one is only to look at.
         */
        const val SOURCE = "source"
        const val SOURCE_ACCESSIBILITY = "accessibility"
        const val SOURCE_MEDIA_PROJECTION = "media_projection"

        /** What the brain is told when a projection shot is all it gets. */
        const val PROJECTION_NOTE =
            "Captured with MediaProjection: this is the raw screen, with no aim " +
                "grid and no element list — both of those need Andee's accessibility " +
                "service, and so does tapping. You can describe what you see; you " +
                "cannot act on it until accessibility is back on."
    }
}
