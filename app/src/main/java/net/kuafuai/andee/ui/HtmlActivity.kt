package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale

/**
 * Full-screen canvas for brain-generated UI: any HTML the model composes
 * renders here, live. Data in via [show] (file-backed, so huge HTML doesn't
 * ride an Intent), the brain gets "closed" when the user is done.
 *
 * A tiny close affordance floats top-right ("✕ 完成") — the brain's UI
 * must never trap the user.
 *
 * In and out are [StageActivity]'s, shared with the two camera screens: the
 * three used to arrive three different ways and none of them left smoothly,
 * because `finish()` here raced the platform's own exit animation and the
 * caller's hand-back of the card. See [StageActivity].
 */
class HtmlActivity : StageActivity() {

    companion object {
        @Volatile var htmlFile: java.io.File? = null
        @Volatile var onClose: (() -> Unit)? = null

        /**
         * What the open page is called.
         *
         * Callers always set this before opening ([showFile]), because the
         * title is theirs — a message's own first line, or the model's. The
         * blank default is the one case no caller covers, and [onCreate]
         * resolves it from resources so the fallback speaks the user's
         * language rather than a hardcoded one.
         */
        @Volatile var titleText: String = ""

        /**
         * 屏幕上是否已经有一页。**在屏**（onCreate 到 onDestroy 之间）为真。
         *
         * 放在这里而不是调用方：`showFile` 有两个入口 —— 大脑的 `ui.show_html`
         * 和用户在对话记录里点一页重看 —— 而"同时只能有一页"是 WebView 这个类
         * 的事实，不是某一个调用方的约定。那个共享的静态 [htmlFile] 也让它是
         * 事实：两个实例会读到同一份文件，第二个盖上来以后第一个显示的就是错的
         * 内容了。
         *
         * 给 `ui.show_html` 读：它现在**不再等用户关页面**（那次改动见
         * `CommandDispatcher`），所以"屏幕上还有一页"必须靠这个标志判断，否则
         * 模型连着要两页就会叠出两个 Activity。
         */
        @Volatile var pageUp = false

        /**
         * The page that is on screen, for [replaceFile].
         *
         * A weak reference rather than a second `pageUp` flag, because the
         * question being asked is not "is one up" but "which object do I hand
         * new bytes to" — and the answer has to survive being asked from a
         * dispatcher worker while the field is written from the main thread.
         *
         * Set at the top of [onCreate], before that method reads the statics
         * below, and cleared in [onDestroy]. Those two facts are what close the
         * handoff window: a `startActivity` that has been *asked* for but has not
         * run yet is covered by the statics being the live values, and one that
         * has run is covered by this.
         */
        @Volatile private var live: java.lang.ref.WeakReference<HtmlActivity>? = null

        /** Main thread. [replaceFile] posts here; a dispatcher worker only waits on it. */
        internal val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        /**
         * Origin the brain's page runs as.
         *
         * Not a `file://` URL and not null, and both halves of that are bugs
         * this went through. `targetSdk 30` turns `allowFileAccess` off by
         * default, so loading the staged file failed outright with
         * ERR_ACCESS_DENIED — the "网页无法打开" the user saw. A *null* base URL
         * fixes the load but hands the page an opaque origin, where
         * `localStorage` throws a SecurityException: one `localStorage.getItem`
         * near the top of a script — a high score, a saved setting, exactly what
         * a model writes without thinking about it — and the whole script dies
         * and the page renders blank. A stable fake https origin gives DOM
         * storage somewhere to live, and never resolves.
         *
         * The page's own CSS and JS stay inline — there is no relative path to
         * a host that never resolves. Media is the exception, and this origin
         * is what decides it: a page served from https is a secure origin, so
         * WebView (default `MIXED_CONTENT_NEVER_ALLOW`; this app targets 34)
         * refuses an `http://` image or clip and loads an absolute `https://`
         * one. §7 of `LocalPrompt` draws the model's side of that line, and
         * relaxing it here would mean `MIXED_CONTENT_COMPATIBILITY_MODE` —
         * with cleartext anyway refused to every host but the one named in
         * `network_security_config.xml`.
         */
        private const val BASE_URL = "https://brain.local/"

        private const val TAG = "Body"

        /** Show the page even if `onPageFinished` never lands. See [StageActivity]. */

        /**
         * Write the HTML to the cache dir and open the activity.
         * @return the file written, or null on IO failure.
         */
        fun show(context: android.content.Context, html: String, title: String, onClosed: () -> Unit): java.io.File? {
            return runCatching {
                val f = java.io.File(context.cacheDir, "brain_ui_${System.currentTimeMillis()}.html")
                f.writeText(html)
                f
            }.getOrNull()?.also { showFile(context, it, title, onClosed) }
        }

        /**
         * Open a page that already exists on disk.
         *
         * The file belongs to the caller and is never deleted here — which is
         * the whole difference from the old behaviour, where closing the page
         * wiped it. That was right when the file was scratch space for one
         * viewing; it is wrong now that [ChatHistory] keeps pages so they can
         * be opened again from the scrollback.
         */
        fun showFile(
            context: android.content.Context,
            file: java.io.File,
            title: String,
            onClosed: () -> Unit,
        ) {
            titleText = title
            onClose = onClosed
            htmlFile = file
            // **Before `startActivity`, not in `onCreate`.** Starting an activity
            // is asynchronous, so an `onCreate`-only flag is false for the whole
            // window between "we handed the page to the system" and "the page is
            // on screen" — and that window is exactly when a model that asked
            // for two pages in one message asks for the second one. Measured:
            // with the flag set in `onCreate`, a second `ui.show_html` 60 ms
            // later sailed straight through and stacked two activities over the
            // shared static file below.
            pageUp = true
            runCatching {
                context.startActivity(
                    android.content.Intent(context, HtmlActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                // Nothing will ever clear it from `onDestroy` if nothing started.
                pageUp = false
                throw it
            }
        }

        /**
         * Put new bytes on the page that is already up. Returns false when there
         * is nothing to hand them to.
         *
         * **The reason this exists is that "one page at a time" used to mean
         * "refused".** A user who asked for a change to the page in front of
         * them — "把标题改短点" — got a model call, a refused `ui.show_html`, and
         * a sentence telling them to close the page by hand first: an
         * instruction to do the one thing the assistant was there to do. The
         * limit is real (one [htmlFile], one WebView), but what it forbids is two
         * *instances*, not a second render.
         *
         * Safe against the handoff gap by two paths, and both are needed:
         * before [onCreate] has run there is no instance and the statics are the
         * live values, so writing them is the replacement; once it has run,
         * [swapTo] swaps them under the running WebView. A page that is already
         * on its way out is refused rather than swapped into — writing into a
         * window that is being destroyed loses the new page entirely, and the
         * caller is told to open it as a fresh page instead.
         */
        fun replaceFile(
            context: android.content.Context,
            file: java.io.File,
            title: String,
            onClosed: () -> Unit,
        ): Boolean {
            if (!pageUp) return false
            val running = live?.get()
            if (running == null) {
                titleText = title
                onClose = onClosed
                htmlFile = file
                return true
            }
            val done = java.util.concurrent.CountDownLatch(1)
            var ok = false
            mainHandler.post {
                ok = if (running.isLeaving) {
                    false
                } else {
                    running.swapTo(file, title, onClosed)
                    true
                }
                done.countDown()
            }
            done.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            return ok
        }
    }

    private var web: WebView? = null
    private var spinner: ProgressBar? = null

    /**
     * Swap this page's contents. Main thread only — [replaceFile] posts here.
     *
     * Not a second Activity and not a `startActivity`: the WebView is already on
     * screen and already sized, so the new render lands in the same window at
     * the same scroll position of the *chrome*, which is what "改了" should look
     * like. The scroll position of the page itself resets, and that is correct —
     * different bytes are not the same document.
     *
     * The statics are updated before the load because [onDestroy] reads them,
     * and a page closed two seconds after a replacement has to be the page the
     * caller was told about.
     */
    private fun swapTo(file: java.io.File, title: String, closed: () -> Unit) {
        titleText = title
        onClose = closed
        htmlFile = file
        val html = runCatching { file.readText() }.getOrNull() ?: return
        // No spinner and no stage animation: the window is already up, and
        // growing it again would read as a second page opening. A slow render
        // shows the old page for a beat, which is the honest thing to show.
        web?.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The brain composed a full-screen page; an AppCompat title bar reading
        // "Andee" above it is our chrome intruding on their canvas, and it
        // eats the top of every layout that assumed it had 100vh.
        // Again here, not instead of in [showFile]: this one covers the activity
        // being recreated by the system after process death, where no caller ran.
        pageUp = true
        // Before the statics below are read, so a replacement that lands while
        // this method is running either writes the statics (this is not live
        // yet) or takes the running path — never both and never neither.
        live = java.lang.ref.WeakReference(this)
        // Only when no caller named the page. [titleText] itself is set by
        // [showFile] before this activity starts, so the common path is already
        // non-blank and this is the fallback for the recreated-instance case.
        if (titleText.isBlank()) {
            titleText = AppLocale.wrap(this).getString(R.string.html_default_title)
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // The model composes for a phone-shaped viewport and says so in its
            // own <meta viewport>. Without this the WebView ignores the tag,
            // lays the page out at desktop width and scales it down — which is
            // most of why a generated page reads as "meant for a computer".
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    revealStage()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: android.webkit.WebResourceRequest,
                    error: android.webkit.WebResourceError,
                ) {
                    // Only the main document is worth shouting about; a page
                    // that pulls a font off the internet it hasn't got should
                    // still render.
                    if (request.isForMainFrame) {
                        Log.w(TAG, "brain page failed: ${error.errorCode} ${error.description}")
                    }
                }
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    // The page is generated, unreviewed and un-debuggable from
                    // the device. One throw in a top-level script renders a
                    // blank screen with no other trace anywhere.
                    Log.d(TAG, "brain page console: ${m.message()} @${m.lineNumber()}")
                    return true
                }
            }
            layoutParams = matchParent()
            setBackgroundColor(BACKDROP)
        }

        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor(Glass.ACCENT))
            layoutParams = FrameLayout.LayoutParams(
                dpI(34), dpI(34), Gravity.CENTER
            )
        }

        // Loaded as a string against [BASE_URL], not as the file's own URL: the
        // file is staging (it survives an activity recreate, and it keeps a
        // megabyte of HTML off the Intent), not something the WebView is
        // allowed to open.
        val html = htmlFile?.let { f -> runCatching { f.readText() }.getOrNull() }
        if (html == null) {
            Log.w(TAG, "no html staged for HtmlActivity")
            finish()
            return
        }
        web?.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)

        // Close pill — always reachable, floats above the content. Top-right,
        // where a close control is expected and where the page is least likely
        // to have put anything: generated pages open with a title and end with
        // their controls, so a pill pinned to the bottom lands on the buttons.
        val close = closePill(this) { leave() }.apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.TOP
            ).apply {
                topMargin = dpI(12)
                marginEnd = dpI(12)
            }
        }

        // **The pill has to clear the status bar, and it did not.** This window
        // is edge-to-edge — its frame is the whole display, which is why the
        // page's own 100vh starts at y=0 — so `topMargin = 12dp` puts the pill
        // *under* the clock and the battery, in the strip the status bar owns.
        // Measured on the 1600x2560 tablet: a tap dead centre on the pill was
        // swallowed by the status bar and the page would only close with the
        // back gesture. That matters more than it looks — the pill is the
        // affordance the tool description promises the model ("keep roughly
        // 110x44 clear there"), and before `ui.show_html` stopped blocking,
        // failing to reach it meant the brain sat in a five-minute wait.
        stage.setOnApplyWindowInsetsListener { _, insets ->
            val top = insets.getInsets(
                android.view.WindowInsets.Type.systemBars() or
                    android.view.WindowInsets.Type.displayCutout(),
            ).top
            val lp = close.layoutParams as FrameLayout.LayoutParams
            val want = dpI(12) + top
            // Guarded like `FloatingWindowUi.insetTopBar`: writing layoutParams
            // asks for a layout pass, and this runs inside one.
            if (lp.topMargin != want) {
                lp.topMargin = want
                close.layoutParams = lp
            }
            insets
        }

        stage.addView(web)
        stage.addView(spinner)
        stage.addView(close)
    }

    /**
     * The page is drawable. Everything here has to happen *inside* the stage's
     * growth, not alongside it — the stage is the whole window, so a spinner
     * that fades on its own clock is a spinner the user sees pop into an
     * already-full-size page.
     */
    override fun onStageRevealing() {
        spinner?.animate()?.alpha(0f)?.setDuration(160)
            ?.withEndAction { spinner?.visibility = android.view.View.GONE }?.start()
    }

    /**
     * The hand-back, and it fires from [onStageLeaving] — while the page is
     * still shrinking — rather than from `onDestroy`. [reportClosed] is what
     * makes that safe to do from both.
     */
    override fun onStageLeaving() {
        spinner?.animate()?.cancel()
        reportClosed()
    }

    override fun onDestroy() {
        // The other half of the pair above: a page the system tore down rather
        // than the user closing it (low memory, an activity-recorder replay)
        // never reaches [leave], and the caller still has to be told. Idempotent
        // by construction — see [reportClosed].
        reportClosed()
        // Note what is *not* here: the file is not deleted. Pages outlive their
        // viewing now so the scrollback can re-open them; eviction is
        // [ChatHistory]'s job, against its own budget. See [showFile].
        htmlFile = null
        pageUp = false
        live = null
        super.onDestroy()
    }

    /**
     * Tell the caller the page is over, exactly once, whenever that happens.
     *
     * `onClose` is the only channel the caller has: `ui.show_html` uses it to
     * put the ball back and to tell the brain how long the page was up, and
     * [ScreenBodyService.openPageFrom] uses it to unfold the card the user had
     * open. Both were fired from `onDestroy`, which is late — see
     * [StageActivity.onStageLeaving] for why that reads as a flash.
     *
     * [pageUp] is deliberately *not* cleared here. It stays true until the
     * window is actually gone, because the gap between "the user tapped ✕" and
     * "the window is gone" is exactly when a model that asked for two pages in
     * one message would ask for the second one, and the flag is what stops it
     * — [showFile] sets it for the same reason.
     */
    private fun reportClosed() {
        val cb = onClose ?: return
        onClose = null
        runCatching { cb() }
            .onFailure { Log.w(TAG, "page-closed callback failed", it) }
    }
}
