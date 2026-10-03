package net.kuafuai.andee.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/**
 * The look of every card this app puts on screen, in one place.
 *
 * Four overlays share it — [SettingsUi], [VaultUi], [WakeEnrollUi],
 * [SelfCheckUi] — and they share it because they are reachable from one
 * another: the settings card opens the vault on top of itself, and the
 * self-check card opens the settings card on top of *itself*. Two different
 * dark blues stacked like that read as a bug before they read as a style.
 *
 * **The base colour is pure black and everything above it is white at a low
 * alpha.** That is not only taste. These are overlays: what is behind them is
 * some other app, and it can be a white document or a night-mode terminal. A
 * palette of *opaque* greys has to pick one of those to look right against. A
 * stack of translucent whites over black does not — it darkens whatever is
 * behind it and then lays the same relative steps on top, so the hierarchy
 * (card → panel → recessed well → thumb) survives both.
 *
 * ### The blur is real, and it is a different mechanism from the one that isn't
 *
 * `CLAUDE.md` records that frosted glass is unavailable for the pills inside
 * `FloatingWindowUi`, and that is still true: `RenderEffect` blurs a View's own
 * content, and `Window.setBackgroundBlurRadius` cannot be scoped to one floating
 * child of a window.
 *
 * These cards are not children. **Each one is its own window**, so the
 * window-level blur applies to exactly the right rectangle: [frost] sets
 * `FLAG_BLUR_BEHIND` + `blurBehindRadius`, and what is behind the card comes
 * through it defocused.
 *
 * Only that one. The *other* half of Android's blur API — `backgroundBlurRadius`,
 * the one clipped to the window's own rounded bounds — is a `@hide` field whose
 * public door is `Window.setBackgroundBlurRadius`, and we have no `Window`:
 * these are bare Views handed to `WindowManager.addView`. So the blur is not
 * clipped to the card; **the whole screen behind it softens**, which for a
 * centred modal is the effect you wanted anyway.
 *
 * It is API 31+ **and optional at runtime**: `isCrossWindowBlurEnabled` is false
 * on battery saver, on devices that cannot afford it, and whenever the developer
 * option is off. So [frost] returns whether it got the blur and the caller picks
 * its fill accordingly — [CARD_FROSTED] when the compositor is doing the work,
 * [CARD_SOLID] when nobody is and a 60%-black card would just be a smeary window
 * onto the app below.
 *
 * ### Translucency is also the safer direction for touch
 *
 * Every overlay this app owns counts toward Android's 0.80 untrusted-touch
 * ceiling, and going over it makes the platform silently discard the brain's
 * injected gestures. The old cards were fully opaque. These are not, so this
 * change can only move that number down.
 *
 * ### Motion
 *
 * Nothing here animates for decoration. Each one answers a question the user
 * would otherwise have to ask: [enter]/[exit] say which card appeared and where
 * it went, the sliding segment thumb says *the same control* changed value
 * rather than a new one appearing, and [expand]/[collapse] say the rows that
 * just vanished were folded away, not deleted. Durations are short enough
 * (≤300 ms) that a user who is not looking for them will not notice, which is
 * the point — a settings card that makes you wait for it is worse than one that
 * snaps.
 */
object Glass {

    // ---- Palette ----

    /** The card's fill when [frost] got its blur: dark enough to read against. */
    const val CARD_FROSTED = "#99000000"

    /** …and when it did not. Nearly opaque, because there is nothing to show. */
    const val CARD_SOLID = "#F2060608"

    /** The card's edge. The one place a bright hairline belongs. */
    const val HAIRLINE = "#2BFFFFFF"

    /** A grouped surface sitting on the card. */
    const val PANEL = "#0FFFFFFF"
    const val PANEL_EDGE = "#17FFFFFF"

    /**
     * A card sitting on the blurred backdrop instead of on [PANEL].
     *
     * [PANEL] cannot do this job: 6% white is the right film for a row group
     * *inside* a 60%-black card and is invisible over the fullscreen backdrop,
     * because those two surfaces have nothing in common — one is nearly opaque
     * black, the other is a blurred photo at ~13% mean luma. This pair is the
     * glassmorphism recipe proper, and the numbers are the balance that makes
     * it read as a pane rather than as a hole: enough fill for [LABEL] and
     * [SECONDARY] to sit on, enough edge and sheen to give the rectangle a lit
     * top. See [glass].
     *
     * **Currently no caller.** It was written for the self-check page, which was
     * a fullscreen surface on [net.kuafuai.andee.ui.Backdrop] with these rows on
     * it for one day; that page is now a `Glass.card` opened from `✓` (see
     * [net.kuafuai.andee.ui.SelfCheckUi]) and its rows are [PANEL], because a
     * surface's fill is decided by what is directly underneath it and nothing
     * else — 12% white on a 60%-black card is a grey box. The distinction is
     * kept, rather than the two constants deleted, because the *fact* behind it
     * has not gone anywhere: [Backdrop] is still what
     * [net.kuafuai.andee.ui.TextInputActivity] and the launcher self-check host
     * paint themselves with, and anything put on top of it needs this fill and
     * not [PANEL].
     */
    const val GLASS = "#1FFFFFFF"
    const val GLASS_EDGE = "#38FFFFFF"

