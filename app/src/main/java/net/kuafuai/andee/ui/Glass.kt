package net.kuafuai.andee.ui
import net.kuafuai.andee.compat.WindowMetricsCompat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
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
    //
    // **Light.** The card is a white surface now, and every name below kept its
    // name through the change so the call sites did not have to move — what moved
    // is the direction each one leans.
    //
    // The old values were all "a white film over a near-black card": `#1FFFFFFF`
    // for a panel, `#2BFFFFFF` for an edge, `#FFFFFF` for a title. Not one of them
    // survives on white — a 6% white film over white *is* white. So each film is
    // now the same alpha in black, each text colour went from near-white to
    // near-black, and the three state colours were darkened until they carry on a
    // white field rather than glowing against a dark one.
    //
    // The exception is the committing button ([tinted]): one saturated fill reads on
    // either ground, and it is the one place colour is allowed to be loud.

    /**
     * The two palettes, behind one set of names.
     *
     * **Day is dark-on-light and night is light-on-dark, so most pairs are opposites**
     * — a film that is black at low alpha in one is white at low alpha in the other,
     * and near-black text becomes near-white. The night values are the ones this app
     * shipped with before the light card, name for name, so night is a look that was
     * already tuned on a tablet rather than a set of colours invented for a switch.
     *
     * **Three things are deliberately not a plain inversion**, because what they mean
     * is a position rather than a colour:
     *  * [well] is *recessed* on both palettes — darker than what it sits on. A
     *    mirrored day value would make every text field glow.
     *  * [thumb] is *raised* on both — lighter than the well it slides in.
     *  * [sheen] flips end for end rather than in place, because its job is a lit
     *    edge and the direction the light comes from does not change with the theme.
     */
    private class Palette(
        val cardFrosted: String,
        val cardSolid: String,
        val hairline: String,
        val panel: String,
        val panelEdge: String,
        val glass: String,
        val glassEdge: String,
        val well: String,
        val wellEdge: String,
        val thumb: String,
        val thumbEdge: String,
        /** Gradient stops for [sheen], in the order the gradient runs: top to bottom. */
        val sheen: IntArray,
        val title: String,
        val label: String,
        val secondary: String,
        val muted: String,
        val photoInk: String,
        val accent: String,
        /** The fill of the one committing surface; see [tinted]. */
        val tint: String,
        val danger: String,
        val ok: String,
        val warn: String,
    )

    private fun stops(vararg hex: String): IntArray =
        IntArray(hex.size) { Color.parseColor(hex[it]) }

    private val DAY = Palette(
        cardFrosted = "#F2FFFFFF",
        cardSolid = "#FFFFFFFF",
        hairline = "#24000000",
        panel = "#0A000000",
        panelEdge = "#14000000",
        glass = "#0F000000",
        glassEdge = "#24000000",
        well = "#0D000000",
        wellEdge = "#1A000000",
        thumb = "#FFFFFFFF",
        thumbEdge = "#2E000000",
        sheen = stops("#1FFFFFFF", "#00FFFFFF", "#00FFFFFF", "#14000000"),
        title = "#0E1116",
        label = "#2B313B",
        secondary = "#5A6270",
        muted = "#7D8592",
        photoInk = "#252A33",
        accent = "#3B62E0",
        tint = "#3B62E0",
        danger = "#DC2626",
        ok = "#16A34A",
        warn = "#D97706",
    )

    private val NIGHT = Palette(
        cardFrosted = "#99000000",
        cardSolid = "#F2060608",
        hairline = "#2BFFFFFF",
        panel = "#0FFFFFFF",
        panelEdge = "#17FFFFFF",
        glass = "#1FFFFFFF",
        glassEdge = "#38FFFFFF",
        well = "#59000000",
        wellEdge = "#1AFFFFFF",
        thumb = "#2EFFFFFF",
        thumbEdge = "#40FFFFFF",
        sheen = stops("#1FFFFFFF", "#00FFFFFF"),
        title = "#FFFFFF",
        label = "#DCE1EA",
        secondary = "#8F97A6",
        muted = "#5F6775",
        photoInk = "#98A0AE",
        accent = "#7FA5FF",
        tint = "#4C6FFF",
        danger = "#FF6B6B",
        ok = "#4ADE80",
        warn = "#FBBF24",
    )

    /**
     * The palette in force, resolved on every access rather than cached — the theme
     * can change between two builds of what looks like the same view, which is the
     * entire reason [Theme.onChanged] exists.
     */
    private val active: Palette get() = if (Theme.night) NIGHT else DAY

    // ---- The names, as getters rather than constants ----
    //
    // Every call site still reads `Glass.TITLE`. What changed is *when* it is read:
    // a `const val` is inlined into its readers at compile time and therefore cannot
    // vary at runtime at all, which is the whole reason a theme switch could not be
    // added as a plain setting. Turning them into getters is what makes the switch
    // possible without touching forty call sites; the price is that the compiler no
    // longer folds them, which for a handful of `Color.parseColor` calls per card
    // build is nothing.
    //
    // ⚠️ The consequence to remember: **any `Glass.*` read is a read of whatever the
    // theme was when it ran**, so a view built before a change keeps the old colours
    // until it is rebuilt. Nothing may assume otherwise — every surface subscribes.

    /**
     * The card's fill when [frost] got its blur.
     *
     * Day is 95% rather than opaque, and the 5% is doing specific work: the card ends
     * up *lighter* than the page it sits on ([Backdrop]'s wash averages ~#F9FAFD), so
     * the pane reads as a raised surface and the wash underneath gives it something to
     * sit on. At 100% white the card and the page would be the same white and the card
     * would be a rectangle drawn in nothing. Night is the 60% black it always was,
     * over a photo.
     */
    val CARD_FROSTED: String get() = active.cardFrosted

    /** …and when it did not. Opaque, because there is nothing to show through it. */
    val CARD_SOLID: String get() = active.cardSolid

    /**
     * The card's edge. In day this is *the separation*, and the one piece of the iOS
     * material recipe that survives a white surface — see [sheen]. In night it is the
     * bright hairline the dark card was built around, which is the same job from the
     * other side.
     */
    val HAIRLINE: String get() = active.hairline

    /** A grouped surface sitting on the card. */
    val PANEL: String get() = active.panel
    val PANEL_EDGE: String get() = active.panelEdge

    /**
     * A card sitting on the blurred backdrop instead of on [PANEL].
     *
     * [PANEL] cannot do this job: it is the right film for a row group *inside* a
     * card and too weak over the fullscreen backdrop, because those two surfaces have
     * nothing in common — one is a card, the other is whatever the backdrop is
     * painted with. This pair is the glassmorphism recipe proper, and the numbers are
     * the balance that makes it read as a pane rather than as a hole: enough fill for
     * [LABEL] and [SECONDARY] to sit on, enough edge and sheen to give the rectangle
     * a lit top. See [glass].
     *
     * **Currently no caller.** It was written for the self-check page, which was a
     * fullscreen surface on [net.kuafuai.andee.ui.Backdrop] with these rows on it for
     * one day; that page is now a `Glass.card` opened from `✓` (see
     * [net.kuafuai.andee.ui.SelfCheckUi]) and its rows are [PANEL], because a
     * surface's fill is decided by what is directly underneath it and nothing else.
     * The distinction is kept, rather than the two constants deleted, because the
     * *fact* behind it has not gone anywhere: [Backdrop] is still what
     * [net.kuafuai.andee.ui.TextInputActivity] and the launcher self-check host paint
     * themselves with, and anything put on top of it needs this fill and not [PANEL].
     */
    val GLASS: String get() = active.glass
    val GLASS_EDGE: String get() = active.glassEdge

    /**
     * Recessed: text fields and the track a segmented picker slides in.
     *
     * **Darker than what it sits on in both palettes** — 5% black on a white card, 35%
     * black on a dark one. Mirroring the day value instead would put a 35% *white*
     * film here and make every text field glow.
     */
    val WELL: String get() = active.well
    val WELL_EDGE: String get() = active.wellEdge

    /**
     * The pane that slides between segments: raised rather than recessed, so it is
     * *lighter* than the well it slides in on both palettes — pure white over day's
     * 5% black, 18% white over night's 35% black.
     */
    val THUMB: String get() = active.thumb
    val THUMB_EDGE: String get() = active.thumbEdge

    // The ramp. Day runs dark→light in this order, night runs light→dark. The two
    // light ends of day sit closer to the middle than an exact mirror of the night
    // values would put them: mirroring #5F6775 for contrast on white gives roughly
    // #98A0AD, a 2.2:1 grey — legible as a whisper of text and *not* legible as the
    // row of bar icons, which is what actually reads [MUTED] (`FloatingWindowUi.
    // barButton`). Light-on-dark forgives a low ratio; dark-on-light does not.
    val TITLE: String get() = active.title
    val LABEL: String get() = active.label
    val SECONDARY: String get() = active.secondary
    val MUTED: String get() = active.muted

    /**
     * Ink for things that sit **on the backdrop photograph, or on a well over it** rather
     * than on a card: the gesture tips along the bottom of the full card, and the top
     * bar's five icons.
     *
     * **The value is bracketed by two measured failures, and both are worth knowing
     * before moving it.** [MUTED] is what these two had, and it is chosen against a
     * card — white in day, near-black at night — so on a photograph it has no ground to
     * stand on: measured **1.4:1**, reported from the tablet as 看不清. Moving to the
     * other end of the ramp fixed the legibility and overshot it: near-black on the light
     * page's mid-blue sky measured **6:1** and read as a lump, reported as 太突兀.
     *
     * So this is the middle of the ramp — about **3:1** against either ground, which is
     * the threshold for *graphics* rather than for body text, and these callers are large
     * solid glyphs and one small caption rather than prose. Both ends of the ramp fail on
     * an image, at opposite extremes, and there is no setting that is both quiet and
     * legible on a background whose brightness is somebody else's photograph.
     *
     * The rule to carry: **a colour chosen against a known ground has no guarantee once
     * the ground is an image.** Anything else landing directly on the photograph takes
     * this token and not one of the card's.
     */
    val PHOTO_INK: String get() = active.photoInk

    /** Section headings and anything tappable that is only text. */
    val ACCENT: String get() = active.accent

    /**
     * The label on the button that commits — on [tinted], and nowhere else.
     *
     * **Not [TITLE]**, which is what those call sites used to name, and the reason is
     * measurable rather than a matter of taste: [TITLE] is near-black in day and white
     * at night, because its job is to sit on a card. On a saturated fill that only
     * works at night — a near-black label on the day tint measures about 4:1, under
     * the line for body text, while white clears 4.5:1 on either tint.
     *
     * Both fills were picked deep enough to carry white, which is what lets one
     * constant be right for both themes.
     */
    const val ON_TINT = "#FFFFFF"

    val DANGER: String get() = active.danger

    /** Done, granted, reachable — the self-check's green and nothing else's. */
    val OK: String get() = active.ok

    /** Works, but degrades silently. */
    val WARN: String get() = active.warn

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
     *
     * **Two columns of six.** The larger column is the tablet's, and it was
     * drawn on a 711dp-wide screen where a card is 500dp with air around it. A
     * phone is 392dp wide and the same card fills it edge to edge — so the same
     * 26sp title that reads as a heading on the tablet reads as a shout on the
     * phone. **Nothing about the phone's type is actually bigger; what changed is
     * what holds it.** Padding, corner radii and line spacing here are all in dp
     * and do not follow a smaller screen down, so a glyph that keeps its size
     * against a box that lost a third of its width looks wrong, and reads as
     * clumsy rather than as large. Hence a second column, about three quarters
     * of the first, chosen once by [phone].
     *
     * **The tablet column is the original design and stays untouched.** A phone
     * value is derived from it, never the other way round — if a rung has to
     * move, move both columns.
     *
     * Careful with the arithmetic: these are sp, so a device whose *system* font
     * scale is not 1.0 multiplies them again at draw time, and only the type
     * moves — the dp spacers around it do not. The phone column is set low
     * enough that it still lands under the tablet's on a phone whose owner has
     * turned system text up; see the note on [phone].
     */
    object Type {

        /**
         * Above this many dp on the device's *short* side, a screen is a tablet.
         *
         * 600 is Android's own line — the `sw600dp` resource qualifier — and
         * there is no reason to invent a second one. Neither device is anywhere
         * near it: the tablet is 711dp, the phone 392dp.
         */
        private const val TABLET_MIN_WIDTH_DP = 600f

        /**
         * Which column this device gets, decided once.
         *
         * **Why `Resources.getSystem()` and not a Context.** These are read at
         * roughly a hundred construction sites, most of them several frames away
         * from anything holding a Context — the same predicament [Theme] lays out
         * for its palette, and answered the same way. It helps that the answer is
         * a property of the *device*: how wide the screen physically is, not which
         * card happens to be drawing.
         *
         * **Why `displayMetrics` arithmetic and not `configuration.smallestScreenWidthDp`.**
         * The Configuration reached this way is not guaranteed to have been filled
         * in — a zero there would silently read as "not a tablet" and leave the phone
         * unchanged — while `displayMetrics` always carries the display's real pixels
         * and density. The smaller of the two sides, so rotation and fold state cannot
         * flip the answer.
         *
         * **This deliberately does not read the system font scale.** A phone with
         * text turned up to 1.15 still renders these at 1.15×, and the phone column
         * is set with that in mind: it lands a little under the tablet's own sizes,
         * not far under. Turning the system scale back down therefore makes the
         * phone's type *smaller* than it looks here — which is the honest behaviour
         * (the user asked for smaller text) rather than a bug to chase.
         */
        private val phone: Boolean by lazy {
            val dm = Resources.getSystem().displayMetrics
            minOf(dm.widthPixels, dm.heightPixels) / dm.density < TABLET_MIN_WIDTH_DP
        }

        /** A card's own title. */
        val DISPLAY: Float get() = if (phone) 20f else 26f

        /** The title of a card stacked on another card, or of a step. */
        val HEADLINE: Float get() = if (phone) 15.5f else 20f

        /** A dialog's title, a row that is the point of its panel. */
        val TITLE: Float get() = if (phone) 13.5f else 17f

        /** What the user reads: rows, fields, buttons, answers. */
        val BODY: Float get() = if (phone) 12f else 15f

        /** What explains the body: subtitles, notes, section heads, hints. */
        val CAPTION: Float get() = if (phone) 10.5f else 13f

        /**
         * Timestamps, badges, the pinned gesture tips.
         *
         * The one rung the phone column barely touches, and on purpose: this is
         * already the floor of what can be read, and a floor that moves with the
         * screen is not a floor. It stays at the tablet's value on both.
         */
        val MICRO: Float get() = if (phone) 9.5f else 11f
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
    fun usableSize(context: Context): Pair<Int, Int> = WindowMetricsCompat.usableSize(
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    )

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

    /**
     * The one loud surface: the button that commits.
     *
     * **Flat, and the theme's own blue.** It used to be a fixed blue-to-violet
     * gradient, and both halves of that turned out to be wrong. A gradient inside a
     * pill makes the button carry its own light source — the look of a landing page's
     * call to action — and it was the only colour in the palette that did not answer
     * to [Theme], so on a light card it sat there like a sticker off another design.
     *
     * What makes this the loudest thing on a card is that it is the only saturated
     * fill on it. That survives going flat; the gradient was never what did the work.
     *
     * The two values sit one step deeper than each theme's [ACCENT], because the label
     * on this surface is white in both themes ([ON_TINT]) and white is the thing a
     * fill has to be chosen against. Violet went with the gradient — the interface has
     * exactly one hue and it is blue.
     */
    @Suppress("UNUSED_PARAMETER") // takes a Context like its siblings, so callers read alike
    fun tinted(context: Context, radius: Int): Drawable = sheened(
        base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(active.tint))
            cornerRadius = radius.toFloat()
        },
        radius = radius,
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
     * The shading that makes a flat rectangle read as a solid: `TOP_BOTTOM`, from
     * [Palette.sheen].
     *
     * **The one gradient whose direction belongs to the theme rather than to its
     * colour.** Light comes from above on both, so the *top* is the lit end on both —
     * but "lit" means opposite things: on the dark card it is a white film fading
     * downward to nothing, and on the white card it is a bright top that is mostly
     * invisible, followed by a shadow along the bottom. Inverting the day stops in
     * place instead of reversing them would shade the top of every surface and leave
     * its bottom lit, which is the one arrangement that reads as wrong at a glance
     * without being nameable.
     *
     * Day's empty middle is deliberate. A gradient tinted end to end turns the card
     * into a sheet of grey with a light top, which is what a light card must not be.
     */
    private fun sheen(radius: Int): Drawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        active.sheen,
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
        // Hide for real and let a blank frame commit before the window goes, so no card frame is left behind.
        val blankThenDone = Runnable {
            v.visibility = View.INVISIBLE
            ui.postDelayed(once, BLANK_FRAME_MS)
        }
        v.animate()
            .alpha(0f).scaleX(0.96f).scaleY(0.96f).translationY(dp(v.context, 8).toFloat())
            .setDuration(170)
            .setInterpolator(EASE_IN)
            .withEndAction(blankThenDone)
            .start()
        ui.postDelayed(once, 400)
    }

    private const val BLANK_FRAME_MS = 34L

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
