package net.kuafuai.andee.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import net.kuafuai.andee.R
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.device.PermissionRequestActivity
import net.kuafuai.andee.device.SelfCheck
import net.kuafuai.andee.i18n.AppLocale
import java.util.concurrent.Executors

/**
 * The self-check list, as a card — [SelfCheck]'s only renderer.
 *
 * ### Why a card and not the page it used to be
 *
 * The entry point is `✓`, one glyph away from `⚙` in the assistant's control
 * bar, and that is the whole argument: those two buttons open *our own
 * surfaces* rather than acting on the screen, so they have to open the same
 * kind of thing. The settings sheet is a centred `Glass.card`; a self-check
 * that folded the assistant away and took the entire screen — with a background
 * image of its own — made two adjacent buttons behave like two different
 * products. It is now the same card chrome: same fill, same radius, same
 * header, same footer.
 *
 * ### Two hosts, one view
 *
 * [host] is null for the assistant's own case: this class creates the window
 * (`TYPE_APPLICATION_OVERLAY`) exactly as [SettingsUi] does, and the service
 * owns it. Non-null means somebody else is placing the card —
 * [SelfCheckActivity], which is an Activity and has a `stage` to put it in.
 *
 * The second host is not symmetry for its own sake. **The Activity is the one
 * surface in this app that runs while the assistant does not**, so when the
 * accessibility service is off, or the overlay grant is missing, or the user
 * has just reinstalled, there is no window this class could add itself to and
 * the launcher icon is the only door. Rendering both hosts from one builder is
 * what keeps the look identical on the day the user is looking at the broken
 * one.
 *
 * The difference between the hosts is only ever *where the card goes* and
 * *whether we may ask the compositor to blur*:
 *
 *  * **Window** — [Glass.frost] decides the fill, and the answer is whatever
 *    the device agreed to, exactly like [SettingsUi].
 *  * **Activity** — the fill is the *frosted* one unconditionally, because the
 *    Activity is already showing a blurred, scrimmed [Backdrop]; the blur
 *    behind this card is real, we simply did not have to ask for it. There is
 *    no window of ours to hand to [Glass.frost] and nothing it could add.
 *
 * ### The rows are [Glass.panel] here, and that is a correction
 *
 * They were [Glass.GLASS] while this list was bare on the backdrop. Inside a
 * card the arithmetic inverts: `GLASS` is 12% white, which on a 60%-black card
 * is a grey box, where `PANEL`'s 6% is the film that was measured for exactly
 * this — a group of rows *on* a card. A surface's fill is decided by what is
 * directly underneath it and by nothing else.
 *
 * ### Order of operations
 *
 * [SelfCheck.local] first — no network, instant — then the two probes on a
 * worker. A card that waits on a 6-second socket timeout before drawing its
 * first pixel reads as the thing being broken, and this is the card the user
 * opens *because* something is broken.
 *
 * ### Language
 *
 * [LangToggle] in the header, through the same [VoiceConfig] key the settings
 * sheet writes. [onLocaleChanged] is how everyone *else* hears about it, and in
 * the window host that is load-bearing: the ball, the card and the scrollback
 * are views the service built, so a language change that only repainted this
 * card would leave the user with two languages on screen the moment they closed
 * it. A host with no overlays to tell can spend the callback on its own
 * bookkeeping instead — see `SelfCheckActivity`, which uses it to remember what
 * is on screen. And [rebuild] re-renders [last] rather than re-running the
 * probes — see [SelfCheck.Finding] for why that is correct rather than a
 * shortcut.
 */
