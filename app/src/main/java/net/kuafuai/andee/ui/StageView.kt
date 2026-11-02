package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.config.Stage
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.stage.StageLine
import net.kuafuai.andee.stage.StageShell

/**
 * The stage: the middle of the unfolded card, where the conversation used to be, and Andee's own.
 *
 * A WebView drawing the fragment Andee wrote ([Stage]) inside a shell that is the device's: charset,
 * viewport, a Content-Security-Policy with no network for scripts or fetches, the theme's colours as CSS
 * variables, the house classes in the cards' glass, and `window.andee`. The shell is rebuilt from [Glass]
 * on every draw, so a theme change restyles the stage without Andee rewriting it.
 *
 * Two more things live here. The **live line** under the stage says what was just said — the conversation
 * is one tap away (it opens the chat), but standing on the stage the user still sees what was said. It sits
 * *below* the stage, never over it (over it, it hid the bottom of whatever Andee put there), and it reads a
 * long reply a sentence at a time ([ticker]) instead of cutting it off after two lines.
 * And the **empty state**: a stage nobody has furnished asks Andee to, with one button.
 *
 * `andee.say(text)` from the page reaches Andee as the user's words **only within a moment of a real tap
 * on the stage**: a script that calls it on load would otherwise be Andee talking to itself in a loop.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class StageView(context: Context) : LinearLayout(context) {

    var onSay: ((String) -> Unit)? = null
    var onFurnish: (() -> Unit)? = null
    var onOpenChat: (() -> Unit)? = null

    private var lctx: Context = AppLocale.wrap(context)
    private var lastTouchUp = 0L
    private var lastSay = 0L

    /** The next load is a different stage, not a repaint of the same one: show it from the top. */
    private var scrollToTop = false
    private var drawnVersion = -1

    /** What the WebView was last given, so a repaint with nothing new does not reload it. */
    private var drawn: String? = null
    private var liveText = ""
    private var lastLine = ""

    private val web = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addJavascriptInterface(Bridge(), "AndeeStage")
        // The stage is a page that stays put: no link navigates it away.
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            // A new stage starts at its top. The WebView keeps the previous page's scroll position across a
            // load, so a stage that was scrolled used to open its successor half-way down, title out of sight.
            override fun onPageFinished(view: WebView, url: String) {
                if (scrollToTop) { scrollToTop = false; view.scrollTo(0, 0) }
            }
        }
        setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_UP) lastTouchUp = SystemClock.uptimeMillis()
            false
        }
    }

    private val emptyTitle = TextView(context)
    private val emptyBody = TextView(context)
    private val emptyButton = TextView(context)
    private val empty = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(32), 0, dp(32), dp(48))
        addView(emptyTitle.apply {
            gravity = Gravity.CENTER
            textSize = Glass.Type.HEADLINE * 1.15f
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(emptyBody.apply {
            gravity = Gravity.CENTER
            textSize = Glass.Type.BODY
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(emptyButton.apply {
            gravity = Gravity.CENTER
            textSize = Glass.Type.BODY
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20), dp(11), dp(20), dp(11))
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { onFurnish?.invoke() }
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
    }

    private val live = TextView(context).apply {
        // One sentence at a time; two lines is room for a long one.
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        textSize = Glass.Type.CAPTION
        setPadding(dp(14), dp(9), dp(14), dp(9))
        isClickable = true
        setOnClickListener { onOpenChat?.invoke() }
        visibility = View.GONE
    }

    init {
        orientation = VERTICAL
        // The stage takes what the live line leaves; the line is under it, not on it.
        val body = FrameLayout(context)
        body.addView(web, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        body.addView(empty, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(live, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(14); rightMargin = dp(14); topMargin = dp(4); bottomMargin = dp(6)
        })
        paintChrome()
        refresh()
    }

    /** Re-read the stage and redraw if anything changed. Main thread. */
    fun refresh() {
        val s = Stage.current(context)
        if (s == null) {
            web.visibility = View.GONE
            empty.visibility = View.VISIBLE
            if (drawn != null) { drawn = null; web.loadUrl("about:blank") }
            return
        }
        empty.visibility = View.GONE
        web.visibility = View.VISIBLE
        val html = shell(s.fragment)
        if (html == drawn) return
        // A new version (not a theme or language repaint of the same one) opens at the top.
        if (s.version != drawnVersion) scrollToTop = true
        drawnVersion = s.version
        drawn = html
        // An https origin, so https images and media load (no mixed content).
        web.loadDataWithBaseURL(StageShell.BASE_URL, html, "text/html", "utf-8", null)
    }

    /** The voice pipeline's live line: a draft, a final, or "" to clear. */
    fun setLive(text: String) {
        val was = liveText
        liveText = text
        // A live line that ended goes away. It does not replay the last message: the line is cleared from many
        // places, and the answer that follows a draft arrives as a new message anyway (setLastLine plays it).
        if (text.isBlank()) {
            if (was.isNotBlank()) {
                removeCallbacks(ticker)
                hide()
            }
            return
        }
        paintLive()
    }

    /** The last thing said in the conversation, shown when nothing is live. */
    fun setLastLine(text: String) {
        // Called on every change to the history (an event row, a fold); replay only for a new message.
        if (text == lastLine) return
        lastLine = text
        paintLive()
    }

    fun onThemeChanged() {
        paintChrome()
        drawn = null
        refresh()
    }

    fun onLocaleChanged() {
        lctx = AppLocale.wrap(context)
        paintChrome()
        drawn = null
        refresh()
    }

    /**
     * What the line shows. While something is being said (a draft, a transcript) it is the newest sentence of
     * it, as it arrives. Otherwise the last thing said, a sentence at a time: each held [dwell] and faded into
     * the next, **once** — after the last sentence it stays [LINGER_MS] and fades out, and the stage has the
     * whole height again. A new message plays from its first sentence.
     */
    private fun paintLive() {
        removeCallbacks(ticker)
        val live0 = liveText.trim()
        if (live0.isNotEmpty()) {
            show(StageLine.sentences(live0).lastOrNull() ?: live0, fade = false)
            return
        }
        lines = StageLine.sentences(lastLine)
        index = 0
        if (lines.isEmpty()) {
            live.visibility = View.GONE
            return
        }
        show(lines[0], fade = false)
        postDelayed(ticker, if (lines.size > 1) StageLine.dwell(lines[0]) else StageLine.LINGER_MS)
    }

    private var lines: List<String> = emptyList()
    private var index = 0

    private val ticker = object : Runnable {
        override fun run() {
            if (index >= lines.lastIndex) {
                hide()
                return
            }
            index++
            show(lines[index], fade = true)
            postDelayed(this, if (index == lines.lastIndex) StageLine.LINGER_MS else StageLine.dwell(lines[index]))
        }
    }

    /** Played to the end: fade out and give the stage its height back. */
    private fun hide() {
        live.animate().alpha(0f).setDuration(FADE_MS * 2).withEndAction {
            live.visibility = View.GONE
            live.alpha = 1f
        }.start()
    }

    private fun show(text: String, fade: Boolean) {
        live.visibility = View.VISIBLE
        if (!fade) {
            live.animate().cancel()
            live.alpha = 1f
            live.text = text
            return
        }
        live.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            live.text = text
            live.animate().alpha(1f).setDuration(FADE_MS).start()
        }.start()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        live.animate().cancel()
        live.visibility = View.GONE
        super.onDetachedFromWindow()
    }

    private fun paintChrome() {
        emptyTitle.text = lctx.getString(R.string.stage_empty_title)
        emptyTitle.setTextColor(Color.parseColor(Glass.TITLE))
        emptyBody.text = lctx.getString(R.string.stage_empty_body)
        emptyBody.setTextColor(Color.parseColor(Glass.SECONDARY))
        emptyButton.text = lctx.getString(R.string.stage_empty_button)
        emptyButton.setTextColor(Color.parseColor(Glass.ON_TINT))
        emptyButton.background = Glass.tinted(context, dp(22))
        live.setTextColor(Color.parseColor(Glass.LABEL))
        live.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (Theme.night) 0x33FFFFFF else 0x29000000)
        }
    }

    /** The page: the fragment inside the shell both bodies share ([StageShell]), in this palette. */
    private fun shell(fragment: String): String {
        fun css(hex: String): String {
            val c = Color.parseColor(hex)
            return "rgba(${Color.red(c)},${Color.green(c)},${Color.blue(c)},${"%.3f".format(java.util.Locale.US, Color.alpha(c) / 255f)})"
        }
        val night = Theme.night
        val theme = StageShell.Theme(
            night = night,
            ink = css(Glass.TITLE), ink2 = css(Glass.LABEL), muted = css(Glass.SECONDARY), accent = css(Glass.ACCENT),
            ok = css(Glass.OK), warn = css(Glass.WARN), danger = css(Glass.DANGER),
            glass = if (night) "rgba(255,255,255,.10)" else "rgba(0,0,0,.07)",
            edge = if (night) "rgba(255,255,255,.16)" else "rgba(255,255,255,.55)",
            well = if (night) "rgba(255,255,255,.08)" else "rgba(0,0,0,.06)",
        )
        return StageShell.html(fragment, theme, VoiceConfig.uiLanguage(context), "AndeeStage.say(t)")
    }

    private inner class Bridge {
        @JavascriptInterface
        fun say(text: String) {
            post {
                val now = SystemClock.uptimeMillis()
                // Only from a real tap, and not twice in a breath.
                if (now - lastTouchUp > TAP_WINDOW_MS || now - lastSay < SAY_GAP_MS) return@post
                val t = text.trim().take(500)
                if (t.isEmpty()) return@post
                lastSay = now
                onSay?.invoke(t)
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAP_WINDOW_MS = 1_500L
        const val SAY_GAP_MS = 2_000L
        const val FADE_MS = 140L
    }
}
