package net.kuafuai.andee.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.ui.StatusPill
import net.kuafuai.andee.ui.ViewfinderView
import net.kuafuai.andee.ui.avoidSystemBars
import net.kuafuai.andee.ui.closePill
import net.kuafuai.andee.ui.dpI
import net.kuafuai.andee.ui.matchParent
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * The ball's LIVE eye — a persistent camera session the brain samples.
 * See the `look` tool description for the brain-facing contract:
 * device serves frames, brain decides cadence (once for "这是什么",
 * a self-chosen loop for "盯着水桶满了没").
 *
 * Frame source is ImageCapture (not PreviewView.getBitmap — that stayed
 * null on this OEM in COMPATIBLE mode; a hardware quirk not worth
 * fighting when takePicture just works).
 *
 * In and out are [net.kuafuai.andee.ui.StageActivity]'s, the same gesture the
 * pages get: the eye opened by cutting to the feed and left by cutting to
 * whatever was behind it, and three surfaces that behave three ways is
 * something the user notices even when he can't say what it was.
 */
class LookActivity : net.kuafuai.andee.ui.StageActivity() {

    companion object {
        @Volatile var instance: LookActivity? = null
        @Volatile var usingFront = false

        /**
         * When the *user* closed the camera, in `SystemClock.uptimeMillis`.
         * Read by the dispatcher so a look right afterwards can say what
         * actually happened instead of "camera not ready yet" — which is both
         * false and an invitation to reopen the lens in the user's face.
         */
        @Volatile var userClosedAt = 0L

        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        /** How long "看到了" / "没看清" stays up before the resting line returns. */
        private const val STATUS_HOLD_MS = 900L

        /**
         * How long after a bind the sensor is still auto-exposing. Shooting
         * immediately hands the brain a frame that is washed out or nearly
         * black — and it has no way to know that is a camera artefact rather
         * than the room, so it describes the dark. Measured ~1.5s on this
         * hardware; it costs nothing on later samples because the clock runs
         * from the bind, not from the request.
         */
        private const val EXPOSURE_SETTLE_MS = 1500L

        fun open(context: Context, front: Boolean) {
            usingFront = front
            val act = instance
            if (act == null || act.isFinishing) {
                context.startActivity(
                    Intent(context, LookActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else {
                mainHandler.post { if (!act.isFinishing) act.rebind(front) }
            }
        }

        fun close() {
            val act = instance ?: return
            act.closingByBrain = true
            // The brain's own close, so it goes the same way the ✕ pill does.
            // Called from a worker thread and from a path that must not care
            // whether the stage has been revealed — [leave] posts and guards.
            mainHandler.post { if (!act.isFinishing) act.leave() }
            instance = null
        }

        /**
         * Capture one frame through ImageCapture into a scratch file, then
         * downscale to maxSide and return base64 JPEG. Null if the camera
         * isn't bound yet (first moments after open/rebind). Blocking with
         * an internal cap (~3s) — the dispatcher already retries.
         *
         * Runs on the dispatcher thread, so every UI touch here goes through
         * [mainHandler]. The flash and the status line are not decoration:
         * they are the only way the user can tell *when* the camera actually
         * took something, on the most invasive screen this device has.
         */
        fun sampleFrame(maxSide: Int = 1280): String? {
            val act = instance ?: return null
            val capture = act.imageCapture ?: return null
            // Let the sensor finish auto-exposing before the first shot after
            // a bind. Only ever waits once per bind — a monitoring loop that
            // samples every 30s pays nothing.
            val settle = EXPOSURE_SETTLE_MS -
                (android.os.SystemClock.uptimeMillis() - act.boundAt)
            if (settle > 0) {
                act.flashStatus(AppLocale.str(act, R.string.dev_look_focusing), holdMs = settle)
                Thread.sleep(settle)
            }
            val f = File(act.cacheDir, "look_${System.currentTimeMillis()}.jpg")
            val done = java.util.concurrent.CountDownLatch(1)
            val ok = AtomicReference<Boolean>(false)
            mainHandler.post { act.onShutter() }
            capture.takePicture(
                androidx.camera.core.ImageCapture.OutputFileOptions.Builder(f).build(),
                ContextCompat.getMainExecutor(act),
                object : androidx.camera.core.ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: androidx.camera.core.ImageCapture.OutputFileResults) {
                        ok.set(true); done.countDown()
                        act.flashStatus(AppLocale.str(act, R.string.dev_look_seen))
                    }
                    override fun onError(exc: ImageCaptureException) {
                        done.countDown()
                        act.flashStatus(AppLocale.str(act, R.string.dev_look_unclear))
                    }
                },
            )
            if (!done.await(3, java.util.concurrent.TimeUnit.SECONDS) || !ok.get()) {
                f.delete(); return null
            }
            return runCatching {
                val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
                val w = bmp.width; val h = bmp.height
                if (w <= 0 || h <= 0) return null
                val scale = maxSide.toFloat() / maxOf(w, h)
                val out = if (scale < 1f) Bitmap.createScaledBitmap(
                    bmp, (w * scale).toInt(), (h * scale).toInt(), true
                ) else bmp
                val bos = ByteArrayOutputStream()
                out.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                f.delete()
                Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            }.getOrNull()
        }
    }

    @Volatile private var previewView: PreviewView? = null
    @Volatile private var imageCapture: ImageCapture? = null
    private var viewfinder: ViewfinderView? = null
    private var status: StatusPill? = null
    @Volatile private var closingByBrain = false

    /**
     * Strings in the user's language. Refreshed in [onCreate].
     *
     * UI here is a full-screen Activity, but the overlays elsewhere are
     * Service-owned, so the app reads its language through [AppLocale]
     * everywhere rather than relying on per-app locale. Views are still built
     * on `this`; only text comes from [lctx].
     */
    private var lctx: Context = this

    /**
     * When the current camera was bound, in `SystemClock.uptimeMillis`.
     * Reset on every [rebind] — switching to the front lens starts the
     * exposure ramp over, so the settle applies there too.
     */
    @Volatile private var boundAt = 0L

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        lctx = AppLocale.wrap(this)
        // Full-bleed preview; our own chrome insets itself (avoidSystemBars).
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 4244)
            return
        }
        buildUi()
        rebind(usingFront)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            buildUi()
            rebind(usingFront)
        } else leave()
    }

    /** Shutter pressed — pulse the brackets. Main thread. */
    private fun onShutter() {
        viewfinder?.flash()
    }

    /**
     * Say what just happened, then fall back to the resting line. Safe from
     * any thread; [sampleFrame]'s CameraX callbacks land on the main executor
     * but the call itself comes off the dispatcher.
     */
    private fun flashStatus(text: String, holdMs: Long = STATUS_HOLD_MS) {
        mainHandler.post {
            status?.setStatus(text)
            mainHandler.postDelayed({ status?.setStatus(restingStatus()) }, holdMs)
        }
    }

    private fun restingStatus(): String =
        if (usingFront) lctx.getString(R.string.dev_look_resting_front)
        else lctx.getString(R.string.dev_look_resting)

    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        val root = stage
        previewView = PreviewView(this).apply {
            // COMPATIBLE = TextureView: keeps the floating ball + edge glow
            // visible over the camera feed (SurfaceView would cover them).
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            layoutParams = matchParent()
        }
        viewfinder = ViewfinderView(this).apply { layoutParams = matchParent() }
        val pill = StatusPill(this).apply {
            setStatus(restingStatus())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            ).apply { bottomMargin = dpI(28) }
        }
        status = pill
        val close = closePill(this) {
            // Remember it was the user, so the next look tells the brain the
            // truth instead of "camera not ready yet".
            userClosedAt = android.os.SystemClock.uptimeMillis()
            leave()
        }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.TOP
            ).apply { topMargin = dpI(12); marginEnd = dpI(12) }
        }
        avoidSystemBars(close, topDp = 12)
        avoidSystemBars(pill, bottomDp = 28)
        root.addView(previewView)
        root.addView(viewfinder)
        root.addView(pill)
        root.addView(close)
    }

    @SuppressLint("SetTextI18n")
    fun rebind(front: Boolean) {
        usingFront = front
        status?.setStatus(restingStatus())
        // Drop the old capture while the new lens binds: it belongs to a
        // camera that is being unbound, and leaving it visible would let a
        // sample through against the *previous* bind's settled clock.
        imageCapture = null
        val pv = previewView ?: return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(pv.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.unbindAll()
            runCatching {
                provider.bindToLifecycle(
                    this,
                    if (front) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, capture
                )
            }.onSuccess {
                // boundAt first: sampleFrame gates on imageCapture, so publishing
                // the capture before the timestamp would let one frame through
                // with a stale (or zero) bind time and skip the settle entirely.
                boundAt = android.os.SystemClock.uptimeMillis()
                imageCapture = capture
                // The feed is drawable. Idempotent, so the rebind that happens
                // when the brain swaps lenses mid-session does not re-run it.
                revealStage()
            }.onFailure {
                // A refused bind is otherwise invisible: the stage stays at
                // 0.94 and zero alpha, which reads as a black screen the user
                // cannot leave. Show it anyway with the chrome on it, so ✕
                // 完成 is there and the status line says what happened.
                android.util.Log.w("Body", "camera bind failed: ${it.message}")
                status?.setStatus(lctx.getString(R.string.dev_camera_unavailable))
                revealStage()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        // Back key is the user too — same promise as the ✕ pill. Only the
        // brain's own close() is exempt, and it says so.
        if (!closingByBrain) userClosedAt = android.os.SystemClock.uptimeMillis()
        closingByBrain = false
        instance = null
        super.onDestroy()
    }
}

fun lookSessionOpen(): Boolean =
    LookActivity.instance?.let { !it.isFinishing } == true
