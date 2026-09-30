package net.kuafuai.andee.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
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
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The ball's eye: full-screen camera preview with live barcode scanning.
 * A code found → vibrate-lite (result banner) → finish with the payload.
 * The brain gets: {value, format} plus rawText when the code is a URL/text.
 *
 * Opening sequence (device.scan): check CAMERA permission → denied? fire the
 * runtime dialog from here directly (we ARE the activity) → granted → scan.
 * So one tool call ends in either a scanned code or an honest error string.
 *
 * Result delivery: the controller polls [LastResult] over the WS; simpler
 * than a result-callback chain through the service for now.
 *
 * In and out are [net.kuafuai.andee.ui.StageActivity]'s, like every other
 * full-screen face — see that class for why leaving one of these used to
 * flash.
 */
class ScanActivity : net.kuafuai.andee.ui.StageActivity() {

    companion object {
        var LastResult: android.os.Bundle? = null
        const val EXTRA_AUTO_CLOSE_MS = "auto_close_ms"
        private const val HIT_GREEN = 0xFF07C160.toInt()
    }

    private val handling = AtomicBoolean(false)
    private var status: StatusPill? = null
    private var viewfinder: ViewfinderView? = null
    private var autoCloseAt = 0L

    /** Strings in the user's language. Refreshed in [startCamera]. */
    private var lctx: Context = this

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full-bleed preview; our own chrome insets itself (avoidSystemBars).
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 4243)
            return
        }
        autoCloseAt = System.currentTimeMillis() +
            (intent.getLongExtra(EXTRA_AUTO_CLOSE_MS, 60_000L))
        startCamera()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            autoCloseAt = System.currentTimeMillis() +
                (intent.getLongExtra(EXTRA_AUTO_CLOSE_MS, 60_000L))
            startCamera()
        } else {
            handling.set(true)
            LastResult = Bundle().apply {
                putString("error", "camera permission denied")
            }
            leave()
        }
    }

    private fun startCamera() {
        lctx = AppLocale.wrap(this)
        val root = stage
        val previewView = PreviewView(this).apply {
            layoutParams = matchParent()
        }
        viewfinder = ViewfinderView(this).apply { layoutParams = matchParent() }
        val pill = StatusPill(this).apply {
            setStatus(lctx.getString(R.string.dev_scan_align))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            ).apply { bottomMargin = dpI(28) }
        }
        status = pill
        val close = closePill(this) { recordCancel(); leave() }.apply {
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

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                    .build()
            )
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val exec = Executors.newSingleThreadExecutor()
            analysis.setAnalyzer(exec) { proxy ->
                @Suppress("DEPRECATION")
                val media = proxy.image
                if (media != null) {
                    val input = InputImage.fromMediaImage(
                        media, proxy.imageInfo.rotationDegrees
                    )
                    scanner.process(input)
                        .addOnSuccessListener { codes ->
                            if (codes.isNotEmpty() && handling.compareAndSet(false, true)) {
                                onCode(codes.first())
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                } else proxy.close()
                // auto-close watchdog
                if (System.currentTimeMillis() > autoCloseAt && handling.compareAndSet(false, true)) {
                    LastResult = Bundle().apply { putString("error", "scan timed out") }
                    runOnUiThread { leave() }
                }
            }
            provider.unbindAll()
            runCatching {
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            }.onSuccess { revealStage() }.onFailure {
                android.util.Log.w("Body", "scan bind failed: ${it.message}")
                status?.setStatus(lctx.getString(R.string.dev_camera_unavailable))
                handling.set(true)
                LastResult = Bundle().apply { putString("error", "camera bind failed") }
                leave()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onCode(code: Barcode) {
        LastResult = Bundle().apply {
            putString("value", code.rawValue ?: code.rawBytes?.toString(Charsets.UTF_8) ?: "")
            putString("format", code.format.toString())
            putLong("when", System.currentTimeMillis())
        }
        runOnUiThread {
            status?.setStatus("✓ ${code.rawValue?.take(40)}")
            status?.setDotColor(HIT_GREEN)
            viewfinder?.flash(HIT_GREEN)
            // Long enough for the ✕ green and the value to register — this is
            // the only confirmation the user gets that the scan worked, and a
            // stage that shrinks out in 190 ms takes it away.
            window.decorView.postDelayed({ leave() }, 700)
        }
    }

    /**
     * The user gave up. Writing [LastResult] is the whole point: without it
     * the dispatcher's poll had nothing to find and span until its 30s
     * timeout, so backing out of a scan cost the brain half a minute of
     * silence before it heard anything at all.
     *
     * `handling` is the same latch the hit and timeout paths take, so
     * whichever outcome happened first keeps its result.
     */
    private fun recordCancel(): Boolean {
        if (!handling.compareAndSet(false, true)) return false
        LastResult = Bundle().apply { putString("error", "cancelled by user") }
        return true
    }

    override fun onDestroy() {
        // Covers the back key, which never passes through the ✕ pill.
        recordCancel()
        super.onDestroy()
    }
}

/** Controller-side helper: is the camera granted? */
fun cameraGranted(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
