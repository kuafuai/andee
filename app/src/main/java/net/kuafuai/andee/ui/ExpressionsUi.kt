package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.config.CustomAvatar
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.ui.ball.BallLooks
import net.kuafuai.andee.ui.ball.EmotionBallTextureView
import net.kuafuai.andee.ui.ball.Mood

/**
 * Every character and every expression, to look at and to choose.
 *
 * The card holds a ball of its own — a second [EmotionBallTextureView], not the
 * one in the corner — and two rows of chips under it: who (the six looks) and how
 * it feels (the expressions). Tapping a look makes it the ball's look for real,
 * the same write the swipe makes; tapping an expression only changes what *this*
 * card's ball is wearing, and nothing else is touched, so it is safe to poke at
 * every one of them.
 *
 * **Why a second ball and not the real one.** The real ball is a window of its own
 * behind this card and is driven by the voice pipeline: it is about to go
 * SPEAKING or LISTENING the moment anything happens, and an expression the user
 * picked would be overwritten mid-look. This ball belongs to the card, and the
 * card's chips are the only thing that tells it what to wear.
 *
 * It does mean two GL contexts for as long as the card is up. Both are one small
 * view each, and the cost is paid only while this screen is open.
 *
 * Like [WakeEnrollUi] — which it is built on — it stays `FLAG_NOT_FOCUSABLE` and
 * does not touch [OwnCard]: nothing here takes text, so it never needs to be the
 * active window.
 */
