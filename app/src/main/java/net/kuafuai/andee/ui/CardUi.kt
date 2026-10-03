package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale

/**
 * The ball's little signboards — quiet, tappable cards the ball *holds up*.
 *
 * Why cards beat push-interrupts for incoming messages: a notification that
 * wakes the brain every time trains the user to mute everything. A small
 * card that asks "看看吗?" costs one glance and a tap — the brain only
 * hears about messages the user actually cares about. The brain gets the
 * SAME power as a tool (ask_user / alert), so it can escalate to a card
 * whenever IT thinks the user should decide something.
 *
 * The card is not a free-floating box: it is anchored to the ball. A tail
 * on the card's underside points at the ball, the card sits just above it,
 * and while the card is up the ball itself attends — setHolding(true) puts
 * a listening face on it and stops the idle fidget. The user is deciding
 * something; the ball waits for the answer like it waits for speech.
 *
 * One card at a time — a new card replaces the old one (the old one's
 * callback fires with "superseded"). Queues of cards are just push spam
 * with extra steps.
 */
object CardUi {

    private val main = Handler(Looper.getMainLooper())

    /**
     * Run [r] on the main thread — the thread the task flag and every card
     * operation live on. Exposed for the notification relay, whose
     * onNotificationPosted arrives on a binder thread and needs its
     * card-or-not decision to happen where the state is authoritative.
     */
    fun post(r: () -> Unit) {
        main.post(r)
    }
    private var wm: WindowManager? = null
    private var current: CardHandle? = null

    private const val CARD_MAX_WIDTH_DP = 560
    private const val AUTO_DISMISS_MS = 25_000L
    private const val TAG = "Card"

    /**
     * How long the same error stays suppressed, and how long an error card
     * stays up. Shorter than [AUTO_DISMISS_MS]: an error is an FYI, not a
     * question, and the scrollback keeps it either way.
     */
    private const val ERROR_DEDUP_MS = 10_000L
    private const val ERROR_DISMISS_MS = 8_000L

    private var lastErrorText: String? = null
    private var lastErrorAt = 0L

    /**
     * How far the tail tip reaches toward the ball. Superseded as a *gap*
     * metric by the single-window rewrite — the card is now a child of the
     * ball's own window (FloatingWindowUi.showSignboard), and the tail
     * overlaps the ball area's transparent padding by a fraction of the
     * window side instead of keeping a dp clearance from a measured head.
     */
    private const val TAIL_OVERLAP_NOTE = "see FloatingWindowUi.SIGN_OVERLAP_FRACTION"

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context
        if (wm == null) {
            wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
    }

    class Result(
        /** Which button the user tapped, or null for timeout/superseded/dismissed-elsewhere. */
        val button: String?,
        val how: String,   // "button" | "timeout" | "superseded" | "tap_outside"
    )

    /**
     * Show a confirm-style card. Callback fires exactly once, on the main
     * thread. Safe to call from any thread.
     *
     * @param buttons label list, e.g. ["帮我看看", "忽略"]. First button is
     *        rendered as the primary (tinted) one.
     */
    fun ask(
        question: String,
        buttons: List<String>,
        timeoutMs: Long = AUTO_DISMISS_MS,
        onDone: (Result) -> Unit,
    ) {
        show(question, buttons, cancelable = false, timeoutMs, onDone)
    }

    /**
     * Show a one-lane notice with a single dismiss control. Tapping the card
     * body also counts as "read".
     */
    fun alert(text: String, timeoutMs: Long = AUTO_DISMISS_MS, onDone: (Result) -> Unit) {
        // No context means the service is not up, and `show` would bail on the
        // same condition one call later — so returning here changes nothing
        // observable, it just keeps the label lookup off a null.
        val ctx = appContext ?: return
        show(text, listOf(AppLocale.str(ctx, R.string.card_ack)), cancelable = true, timeoutMs, onDone)
    }

