package net.kuafuai.andee.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.device.SelfCheck
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.ui.ball.Action
import net.kuafuai.andee.ui.ball.BallLooks
import net.kuafuai.andee.ui.ball.EmotionBallTextureView
import net.kuafuai.andee.ui.ball.Mood

/**
 * First run as one forward-moving story, rather than a settings form.
 *
 * A prelude comes first and asks two things before anything is said: which
 * language, and whether Andee may speak aloud. Then Andee introduces itself — what it is (this device's own individual,
 * not an app on it) and how that differs from a chatbot and from an agent —
 * and only then do three concrete jobs show what that means in practice. Only after that does the user see setup, one missing item at a time.
 * Android and VoiceConfig remain the sources of truth, so a permission, key or
 * model that already exists is skipped instead of being asked for again.
 *
 * **Andee is on stage the whole way.** One [EmotionBallTextureView] sits on the
 * card's top edge, is added once and survives every page change, so the user
 * meets the individual rather than a form about it. Each story is *performed*:
 * the request types itself in, each step lands with the ball's own gesture, the
 * result arrives last. Every view is built in its final state and the
 * choreography only hides and reveals it, so with system animations off (or a
 * tap on the button mid-scene) the page is simply complete.
 *
 * **And it speaks.** Each page has a pre-recorded line in Andee's own voice
 * (`assets/onboarding/<lang>/<page>.mp3`, made by `tools/onboarding_voice.py`,
 * which holds the script). Recorded, not synthesised, because no key exists
 * yet. It plays when a page appears and sound is on, stops on any page change,
 * and while it plays the ball wears SPEAKING with the page's face layered on
 * top, so the mouth moves and the expression stays. The ball is always the
 * Andee look here, since the recording is Andee's voice.
 */
