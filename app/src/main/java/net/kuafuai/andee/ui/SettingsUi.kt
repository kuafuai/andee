package net.kuafuai.andee.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.StringRes
import net.kuafuai.andee.R
import net.kuafuai.andee.config.FactoryReset
import net.kuafuai.andee.config.Notebook
import net.kuafuai.andee.config.Vault
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.wake.WakeTemplates
import net.kuafuai.andee.wake.WakeWord

/**
 * How much of the notebook the settings card prints.
 *
 * Bounded because it is a `TextView` inside a scroll view inside a floating
 * card, laid out on every rebuild: a few thousand lines would be paid for on
 * each open, and again on every language switch. The cut is announced in the
 * last line — a silent truncation would read as "this is all of it".
 */
private const val NOTEBOOK_DUMP_CHARS = 6_000

/**
 * Settings overlay for editing [VoiceConfig].
 *
 * A focusable glass card centred on screen — see [Glass] for the palette, the
 * blur and the motion, all of which are shared with [VaultUi] and
 * [WakeEnrollUi] because this card opens both of them on top of itself.
 *
 * Reads the saved values once on [show] so every row draws itself already
 * filled, and writes back on 保存. Text fields follow [VoiceConfig.save]'s rule
 * — a non-empty value overwrites, an empty one keeps what was there — so a
 * field can be corrected but not cleared. That limitation is why the settings
 * that are really *choices* (`brain`, `llm_thinking`, `grounding`, `lang`) are
 * segmented pickers rather than fields you type `hub` / `on` / `off` into: a
 * picker always sends a value, and a typo in a free-text enum silently failed
 * closed with nothing on screen to say so.
 *
 * Two organising rules, because the thing this card is worst at is being long:
 *
 *  * **The brain section shows one half of itself.** `hub_url` and the DeepSeek
 *    rows are alternatives, never both in play, so picking a brain folds the
 *    other one's fields away instead of leaving the user to guess which four
 *    rows matter. It *folds* rather than flicking to `GONE`: rows that vanish
 *    instantly read as rows that were deleted.
 *  * **The 火山 endpoints start collapsed.** They ship working defaults
 *    ([VoiceConfig.DEFAULT_ASR_ENDPOINT] and friends) and the key is compiled
 *    in, so on almost every device they are read-only trivia sitting above the
 *    settings that actually get edited.
 *
 * **Language.** Every visible string comes from a resource, through [lctx], and
 * the picker sits in the header because it is the one setting whose effect is
 * the card itself. It is also the one setting written the instant it is tapped:
 * the whole card is rebuilt in the new language ([rebuild]), and that redraw is
 * the confirmation — a language switch that waited for 保存 would look broken for
 * as long as the user stared at it. Consequence, deliberate: 取消 does not put
 * the language back, because by then they are reading the answer.
 *
 * This card used to carry its own `t(zh, en)` helper, with both languages
 * written at every call site. That worked for exactly two languages and made a
 * third one a rewrite of this file, so the strings moved to `values/` and
 * `values-en/` and the helper is gone. A new language is now a new `values-xx/`
 * directory and no edit here at all.
 *
 * One string is deliberately *not* a resource: the two labels in [langPicker].
 * A language picker names each language in its own script, so 中 and EN are the
 * same in every locale.
 *
 * The 火山 key row is where a key comes from at all: [VoiceConfig.API_KEY]
 * ships empty in the public source, so an empty row means the device cannot
 * hear or speak. `SelfCheck`'s `voice_key` row says so before the user finds
 * out from a connect timeout.
 */