    /**
     * Something broke and the user has to be told. Pops a card and leaves a
     * row in the scrollback, so the failure survives the card timing out.
     *
     * The de-dup is the whole reason this isn't just `alert` at the call
     * sites: the failures worth showing arrive in bursts, not singly — a hub
     * that is down produces the same sentence on every reconnect attempt, and
     * since a new card supersedes the old one that reads as a card flickering
     * in place, cancelling whatever the user was in the middle of answering.
     */
    fun error(text: String) {
        if (text.isBlank()) return
        val now = android.os.SystemClock.uptimeMillis()
        synchronized(this) {
            if (text == lastErrorText && now - lastErrorAt < ERROR_DEDUP_MS) return
            lastErrorText = text
            lastErrorAt = now
        }
        ChatHistory.addError(text)
        alert(text, ERROR_DISMISS_MS) { }
    }

    fun dismissCurrent() {
        main.post { current?.close("superseded") }
    }

    // ------------------------------------------------------------------

    private class CardHandle(
        val view: View,
        /** The ball window hosting this card, or null for the standalone fallback. */
        val host: FloatingWindowUi?,
        @Volatile var finished: Boolean = false,
        var onDone: ((Result) -> Unit)? = null,
        var timeout: Runnable? = null,
    ) {
        fun close(how: String, button: String? = null) {
            if (finished) return
            finished = true
            timeout?.let { main.removeCallbacks(it) }
            if (host != null) {
                host.hideSignboard()
            } else {
                runCatching { wm?.removeView(view) }
            }
            if (current === this) current = null
            // The ball can stop attending — the question is answered, timed
            // out, or was replaced by a newer one (which re-arms it below).
            ballWindow()?.setHolding(false)
            onDone?.invoke(Result(button, how))
        }
    }

    /** The ball's window, when the service is up. Null-safe by design. */
    private fun ballWindow(): FloatingWindowUi? =
        net.kuafuai.andee.ScreenBodyService.get()?.ballWindow()

    /**
     * Wire the window's eviction hook once per window. The window clears its
     * signboard when it folds or unfolds; the card it evicted must fire its
     * own callbacks through the same exactly-once path as a timeout, or the
     * notification relay's ask would dangle forever.
     */
    private fun ensureEvictorWired(host: FloatingWindowUi) {
        if (host.signboardEvictor != null) return
        host.signboardEvictor = { current?.close("superseded") }
    }