class SelfCheckUi(
    private val context: Context,
    private val onDismiss: () -> Unit,
    private val onOpenSettings: (() -> Unit)? = null,
    /** Fired when this card's own picker moved the language — see the class doc. */
    private val onLocaleChanged: () -> Unit = {},
    /**
     * Fired just before the user is sent to another screen, so the host can get
     * out of the way. See [apply] for the whole argument.
     *
     * Default no-op, and that is the Activity host's correct answer rather than
     * a shrug: there the card *is* an Activity, so the system draws whatever it
     * starts on top of it and there is nothing to yield. Only the window host —
     * where this card and the assistant's card behind it are both overlays —
     * has anything to do, and it is the only host that passes this.
     */
    private val onYieldScreen: () -> Unit = {},
    /** Non-null ⇒ that host places the card and this class owns no window. */
    private val host: FrameLayout? = null,
) {

    private companion object {
        const val TAG = "Body"

        /** Settings' number, for the same reason: the card is centred, so it reads on all four sides. */
        const val GUTTER_DP = 20
        const val CARD_RADIUS_DP = 28

        val OK_GREEN = android.graphics.Color.parseColor(Glass.OK)
        val WARN_AMBER = android.graphics.Color.parseColor(Glass.WARN)

        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }

    /** Null in [host] mode — the whole point of which is that there is no window. */
    private val wm: WindowManager? =
        if (host == null) context.getSystemService(Context.WINDOW_SERVICE) as WindowManager else null

    private val main = Handler(Looper.getMainLooper())

    /**
     * The network half, one thread, created on demand.
     *
     * Never the main thread: [SelfCheck.run] blocks on a socket, deliberately,
     * because that is the only way to tell "wrong URL" from "wrong key" from
     * "wrong model name".
     */
    private val prober = Executors.newSingleThreadExecutor { r ->
        Thread(r, "self-check").apply { isDaemon = true }
    }

    private var root: View? = null
    private var closing: View? = null

    /** Whether the compositor agreed to blur. Decides the fill — see [Glass.frost]. */
    private var frosted = false

    /** Always a `var`, never `by lazy`. See the class doc and [repaintForLanguage]. */
    private var lctx: Context = context

    private var hero: LinearLayout? = null
    private var rows: LinearLayout? = null

    /**
     * The last list drawn, kept so a language switch can redraw it without
     * re-running the probes.
     *
     * Reusing it is not a shortcut, it is the reason [SelfCheck.Finding] holds
     * `@StringRes Int` and a `detailArgs` list rather than finished sentences:
     * the finding is language-free and the sentence is rendered from it at draw
     * time, so redrawing the *same* list in a new language is correct. Had the
     * findings carried strings, changing language would cost two network
     * round-trips to say the same thing in different words.
     */
    private var last: List<SelfCheck.Finding>? = null

    /**
     * Whether there is a settings sheet to open at all — the difference between
     * 打开设置 opening a card and 打开设置 doing nothing.
     *
     * Asked of the caller rather than inferred from the `accessibility` row,
     * which is what this used to do. That inference was true while only the
     * service could raise our sheet; it stopped being true when
     * [SelfCheckActivity] learned to raise its own, and an inference is exactly
     * the wrong shape for a capability its owner already knows. The rows this
     * gates — 大脑 and 语音密钥 — are the two a user configures *before* the
     * assistant works, so suppressing their one button on a page reached with
     * the service off is suppressing it in the case it exists for.
     */
    private val canOpenSettings = onOpenSettings != null

    fun isShowing(): Boolean = root != null

    // ---- Lifetimes ----

    fun show() {
        if (host == null) showInWindow() else showInHost()
    }

    private fun showInHost() {
        val stage = host ?: return
        if (root != null) return
        lctx = AppLocale.wrap(context)
        // The Activity is already a blurred backdrop, so the card takes the
        // frosted fill without asking anyone: there is no window here for
        // Glass.frost to edit, and nothing it could add that the backdrop has
        // not already done.
        frosted = true
        val v = build()
        // The same measured size the window host would use, not MATCH_PARENT
        // minus a gutter: those two are nearly the same number and not the same
        // number, and "nearly" is how a card the user opens from two identical
        // buttons ends up two different sizes. See [cardWidth] and [cardHeight].
        stage.addView(v, FrameLayout.LayoutParams(cardWidth(), cardHeight(), Gravity.CENTER))
        root = v
        OwnCard.shown()
    }

    private fun showInWindow() {
        val wm = wm ?: return
        // A re-open during the closing animation: drop the outgoing card now
        // rather than letting two of them share the screen.
        closing?.let { runCatching { wm.removeView(it) }; OwnCard.hidden() }
        closing = null
        if (root != null) return

        lctx = AppLocale.wrap(context)
        val p = WindowManager.LayoutParams(
            cardWidth(), cardHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // No FLAG_NOT_FOCUSABLE: nothing here needs the keyboard, but this
            // card can open the settings sheet on top of itself and the two
            // must not disagree about who owns focus. Same params as SettingsUi,
            // for the same reason it is a card at all.
            0,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        // Before build(), because the answer picks the card's fill.
        frosted = Glass.frost(context, wm, p)
        val v = build()
        try {
            v.hideFromAccessibility()
            wm.addView(v, p)
            root = v
            OwnCard.shown()
            Glass.enter(v)
        } catch (t: Throwable) {
            Log.w(TAG, "self-check card refused", t)
        }
    }

    /**
     * Close, and hand the news to [onDismiss] once the card is off screen.
     *
     * The two hosts animate differently on purpose. In a window the card has to
     * sink under its own power, and [Glass.exit] guarantees `done` runs exactly
     * once even if the animation is cancelled — what hangs off it is `removeView`
     * plus the [OwnCard] bookkeeping. On the Activity's stage there is nothing
     * to animate: `leave()` is already growing the whole stage away underneath
     * this, and a card shrinking inside a shrinking screen is two exits.
     *
     * ### [onDismiss] only from the card that is still the current one
     *
     * It used to fire outside the guard, and the guard is not decoration:
     * `closing !== v` means a *newer* card took this one's place while the exit
     * animation was still running — [showInWindow] drops the outgoing view and
     * clears [closing] itself, which is exactly what that branch is for. The new
     * card has already told the host it exists; letting the outgoing one also
     * announce its closing clears the *new* card's record instead, and the host
     * then believes no card is up while one is on screen.
     *
     * Narrow either way, but this change widened it: `hide()` no longer comes
     * only from a user pressing ✕ — [onYieldScreen] has the host close the card
     * on the way out to the browser. Fold, reopen the check, and the stale exit
     * would land after the second card was up.
     */
    fun hide() {
        val v = root ?: return
        root = null
        val stage = host
        if (stage != null) {
            runCatching { stage.removeView(v) }
            OwnCard.hidden()
            onDismiss()
            return
        }
        val wm = wm ?: return
        closing = v
        Glass.exit(v) {
            if (closing === v) {
                runCatching { wm.removeView(v) }
                closing = null
                OwnCard.hidden()
                onDismiss()
            }
        }
    }

    /**
     * Released with the window. Safe to call twice, and safe to call after [hide].
     *
     * It is also the backstop for [OwnCard], and that is not tidiness: the count
     * is what tells the brain whether one of our own cards is covering the
     * screen, and a host that goes away *without* [hide] — an Activity being
     * destroyed rather than closed — would leave the process permanently
     * convinced a card is still up. [hide] clears [root] as its first act, so
     * the two do not double-count; see [Glass.exit] for why that count is the
     * thing that must not leak.
     */
    fun destroy() {
        prober.shutdownNow()
        if (root != null) {
            root = null
            OwnCard.hidden()
        }
    }

    // ---- Data ----

    /** Paint what is knowable now, then fill in the probes behind it. */
    fun start() {
        render(SelfCheck.local(context))
        probe()
    }

    private fun probe() {
        val app = context.applicationContext
        prober.execute {
            val found = runCatching { SelfCheck.run(app) }.getOrNull() ?: return@execute
            main.post {
                // The card may have been closed while the socket was open.
                if (root == null) return@post
                render(found)
            }
        }
    }

    /**
     * Draw the list, problems first.
     *
     * The order [SelfCheck.run] returns is the order the *probe* has to run in
     * (network before the two things that need it), which is not the order a
     * reader wants. So the rows are regrouped here — everything needing
     * attention at the top, then the facts — and the facts get their own
     * heading ([R.string.check_page_notes_lead]) so a green row is never read
     * as a fault the user has to go and fix.
     */
    private fun render(found: List<SelfCheck.Finding>) {
        last = found
        val attention = found.filter { SelfCheck.needsAttention(it) }
        val rest = found.filterNot { SelfCheck.needsAttention(it) }

        hero?.removeAllViews()
        hero?.addView(summary(attention.size))
        // The one case where the card has to say it cannot help: our sheet is an
        // overlay either way, so with the overlay grant missing there is nowhere
        // for 打开设置 to go. Say that instead of showing a button that would do
        // nothing — and note the row itself has no button either, see
        // [fixButton]. Only reachable in host mode: the window host is already
        // drawing overlays, so if it is drawing this at all the grant is there.
        if (!canOpenSettings) hero?.addView(note(lctx.getString(R.string.check_page_no_overlay)))

        rows?.removeAllViews()
        attention.forEach { rows?.addView(row(it)) }
        if (rest.isNotEmpty()) {
            if (attention.isNotEmpty()) rows?.addView(sectionHeading(R.string.check_page_notes_lead))
            rest.forEach { rows?.addView(row(it)) }
        }
    }

    // ---- Language ----

    /**
     * Redraw in whatever language is now saved, because this card's own picker
     * was used.
     */
    private fun onLanguageChanged() {
        repaintForLanguage()
        onLocaleChanged()
    }

    /**
     * The same, for a change made *elsewhere* — in the window host the settings
     * sheet opens on top of this card, so its picker is reachable while this one
     * is on screen.
     *
     * No [onLocaleChanged] call: whoever told us already told the service, and
     * bouncing it back would rebuild the ball twice.
     */
    fun repaintForLanguage() {
        if (root == null) return
        lctx = AppLocale.wrap(context)
        rebuild()
    }

    /**
     * Swap the whole card for a freshly built one, in place, and paint it.
     *
     * Same trick as [SettingsUi.rebuild], including reusing the old
     * `LayoutParams` and leaning on the fact that the card was up before and is
     * up after (no [OwnCard] bookkeeping — counting it again would leave
     * `annotate` permanently convinced a card is open). It cross-fades rather
     * than cutting, because the swap is `removeView` + `addView` on a window and
     * a hard cut shows one frame of whatever is behind the card.
     *
     * ### Why the redraw is in here, and not one line after the caller's return
     *
     * [build] only assembles the chrome: it leaves [hero] and [rows] empty and
     * points those two fields at the *new* card. Whoever fills them has to run
     * after `build()` — and in the **window host that means after this function
     * has already returned**, because the new card is built inside
     * [Glass.fadeOut]'s end action, a fifth of a second later. Drawing from the
     * call site therefore wrote the whole list into the *outgoing* card's
     * [hero] and [rows], which were then removed with it, and the replacement
     * arrived as a card with its header, its footer and nothing between them.
     * Reported on the tablet as: switch language on the assistant's card, watch
     * the list disappear, close the card — everything normal again, because the
     * next open goes through [showInWindow], which is synchronous.
     *
     * The hosted path builds synchronously, so the distinction does not exist
     * there, and that is exactly why the fault survived: it is invisible on the
     * host the user reaches from the launcher and only fires on the one reached
     * from `✓`. Keeping `again` on this side of the branch is what makes the two
     * hosts incapable of disagreeing again.
     *
     * `again` is the list already drawn and not a fresh probe: see [last] for why
     * re-rendering the same findings in a new language is correct.
     */
    private fun rebuild() {
        val old = root ?: return
        val again = last ?: SelfCheck.local(context)
        val stage = host
        if (stage != null) {
            // No cross-fade on a stage: the new card goes in at the same size in
            // the same frame, and the backdrop behind it never moves, so a fade
            // here would only be a flicker of the Activity's own background.
            val v = build()
            val i = stage.indexOfChild(old)
            runCatching { stage.removeView(old) }
            if (i >= 0) stage.addView(v, i, old.layoutParams) else stage.addView(v, old.layoutParams)
            root = v
            render(again)
            return
        }
        val wm = wm ?: return
        Glass.fadeOut(old) {
            if (root !== old) return@fadeOut
            val v = build()
            runCatching {
                v.hideFromAccessibility()
                val p = old.layoutParams as WindowManager.LayoutParams
                wm.removeView(old)
                wm.addView(v, p)
                root = v
                Glass.fadeIn(v)
                render(again)
            }
        }
    }

    // ---- Build ----

    private fun build(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.card(context, dp(CARD_RADIUS_DP), frosted)
            // So the sheen and the panels stay inside the rounded corners.
            clipToOutline = true
        }

        card.addView(header(), matchWrap())

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            // The card already has an edge; a scrollbar track on top of glass
            // is one line too many.
            isVerticalScrollBarEnabled = false
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Same 24dp as the settings form: this is the same card, and two
            // cards with the same chrome and different insets read as one of
            // them being broken.
            setPadding(dp(24), dp(4), dp(24), dp(24))
        }

        hero = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(hero, matchWrap())

        rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(rows, matchWrap())

        scroll.addView(body, matchWrap())
        card.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        card.addView(footer(), matchWrap())
        return card
    }

    private fun header(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(22), dp(18), dp(14))
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(context).apply {
            text = lctx.getString(R.string.check_page_title)
            // Settings' 26sp, so the two cards' titles are the same size.
            textSize = Glass.Type.DISPLAY
            setTextColor(Color.parseColor(Glass.TITLE))
            letterSpacing = 0.01f
        })
        titles.addView(TextView(context).apply {
            text = lctx.getString(R.string.check_page_lead)
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(Glass.MUTED))
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(
            LangToggle(context) { onLanguageChanged() },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(12) },
        )
        row.addView(closeButton(), LinearLayout.LayoutParams(dp(36), dp(36)))
        return row
    }

    private fun closeButton(): View = TextView(context).apply {
        text = "✕"
        textSize = Glass.Type.BODY
        setTextColor(Color.parseColor(Glass.SECONDARY))
        gravity = Gravity.CENTER
        background = Glass.panel(context, dp(18))
        isClickable = true
        isFocusable = true
        Glass.pressable(this)
        contentDescription = lctx.getString(R.string.check_page_done)
        setOnClickListener { hide() }
    }

    private fun footer(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(18))
        }
        // Left, and deliberately NOT a filled button: this must not read as one
        // of the two ways out of the card. It is the one control worth having on
        // a card whose whole subject is state the user changes in *other* apps —
        // without it, checking whether a fix took means closing this and coming
        // back, which turns a two-tap fix into a round trip.
        row.addView(
            textButton(lctx.getString(R.string.check_page_recheck), Glass.ACCENT) {
                // Local first so the tap has a visible effect on this frame; the
                // probes are about to hold this card open for up to ~6 s.
                render(SelfCheck.local(context))
                probe()
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        row.addView(button(lctx.getString(R.string.check_page_done), filled = true) { hide() })
        return row
    }

    /**
     * Settings' bare text button, down to the vertical padding.
     *
     * The one thing not copied is its `gravity`: [SettingsUi.textButton] centres
     * its label because the cell it sits in is squeezed between two pills on the
     * right, and here the same weight lands the text in the middle of the card
     * with nothing on either side of it. Start-aligned is the same button in the
     * only position it has here.
     */
    private fun textButton(label: String, tint: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = Glass.Type.BODY
            setTextColor(Color.parseColor(tint))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(13), dp(4), dp(13))
            isClickable = true
            isFocusable = true
            Glass.pressable(this)
            setOnClickListener { onClick() }
        }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit): TextView {
        val radius = dp(24)
        return TextView(context).apply {
            text = label
            textSize = Glass.Type.BODY
            setTextColor(Color.parseColor(if (filled) Glass.TITLE else Glass.LABEL))
            gravity = Gravity.CENTER
            background =
                if (filled) Glass.tinted(context, radius) else Glass.panel(context, radius)
            setPadding(dp(26), dp(13), dp(26), dp(13))
            isClickable = true
            isFocusable = true
            Glass.pressable(this)
            setOnClickListener { onClick() }
        }
    }

    // ---- One row ----

    /**
     * A finding: a dot, what was checked, what was found, and the way out.
     *
     * Two lines rather than one because the title alone ("麦克风") tells the
     * user nothing they can act on — the second line is the one that says the
     * permission is off, that the ball will therefore never wake, and that the
     * button beside it fixes that. See the `check_detail_*` strings.
     */
    private fun row(f: SelfCheck.Finding): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.panel(context, dp(14))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) }
        }

        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor(f.level))
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(10)
            }
        })
        head.addView(TextView(context).apply {
            text = lctx.getString(f.title)
            setTextColor(Color.parseColor(Glass.TITLE))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.TITLE)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        fixButton(f)?.let { head.addView(it) }
        card.addView(head)

        card.addView(TextView(context).apply {
            text = AppLocale.str(context, f.detail, *f.detailArgs.toTypedArray())
            setTextColor(Color.parseColor(Glass.SECONDARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.BODY)
            setPadding(0, dp(6), 0, 0)
        })

        // The one fix with no button: show the command, and be explicit that it
        // is an adb errand. `setTextIsSelectable` because the user has to get
        // this string onto a computer, and retyping a 60-character package name
        // from a screenshot is how a working instruction turns into a broken one.
        (f.fix as? SelfCheck.Fix.Adb)?.let { adb ->
            card.addView(TextView(context).apply {
                text = lctx.getString(R.string.check_fix_adb_lead)
                setTextColor(Color.parseColor(Glass.MUTED))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.CAPTION)
                setPadding(0, dp(10), 0, dp(4))
            })
            card.addView(TextView(context).apply {
                text = adb.command
                setTextColor(Color.parseColor(Glass.LABEL))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.CAPTION)
                typeface = Typeface.MONOSPACE
                // Direct call, not `isTextSelectable = true`: TextView's setter
                // is `setTextIsSelectable` and its getter `isTextSelectable`,
                // which Kotlin does not pair into a var — the assignment parses
                // as a read of the getter and fails.
                setTextIsSelectable(true)
                background = Glass.well(context, dp(8))
                setPadding(dp(10), dp(8), dp(10), dp(8))
            })
        }
        return card
    }

    private fun dotColor(level: SelfCheck.Level): Int = when (level) {
        SelfCheck.Level.OK -> OK_GREEN
        SelfCheck.Level.NOTE -> Color.parseColor(Glass.MUTED)
        SelfCheck.Level.WARN -> WARN_AMBER
        SelfCheck.Level.FAIL -> Color.parseColor(Glass.DANGER)
    }

    /**
     * The way out, or nothing when there is not one.
     *
     * Text rather than a filled button: a row can have zero or one door, and a
     * column of gradient buttons inside a card reads as a menu. The accent
     * colour is this app's established "this is tappable" mark.
     */
    private fun fixButton(f: SelfCheck.Finding): TextView? {
        // Ours, and unreachable: see [canOpenSettings]. The note above the list
        // has already said so, and a button that does nothing is worse than no
        // button — it is the exact failure this list exists to avoid.
        if (f.fix is SelfCheck.Fix.OurSettings && !canOpenSettings) return null
        val label = when (val fix = f.fix) {
            // Zero means "the action's own word"; a fix instance gets to
            // override it when 去开启 would name the wrong errand — see
            // SelfCheck.Fix.Screen.label.
            is SelfCheck.Fix.Screen ->
                if (fix.label != 0) fix.label else R.string.check_fix_settings

            is SelfCheck.Fix.Link -> R.string.check_fix_download
            is SelfCheck.Fix.Grant -> R.string.check_fix_allow
            is SelfCheck.Fix.OurSettings -> R.string.check_fix_app_settings
            else -> return null
        }
        return TextView(context).apply {
            text = lctx.getString(label)
            setTextColor(Color.parseColor(Glass.ACCENT))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.BODY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(6), 0, dp(6))
            isClickable = true
            setOnClickListener { apply(f) }
        }
    }

    // ---- Doors ----

    /**
     * Every fix is a `startActivity` — nothing here closes a gap by itself.
     *
     * ### The card gets out of the way first, and why it has to
     *
     * In the window host this card is a `TYPE_APPLICATION_OVERLAY` sized to 98%
     * × 96% of the display, and **an overlay outranks every Activity on the
     * device**. So the browser 去下载 opened came up *underneath* the card that
     * had just asked the user to look at it, and so did the system Settings page
     * 去开启 opens. Reported from the field exactly as it looks: "点击去下载ADB
     * 的时候，应该退出全屏，不然挡住浏览器了". Nothing about the destinations is
     * wrong — they are ordinary Activities and they do come up. They just come up
     * behind us.
     *
     * So every door but one tells the host to yield first — see
     * [onYieldScreen]. It is deliberately *every* door and not just the browser:
     * 去开启 and 允许 are the same mistake against the same kind of window, and
     * fixing only the one that got reported would leave two dead buttons behind
     * a live one.
     *
     * [SelfCheck.Fix.OurSettings] is the exception, and it is the reason the
     * yield is here and not in [launch]: that sheet is an overlay of ours too,
     * and the design is that it comes up **over** this card so the list is still
     * behind it to return to. Yielding for it would close the card the user is
     * meant to come back to.
     *
     * The card itself is not closed here — delivering the yield is the host's
     * job, because only the host knows whether there is anything to close. On
     * the Activity host there is not: that card *is* an Activity, so whatever it
     * starts is drawn on top of it by the system already. See [onYieldScreen].
     */
    private fun apply(f: SelfCheck.Finding) {
        val fix = f.fix
        if (fix !is SelfCheck.Fix.OurSettings) {
            // Only if there is somewhere to go. Yielding and failing is the one
            // outcome with nothing on screen: the card is gone and so is the
            // page it promised. Cheap to rule out — see [openable] — and this is
            // not hypothetical, a HONOR really has Settings screens with no
            // Activity behind the action.
            if (!openable(fix)) {
                toastNoScreen()
                return
            }
            onYieldScreen()
        }
        when (fix) {
            // Both of these leave the device, and both are already spelled out by
            // [intentFor] — the same call [openable] asked, so what was checked
            // and what gets launched can never be two different Intents. 去下载
            // is the link: a browser, not Settings, and the user comes back
            // through wherever they were with 重新检查 to tell them whether it
            // took.
            is SelfCheck.Fix.Screen, is SelfCheck.Fix.Link -> launch(intentFor(fix))

            is SelfCheck.Fix.Grant -> launch(
                Intent(context, PermissionRequestActivity::class.java)
                    .putExtra("permissions", fix.permissions.toTypedArray())
            )

            is SelfCheck.Fix.OurSettings -> onOpenSettings?.invoke()

            is SelfCheck.Fix.Adb, SelfCheck.Fix.Nothing -> Unit
        }
    }

    /**
     * Whether [fix] has an Activity on this device at all — asked **before**
     * [onYieldScreen] closes the card, so the two can never disagree.
     *
     * Everything this card cannot open is either its own ([Fix.OurSettings],
     * [Fix.Grant]) or has nothing to open ([Fix.Adb], [Fix.Nothing]), so only
     * the two that leave the device are worth asking about.
     *
     * `queryIntentActivities` rather than `resolveActivity`: the stricter call
     * wants a default-browsing handler, and a device with several browsers and
     * no default set would read as "nowhere to go" for 去下载 — refusing to
     * yield on the very button this exists to serve. When the query itself
     * fails the answer is `true`: that defers to [launch]'s own failure toast,
     * which is the honest fallback rather than a second guess.
     */
    private fun openable(fix: SelfCheck.Fix): Boolean = when (fix) {
        is SelfCheck.Fix.Screen, is SelfCheck.Fix.Link ->
            runCatching {
                context.packageManager
                    .queryIntentActivities(intentFor(fix), 0)
                    .isNotEmpty()
            }.getOrDefault(true)

        else -> true
    }

    /**
     * The Intent [apply] will hand to [launch]; one definition, asked twice.
     *
     * Only the two that leave the device are spelled out. The `else` is an empty
     * Intent rather than a throw because no caller reaches it — [apply] routes
     * the other three fixes to their own branches and [openable] short-circuits
     * before asking — but an empty Intent resolving to nothing reads as "no
     * Activity for a fix that never wanted one", while a throw here would take
     * the card down for a case that cannot happen.
     */
    private fun intentFor(fix: SelfCheck.Fix): Intent = when (fix) {
        is SelfCheck.Fix.Screen -> Intent(fix.action).apply {
            fix.data?.let { data = Uri.parse(it) }
            fix.extras.forEach { (name, value) -> putExtra(name, value) }
        }

        is SelfCheck.Fix.Link -> Intent(Intent.ACTION_VIEW, Uri.parse(fix.url))

        else -> Intent()
    }

    private fun toastNoScreen() {
        runCatching {
            Toast.makeText(context, lctx.getString(R.string.check_fix_no_screen), Toast.LENGTH_LONG)
                .show()
        }
    }

    /**
     * A Settings screen the ROM may not have — and when it does not, **say so**.
     *
     * The `runCatching` used to be the whole story, and it is how this app
     * shipped a dead button: on a HONOR, `ACTION_NOTIFICATION_LISTENER_SETTINGS`
     * resolves to no activity at all, so 去开启 swallowed an
     * `ActivityNotFoundException` and the user reported exactly what that
     * looks like from the outside — "点了没反应". A silent no-op on the one
     * control that exists to fix something is worse than no button, which is the
     * rule this whole card is built on. So the failure gets a voice.
     *
     * This is the second line of defence, not the first: [openable] already
     * refuses to yield when there is nowhere to go. It still has to exist,
     * because the check and the launch are separated by the card's exit
     * animation — long enough for the answer to change. And unlike
     * [openable]'s refusal, this one arrives after the card is gone, so the
     * toast is the only thing left to say.
     */
    private fun launch(intent: Intent) {
        // Only when there is no Activity of ours in play: from a Service Context
        // the flag is mandatory, and from an Activity it would put somebody
        // else's settings screen in a task of its own.
        if (host == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }.onFailure {
            Log.w(TAG, "self-check: no activity for ${intent.action}", it)
        }.isSuccess
        if (!ok) toastNoScreen()
    }

    // ---- Small parts ----

    private fun summary(problems: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = if (problems == 0) {
                lctx.getString(R.string.check_page_ok_title)
            } else {
                lctx.getString(R.string.check_page_issues, problems)
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.TITLE)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(if (problems == 0) Glass.OK else Glass.DANGER))
        })
        // Only in the clean case: a green headline with no sentence under it
        // leaves the user wondering whether the list below is part of the
        // problem, and check_page_ok_note is the sentence that answers that.
        if (problems == 0) {
            addView(TextView(context).apply {
                text = lctx.getString(R.string.check_page_ok_note)
                setTextColor(Color.parseColor(Glass.SECONDARY))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.BODY)
                setPadding(0, dp(4), 0, dp(12))
            })
        }
    }

    private fun sectionHeading(id: Int): TextView = TextView(context).apply {
        text = lctx.getString(id)
        setTextColor(Color.parseColor(Glass.MUTED))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.CAPTION)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun note(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.parseColor(Glass.SECONDARY))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.BODY)
        background = Glass.panel(context, dp(12))
        setPadding(dp(14), dp(12), dp(14), dp(12))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun dp(v: Int): Int = Glass.dp(context, v)

    /**
     * Preferred size, capped to what the screen actually has.
     *
     * Settings' numbers, deliberately. Two cards opened from adjacent buttons
     * have to be the same size or the second one reads as a different product
     * arriving; see [SettingsUi.cardWidth] for why these are not the old
     * tablet-phone constants.
     *
     * The two hosts ask two different objects for the screen — the window host
     * has a `WindowManager` and the Activity host does not — so the *measurement*
     * is factored out here and neither branch does its own arithmetic.
     */
    private fun cardWidth(): Int {
        val screen = screenW()
        return minOf(screen - dp(2 * GUTTER_DP), (screen * 0.98f).toInt())
    }

    private fun cardHeight(): Int {
        val screen = screenH()
        return minOf(screen - dp(2 * GUTTER_DP), (screen * 0.96f).toInt())
    }

    private fun screenW(): Int = Glass.usableSize(context).first

    private fun screenH(): Int = Glass.usableSize(context).second
}