    /** Recessed: text fields and the track a segmented picker slides in. */
    const val WELL = "#59000000"
    const val WELL_EDGE = "#1AFFFFFF"

    /** The pane that slides between segments. Lighter than what it sits on. */
    const val THUMB = "#2EFFFFFF"
    const val THUMB_EDGE = "#40FFFFFF"

    /** The highlight that makes a flat rectangle read as a pane of something. */
    private const val SHEEN_TOP = "#1FFFFFFF"
    private const val SHEEN_BOTTOM = "#00FFFFFF"

    const val TITLE = "#FFFFFF"
    const val LABEL = "#DCE1EA"
    const val SECONDARY = "#8F97A6"
    const val MUTED = "#5F6775"

    /** Section headings and anything tappable that is only text. */
    const val ACCENT = "#7FA5FF"

    /** The primary button is a gradient, which is the one place colour is loud. */
    const val TINT_START = "#4C6FFF"
    const val TINT_END = "#8250FF"

    const val DANGER = "#FF6B6B"

    /** Done, granted, reachable — the self-check's green and nothing else's. */
    const val OK = "#4ADE80"

    /** Works, but degrades silently. */
    const val WARN = "#FBBF24"

    // ---- Type ----

    /**
     * The only text sizes a card may use, in sp.
     *
     * The cards grew sixteen different sizes between them — 9.5, 10, 11, 11.5,
     * 12, 12.5, 13, 14, 14.5, 15, 16, 17, 19, 20, 26 — each a reasonable local
     * choice and together the reason the surfaces read as assembled rather
     * than designed: a 14 beside a 14.5 is not a hierarchy, it is a rounding
     * error the eye notices without being able to name. Six steps, each far
     * enough from its neighbours to mean something. A new size is a new rung
     * here, not a literal at the call site.
     */
    object Type {
        /** A card's own title. */
        const val DISPLAY = 26f

        /** The title of a card stacked on another card, or of a step. */
        const val HEADLINE = 20f

        /** A dialog's title, a row that is the point of its panel. */
        const val TITLE = 17f

        /** What the user reads: rows, fields, buttons, answers. */
        const val BODY = 15f

        /** What explains the body: subtitles, notes, section heads, hints. */
        const val CAPTION = 13f

        /** Timestamps, badges, the pinned gesture tips. */
        const val MICRO = 11f
    }

    // ---- Motion ----

    /** Fast out of the gate, long tail. The house easing. */
    val EASE: PathInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** For things leaving, which should not linger. */
    val EASE_IN: PathInterpolator = PathInterpolator(0.4f, 0f, 1f, 1f)

    private val ARGB = ArgbEvaluator()

    /**
     * Not the view's own handler: [exit]'s completion has to run even when the
     * animation never finishes because the window went away underneath it.
     */
    private val ui = Handler(Looper.getMainLooper())

    // ---- Window ----

    /**
     * Width and height a centred card can actually occupy: the display minus
     * the status bar, navigation bar and cutout.
     *
     * Not `currentWindowMetrics.bounds` alone. An overlay without
     * `FLAG_LAYOUT_IN_SCREEN` is laid out between the bars, so a card sized
     * as "display minus a gutter" spends its gutter on the bars — invisible on
     * a landscape tablet, flush against both bars on a portrait phone.
     */
    fun usableSize(context: Context): Pair<Int, Int> {
        val m = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics
        val i = m.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        return (m.bounds.width() - i.left - i.right) to (m.bounds.height() - i.top - i.bottom)
    }

    /**
     * Ask the compositor to blur behind this window. Returns whether it agreed.
     *
     * Call before `addView`; on a window that never changes its mind the flag
     * belongs on the params it is created with. The four-argument overload is
     * for the one window that does.
     *
     * See the class KDoc for why the answer can be no, and for why this blurs
     * the whole screen rather than only the card.
     */
    fun frost(context: Context, wm: WindowManager, p: WindowManager.LayoutParams): Boolean =
        frost(context, wm, p, want = true)

