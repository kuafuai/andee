package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.io.File

/**
 * The top of a page, as a picture, for its card in the conversation.
 *
 * A page card shows part of the page itself (like a mini-program share card),
 * so the picture has to exist even for a page nobody has opened — one drawn
 * while the phone was locked, say. So it is rendered here: the page is loaded
 * into a WebView laid out at a phone's width, attached off-screen to the
 * conversation's own window (a WebView that is not attached to a window draws
 * nothing), drawn into a bitmap once it has settled, and kept beside the page
 * as `page_<id>.thumb.png`. [ChatHistory] deletes it with the page.
 *
 * One page at a time, on the main thread, each bounded by [TIMEOUT_MS]; a page
 * that never settles gets no picture and its card keeps the placeholder.
 */
object PageThumbs {
    private const val TAG = "PageThumbs"

    /** The page is laid out this many dp wide — a phone — and cropped to [ASPECT] tall. */
    private const val WIDTH_DP = 360
    const val ASPECT = 0.8f // height / width, as the card's preview

    /** Time for a page's own scripts and fonts after `onPageFinished`. */
    private const val SETTLE_MS = 700L
    private const val TIMEOUT_MS = 6_000L

    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val queue = ArrayDeque<Job>()
    private var busy = false

    private class Job(val host: ViewGroup, val page: File, val done: (Bitmap?) -> Unit)

    fun fileFor(page: File): File = File(page.parentFile, page.nameWithoutExtension + ".thumb.png")

    /** The picture if it is already made (memory, then disk), else null. Main thread. */
    fun cached(page: File): Bitmap? {
        memory.get(page.path)?.let { return it }
        val f = fileFor(page)
        if (!f.exists()) return null
        return runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()?.also { memory.put(page.path, it) }
    }

    /**
     * Make the picture for [page] using [host]'s window, then call [done] on the
     * main thread (with null if it could not be made). Main thread.
     */
    fun request(host: ViewGroup, page: File, done: (Bitmap?) -> Unit) {
        cached(page)?.let { done(it); return }
        queue.addLast(Job(host, page, done))
        pump()
    }

    private fun pump() {
        if (busy) return
        val job = queue.removeFirstOrNull() ?: return
        if (!job.page.exists() || !job.host.isAttachedToWindow) {
            job.done(null)
            pump()
            return
        }
        busy = true
        render(job) { bmp ->
            busy = false
            if (bmp != null) memory.put(job.page.path, bmp)
            job.done(bmp)
            pump()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun render(job: Job, finish: (Bitmap?) -> Unit) {
        val ctx = job.host.context
        val density = ctx.resources.displayMetrics.density
        val w = (WIDTH_DP * density).toInt()
        val h = (w * ASPECT).toInt()
        var finished = false
        val web = WebView(ctx)
        fun end(bmp: Bitmap?) {
            if (finished) return
            finished = true
            runCatching { job.host.removeView(web) }
            web.destroy()
            finish(bmp)
        }
        web.apply {
            // The same viewport handling as HtmlActivity, so the picture is the
            // page as it will open.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = true
            setBackgroundColor(android.graphics.Color.BLACK) // StageActivity.BACKDROP
            isVerticalScrollBarEnabled = false
            // Attached, so it renders; far off to the side, so nobody sees it;
            // not touchable and not in the accessibility tree.
            translationX = -10f * w
            isClickable = false
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    main.postDelayed({ end(snap(web, w, h, job.page)) }, SETTLE_MS)
                }
            }
        }
        job.host.addView(web, FrameLayout.LayoutParams(w, h))
        main.postDelayed({ end(null) }, TIMEOUT_MS)
        web.loadUrl(job.page.toURI().toString())
    }

    private fun snap(web: WebView, w: Int, h: Int, page: File): Bitmap? = runCatching {
        // Drawn at full size, kept at half: a card shows it a third of the
        // screen wide, and a hundred of these must not be a hundred screenshots.
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        web.draw(Canvas(full))
        val small = Bitmap.createScaledBitmap(full, w / 2, h / 2, true)
        if (small !== full) full.recycle()
        fileFor(page).outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        small
    }.onFailure { Log.w(TAG, "could not draw ${page.name}", it) }.getOrNull()
}
