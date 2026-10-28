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
     * `BodyCoreService.applyFactoryReset`, which does the wiping *and*
     * rebuilds the brain — this class knows how to ask, not how to erase.
     */
    private val onFactoryReset: () -> Unit,
    /**
     * 清空聊天记录, after the user has confirmed. Wired to
     * `BodyCoreService.clearChatHistory`, which wipes the scrollback *and*
     * rebuilds the brain so the conversation goes with it — clearing only the
     * visible rows would leave the model remembering everything the user
     * just asked to forget.
     */
    private val onClearChat: () -> Unit,
    /**
     * 清空小本本, after the user has confirmed. Wired to
     * `BodyCoreService.clearNotebook`.
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
    /**
     * Open a web page. An overlay cannot launch a browser itself (the
     * background-launch restriction), so the host does it: the service via
     * `SelfCheckActivity.showForErrand`, the Activity host directly. The host
     * also takes this card down first — an overlay left up would sit on top of
     * the page it just opened.
     */
    private val onOpenLink: (String) -> Unit = {},
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
        // Devices → 扫码配对 opens the camera from on top of this card; see [StandAside].
        if (aside == null) aside = StandAside.register(wm) { root }
    }

    private var aside: Any? = null

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
        val screen = Glass.usableSize(context).first
        return minOf(screen - dp(2 * GUTTER_DP), (screen * 0.98f).toInt())
    }

    private fun cardHeight(): Int {
        val screen = Glass.usableSize(context).second
        return minOf(screen - dp(2 * GUTTER_DP), (screen * 0.96f).toInt())
    }

    fun hide() {
        val v = root ?: return
        root = null
        aside?.let { StandAside.unregister(it) }; aside = null
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

        // Split in two, and the split is the whole of item 5 of the review: the
        // card used to be eleven sections in one flat column, in which the
        // question "what can I actually set here" took a full scroll to answer
        // because the answer was interleaved with endpoints and a factory-reset
        // button. 常用 is what a person opens settings for; 高级 is everything
        // that answers a question they have not asked yet.
        //
        // Order inside 常用 is by how early the errand comes up, not
        // alphabetically: who does the thinking, then how it hears you, then
        // what it is allowed to keep, then what it may interrupt you for, then
        // the credentials it can spend. 大脑 stays first because it is the one
        // setting that changes what the rest of the card means.
        brainSection(form)
        // 外观 is the only appearance setting left up here: 形象与表情 moved into 高级,
        // so the "these two are the same kind of setting" argument went with it. What is
        // left in 常用 is what a person opens settings to change.
        // The same order as the Mac's settings page. 形象与表情, 装备 and 保险箱 are opened from here on both bodies;
        // 小本本 and 朋友 have their own keys on the top bar (and the Mac's sidebar), so this card only holds
        // the notebook's review settings and no door to the friends list.
        themeSection(form)
        expressionsSection(form)
        wakeSection(form)
        notebookSection(form)
        gearSection(form)
        notifySection(form)
        vaultSection(form)
        maxStepsSection(form)

        advancedSection(form)

        sv.addView(form, matchWrap())
        card.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        card.addView(footerBar(), matchWrap())
        return card
    }

    /**
     * 单次任务最多步数: the per-task runaway guard. In 常用 rather than 高级
     * because it is a knob a user might actually turn — "the AI gave up
     * halfway" has one cause and one fix, and burying it behind a disclosure
     * means the user cannot find the fix on their own.
     */
    private fun maxStepsSection(form: LinearLayout) {
        val panel = group(
            form,
            lctx.getString(R.string.settings_max_steps),
            lctx.getString(R.string.settings_max_steps_note),
        )
        field(
            panel,
            "max_steps",
            lctx.getString(R.string.settings_max_steps),
            hint = VoiceConfig.DEFAULT_MAX_STEPS.toString(),
            numeric = true,
        )
    }

    /**
     * 高级: the three sections a configured device never opens.
     *
     * A foldable *group of sections* rather than one more collapsible section —
     * the only one in the card — because its members draw their own headings and
     * panels and putting a panel around them would nest one inside another. That
     * is what `bare = true` is for; see [collapsible].
     *
     * It is closed by default, which is the point: nothing it hides is needed
     * to make the device work out of the box. The 火山 key and endpoints
     * (which used to live here) are now rows of the 本机 tab under 后端, next
     * to the LLM they power; this disclosure now carries show-cloud-option,
     * typing diagnostics and the factory-reset button.
     *
     * The note under the heading is not decoration and is not optional: a
     * collapsed disclosure gives no account of itself, so a user who has lost
     * 恢复出厂设置 has no way to know it is behind this chevron and not gone.
     */
    private fun advancedSection(form: LinearLayout) {
        val body = collapsible(
            form,
            lctx.getString(R.string.settings_section_advanced),
            lctx.getString(R.string.settings_advanced_note),
            bare = true,
        )
        // 火山 (the voice section) used to live here. It is now a sub-panel
        // of 后端 → 本机, so a user looking for its rows finds them next to
        // the backend they belong to — see [brainSection].
        // 形象与表情, moved down here from 常用. It is a door rather than a setting —
        // see [expressionsSection] — and a door that most users never open belongs
        // behind the disclosure rather than in the column everyone scrolls.
        showHubSection(body)
        typingSection(body)
        diagnosticsSection(body)
    }

    /**
     * 显示云端选项: lives in 高级 rather than beside the backend picker because
     * it is a knob a user turns once (if ever) to reveal a tab they want. It
     * writes eagerly and rebuilds the whole card, so flipping it on and
     * scrolling back up finds the 云端 tab already in the backend picker.
     */
    private fun showHubSection(form: LinearLayout) {
        val panel = group(
            form,
            lctx.getString(R.string.settings_show_hub),
            lctx.getString(R.string.settings_show_hub_note),
        )
        val row = segments(
            options = listOf("off" to lctx.getString(R.string.common_off), "on" to lctx.getString(R.string.common_on)),
            picked = if (VoiceConfig.showHub(context)) "on" else "off",
            radius = dp(13),
        ) { v ->
            VoiceConfig.save(context, mapOf("show_hub" to v))
            rebuild()
        }
        panel.addView(row, rowParams(dp(7)))
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
            textSize = Glass.Type.DISPLAY
            setTextColor(Color.parseColor(Glass.TITLE))
            letterSpacing = 0.01f
        })
        titles.addView(TextView(context).apply {
            text = saved["device_name"].orEmpty().ifEmpty { lctx.getString(R.string.settings_default_device_name) }
            textSize = Glass.Type.CAPTION
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
        textSize = Glass.Type.BODY
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
     * (`BodyCoreService.startBrain` builds one brain or the other), so the
     * picker folds away the half that is not in play.
     */
    /**
     * The backend section. Three tabs — 本机 / CodeFlying / 云端 — only one is
     * ever in play, and the picker folds away the two that are not.
     *
     * Visibility rules:
     *  * **CodeFlying** is only in the picker when the build enables it with a
     *    non-empty domain (the build's CODEFLYING_DOMAIN). The tab
     *    itself has no editable rows — endpoints and credential are compiled
     *    in — just a note describing what the hosted backend offers.
     *  * **云端** is only in the picker when the user has turned the "显示
     *    云端选项" toggle on (`show_hub`, saved eagerly so the picker rebuilds
     *    at once). It is off by default: a device nobody has configured for a
     *    hub is better off not seeing an option it has nothing to fill in.
     *
     * Voice (`asr_endpoint` / `tts_endpoint` / `api_key` and the resource IDs)
     * used to live inside 高级 → 火山. It now lives inside the **本机** tab,
     * because voice runs on this device whichever backend answers, but a
     * CodeFlying device never touches these fields and a 云端 device cares
     * about hub plumbing instead.
     */
    private fun brainSection(form: LinearLayout) {
        val panel = group(
            form,
            lctx.getString(R.string.settings_section_brain),
            lctx.getString(R.string.settings_brain_panel_note),
        )

        // Tab picker. 本机 and CodeFlying are always visible — CodeFlying stays
        // in the row even when the build didn't configure it, so the user is
        // told what it is and how to turn it on. 云端 is still gated on the
        // user's own show_hub toggle: it is the only tab that is pure extra
        // configuration surface, so nobody who hasn't asked for it needs to
        // see it. If the user picks CodeFlying without a configured build,
        // [VoiceConfig.brainMode] silently falls back to local at runtime —
        // the saved value sticks, so the picker rebuilds correctly, and
        // installing a configured build later flips the behaviour on without
        // the user having to re-pick.
        // CodeFlying leads: it is the default tab on a fresh install, and the
        // leftmost cell is the fallback (see [segmented]).
        val options = buildList {
            add(VoiceConfig.BRAIN_CODEFLYING to lctx.getString(R.string.settings_brain_codeflying))
            add(VoiceConfig.BRAIN_LOCAL to lctx.getString(R.string.settings_brain_local))
            if (VoiceConfig.showHub(context)) {
                add(VoiceConfig.BRAIN_HUB to lctx.getString(R.string.settings_brain_cloud))
            }
        }
        var applyMode: ((String, Boolean) -> Unit)? = null
        // Filled in by the picker itself: the "no key?" line below has to move the
        // tab the same way a tap on the cell would, or the card would keep showing
        // 本机 while saving CodeFlying.
        var pickBrain: (String) -> Unit = {}
        val mode = segmented(panel, "brain", options, setterRef = { pickBrain = it }) { applyMode?.invoke(it, true) }

        // ---- 云端 tab ----
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
        note(hubBlock, lctx.getString(R.string.settings_brain_cloud_note))

        // ---- CodeFlying tab ----
        // No "connected or not": the tab is the default, and the one thing it has
        // to say is how to get a key while there is none. With a key it says what
        // the backend is.
        val cfBlock = block(panel)
        val cfKeyed = VoiceConfig.codeFlyingKey(context).isNotEmpty()
        // Same words and same link whether or not a key is set: the tab does not
        // describe the state, the key box below does.
        note(cfBlock, lctx.getString(R.string.settings_brain_codeflying_note))
        note(cfBlock, lctx.getString(R.string.settings_codeflying_get_key))
        linkNote(cfBlock, lctx.getString(R.string.settings_codeflying_open_site)) {
            onOpenLink(lctx.getString(R.string.codeflying_url))
        }
        // Shows the key in effect (typed, else the build's); editing it overrides.
        // The server address is the build's and has no row. The key row is always
        // here: it is where a person who just got a key from the link above puts it.
        field(
            cfBlock,
            "codeflying_key",
            lctx.getString(R.string.settings_codeflying_key),
            hint = "kft_…",
            secret = true,
            copyValue = { VoiceConfig.codeFlyingKey(context) },
        )

        // ---- 本机 tab: LLM + 火山 voice rows ----
        val localBlock = block(panel)
        // The hint *is* the value a blank row resolves to, so the hint and the
        // resolved config come from the same language-aware pair — see
        // [VoiceConfig.llmModelDefault]. An English card advertises an OpenAI
        // model / base URL and, if the user never saves one, actually calls it;
        // a Chinese card stays on DeepSeek.
        field(
            localBlock,
            "llm_api_key",
            lctx.getString(R.string.settings_llm_api_key),
            hint = "sk-…",
            secret = true,
        )
        // Directly under the row that asks for it: whoever has no key is
        // reading this row, not the picker at the top of the card. Tapping
        // moves the tab under their finger rather than sending them up to
        // find which cell that was — and it only moves the pick; 保存 still
        // commits it, like every other row.
        linkNote(localBlock, lctx.getString(R.string.settings_no_key_use_codeflying)) {
            pickBrain(VoiceConfig.BRAIN_CODEFLYING)
        }
        field(
            localBlock,
            "llm_model",
            lctx.getString(R.string.settings_llm_model),
            hint = VoiceConfig.llmModelDefault(context),
        )
        field(
            localBlock,
            "llm_base_url",
            lctx.getString(R.string.settings_llm_base_url),
            hint = VoiceConfig.llmBaseUrlDefault(context),
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
        note(localBlock, lctx.getString(R.string.settings_brain_local_note))

        // ---- Which service hears and speaks ----
        //
        // One container, so that the whole block can be taken out in one move
        // rather than view by view — see the visibility flip at the end of it.
        //
        // **Not inside `localBlock`**, even though it reads like a 本机
        // concern: who hears and who thinks are two different questions, and
        // hanging this off the 本机 tab would hide it from a 云端 device that
        // legitimately wants to point its own voice service somewhere.
        //
        // The two sets of rows are mutually exclusive and are not two flavours
        // of the same thing: 火山's are endpoints and resource ids, Google's are
        // a project, a location and two model names.
        val voiceBlock = block(panel)
        // 听（识别）：谁把用户的话转成文字。系统引擎没有识别器，所以这一
        // 侧只有 火山/谷歌。
        rowLabel(voiceBlock, lctx.getString(R.string.settings_voice_hear_provider))
        var applyProvider: ((String, Boolean) -> Unit)? = null
        val provider = segmented(
            voiceBlock,
            "voice_provider",
            listOf(
                // 火山 first, because the first option is the fallback when
                // nothing is saved — and for every device that predates this
                // row, 火山 is what it has been running. See
                // [VoiceConfig.voiceProvider].
                VoiceConfig.VOICE_HUOSHAN to lctx.getString(R.string.settings_voice_provider_huoshan),
                VoiceConfig.VOICE_GOOGLE to lctx.getString(R.string.settings_voice_provider_google),
            ),
        ) { applyProvider?.invoke(it, true) }

        // 说（朗读）：谁把答案读出来。可以选系统自带的引擎——免费、离线。
        rowLabel(voiceBlock, lctx.getString(R.string.settings_voice_speak_provider))
        var speakPicked = ""
        val speak = segmented(
            voiceBlock,
            "speak_provider",
            listOf(
                VoiceConfig.VOICE_HUOSHAN to lctx.getString(R.string.settings_voice_provider_huoshan),
                VoiceConfig.VOICE_GOOGLE to lctx.getString(R.string.settings_voice_provider_google),
                VoiceConfig.VOICE_SYSTEM to lctx.getString(R.string.settings_voice_provider_system),
            ),
        ) {
            speakPicked = it
            applyProvider?.invoke(provider, true)
        }
        speakPicked = speak

        // 火山: previously in 高级 → 火山. Rows copy that section's labels,
        // which still describe exactly these keys.
        val huoshanBlock = block(voiceBlock)
        field(
            huoshanBlock,
            "api_key",
            lctx.getString(R.string.settings_voice_api_key),
            hint = lctx.getString(R.string.settings_voice_api_key_hint),
            secret = true,
        )
        // Same line, same reason, under the voice key: this is the other half
        // of what CodeFlying would hand over for free.
        linkNote(huoshanBlock, lctx.getString(R.string.settings_no_key_use_codeflying)) {
            pickBrain(VoiceConfig.BRAIN_CODEFLYING)
        }
        field(huoshanBlock, "asr_endpoint", lctx.getString(R.string.settings_voice_asr_endpoint))
        field(huoshanBlock, "asr_resource_id", lctx.getString(R.string.settings_voice_asr_resource_id))
        field(huoshanBlock, "tts_endpoint", lctx.getString(R.string.settings_voice_tts_endpoint))
        field(huoshanBlock, "tts_resource_id", lctx.getString(R.string.settings_voice_tts_resource_id))
        field(
            huoshanBlock,
            "tts_sample_rate",
            lctx.getString(R.string.settings_voice_tts_sample_rate),
            numeric = true,
        )

        // ---- Google: one Vertex Express key, two protocols ----
        //
        // Every hint here is the value a blank row resolves to, from the same
        // constants [VoiceConfig.load] reads — the same rule the LLM rows
        // follow, for the same reason: a card that advertises a model but
        // silently calls another is lying where the user goes to decide.
        // A blank row means "use whatever the build carries", which for a
        // packaged-for-Google APK is a real key and a real project number. So
        // the hint has to say that, or the user reads an empty box as "not set
        // up" while the device is happily talking to Google with a credential
        // they never typed. The key's *value* is never shown — a settings card
        // is not a place to print a secret — but the project number is not one,
        // and printing it is how the row can be checked at a glance.
        val builtInKey = VoiceConfig.googleApiKeyDefault()
        val builtInProject = VoiceConfig.googleProjectDefault()

        val googleBlock = block(voiceBlock)
        field(
            googleBlock,
            "google_api_key",
            lctx.getString(R.string.settings_google_api_key),
            hint = if (builtInKey.isNotEmpty()) {
                lctx.getString(R.string.settings_google_key_builtin)
            } else {
                lctx.getString(R.string.settings_google_api_key_hint)
            },
            secret = true,
        )
        linkNote(googleBlock, lctx.getString(R.string.settings_no_key_use_codeflying)) {
            pickBrain(VoiceConfig.BRAIN_CODEFLYING)
        }
        field(
            googleBlock,
            "google_project",
            lctx.getString(R.string.settings_google_project),
            hint = builtInProject.ifEmpty {
                lctx.getString(R.string.settings_google_project_hint)
            },
        )
        field(
            googleBlock,
            "google_location",
            lctx.getString(R.string.settings_google_location),
            hint = VoiceConfig.googleLocationDefault(),
        )
        field(
            googleBlock,
            "google_asr_model",
            lctx.getString(R.string.settings_google_asr_model),
            hint = VoiceConfig.googleAsrModelDefault(),
        )
        field(
            googleBlock,
            "google_tts_model",
            lctx.getString(R.string.settings_google_tts_model),
            hint = VoiceConfig.googleTtsModelDefault(),
        )
        field(
            googleBlock,
            "google_base_url",
            lctx.getString(R.string.settings_google_base_url),
            hint = VoiceConfig.googleBaseUrlDefault(),
        )
        field(
            googleBlock,
            "google_language",
            lctx.getString(R.string.settings_google_language),
            hint = lctx.getString(R.string.settings_google_language_hint),
        )
        note(googleBlock, lctx.getString(R.string.settings_voice_google_note))

        applyProvider = { p, animate ->
            // A provider's block is shown if it is picked on *either* side. The
            // blocks hold both the hearing and speaking rows, so hiding one
            // because the *hearing* picker does not name it would bury the key
            // field for the provider the *speaking* picker just chose.
            val useHuoshan = p == VoiceConfig.VOICE_HUOSHAN || speakPicked == VoiceConfig.VOICE_HUOSHAN
            val useGoogle = p == VoiceConfig.VOICE_GOOGLE || speakPicked == VoiceConfig.VOICE_GOOGLE
            Glass.setVisible(huoshanBlock, useHuoshan, animate)
            Glass.setVisible(googleBlock, useGoogle, animate)
        }
        applyProvider(provider, false)

        // **The voice block is not drawn at all under the hosted backend**, and
        // not merely disabled. There, [VoiceConfig.voiceProvider] pins 火山 and
        // [VoiceConfig.load] takes every endpoint and credential from the build
        // — so every control in this block is read by nothing. A row the user
        // can edit that changes no behaviour is the small lie this card exists
        // to avoid, and the honest surface for "what is actually in use" is the
        // self-check page, which grades the real provider rather than a saved
        // string.
        //
        // **Its flip belongs to the mode picker, not to this build pass.** It
        // used to be a lone `setVisible` evaluated once here, against the
        // *saved* mode, so picking 本机 out of CodeFlying left the block hidden
        // until 保存 rebuilt the card — a control that only appears if you
        // press the button standing next to it. Same condition; the difference
        // is that it is asked of the value the user just picked, through
        // [VoiceConfig.resolveBrainMode], so a build that does not enable
        // CodeFlying still shows the block its runtime is actually reading.
        //
        // One block visible at a time. Simultaneous flips so the card's total
        // height barely moves — see the old comment, still true.
        applyMode = { m, animate ->
            Glass.setVisible(hubBlock, m == VoiceConfig.BRAIN_HUB, animate)
            Glass.setVisible(cfBlock, m == VoiceConfig.BRAIN_CODEFLYING, animate)
            Glass.setVisible(localBlock, m == VoiceConfig.BRAIN_LOCAL, animate)
            // Keyed on the tab the user is looking at, not on what the build
            // can resolve it to: CodeFlying carries its own voice, and a voice
            // key row under that tab is a row nothing reads.
            Glass.setVisible(voiceBlock, m != VoiceConfig.BRAIN_CODEFLYING, animate)
        }
        applyMode(mode, false)
        applyThinking = { th, animate ->
            Glass.setVisible(effortBlock, th != "disabled", animate)
        }
        applyThinking(thinking, false)
    }

    /**
     * 浅色 / 深色 — which of [Glass]'s two palettes the whole app draws with.
     *
     * **Eager, like the language picker and for the same reason**: the redraw *is* the
     * confirmation, and routing this through 保存 would mean the card you are looking
     * at is the one thing that does not change until you leave it.
     *
     * [Theme.set] is what does the work, and it reaches every live surface itself —
     * see its note on [Theme.onChanged]. All this has to do is move its own picker,
     * which [rebuild] handles.
     *
     * Not in [picks]: it is already on disk by the time [rebuild] runs, the same
     * arrangement [langPicker] has.
     */
    private fun themeSection(form: LinearLayout) {
        val panel = group(
            form,
            lctx.getString(R.string.settings_section_theme),
            lctx.getString(R.string.settings_theme_note),
        )
        val row = segments(
            options = listOf(
                Theme.DAY to lctx.getString(R.string.settings_theme_day),
                Theme.NIGHT to lctx.getString(R.string.settings_theme_night),
            ),
            picked = if (Theme.night) Theme.NIGHT else Theme.DAY,
            radius = dp(13),
        ) { v ->
            Theme.set(context, v == Theme.NIGHT)
            rebuild()
        }
        panel.addView(row, rowParams(dp(7)))
    }

    /**
     * The door to [ExpressionsUi]. Nothing is edited here — the card it opens is
     * where a look is picked and where every expression can be tried — so the
     * section is a sentence and one button, like [vaultSection]'s door, and the
     * look picked inside is saved the moment it is picked rather than on 保存.
     *
     * Called from [advancedSection] rather than from the form's own list, which is
     * where it used to be. The argument for moving it is in the name: it is a door,
     * and what it opens is a card for *trying* looks rather than for setting
     * anything. 常用 is the column a person scrolls looking for a knob; a door they
     * open once should not be in it. Its body is unchanged — it takes its parent and
     * draws the same section — so the only thing that moved is who calls it.
     */
    private fun expressionsSection(form: LinearLayout) {
        val panel = group(form, lctx.getString(R.string.settings_section_expr))
        panel.addView(
            TextView(context).apply {
                text = lctx.getString(R.string.settings_expr_note)
                textSize = Glass.Type.CAPTION
                setTextColor(Color.parseColor(Glass.LABEL))
                setLineSpacing(dp(3).toFloat(), 1f)
            },
            rowParams(0),
        )
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(lctx.getString(R.string.settings_expr_open), filled = false) {
            ExpressionsUi(context).show()
        })
        panel.addView(row, rowParams(dp(12)))
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
            textSize = Glass.Type.CAPTION
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

        // The screen-off half of the wake word, as one button that carries its
        // own state. Two buttons in a picker would read as a choice to make;
        // this is a thing that is on or off, and tapping it says which.
        //
        // Written straight through rather than on 保存, like the language and
        // the ball's look: the gate that reads it reconsiders ten times a
        // second, so the new answer is live before the finger is off the
        // button, and there is nothing left here for a 保存 to confirm.
        val lockedBtn = button("", filled = false) {}
        fun paintLocked() {
            val on = VoiceConfig.wakeWhileLocked(context)
            lockedBtn.text = lctx.getString(
                if (on) R.string.settings_wake_locked_on else R.string.settings_wake_locked_off,
            )
            // Same pair [button] picks with, so the on state reads as the
            // filled chip it would have been had it been born on.
            lockedBtn.setTextColor(Color.parseColor(if (on) Glass.ON_TINT else Glass.LABEL))
            lockedBtn.background =
                if (on) Glass.tinted(context, dp(24)) else Glass.panel(context, dp(24))
        }
        lockedBtn.setOnClickListener {
            VoiceConfig.setWakeWhileLocked(context, !VoiceConfig.wakeWhileLocked(context))
            paintLocked()
        }
        paintLocked()
        panel.addView(lockedBtn, rowParams(dp(12)))
        // The cost, said out loud next to the switch rather than buried in a
        // README: with the screen off this holds the microphone open, and the
        // platform says so on the status bar whether we mention it or not.
        note(panel, lctx.getString(R.string.settings_wake_locked_note))
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
            textSize = Glass.Type.CAPTION
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
        textSize = Glass.Type.BODY
        setTextColor(Color.parseColor(Glass.DANGER))
        letterSpacing = 0.02f
    }

    /** The grey explanation under it. [text] is pre-joined by the caller. */
    private fun confirmBody(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = Glass.Type.CAPTION
        // SECONDARY, same reason as [noteView]: this is the warning body on a
        // factory-reset / clear-chat confirm — the one thing the user must
        // actually read before tapping the red button.
        setTextColor(Color.parseColor(Glass.SECONDARY))
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
    /**
     * 装备: a summary here, the card is where it is read and edited. A door like [vaultSection]'s, not a
     * setting: what is installed and what Andee has learned about it is a list, not a switch.
     */
    private fun gearSection(form: LinearLayout) {
        val panel = group(form, lctx.getString(R.string.settings_section_gear))
        val shelf = net.kuafuai.andee.device.AndroidGear(context)
        fun summary(): String {
            val all = shelf.gear.forCard()
            return if (all.isEmpty()) {
                lctx.getString(R.string.settings_gear_empty_note)
            } else {
                lctx.getString(R.string.settings_gear_count_note, all.size, all.count { it.first.does.isNotBlank() })
            }
        }
        val state = TextView(context).apply {
            text = summary()
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(Glass.LABEL))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        panel.addView(state, rowParams(0))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(lctx.getString(R.string.settings_gear_manage), filled = false) {
            // Scan first if the shelf has never been read, so the card is not an empty promise.
            Thread { shelf.ensureScanned(); android.os.Handler(android.os.Looper.getMainLooper()).post {
                net.kuafuai.andee.ui.GearUi(context, shelf) { state.text = summary() }.show()
            } }.apply { isDaemon = true }.start()
        })
        panel.addView(row, rowParams(dp(12)))
    }

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
        // Read-only, like the Mac's line. Browsing memories and scenes is the top bar's 小本本 and 情景 keys.
        readOnly(
            panel,
            lctx.getString(R.string.settings_notebook_today),
            notebookStats(),
        )
    }

    private fun notebookStats(): String = lctx.getString(
        R.string.settings_notebook_stats,
        Notebook.sweepCountToday(context),
        Notebook.sweepTokensToday(context),
        Notebook.memories(context).size,
        Notebook.todos(context, "pending").size,
    )

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
     * @param bare the returned container carries no fill of its own, so the
     *   sections that go inside it stay flush with the ones above rather than
     *   sitting in a panel inside a panel. Used by 高级, which is a *group of
     *   sections* rather than a section — the only disclosure here whose
     *   children draw their own headings and panels. See [advancedSection].
     */
    private fun collapsible(
        form: LinearLayout,
        title: String,
        note: String,
        isOpen: () -> Boolean = { advancedOpen },
        setOpen: (Boolean) -> Unit = { advancedOpen = it },
        bare: Boolean = false,
    ): LinearLayout {
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
        }
        head.addView(sectionTitle(title), LinearLayout.LayoutParams(0, WRAP, 1f))
        val chevron = TextView(context).apply {
            text = "›"
            textSize = Glass.Type.TITLE
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
            if (bare) {
                setPadding(0, 0, 0, 0)
                background = null
            }
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
        textSize = Glass.Type.CAPTION
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
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(Glass.LABEL))
        }, rowParams(dp(16)))
    }

    private fun note(parent: LinearLayout, text: String) {
        parent.addView(noteView(text), rowParams(dp(10)))
    }

    /** [note], tinted and tappable: an explanation that is also the way out of it. */
    private fun linkNote(parent: LinearLayout, text: String, onClick: () -> Unit) {
        parent.addView(noteView(text).apply {
            setTextColor(Color.parseColor(Glass.ACCENT))
            isClickable = true
            isFocusable = true
            Glass.pressable(this)
            setOnClickListener { onClick() }
        }, rowParams(dp(10)))
    }

    private fun noteView(text: String): TextView = TextView(context).apply {
        this.text = text
        // Same reason as [rowLabel]. Notes are the longest strings in the card
        // and the ones a user actually reads, so they can afford the space
        // least of all — but at 11sp on a 690dp-wide card they end up as one
        // line each, which is both harder to read and denser-looking than the
        // rows they sit between.
        //
        // SECONDARY, not MUTED: these are explanations the user is meant to
        // read, so one step brighter against the card fill — MUTED is the
        // right shade for a timestamp or an inactive icon, not a paragraph.
        textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(Glass.SECONDARY))
        setLineSpacing(dp(4).toFloat(), 1f)
    }

    private fun readOnly(parent: LinearLayout, title: String, value: String): TextView {
        rowLabel(parent, title)
        val v = TextView(context).apply {
            text = value
            textSize = Glass.Type.BODY
            setTextColor(Color.parseColor(Glass.SECONDARY))
        }
        parent.addView(v, rowParams(dp(4)))
        return v
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
        /** When set, a 复制 link sits beside the 显示 toggle and copies this (the value in effect, even if the box is blank). */
        copyValue: (() -> String)? = null,
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
            textSize = Glass.Type.BODY
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
            textSize = Glass.Type.CAPTION
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
        if (copyValue != null) {
            row.addView(TextView(context).apply {
                text = lctx.getString(R.string.common_copy)
                textSize = Glass.Type.CAPTION
                setTextColor(Color.parseColor(Glass.ACCENT))
                setPadding(dp(10), dp(8), dp(2), dp(8))
                isClickable = true
                setOnClickListener {
                    val v = et.text?.toString().orEmpty().trim().ifEmpty { copyValue() }
                    if (v.isNotEmpty()) {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("CodeFlying", v))
                        text = lctx.getString(R.string.common_copied)
                        postDelayed({ text = lctx.getString(R.string.common_copy) }, 1500)
                    }
                }
            })
        }
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
        /** Handed the picker's own select, so a link can pick a value for the user. */
        setterRef: (((String) -> Unit) -> Unit)? = null,
        onPick: (String) -> Unit = {},
    ): String {
        val current = saved[key].orEmpty().trim().lowercase()
        val start = options.firstOrNull { it.first == current }?.first ?: options.first().first
        var picked = start
        val track = segments(options, start, dp(13), setterRef = setterRef) { picked = it; onPick(it) }
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
        /**
         * Handed [select], for a value to be picked from code rather than by
         * tapping a cell — a link under a row that offers another tab has to
         * move the thumb too, or the card shows one thing and saves another.
         *
         * It is handed the picker's own *select*, so its argument is that
         * function, not a value: the caller stores it and calls it later.
         *
         * Named before [onPick] so that every caller's trailing lambda still
         * lands on [onPick], where it always has.
         */
        setterRef: (((String) -> Unit) -> Unit)? = null,
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

        // The one path a pick takes, whether it came from a tap or from code.
        // The thumb, the two label colours and [onPick] are a single change, and
        // a caller that moved only some of them would leave the card saving a
        // value it is not showing.
        fun select(value: String) {
            val i = options.indexOfFirst { it.first == value }.coerceAtLeast(0)
            if (index == i) return
            index = i
            place(i, animate = true)
            onPick(value)
        }

        for ((i, option) in options.withIndex()) {
            val (value, label) = option
            val cell = TextView(context).apply {
                text = label
                textSize = if (compact) Glass.Type.MICRO else Glass.Type.CAPTION
                gravity = Gravity.CENTER
                setTextColor(if (i == index) onColor else offColor)
                if (compact) setPadding(dp(12), dp(6), dp(12), dp(6))
                else setPadding(dp(6), dp(10), dp(6), dp(10))
                isClickable = true
                setOnClickListener { select(value) }
            }
            cells += cell
            if (compact) cellRow.addView(cell)
            else cellRow.addView(cell, LinearLayout.LayoutParams(0, WRAP, 1f))
        }

        cellRow.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!sliding) place(index, animate = false)
        }
        place(index, animate = false)
        setterRef?.invoke { select(it) }
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
            textSize = Glass.Type.BODY
            setTextColor(
                Color.parseColor(
                    if (danger) Glass.DANGER else if (filled) Glass.ON_TINT else Glass.LABEL,
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
            // Overridable keys go in even when blank: blank is how the user
            // says "go back to the compiled default", and VoiceConfig.save()
            // deletes the pref when it sees one. Non-overridable keys only go
            // in when non-empty, so an untouched field doesn't wipe a saved
            // value.
            if (v.isNotEmpty() || k in VoiceConfig.OVERRIDE_KEYS) {
                updates[k] = v
            }
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