    /**
     * The same, for a window that stops wanting the blur and later wants it
     * back — [FloatingWindowUi], which is one window in two shapes: a
     * fullscreen card that should frost, and a corner ball that must not
     * (a blurred 168 dp square hanging around the ball is just a smudge on
     * the user's screen).
     *
     * Unlike the three cards this one is applied with `updateViewLayout` after
     * the fact, which does take. The caller pushes the params; this only edits
     * them, and returns what the fill should now assume.
     */
    fun frost(
        context: Context,
        wm: WindowManager,
        p: WindowManager.LayoutParams,
        want: Boolean,
    ): Boolean {
        if (!want ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            !wm.isCrossWindowBlurEnabled
        ) {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
            return false
        }
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        // Heavier than it would need to be if it were clipped to the card: this
        // one blur has to both frost the pane and push the app underneath back.
        p.blurBehindRadius = dp(context, 48)
        return true
    }

    // ---- Surfaces ----

    /**
     * The card itself: a translucent fill, a sheen down the top third, a
     * hairline edge.
     *
     * @param frosted what [frost] returned — it decides the fill, not the shape.
     */
    fun card(context: Context, radius: Int, frosted: Boolean): Drawable = sheened(
        base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(if (frosted) CARD_FROSTED else CARD_SOLID))
            cornerRadius = radius.toFloat()
            setStroke(dp(context, 1), Color.parseColor(HAIRLINE))
        },
        radius = radius,
    )

    /** A group of rows, raised off the card. */
    fun panel(context: Context, radius: Int): Drawable = sheened(
        base = fill(context, PANEL, PANEL_EDGE, radius),
        radius = radius,
    )

    /**
     * A pane on the fullscreen backdrop — the glass in glassmorphism.
     *
     * Same shape as [panel] and a different surface: see [GLASS] for why the
     * two fills cannot be swapped. The sheen is what does most of the work
     * here, because the fill is only 12% white and the backdrop behind it is
     * mostly dark — it is the top-lit edge that makes the rectangle look like
     * something sitting *on* the image rather than a window cut into it.
     *
     * **No caller at the moment; see [GLASS] for why it stayed anyway.** Reach
     * for it whenever the parent is [net.kuafuai.andee.ui.Backdrop] rather than a
     * card, and for a whole fullscreen surface (a page's own background) use
     * `Backdrop` plus a `CENTER_CROP` ImageView instead — this is for the cards
     * that go *on* that.
     */
    fun glass(context: Context, radius: Int): Drawable = sheened(
        base = fill(context, GLASS, GLASS_EDGE, radius),
        radius = radius,
    )

    /** A field or a picker track: pressed *into* the card rather than onto it. */
    fun well(context: Context, radius: Int): Drawable = fill(context, WELL, WELL_EDGE, radius)

    /** The segmented picker's sliding pane. */
    fun thumb(context: Context, radius: Int): Drawable = sheened(
        base = fill(context, THUMB, THUMB_EDGE, radius),
        radius = radius,
    )

    /** The one loud surface: the button that commits. */
    @Suppress("UNUSED_PARAMETER") // takes a Context like its siblings, so callers read alike
    fun tinted(context: Context, radius: Int): Drawable = LayerDrawable(
        arrayOf(
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor(TINT_START), Color.parseColor(TINT_END)),
            ).apply { cornerRadius = radius.toFloat() },
            sheen(radius),
        ),
    )

    private fun fill(context: Context, color: String, edge: String, radius: Int) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(color))
            cornerRadius = radius.toFloat()
            setStroke(dp(context, 1), Color.parseColor(edge))
        }

    private fun sheened(base: Drawable, radius: Int): Drawable =
        LayerDrawable(arrayOf(base, sheen(radius)))

    /**
     * A white gradient fading out downward. It is what separates "a rectangle
     * filled with 6% white" from something that looks lit from above.
     */
    private fun sheen(radius: Int): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor(SHEEN_TOP), Color.parseColor(SHEEN_BOTTOM)),
    ).apply { cornerRadius = radius.toFloat() }

    // ---- Card motion ----

    /** Rise into place. Slight overshoot, because a card that just appears is flat. */
    fun enter(v: View) {
        v.alpha = 0f
        v.scaleX = 0.94f
        v.scaleY = 0.94f
        v.translationY = dp(v.context, 14).toFloat()
        v.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(320)
            .setInterpolator(OvershootInterpolator(0.9f))
            .start()
    }

    /**
     * Sink away, then run [done] — which is where the caller removes the window.
     *
     * [done] runs exactly once and runs even if the animation is cancelled
     * (a detached view never delivers its end action), because what hangs off it
     * is `removeView` plus the [OwnCard] bookkeeping that tells the brain
     * whether one of our own cards is covering the screen. Leaking that count
     * would have the device permanently claim a card is open.
     */
    fun exit(v: View, done: () -> Unit) {
        val once = object : Runnable {
            private var ran = false
            override fun run() {
                if (ran) return
                ran = true
                done()
            }
        }
        v.animate()
            .alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(dp(v.context, 8).toFloat())
            .setDuration(170)
            .setInterpolator(EASE_IN)
            .withEndAction(once)
            .start()
        ui.postDelayed(once, 400)
    }

    /** Cross-fade one root view for another — see `SettingsUi.rebuild`. */
    fun fadeOut(v: View, then: () -> Unit) {
        v.animate().alpha(0f).scaleX(0.985f).scaleY(0.985f)
            .setDuration(110).setInterpolator(EASE_IN)
            .withEndAction(then).start()
        ui.postDelayed(then, 300)
    }

    fun fadeIn(v: View) {
        v.alpha = 0f
        v.scaleX = 0.985f
        v.scaleY = 0.985f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(200).setInterpolator(EASE).start()
    }

    // ---- Row motion ----

    /**
     * Unfold a hidden block to its natural height.
     *
     * Measured against the parent's usable width rather than animated to
     * `WRAP_CONTENT` (which no animator can interpolate to), and the height is
     * handed back to `WRAP_CONTENT` at the end so later content changes still
     * size it. If the parent has not been laid out yet there is nothing to
     * measure against, and the block simply appears — that is the first build,
     * where there was no transition to show anyway.
     */
    fun expand(v: View) {
        if (v.visibility == View.VISIBLE) return
        val parent = v.parent as? View
        val avail = (parent?.width ?: 0) - (parent?.paddingLeft ?: 0) - (parent?.paddingRight ?: 0)
        if (avail <= 0) {
            v.visibility = View.VISIBLE
            v.alpha = 1f
            return
        }
        v.measure(
            View.MeasureSpec.makeMeasureSpec(avail, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val target = v.measuredHeight
        val lp = v.layoutParams
        v.visibility = View.VISIBLE
        v.alpha = 0f
        ValueAnimator.ofInt(0, target).apply {
            duration = 240
            interpolator = EASE
            addUpdateListener {
                lp.height = it.animatedValue as Int
                v.layoutParams = lp
                v.alpha = it.animatedFraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    v.layoutParams = lp
                    v.alpha = 1f
                }
            })
            start()
        }
    }

    /** The mirror of [expand]. Ends `GONE`, so it stops costing layout. */
    fun collapse(v: View) {
        if (v.visibility != View.VISIBLE) return
        val from = v.height
        val lp = v.layoutParams
        if (from <= 0) {
            v.visibility = View.GONE
            return
        }
        ValueAnimator.ofInt(from, 0).apply {
            duration = 200
            interpolator = EASE
            addUpdateListener {
                lp.height = it.animatedValue as Int
                v.layoutParams = lp
                v.alpha = 1f - it.animatedFraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    v.visibility = View.GONE
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    v.layoutParams = lp
                    v.alpha = 1f
                }
            })
            start()
        }
    }

    fun setVisible(v: View, visible: Boolean, animate: Boolean) {
        if (!animate) {
            v.visibility = if (visible) View.VISIBLE else View.GONE
            v.alpha = 1f
            return
        }
        if (visible) expand(v) else collapse(v)
    }

    // ---- Touch ----

    /**
     * Shrink under the finger and spring back.
     *
     * Returns false from the touch listener on purpose: the View's own
     * `onTouchEvent` still runs, so this is purely additive and cannot swallow
     * a click. Lint's accessibility warning is suppressed because every window
     * this is used in calls `hideFromAccessibility` — these views are invisible
     * to screen readers by design, ours included.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun pressable(v: View) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.8f)
                        .setDuration(90).setInterpolator(EASE).start()

                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                -> {
                    // On UP only: a scroll that began on a button arrives as
                    // CANCEL, and ticking for it would buzz through every list.
                    if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                        .setDuration(200).setInterpolator(OvershootInterpolator(2f)).start()
                }
            }
            false
        }
    }

    // ---- Helpers ----

    /** One shared evaluator: segment repainting runs this on every frame. */
    fun lerpColor(from: Int, to: Int, f: Float): Int = ARGB.evaluate(f, from, to) as Int

    fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
