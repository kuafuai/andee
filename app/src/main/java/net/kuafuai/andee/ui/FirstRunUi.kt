package net.kuafuai.andee.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
 * First-run onboarding wizard: a stepped flow that turns a blank device into
 * one ready to use.
 *
 * ### What this is not
 *
 * Not a second hand-written checklist. This reuses [SelfCheck.Finding] as its
 * only data source — [SelfCheck.kt]'s own KDoc warns that a second list would
 * drift from the first within a week, and the whole point of having one source
 * of truth is that nothing can contradict it. The wizard *filters* and
 * *reorders* the findings; it does not invent its own.
 *
 * ### The steps, and why they are in this order
 *
 * Six: accessibility → overlay → key → wake → look → first sentence. That is
 * dependency order — each uses the ones before it — and it is also the
 * ascending cost: the cheapest steps come first, so a user who bails out still
 * has the easier half done. The first three are [SelfCheck] rows; the last
 * three are instructions + a pill that opens something this service already
 * owns ([net.kuafuai.andee.wake.WakeEnrollUi], [net.kuafuai.andee.device.LookActivity],
 * and [net.kuafuai.andee.ui.FloatingWindowUi]'s mic).
 *
 * ### Two doors in
 *
 *  - **Auto**, from [net.kuafuai.andee.ScreenBodyService.onServiceConnected]
 *    when the config is blank: this is a device nobody has touched, so the
 *    wizard is a better first screen than a mute ball with no explanation.
 *  - **Manual**, from the existing self-check card (long-press ✓ on the card,
 *    not the button — a second deliberate step, so a stray tap on the check
 *    list does not blow the existing config away).
 *
 * Either way, it is only an *offer* — the first card asks whether to begin, and
 * 取消 lands you at the ball with no config lost. No auto-advancing; the user
 * taps 下一步 on each step once the Android permission screen they just came
 * back from is green or their settings field is filled.
 *
 * ### The two hosts
 *
 * Like [SelfCheckUi] and [SettingsUi], this is built for both: [host] null
 * means the service creates a window, non-null means the caller places it. The
 * hosted path is unreachable at the moment — the launcher Activity opens the
 * self-check list, not this — but the split is kept because the Activity is
 * where an interrupted wizard would land if service restarts ever resume it,
 * and because "cards match self-check" was the whole item 4 design.
 *
 * ### Yielding
 *
 * [onYieldScreen] is the same contract [SelfCheckUi] uses, and for the same
 * reason: the window host is an overlay, so Android Settings screens come up
 * behind the card unless we get out of the way. See [SelfCheckUi.apply].
 *
 * ### Language
 *
 * [LangToggle] in the header, same place the settings card and the self-check
 * card put theirs. [onLocaleChanged] announces the change to the service, and
 * [repaintForLanguage] is how the service tells this card to redraw itself
 * (because the scrollback and the ball are also on screen and need to follow).
 */
class FirstRunUi(
    private val context: Context,
    private val onDismiss: () -> Unit,
    private val onComplete: () -> Unit,
    /**
     * The 大脑 / 语音密钥 rows open *our* settings sheet, which is an overlay the
     * service owns — exactly as [SelfCheckUi] does. Null when there is no sheet
     * to open, in which case those rows get no button rather than a dead one.
     */
    private val onOpenSettings: (() -> Unit)? = null,
    private val onOpenWakeEnroll: (() -> Unit)? = null,
    private val onOpenLook: (() -> Unit)? = null,
    private val onOpenMic: (() -> Unit)? = null,
    private val onLocaleChanged: () -> Unit = {},
    private val onYieldScreen: () -> Unit = {},
    private val host: FrameLayout? = null,
) {

    private companion object {
        const val TAG = "Body"
        const val GUTTER_DP = 20
        const val CARD_RADIUS_DP = 28

        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        /** The steps, in order. Only the first three are SelfCheck rows. */
        private val STEP_IDS = listOf(
            "accessibility",
            "overlay",
            "voice_key",
            "wake",
            "look",
            "first_sentence",
        )
    }

    private val wm: WindowManager? =
        if (host == null) context.getSystemService(Context.WINDOW_SERVICE) as WindowManager else null

    private val main = Handler(Looper.getMainLooper())
    private val prober = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wizard-probe").apply { isDaemon = true }
    }

    private var root: View? = null
    private var closing: View? = null
    private var frosted = false
    private var lctx: Context = context

    private var stepIndex = -1 // -1 = welcome, 0..5 = steps, 6 = complete
    private var body: LinearLayout? = null

    /**
     * The last full list, kept so repaint can draw the current step from it.
     * See [SelfCheckUi.last] for why this is correct rather than a shortcut.
     */
    private var findings: List<SelfCheck.Finding>? = null

    fun isShowing(): Boolean = root != null

    // ---- Lifecycle ----

    fun show() {
        if (host == null) showInWindow() else showInHost()
    }

    private fun showInHost() {
        val stage = host ?: return
        if (root != null) return
        lctx = AppLocale.wrap(context)
        frosted = true
        val v = build()
        stage.addView(v, FrameLayout.LayoutParams(cardWidth(), cardHeight(), Gravity.CENTER))
        root = v
        OwnCard.shown()
        renderWelcome()
    }

    private fun showInWindow() {
        val wm = wm ?: return
        closing?.let { runCatching { wm.removeView(it) }; OwnCard.hidden() }
        closing = null
        if (root != null) return

        lctx = AppLocale.wrap(context)
        val p = WindowManager.LayoutParams(
            cardWidth(), cardHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        frosted = Glass.frost(context, wm, p)
        val v = build()
        try {
            v.hideFromAccessibility()
            wm.addView(v, p)
            root = v
            OwnCard.shown()
            Glass.enter(v)
            renderWelcome()
        } catch (t: Throwable) {
            Log.w(TAG, "wizard refused", t)
        }
    }

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

    fun destroy() {
        prober.shutdownNow()
        if (root != null) {
            root = null
            OwnCard.hidden()
        }
    }

    // ---- Navigation ----

    /** The welcome card: do you want to begin? */
    private fun renderWelcome() {
        stepIndex = -1
        body?.removeAllViews()
        body?.addView(heroText(
            lctx.getString(R.string.wizard_welcome_title),
            lctx.getString(R.string.wizard_welcome_body),
        ))
    }

    /** A step from the list. */
    private fun renderStep(index: Int) {
        if (index < 0 || index >= STEP_IDS.size) return
        stepIndex = index
        val id = STEP_IDS[index]
        body?.removeAllViews()

        when (id) {
            "accessibility", "overlay", "voice_key" -> {
                // SelfCheck rows: show the finding.
                val f = findings?.firstOrNull { it.id == id }
                if (f != null) {
                    body?.addView(stepTitle(index))
                    body?.addView(findingCard(f))
                }
            }
            "wake" -> {
                body?.addView(stepTitle(index))
                body?.addView(instructionCard(
                    lctx.getString(R.string.wizard_wake_body),
                    lctx.getString(R.string.wizard_wake_button),
                ) {
                    onOpenWakeEnroll?.invoke()
                })
            }
            "look" -> {
                body?.addView(stepTitle(index))
                body?.addView(instructionCard(
                    lctx.getString(R.string.wizard_look_body),
                    lctx.getString(R.string.wizard_look_button),
                ) {
                    onOpenLook?.invoke()
                })
            }
            "first_sentence" -> {
                body?.addView(stepTitle(index))
                body?.addView(instructionCard(
                    lctx.getString(R.string.wizard_sentence_body),
                    lctx.getString(R.string.wizard_sentence_button),
                ) {
                    onOpenMic?.invoke()
                })
            }
        }
    }

    /** The final card: you're done. */
    private fun renderComplete() {
        stepIndex = STEP_IDS.size
        body?.removeAllViews()
        body?.addView(heroText(
            lctx.getString(R.string.wizard_complete_title),
            lctx.getString(R.string.wizard_complete_body),
        ))
    }

    private fun advance() {
        if (stepIndex == -1) {
            // Welcome → step 0: load findings first.
            probe { renderStep(0) }
        } else if (stepIndex < STEP_IDS.size - 1) {
            renderStep(stepIndex + 1)
        } else {
            renderComplete()
        }
    }

    private fun retreat() {
        if (stepIndex == 0) {
            renderWelcome()
        } else if (stepIndex > 0) {
            renderStep(stepIndex - 1)
        }
    }

    private fun probe(then: () -> Unit) {
        val app = context.applicationContext
        prober.execute {
            val found = runCatching { SelfCheck.run(app) }.getOrNull() ?: return@execute
            main.post {
                if (root == null) return@post
                findings = found
                then()
            }
        }
    }

    // ---- Language ----

    private fun onLanguageChanged() {
        repaintForLanguage()
        onLocaleChanged()
    }

    fun repaintForLanguage() {
        if (root == null) return
        lctx = AppLocale.wrap(context)
        rebuild()
    }

    private fun rebuild() {
        val old = root ?: return
        val stage = host
        if (stage != null) {
            val v = build()
            val i = stage.indexOfChild(old)
            runCatching { stage.removeView(old) }
            if (i >= 0) stage.addView(v, i, old.layoutParams) else stage.addView(v, old.layoutParams)
            root = v
            repaintCurrentStep()
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
                repaintCurrentStep()
            }
        }
    }

    private fun repaintCurrentStep() {
        when (stepIndex) {
            -1 -> renderWelcome()
            in 0 until STEP_IDS.size -> renderStep(stepIndex)
            else -> renderComplete()
        }
    }

    // ---- Build ----

    private fun build(): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.card(context, dp(CARD_RADIUS_DP), frosted)
            clipToOutline = true
        }

        card.addView(header(), matchWrap())

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(24))
        }
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
            text = lctx.getString(R.string.wizard_title)
            textSize = Glass.Type.DISPLAY
            setTextColor(Color.parseColor(Glass.TITLE))
            letterSpacing = 0.01f
        })
        titles.addView(TextView(context).apply {
            text = progressLabel()
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

    private fun progressLabel(): String = when (stepIndex) {
        -1 -> lctx.getString(R.string.wizard_progress_welcome)
        in 0 until STEP_IDS.size -> lctx.getString(
            R.string.wizard_progress_step,
            stepIndex + 1,
            STEP_IDS.size,
        )
        else -> lctx.getString(R.string.wizard_progress_done)
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
        contentDescription = lctx.getString(R.string.wizard_cancel)
        setOnClickListener { hide() }
    }

    private fun footer(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(18))
        }

        val leftLabel = when (stepIndex) {
            -1 -> lctx.getString(R.string.wizard_cancel)
            in 0 until STEP_IDS.size -> lctx.getString(R.string.wizard_back)
            else -> null
        }
        if (leftLabel != null) {
            row.addView(
                textButton(leftLabel, Glass.SECONDARY) {
                    if (stepIndex == -1) hide() else retreat()
                },
                LinearLayout.LayoutParams(0, WRAP, 1f),
            )
        } else {
            row.addView(View(context), LinearLayout.LayoutParams(0, WRAP, 1f))
        }

        val rightLabel = when (stepIndex) {
            -1 -> lctx.getString(R.string.wizard_begin)
            in 0 until STEP_IDS.size - 1 -> lctx.getString(R.string.wizard_next)
            STEP_IDS.size - 1 -> lctx.getString(R.string.wizard_finish)
            else -> lctx.getString(R.string.wizard_done)
        }
        row.addView(button(rightLabel, filled = true) {
            if (stepIndex == STEP_IDS.size) {
                onComplete()
                hide()
            } else {
                advance()
            }
        })
        return row
    }

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

    // ---- Step parts ----

    private fun heroText(title: String, body: String): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(20), 0, 0)
        }
        card.addView(TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.HEADLINE)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(Glass.TITLE))
            gravity = Gravity.CENTER
        })
        card.addView(TextView(context).apply {
            text = body
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.BODY)
            setTextColor(Color.parseColor(Glass.SECONDARY))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), 0)
        })
        return card
    }

    private fun stepTitle(index: Int): View = TextView(context).apply {
        val id = STEP_IDS.getOrNull(index) ?: ""
        text = when (id) {
            "accessibility" -> lctx.getString(R.string.check_title_accessibility)
            "overlay" -> lctx.getString(R.string.check_title_overlay)
            "voice_key" -> lctx.getString(R.string.check_title_voice_key)
            "wake" -> lctx.getString(R.string.wizard_wake_title)
            "look" -> lctx.getString(R.string.wizard_look_title)
            "first_sentence" -> lctx.getString(R.string.wizard_sentence_title)
            else -> ""
        }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.HEADLINE)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.parseColor(Glass.TITLE))
        setPadding(0, dp(8), 0, dp(12))
    }

    private fun findingCard(f: SelfCheck.Finding): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.panel(context, dp(14))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        card.addView(TextView(context).apply {
            text = AppLocale.str(context, f.detail, *f.detailArgs.toTypedArray())
            setTextColor(Color.parseColor(Glass.SECONDARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.BODY)
        })
        fixButton(f)?.let {
            card.addView(it.apply {
                val lp = layoutParams as? LinearLayout.LayoutParams
                lp?.topMargin = dp(10)
                layoutParams = lp
            })
        }
        return card
    }

    private fun instructionCard(body: String, buttonLabel: String, onClick: () -> Unit): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.panel(context, dp(14))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        card.addView(TextView(context).apply {
            text = body
            setTextColor(Color.parseColor(Glass.SECONDARY))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.BODY)
        })
        card.addView(button(buttonLabel, filled = false, onClick).apply {
            val lp = LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) }
            layoutParams = lp
        })
        return card
    }

    /**
     * The way out for a step that is a [SelfCheck] row, or nothing when there is
     * not one.
     *
     * The [SelfCheck.Fix.OurSettings] guard is the same rule [SelfCheckUi] uses:
     * our sheet is an overlay the service owns, so with the service not there to
     * raise it the row must not offer a button that would do nothing. The wizard
     * is only ever raised by the service today, so this is currently unreachable
     * — it is here because the row and the button would otherwise be able to
     * disagree the day a host without overlays raises this card.
     */
    private fun fixButton(f: SelfCheck.Finding): View? {
        if (f.fix is SelfCheck.Fix.OurSettings && onOpenSettings == null) return null
        val label = when (val fix = f.fix) {
            is SelfCheck.Fix.Screen ->
                if (fix.label != 0) fix.label else R.string.check_fix_settings
            is SelfCheck.Fix.Link -> R.string.check_fix_download
            is SelfCheck.Fix.Grant -> R.string.check_fix_allow
            is SelfCheck.Fix.OurSettings -> R.string.check_fix_app_settings
            else -> return null
        }
        return button(lctx.getString(label), filled = false) { apply(f) }
    }

    // ---- Doors ----

    /**
     * Every fix is a `startActivity`, exactly as [SelfCheckUi.apply] — and the
     * yield matters here for the same reason it does there: this card is a
     * fullscreen `TYPE_APPLICATION_OVERLAY` in the window host, so the system
     * Settings page it opens comes up *behind* it unless we get out of the way.
     *
     * [SelfCheck.Fix.OurSettings] is the one exception: our own sheet is
     * supposed to come up over this card so the wizard is still behind it to
     * return to. And it is the one door that can be missing — see
     * [onOpenSettings] — in which case the row gets no button at all rather
     * than a button that does nothing.
     */
    private fun apply(f: SelfCheck.Finding) {
        val fix = f.fix
        if (fix !is SelfCheck.Fix.OurSettings) {
            if (!openable(fix)) {
                toastNoScreen()
                return
            }
            onYieldScreen()
        }
        when (fix) {
            is SelfCheck.Fix.Screen, is SelfCheck.Fix.Link -> launch(intentFor(fix))
            is SelfCheck.Fix.Grant -> launch(
                Intent(context, PermissionRequestActivity::class.java)
                    .putExtra("permissions", fix.permissions.toTypedArray())
            )
            is SelfCheck.Fix.OurSettings -> onOpenSettings?.invoke()
            is SelfCheck.Fix.Adb, SelfCheck.Fix.Nothing -> Unit
        }
    }

    private fun openable(fix: SelfCheck.Fix): Boolean = when (fix) {
        is SelfCheck.Fix.Screen, is SelfCheck.Fix.Link ->
            runCatching {
                context.packageManager
                    .queryIntentActivities(intentFor(fix), 0)
                    .isNotEmpty()
            }.getOrDefault(true)
        else -> true
    }

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

    private fun launch(intent: Intent) {
        if (host == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { context.startActivity(intent) }.onFailure {
            Log.w(TAG, "wizard: no activity for ${intent.action}", it)
        }.isSuccess
        if (!ok) toastNoScreen()
    }

    // ---- Helpers ----

    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun dp(v: Int): Int = Glass.dp(context, v)

    private fun cardWidth(): Int {
        val screen = screenW()
        return minOf(screen - dp(2 * GUTTER_DP), (screen * 0.98f).toInt())
    }

    private fun cardHeight(): Int {
        val screen = screenH()
        return minOf(screen - dp(2 * GUTTER_DP), (screen * 0.96f).toInt())
    }

    private fun screenW(): Int = wm?.currentWindowMetrics?.bounds?.width()
        ?: context.resources.displayMetrics.widthPixels

    private fun screenH(): Int = wm?.currentWindowMetrics?.bounds?.height()
        ?: context.resources.displayMetrics.heightPixels
}