    @SuppressLint("SetTextI18n")
    private fun show(
        text: String,
        buttons: List<String>,
        cancelable: Boolean,
        timeoutMs: Long,
        onDone: (Result) -> Unit,
    ) {
        val windowManager = wm ?: return
        main.post {
            // One card at a time — the newcomer replaces whoever's up.
            current?.close("superseded")

            val context = appContext ?: return@post
            val density = android.content.res.Resources.getSystem().displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val dm = appContext?.resources?.displayMetrics
                ?: android.content.res.Resources.getSystem().displayMetrics

            // ---- card root ------------------------------------------------
            // The tail is drawn by the root itself (drawChild hook) rather
            // than being a separate view: it has to sit *below* the rounded
            // card background and share its color, and a child view with its
            // own background would fight the parent's rounded corners.
            val cardReal = TailLinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                cornerRadius = dp(22)
                setPadding(dp(22), dp(18), dp(22), dp(16))
            }

            // Question text
            val title = TextView(context).apply {
                this.text = text
                setTextColor(Color.parseColor(Glass.TITLE))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.TITLE)
                typeface = Typeface.DEFAULT_BOLD
                setLineSpacing(dp(3).toFloat(), 1f)
                maxLines = 4
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            cardReal.addView(title)

            // Button row — buttons laid out right-to-left (primary last =
            // rightmost), spacing via layout params passed to addView.
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, 0)
            }
            val btnViews = mutableListOf<TextView>()
            val btnLabels = mutableListOf<String>()
            buttons.asReversed().forEach { label ->
                // reversed list: original index 0 (primary) comes LAST →
                // rightmost, and renders tinted.
                val isPrimary = label == buttons.first()
                val btn = TextView(context).apply {
                    this.text = label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.BODY)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(18), dp(9), dp(18), dp(9))
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = false
                    // The primary keeps a solid fill: it is the one thing on
                    // the card that must not read as glass, or there is nothing
                    // to aim at. The secondary becomes another pane of the same
                    // glass as the card — a solid slate slab sitting on a
                    // translucent card was the loudest part of the old look.
                    // Both come from [Glass], which is also where the settings
                    // card's buttons come from: a dialog the assistant raises
                    // and a card the user opened should not be two designs.
                    background = if (isPrimary) {
                        Glass.tinted(context, dp(22))
                    } else {
                        // `thumb`, not `panel`: nothing is blurred behind this
                        // card, so its panes have to carry themselves a step
                        // brighter than they would on a frosted one.
                        Glass.thumb(context, dp(22))
                    }
                    setTextColor(Color.parseColor(if (isPrimary) Glass.TITLE else Glass.LABEL))
                    Glass.pressable(this)
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(10) }
                row.addView(btn, lp)
                btnViews.add(btn)
                btnLabels.add(label)
            }

            // Long option labels: every button in the row is measured against
            // the card's full content width, so a label wider than that wraps
            // inside its own button — mixed-height buttons, and where several
            // of them overflow, buttons half-cut at the card's edge. Decide the
            // row's shape from whether it actually fits: horizontal when it
            // does (then no single button can have wrapped, since any wrapped
            // button alone fills the width), a full-width stacked list when it
            // does not — which is also the shape a long list of choices reads
            // as anyway. The primary stays last: rightmost in the row,
            // bottommost in the stack, the "confirm" spot either way.
            //
            // The natural-width measure is UNSPECIFIED on purpose: measured
            // against the card's width instead, an overflowing row reports
            // exactly that width back (resolveSizeAndState clamps it), so the
            // comparison could never fire. UNSPECIFIED neither clamps nor
            // wraps, so this is the true sum of the single-line widths.
            val cardWidth = minOf(dm.widthPixels - dp(32), dp(CARD_MAX_WIDTH_DP))
            val contentWidth = cardWidth - dp(22) * 2
            row.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            if (row.measuredWidth > contentWidth) {
                row.orientation = LinearLayout.VERTICAL
                btnViews.forEachIndexed { i, btn ->
                    val vlp = btn.layoutParams as LinearLayout.LayoutParams
                    vlp.width = LinearLayout.LayoutParams.MATCH_PARENT
                    vlp.marginStart = 0
                    vlp.topMargin = if (i == 0) 0 else dp(8)
                    btn.layoutParams = vlp
                }
            }
            cardReal.addView(row)

            // ---- host inside the ball's window --------------------------------
            //
            // The old design put this card in its own overlay window and
            // positioned it from a measured copy of the ball's position —
            // which goes stale mid-slide, disagrees with the overlay
            // coordinate space on some devices, and reads a `winYOrigin` that
            // is only refreshed when the ball is still. Each of those hits a
            // different subset of devices; together they are the "sometimes
            // it's off" this rewrite exists to kill. The card is now a child
            // of the ball's own window (see FloatingWindowUi.showSignboard):
            // same coordinate space, zero translation, and the ball being
            // dragged or perched takes the card with it for free.
            val host = ballWindow()
            if (host == null || !host.isFolded()) {
                // No ball window to hold it: fall back to the standalone
                // overlay. Rare (service down mid-question) but the question
                // still has to be answerable.
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    width = minOf(dm.widthPixels - dp(32), dp(CARD_MAX_WIDTH_DP))
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = dp(96)
                }
                cardReal.tailX = -1

                // ---- handle + callbacks ------------------------------------
                val handle = CardHandle(cardReal, host = null)
                handle.onDone = { r -> main.post { onDone(r) } }
                wireButtons(handle, btnViews, btnLabels, cancelable, cardReal, title)
                val timeoutRunnable = Runnable { handle.close("timeout") }
                handle.timeout = timeoutRunnable
                main.postDelayed(timeoutRunnable, timeoutMs)

                runCatching {
                    cardReal.hideFromAccessibility()
                    windowManager.addView(cardReal, params)
                    current = handle
                    Glass.enter(cardReal)
                }.onFailure {
                    handle.finished = true
                    handle.timeout?.let { t -> main.removeCallbacks(t) }
                    // Worth a line. This is the one path where a message that
                    // was supposed to reach the user goes nowhere at all, and
                    // the caller only sees `onDone("error: …")` — which for a
                    // fire-and-forget alert is no one. Without this, "the card
                    // never appeared" has no evidence anywhere.
                    Log.w(TAG, "standalone card could not be added", it)
                    onDone(Result(null, "error: ${it.message}"))
                }
                return@post
            }

            // Held by the ball: the tail points at the window's centre —
            // which is where the ball is, by construction, not by measurement.
            cardReal.measure(
                View.MeasureSpec.makeMeasureSpec(
                    minOf(dm.widthPixels - dp(32), dp(CARD_MAX_WIDTH_DP)),
                    View.MeasureSpec.EXACTLY,
                ),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            cardReal.tailX = cardReal.measuredWidth / 2

            // ---- handle + callbacks ----------------------------------------
            val handle = CardHandle(cardReal, host)
            handle.onDone = { r -> main.post { onDone(r) } }
            wireButtons(handle, btnViews, btnLabels, cancelable, cardReal, title)
            val timeoutRunnable = Runnable { handle.close("timeout") }
            handle.timeout = timeoutRunnable
            main.postDelayed(timeoutRunnable, timeoutMs)

            if (host.showSignboard(cardReal)) {
                current = handle
                ensureEvictorWired(host)
                // Grows out of the ball's head instead of blinking into being.
                // The pivot is the tail tip — the point the card is held by —
                // so the tail stays on the head for the whole 320 ms rather
                // than sweeping across it. No matching exit animation: taking
                // a card down resizes the window, and a card that is still
                // fading is a card the next `ask` cannot replace (one
                // signboard at a time, and the second would be refused).
                cardReal.pivotX = cardReal.measuredWidth / 2f
                cardReal.pivotY = cardReal.measuredHeight.toFloat()
                Glass.enter(cardReal)
                // The ball holds still and attends while its question is up.
                // Armed after hosting so a throw in between can't leave the
                // ball frozen mid-attend.
                host.setHolding(true)
            } else {
                handle.finished = true
                handle.timeout?.let { t -> main.removeCallbacks(t) }
                // The other silent one. `showSignboard` refuses when a signboard
                // is already up (it hosts one at a time) or when the ball window
                // is not there to hold anything — both of which mean this
                // message never reached the user, and both of which used to
                // leave no trace anywhere.
                Log.w(TAG, "signboard refused to host the card")
                onDone(Result(null, "error: signboard busy"))
            }
        }
    }

    /** Shared button wiring for both the hosted and the fallback card. */
    private fun wireButtons(
        handle: CardHandle,
        btnViews: List<TextView>,
        btnLabels: List<String>,
        cancelable: Boolean,
        cardReal: View,
        title: View,
    ) {
        btnViews.forEachIndexed { i, btn ->
            btn.setOnClickListener { handle.close("button", btnLabels[i]) }
        }
        if (cancelable) {
            // Alert: tapping the card body itself = read.
            cardReal.setOnClickListener { handle.close("tap_outside") }
            title.setOnClickListener { handle.close("tap_outside") }
        }
    }

    /**
     * Card body that also draws the pointing tail. The tail is a downward
     * triangle hanging from the card's bottom edge at [tailX]; when [tailX]
     * is negative it isn't drawn (the no-ball fallback placement).
     *
     * ## Why it is painted in three passes
     *
     * The card used to be one flat near-opaque slate rectangle, which next to
     * the scrollback's panes read as a different app's dialog dropped on top
     * of this one. It is now the same recipe `HistoryListView.glass()` uses,
     * for the same reason: a translucent base, a top-to-bottom white film
     * standing in for light falling across a pane, and a hairline rim one step
     * brighter than the film so the pane has an edge that catches that light.
     *
     * There is no real blur behind it, and there cannot be one. Android 12's
     * shape-aware `Window.setBackgroundBlurRadius` needs a `Window`, which a
     * view added through `WindowManager.addView` does not have; the one that
     * works here, `LayoutParams.setBlurBehindRadius`, blurs a hard *rectangle*
     * behind the whole window and would leave a blurred oblong halo around a
     * rounded card with a tail hanging off it. What actually reads as glass on
     * a dark surface is the film and the rim, not the blur.
     *
     * The base stays in the high 0x80s of alpha for the plainest of reasons:
     * with nothing blurred behind it, the card floats over whatever the user
     * was reading, and any more transparency than this turns the question into
     * a puzzle. Note this is *pixel* alpha inside a TRANSLUCENT window — the
     * window's own alpha is untouched, which matters because Android's
     * untrusted-touch accounting reads that one and silently drops injected
     * gestures once our overlays pass 0.80 combined.
     *
     * ## One silhouette, not two shapes
     *
     * Body and tail are unioned into a single path before anything is painted.
     * Filling them separately was fine for a flat colour, but a film gradient
     * would restart inside the tail and the rim would draw a line straight
     * across the card's bottom edge, cutting the tail off from the card it
     * hangs from.
     */
    private class TailLinearLayout(ctx: Context) : LinearLayout(ctx) {

        var tailX: Int = -1
            set(value) {
                field = value
                silhouette = null
            }

        var cornerRadius: Int = 0
            set(value) {
                field = value
                silhouette = null
            }

        private val density = resources.displayMetrics.density

        private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BASE }
        private val filmPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = RIM
            strokeWidth = (density * 0.7f).coerceAtLeast(1f)
        }

        /** Rebuilt on the first draw at a new size; see [tailX]/[cornerRadius]. */
        private var silhouette: Path? = null

        /** How far the tail reaches below the card's content box, px. */
        fun tailLength(): Int = (14 * density).toInt().coerceAtLeast(12)

        /**
         * The tail draws below `height`, and a view cannot draw outside its
         * own bounds without being clipped — a WRAP_CONTENT window measured
         * at the content height sheared the tail off entirely (verified on
         * device: card bottom edge, then desktop pixels). Growing the
         * measured height by exactly the tail's reach gives it room.
         */
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            if (tailX >= 0) {
                setMeasuredDimension(measuredWidth, measuredHeight + tailLength())
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
            super.onSizeChanged(w, h, oldW, oldH)
            silhouette = null
            if (h > 0) {
                // Light falls across the pane from the top, so the film has to
                // run the card's real height — a shader can only be built once
                // that height is known, which is why this isn't a constant.
                filmPaint.shader = LinearGradient(
                    0f, 0f, 0f, h.toFloat(),
                    FILM_TOP, FILM_BOTTOM,
                    Shader.TileMode.CLAMP,
                )
            }
        }

        override fun dispatchDraw(canvas: Canvas) {
            val shape = silhouette ?: buildSilhouette().also { silhouette = it }
            canvas.drawPath(shape, basePaint)
            canvas.drawPath(shape, filmPaint)
            canvas.drawPath(shape, rimPaint)
            super.dispatchDraw(canvas)
        }

        private fun buildSilhouette(): Path {
            val t = if (tailX >= 0) tailLength() else 0
            // Inset by half the rim so the stroke lands inside the view. A
            // centred stroke on the view's own edge loses its outer half to
            // clipping, which reads as a rim that is there on three sides and
            // thinner on the fourth.
            val inset = rimPaint.strokeWidth / 2f
            val body = Path().apply {
                addRoundRect(
                    inset, inset,
                    width - inset, (height - t) - inset,
                    cornerRadius.toFloat(), cornerRadius.toFloat(),
                    Path.Direction.CW,
                )
            }
            if (t <= 0) return body
            val half = t * 0.62f
            val tail = Path().apply {
                // The base is a few pixels *inside* the body, not level with
                // its bottom edge. Level, the two shapes meet along a single
                // line, the union keeps that line, and the rim drew itself
                // straight across the tail's mouth — a triangle taped to the
                // card rather than part of it. A real overlap gives the union
                // something to dissolve.
                val baseY = (height - t) - inset - OVERLAP * density
                moveTo(tailX - half, baseY)
                lineTo(tailX + half, baseY)
                lineTo(tailX.toFloat(), height - inset)
                close()
            }
            body.op(tail, Path.Op.UNION)
            return body
        }

        private companion object {
            /** How far the tail's base reaches up into the card body, dp. */
            const val OVERLAP = 2f

            /**
             * Black, and nearly opaque. [Glass.CARD_SOLID] is the fill the
             * settings card falls back to when the compositor refuses to blur
             * behind it, which is permanently this card's situation — so it is
             * the same value for the same reason, not a coincidence worth
             * re-deciding. See the class KDoc on why not lower.
             */
            val BASE = Color.parseColor(Glass.CARD_SOLID)

            // Alpha in the low teens is the whole trick: high enough to lift
            // the pane off what is behind it, low enough that the base colour
            // still shows through, which is the only thing making it read as
            // translucent rather than as grey paint.
            val FILM_TOP = 0x2EFFFFFF
            val FILM_BOTTOM = 0x0CFFFFFF
            val RIM = 0x3DFFFFFF
        }
    }
}