class OnboardingUi(
    private val context: Context,
    private val host: FrameLayout,
    private val onOpenFinding: (SelfCheck.Finding) -> Unit,
    private val onOpenUrl: (String) -> Unit,
    private val onSaveModel: (Map<String, String>) -> Unit,
    /** Language or sound changed: the host repaints whatever else is already on screen. */
    private val onPrefsChanged: () -> Unit,
    private val onComplete: () -> Unit,
) {
    private var lctx: Context = AppLocale.wrap(context)
    private var root: View? = null
    private var paintedPage: OnboardingState.Page? = null
    private var paintedGate: String? = null

    /** Persistent across pages: a GL context per page would be a flicker per tap. */
    private var ball: EmotionBallTextureView? = null

    /** The page's face; while a line plays it rides on SPEAKING as the emotion. */
    private var face: Mood = Mood.CALM
    private var player: MediaPlayer? = null
    private var talking = false

    /** `<lang>/<clip>` last started, so a repaint of the same page does not replay it. */
    private var lastClip: String? = null

    /** Every pending beat of the current page's scene; cleared on each render. */
    private val beats = Handler(Looper.getMainLooper())
    private val motion: Boolean get() = ValueAnimator.areAnimatorsEnabled()

    private var selectedBrain = when (VoiceConfig.brainMode(context)) {
        VoiceConfig.BRAIN_LOCAL -> VoiceConfig.BRAIN_LOCAL
        else -> VoiceConfig.BRAIN_CODEFLYING
    }
    private val initialConfig = VoiceConfig.current(context)
    private var draftCodeFlyingKey = initialConfig["codeflying_key"].orEmpty()
    private var draftLocalKey = initialConfig["llm_api_key"].orEmpty()
    private var draftLocalBase = initialConfig["llm_base_url"].orEmpty()
    private var draftLocalModel = initialConfig["llm_model"].orEmpty()
    private var codeFlyingKeyEdit: EditText? = null
    private var localKeyEdit: EditText? = null
    private var localBaseEdit: EditText? = null
    private var localModelEdit: EditText? = null

    // Voice, for the local brain only: CodeFlying brings its own from the build.
    // Who hears and who speaks are two pickers, as in SettingsUi.
    private var draftHear = initialConfig["voice_provider"].orEmpty()
        .ifBlank { VoiceConfig.VOICE_HUOSHAN }
    private var draftSpeak = initialConfig["speak_provider"].orEmpty()
        .ifBlank { draftHear }
    private var draftVolcKey = initialConfig["api_key"].orEmpty()
    private var draftGoogleKey = initialConfig["google_api_key"].orEmpty()
    private var draftGoogleProject = initialConfig["google_project"].orEmpty()
    private var volcKeyEdit: EditText? = null
    private var googleKeyEdit: EditText? = null
    private var googleProjectEdit: EditText? = null

    /** Read aloud or not; written when the prelude is left, unmuted unless the user says otherwise. */
    private var soundOn = !VoiceConfig.muted(context)

    private var selectedAssistant = OnboardingState.assistantName(context)
    private var customAssistantDraft = ""
    private var userNameDraft = OnboardingState.userName(context)

    fun show() {
        if (root != null) return
        // Andee's own look, whatever is saved: the recorded voice is Andee's.
        // Not saved — the user's pick comes back in [destroy].
        BallLooks.requestByName("andee")
        ball = EmotionBallTextureView(context).apply {
            alpha = 0f
            elevation = dp(24).toFloat()
        }
        host.addView(
            ball,
            FrameLayout.LayoutParams(dp(BALL_DP), dp(BALL_DP), Gravity.TOP or Gravity.CENTER_HORIZONTAL),
        )
        render(animated = false)
    }

    /** Re-read system state after returning from a permission or browser trip. */
    fun refresh() {
        val page = normalizePage()
        val gate = if (page == OnboardingState.Page.PERMISSIONS) nextMissing()?.id else null
        if (page != paintedPage || gate != paintedGate) {
            // A gate that was showing and is not any more was just granted out
            // in Settings. That is the moment the user did something for it.
            justGranted = paintedPage == OnboardingState.Page.PERMISSIONS && paintedGate != null
            render(animated = true)
            justGranted = false
        }
    }

    private var justGranted = false

    /** The host is going to the background (a Settings trip): stop talking. */
    fun pause() {
        stopVoice()
    }

    fun destroy() {
        captureModelDrafts()
        stopVoice()
        BallLooks.requestByName(VoiceConfig.ballLook(context))
        beats.removeCallbacksAndMessages(null)
        root?.animate()?.cancel()
        root?.let { host.removeView(it) }
        root = null
        ball?.let { host.removeView(it) }
        ball = null
    }

    // ---- Page routing -------------------------------------------------------

    private fun normalizePage(): OnboardingState.Page {
        var page = OnboardingState.page(context)
        while (true) {
            page = when {
                page == OnboardingState.Page.PERMISSIONS && nextMissing() == null -> {
                    OnboardingState.setPage(context, OnboardingState.Page.MODEL)
                    OnboardingState.Page.MODEL
                }

                page == OnboardingState.Page.MODEL && modelReady() -> {
                    OnboardingState.setPage(context, OnboardingState.Page.MEET)
                    OnboardingState.Page.MEET
                }

                else -> return page
            }
        }
    }

    private fun advance(to: OnboardingState.Page) {
        OnboardingState.setPage(context, to)
        render(animated = true)
    }

    /**
     * The setup steps still to show: any of [REQUIRED_GATES] the checklist does
     * not call OK, minus the ones the user skipped. Only [SKIPPABLE] ones can
     * be skipped; without the overlay there is no Andee to come back to.
     */
    private fun requiredMissing(): List<SelfCheck.Finding> {
        val byId = SelfCheck.local(context).associateBy { it.id }
        val skipped = OnboardingState.skipped(context)
        return REQUIRED_GATES.mapNotNull { id ->
            byId[id]?.takeIf { SelfCheck.needsAttention(it) && id !in skipped }
        }
    }

    private fun nextMissing(): SelfCheck.Finding? = requiredMissing().firstOrNull()

    private fun modelReady(): Boolean {
        val brain = VoiceConfig.brainConfig(context)
        return if (brain.isLocal) brain.apiKey.isNotBlank() && voiceReady()
        else VoiceConfig.hubConfig(context).url.isNotBlank()
    }

    /**
     * Whether the voice side can hear and speak, by the checklist's own row —
     * so this page and the self-check can never disagree about the same key.
     * Only asked for the local brain; CodeFlying's voice comes with the build.
     */
    private fun voiceReady(): Boolean =
        SelfCheck.local(context).firstOrNull { it.id == "voice_key" }?.level != SelfCheck.Level.WARN

    private fun render(animated: Boolean) {
        lctx = AppLocale.wrap(context)
        beats.removeCallbacksAndMessages(null)
        val page = normalizePage()
        val gate = if (page == OnboardingState.Page.PERMISSIONS) nextMissing() else null
        if (justGranted) celebrate()
        val next = buildRoot(page, gate)
        val old = root
        root = next
        paintedPage = page
        paintedGate = gate?.id

        host.addView(next, FrameLayout.LayoutParams(MATCH, MATCH))
        ball?.bringToFront()
        speak(clipFor(page, gate))
        if (old == null || !animated) {
            old?.let { host.removeView(it) }
            Glass.enter(next)
            return
        }

        old.isClickable = false
        next.alpha = 0f
        next.translationX = dp(18).toFloat()
        next.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(260)
            .setInterpolator(Glass.EASE)
            .start()
        old.animate()
            .alpha(0f)
            .translationX(-dp(10).toFloat())
            .setDuration(150)
            .setInterpolator(Glass.EASE_IN)
            .withEndAction { runCatching { host.removeView(old) } }
            .start()
    }

    // ---- Shared frame -------------------------------------------------------

    private fun buildRoot(page: OnboardingState.Page, gate: SelfCheck.Finding?): View {
        // The top padding leaves room for the part of the ball above the card.
        val outer = FrameLayout(context).apply {
            isClickable = true
            setPadding(dp(12), dp(14 + BALL_ABOVE_DP), dp(12), dp(14))
        }
        val (screenW, screenH) = Glass.usableSize(context)
        val cardWidth = minOf(dp(590), screenW - dp(24))
        val cardHeight = minOf(dp(790), screenH - dp(28 + BALL_ABOVE_DP))
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
            background = Glass.card(context, dp(30), frosted = true)
            clipToOutline = true
            elevation = dp(16).toFloat()
        }
        outer.addView(
            card,
            FrameLayout.LayoutParams(cardWidth, cardHeight, Gravity.CENTER),
        )
        // The model/name pages open the IME. The Activity is adjustResize, but
        // this card has a deliberate tablet cap rather than MATCH_PARENT, so it
        // also has to follow the resized stage or its footer would be centred
        // below the keyboard. On a normal frame this is a no-op.
        outer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val available = (bottom - top - outer.paddingTop - outer.paddingBottom).coerceAtLeast(dp(280))
            val target = minOf(cardHeight, available)
            val lp = card.layoutParams
            if (lp.height != target) {
                lp.height = target
                card.layoutParams = lp
            }
        }
        // The ball rides the card's top edge, wherever the keyboard put it.
        // Its centre sits just above the edge, so it reads as perched, not pasted.
        card.addOnLayoutChangeListener { _, _, top, _, _, _, _, _, _ ->
            val b = ball ?: return@addOnLayoutChangeListener
            b.translationY = (outer.top + top - dp(BALL_DP) / 2 - dp(BALL_LIFT_DP)).toFloat()
            if (b.alpha == 0f) b.animate().alpha(1f).setDuration(320).setInterpolator(Glass.EASE).start()
        }

        card.addView(topRail(page), LinearLayout.LayoutParams(MATCH, WRAP))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(30), 0, dp(10))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body, ViewGroup.LayoutParams(MATCH, WRAP))
        }
        card.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(footer, LinearLayout.LayoutParams(MATCH, WRAP))

        when (page) {
            OnboardingState.Page.PRELUDE -> prelude(body, footer)
            OnboardingState.Page.INTRO -> intro(body, footer)
            OnboardingState.Page.SHOPPING -> shopping(body, footer)
            OnboardingState.Page.CUSTOMER -> customer(body, footer)
            OnboardingState.Page.SUBSCRIPTIONS -> subscriptions(body, footer)
            OnboardingState.Page.PERMISSIONS -> permission(body, footer, requireNotNull(gate))
            OnboardingState.Page.MODEL -> model(body, footer)
            OnboardingState.Page.MEET -> meet(body, footer)
            OnboardingState.Page.READY -> ready(body, footer)
        }
        return outer
    }

    private fun topRail(page: OnboardingState.Page): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = "ANDEE"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.CAPTION)
            setTextColor(Color.parseColor(Glass.TITLE))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.18f
        })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val current = page.ordinal
        OnboardingState.Page.entries.forEachIndexed { index, _ ->
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(4).toFloat()
                    setColor(
                        Color.parseColor(
                            when {
                                index == current -> Glass.ACCENT
                                index < current -> Glass.OK
                                else -> Glass.HAIRLINE
                            },
                        ),
                    )
                }
            }, LinearLayout.LayoutParams(if (index == current) dp(24) else dp(6), dp(6)).apply {
                marginStart = dp(6)
            })
        }
    }

    // ---- Before anything is said -----------------------------------------------

    /**
     * Language and sound, before the first sentence. Language is written the
     * moment it is tapped and the page redraws in it — the redraw is the
     * confirmation, as in Settings — and its two labels are never translated,
     * so the picker stays readable from the wrong language. Sound is written on
     * leaving; it defaults to on.
     */
    private fun prelude(body: LinearLayout, footer: LinearLayout) {
        body.gravity = Gravity.CENTER_VERTICAL
        if (!justGranted) mood(Mood.CURIOUS)
        pageHeader(
            body,
            R.string.onboarding_prelude_badge,
            R.string.onboarding_prelude_title,
            R.string.onboarding_prelude_body,
        )

        body.addView(sectionLabel(lctx.getString(R.string.onboarding_prelude_lang)), spacedParams(26))
        val lang = VoiceConfig.uiLanguage(context)
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(VoiceConfig.LANG_ZH to "中文", VoiceConfig.LANG_EN to "English").forEachIndexed { i, (code, name) ->
                addView(modelChoice(name, if (code == VoiceConfig.LANG_ZH) "Chinese" else "英文", code == lang) {
                    if (code == lang) return@modelChoice
                    // The same single write SettingsUi and LangToggle make.
                    VoiceConfig.save(context, mapOf("lang" to code))
                    onPrefsChanged()
                    render(animated = false)
                    ball?.greet()
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(10) })
            }
        }, spacedParams(8))

        body.addView(sectionLabel(lctx.getString(R.string.onboarding_prelude_sound)), spacedParams(20))
        body.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(
                true to (R.string.onboarding_prelude_sound_on to R.string.onboarding_prelude_sound_on_note),
                false to (R.string.onboarding_prelude_sound_off to R.string.onboarding_prelude_sound_off_note),
            ).forEachIndexed { i, (on, text) ->
                addView(modelChoice(lctx.getString(text.first), lctx.getString(text.second), on == soundOn) {
                    if (on == soundOn) return@modelChoice
                    soundOn = on
                    render(animated = false)
                    if (on) { mood(Mood.HAPPY); ball?.trigger(Action.NOD) } else mood(Mood.CALM)
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(10) })
            }
        }, spacedParams(8))

        footer.addView(primaryButton(lctx.getString(R.string.onboarding_prelude_next)) {
            VoiceConfig.setMuted(context, !soundOn)
            onPrefsChanged()
            advance(OnboardingState.Page.INTRO)
        })
    }

    // ---- Who it is ----------------------------------------------------------

    /**
     * It says hello and says what it is, in one line and one contrast: the two
     * familiar kinds wait for you, it does not. Type only — no boxes, no
     * paragraph — so the page has a single thing to read. The two that wait
     * light up first and then step back as "me" lands.
     */
    private fun intro(body: LinearLayout, footer: LinearLayout) {
        body.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(context).apply {
            text = lctx.getString(R.string.onboarding_intro_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.DISPLAY * 1.5f)
            setTextColor(Color.parseColor(Glass.TITLE))
            setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(0f, 1.1f)
        }
        val chatbot = contrastLine(
            lctx.getString(R.string.onboarding_intro_chatbot_name),
            lctx.getString(R.string.onboarding_intro_chatbot),
            mine = false,
        )
        val agent = contrastLine(
            lctx.getString(R.string.onboarding_intro_agent_name),
            lctx.getString(R.string.onboarding_intro_agent),
            mine = false,
        )
        val me = contrastLine(
            lctx.getString(R.string.onboarding_intro_me_name),
            lctx.getString(R.string.onboarding_intro_me),
            mine = true,
        )
        body.addView(badge(lctx.getString(R.string.onboarding_intro_badge)))
        body.addView(title, spacedParams(10))
        body.addView(chatbot, spacedParams(40))
        body.addView(agent, spacedParams(22))
        body.addView(me, spacedParams(22))

        mood(Mood.WAVE)
        beat(300) { ball?.greet() }
        val typed = typeIn(title, at = 350, perChar = 60)
        // It plays the two that wait — dozing off on them — then wakes for "me".
        reveal(chatbot, typed + 250)
        beat(typed + 250) { mood(Mood.REST) }
        reveal(agent, typed + 750)
        val landed = typed + 1450
        pop(me, landed)
        beat(landed) {
            mood(Mood.HAPPY)
            ball?.trigger(Action.HOP)
        }
        // Built dimmed; with motion on they arrive bright and recede here.
        if (motion) {
            beats.postDelayed({
                for (v in listOf(chatbot, agent)) {
                    v.animate().alpha(RECEDED).setDuration(420).setInterpolator(Glass.EASE).start()
                }
            }, landed)
        } else {
            chatbot.alpha = RECEDED
            agent.alpha = RECEDED
        }
        footer.addView(primaryButton(lctx.getString(R.string.onboarding_intro_next)) {
            advance(OnboardingState.Page.SHOPPING)
        })
    }

    /** A small name over a large phrase; "me" in the accent colour. */
    private fun contrastLine(name: String, phrase: String, mine: Boolean): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.CAPTION)
                setTextColor(Color.parseColor(if (mine) Glass.ACCENT else Glass.MUTED))
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.06f
            })
            addView(TextView(context).apply {
                text = phrase
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.DISPLAY * 1.15f)
                setTextColor(Color.parseColor(if (mine) Glass.ACCENT else Glass.TITLE))
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        }

    // ---- Stories ------------------------------------------------------------

    private fun shopping(body: LinearLayout, footer: LinearLayout) {
        pageHeader(
            body,
            R.string.onboarding_story_badge,
            R.string.onboarding_shop_title,
            R.string.onboarding_shop_body,
        )
        val request = requestBubble(lctx.getString(R.string.onboarding_shop_request))
        val steps = listOf(
            flowRow("1", lctx.getString(R.string.onboarding_shop_coupon)),
            flowRow("2", lctx.getString(R.string.onboarding_shop_compare)),
            flowRow("3", lctx.getString(R.string.onboarding_shop_agent)),
        )
        val result = resultBox(
            lctx.getString(R.string.onboarding_shop_result),
            lctx.getString(R.string.onboarding_shop_confirm),
        )
        body.addView(panel().apply {
            addView(request)
            steps.forEach { addView(it.first) }
            addView(result)
        }, spacedParams(18))

        // The user speaks, Andee goes to work, one app at a time, then comes back.
        mood(Mood.LISTENING)
        val typed = typeIn(request, at = 350)
        beat(typed + 150) { mood(Mood.SEARCHING) }
        steps.forEachIndexed { i, (row, check) ->
            val at = typed + 300 + i * 620L
            reveal(row, at)
            land(check, at + 420)
            // The last step is a second opinion: it weighs it rather than hunts.
            if (i == steps.lastIndex) beat(at) { mood(Mood.THINKING) }
        }
        val done = typed + 300 + steps.size * 620L + 200
        pop(result, done)
        beat(done) { mood(Mood.SUCCESS); ball?.signalOutcome(true) }
        // Then it stops and waits for the user before paying.
        beat(done + 1300) { mood(Mood.HOLDING) }
        footer.addView(primaryButton(lctx.getString(R.string.onboarding_shop_next)) {
            advance(OnboardingState.Page.CUSTOMER)
        })
    }

    private fun customer(body: LinearLayout, footer: LinearLayout) {
        pageHeader(
            body,
            R.string.onboarding_customer_badge,
            R.string.onboarding_customer_title,
            R.string.onboarding_customer_body,
        )
        val incoming = chatBubble(lctx.getString(R.string.onboarding_customer_in), mine = false)
        val typing = chatBubble("•••", mine = true).apply { visibility = View.GONE }
        val reply = chatBubble(lctx.getString(R.string.onboarding_customer_reply), mine = true)
        val memory = infoStrip("✓", lctx.getString(R.string.onboarding_customer_memory), Glass.OK)
        val handoff = infoStrip("→", lctx.getString(R.string.onboarding_customer_handoff), Glass.ACCENT)
        body.addView(panel().apply {
            addView(incoming)
            addView(typing)
            addView(reply)
            addView(memory)
            addView(handoff)
        }, spacedParams(18))

        // A message arrives, Andee thinks, answers, and files away what it learnt.
        mood(Mood.CURIOUS)
        reveal(incoming, 300, fromX = -16)
        beat(500) { ball?.trigger(Action.POP) }
        if (motion) {
            reply.visibility = View.GONE
            beat(1000) {
                mood(Mood.THINKING)
                typing.visibility = View.VISIBLE
                typing.alpha = 0f
                typing.animate().alpha(1f).setDuration(180).start()
            }
            beat(2000) {
                typing.visibility = View.GONE
                reply.visibility = View.VISIBLE
                reply.alpha = 0f
                reply.translationX = dp(16).toFloat()
                reply.animate().alpha(1f).translationX(0f).setDuration(280).setInterpolator(Glass.EASE).start()
                mood(Mood.HAPPY)
                ball?.trigger(Action.NOD)
            }
        }
        reveal(memory, 2700)
        beat(2700) { mood(Mood.WINK) }
        reveal(handoff, 3200)
        footer.addView(primaryButton(lctx.getString(R.string.onboarding_customer_next)) {
            advance(OnboardingState.Page.SUBSCRIPTIONS)
        })
    }

    private fun subscriptions(body: LinearLayout, footer: LinearLayout) {
        pageHeader(
            body,
            R.string.onboarding_subscription_badge,
            R.string.onboarding_subscription_title,
            R.string.onboarding_subscription_body,
        )
        val digest = label(lctx.getString(R.string.onboarding_subscription_digest), Glass.TITLE, bold = true)
        val keep = lctx.getString(R.string.onboarding_subscription_keep)
        val cancel = lctx.getString(R.string.onboarding_subscription_cancel)
        val charges = listOf(
            chargeRow(lctx.getString(R.string.onboarding_subscription_one_price),
                lctx.getString(R.string.onboarding_subscription_one), keep, cancel = false),
            chargeRow(lctx.getString(R.string.onboarding_subscription_two_price),
                lctx.getString(R.string.onboarding_subscription_two), cancel, cancel = true),
            chargeRow(lctx.getString(R.string.onboarding_subscription_three_price),
                lctx.getString(R.string.onboarding_subscription_three), cancel, cancel = true),
        )
        val result = resultBox(
            lctx.getString(R.string.onboarding_subscription_saved),
            lctx.getString(R.string.onboarding_subscription_ask),
        )
        val handOver = headline(lctx.getString(R.string.onboarding_subscription_handover))
        body.addView(panel().apply {
            addView(digest)
            charges.forEach { addView(it.first) }
            addView(result)
        }, spacedParams(18))
        body.addView(handOver, spacedParams(22))

        // It finds every charge, judges each one, then adds up what that saves.
        mood(Mood.SEARCHING)
        reveal(digest, 250)
        charges.forEachIndexed { i, (row, verdict) ->
            val at = 650L + i * 560
            reveal(row, at, dy = -12)
            land(verdict, at + 380)
            // Money leaking out for nothing is worth a worried look.
            if (i == 1) beat(at + 380) { mood(Mood.CONCERNED) }
        }
        val tallied = 650L + charges.size * 560 + 250
        pop(result, tallied)
        // The shield: it is guarding the user's money.
        beat(tallied) { mood(Mood.PROTECT); ball?.signalOutcome(true) }
        reveal(handOver, tallied + 700)
        beat(tallied + 700) { ball?.trigger(Action.NOD) }
        footer.addView(primaryButton(lctx.getString(R.string.onboarding_subscription_next)) {
            advance(OnboardingState.Page.PERMISSIONS)
        })
    }

    // ---- Adaptive setup -----------------------------------------------------

    private fun permission(
        body: LinearLayout,
        footer: LinearLayout,
        finding: SelfCheck.Finding,
    ) {
        val remaining = requiredMissing().size + if (modelReady()) 0 else 1
        val title: Int
        val copy: Int
        val action: Int
        when (finding.id) {
            "overlay" -> {
                title = R.string.onboarding_permission_overlay_title
                copy = R.string.onboarding_permission_overlay_body
                action = R.string.onboarding_permission_overlay_action
            }

            "accessibility" -> {
                title = R.string.onboarding_permission_accessibility_title
                copy = R.string.onboarding_permission_accessibility_body
                action = R.string.onboarding_permission_accessibility_action
            }

            else -> {
                title = R.string.onboarding_permission_microphone_title
                copy = R.string.onboarding_permission_microphone_body
                action = R.string.onboarding_permission_microphone_action
            }
        }
        // Listening for the microphone; waiting on the user for the others.
        if (!justGranted) mood(
            when (finding.id) {
                "microphone" -> Mood.LISTENING
                "accessibility" -> Mood.HOLDING
                else -> Mood.CURIOUS
            },
        )
        body.addView(badge(lctx.getString(R.string.onboarding_setup_badge, remaining)))
        body.addView(headline(lctx.getString(title)), spacedParams(12))
        body.addView(bodyCopy(lctx.getString(copy)), spacedParams(10))
        body.addView(
            infoStrip("✓", lctx.getString(R.string.onboarding_setup_skip_note), Glass.OK),
            spacedParams(18),
        )
        body.addView(panel().apply {
            addView(label(lctx.getString(R.string.onboarding_permission_safety_title), Glass.TITLE, bold = true))
            addView(bodyCopy(lctx.getString(R.string.onboarding_permission_safety_body)).apply {
                setPadding(0, dp(6), 0, 0)
            })
        }, spacedParams(12))
        footer.addView(primaryButton(lctx.getString(action)) { onOpenFinding(finding) })
        // Accessibility and the microphone each cost a capability, not Andee:
        // say what is lost, and let the user go on without it.
        val skipNote = when (finding.id) {
            "accessibility" -> R.string.onboarding_skip_accessibility_note
            "microphone" -> R.string.onboarding_skip_microphone_note
            else -> null
        }
        if (skipNote != null && finding.id in SKIPPABLE) {
            footer.addView(textLink(lctx.getString(R.string.onboarding_permission_skip)) {
                OnboardingState.skip(context, finding.id)
                render(animated = true)
            }.apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH, WRAP))
            footer.addView(bodyCopy(lctx.getString(skipNote)).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.CAPTION)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    // ---- Model --------------------------------------------------------------

    private fun model(body: LinearLayout, footer: LinearLayout) {
        if (!justGranted) mood(Mood.THINKING)
        pageHeader(
            body,
            R.string.onboarding_model_badge,
            R.string.onboarding_model_title,
            R.string.onboarding_model_body,
        )

        val choices = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        choices.addView(
            modelChoice(
                title = "CodeFlying",
                subtitle = lctx.getString(R.string.onboarding_model_recommended),
                selected = selectedBrain == VoiceConfig.BRAIN_CODEFLYING,
            ) {
                captureModelDrafts()
                selectedBrain = VoiceConfig.BRAIN_CODEFLYING
                render(animated = true)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(5) },
        )
        choices.addView(
            modelChoice(
                title = lctx.getString(R.string.onboarding_model_local_title),
                subtitle = "API",
                selected = selectedBrain == VoiceConfig.BRAIN_LOCAL,
            ) {
                captureModelDrafts()
                selectedBrain = VoiceConfig.BRAIN_LOCAL
                render(animated = true)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(5) },
        )
        body.addView(choices, spacedParams(18))

        val form = panel()
        if (selectedBrain == VoiceConfig.BRAIN_CODEFLYING) {
            form.addView(bodyCopy(lctx.getString(R.string.onboarding_model_codeflying_note)))
            form.addView(textLink(lctx.getString(R.string.onboarding_model_open_site)) {
                onOpenUrl(lctx.getString(R.string.codeflying_url))
            }, spacedParams(4))
            codeFlyingKeyEdit = input(
                label = lctx.getString(R.string.settings_codeflying_key),
                value = draftCodeFlyingKey,
                hint = lctx.getString(R.string.onboarding_model_key_hint),
                secret = true,
            )
            form.addView(fieldBlock(lctx.getString(R.string.settings_codeflying_key), codeFlyingKeyEdit!!), spacedParams(8))
        } else {
            form.addView(bodyCopy(lctx.getString(R.string.onboarding_model_local_note)))
            localKeyEdit = input("", draftLocalKey, lctx.getString(R.string.onboarding_model_key_hint), secret = true)
            localBaseEdit = input(
                "",
                draftLocalBase,
                lctx.getString(R.string.onboarding_model_base_hint, VoiceConfig.llmBaseUrlDefault(context)),
            )
            localModelEdit = input(
                "",
                draftLocalModel,
                lctx.getString(R.string.onboarding_model_name_hint, VoiceConfig.llmModelDefault(context)),
            )
            form.addView(fieldBlock(lctx.getString(R.string.settings_llm_api_key), localKeyEdit!!), spacedParams(8))
            form.addView(fieldBlock(lctx.getString(R.string.settings_llm_base_url), localBaseEdit!!), spacedParams(8))
            form.addView(fieldBlock(lctx.getString(R.string.settings_llm_model), localModelEdit!!), spacedParams(8))
        }
        body.addView(form, spacedParams(12))
        if (selectedBrain == VoiceConfig.BRAIN_LOCAL) body.addView(voiceForm(), spacedParams(12))

        val error = errorLabel()
        body.addView(error, spacedParams(8))
        footer.addView(primaryButton(lctx.getString(R.string.onboarding_model_save)) {
            captureModelDrafts()
            val key = if (selectedBrain == VoiceConfig.BRAIN_CODEFLYING) {
                draftCodeFlyingKey.ifBlank { VoiceConfig.codeFlyingBuiltInKey(context) }
            } else {
                draftLocalKey
            }
            if (key.isBlank()) {
                error.text = lctx.getString(R.string.onboarding_model_key_required)
                error.visibility = View.VISIBLE
                mood(Mood.ANXIOUS)
                ball?.trigger(Action.SHAKE)
                return@primaryButton
            }
            if (selectedBrain == VoiceConfig.BRAIN_LOCAL && !voiceDraftComplete()) {
                error.text = lctx.getString(R.string.onboarding_voice_key_required)
                error.visibility = View.VISIBLE
                mood(Mood.ANXIOUS)
                ball?.trigger(Action.SHAKE)
                return@primaryButton
            }
            val updates = if (selectedBrain == VoiceConfig.BRAIN_CODEFLYING) {
                mapOf(
                    "brain" to VoiceConfig.BRAIN_CODEFLYING,
                    "codeflying_key" to draftCodeFlyingKey,
                )
            } else {
                mapOf(
                    "brain" to VoiceConfig.BRAIN_LOCAL,
                    "llm_api_key" to draftLocalKey,
                    "llm_base_url" to draftLocalBase,
                    "llm_model" to draftLocalModel,
                    "voice_provider" to draftHear,
                    "speak_provider" to draftSpeak,
                    // Blank values are dropped by save(), so an untouched key
                    // field keeps whatever was there.
                    "api_key" to draftVolcKey,
                    "google_api_key" to draftGoogleKey,
                    "google_project" to draftGoogleProject,
                )
            }
            onSaveModel(updates)
            advance(OnboardingState.Page.MEET)
        })
    }

    private fun captureModelDrafts() {
        codeFlyingKeyEdit?.let { draftCodeFlyingKey = it.text?.toString().orEmpty().trim() }
        localKeyEdit?.let { draftLocalKey = it.text?.toString().orEmpty().trim() }
        localBaseEdit?.let { draftLocalBase = it.text?.toString().orEmpty().trim() }
        localModelEdit?.let { draftLocalModel = it.text?.toString().orEmpty().trim() }
        volcKeyEdit?.let { draftVolcKey = it.text?.toString().orEmpty().trim() }
        googleKeyEdit?.let { draftGoogleKey = it.text?.toString().orEmpty().trim() }
        googleProjectEdit?.let { draftGoogleProject = it.text?.toString().orEmpty().trim() }
    }

    // ---- Voice (local brain) -----------------------------------------------

    private fun usesHuoshan() = draftHear == VoiceConfig.VOICE_HUOSHAN || draftSpeak == VoiceConfig.VOICE_HUOSHAN
    private fun usesGoogle() = draftHear == VoiceConfig.VOICE_GOOGLE || draftSpeak == VoiceConfig.VOICE_GOOGLE

    /**
     * Mirrors [SelfCheck]'s voice row against the drafts, before they are
     * saved: every provider in use needs its key. A build packaged with a
     * Google key/project needs neither typed.
     */
    private fun voiceDraftComplete(): Boolean {
        if (usesHuoshan() && draftVolcKey.isBlank()) return false
        if (usesGoogle()) {
            if (draftGoogleKey.isBlank() && VoiceConfig.googleApiKeyDefault().isBlank()) return false
            if (draftGoogleProject.isBlank() && VoiceConfig.googleProjectDefault().isBlank()) return false
        }
        return true
    }

    /**
     * Who hears, who speaks, and only the keys those two need. Endpoints and
     * resource ids ship working defaults and stay in Settings.
     */
    private fun voiceForm(): View = panel().apply {
        addView(label(lctx.getString(R.string.onboarding_voice_title), Glass.TITLE, bold = true))
        addView(bodyCopy(lctx.getString(R.string.onboarding_voice_body)), spacedParams(6))

        addView(sectionLabel(lctx.getString(R.string.settings_voice_hear_provider)), spacedParams(12))
        addView(pickerRow(
            listOf(
                VoiceConfig.VOICE_HUOSHAN to lctx.getString(R.string.settings_voice_provider_huoshan),
                VoiceConfig.VOICE_GOOGLE to lctx.getString(R.string.settings_voice_provider_google),
            ),
            draftHear,
        ) { draftHear = it }, spacedParams(6))

        addView(sectionLabel(lctx.getString(R.string.settings_voice_speak_provider)), spacedParams(12))
        addView(pickerRow(
            listOf(
                VoiceConfig.VOICE_HUOSHAN to lctx.getString(R.string.settings_voice_provider_huoshan),
                VoiceConfig.VOICE_GOOGLE to lctx.getString(R.string.settings_voice_provider_google),
                VoiceConfig.VOICE_SYSTEM to lctx.getString(R.string.settings_voice_provider_system),
            ),
            draftSpeak,
        ) { draftSpeak = it }, spacedParams(6))

        volcKeyEdit = null
        googleKeyEdit = null
        googleProjectEdit = null
        if (usesHuoshan()) {
            volcKeyEdit = input("", draftVolcKey, lctx.getString(R.string.settings_voice_api_key_hint), secret = true)
            addView(fieldBlock(lctx.getString(R.string.settings_voice_api_key), volcKeyEdit!!), spacedParams(12))
        }
        if (usesGoogle()) {
            val builtInKey = VoiceConfig.googleApiKeyDefault()
            val builtInProject = VoiceConfig.googleProjectDefault()
            googleKeyEdit = input(
                "",
                draftGoogleKey,
                lctx.getString(
                    if (builtInKey.isNotEmpty()) R.string.settings_google_key_builtin
                    else R.string.settings_google_api_key_hint,
                ),
                secret = true,
            )
            googleProjectEdit = input(
                "",
                draftGoogleProject,
                builtInProject.ifEmpty { lctx.getString(R.string.settings_google_project_hint) },
            )
            addView(fieldBlock(lctx.getString(R.string.settings_google_api_key), googleKeyEdit!!), spacedParams(12))
            addView(fieldBlock(lctx.getString(R.string.settings_google_project), googleProjectEdit!!), spacedParams(8))
        }
        addView(bodyCopy(lctx.getString(R.string.onboarding_voice_more)).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.CAPTION)
        }, spacedParams(10))
    }

    /** A row of pills; a pick re-renders, since it changes which keys are asked. */
    private fun pickerRow(
        options: List<Pair<String, String>>,
        picked: String,
        onPick: (String) -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        options.forEachIndexed { i, (value, text) ->
            addView(choicePill(text, value == picked).apply {
                setOnClickListener {
                    if (value == picked) return@setOnClickListener
                    captureModelDrafts()
                    onPick(value)
                    render(animated = false)
                }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(7) })
        }
    }

    // ---- Names and completion ----------------------------------------------

    private fun meet(body: LinearLayout, footer: LinearLayout) {
        if (!justGranted) {
            mood(Mood.WAVE)
            beat(400) { ball?.greet() }
        }
        pageHeader(
            body,
            R.string.onboarding_meet_badge,
            R.string.onboarding_meet_title,
            R.string.onboarding_meet_body,
        )
        body.addView(sectionLabel(lctx.getString(R.string.onboarding_meet_assistant)), spacedParams(18))

        val andy = lctx.getString(R.string.onboarding_meet_andy)
        if (selectedAssistant != "Andee" && selectedAssistant != andy && selectedAssistant.isNotBlank()) {
            customAssistantDraft = selectedAssistant
            selectedAssistant = OTHER
        }
        val optionRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val optionViews = mutableListOf<Pair<String, TextView>>()
        fun addOption(value: String, label: String) {
            val view = choicePill(label, selectedAssistant == value)
            optionViews += value to view
            optionRow.addView(view, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                if (optionViews.size > 1) marginStart = dp(7)
            })
        }
        addOption("Andee", "Andee")
        addOption(andy, andy)
        addOption(OTHER, lctx.getString(R.string.onboarding_meet_other))
        body.addView(optionRow, spacedParams(8))

        val customName = input("", customAssistantDraft, lctx.getString(R.string.onboarding_meet_custom_hint))
        customName.visibility = if (selectedAssistant == OTHER) View.VISIBLE else View.GONE
        body.addView(customName, spacedParams(8))
        optionViews.forEach { (value, view) ->
            view.setOnClickListener {
                selectedAssistant = value
                optionViews.forEach { (v, cell) -> setChoiceSelected(cell, v == value) }
                customName.visibility = if (value == OTHER) View.VISIBLE else View.GONE
                if (value == OTHER) customName.requestFocus()
            }
        }

        body.addView(sectionLabel(lctx.getString(R.string.onboarding_meet_user)), spacedParams(18))
        val userName = input("", userNameDraft, lctx.getString(R.string.onboarding_meet_user_hint))
        body.addView(userName, spacedParams(8))
        // It leans in while a name is being typed and nods once it has one.
        val listen = NameListener(Mood.CURIOUS)
        userName.addTextChangedListener(listen)
        customName.addTextChangedListener(listen)
        val error = errorLabel()
        body.addView(error, spacedParams(8))

        footer.addView(primaryButton(lctx.getString(R.string.onboarding_meet_continue)) {
            val assistant = if (selectedAssistant == OTHER) customName.text?.toString().orEmpty().trim()
            else selectedAssistant
            val user = userName.text?.toString().orEmpty().trim()
            customAssistantDraft = customName.text?.toString().orEmpty()
            userNameDraft = user
            if (assistant.isBlank() || user.isBlank()) {
                error.text = lctx.getString(R.string.onboarding_meet_name_required)
                error.visibility = View.VISIBLE
                mood(Mood.CONCERNED)
                return@primaryButton
            }
            OnboardingState.saveNames(context, assistant, user)
            advance(OnboardingState.Page.READY)
        })
    }

    private fun ready(body: LinearLayout, footer: LinearLayout) {
        val assistant = OnboardingState.assistantName(context)
        // Andee speaks: the title is to the user by their name, the body is
        // Andee introducing itself by the name the user just gave it.
        val user = OnboardingState.userName(context).ifBlank { assistant }
        val title = headline(lctx.getString(R.string.onboarding_ready_title, user))
        val promises = listOf(
            infoStrip("✓", lctx.getString(R.string.onboarding_ready_memory), Glass.OK),
            infoStrip("↗", lctx.getString(R.string.onboarding_ready_proactive), Glass.ACCENT),
            infoStrip("●", lctx.getString(R.string.onboarding_ready_control), Glass.WARN),
        )
        body.addView(badge(lctx.getString(R.string.onboarding_ready_badge)))
        body.addView(title, spacedParams(12))
        body.addView(bodyCopy(lctx.getString(R.string.onboarding_ready_body, assistant)), spacedParams(10))
        body.addView(panel().apply { promises.forEach { addView(it) } }, spacedParams(22))

        mood(Mood.CELEBRATE)
        beat(200) { ball?.trigger(Action.HOP) }
        val typed = typeIn(title, at = 250)
        promises.forEachIndexed { i, row -> reveal(row, typed + 200 + i * 260L) }
        footer.addView(primaryButton(lctx.getString(R.string.onboarding_ready_start)) {
            OnboardingState.complete(context)
            onComplete()
        })
    }

    // ---- Choreography -------------------------------------------------------

    private fun mood(m: Mood) {
        face = m
        paintFace()
    }

    private fun paintFace() {
        val b = ball ?: return
        if (talking) {
            b.setMood(Mood.SPEAKING)
            b.setEmotion(face)
        } else {
            b.setEmotion(null)
            b.setMood(face)
        }
    }

    // ---- Voice --------------------------------------------------------------

    /**
     * The line for this page, or null for none. The prelude is silent: it is
     * where the user decides whether Andee may speak at all, so it must not
     * speak before they have.
     */
    private fun clipFor(page: OnboardingState.Page, gate: SelfCheck.Finding?): String? = when {
        page == OnboardingState.Page.PRELUDE -> null
        page == OnboardingState.Page.PERMISSIONS && gate != null -> "perm_${gate.id}"
        else -> page.name.lowercase()
    }

    /** Play [clip] in the current language, unless it is already the one playing or sound is off. */
    private fun speak(clip: String?) {
        if (clip == null) {
            stopVoice()
            lastClip = null
            return
        }
        val key = VoiceConfig.uiLanguage(context) + "/" + clip
        if (key == lastClip) return
        stopVoice()
        lastClip = key
        if (!soundOn) return
        val mp = runCatching {
            context.assets.openFd("onboarding/$key.mp3").use { fd ->
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    prepare()
                }
            }
        }.getOrNull() ?: return
        mp.setOnCompletionListener { if (player === it) stopVoice() }
        player = mp
        talking = true
        paintFace()
        mp.start()
    }

    private fun stopVoice() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        if (talking) {
            talking = false
            paintFace()
        }
    }

    /** The ball's reward for a permission the user just went out and granted. */
    private fun celebrate() {
        mood(Mood.SUCCESS)
        ball?.signalOutcome(true)
        beat(1600) {
            mood(
                when (paintedPage) {
                    OnboardingState.Page.MODEL -> Mood.THINKING
                    OnboardingState.Page.MEET -> Mood.WAVE
                    else -> Mood.CURIOUS
                },
            )
        }
    }

    /** Run [block] at [at] ms into the scene, or now if nothing animates. */
    private fun beat(at: Long, block: () -> Unit) {
        if (motion) beats.postDelayed(block, at) else block()
    }

    /** Hide a view built in its final state and slide it in at [at]. */
    private fun reveal(view: View, at: Long, dy: Int = 10, fromX: Int = 0) {
        if (!motion) return
        view.alpha = 0f
        view.translationY = dp(dy).toFloat()
        view.translationX = dp(fromX).toFloat()
        beats.postDelayed({
            view.animate().alpha(1f).translationY(0f).translationX(0f)
                .setDuration(300).setInterpolator(Glass.EASE).start()
        }, at)
    }

    /** As [reveal], with a little overshoot: the scene's payoff. */
    private fun pop(view: View, at: Long) {
        if (!motion) return
        view.alpha = 0f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        beats.postDelayed({
            view.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(360).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
        }, at)
    }

    /** A step's ✓ lands with the ball's own tool pulse, as a real tool call does. */
    private fun land(check: View, at: Long) {
        if (!motion) return
        check.alpha = 0f
        check.scaleX = 0.3f
        check.scaleY = 0.3f
        beats.postDelayed({
            ball?.pulseTool()
            check.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
        }, at)
    }

    /**
     * Type [view]'s text in, character by character, and return when it ends.
     * The untyped tail is painted transparent rather than cut off, so the line
     * breaks where it will finally break and nothing reflows while typing.
     */
    private fun typeIn(view: TextView, at: Long, perChar: Long = 38): Long {
        val full = view.text.toString()
        if (!motion || full.isEmpty()) return at
        val clear = ForegroundColorSpan(Color.TRANSPARENT)
        fun paint(n: Int) {
            view.text = SpannableString(full).apply {
                if (n < full.length) setSpan(clear, n, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        paint(0)
        for (n in 1..full.length) beats.postDelayed({ paint(n) }, at + n * perChar)
        return at + full.length * perChar
    }

    /** Leans in while text arrives; nods when the typing pauses. */
    private inner class NameListener(private val leaning: Mood) : android.text.TextWatcher {
        private val settled = Runnable {
            mood(Mood.HAPPY)
            ball?.trigger(Action.NOD)
        }

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            mood(leaning)
            beats.removeCallbacks(settled)
            if (!s.isNullOrBlank()) beats.postDelayed(settled, 700)
        }
    }

    // ---- Components ---------------------------------------------------------

    private fun pageHeader(body: LinearLayout, badge: Int, title: Int, copy: Int) {
        body.addView(badge(lctx.getString(badge)))
        body.addView(headline(lctx.getString(title)), spacedParams(12))
        body.addView(bodyCopy(lctx.getString(copy)), spacedParams(10))
    }

    private fun badge(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.CAPTION)
        setTextColor(Color.parseColor(Glass.ACCENT))
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.03f
    }

    private fun headline(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.DISPLAY)
        setTextColor(Color.parseColor(Glass.TITLE))
        setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1.03f)
    }

    private fun bodyCopy(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.BODY)
        setTextColor(Color.parseColor(Glass.SECONDARY))
        setLineSpacing(dp(4).toFloat(), 1.08f)
    }

    private fun label(text: String, color: String, bold: Boolean = false): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.BODY)
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun sectionLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.CAPTION)
        setTextColor(Color.parseColor(Glass.MUTED))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun panel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Glass.panel(context, dp(20))
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun requestBubble(text: String): TextView = label(text, Glass.TITLE, bold = true).apply {
        background = Glass.well(context, dp(16))
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    /** A step row and its ✓, which the scene lands separately. */
    private fun flowRow(number: String, text: String): Pair<View, View> {
        val check = label("✓", Glass.OK, bold = true)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, 0)
            addView(node(number))
            addView(label(text, Glass.LABEL), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = dp(11)
            })
            addView(check)
        }
        return row to check
    }

    private fun node(text: String): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.MICRO)
        setTextColor(Color.parseColor(Glass.ON_TINT))
        setTypeface(typeface, Typeface.BOLD)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(Glass.ACCENT))
        }
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
    }

    private fun resultBox(title: String, note: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Glass.well(context, dp(16))
        setPadding(dp(14), dp(11), dp(14), dp(11))
        addView(label(title, Glass.OK, bold = true))
        addView(label(note, Glass.MUTED).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.CAPTION)
            setPadding(0, dp(3), 0, 0)
        })
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) }
    }

    private fun chatBubble(text: String, mine: Boolean): View = FrameLayout(context).apply {
        val bubble = label(text, if (mine) Glass.ON_TINT else Glass.LABEL).apply {
            background = if (mine) Glass.tinted(context, dp(17)) else Glass.well(context, dp(17))
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        addView(
            bubble,
            FrameLayout.LayoutParams(WRAP, WRAP, if (mine) Gravity.END else Gravity.START).apply {
                if (mine) marginStart = dp(32) else marginEnd = dp(32)
            },
        )
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(9) }
    }

    private fun infoStrip(icon: String, text: String, color: String): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(label(icon, color, bold = true), LinearLayout.LayoutParams(dp(26), WRAP))
            addView(label(text, Glass.SECONDARY), LinearLayout.LayoutParams(0, WRAP, 1f))
        }

    /** A monthly charge and Andee's verdict on it; the verdict lands separately. */
    private fun chargeRow(price: String, text: String, verdict: String, cancel: Boolean): Pair<View, View> {
        val tag = TextView(context).apply {
            this.text = verdict
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.MICRO)
            setTextColor(Color.parseColor(if (cancel) Glass.DANGER else Glass.OK))
            setTypeface(typeface, Typeface.BOLD)
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(11), 0, 0)
            addView(TextView(context).apply {
                this.text = price
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.MICRO)
                setTextColor(Color.parseColor(Glass.TITLE))
                setTypeface(typeface, Typeface.BOLD)
                background = Glass.well(context, dp(10))
            }, LinearLayout.LayoutParams(dp(64), dp(28)))
            addView(label(text, if (cancel) Glass.LABEL else Glass.SECONDARY),
                LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(11) })
            addView(tag, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(8) })
        }
        return row to tag
    }

    private fun modelChoice(
        title: String,
        subtitle: String,
        selected: Boolean,
        onClick: () -> Unit,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = if (selected) Glass.thumb(context, dp(18)) else Glass.panel(context, dp(18))
        setPadding(dp(10), dp(12), dp(10), dp(12))
        addView(label(title, if (selected) Glass.TITLE else Glass.LABEL, bold = true))
        addView(label(subtitle, if (selected) Glass.ACCENT else Glass.MUTED).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.MICRO)
            setPadding(0, dp(3), 0, 0)
        })
        isClickable = true
        isFocusable = true
        Glass.pressable(this)
        setOnClickListener { onClick() }
    }

    private fun fieldBlock(title: String, edit: EditText): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(sectionLabel(title))
        addView(edit, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
    }

    private fun input(
        label: String,
        value: String,
        hint: String,
        secret: Boolean = false,
    ): EditText = EditText(context).apply {
        setText(value)
        this.hint = hint
        setSingleLine(true)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.BODY)
        setTextColor(Color.parseColor(Glass.TITLE))
        setHintTextColor(Color.parseColor(Glass.MUTED))
        background = Glass.well(context, dp(14))
        setPadding(dp(13), dp(11), dp(13), dp(11))
        inputType = if (secret) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        if (secret) transformationMethod = PasswordTransformationMethod.getInstance()
        contentDescription = label.ifBlank { hint }
    }

    private fun textLink(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.BODY)
        setTextColor(Color.parseColor(Glass.ACCENT))
        setPadding(0, dp(10), 0, dp(6))
        isClickable = true
        isFocusable = true
        Glass.pressable(this)
        setOnClickListener { onClick() }
    }

    private fun choicePill(text: String, selected: Boolean): TextView = TextView(context).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.BODY)
        setPadding(dp(8), dp(11), dp(8), dp(11))
        isClickable = true
        isFocusable = true
        Glass.pressable(this)
        setChoiceSelected(this, selected)
    }

    private fun setChoiceSelected(view: TextView, selected: Boolean) {
        view.background = if (selected) Glass.thumb(context, dp(16)) else Glass.panel(context, dp(16))
        view.setTextColor(Color.parseColor(if (selected) Glass.TITLE else Glass.SECONDARY))
        view.setTypeface(view.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun errorLabel(): TextView = TextView(context).apply {
        visibility = View.GONE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.CAPTION)
        setTextColor(Color.parseColor(Glass.DANGER))
    }

    private fun primaryButton(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = "$text  →"
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Glass.Type.BODY)
        setTextColor(Color.parseColor(Glass.ON_TINT))
        setTypeface(typeface, Typeface.BOLD)
        background = Glass.tinted(context, dp(22))
        setPadding(dp(18), dp(14), dp(18), dp(14))
        isClickable = true
        isFocusable = true
        Glass.pressable(this)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun spacedParams(top: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply {
        topMargin = dp(top)
    }

    private fun dp(value: Int): Int = Glass.dp(context, value)

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val OTHER = "__other__"

        /** How far the two kinds that wait step back once "me" has landed. */
        private const val RECEDED = 0.32f
        private val REQUIRED_GATES = listOf("overlay", "accessibility", "microphone")

        /** Gates that cost a capability rather than Andee itself, and may be skipped. */
        private val SKIPPABLE = setOf("accessibility", "microphone")

        /** The ball's view; its centre is [BALL_LIFT_DP] above the card's edge. */
        private const val BALL_DP = 160
        private const val BALL_LIFT_DP = 8

        /** Room above the card for the ball's upper half. */
        private const val BALL_ABOVE_DP = BALL_DP / 2 + BALL_LIFT_DP + 4
    }
}