class ExpressionsUi(
    private val context: Context,
    private val onDismiss: () -> Unit = {},
) {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null
    private var preview: EmotionBallTextureView? = null
    private var caption: TextView? = null
    private var frosted = false
    private var lctx: Context = context

    /** Which expression chip is lit. Null until the user picks one. */
    private var pickedMood: Mood = Mood.CALM
    private val moodChips = LinkedHashMap<Mood, TextView>()
    private val lookChips = ArrayList<TextView>()
    private var customChip: TextView? = null
    private val avatarChanged: () -> Unit = { if (root != null) refresh() }

    fun show() {
        if (root != null) return
        val p = WindowManager.LayoutParams(
            cardWidth(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        frosted = Glass.frost(context, wm, p)
        val v = build()
        runCatching {
            v.hideFromAccessibility()
            wm.addView(v, p)
            root = v
            CustomAvatar.observe(context, avatarChanged)
            Glass.enter(v)
        }
    }

    fun hide() {
        val v = root ?: return
        root = null
        CustomAvatar.unobserve(avatarChanged)
        preview = null
        Glass.exit(v) {
            runCatching { wm.removeView(v) }
            onDismiss()
        }
    }

    // ---- Build ----

    @SuppressLint("SetTextI18n")
    private fun build(): View {
        lookChips.clear()
        moodChips.clear()
        customChip = null
        lctx = AppLocale.wrap(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(16))
            background = Glass.card(context, dp(28), frosted)
            clipToOutline = true
        }

        card.addView(TextView(context).apply {
            text = lctx.getString(R.string.expr_title)
            textSize = Glass.Type.HEADLINE
            setTextColor(Color.parseColor(Glass.TITLE))
        })

        // The stage. Square and fixed, so the card does not change height when a
        // look with taller horns or a raised hand is chosen.
        val side = dp(STAGE_DP)
        val stage = FrameLayout(context).apply {
            background = Glass.well(context, dp(22))
        }
        val ball = EmotionBallTextureView(context)
        preview = ball
        stage.addView(ball, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        card.addView(
            stage,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, side + dp(8)).apply {
                topMargin = dp(12)
            },
        )

        caption = TextView(context).apply {
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(Glass.SECONDARY))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(6))
        }
        card.addView(caption)

        // ---- Who ----
        card.addView(heading(lctx.getString(R.string.expr_section_look)))
        card.addView(chipRow {
            for (look in BallLooks.ALL) {
                val chip = chip(lctx.getString(look.labelRes)) { pickLook(look.name) }
                lookChips.add(chip)
                addView(chip, chipParams())
            }
            if (CustomAvatar.hasImage(context)) {
                customChip = chip(lctx.getString(R.string.expr_custom_label)) {
                    VoiceConfig.save(context, mapOf("custom_avatar" to "on"))
                    refresh()
                }.also { addView(it, chipParams()) }
            }
        })

        // ---- How it feels ----
        card.addView(heading(lctx.getString(R.string.expr_section_mood)))
        val grid = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        MOODS.forEachIndexed { i, m ->
            if (i % PER_ROW == 0) {
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                wrap.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(6) },
                )
            }
            val chip = chip(lctx.getString(labelOf(m))) { pickMood(m) }
            moodChips[m] = chip
            row!!.addView(
                chip,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(3); marginEnd = dp(3)
                },
            )
        }
        grid.addView(wrap)
        // As tall as the chips need, so all nineteen are on screen and nobody has to
        // scroll to find 睡着 — it was a fixed 190 dp, five rows do not fit in that,
        // and the top row came up half cut off. Capped at a third of the screen so a
        // landscape phone still gets a card it can see the footer of; only there does
        // it scroll.
        wrap.measure(
            View.MeasureSpec.makeMeasureSpec(cardWidth() - dp(44), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val screenH = wm.currentWindowMetrics.bounds.height()
        val gridH = minOf(wrap.measuredHeight, (screenH * 0.34f).toInt())
        card.addView(
            grid,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, gridH),
        )

        // ---- Footer ----
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        footer.addView(button(lctx.getString(R.string.wake_close)) { hide() })
        card.addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        // Start on whichever look the ball is actually wearing, so opening the card
        // shows what the user already has rather than a different character.
        BallLooks.requestByName(VoiceConfig.ballLook(context))
        refresh()
        return card
    }

    // ---- Behaviour ----

    private fun pickLook(name: String) {
        // The same write the swipe makes (FloatingWindowUi.stepBallLook): the look is
        // saved the instant it is chosen, so the corner ball follows and a restart
        // keeps it. Reading the saved name back is what the real ball does on its
        // next frame — nothing else needs telling.
        BallLooks.requestByName(name)
        VoiceConfig.save(context, mapOf("ball" to name))
        refresh()
    }

    private fun pickMood(m: Mood) {
        pickedMood = m
        refresh()
    }

    /** Push the card's choices onto its ball and repaint the chips. */
    private fun refresh() {
        preview?.setMood(pickedMood)
        preview?.setListening(pickedMood == Mood.LISTENING)
        val look = BallLooks.requestedLook
        val custom = VoiceConfig.customAvatarEnabled(context) && CustomAvatar.hasImage(context)
        lookChips.forEachIndexed { i, chip -> light(chip, !custom && BallLooks.ALL[i] === look) }
        customChip?.let { light(it, custom) }
        for ((m, chip) in moodChips) light(chip, m == pickedMood)
        caption?.text = lctx.getString(
            R.string.expr_caption,
            lctx.getString(if (custom) R.string.expr_custom_label else look.labelRes),
            lctx.getString(labelOf(pickedMood)),
        )
    }

    // ---- Widgets ----

    private fun heading(text: String) = TextView(context).apply {
        this.text = text
        textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(Glass.SECONDARY))
        setPadding(dp(2), dp(10), 0, dp(2))
    }

    private fun chipRow(fill: LinearLayout.() -> Unit): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.fill()
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun chipParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { marginEnd = dp(6); topMargin = dp(4) }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = Glass.Type.CAPTION
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(9), dp(12), dp(9))
        isClickable = true
        Glass.pressable(this)
        setOnClickListener { onClick() }
    }

    /** A lit chip is the tinted pane; an unlit one is the plain panel. */
    private fun light(chip: TextView, on: Boolean) {
        chip.background =
            if (on) Glass.tinted(context, dp(18)) else Glass.panel(context, dp(18))
        chip.setTextColor(Color.parseColor(if (on) Glass.ON_TINT else Glass.LABEL))
    }

    private fun button(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = Glass.Type.BODY
        setTextColor(Color.parseColor(Glass.LABEL))
        gravity = Gravity.CENTER
        background = Glass.panel(context, dp(20))
        setPadding(dp(22), dp(11), dp(22), dp(11))
        isClickable = true
        Glass.pressable(this)
        setOnClickListener { onClick() }
    }

    private fun cardWidth(): Int =
        minOf(dp(460), (wm.currentWindowMetrics.bounds.width() * 0.94f).toInt())

    private fun dp(v: Int): Int = Glass.dp(context, v)

    private companion object {
        const val STAGE_DP = 220
        const val PER_ROW = 4

        /**
         * Every expression worth looking at, in the order a person would try them:
         * the everyday ones, then the gestures, then what it does while it works.
         * SPEAKING is here because its mouth is the one thing a still frame cannot
         * show — it moves on its own when picked.
         */
        val MOODS = listOf(
            Mood.CALM, Mood.HAPPY, Mood.CURIOUS, Mood.WINK,
            Mood.WAVE, Mood.CELEBRATE, Mood.PROTECT, Mood.REST,
            Mood.THINKING, Mood.LISTENING, Mood.SPEAKING, Mood.SEARCHING,
            Mood.TENSE, Mood.ANXIOUS, Mood.CONCERNED, Mood.FAILED,
            Mood.SUCCESS, Mood.HOLDING, Mood.SLEEPING,
        )

        fun labelOf(m: Mood): Int = when (m) {
            Mood.CALM -> R.string.expr_calm
            Mood.HAPPY -> R.string.expr_happy
            Mood.CURIOUS -> R.string.expr_curious
            Mood.TENSE -> R.string.expr_tense
            Mood.ANXIOUS -> R.string.expr_anxious
            Mood.CONCERNED -> R.string.expr_concerned
            Mood.WINK -> R.string.expr_wink
            Mood.WAVE -> R.string.expr_wave
            Mood.PROTECT -> R.string.expr_protect
            Mood.CELEBRATE -> R.string.expr_celebrate
            Mood.REST -> R.string.expr_rest
            Mood.LISTENING -> R.string.expr_listening
            Mood.HOLDING -> R.string.expr_holding
            Mood.THINKING -> R.string.expr_thinking
            Mood.SPEAKING -> R.string.expr_speaking
            Mood.SEARCHING -> R.string.expr_searching
            Mood.SUCCESS -> R.string.expr_success
            Mood.FAILED -> R.string.expr_failed
            Mood.SLEEPING -> R.string.expr_sleeping
        }
    }
}