class SettingsUi(
    private val context: Context,
    private val onDismiss: () -> Unit,
    /**
     * 恢复出厂设置, after the user has confirmed twice. Wired to
     * `ScreenBodyService.applyFactoryReset`, which does the wiping *and*
     * rebuilds the brain — this class knows how to ask, not how to erase.
     */
    private val onFactoryReset: () -> Unit,
    /**
     * 清空聊天记录, after the user has confirmed. Wired to
     * `ScreenBodyService.clearChatHistory`, which wipes the scrollback *and*
     * rebuilds the brain so the conversation goes with it — clearing only the
     * visible rows would leave the model remembering everything the user
     * just asked to forget.
     */
    private val onClearChat: () -> Unit,
    /**
     * 清空小本本, after the user has confirmed. Wired to
     * `ScreenBodyService.clearNotebook`.
     *
     * A separate errand from [onClearChat] rather than a checkbox on it: what
     * the assistant *chose to keep* outlives any one conversation, which is
     * the point of the notebook, so wiping a conversation must not take it and
     * wiping it must not take the conversation.
     */
    private val onClearNotes: () -> Unit,
    /**
     * The language was just changed, and the rest of the UI has to follow.
     *
     * This card can rebuild itself ([rebuild]) because it owns every view it
     * draws. The ball, the scrollback and the pinned tips cannot: they belong
     * to [FloatingWindowUi], which lives in the service and outlives this card.
     * So the service passes a hook in here and the picker calls it.
     *
     * Called *before* [rebuild], so the card and the thing behind it are never
     * briefly speaking different languages while the cross-fade runs.
     *
     * Default no-op: a card built without a service behind it (a test, or any
     * future caller) still switches its own language correctly.
     */
    private val onLocaleChanged: () -> Unit = {},
) {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null

    /**
     * The card on its way out, still on screen for the length of [Glass.exit].
     * Kept so a [show] that arrives mid-exit can take it down at once instead of
     * stacking a second window on top of it.
     */
    private var closing: View? = null

    /** Text rows, by preference key. */
    private val fields = mutableMapOf<String, EditText>()

    /** Segmented rows, by preference key; each lambda reads back what is picked. */
    private val picks = mutableMapOf<String, () -> String>()

    /** Saved values, read once per [show]. See the class KDoc. */
    private var saved: Map<String, String> = emptyMap()

    /** English rather than Chinese. Resolved in [show]; flipped by [rebuild]. */
    private var english = false

    /**
     * Strings in the user's language, refreshed at the top of [build].
     *
     * Refreshed *there* and not in [show] on purpose: the language picker calls
     * [rebuild], which goes straight to [build] without passing through [show],
     * and rewrapping in the one function that consumes it means no future
     * caller has to remember to do it.
     *
     * Views are still built on [context]; only text comes from here. See
     * [AppLocale] for why those two are deliberately different Contexts.
     */
    private var lctx: Context = context

    /** Survives a [rebuild], so switching language doesn't re-hide the group. */
    private var advancedOpen = false

    /** Same, for the notebook dump. Kept apart so opening one does not unfold the other. */
    private var notebookOpen = false

    /**
     * The 诊断 section is showing the factory-reset warning instead of its
     * three buttons. Survives a [rebuild] so a language switch mid-confirmation
     * does not silently put the destructive button back.
     */
    private var factoryConfirm = false

    /** Same, for the 清空聊天记录 warning. One confirm at a time. */
    private var clearChatConfirm = false

    /** Same, for the 清空小本本 warning. */
    private var clearNotesConfirm = false

    /**
     * The form's scroller, so [rebuild] can put the user back where they were.
     * It did not matter while the destructive buttons were in the footer — the
     * footer never scrolls — but their confirms now appear in 诊断, near the
     * bottom of a long form, and a rebuild that starts at the top leaves the
     * user looking at the brain section wondering what their tap did.
     */
    private var scroll: ScrollView? = null

    /** Whether the compositor agreed to blur. Decides the fill — see [Glass.frost]. */
    private var frosted = false

    fun isShowing(): Boolean = root != null

    fun show() {
        // A re-open during the closing animation: drop the outgoing card now
        // rather than letting two of them share the screen.
        closing?.let { runCatching { wm.removeView(it) }; OwnCard.hidden() }
        closing = null
        if (root != null) return

        saved = VoiceConfig.current(context)
        english = saved["lang"] == VoiceConfig.LANG_EN
        val p = WindowManager.LayoutParams(
            cardWidth(), cardHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // No FLAG_NOT_FOCUSABLE: this window takes keyboard focus so EditText works.
            // No FLAG_LAYOUT_NO_LIMITS either — that flag lets a window lay out past
            // the screen edges, which on a phone is exactly how a card sized for a
            // tablet goes half-missing instead of being clamped to something visible.
            0,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            // The IME is most of a phone screen. Shrink the card instead of letting
            // the keyboard sit on top of the field being typed into; the form is
            // already in a ScrollView, so it absorbs the loss.
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
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
            // ignore
        }
    }

    /**
     * Swap the whole card for a freshly built one, in place.
     *
     * Same trick as [VaultUi.rebuild], including reusing the old LayoutParams,
     * and no [OwnCard] bookkeeping: the card was up before and is up after, so
     * counting it again would leave `annotate` permanently convinced a card is
     * open. Typed-but-unsaved text is carried across through [saved] — losing
     * a half-entered API key to a language tap would be its own bug.
     *
     * It cross-fades rather than cutting. The swap is `removeView` +
     * `addView` on a *window*, so a hard cut shows one frame of whatever is
     * behind the card, which looks like the card blinked out of existence.
     */
    private fun rebuild() {
        val old = root ?: return
        saved = saved + currentEdits()
        // null means "the bottom", resolved once the new form has a height.
        // Not Int.MAX_VALUE: ScrollView.clamp() evaluates `viewport + y`, which
        // overflows to negative, skips the clamp and scrolls the form clean off
        // the card — an empty sheet with a header and a footer.
        val offset: Int? =
            // A confirm is taller than the buttons it replaces — the factory
            // list alone is five lines — so holding the old offset leaves its
            // 确认 row below the fold. 诊断 is the last section and the danger
            // zone is the last thing in it, so the bottom *is* the confirm.
            if (clearChatConfirm || clearNotesConfirm || factoryConfirm) null else (scroll?.scrollY ?: 0)
        Glass.fadeOut(old) {
            if (root !== old) return@fadeOut
            fields.clear()
            picks.clear()
            val v = build()
            runCatching {
                v.hideFromAccessibility()
                val p = old.layoutParams as WindowManager.LayoutParams
                wm.removeView(old)
                wm.addView(v, p)
                root = v
                // After layout, or the scroller has no content to scroll over
                // and clamps the offset to zero.
                scroll?.let { sv ->
                    sv.post { sv.scrollTo(0, offset ?: (sv.getChildAt(0)?.height ?: 0)) }
                }
                Glass.fadeIn(v)
            }
        }
    }

    /**
     * Preferred size, capped to what the screen actually has.
     *
     * **Both constants were tablet-phone numbers on a tablet.** 500×600 dp on
     * the 1600×2560 / 360dpi device this ships on is 711×1138 dp of screen, so
     * the card used about half the width and half the height — and it read as
     * small rather than as compact, because the form inside it had to shrink
     * its own type to fit rather than being given room.
     *
     * The width is now the screen minus a gutter, and the height the screen
     * minus room for the status bar and the gesture pill, both clamped again
     * by [cardWidth]/[cardHeight] against the real bounds so a phone is still
     * usable. The gutter is deliberately asymmetric — 20dp left, right, and
     * bottom — because the card is centred and the leftover lands on all four
     * sides anyway; the point is only that it does not touch the edge.
     */
    private fun cardWidth(): Int {
        val screen = wm.currentWindowMetrics.bounds.width()
        return minOf(screen - dp(2 * GUTTER_DP), (screen * 0.98f).toInt())
    }

    private fun cardHeight(): Int {
        val screen = wm.currentWindowMetrics.bounds.height()
        return minOf(screen - dp(2 * GUTTER_DP), (screen * 0.96f).toInt())
    }

    fun hide() {
        val v = root ?: return
        root = null
        closing = v
        fields.clear()
        picks.clear()
        scroll = null
        Glass.exit(v) {
            if (closing === v) {
                runCatching { wm.removeView(v) }
                closing = null
                OwnCard.hidden()
            }
            onDismiss()
        }
    }

    // ---- Build ----

    private fun build(): View {
        lctx = AppLocale.wrap(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.card(context, dp(28), frosted)
            // So the sheen and the panels stay inside the rounded corners.
            clipToOutline = true
        }

        card.addView(header(), matchWrap())

        val sv = ScrollView(context).apply {
            isFillViewport = true
            // The card already has an edge; a scrollbar track on top of glass
            // is one line too many.
            isVerticalScrollBarEnabled = false
        }
        scroll = sv
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Was dp(18) all round, sized against a 500dp card. On a card that
            // now spans nearly the screen, the old padding makes every row look
            // like it lost its margin — and the vertical axis is the one that
            // matters, because the form is a scroll view and every dp of dead
            // space at the top is a dp the user scrolls past to see nothing.
            setPadding(dp(24), dp(4), dp(24), dp(24))
        }

        brainSection(form)
        voiceSection(form)
        notebookSection(form)
        notifySection(form)
        wakeSection(form)
        typingSection(form)
        vaultSection(form)
        diagnosticsSection(form)

        sv.addView(form, matchWrap())
        card.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        card.addView(footerBar(), matchWrap())
        return card
    }

    @SuppressLint("SetTextI18n")
    private fun header(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(22), dp(18), dp(14))
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(context).apply {
            text = lctx.getString(R.string.settings_title)
            // 21 was sized against the old, smaller card. On a card this wide
            // a 21sp title is the first thing that reads as undersized, and it
            // is the one line that has to carry the sheet's identity.
            textSize = 26f
            setTextColor(Color.parseColor(Glass.TITLE))
            letterSpacing = 0.01f
        })
        titles.addView(TextView(context).apply {
            text = saved["device_name"].orEmpty().ifEmpty { lctx.getString(R.string.settings_default_device_name) }
            textSize = 13f
            setTextColor(Color.parseColor(Glass.MUTED))
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(langPicker())
        row.addView(closeButton(), LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            marginStart = dp(12)
        })
        return row
    }

    @SuppressLint("SetTextI18n")
    private fun closeButton(): View = TextView(context).apply {
        text = "✕"
        // 13dp of glyph in a 36dp circle, on a card the user can now see
        // properly. It was 13 in a 32dp circle, which is a target you have to
        // aim at; 15 in 36 is one you can hit without looking.
        textSize = 15f
        setTextColor(Color.parseColor(Glass.SECONDARY))
        gravity = Gravity.CENTER
        // A circle, so it reads as a button at a size where a bare glyph would
        // just look like punctuation that happens to be tappable.
        background = Glass.panel(context, dp(18))
        isClickable = true
        isFocusable = true
        Glass.pressable(this)
        setOnClickListener { hide() }
    }

    /**
     * 中文 / English, and the only picker that acts on the tap.
     *
     * Deliberately not in [picks]: it is already on disk by the time [rebuild]
     * runs, and re-writing it from 保存 would be the same value twice.
     */
    private fun langPicker(): View = segments(
        // Deliberately not resources. A language picker labels each language in
        // its own script, so 中 stays 中 and EN stays EN no matter what the card
        // is currently speaking — these two are the only strings in the app that
        // must NOT be translated, and translating them is the one way to make
        // the picker unusable.
        options = listOf(
            VoiceConfig.LANG_ZH to "中",
            VoiceConfig.LANG_EN to "EN",
        ),
        picked = if (english) VoiceConfig.LANG_EN else VoiceConfig.LANG_ZH,
        radius = dp(14),
        compact = true,
    ) { lang ->
        VoiceConfig.save(context, mapOf("lang" to lang))
        english = lang == VoiceConfig.LANG_EN
        // The overlays behind this card first, then this card. Both are cheap;
        // the order only matters so that the two are never briefly in different
        // languages while the cross-fade runs.
        onLocaleChanged()
        rebuild()
    }

    private fun footerBar(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // END, not the old weight trick: the row used to carry
            // 恢复出厂设置 on the left with weight 1 to push this pair right.
            // The destructive buttons have moved into 诊断 (see [dangerZone]),
            // so there is nothing on the left to push against.
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(18), dp(10), dp(18), dp(18))
        }
        row.addView(button(lctx.getString(R.string.common_cancel), filled = false) { hide() })
        row.addView(View(context), LinearLayout.LayoutParams(dp(10), 1))
        row.addView(button(lctx.getString(R.string.common_save), filled = true) { saveAndHide() })
        return row
    }

    // ---- Sections ----

    /**
     * Who does the thinking, and how to reach them.
     *
     * The two halves are mutually exclusive on the service side too
     * (`ScreenBodyService.startBrain` builds one brain or the other), so the
     * picker folds away the half that is not in play.
     */
    private fun brainSection(form: LinearLayout) {
        val panel = group(
            form,
            lctx.getString(R.string.settings_section_brain),
            lctx.getString(R.string.settings_brain_panel_note),
        )

        // Assigned below, once both blocks exist; the picker's callback runs
        // only on a tap, so the null window closes before anyone can tap.
        var applyMode: ((String, Boolean) -> Unit)? = null
        val mode = segmented(
            panel,
            "brain",
            listOf(
                // Local leftmost because the leftmost option is the fallback
                // when the saved value matches nothing, and local is the
                // out-of-box default — see [VoiceConfig.brainConfig].
                VoiceConfig.BRAIN_LOCAL to lctx.getString(R.string.settings_brain_local),
                VoiceConfig.BRAIN_HUB to lctx.getString(R.string.settings_brain_cloud),
            ),
        ) { applyMode?.invoke(it, true) }

        val hubBlock = block(panel)
        field(
            hubBlock,
            "hub_url",
            lctx.getString(R.string.settings_hub_url),
            hint = lctx.getString(R.string.settings_hub_url_hint),
        )
        field(
            hubBlock,
            "device_name",
            lctx.getString(R.string.settings_device_name),
            hint = lctx.getString(R.string.settings_device_name_hint),
        )
        note(
            hubBlock,
            lctx.getString(R.string.settings_brain_cloud_note),
        )

        val localBlock = block(panel)
        field(localBlock, "llm_api_key", "DeepSeek Key", hint = "sk-…", secret = true)
        field(localBlock, "llm_model", lctx.getString(R.string.settings_llm_model), hint = VoiceConfig.DEFAULT_LLM_MODEL)
        field(
            localBlock,
            "llm_base_url",
            lctx.getString(R.string.settings_llm_base_url),
            hint = VoiceConfig.DEFAULT_LLM_BASE_URL,
        )

        rowLabel(localBlock, lctx.getString(R.string.settings_llm_thinking))
        var applyThinking: ((String, Boolean) -> Unit)? = null
        val thinking = segmented(
            localBlock,
            "llm_thinking",
            listOf("enabled" to lctx.getString(R.string.common_on), "disabled" to lctx.getString(R.string.common_off)),
        ) { applyThinking?.invoke(it, true) }

        val effortBlock = block(localBlock)
        rowLabel(effortBlock, lctx.getString(R.string.settings_llm_effort))
        segmented(
            effortBlock,
            "llm_reasoning_effort",
            listOf(
                "high" to lctx.getString(R.string.common_high),
                "medium" to lctx.getString(R.string.common_medium),
                "low" to lctx.getString(R.string.common_low),
            ),
        )
        note(
            localBlock,
            lctx.getString(R.string.settings_brain_local_note),
        )

        applyMode = { m, animate ->
            val local = m == VoiceConfig.BRAIN_LOCAL
            // Both at once, not one after the other: one block shrinking while
            // the other grows keeps the card's total height almost still, and
            // sequencing them makes the whole form jump twice.
            Glass.setVisible(hubBlock, !local, animate)
            Glass.setVisible(localBlock, local, animate)
        }
        applyMode(mode, false)
        applyThinking = { th, animate ->
            Glass.setVisible(effortBlock, th != "disabled", animate)
        }
        applyThinking(thinking, false)
    }

    /**
     * The wake phrase is taught by saying it, not by typing it. It lives here
     * rather than on the ball because it is a once-per-device chore — see
     * [WakeEnrollUi].
     */
    private fun wakeSection(form: LinearLayout) {
        val panel = group(form, lctx.getString(R.string.settings_section_wake))
        fun summary(): String =
            if (WakeTemplates.enrolled(context)) {
                lctx.getString(R.string.settings_wake_enrolled)
            } else {
                lctx.getString(R.string.settings_wake_not_enrolled)
            }

        val state = TextView(context).apply {
            text = summary()
            textSize = 13f
            setTextColor(Color.parseColor(Glass.LABEL))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        panel.addView(state, rowParams(0))

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(lctx.getString(R.string.settings_wake_record), filled = false) {
            WakeEnrollUi(context) { state.text = summary() }.show()
        })
        row.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        row.addView(button(lctx.getString(R.string.common_clear), filled = false) {
            WakeTemplates.clear(context)
            WakeWord.reload()
            state.text = summary()
        })
        panel.addView(row, rowParams(dp(12)))
    }

    /**
     * Same shape as [wakeSection], and for the same reason: the real screen is
     * its own overlay ([VaultUi]) because the list grows and this card is
     * already full. Only the count and the door belong here.
     */
    private fun vaultSection(form: LinearLayout) {
        val panel = group(form, lctx.getString(R.string.settings_section_vault))
        fun summary(): String {
            val n = Vault.entries(context).size
            return if (n == 0) {
                lctx.getString(R.string.settings_vault_empty_note)
            } else {
                lctx.getString(R.string.settings_vault_count_note, n)
            }
        }

        val state = TextView(context).apply {
            text = summary()
            textSize = 13f
            setTextColor(Color.parseColor(Glass.LABEL))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        panel.addView(state, rowParams(0))

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(lctx.getString(R.string.settings_vault_manage), filled = false) {
            VaultUi(context) { state.text = summary() }.show()
        })
        panel.addView(row, rowParams(dp(12)))
    }

    /** 火山 endpoints. Collapsed, because they ship working and rarely move. */
    private fun voiceSection(form: LinearLayout) {
        val panel = collapsible(
            form,
            lctx.getString(R.string.settings_section_voice),
            lctx.getString(R.string.settings_voice_factory_note),
        )
        // The one overridable credential. Blank means *factory*, which is what
        // an untouched box shows — see VoiceConfig.save / OVERRIDE_KEYS. The key
        // is masked like any other credential.
        field(
            panel, "api_key",
            lctx.getString(R.string.settings_voice_api_key),
            hint = lctx.getString(R.string.settings_voice_api_key_hint),
            secret = true,
        )
        // Feedback for the row above. Without it, "did my key actually take
        // effect?" is only answerable by reading shared_prefs over adb.
        readOnly(
            panel,
            lctx.getString(R.string.settings_voice_in_use),
            if (VoiceConfig.usingOwnCredentials(context)) {
                lctx.getString(R.string.settings_voice_in_use_own)
            } else {
                lctx.getString(R.string.settings_voice_in_use_factory)
            },
        )
        field(panel, "asr_endpoint", lctx.getString(R.string.settings_voice_asr_endpoint))
        field(panel, "asr_resource_id", lctx.getString(R.string.settings_voice_asr_resource_id))
        field(panel, "tts_endpoint", lctx.getString(R.string.settings_voice_tts_endpoint))
        field(panel, "tts_resource_id", lctx.getString(R.string.settings_voice_tts_resource_id))
        field(panel, "tts_sample_rate", lctx.getString(R.string.settings_voice_tts_sample_rate), numeric = true)
    }

    private fun diagnosticsSection(form: LinearLayout) {
        val panel = group(form, lctx.getString(R.string.settings_section_diagnostics))
        rowLabel(panel, lctx.getString(R.string.settings_grounding))
        // 关 first, and not for looks: the leftmost option is what an
        // unrecognised saved value falls back to, and this one writes pictures
        // of whatever the user is doing. Same direction as
        // VoiceConfig.groundingEnabled, which treats anything but "on" as off.
        segmented(
            panel,
            "grounding",
            listOf("off" to lctx.getString(R.string.common_off), "on" to lctx.getString(R.string.common_on)),
        )
        note(
            panel,
            lctx.getString(R.string.settings_grounding_note),
        )
        readOnly(
            panel,
            lctx.getString(R.string.settings_device_id),
            saved["device_id"].orEmpty().ifEmpty { "—" },
        )
        dangerZone(panel)
    }

    /**
     * The three destructive actions, together at the foot of 诊断.
     *
     * They used to live in the footer beside 取消/保存, and that was the
     * problem: a destructive control sitting next to the card's two ways out
     * reads as a third way out. Here they sit under the section that is
     * already about the device's stored state, and each replaces this row
     * with its own warning before it does anything — the confirm appears
     * where the tap landed, not down in the footer where the button no
     * longer is.
     *
     * Ordered by how much they take: this conversation, everything it ever
     * chose to remember, then the device itself.
     */
    private fun dangerZone(panel: LinearLayout) {
        when {
            clearChatConfirm -> {
                panel.addView(confirmTitle(lctx.getString(R.string.settings_clear_chat_confirm_title)))
                panel.addView(confirmBody(lctx.getString(R.string.settings_clear_chat_confirm_body)))
                panel.addView(confirmRow(
                    back = { clearChatConfirm = false; rebuild() },
                    confirmLabel = lctx.getString(R.string.settings_clear_chat_confirm),
                ) {
                    onClearChat()
                    hide()
                })
            }
            clearNotesConfirm -> {
                panel.addView(confirmTitle(lctx.getString(R.string.settings_clear_notes_confirm_title)))
                panel.addView(confirmBody(lctx.getString(R.string.settings_clear_notes_confirm_body)))
                panel.addView(confirmRow(
                    back = { clearNotesConfirm = false; rebuild() },
                    confirmLabel = lctx.getString(R.string.settings_clear_notes_confirm),
                ) {
                    onClearNotes()
                    hide()
                })
            }
            factoryConfirm -> {
                panel.addView(confirmTitle(lctx.getString(R.string.settings_factory_reset_confirm_title)))
                // The list comes from [FactoryReset] itself rather than being
                // typed here — the same list the wipe walks. What the user
                // agrees to and what the code does are therefore the same
                // thing by construction, which is the only arrangement
                // acceptable for an irreversible action.
                panel.addView(confirmBody(
                    lctx.getString(R.string.settings_factory_reset_list_intro) + "\n" +
                        FactoryReset.items(lctx).joinToString("\n") { "· $it" } + "\n" +
                        lctx.getString(R.string.settings_factory_reset_after),
                ))
                panel.addView(confirmRow(
                    back = { factoryConfirm = false; rebuild() },
                    confirmLabel = lctx.getString(R.string.settings_factory_reset_confirm),
                ) {
                    onFactoryReset()
                    hide()
                })
            }
            else -> {
                // Stacked, not side by side. Half the panel leaves ~210px of
                // text room at this density, which is not enough for "Clear
                // chat history" — it wrapped to three lines and the pair came
                // out taller than two full-width buttons. Full width also
                // stops the layout depending on how long the label is in
                // whichever language the card is in.
                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(14), 0, 0)
                }
                fun danger(@StringRes label: Int, arm: () -> Unit) {
                    if (col.childCount > 0) {
                        col.addView(View(context), LinearLayout.LayoutParams(1, dp(10)))
                    }
                    col.addView(
                        button(lctx.getString(label), filled = false, danger = true) { arm() },
                        matchWrap(),
                    )
                }
                danger(R.string.settings_clear_chat) { clearChatConfirm = true; rebuild() }
                danger(R.string.settings_clear_notes) { clearNotesConfirm = true; rebuild() }
                danger(R.string.settings_factory_reset) { factoryConfirm = true; rebuild() }
                panel.addView(col, matchWrap())
            }
        }
    }

    /** The bold red line at the head of a destructive confirm. */
    private fun confirmTitle(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.parseColor(Glass.DANGER))
        letterSpacing = 0.02f
    }

    /** The grey explanation under it. [text] is pre-joined by the caller. */
    private fun confirmBody(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor(Glass.MUTED))
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(0, dp(8), 0, 0)
    }

    /**
     * [不，返回] above [确认…] — back out is never the red button.
     *
     * Stacked and full width, like the two buttons this replaces: side by side
     * the pair overflowed the panel and "Erase everything" broke mid-word.
     */
    private fun confirmRow(
        back: () -> Unit,
        confirmLabel: String,
        confirm: () -> Unit,
    ): View {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        col.addView(
            button(lctx.getString(R.string.settings_factory_reset_back), filled = false) { back() },
            matchWrap(),
        )
        col.addView(View(context), LinearLayout.LayoutParams(1, dp(10)))
        col.addView(button(confirmLabel, filled = false, danger = true) { confirm() }, matchWrap())
        return col
    }

    /**
     * The notebook: what this device remembers about you, and what it owes you.
     *
     * Three things only — the switch for the quiet-hour review, its cost, and a
     * way to read the book. There is deliberately no editing, no searching and
     * no deleting here: the model keeps the notebook, and the one power a person
     * needs over a device that claims to remember them is **to be able to see
     * what it wrote down**.
     */
    private fun notebookSection(form: LinearLayout) {
        val panel = group(form, lctx.getString(R.string.settings_section_notebook))
        // On first, matching VoiceConfig.sweepConfig's fail-open reading: this
        // is the feature that makes the device feel like it knows you, and
        // turning it off is a deliberate act.
        segmented(
            panel,
            "sweep",
            listOf("on" to lctx.getString(R.string.common_on), "off" to lctx.getString(R.string.common_off)),
        )
        note(
            panel,
            lctx.getString(R.string.settings_notebook_sweep_note),
        )
        field(
            panel,
            "sweep_quiet_minutes",
            lctx.getString(R.string.settings_notebook_quiet_minutes),
            hint = VoiceConfig.DEFAULT_SWEEP_QUIET_MINUTES.toString(),
            numeric = true,
        )
        field(
            panel,
            "sweep_daily_cap",
            lctx.getString(R.string.settings_notebook_daily_cap),
            hint = VoiceConfig.DEFAULT_SWEEP_DAILY_CAP.toString(),
            numeric = true,
        )

        // The bill, such as it is. These calls are paid for with the user's own
        // key, so the count and the spend are shown rather than hidden.
        val used = Notebook.sweepCountToday(context)
        val spent = Notebook.sweepTokensToday(context)
        val remembered = Notebook.memories(context).size
        val open = Notebook.todos(context, "pending").size
        readOnly(
            panel,
            lctx.getString(R.string.settings_notebook_today),
            lctx.getString(
                R.string.settings_notebook_stats,
                used, spent, remembered, open,
            ),
        )

        val dump = collapsible(
            form,
            lctx.getString(R.string.settings_notebook_dump),
            lctx.getString(R.string.settings_notebook_dump_hint),
            isOpen = { notebookOpen },
            setOpen = { notebookOpen = it },
        )
        dump.addView(notebookDumpView(), matchWrap())
    }

    /**
     * Which notifications the device may think about by itself.
     *
     * One setting, three tiers, and the reason it is not a per-app checklist is
     * that the question has three honest answers and a list of installed apps
     * has none of them: a checklist is a screen nobody finishes, and it goes
     * stale the next time the user installs something.
     *
     * It sits here rather than in 诊断 because it is the same kind of setting as
     * the notebook sweep directly above — both are the device choosing to spend
     * the user's key on something nobody asked for out loud — and a user who is
     * deciding how chatty this thing is allowed to be should find both decisions
     * in one place.
     */
    private fun notifySection(form: LinearLayout) {
        val panel = group(form, lctx.getString(R.string.settings_section_notify))
        // 关 leftmost, which is the fallback for an unrecognised saved value —
        // the same direction VoiceConfig.notifyScope reads in, and deliberately
        // *not* the same as its default. An unset preference is a device nobody
        // configured and gets 聊天类; an unreadable one is a value this build
        // does not understand and gets nothing. See that method.
        segmented(
            panel,
            "notify",
            listOf(
                VoiceConfig.NOTIFY_OFF to lctx.getString(R.string.common_off),
                VoiceConfig.NOTIFY_CHAT to lctx.getString(R.string.settings_notify_chat),
                VoiceConfig.NOTIFY_ALL to lctx.getString(R.string.settings_notify_all),
            ),
        )
        note(
            panel,
            lctx.getString(R.string.settings_notify_note),
        )
    }

    /**
     * The book itself, as text.
     *
     * Truncated at [NOTEBOOK_DUMP_CHARS] rather than paginated: this is a
     * TextView inside a scroll view inside a floating card, and a few thousand
     * lines of text would make every layout pass of that card expensive. The
     * truncation says so in the last line — a silent cut-off would read as "this
     * is everything".
     */
    private fun notebookDumpView(): TextView {
        val full = Notebook.dumpText(context)
        val body = if (full.length <= NOTEBOOK_DUMP_CHARS) {
            full
        } else {
            full.take(NOTEBOOK_DUMP_CHARS) +
                "\n\n" +
                lctx.getString(
                    R.string.settings_notebook_dump_truncated,
                    full.length - NOTEBOOK_DUMP_CHARS,
                )
        }
        return TextView(context).apply {
            text = body
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor(Glass.SECONDARY))
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
        }
    }

    // ---- Widgets ----

    /** Section title + its panel. Returns the panel; rows go in there. */
    private fun group(form: LinearLayout, title: String, note: String? = null): LinearLayout {
        form.addView(sectionTitle(title), matchWrap())
        if (note != null) form.addView(noteView(note), matchWrap())
        val panel = panel()
        form.addView(panel, rowParams(dp(10)))
        return panel
    }

    /**
     * The keyboard, and the one thing about it worth telling the user.
     *
     * Nothing to configure. The device's input method is the user's own — the
     * one they can type on in any app — and the brain borrows ADBKeyboard only
     * inside a single `type_text` call. So this section exists to say that out
     * loud (it is the answer to "why does my keyboard keep changing?"), and to
     * name the one state where nothing can be typed at all: a tablet whose
     * single IME is the headless one. See [net.kuafuai.andee.ui.ImeSwitch].
     */
    private fun typingSection(form: LinearLayout) {
        val panel = group(form, lctx.getString(R.string.settings_section_typing))
        val human = net.kuafuai.andee.ui.ImeSwitch.typable(context)
        if (human.isEmpty()) {
            note(
                panel,
                lctx.getString(R.string.settings_typing_no_ime),
            )
            return
        }
        val now = net.kuafuai.andee.ui.ImeSwitch.current(context)
        readOnly(
            panel,
            lctx.getString(R.string.settings_typing_current),
            net.kuafuai.andee.ui.ImeSwitch.label(context, now),
        )
        note(
            panel,
            lctx.getString(R.string.settings_typing_note),
        )
    }

    /**
     * As [group], but the panel folds away behind a chevron.
     *
     * The chevron rotates rather than swapping between 展开 / 收起, which also
     * spares the pair of strings: a quarter turn means the same thing in both
     * languages.
     *
     * @param isOpen / @param setOpen which flag remembers the state. Parameterised
     *   so two foldable panels can exist without one unfolding the other.
     */
    private fun collapsible(
        form: LinearLayout,
        title: String,
        note: String,
        isOpen: () -> Boolean = { advancedOpen },
        setOpen: (Boolean) -> Unit = { advancedOpen = it },
    ): LinearLayout {
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
        }
        head.addView(sectionTitle(title), LinearLayout.LayoutParams(0, WRAP, 1f))
        val chevron = TextView(context).apply {
            text = "›"
            textSize = 17f
            setTextColor(Color.parseColor(Glass.ACCENT))
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(4), 0)
            rotation = if (isOpen()) 90f else 0f
        }
        head.addView(chevron)
        form.addView(head, matchWrap())
        form.addView(noteView(note), matchWrap())

        val panel = panel().apply {
            visibility = if (isOpen()) View.VISIBLE else View.GONE
        }
        form.addView(panel, rowParams(dp(10)))
        head.setOnClickListener {
            val next = !isOpen()
            setOpen(next)
            Glass.setVisible(panel, next, animate = true)
            chevron.animate()
                .rotation(if (next) 90f else 0f)
                .setDuration(240)
                .setInterpolator(Glass.EASE)
                .start()
        }
        return panel
    }

    private fun sectionTitle(title: String): TextView = TextView(context).apply {
        text = title
        textSize = 13f
        setTextColor(Color.parseColor(Glass.ACCENT))
        letterSpacing = 0.08f
        setPadding(0, dp(22), 0, dp(8))
    }

    private fun panel(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = Glass.panel(context, dp(20))
    }

    /** A bare container, used only so a group of rows can be folded at once. */
    private fun block(parent: LinearLayout): LinearLayout {
        val v = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(v, matchWrap())
        return v
    }

    private fun rowLabel(parent: LinearLayout, text: String) {
        parent.addView(TextView(context).apply {
            this.text = text
            // 12 was legible only because the card was small enough to read at
            // a glance. It is now the smallest thing on a much larger surface.
            textSize = 13f
            setTextColor(Color.parseColor(Glass.LABEL))
        }, rowParams(dp(16)))
    }

    private fun note(parent: LinearLayout, text: String) {
        parent.addView(noteView(text), rowParams(dp(10)))
    }

    private fun noteView(text: String): TextView = TextView(context).apply {
        this.text = text
        // Same reason as [rowLabel]. Notes are the longest strings in the card
        // and the ones a user actually reads, so they can afford the space
        // least of all — but at 11sp on a 690dp-wide card they end up as one
        // line each, which is both harder to read and denser-looking than the
        // rows they sit between.
        textSize = 12.5f
        setTextColor(Color.parseColor(Glass.MUTED))
        setLineSpacing(dp(4).toFloat(), 1f)
    }

    private fun readOnly(parent: LinearLayout, title: String, value: String) {
        rowLabel(parent, title)
        parent.addView(TextView(context).apply {
            text = value
            textSize = 14.5f
            setTextColor(Color.parseColor(Glass.SECONDARY))
        }, rowParams(dp(4)))
    }

    /**
     * One text row.
     *
     * @param secret renders masked with a 显示 / Show toggle. The key is the
     *   user's own and this card is an overlay on top of whatever they are
     *   doing, so it has no business printing it at full size by default.
     */
    private fun field(
        parent: LinearLayout,
        key: String,
        label: String,
        hint: String? = null,
        numeric: Boolean = false,
        secret: Boolean = false,
    ) {
        rowLabel(parent, label)
        val et = EditText(context).apply {
            background = Glass.well(context, dp(13))
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setTextColor(Color.parseColor(Glass.TITLE))
            setHintTextColor(Color.parseColor(Glass.MUTED))
            // A bigger card made every row a shorter-looking row: 14sp in a
            // full-width well on a 690dp card is a lot of empty space holding
            // one line of text. Sizing the field up is what fills that space
            // with something rather than just making it emptier.
            textSize = 15f
            if (hint != null) setHint(hint)
            // isSingleLine before the mask, as in VaultUi: the other order lets
            // it undo the password transformation and the dots come out as text.
            isSingleLine = true
            inputType = when {
                numeric -> InputType.TYPE_CLASS_NUMBER
                secret -> secretType(numeric = false)
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (secret) setMasked(true)
            setText(saved[key].orEmpty())
        }
        fields[key] = et

        if (!secret) {
            parent.addView(et, rowParams(dp(5)))
            return
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(et, LinearLayout.LayoutParams(0, WRAP, 1f))
        var shown = false
        row.addView(TextView(context).apply {
            text = lctx.getString(R.string.common_show)
            textSize = 13f
            setTextColor(Color.parseColor(Glass.ACCENT))
            setPadding(dp(14), dp(8), dp(2), dp(8))
            isClickable = true
            setOnClickListener {
                shown = !shown
                et.setMasked(!shown)
                et.setSelection(et.text?.length ?: 0)
                text = if (shown) lctx.getString(R.string.common_hide) else lctx.getString(R.string.common_show)
            }
        })
        parent.addView(row, rowParams(dp(5)))
    }

    /**
     * A full-width segmented picker for a setting with a handful of legal values.
     *
     * @param options preference value to the label shown for it; the first is
     *   the fallback when nothing is saved or the saved string is not one of
     *   them — which is the same fail-closed rule [VoiceConfig.brainConfig] and
     *   [VoiceConfig.groundingEnabled] apply when reading.
     * @return the value it starts on, so the caller can set up whatever depends
     *   on it without re-deriving the fallback.
     */
    private fun segmented(
        parent: LinearLayout,
        key: String,
        options: List<Pair<String, String>>,
        onPick: (String) -> Unit = {},
    ): String {
        val current = saved[key].orEmpty().trim().lowercase()
        val start = options.firstOrNull { it.first == current }?.first ?: options.first().first
        var picked = start
        val track = segments(options, start, dp(13)) { picked = it; onPick(it) }
        picks[key] = { picked }
        parent.addView(track, rowParams(dp(7)))
        return start
    }

    /**
     * The picker itself, and the one control here with real motion in it.
     *
     * A single **thumb** slides between the cells instead of each cell swapping
     * its own background. That is the whole reason this is a `FrameLayout` with
     * a free-floating child rather than a row of styled `TextView`s: a
     * background that blinks from one cell to another reads as two separate
     * things happening, while one pane moving reads as *this control* changing
     * value — which is what actually happened.
     *
     * The thumb is positioned from the cells' measured geometry rather than from
     * weights, so the same code serves the full-width pickers (equal cells) and
     * the compact language pair (中 is narrower than EN). Placement therefore
     * has to wait for layout, and re-runs on every later layout pass — the card
     * resizes when the IME opens.
     */
    private fun segments(
        options: List<Pair<String, String>>,
        picked: String,
        radius: Int,
        compact: Boolean = false,
        onPick: (String) -> Unit,
    ): View {
        val inset = dp(3)
        val track = FrameLayout(context).apply {
            setPadding(inset, inset, inset, inset)
            background = Glass.well(context, radius)
        }
        val thumb = View(context).apply {
            background = Glass.thumb(context, radius - inset)
        }
        // 1×1, and sized from the cell in place() — *not* MATCH_PARENT. A plain
        // View measured against an AT_MOST spec reports the whole spec back, so
        // a MATCH_PARENT thumb in this WRAP_CONTENT track made the track as tall
        // as the space offered it: the header then ate the card and the form's
        // weighted ScrollView got nothing.
        track.addView(thumb, FrameLayout.LayoutParams(1, 1))
        val cellRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        track.addView(cellRow, FrameLayout.LayoutParams(MATCH, WRAP))

        val cells = mutableListOf<TextView>()
        val onColor = Color.parseColor(Glass.TITLE)
        val offColor = Color.parseColor(Glass.SECONDARY)
        var index = options.indexOfFirst { it.first == picked }.coerceAtLeast(0)
        var sliding = false

        fun place(to: Int, animate: Boolean) {
            val cell = cells.getOrNull(to) ?: return
            if (cell.width == 0 || cell.height == 0) {
                // Not laid out yet. View.post before attach queues until it is.
                track.post { place(to, false) }
                return
            }
            val toX = cell.left.toFloat()
            val toY = cell.top.toFloat()
            val toW = cell.width
            val toH = cell.height
            val lp = thumb.layoutParams
            if (!animate) {
                // The guard matters: this runs from a layout listener, and
                // requesting layout from inside one with nothing to change is
                // how a layout loop starts.
                if (lp.width == toW && lp.height == toH && thumb.translationX == toX) return
                lp.width = toW
                lp.height = toH
                thumb.layoutParams = lp
                thumb.translationX = toX
                thumb.translationY = toY
                for ((i, c) in cells.withIndex()) c.setTextColor(if (i == to) onColor else offColor)
                return
            }
            val fromX = thumb.translationX
            val fromW = lp.width
            val fromColors = cells.map { it.currentTextColor }
            sliding = true
            // Height does not animate: every cell in a track is the same height,
            // so only x and width have anywhere to travel.
            lp.height = toH
            thumb.translationY = toY
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260
                interpolator = Glass.EASE
                addUpdateListener { a ->
                    val f = a.animatedFraction
                    thumb.translationX = fromX + (toX - fromX) * f
                    lp.width = (fromW + (toW - fromW) * f).toInt()
                    thumb.layoutParams = lp
                    for ((i, c) in cells.withIndex()) {
                        val target = if (i == to) onColor else offColor
                        c.setTextColor(Glass.lerpColor(fromColors[i], target, f))
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        sliding = false
                    }
                })
                start()
            }
        }

        for ((i, option) in options.withIndex()) {
            val (value, label) = option
            val cell = TextView(context).apply {
                text = label
                textSize = if (compact) 12f else 13f
                gravity = Gravity.CENTER
                setTextColor(if (i == index) onColor else offColor)
                if (compact) setPadding(dp(12), dp(6), dp(12), dp(6))
                else setPadding(dp(6), dp(10), dp(6), dp(10))
                isClickable = true
                setOnClickListener {
                    if (index == i) return@setOnClickListener
                    index = i
                    place(i, animate = true)
                    onPick(value)
                }
            }
            cells += cell
            if (compact) cellRow.addView(cell)
            else cellRow.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
        }

        cellRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!sliding) place(index, animate = false)
        }
        place(index, animate = false)
        return track
    }

    private fun button(
        label: String, filled: Boolean, danger: Boolean = false, onClick: () -> Unit,
    ): TextView {
        val radius = dp(24)
        return TextView(context).apply {
            text = label
            // The footer's buttons were the card's primary targets and were
            // sized for a card narrower than this one. Larger type and more
            // padding is what makes 保存 / 取消 hit-or-miss avoidable on a
            // tablet held in two hands, which is how it is actually used.
            textSize = 15f
            setTextColor(
                Color.parseColor(
                    if (danger) Glass.DANGER else if (filled) Glass.TITLE else Glass.LABEL,
                ),
            )
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

    // ---- Data ----

    /** What is on screen right now, saved or not. Used by [rebuild]. */
    private fun currentEdits(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for ((k, et) in fields) out[k] = et.text?.toString().orEmpty()
        for ((k, read) in picks) out[k] = read()
        return out
    }

    private fun saveAndHide() {
        val updates = mutableMapOf<String, String>()
        for ((k, et) in fields) {
            val v = et.text?.toString()?.trim().orEmpty()
            if (v.isNotEmpty()) updates[k] = v
        }
        // Pickers go in unconditionally: their value is never blank, which is
        // what lets a setting actually be turned back off — VoiceConfig.save()
        // drops blank values, so a text field never could.
        for ((k, read) in picks) updates[k] = read()
        if (updates.isNotEmpty()) VoiceConfig.save(context, updates)
        hide()
    }

    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)

    private fun rowParams(top: Int) = LinearLayout.LayoutParams(MATCH, WRAP).apply {
        topMargin = top
    }

    private fun dp(v: Int): Int = Glass.dp(context, v)

    companion object {
        /**
         * The gap between the card and the screen edge, each side.
         *
         * The screen, not a fixed width, is what the card is sized from — see
         * [cardWidth]. What this constant buys is the *feel*: a card flush to
         * the edge reads as a system dialog hijacking the screen, while one
         * with a margin reads as ours floating above it.
         */
        private const val GUTTER_DP = 20

        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
