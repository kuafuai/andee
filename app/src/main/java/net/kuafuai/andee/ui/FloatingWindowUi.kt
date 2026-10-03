package net.kuafuai.andee.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import net.kuafuai.andee.R
import net.kuafuai.andee.audio.Earcon
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.ui.ball.Action
import net.kuafuai.andee.ui.ball.BallLooks
import net.kuafuai.andee.ui.ball.CLING_HIDDEN
import net.kuafuai.andee.ui.ball.EmotionBallTextureView
import net.kuafuai.andee.ui.ball.Mood
import net.kuafuai.andee.ui.ball.SPHERE_RADIUS_FRACTION
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The floating window IS the ball. Fullscreen by default.
 *
 * Layout (FrameLayout, two stacked areas plus a floating control pill):
 *   ┌──── ball area (top 40%) ─────────────────┐
 *   │                          ◈ ✓ ⚙ ✕  │  ← 产物 / self-check / gear / close
 *   │                                          │
 *   │           [round ball]                   │  ← tap = talk
 *   │                                          │  ← double-tap = type
 *   │                                          │  ← triple-tap = stop
 *   │                                          │  ← swipe = switch character
 *   │                                          │  ← long-press = unfold
 *   │                                          │  ← drag = move (compact only)
 *   ├──── scrollback (the rest) ───────────────┤
 *   │   你    · 15:31  帮我看看今天的天气        │
 *   │   助手  · 15:34  今天多云…                │
 *   │   ▸ live row: what is happening now      │
 *   ├──────────────────────────────────────────┤
 *   │  点小球说话 · 双击打字 · 三连击停止 · 滑动切换性格 │  ← pinned tips
 *   └──────────────────────────────────────────┘
 *
 * One control, three taps, and that is the whole tap vocabulary the user has
 * to learn — the ball is the only thing on screen in both shapes, so all
 * three gestures work folded and unfolded alike. See
 * [BallHostFrame.registerTap]. The swipe is the one gesture that is
 * full-card-only, which is why the tips line spells it out.
 *
 * Everything the assistant hears, says, shows or fails at ends up in that one
 * list, in order. There is no separate subtitle band: [setSubtitle] writes the
 * list's live last row instead, so the user watches one surface rather than
 * two, and a sentence cannot appear on both at once (it used to).
 *
 * Touch handling:
 *   - The control pill's buttons are child Views, consume their own taps
 *   - Ball area (top): circular hit test → tap/drag/long-press
 *   - Scrollback (bottom): its own touches, so it can scroll
 *   - Anywhere outside the circle in the ball area: passes through
 *
 * Tap opens the mic and nothing else — deliberately not "tap, then unfold to
 * talk": the corner ball is there so you can ask about what you are looking
 * at, and covering it again to do so defeats it. Recognition state shows on
 * the ball itself. Long-press is how the user gets the full card back, which
 * is also why ⤡ isn't a toggle.
 *
 * The card is black glass, not paint: [Glass] gives it a translucent fill over
 * a real blur of whatever is behind ([applyWindow] asks for it, and only while
 * the card is unfolded and visible). What it does *not* become is see-through
 * in any useful sense — 60% black over a defocused app is still a wall — so
 * the overlay still hides the app underneath from the *camera* as well as the
 * eye: see [setVisibleForCapture] for why every screenshot has to blank the
 * window first, and why that blank drops the blur too.
 *
 * Two geometries exist. Fullscreen: the assistant card. Compact: a bare ball
 * in the bottom-right corner, which is what the card becomes — and stays —
 * whenever the assistant is driving the device underneath (every `screen.*`
 * command folds the card before it runs), plus whenever the user asks with ⤡.
 * Both routes reach it through [setCompact]; the way back is a long-press on
 * the ball ([Listeners.onLongPress]) with exactly one caller that does not
 * wait for one: the service unfolds once at birth, so a device that just
 * booted shows the card rather than a corner ball. See
 * `ScreenBodyService.onServiceConnected`.
 */
class FloatingWindowUi(
    private val context: Context,
    private val listeners: Listeners,
) {

    enum class State { IDLE, RECORDING, THINKING, SPEAKING, ERROR }

    /**
     * What is happening right now, as the scrollback's live last row — a partial
     * transcript, the tool being run, the state a command left behind.
     * Everything here is superseded by a real entry or cleared; nothing settles
     * into the log through this path.
     *
     * Deliberately *not* the sentence being spoken: that one is already a row in
     * the log by the time it is read out, and echoing it here put the same words
     * on screen twice.
     */
    enum class SubtitleKind { PARTIAL, FINAL }

    interface Listeners {
        fun onTalkClick()
        fun onSettingsClick()

        /**
         * ✓ — the self-check card.
         *
         * Next to ⚙ because they are the same kind of thing to the user: two
         * buttons that open our own surfaces rather than acting on the screen,
         * so they open the same *shape* of surface. It used to open a fullscreen
         * page — see [net.kuafuai.andee.ui.SelfCheckUi] for why that made two
         * adjacent buttons behave like two different products, and for what it
         * costs to have it be a card instead.
         *
         * Handed out rather than opened here for the same reason
         * [onSettingsClick] is: this class draws the card, the service owns what
         * is behind it and what it is allowed to open.
         */
        fun onSelfCheckClick()
        fun onLongPress()

        /** The 产物 key — the pages the assistant has made. See [ArtifactsUi]. */
        fun onArtifactsClick()

        /**
         * `■` is gone from the bar; stop is still here.
         *
         * The button was the third of four glyphs crammed into one pill, and it
         * was the only one of them that duplicated a gesture the ball already
         * had: triple-tap fires the same [onStopClick], and has since the taps
         * were reduced to one vocabulary. The tips line at the bottom of the
         * card has always said so — "三连击停止" — which is what makes deleting
         * the button a simplification rather than a removal: the user loses a
         * control they had two of and keeps the one that works folded as well.
         */
        fun onStopClick()

        /** ⤡ in the subtitle band — the user asking for the screen back. */
        fun onMinimizeClick()

        /**
         * Two taps on the ball — the user wants to type instead of talk.
         *
         * On the ball rather than as a key in the top bar, because the ball is
         * the one control that exists in **both** shapes. The bar is `GONE`
         * while folded, so a bar key could only ever be a way in from the state
         * the user is already looking at the card in; the ball is there either
         * way, and the three taps now read as one set: ask, type, stop.
         *
         * Handed out rather than opened here for two reasons that are both
         * about the window system rather than about taste: the field is an
         * Activity ([TextInputActivity]) because an overlay cannot raise a soft
         * keyboard, and the card has to get out of its way before it opens
         * (`setTypingTop`) or it sits on top of it and eats every touch.
         */
        fun onTextInputToggle()

        /**
         * A page row in the scrollback, tapped to be shown again.
         *
         * Handed out rather than opened here because re-showing a page is not
         * just starting an activity: the card has to fold out of its own way
         * first, exactly as `ui.show_html` does. That sequencing belongs to
         * whoever owns the dispatcher, not to the window.
         */
        fun onHistoryPageClick(entry: ChatHistory.Entry)

        /**
         * A message row, double-tapped, to be read fullscreen.
         *
         * Handed out for the same reason as [onHistoryPageClick]: the reader is
         * fullscreen, so the card has to fold out of its way first, and that
         * sequencing belongs to whoever owns the dispatcher.
         */
        fun onTextOpen(entry: ChatHistory.Entry)
    }

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())

    private var root: BallHostFrame? = null
    private var params: WindowManager.LayoutParams? = null
    private var ball: EmotionBallTextureView? = null
    private var topBarView: View? = null
    private var historyView: HistoryListView? = null

    /** The ball's gesture vocabulary, pinned along the bottom of the full card. */
    private var tipsView: View? = null

    // Folded into p.alpha / p.flags by [applyWindow]. Main thread only.
    private var captureHidden = false
    private var compact = false

    /** The typing field's top edge, in screen px. 0 = no field is up. */
    private var typingTop = 0

    /** The window geometry [setTypingTop] borrowed, to be put back. */
    private var typingGeometry: IntArray? = null
    private var gesturePassthrough = false

    /** Whether the compositor is blurring behind us. See [Glass.frost]. */
    private var frosted = false

    /**
     * The card's own background image, at the bottom of the root's z-order.
     *
     * Permanent child, `GONE` while folded: a fullscreen image inside a 168 dp
     * corner square is a photo with a ball on it, and the fold's whole job is to
     * get out of the way. See [Backdrop].
     */
    private var backdrop: ImageView? = null

    /**
     * The scrim currently painted over the backdrop, or `null` for none.
     *
     * Held so repeated [applyWindow] calls during one card don't rebuild the
     * drawable and invalidate the window for a value that has not changed.
     */
    private var scrimShown: Int? = null

    /** Main thread only: last state handed to [setState], to spot transitions. */
    private var lastState: State? = null

    /** Geometry to put back when the fold ends — see [enterCompact]. */
    private var fullGeometry: IntArray? = null

    /**
     * The three independent reasons the ball can be hanging off the ledge, and
     * the derived answer to "is it hanging".
     *
     * Split because they are owned by different parties and they *will*
     * disagree. The task owns [perchedByTask] — it clings while driving an app
     * and climbs back to speak. The user owns [perchedByUser] by dragging it
     * onto the edge, and that placement has to survive the next task ending, or
     * the ball would haul itself out of the corner the user deliberately put it
     * in. A page the brain put on screen owns [perchedByPage] for exactly as
     * long as that page is up.
     *
     * The page needs its own flag rather than borrowing the task's: `show_html`
     * almost always runs *inside* a task, so setting the task flag would be a
     * no-op on the way in and — far worse — clearing it on the way out would
     * haul the ball back up in the middle of a task that is still running.
     *
     * A hand on the ball outranks all of it: dragging it off the ledge clears
     * every flag, because "get out of my way" and "come back here" are the same
     * gesture from the user's side.
     *
     * Main thread only.
     */
    private var perchedByTask = false
    private var perchedByUser = false
    private var perchedByPage = false
    private val perched: Boolean get() = perchedByTask || perchedByUser || perchedByPage

    /**
     * Compact spot with no task running — where the ball climbs back to.
     *
     * Tracked explicitly rather than sampled off the window when a task starts,
     * because at that moment `p.y` may be halfway through the previous climb:
     * two tasks in quick succession would then record a mid-flight value as home
     * and the ball would settle somewhere it had never been.
     *
     * X is tracked for the same reason and one more: a cling *moves* the ball
     * sideways to [perchX], so on the way back up there is no longer any
     * "wherever it already is" to fall back on. Without [homeX] the ball would
     * climb off the ledge and stay pinned against the right screen edge at
     * whatever height it rests at, which reads as stuck rather than as parked —
     * a worse position than the corner it would have returned to.
     */
    private var homeX = 0
    private var homeY = 0

    /**
     * Whether the perch pose is currently on, which is not the same as
     * [perched]: a task can start while the card is open, and the perch only
     * exists in the compact geometry. Also the drag's own latch — the finger
     * owns `p.y` mid-drag, so this flips as the ball crosses the ledge and the
     * geometry only catches up on release.
     */
    private var perchApplied = false

    // ---- Idle fidget (see [strollStep]) ----
    private val strollRunnable = Runnable { strollStep() }

    /** Hops left in the current burst before the next long rest. */
    private var strollLeft = 0

    private var slide: ValueAnimator? = null

    fun isShown(): Boolean = root != null

    /**
     * Fullscreen, and not draggable: the drag that moves the folded corner
     * ball is compact-only — the full card is a surface the user reads, not
     * a widget they reposition.
     *
     * Format stays TRANSLUCENT and the fill is the background colour's job —
     * see [paintCard]. The ball is a TextureView that blends its GL surface
     * against whatever the card is filled with, and [setVisibleForCapture]
     * drives window alpha to 0; both need a window the compositor is willing
     * to blend, whatever the card's own alpha happens to be.
     */
    fun show(): Boolean {
        if (root != null) return true
        // Before the ball exists, so the first frame is already the look the
        // user left it on. A no-op when nothing is saved, and a no-op for a
        // name that no longer matches a look — which is what makes deleting a
        // look from [BallLooks.ALL] safe.
        BallLooks.requestByName(VoiceConfig.ballLook(context))
        val v = build()
        val dm = context.resources.displayMetrics
        val side = dp(COMPACT_DP)
        val p = WindowManager.LayoutParams(
            side,
            side,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Kept in step with [applyWindow], which owns these from here on —
            // FLAG_LAYOUT_IN_SCREEN in particular, or the first measurement of
            // [winYOrigin] is taken in a frame the window is about to leave.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels - side - dp(COMPACT_MARGIN_DP)
            y = dm.heightPixels - side - dp(COMPACT_MARGIN_DP)
            // FLAG_LAYOUT_IN_SCREEN alone is not enough: since API 30 the window
            // also fits the system-bar insets by default, so on the Honor phone a
            // MATCH_PARENT card still started below the status bar and stopped
            // above the navigation bar. Zero lets the card cover the display.
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // Where the ball climbs back to when a task ends. [enterCompact] is the
        // other place this is set, and it is the one that usually runs — but
        // show() adds the window already folded and so never passes through it.
        // Left at its 0 default, the first unperch slid the ball to the top of
        // the screen.
        homeX = p.x
        homeY = p.y
        // Keep the restore target, but add the overlay already compact.
        //
        // This used to carry a second reason — "no frame in which a newly
        // enabled service owns a full-screen touchable window", because
        // returning false from onTouchEvent cannot forward a touch that has
        // already entered this window. That is no longer the service's
        // behaviour: it unfolds immediately after [show] (see
        // `ScreenBodyService.onServiceConnected`), so the card is up from the
        // first frame it is drawn in. What is left is the mechanical reason the
        // order is still worth keeping: compact is the state [fullGeometry] is
        // seeded from just below, and [exitCompact] is the single code path
        // that knows how to turn this window into a card — visibilities, ball
        // scale, scrollback refresh. Being born fullscreen would mean a second
        // way to arrive at that state, and the unfold is one `ui.post` away
        // anyway.
        compact = true
        // The restore target itself: skipped below precisely because we never
        // pass through enterCompact() (setCompact(true) is a no-op from this
        // state), and without it exitCompact() has nothing to grow back to —
        // the first unfold, whether that is the service's own at birth or a
        // long-press, would leave a fullscreen card's content inside a 160dp
        // corner square forever.
        fullGeometry = intArrayOf(
            0, 0,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        return try {
            v.hideFromAccessibility()
            wm.addView(v, p)
            root = v
            params = p
            syncCover()
            applyCompactAppearance()
            // Only now can [winYOrigin] be measured, and the resting position
            // depends on it — the y above is a first guess that is correct only
            // where the offset is 0.
            ui.post {
                refreshWinYOrigin()
                val pp = params ?: return@post
                if (!perchApplied) {
                    pp.y = compactHomeY()
                    homeX = pp.x
                    homeY = pp.y
                    runCatching { wm.updateViewLayout(v, pp) }
                }
            }
            retimeStroll()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** The signboard content while a card is up. Main thread only. */
    private var signboard: View? = null

    /**
     * Who to tell when a fold or unfold forces the signboard down.
     *
     * The window cannot be a corner square and host a card-sized pane at the
     * same time, so [enterCompact] and [exitCompact] evict rather than trying
     * to carry the signboard across the geometry change. Wired by CardUi, the
     * only thing that ever puts a signboard up.
     */
    var signboardEvictor: (() -> Unit)? = null

    /** True while the window is the folded corner ball. */
    fun isFolded(): Boolean = compact

    /**
     * Host a signboard inside this window, above the ball.
     *
     * This is the fix for the anchor drift the two-window design could never
     * quite shake: a separate card window positioned from a *measured* copy of
     * the ball's position goes stale whenever the ball is mid-slide, the
     * window origin offset is out of date, or a device's overlay coordinate
     * space disagrees with `getLocationOnScreen` — and each of those hits a
     * different subset of devices, which is why "sometimes it's off". Here
     * the card is a child of the very window the ball lives in. The tail is
     * centred on the window, the ball is centred in the window, and nothing
     * short of the layout system itself can put space between them.
     *
     * While folded, the window grows: the card rides on top, the ball area
     * drops to the bottom of the taller window, and `p.x/p.y` are recomputed
     * so the ball keeps its on-screen spot — the card *appears around* the
     * ball rather than the ball moving under a card. A perched ball climbs
     * back first (it cannot hold a sign hanging by its paws); [hideSignboard]
     * re-applies the perch, so the pose the sign interrupted survives it.
     *
     * While fullscreen, the signboard is a centred pane over the scrollback —
     * same window, so still no coordinate translation.
     *
     * Main thread only. One signboard at a time; false when there is no
     * window to host it (caller falls back to its own overlay).
     */
    fun showSignboard(content: View): Boolean {
        val v = root ?: return false
        val p = params ?: return false
        if (signboard != null) return false
        val dm = context.resources.displayMetrics
        if (!compact) {
            val w = minOf(dm.widthPixels - dp(48), dp(560))
            val lp = FrameLayout.LayoutParams(
                w, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
            )
            v.addView(content, lp)
            signboard = content
            return true
        }
        val side = dp(COMPACT_DP)
        // The width the card will get; measured here so the window can grow
        // by exactly the card's height in the same updateViewLayout.
        val w = minOf(dm.widthPixels - dp(32), dp(560))
        content.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        // The tail tip reaches into the ball area's transparent padding, down
        // to where the renderer's raised paws are — that overlap is what makes
        // the board read as held rather than as hovering a thumb's width
        // above the head.
        val overlap = (side * SIGN_OVERLAP_FRACTION).toInt()
        val h = content.measuredHeight + side - overlap
        // Ball keeps its spot. If it is hanging off the ledge, use the
        // climb-back target: holding a sign and gripping a ledge are two
        // different poses and the ledge loses.
        val ballY = if (perchApplied) homeY else p.y
        slide?.cancel()
        perchApplied = false
        syncWorking()
        p.width = w
        p.height = h
        p.x = (p.x + side / 2) - w / 2
        p.y = ballY + side - h
        // Ball area to the bottom of the taller window, CENTRED horizontally:
        // the window is card-wide now, not square, and a bottom-right ball
        // area would put the head off to one side of the tail. Verified on
        // device — centring is also what keeps the tail (drawn at the card's
        // centre) on the head after the screen-edge clamp moves the window.
        root?.ballAreaView?.let { area ->
            (area.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.height = side
                it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                area.layoutParams = it
            }
        }
        v.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        clampHeld(p)
        runCatching { wm.updateViewLayout(v, p) }
        signboard = content
        return true
    }

    /**
     * Take the signboard down and shrink back to the bare corner ball,
     * keeping the ball's on-screen spot — the inverse of [showSignboard].
     * Re-applies the perch afterwards if the sign had interrupted one.
     *
     * Main thread only. No-op when nothing is up.
     */
    fun hideSignboard() {
        val v = root ?: return
        val p = params ?: return
        val content = signboard ?: return
        signboard = null
        runCatching { v.removeView(content) }
        if (!compact) return
        val side = dp(COMPACT_DP)
        // Shrink the window around wherever the ball is *holding the sign*
        // (bottom of the taller window), so the card goes away without the
        // ball moving a single pixel. Perching is deliberately NOT done
        // here: in the "帮我看看" flow, beginTask lands one main-loop tick
        // later (CardUi.close runs hideSignboard synchronously, then the
        // onDone callback posts it), and setWorking→applyPerch slides to
        // the ledge as a proper 240ms hop. Pre-deciding the perch here used
        // to fight that: perchApplied was already true when applyPerch ran,
        // it returned without sliding, and the ball sat half-sunk where the
        // tall window's bottom had been until something else moved it —
        // the exact "先变成一半再瞬移" glitch. One owner for the perch
        // transition, and it is the one that animates.
        refreshWinYOrigin()
        val holdY = p.y + p.height - side
        val wantPerch = perchedByUser || perchedByPage
        p.x = p.x + p.width / 2 - side / 2
        p.y = holdY
        p.width = side
        p.height = side
        root?.ballAreaView?.let { area ->
            (area.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.height = FrameLayout.LayoutParams.MATCH_PARENT
                it.gravity = Gravity.TOP or Gravity.START
                area.layoutParams = it
            }
        }
        runCatching { wm.updateViewLayout(v, p) }
        if (wantPerch) {
            perchApplied = true
            // Both axes: the X here is the card's centre, not the ball's own,
            // because the window was card-wide a line ago and the ball was being
            // kept under the tail. Putting the perch back has to undo that too,
            // or the ball re-clings in the middle of the bottom edge — the one
            // spot a cling must not sit.
            slideTo(perchX(), perchY())
        } else {
            perchApplied = false
            homeX = p.x
            homeY = p.y
        }
        syncWorking()
        retimeStroll()
    }

    /**
     * Keep a held signboard on screen. Unlike [clampCompact] there is no
     * ledge to stop at — a ball holding a sign does not grip — and the whole
     * card has to stay readable, not just the ball.
     *
     * Main thread only.
     */
    private fun clampHeld(p: WindowManager.LayoutParams) {
        val dm = context.resources.displayMetrics
        val m = dp(8)
        p.x = p.x.coerceIn(m, (dm.widthPixels - p.width - m).coerceAtLeast(m))
        p.y = p.y.coerceIn(
            m - winYOrigin,
            (bottomEdge() - p.height - m - winYOrigin).coerceAtLeast(m - winYOrigin),
        )
    }

    /** A signboard is up — the ball is holding it. */
    fun isHoldingSign(): Boolean = signboard != null

    /**
     * End the card at this screen line while a typing field is up: `topPx` is
     * the field's own top edge, and `0` means no field.
     *
     * Why the card has to move at all: this window is a fullscreen
     * `TYPE_APPLICATION_OVERLAY`, and on this platform that type is drawn
     * **above** `TYPE_INPUT_METHOD` (measured from `dumpsys window`: our
     * overlays sort above the IME and above every app). So a card left in place
     * does not merely cover the field — it covers the keyboard too.
     *
     * The first cut hid the window instead (alpha 0 + untouchable). That works
     * and reads badly: what is behind it is whatever app the user was on, so
     * typing looked like being thrown out of the assistant and then dropped back
     * into it. Two jumps, no continuity. Ending the card at the field's top edge
     * keeps it on screen the whole time — the ball and the scrollback stay where
     * the user left them, and the field slides in underneath, which is what
     * every chat app does with a keyboard.
     *
     * **The number is the field's top, not the space it needs.** It is easy to
     * get backwards and it was: the first version computed
     * `displayMetrics.heightPixels - top`, which both used a height that
     * excludes the navigation bar (2524 here, not 2560) and left a 600 px band
     * of the user's launcher showing between the card and the field.
     *
     * The *shape* is never changed, only the height, and only for as long as the
     * field is up: [typingGeometry] holds what to put back, and a fold that
     * happens in the meantime takes precedence over it (see [enterCompact]).
     *
     * Main thread only, via the usual post.
     */
    fun setTypingTop(topPx: Int) {
        ui.post {
            if (typingTop == topPx) return@post
            typingTop = topPx
            applyTypingTop()
        }
    }

    private fun applyTypingTop() {
        val v = root ?: return
        val p = params ?: return
        if (typingTop > 0) {
            // Folded, the window is a corner square that is nowhere near the
            // field; there is nothing to step aside from.
            if (compact) return
            if (typingGeometry == null) {
                typingGeometry = intArrayOf(p.x, p.y, p.width, p.height)
            }
            p.height = maxOf(dp(MIN_TYPING_CARD_DP), typingTop)
            android.util.Log.i("Body", "card steps aside: field top=$typingTop → height=${p.height}")
            runCatching { wm.updateViewLayout(v, p) }
        } else if (compact) {
            // A fold in between owns the geometry now; just forget the snapshot.
            typingGeometry = null
        } else {
            restoreTypingGeometry()
        }
        // The flags change with it: see [applyWindow].
        applyWindow()
    }

    /** Put back the geometry [setTypingBand] borrowed. */
    private fun restoreTypingGeometry() {
        val g = typingGeometry ?: return
        typingGeometry = null
        val v = root ?: return
        val p = params ?: return
        p.x = g[0]
        p.y = g[1]
        p.width = g[2]
        p.height = g[3]
        runCatching { wm.updateViewLayout(v, p) }
    }

    fun hide(): Boolean {
        val v = root ?: return true
        return try {
            ui.removeCallbacks(strollRunnable)
            slide?.cancel()
            wm.removeView(v)
            root = null
            params = null
            ball = null
            syncCover()
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ---- State / content ----

    fun setState(s: State) {
        ui.post {
            val mood = when (s) {
                State.IDLE -> Mood.CALM
                State.RECORDING -> Mood.LISTENING
                State.THINKING -> Mood.THINKING
                State.SPEAKING -> Mood.SPEAKING
                State.ERROR -> Mood.CONCERNED
            }
            ball?.setMood(mood)
            ball?.setListening(s == State.RECORDING)
            // The model's face belongs to one utterance. Anything that is not
            // speaking has ended that utterance — including the IDLE the drain
            // posts — so the channel is dropped here rather than at each of the
            // places that stop the voice. Missing one of those would leave the
            // ball wearing an emotion into the next conversation.
            if (s != State.SPEAKING) ball?.setEmotion(null)
            // Two transitions are worth a gesture, and only two: the mic
            // opening is the one moment the user is waiting for an acknowledgement
            // before speaking, and an error is the one moment a face alone is
            // easy to miss. Animating every transition makes the ball twitch its
            // way through a conversation.
            //
            // The same two moments get a tone, for the same reason turned up a
            // notch: a face only lands if the user is looking at it, and having
            // just spoken to the room is precisely when they are not. Speaking
            // gets none — it announces itself — and errors get none either, now
            // that they arrive as a card. See [Earcon].
            if (s != lastState) {
                // A haptic for the hand that just touched the ball. Through the
                // view, not a Vibrator: no permission, it honours the system's
                // touch-feedback switch, and — unlike a tone — it cannot take
                // audio focus from a meeting or a call.
                val haptic = when {
                    s == State.RECORDING -> HapticFeedbackConstants.CONFIRM
                    lastState == State.RECORDING -> HapticFeedbackConstants.GESTURE_END
                    s == State.ERROR -> HapticFeedbackConstants.REJECT
                    else -> null
                }
                if (haptic != null) root?.performHapticFeedback(haptic)
                when (s) {
                    State.RECORDING -> {
                        ball?.trigger(Action.NOD)
                        Earcon.listening()
                    }
                    State.THINKING -> Earcon.thinking()
                    State.ERROR -> ball?.trigger(Action.SHAKE)
                    else -> {}
                }
                lastState = s
            }
            // Real state driving disables demo cycling.
            ball?.setDemoCycle(false)
            retimeStroll()
        }
    }

    /**
     * The face the model picked for the reply it is saying right now.
     *
     * Posted to the same handler as [setState], which is what keeps the two
     * ordered: the emotion is applied by [net.kuafuai.andee.tts.TtsController]
     * immediately after it asks for SPEAKING, and a null arriving from any
     * other state transition lands after it rather than racing it.
     */
    fun setEmotion(m: Mood?) {
        ui.post { ball?.setEmotion(m) }
    }

    /**
     * The agent ran a tool against the device. Shows as a nod plus the
     * SEARCHING face, which decays on its own — so this is fire-and-forget and
     * there is no matching "stopped" call to forget to make.
     */
    fun pulseTool() {
        ui.post { ball?.pulseTool() }
    }

    /** A task ended. A brief pleased or flinching face, then back to normal. */
    fun signalOutcome(ok: Boolean) {
        ui.post { ball?.signalOutcome(ok) }
    }

    /**
     * The user changed the language in 设置. Re-read everything the window is
     * showing right now.
     *
     * **Why this is needed at all.** The window is a `WindowManager` overlay
     * owned by the accessibility service, so it outlives any Activity and no
     * amount of `setApplicationLocales` will rebuild it — Android has no idea
     * these views exist. Every string in here was resolved once, when the view
     * was built, and the views are not rebuilt on a language change because the
     * ball's own state (where it is, whether it is listening, whether the card
     * is folded) lives in them. So the strings are re-read in place instead.
     *
     * Whatever gets added to this window later needs a line here too, or it
     * will keep speaking the language the device was in when the service
     * started. That is the whole cost of overlays living outside the Activity
     * lifecycle.
     */
    fun onLocaleChanged() {
        ui.post {
            (tipsView as? TextView)?.text = AppLocale.str(context, R.string.window_tips)
            historyView?.onLocaleChanged()
        }
    }

    /**
     * What is happening *right now*, shown as the live last row of the
     * scrollback rather than as a band under the ball.
     *
     * One place for everything the assistant says or hears, in the order it
     * happened: the partial transcript turns into the user's row, the tool
     * names and drafts turn into the assistant's. A separate band meant the
     * user had to watch two surfaces and meant the same sentence appeared on
     * both — which it did.
     */
    fun setSubtitle(text: String, kind: SubtitleKind = SubtitleKind.PARTIAL) {
        ui.post { historyView?.setLive(text, kind) }
    }

    fun clearSubtitle() = setSubtitle("")

    /**
     * Move the ball one look along and say which one it landed on.
     *
     * The renderer reads [BallLooks] on its own thread and latches it at the
     * top of a frame, so all this side has to do is *ask*; there is nothing to
     * post to the GL thread and nothing to wait for.
     *
     * Three things happen together on purpose. The pick is written to prefs
     * immediately rather than on some later 保存 — the ball changing in front
     * of the user is the confirmation, and there is no card here to put a
     * button on. The name is announced, because the looks are close enough
     * relatives that a user who swiped past one should be told what they now
     * have. And the ball pops, so the change reads as the ball *doing*
     * something rather than as a redraw.
     *
     * The announcement goes through the transcript's transient line
     * ([SubtitleKind.FINAL]) rather than being committed as a row: it is a
     * label on a control, not something anybody said, and it clears itself.
     */
    private fun stepBallLook(delta: Int) {
        val next = BallLooks.step(delta)
        VoiceConfig.save(context, mapOf("ball" to next.name))
        ball?.trigger(Action.POP)
        setSubtitle(AppLocale.str(context, next.labelRes), SubtitleKind.FINAL)
    }

    /**
     * Temporarily make the whole overlay non-touchable so a synthesized
     * gesture (dispatchGesture) can reach the app underneath. When off, the
     * overlay is passive: no clicks, no drag. Toggle it back on when done.
     *
     * The overlay is still visible either way — only touch is affected.
     */
    fun setTouchable(touchable: Boolean) {
        ui.post {
            gesturePassthrough = !touchable
            applyWindow()
        }
    }

    /**
     * Drive the whole window to alpha 0 so an [AccessibilityService.takeScreenshot]
     * captures what's underneath instead of us.
     *
     * Load-bearing since the card went fullscreen: the overlay is composited
     * into the display capture like any other window, so without this every
     * screenshot the brain receives is a black rectangle — and, once the card
     * grew a blur, a *blurred* screen even at alpha 0, which is why
     * [applyWindow] drops the blur on the same switch.
     * Window alpha rather than View.INVISIBLE or removeView — it's one field,
     * and it doesn't tear down the ball's GL context on every capture.
     *
     * Touch is unaffected; an invisible window still eats taps, which is fine
     * for the ~200 ms a capture holds it.
     */
    fun setVisibleForCapture(visible: Boolean) {
        ui.post {
            captureHidden = !visible
            applyWindow()
        }
    }

    /**
     * Fold to the corner ball so the user sees — and can touch — the real app
     * underneath. The card covers the whole screen and is not meaningfully
     * see-through, so this is the only way anything below it is ever usable.
     *
     * Shrinks rather than vanishes. The obvious implementation — window alpha 0
     * plus FLAG_NOT_TOUCHABLE — was the first cut, and it strands the user: a
     * fullscreen touchable window swallows every touch, so handing the screen
     * back means giving up touch, and giving up touch means the ball and the
     * stop button are both gone for good with no way to ask for the assistant.
     * Shrinking the window is what breaks the tie: the ball stays visible and
     * tappable in its own small rectangle while every other pixel belongs to
     * the user.
     *
     * Idempotent, and there is nothing time-based in here — this is a resting
     * state, not a timed peek. Ending it is the caller's business, since when
     * to come back isn't ours to decide.
     */
    fun setCompact(compact: Boolean) {
        ui.post {
            if (this.compact == compact) return@post
            this.compact = compact
            syncCover()
            if (compact) enterCompact() else exitCompact()
            applyWindow()
        }
    }

    /**
     * Publish "this window is covering the display" for the two readers that
     * cannot be handed a reference to it — see [FullscreenCard].
     *
     * Derived from both facts rather than mirrored off [setCompact], because
     * [hide] leaves `compact` alone: a card torn down while unfolded would
     * otherwise keep claiming to cover a screen it is no longer on. Main thread
     * only, like everything that touches these two fields.
     */
    private fun syncCover() {
        FullscreenCard.covering = root != null && !compact
    }

    /**
     * Whether the card is folded, read from outside.
     *
     * Only the service asks, and only for one decision: a fold takes the
     * scrollback with it (`historyView` goes `GONE`), so anything that has to
     * be readable *now* cannot be put there. See the answer's fallback card in
     * [net.kuafuai.andee.ScreenBodyService].
     */
    fun isCompact(): Boolean = compact

    /**
     * Difference between a window's `p.y` and where it actually lands on
     * screen, measured rather than assumed.
     *
     * `Gravity.TOP` plus `FLAG_LAYOUT_NO_LIMITS` is supposed to make `p.y` a
     * display coordinate, and on the tablet it is — the offset measures 0. On
     * an Honor phone it measures the status bar height, so everything computed
     * against `bottomEdge()` landed that much too low: the ledge the paws hang
     * on ended up past the bottom of the physical screen, which is why the ball
     * showed but its hands never did.
     *
     * Not worth arguing with. One `getLocationOnScreen` says what the device
     * actually does, and subtracting it makes the same arithmetic correct on
     * both.
     *
     * **It belongs to the folded geometry and to nothing else** — see
     * [refreshWinYOrigin], which is the whole reason this is not simply read
     * whenever it is wanted.
     */
    private var winYOrigin = 0

    /**
     * Re-measure [winYOrigin]. Needs the window laid out and *not* mid-slide,
     * or `p.y` and the on-screen location come from different frames.
     *
     * **Only ever measured while the window is the corner square**, because the
     * offset is a property of the *geometry* and not of the window type. On this
     * Honor phone, measured through `BallGeom`: as a `MATCH_PARENT` card the
     * frame starts at `126` with `p.y = 0`, so the offset reads as the status
     * bar height; as the 168 dp square it reads `0`, because an explicitly sized
     * and positioned `FLAG_LAYOUT_NO_LIMITS` window really is placed in display
     * coordinates. `FLAG_LAYOUT_IN_SCREEN` does not close that gap — a
     * MATCH_PARENT overlay still resolves against the content frame.
     *
     * [enterCompact] used to take the measurement in the card's own frame, one
     * line before shrinking to the square, and then spend it on the square's
     * `p.y`. That is the bug behind "球球悬在底部上面": every value here is
     * subtracted from a screen Y, so a 126 that belonged to the outgoing frame
     * lifted the folded ball 126 px clear of the edge its paws are drawn to
     * grip. The ledge was right whenever [applyPerch] happened to re-measure
     * afterwards and wrong whenever the fold arrived already perched — which is
     * the ordinary case, since a task starts before its first `screen.*` call.
     *
     * Main thread only.
     */
    private fun refreshWinYOrigin() {
        val v = root ?: return
        val p = params ?: return
        if (v.width == 0 || slide?.isRunning == true) return
        if (p.width == WindowManager.LayoutParams.MATCH_PARENT) return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        winYOrigin = loc[1] - p.y
    }

    /** Resting compact `p.y`: bottom-right of the *visible* area. Main thread only. */
    private fun compactHomeY(): Int =
        bottomEdge() - dp(COMPACT_DP) - dp(COMPACT_MARGIN_DP) - winYOrigin

    /**
     * The assistant is working: sink the corner ball half-way below the bottom
     * screen edge, hang it there by two paws, and put a busy face on it. Haul
     * back up when the task ends, which is also when the answer starts being
     * read aloud.
     *
     * The two halves say different things and both are wanted. The face says
     * "I'm working"; the perch says "and you can have your corner back while I
     * do". A thumb-sized ball sitting fully in frame for a minute of app
     * driving is an obstruction over the very app it is driving — but making it
     * vanish would strand the user, since the ball is the only way to reach the
     * assistant once the card is folded. Half a ball is the trade: still
     * visible, still tappable, out of the way.
     *
     * Getting *out* of the way is what makes coming back legible. The climb back
     * at [endTask] is the ball stepping forward to speak, and there is nothing
     * to step forward from if it never stepped back.
     *
     * Only one of the two reasons to hang — the user can drag the ball onto the
     * ledge themselves, and that outlasts the task. See [perchedByTask].
     */
    fun setWorking(on: Boolean) {
        ui.post {
            if (perchedByTask == on) return@post
            perchedByTask = on
            applyPerch()
            syncWorking()
            retimeStroll()
        }
    }

    /**
     * A page the brain composed is on screen — cling to the ledge until it is
     * gone.
     *
     * The card is folded by then, but a folded ball is still a thumb-sized
     * opaque thing sitting on top of a page whose whole reason for existing is
     * to be looked at, and it lands wherever it last was, which is as likely as
     * not the middle of it. Half a ball on the bottom edge keeps the assistant
     * reachable without standing in front of what it just drew.
     *
     * Independent of [setWorking]; see [perchedByPage] for why it has to be.
     */
    fun setPagePerched(on: Boolean) {
        ui.post {
            if (perchedByPage == on) return@post
            perchedByPage = on
            applyPerch()
            syncWorking()
            retimeStroll()
        }
    }

    /**
     * Tell the ball whether it is currently hanging off a screen edge.
     *
     * Conditional on being folded, not just on there being a task: the ball
     * grows paws, stretches and swings from them, and in the middle of a
     * fullscreen card there is no edge for any of that to hang off — it would
     * read as a ball sprouting hands into thin air. Main thread only.
     */
    private fun syncWorking() {
        ball?.setWorking(compact && perched)
        ball?.setAtLedge(compact && perchApplied && !slideRunning())
    }

    /** True while a perch/home slide animation is in flight. Main thread only. */
    private fun slideRunning(): Boolean = slide?.isRunning == true

    /**
     * The drawn sphere's on-screen bounding box, for anyone anchoring
     * something to it — currently CardUi, which holds its signboards above it.
     *
     * The *sphere*, not the window. It used to be the window, which was
     * accidentally right while the only window was the 168dp corner square
     * (CardUi could reconstruct the ball from it) and badly wrong the moment
     * the card went fullscreen: the same arithmetic then described a 288px
     * radius ball floating in the middle of the screen, and every card landed
     * on top of the real one.
     *
     * Measured off the live views rather than computed from `p.x/p.y`, so a
     * card asked mid-slide or mid-drag still lands where the ball actually is.
     * Null if the window is gone. Answers on the main thread via the callback.
     */
    fun ballRect(cb: (IntArray?) -> Unit) {
        ui.post {
            val v = root ?: return@post cb(null)
            val area: View = v.ballAreaView ?: v
            val loc = IntArray(2)
            area.getLocationOnScreen(loc)
            // The same two numbers the renderer uses: the sphere is a fixed
            // fraction of the surface's shorter side, times the view scale the
            // ball is drawn at (1f when folded — see applyCompactAppearance).
            val scale = if (compact) 1f else BALL_SCALE
            val r = (min(area.width, area.height) * SPHERE_RADIUS_FRACTION * scale).toInt()
            val cx = loc[0] + area.width / 2
            // The sphere may be drawn below the area's centre while it is
            // dodging the control cluster — see [ballDrop].
            val cy = loc[1] + area.height / 2 + (ball?.translationY ?: 0f).toInt()
            cb(intArrayOf(cx - r, cy - r, cx + r, cy + r))
        }
    }

    /** A signboard is up — the ball waits, attending, instead of idling. */
    fun setHolding(on: Boolean) {
        ui.post {
            ball?.setHolding(on)
            if (on) {
                // Stand still while asking: a ball that fidgets through its own
                // question doesn't look like it cares about the answer.
                ui.removeCallbacks(strollRunnable)
            } else {
                retimeStroll()
            }
        }
    }

    /**
     * Move to or from the perched pose, if the window is in a geometry that
     * has a corner to cling to at all. Nothing to do while the card is
     * fullscreen; [enterCompact] re-reads [perched], so a task that began with
     * the card open still perches the moment it folds.
     *
     * Both axes on the way down and both on the way back up: going down is a
     * cling, and a cling picks its side ([perchX]); coming back up restores the
     * spot the ball was clinging *from* ([homeX]/[homeY]) rather than the spot
     * it happens to be in, which is the corner. See [homeX] for what goes wrong
     * if only Y is restored.
     *
     * Main thread only.
     */
    private fun applyPerch() {
        if (!compact) return
        if (perched == perchApplied) return
        perchApplied = perched
        refreshWinYOrigin()
        if (perched) {
            slideTo(perchX(), perchY())
        } else {
            slideTo(homeX, homeY)
        }
    }

    /**
     * Window Y that puts the ball on the bottom screen edge, hanging.
     *
     * Absolute, deliberately — an offset from wherever the window happens to be
     * was the obvious version and it was wrong: drag the ball anywhere but its
     * default corner and "sink by N pixels" sinks it to somewhere that isn't the
     * screen edge, leaving the paws gripping thin air.
     *
     * The ledge is the bottom edge and only the bottom edge, because a paw needs
     * something to hook over and the paws are drawn to either side of the ball's
     * own centre line — there is no rotation of that pose that grips a vertical
     * edge, so a ball hung off the side of the screen would have its hands in
     * mid-air. Only the horizontal edge runs the full width and can be gripped
     * anywhere along it. So a cling moves the ball *along* that edge, never
     * across it: this picks the Y, [perchX] picks the X, and "which side of the
     * screen" is answered by [perchX] rather than left to the finger.
     *
     * The sphere is centred in the compact window at [SPHERE_RADIUS_FRACTION]
     * of it; putting [CLING_HIDDEN] of it below the edge puts its centre at
     * `edge + r * (2 * CLING_HIDDEN - 1)`. [EmotionBallRenderer] derives the
     * paws' world Y from the same expression, which is why both fractions are
     * shared rather than local to either. At `CLING_HIDDEN = 0.5` the radius
     * term is zero and the expression is just "centre on the edge" — the
     * fraction only starts to matter if that half ever moves.
     */
    private fun perchY(): Int {
        val side = dp(COMPACT_DP)
        val r = side * SPHERE_RADIUS_FRACTION
        val edge = bottomEdge()
        return (edge + r * (2f * CLING_HIDDEN - 1f) - side / 2f).toInt() - winYOrigin
    }

    /**
     * Window X for a cling: the bottom-**right** corner, with the drawn sphere
     * [COMPACT_MARGIN_DP] of clear air from the right screen edge.
     *
     * The ball used to stay at whatever X the finger released it at, and the
     * reason to stop doing that is a gesture conflict the user hit on a phone:
     * a ball clinging near the middle of the bottom edge sits inside the band
     * the system claims for "swipe up", so the drag that lifts it off the ledge
     * goes to the launcher instead of to the ball. A clinging ball is exactly
     * the one the user has to be able to pull back out — it is the only way to
     * reach the assistant once the card is folded — so it goes where the system
     * is least interested in the gesture: the corner. Right rather than left
     * matches where this app parks the ball everywhere else ([compactHomeY] is
     * the bottom-right of the visible area, and [show] and [enterCompact] both
     * build the window there).
     *
     * [spherePad] rather than a bare window clamp, because most of the compact
     * window is transparent air: clamping the *window* to the screen leaves the
     * *ball* a thumb's width short of the edge. That is deliberate for the
     * resting corner — the same gap on Y is what keeps the magnet from tripping
     * at rest, and a ball parked for a task should not be clinging — but a ball
     * already hanging has no such reason to sit inboard, and every reason not
     * to.
     *
     * Measured, because the paw is what sets the floor here and it is not
     * where the silhouette is: `blob` draws each paw part in front of the ball
     * (z = 0.88 against the body's z = 0), so perspective magnifies it. At full
     * grip the right paw's outer toe lands 47.8 dp from the sphere's centre
     * against a silhouette radius of 40.3 dp — it overhangs by 7.5 dp. On a
     * 411 dp-wide phone, from the right screen edge:
     *
     *     resting corner   x = W - side - margin      claw  52.2 dp in
     *     perchX()         x = W - side + pad - margin claw   8.2 dp in
     *     drag limit       x = W - side + pad          claw   7.8 dp *out*
     *
     * So the corner is as far right as this can go: a margin under ~10 dp
     * starts cutting the toe that hangs over, and [clampCompact] already lets a
     * dragged ball push past that. [COMPACT_MARGIN_DP] clears it with room and
     * costs nothing, being the inset the ball already uses against the bottom.
     *
     * Main thread only.
     */
    private fun perchX(): Int {
        val side = dp(COMPACT_DP)
        val dm = context.resources.displayMetrics
        return dm.widthPixels - side + spherePad() - dp(COMPACT_MARGIN_DP)
    }

    /**
     * Transparent margin between the compact window's edge and the drawn
     * sphere, px. The sphere is [SPHERE_RADIUS_FRACTION] of the side and centred,
     * so most of the window is empty air — which is why clamping the *window* to
     * the screen would strand the ball a thumb's width short of every edge.
     * Clamp against this instead and the ball can sit flush.
     */
    private fun spherePad(): Int {
        val side = dp(COMPACT_DP)
        return side / 2 - (side * SPHERE_RADIUS_FRACTION).toInt()
    }

    /**
     * Should the ball be gripping the ledge, given where the window is now?
     *
     * The rule is physical rather than a tuned distance: it grabs on once the
     * drawn sphere's *underside* has reached the ledge, i.e. once the ball is
     * visibly touching the bottom edge. [SNAP_GRACE_DP] is the only fudge — it
     * lets a finger that got close enough count, without which the user would
     * have to land the ball on an exact pixel row to make it cling.
     *
     * Sized so the ball's own resting corner is comfortably outside it: at rest
     * the sphere clears the ledge by about 60 dp, so parking in the corner the
     * ordinary way does not trip the magnet.
     *
     * While a signboard is held the window is taller than the ball area, so
     * the sphere's underside is `y + winYOrigin + (window height − side/2)`,
     * not `y + side/2` — without this a held signboard thought the ball was
     * always on the ledge and drag-to-hold fought the magnet.
     *
     * Main thread only.
     */
    private fun wantsPerch(y: Int): Boolean {
        val side = dp(COMPACT_DP)
        val sphereBottom = y + winYOrigin + windowH() - side / 2 +
            (side * SPHERE_RADIUS_FRACTION).toInt()
        return sphereBottom >= bottomEdge() - dp(SNAP_GRACE_DP)
    }

    /**
     * Keep a dragged ball reachable: the sphere may go flush with any edge but
     * not past it.
     *
     * Y stops at [perchY] rather than at the screen edge — dragging *down* past
     * the ledge has nowhere further to go, and stopping there means the finger
     * is already holding the ball exactly where the magnet wants it. Nothing
     * clamped X or Y before this, and a ball dragged off the side of the screen
     * was simply gone: it is the only way to reach the assistant once folded, so
     * losing it is not a recoverable state.
     *
     * Main thread only.
     */
    private fun clampCompact(p: WindowManager.LayoutParams) {
        val side = dp(COMPACT_DP)
        val pad = spherePad()
        val dm = context.resources.displayMetrics
        p.x = p.x.coerceIn(-pad, dm.widthPixels - side + pad)
        // Signboard up: the whole pane stays on screen and there is no ledge
        // to stop at (a holding ball does not grip). Down: the original rule.
        if (signboard != null) {
            clampHeld(p)
        } else {
            p.y = p.y.coerceIn(-pad - winYOrigin, perchY())
        }
    }

    /**
     * End of a drag: commit the pose the drag was already showing, and pull the
     * window the last few pixels onto the ledge if it is clinging.
     *
     * The pull is what makes it read as a magnet rather than as a drop — the
     * finger is never accurate to the pixel, and a ball left one pixel shy of
     * the ledge with its paws out is gripping nothing. Short and fast on
     * purpose: [PERCH_SLIDE_MS] is the assistant announcing a state change,
     * this is the tail end of the user's own gesture.
     *
     * Both axes, though the finger only ever dropped this ball on one: grabbing
     * the ledge also claims the corner beside it, see [perchX]. The horizontal
     * half of the pull is the longer one whenever the user hung the ball up
     * mid-screen, and it is the half that matters — that is the position where
     * the next swipe up goes to the launcher instead of to the ball.
     *
     * Main thread only.
     */
    private fun settleAfterDrag() {
        val v = root ?: return
        val p = params ?: return
        if (!compact) return
        clampCompact(p)
        if (perchApplied) {
            perchedByUser = true
            slideTo(perchX(), perchY(), SNAP_MS)
        } else {
            homeX = p.x
            homeY = p.y
        }
        runCatching { wm.updateViewLayout(v, p) }
    }

    /**
     * Y of the ledge the ball hangs off: the bottom of the area a user can
     * actually see, which is not the bottom of the display.
     *
     * On a tablet with a hairline gesture pill those are near enough the same
     * number that `displayMetrics.heightPixels` looked correct. On a phone they
     * are not. The navigation bar owns the bottom band and is layered *above*
     * TYPE_APPLICATION_OVERLAY, while `FLAG_LAYOUT_NO_LIMITS` lets us lay out
     * underneath it — so hanging half a ball below `heightPixels` put the
     * visible half behind the nav bar and the ball disappeared. Half a ball is
     * the entire margin of error here, and the inset is deeper than it.
     */
    private fun bottomEdge(): Int {
        val m = wm.currentWindowMetrics
        val inset = m.windowInsets
            .getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            ).bottom
        return m.bounds.bottom - inset
    }

    /**
     * Animated rather than teleported: the perch is a statement about what the
     * assistant is doing, and a ball that jumps has not said anything the user
     * can follow.
     *
     * Both axes travel on one interpolator, because a cling decides both — it
     * hangs off the bottom edge at [perchY] *and* takes the right corner at
     * [perchX] — and separating them would read as the ball changing its mind
     * halfway down. The un-perch passes the current X and so is the vertical
     * slide it always was; see [applyPerch] for why that one has no business
     * moving sideways.
     *
     * Main thread only.
     */
    private fun slideTo(targetX: Int, targetY: Int, ms: Long = PERCH_SLIDE_MS) {
        val v = root ?: return
        val p = params ?: return
        slide?.cancel()
        if (p.x == targetX && p.y == targetY) return
        val fromX = p.x
        val fromY = p.y
        val dx = targetX - fromX
        val dy = targetY - fromY
        slide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                p.x = fromX + (dx * t).roundToInt()
                p.y = fromY + (dy * t).roundToInt()
                runCatching { wm.updateViewLayout(v, p) }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // The geometry is now truly where perchApplied said it was.
                    // This is the moment the cling (paws, half-sunk body) may
                    // start — before it, the ball was travelling and a cling
                    // drawn mid-flight read as chopped-in-half.
                    syncWorking()
                }
            })
            start()
        }
    }

    // ---- Idle fidget ----

    /**
     * Hop on the spot, every so often, when there is nothing else going on.
     *
     * A corner ball that holds one pixel for an hour reads as a decal. A few
     * hops every half-minute is the cheapest way to say there is something alive
     * in there — and because the ball *is* the only way to reach the assistant
     * once the card is folded, looking alive is also the thing that makes it
     * look tappable.
     *
     * On the spot, not travelling: the window stays exactly where the user put
     * it. A ball that wanders has to be re-found every time it is wanted, which
     * costs more than the liveliness is worth.
     *
     * Main thread only.
     */
    private fun strollStep() {
        if (!strollWanted()) {
            retimeStroll()
            return
        }
        ball?.trigger(Action.HOP)

        strollLeft--
        // Two to four hops, then stand still for a while. A ball that hopped on
        // a fixed tick would be a metronome; the point is to look idle, not
        // scheduled.
        val delay = if (strollLeft > 0) {
            STROLL_HOP_MS
        } else {
            strollLeft = 2 + Random.nextInt(3)
            STROLL_REST_MS + Random.nextInt(STROLL_REST_JITTER_MS).toLong()
        }
        ui.postDelayed(strollRunnable, delay)
    }

    /**
     * Only when folded, idle, and awake.
     *
     * Not while working — it is hanging off the edge by its paws, and a ball
     * that bounces while gripping is doing two incompatible things. Not while
     * dozing either: the point of SLEEPING is that the device reads as off, and
     * a ball twitching in its sleep undoes that on its own.
     *
     * Main thread only.
     */
    private fun strollWanted(): Boolean =
        root != null && compact && !perched && signboard == null &&
                lastState.let { it == null || it == State.IDLE } &&
                ball?.isDozing() != true

    /** Push the next hop out to a full rest. Main thread only. */
    private fun retimeStroll() {
        ui.removeCallbacks(strollRunnable)
        strollLeft = 2 + Random.nextInt(3)
        ui.postDelayed(
            strollRunnable,
            STROLL_REST_MS + Random.nextInt(STROLL_REST_JITTER_MS).toLong(),
        )
    }

    /**
     * Bare ball, bottom-right, on nothing at all. The toolbar is ~10 buttons
     * wide and the subtitle band is 200dp tall — neither survives the shrink,
     * so both go rather than getting squeezed into something illegible.
     *
     * The window background goes with them, and the ball carries its own dark
     * fill instead (`EmotionBallRenderer.drawBody`). A background can only be a
     * rectangle or an oval filling the window, which is bigger than the sphere:
     * the two then read as one ball inside another, and the outer one wins —
     * larger, harder-edged, and unable to squash or hop with the ball it is
     * supposed to be part of.
     *
     * Ball at view scale 1: [BALL_SCALE] is the full card's magnification, and
     * a corner square has nothing to magnify into.
     *
     * Main thread only.
     */
    private fun applyCompactAppearance() {
        root?.background = null
        backdrop?.visibility = View.GONE
        topBarView?.visibility = View.GONE
        // Not just invisible — GONE, so the ball area has the whole window to
        // itself. A scrollback left in the layout would cover most of a 168dp
        // square and there would be nothing left to see the ball in.
        historyView?.visibility = View.GONE
        tipsView?.visibility = View.GONE
        // The whole square is the ball's, so the sphere is centred in it and
        // sized against it.
        setBallAreaHeight(FrameLayout.LayoutParams.MATCH_PARENT)
        ball?.scaleX = 1f
        ball?.scaleY = 1f
        // The corner square has no cluster to dodge, so the ball sits dead
        // centre again — and the fold animation lands from wherever it was.
        ball?.translationY = 0f
    }

    /**
     * The card's two stacked areas: the ball rests in the top
     * [BALL_AREA_FRACTION] of it, the scrollback fills the rest.
     *
     * The ball area is also the hit-test bound: [BallHostFrame.onTouchEvent]
     * drops anything below `ballAreaView.height` so the list keeps its own
     * touches, and the list is a ScrollView that needs every one of them.
     *
     * Main thread only.
     */
    /**
     * Re-derive the card's two stacked areas, and where the ball sits in the top
     * one, from the card's current height.
     *
     * Called from the ball host's size change and from the cluster's own layout,
     * because the cluster's height is one of the inputs and it moves: the
     * status-bar inset lands after the first layout. Listening to the cluster
     * rather than hooking that call site keeps it to one place that can be
     * forgotten.
     *
     * Main thread only.
     */
    private fun refitCardAreas() {
        val r = root ?: return
        if (compact) return
        val h = r.height
        if (h <= 0) return
        // While a field is up this window is shorter than the card it is
        // showing, and the band the ball lives in is measured against the
        // **card**, not the window: the sphere keeps the size and the place the
        // user left it in, and the top of the card stays the same picture it was
        // before the keyboard came up. Only the scrollback takes the difference
        // — and it has to, being the part that scrolls. Measured: with the band
        // taken from the window instead, the ball dropped from a 344 px radius
        // to 213 and every pixel of the card's upper half moved.
        val bandH = if (typingTop > 0) wm.currentWindowMetrics.bounds.height() else h
        val ballH = (bandH * BALL_AREA_FRACTION).toInt()
        setBallAreaHeight(ballH)
        // The tips bar is measured, not reserved by a guessed constant: it is one
        // line of text, so its height follows the display's font scale, and a
        // fixed dp would either clip the line on a large-text device or leave a
        // gap on a small one. Zero while it is hidden, which is what keeps the
        // folded corner square from carrying 40 dp of nothing.
        val tipsH = tipsHeight()
        setHistoryHeight(maxOf(0, h - ballH - tipsH))
        setHistoryBottomMargin(tipsH)
        ball?.translationY = (ballDrop(bandH) + dp(BALL_NUDGE_DP)).toFloat()
    }

    /**
     * The tips bar's real height, measured on demand.
     *
     * On demand rather than stored because it is a function of the font scale
     * and the window width, and both can change between two calls. The measure
     * pass is only ever needed before the first layout of a fresh unfold — after
     * that the view answers for itself.
     *
     * Main thread only.
     */
    private fun tipsHeight(): Int {
        val v = tipsView ?: return 0
        if (v.visibility != View.VISIBLE) return 0
        if (v.height > 0) return v.height
        val w = root?.width ?: return 0
        if (w <= 0) return 0
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return v.measuredHeight
    }

    /**
     * How far the ball has to drop to clear the control cluster, in px. Zero on
     * any device where the sphere cannot reach the cluster in the first place —
     * which is every device wide enough that the cluster's left edge sits past
     * the sphere's.
     *
     * The ball is centred in its band, so its top edge sits
     * `(0.5 − SPHERE_RADIUS_FRACTION × BALL_SCALE)` of the band's height below
     * the band's top. The cluster is a fixed 44 dp pushed down by the status bar
     * / cutout inset (see [insetTopBar]), so whether the two meet is a property
     * of the device. Measured against the real numbers: on a 711×1138 dp tablet
     * they never meet; on a 393 dp phone they cleared each other by 0.7 dp; on a
     * 411 dp phone with a taller status bar they overlapped by 4 dp.
     *
     * The first fix here grew the band until the sphere's top edge was pushed
     * below the cluster. That works, and it hides its cost: the band's bottom
     * edge *is* the scrollback's top edge, so buying clearance that way shoves
     * the chat down and shortens it — a corner collision paid for with the whole
     * card's split. It was 60 dp of chat on a phone, and that is exactly what the
     * user came back with ("the chat area doesn't need to be that far down").
     *
     * Dropping the ball instead touches one thing: it slides the sphere down
     * inside its own band, into the empty space that already sits between the
     * ball and the list. Same band, same split, same sphere size, still centred —
     * only its y moves, by roughly the 12-17 dp it takes.
     *
     * Main thread only.
     */
    private fun ballDrop(h: Int): Int {
        val bar = topBarView ?: return 0
        val w = root?.width ?: return 0
        if (bar.height <= 0 || w <= 0) return 0   // not measured yet; the layout hook comes back
        val bandH = h * BALL_AREA_FRACTION
        val r = SPHERE_RADIUS_FRACTION * bandH * BALL_SCALE
        // Horizontal reach, measured from the sphere's centre to the cluster's
        // left edge. Past the radius, the cluster is not in the sphere's column
        // at all and no drop is needed.
        val dx = bar.left - w / 2f
        if (abs(dx) >= r) return 0
        val topEdge = bandH / 2f - sqrt(r * r - dx * dx)
        return maxOf(0f, bar.bottom + dp(BALL_TOP_GAP_DP) - topEdge).toInt()
    }

    private fun setBallAreaHeight(px: Int) {
        val area = root?.ballAreaView ?: return
        val lp = area.layoutParams ?: return
        lp.height = px
        area.layoutParams = lp
    }

    private fun setHistoryHeight(px: Int) {
        val v = historyView ?: return
        val lp = v.layoutParams ?: return
        lp.height = px
        v.layoutParams = lp
    }

    /**
     * Lift the scrollback clear of the tips bar.
     *
     * The list is bottom-anchored, so its height alone would still put its last
     * rows underneath the bar — the two are stacked at the same edge.
     */
    private fun setHistoryBottomMargin(px: Int) {
        val v = historyView ?: return
        val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.bottomMargin = px
        v.layoutParams = lp
    }

    private fun enterCompact() {
        val p = params ?: return
        slide?.cancel()
        // A signboard cannot survive the fold — the window shrinks to a corner
        // square smaller than the pane. Evict through [signboardEvictor] so
        // CardUi's own callbacks fire (timeout semantics, holding state).
        if (signboard != null) {
            signboard = null
            signboardEvictor?.invoke()
        }
        // A typing band shrinks the window, and this line is what a later unfold
        // would grow back to — so the real geometry has to be back in place
        // *before* it is snapshotted, or the card unfolds into a card with a
        // keyboard-sized hole in the bottom of it.
        restoreTypingGeometry()
        fullGeometry = intArrayOf(p.x, p.y, p.width, p.height)
        val side = dp(COMPACT_DP)
        val dm = context.resources.displayMetrics
        p.width = side
        p.height = side
        // X is normally the resting corner, a thumb's width in (see
        // [compactHomeY] for why the same gap is deliberate on Y). A fold that
        // lands already clinging skips the resting corner entirely, though —
        // nothing will slide it afterwards — so it has to be born at [perchX],
        // or the same cling would sit at a different X depending on whether the
        // task started before or after the card folded.
        p.x = if (perched) perchX() else dm.widthPixels - side - dp(COMPACT_MARGIN_DP)
        p.y = compactHomeY()
        // The fold usually happens because a task started, so the perch is
        // normally already wanted by the time we get here. Applied outright
        // rather than slid: it is one motion with the fold, not a second one.
        homeX = p.x
        homeY = p.y
        perchApplied = perched
        if (perched) p.y = perchY()
        applyCompactAppearance()
        syncWorking()
        retimeStroll()
        // Everything above is placed from whatever [winYOrigin] the *last* fold
        // measured, because the square's own frame does not exist until this one
        // has been laid out. Re-measure as soon as it does.
        ui.post { reseatCompact() }
    }

    /**
     * Re-place the folded ball against a freshly measured [winYOrigin].
     *
     * A no-op unless the number actually moved, which is every fold but the
     * first on a device whose folded frame is offset from the display — and on
     * the ones where it is not offset at all, every fold. It exists so that
     * [enterCompact] does not have to depend on a measurement having been taken
     * earlier: [show] takes one, but it bails on a view that is not laid out
     * yet, and the first fold arrives moments later.
     *
     * Main thread only.
     */
    private fun reseatCompact() {
        val v = root ?: return
        val p = params ?: return
        if (!compact || signboard != null || p.width != dp(COMPACT_DP)) return
        val before = winYOrigin
        refreshWinYOrigin()
        if (winYOrigin == before) return
        homeY = compactHomeY()
        p.y = if (perchApplied) perchY() else homeY
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** Main thread only. */
    private fun exitCompact() {
        val p = params ?: return
        slide?.cancel()
        // Same eviction as the fold, in the other direction: the card grows
        // and the signboard's compact geometry (ball at the window's bottom)
        // is meaningless inside it.
        if (signboard != null) {
            signboard = null
            signboardEvictor?.invoke()
        }
        perchApplied = false
        val g = fullGeometry ?: intArrayOf(
            0, 0,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        // Belt-and-braces: a stale stored geometry can be a compact-sized
        // rectangle (service killed while folded, then restored). Growing the
        // window to a corner square is worse than useless, so never restore
        // anything smaller than about a third of the screen.
        val dm = context.resources.displayMetrics
        if (g[2] >= dm.widthPixels / 3 && g[3] >= dm.heightPixels / 3) {
            p.x = g[0]; p.y = g[1]; p.width = g[2]; p.height = g[3]
        } else {
            p.x = 0; p.y = 0
            p.width = WindowManager.LayoutParams.MATCH_PARENT
            p.height = WindowManager.LayoutParams.MATCH_PARENT
        }
        fullGeometry = null
        // The fill is [applyWindow]'s to set — it is the only place that knows
        // whether the compositor agreed to blur — and [setCompact] calls it
        // immediately after this, before the next frame.
        topBarView?.visibility = View.VISIBLE
        historyView?.visibility = View.VISIBLE
        tipsView?.visibility = View.VISIBLE
        backdrop?.visibility = View.VISIBLE
        // The two areas' heights are deferred to [BallHostFrame.onSizeChanged]
        // rather than computed here: the window is still the corner square at
        // this point, and the real height only exists after the layout pass
        // that this geometry change is about to trigger.
        //
        // Anything that arrived while folded — which is most of it, since the
        // card is folded for the whole time the assistant is working — has
        // only ever been appended to a list that wasn't being laid out.
        historyView?.refresh()
        ball?.scaleX = BALL_SCALE
        ball?.scaleY = BALL_SCALE
        syncWorking()
        retimeStroll()
    }

    /**
     * Alpha, touchability and the blur each have more than one reason to be
     * off, and the callers overlap in time (a capture while folded). Kept as
     * separate booleans folded here rather than written directly, or whichever
     * caller finishes last wins and clobbers the other's intent.
     *
     * Main thread only.
     */
    private fun applyWindow() {
        val v = root ?: return
        val p = params ?: return
        p.alpha = if (captureHidden) 0f else 1f
        // FLAG_LAYOUT_IN_SCREEN is what makes the unfolded card actually
        // fullscreen. Without it the window's frame is the *content* area —
        // the display minus the status and navigation bars — so a MATCH_PARENT
        // card stopped 126 px short of the top on this phone and left the
        // status bar sitting on a band of the user's wallpaper. Measured, not
        // assumed: with the flag the window's top is the display's top and
        // [winYOrigin] measures 0 by itself.
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        // Untouchable while a typing field is up, even though the window no
        // longer overlaps it: the field's top edge is *derived* from the
        // keyboard's height, which changes as the IME animates and as 搜狗's
        // toolbar row comes and goes. Being a few pixels late would put this
        // window over the top row of the field and eat the taps aimed at it —
        // and the card has nothing to offer during a typing session anyway.
        p.flags = if (gesturePassthrough || typingTop > 0) {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            base
        }
        // Blur behind only while the card is actually a card. Folded, the
        // window is a 168 dp square around the ball and blurring behind *that*
        // is a smudge following the ball around; hidden for a capture, the
        // window is at alpha 0 but the compositor would still be asked to
        // defocus what is behind it — and what is behind it is exactly the
        // screenshot the brain is about to read. The 80 ms settle in
        // `blankedForCapture` covers this params change along with the alpha.
        // ...and not while the backdrop is up. The card used to be a window onto
        // the user's home screen, which is what the blur was for; the backdrop
        // now covers the whole window, so the compositor would be defocusing a
        // full screen of pixels nobody can see. Turning it off here is also what
        // makes the card look the same on a device where cross-window blur is
        // unavailable, which is most of them.
        val wantFrost = !compact && !captureHidden && !backdropUp()
        frosted = Glass.frost(context, wm, p, want = wantFrost)
        paintCard()
        // The card now runs under the status bar ([FLAG_LAYOUT_IN_SCREEN]), so
        // the control cluster has to step around it — otherwise the clock and
        // the battery icon sit on top of `◈` and `✕`, and those are the two
        // controls the user cannot do without.
        insetTopBar()
        runCatching { wm.updateViewLayout(v, p) }
    }

    /**
     * Push the toolbar below the status bar / cutout. No-op on every call but
     * the first two, since writing `layoutParams` asks for a layout pass and
     * this runs on every state change.
     *
     * **The inset has to come from the view, not from the display.**
     * `WindowManager.currentWindowMetrics` describes the *display*, so its top
     * inset is the status bar's height whether or not this window is underneath
     * it — and on this ROM it is not: measured, the window frame already starts
     * at the status bar's bottom edge, so adding the height again put the pill
     * a second status bar below where it belonged (126 px + 126 px + dp(10),
     * against the dp(10) the code reads as asking for). `rootWindowInsets` is
     * the same question asked relative to *this* window, so it answers 0 where
     * the system has already moved us clear and the full height where it has
     * not — which is what makes the same line correct on a ROM that honours
     * [FLAG_LAYOUT_IN_SCREEN] for overlays and one that doesn't.
     *
     * Main thread only.
     */
    private fun insetTopBar() {
        val bar = topBarView ?: return
        val lp = bar.layoutParams as? FrameLayout.LayoutParams ?: return
        val insets = bar.rootWindowInsets ?: return
        val want = dp(10) + insets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        ).top
        if (lp.topMargin == want) return
        lp.topMargin = want
        bar.layoutParams = lp
    }

    /**
     * The card's fill, which is a question only [applyWindow] can answer:
     * translucent when something is blurring behind it, nearly opaque when
     * nothing is. Folded there is no card at all — the window is the ball's
     * own square and anything painted in it is a box drawn around her.
     *
     * Main thread only.
     */
    private fun paintCard() {
        val v = root ?: return
        val bg = backdrop
        if (compact) {
            v.background = null
            clearScrim(bg)
            return
        }
        // Three answers, in order: with a backdrop the fill is a scrim over it
        // and the question [frosted] was answering ("is anything behind this
        // card?") no longer applies — the answer is our own image, so the same
        // film goes on regardless of what the compositor said. Without one the
        // card falls back to exactly what it did before.
        if (backdropUp()) {
            // **The scrim goes on the image's foreground, not the root's
            // background.** They look interchangeable and are not: the backdrop
            // is a *child* of the root, so a root background is behind the very
            // thing it is supposed to darken. The first version did that, and
            // the card came out as the raw photo at full brightness — measured
            // on the tablet at mean luma 0.40 where the design says 0.13, with
            // the pale ball barely legible on top of it.
            v.background = null
            val s = Backdrop.scrim()
            if (scrimShown != s) {
                bg!!.foreground = ColorDrawable(s)
                scrimShown = s
            }
            return
        }
        clearScrim(bg)
        v.setBackgroundColor(
            Color.parseColor(if (frosted) Glass.CARD_FROSTED else Glass.CARD_SOLID),
        )
    }

    /** Main thread only. */
    private fun clearScrim(bg: ImageView?) {
        if (scrimShown == null && bg?.foreground == null) return
        bg?.foreground = null
        scrimShown = null
    }

    /**
     * Whether the backdrop is both ready and showing. Not just "not folded":
     * the blur is done on a worker at build time, and a card that unfolds during
     * the first few hundred milliseconds of the process's life has to render as
     * it did before rather than as a card with a transparent hole in it.
     *
     * Main thread only.
     */
    private fun backdropUp(): Boolean =
        !compact && Backdrop.ready() && backdrop?.visibility == View.VISIBLE

    // ---- Build ----

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): BallHostFrame {
        val root = BallHostFrame(context)

        // ---- Backdrop (the bottom of the stack) ----
        //
        // First child, so everything else draws on top of it and nothing has to
        // know it is there. CENTER_CROP and not FIT_CENTER: the brief was 铺满,
        // and letterboxing a photo behind a fullscreen card would leave two
        // bands of whatever the card's fill is at the top and bottom.
        //
        // Sized against the **display**, not the window, and pinned to the top.
        // The card is not always fullscreen — a typing session ends it at the
        // field's top edge — and a MATCH_PARENT backdrop re-scales and re-crops
        // with it, so the wallpaper visibly jumped and brightened as the
        // keyboard came up: measured on one screen point, `10,34,60` fullscreen
        // → `65,85,106` while typing, and it is not the app behind (swapping the
        // app behind moves these pixels by 0.9/255). Fixed to the display the
        // backdrop is merely *clipped* by the shorter window, so the top of the
        // card is pixel-for-pixel the same in both states.
        //
        // The decode and the blur are kicked off here rather than lazily on the
        // first unfold. The window is built folded, so the work happens while
        // this view is still GONE and the answer is normally in hand long before
        // the user can long-press the ball.
        val bg = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        root.addView(
            bg,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                wm.currentWindowMetrics.bounds.height(),
                Gravity.TOP,
            ),
        )
        backdrop = bg
        Backdrop.warm(context) { bmp ->
            bg.setImageBitmap(bmp)
            // The fill and the blur flag are both this call's to decide, and the
            // answer just changed underneath them.
            applyWindow()
        }

        // ---- Ball area (top of the card) ----
        //
        // Stacked in a FrameLayout rather than a column with weights: the
        // areas' heights are driven from [BallHostFrame.onSizeChanged] against
        // the card's real height, which is the only place it is known, and the
        // ball has to keep its whole area when the card folds to the corner.
        val ballArea = FrameLayout(context)
        val b = EmotionBallTextureView(context).apply {
            // No demo cycling from birth. It used to read "cycle moods until
            // real state driving takes over" — but real driving only begins
            // with the first setState, and a device idling at a desk never
            // sends one. The ball then cycled all fourteen moods every 5.5 s
            // forever: wearing SPEAKING (mouth sine and all) with no sound,
            // and resetting the CALM→SLEEP accumulator so it could never
            // doze either. CALM says "I am here, not busy" all by itself.
            // Scaled rather than sized down: the sphere's diameter is a fixed
            // fraction of the surface's shorter side, so a fixed dp size would
            // need a matching floor on the window's own minimum or the ball
            // gets cropped when the card is resized small. A view scale tracks
            // whatever geometry the card ends up with. Pivot defaults to the
            // view's center, which is where the sphere already is.
            scaleX = BALL_SCALE
            scaleY = BALL_SCALE
        }
        ball = b
        ballArea.addView(
            b,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        root.addView(
            ballArea,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.ballAreaView = ballArea

        // ---- Scrollback, straight under the ball ----
        val history = HistoryListView(context).apply {
            onPageClick = { listeners.onHistoryPageClick(it) }
            onTextOpen = { listeners.onTextOpen(it) }
        }
        historyView = history
        root.addView(
            history,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM,
            ),
        )
        ChatHistory.setListener { ui.post { historyView?.refresh() } }
        history.refresh()

        // ---- Bottom tips ----
        //
        // The ball's whole vocabulary, where the user's eye already is at the
        // end of a conversation. Pinned rather than appended to the scrollback:
        // it was the list's last row before, which meant it scrolled away —
        // and the one time it is needed is the one time the user has scrolled
        // up to read what happened.
        //
        // Only in the full card. Folded, the window is a 168 dp square and the
        // tips have nowhere to be; the gestures they describe work there anyway.
        val tips = TextView(context).apply {
            text = AppLocale.str(context, R.string.window_tips)
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(Glass.MUTED))
            gravity = Gravity.CENTER
            // The bottom padding clears the gesture bar, the same 30 dp the
            // scrollback's last row used to reserve for itself — this bar
            // inherits that edge of the card.
            setPadding(dp(14), dp(6), dp(14), dp(30))
            visibility = View.GONE
        }
        tipsView = tips
        root.addView(
            tips,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        // ---- Top-right controls ----
        //
        // Added last so it is on top of both.
        val topBar = buildTopBar()
        root.addView(
            topBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply {
                topMargin = dp(10); rightMargin = dp(10)
            },
        )
        topBarView = topBar
        // The cluster's own bounds are an input to the card's split — see
        // [ballAreaHeight]. Watching it here means the status-bar inset lands
        // without this having to know it is responsible for the split.
        topBar.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            // Also the only place the inset is guaranteed to be *available*:
            // `rootWindowInsets` is null until the view is attached, so the
            // first `applyWindow` can run before there is an answer. A laid-out
            // view is an attached one. Self-guarded, so the extra layout pass
            // this asks for on the first call no-ops on the second.
            insetTopBar()
            if (top != oldTop || bottom != oldBottom) refitCardAreas()
        }

        return root
    }

    /**
     * The control cluster: one pill holding the four things the user actually
     * reaches for.
     *
     * Three and not four since typing moved onto the ball's double-tap — see
     * [Listeners.onTextInputToggle] for why it belongs there rather than here.
     * And the same three since the stop button left: `✓` took its place rather
     * than being added beside it, because stop was the one key in this bar the
     * ball can already perform — see [Listeners.onStopClick]. So the count has
     * not changed twice over; only one of the three is different, and it is the
     * one that was not earning its slot.
     *
     * The bar now reads `◈ ✓ ⚙ ✕`. `◈` used to be `⋯`, and behind it were ten
     * developer instruments — swipe four ways, back, home, dump the tree, grab a
     * screenshot — as a second row of monochrome glyphs. That row is gone
     * outright, not folded somewhere else: every one of those actions is one the
     * brain performs on its own (`screen.*`, all of it reachable by talking),
     * and a hand-driven toolbar sitting over the app being driven was a second,
     * competing way to operate the same device. What took the slot is 产物 — the
     * pages the assistant has made, which is the one thing in this bar the user
     * cannot get at by talking.
     *
     * All four keys are `Glass.LABEL` now rather than roughly half of them being
     * dimmed: the dimmer ones were the drawer's contents and a folded `⋯`.
     */
    @SuppressLint("SetTextI18n")
    private fun buildTopBar(): View {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(4), dp(4), dp(4), dp(4))
            // A recessed well rather than a panel: this pill floats over a card
            // that is itself translucent, so its base has to be darker than
            // what it sits on or the glyphs read against the user's app.
            background = Glass.well(context, dp(24))
        }

        val keys = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        keys.addView(
            barButton(R.drawable.ic_artifacts, Glass.MUTED, "产物") { listeners.onArtifactsClick() }
        )
        // ✓ and not ■: the stop button used to sit here, and stop is the one
        // action in this bar the ball can already perform — see onStopClick.
        keys.addView(barButton(R.drawable.ic_check, Glass.MUTED, "自检") { listeners.onSelfCheckClick() })
        // No ⌨ here. Typing moved onto the ball's double-tap, and the bar is
        // the wrong place for it twice over: it only exists while the card is
        // unfolded (so the way to type vanished with the fold — the same fault
        // that made "点右上角的 ⌨" a lie in a failure message), and it put a
        // second meaning on the one control the user already had to learn.
        keys.addView(barButton(R.drawable.ic_settings, Glass.MUTED, "设置") { listeners.onSettingsClick() })
        // Reads as close, not as resize: the card is a thing you dismiss, and the
        // corner ball it folds into stays on screen either way.
        keys.addView(barButton(R.drawable.ic_close, Glass.MUTED, "收起") { listeners.onMinimizeClick() })

        bar.addView(keys)
        return bar
    }

    /**
     * One of the four permanent keys: a vector icon, tinted, centred in a 36 dp
     * square with 6 dp of air on each side.
     *
     * These were Unicode glyphs (`⋯ ✓ ⚙ ✕`) drawn by a custom View that measured
     * each character's ink to centre it. That worked around one problem — every
     * glyph sits at a different height in its font's line box — and could not
     * work around the other: the weight, size and shape of `⚙` is whatever the
     * ROM's font says it is, so the bar looked different on every tablet and
     * never quite matched itself. Vectors are drawn the same everywhere.
     *
     * `mutate()` before `setTint`, because drawables from one resource share
     * constant state and this bar is rebuilt with the card.
     *
     * A bare View draws an image without telling anyone it is there, so the
     * description is not optional: without it the whole control bar drops out
     * of `get_screen_element` and out of TalkBack alike. Deliberately not
     * localised — rebuilding the bar on a language change for four content
     * descriptions nobody reads is the wrong trade.
     */
    private fun barButton(
        @DrawableRes icon: Int,
        color: String,
        description: String,
        onClick: () -> Unit,
    ): View {
        // The 36 dp target stays; only the glyph shrinks, to 20 dp.
        val pad = dp(8)
        return ImageView(context).apply {
            setImageDrawable(
                ContextCompat.getDrawable(context, icon)?.mutate()?.apply {
                    setTint(Color.parseColor(color))
                }
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
            contentDescription = description
            isClickable = true
            isFocusable = true
            Glass.pressable(this)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                leftMargin = dp(2)
            }
        }
    }

    // ---- Tap / drag / long-press on the ball ----

    private inner class BallHostFrame(ctx: Context) : FrameLayout(ctx) {
        /** Set by [build]; used to constrain touch hit-testing to the ball area. */
        var ballAreaView: View? = null

        /**
         * The card is two stacked areas — ball on top, scrollback under it —
         * and the split is only computable here, where the card's real height
         * exists. Driving it from the fold transitions instead would read
         * `params.height` while it is still MATCH_PARENT or still the corner
         * square; this fires after every geometry change, including the unfold
         * and a rotation, and needs no special case for either.
         */
        override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
            super.onSizeChanged(w, h, oldW, oldH)
            if (compact || h <= 0) return
            // Posted, not immediate: this runs inside the layout pass, and a
            // child's requestLayout from there is swallowed — the ball area
            // stayed MATCH_PARENT and the ball sat in the middle of the card
            // instead of the middle of its own area.
            // Posted, not immediate: this runs inside the layout pass, and a
            // child's requestLayout from there is swallowed — the ball area
            // stayed MATCH_PARENT and the ball sat in the middle of the card
            // instead of the middle of its own area.
            post { if (!compact) refitCardAreas() }
        }

        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

        /**
         * How long to wait for another tap before acting on the ones so far.
         *
         * The platform's own double-tap timeout, so a double-tap here takes
         * exactly as long to register as one anywhere else on the device.
         */
        private val multiTapMs = ViewConfiguration.getDoubleTapTimeout().toLong()

        /** Taps of the current run, reset by [tapRunnable] when the window closes. */
        private var tapCount = 0

        /**
         * One tap of a possible run of three.
         *
         * The three gestures share one control, so a tap can no longer act on
         * its own — it has to wait out the multi-tap window to find out whether
         * it was a single one. That is the entire cost of this design: talking
         * now starts ~300 ms after the tap instead of on it. What it buys is the
         * other two, which were previously unreachable from the folded ball.
         *
         * Three is terminal and fires **immediately**. Stop is the urgent one —
         * the device may be tapping its way through somebody else's app — and a
         * fourth tap means nothing, so there is no window left to wait out.
         */
        private fun registerTap() {
            tapCount++
            ui.removeCallbacks(tapRunnable)
            if (tapCount >= TAPS_FOR_STOP) {
                tapCount = 0
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listeners.onStopClick()
                return
            }
            // Felt on the touch, not ~300 ms later when the run resolves: the
            // multi-tap wait is otherwise a tap that seemed not to land.
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            ui.postDelayed(tapRunnable, multiTapMs)
        }

        /** Whatever the run turned out to be. Runs on the UI thread. */
        private val tapRunnable = Runnable {
            when (tapCount) {
                1 -> listeners.onTalkClick()
                2 -> listeners.onTextInputToggle()
            }
            tapCount = 0
        }
        private var startX = 0f
        private var startY = 0f
        private var startParamsX = 0
        private var startParamsY = 0
        private var tracking = false
        private var moved = false
        private var longPressed = false

        /**
         * This drag is a sideways flick across the ball, i.e. a look change —
         * decided once, at the moment the slop is crossed, and never revisited.
         *
         * Deciding once is the point. A gesture that re-chose its meaning every
         * frame would drag the window a little before committing to a swipe,
         * which is a card that jumps and then snaps back for every look change.
         * The 1.4 bias is what keeps a hand that is trying to *move* the card
         * from being read as a swipe: a drag has to be clearly sideways, not
         * merely sideways-ish.
         *
         * Full card only. The folded ball is dragged around the screen and
         * magnets to the ledge, so a horizontal flick there is a move, and
         * taking it away would cost the user the one thing they do with the
         * corner ball most. In the other direction, the full card's ball
         * never drags the window either — see the move branch.
         */
        private var swiping = false
        private val longPressRunnable = Runnable {
            if (tracking && !moved) {
                longPressed = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                listeners.onLongPress()
            }
        }

        private fun isOnBall(x: Float, y: Float, ballH: Int): Boolean {
            val cx = width / 2f
            // The ball area may sit at the bottom of a taller window (a
            // signboard above it), so its centre is not `ballH / 2` from the
            // window's top — read it from the area's real position.
            val cy = (ballAreaView?.top ?: 0) + ballH / 2f + (ball?.translationY ?: 0f)
            // Matches the visible sphere: camera z=4.9 + fov=42 makes the ball
            // fill ~55% of the shorter dimension, times whatever view scale the
            // ball is drawn at. Add a small margin so grazing taps still count.
            // Compact mode differs only in that scale (1f, no subtitle to make
            // room for) — it used to claim the whole window because a disc was
            // painted there, and without the disc that would be an invisible
            // square in the corner eating the user's taps.
            val scale = if (compact) 1f else BALL_SCALE
            val r = min(width, ballH) * 0.32f * scale
            val dx = x - cx
            val dy = y - cy
            return dx * dx + dy * dy <= r * r
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val p = params ?: return false
            val ballH = ballAreaView?.height ?: return false
            val areaTop = ballAreaView?.top ?: 0
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    // A signboard is hosted: its own buttons handle taps in the
                    // pane; the ball stays draggable by its circle, wherever the
                    // taller window put it.
                    if (signboard != null) {
                        if (e.y < areaTop) return false   // the pane's own touch
                        if (!isOnBall(e.x, e.y, ballH)) return false
                    } else if (compact) {
                        // Bare folded ball: circle test only, the whole window
                        // is the area.
                        if (!isOnBall(e.x, e.y, ballH)) return false
                    } else {
                        // Full card: ignore anything below the ball area (the
                        // scrollback handles its own touches); then the circle.
                        if (e.y > ballH) return false
                        if (!isOnBall(e.x, e.y, ballH)) return false
                    }
                    // Don't let it bounce under a finger that is already on it.
                    ui.removeCallbacks(strollRunnable)
                    slide?.cancel()
                    startX = e.rawX; startY = e.rawY
                    startParamsX = p.x; startParamsY = p.y
                    tracking = true
                    moved = false
                    swiping = false
                    longPressed = false
                    ui.postDelayed(longPressRunnable, longPressMs)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return false
                    if (!moved) {
                        val dx = abs(e.rawX - startX)
                        val dy = abs(e.rawY - startY)
                        if (dx > slop || dy > slop) {
                            moved = true
                            swiping = !compact && dx > dy * SWIPE_BIAS
                            ui.removeCallbacks(longPressRunnable)
                        }
                    }
                    // Compact only. The full card is a surface, not a widget:
                    // it has no ledge to grip and no corner to sit in, and a
                    // diagonal drag on its ball used to carry the whole
                    // window off-screen — no clamp while it went, no magnet
                    // to bring it back.
                    if (moved && !swiping && compact) {
                        p.x = startParamsX + (e.rawX - startX).toInt()
                        // Y follows the finger even while gripping. The ledge
                        // used to own Y outright, which kept the paws on their
                        // line but also meant a ball that had clung — because a
                        // task started, or because the user put it there — could
                        // never be pulled back out.
                        p.y = startParamsY + (e.rawY - startY).toInt()
                        clampCompact(p)
                        // Grab and let go on the way, not on release: the
                        // paws are drawn on the ledge, so a ball being
                        // lifted off it has to open its hands while the
                        // finger is still moving, or it reads as gripping
                        // thin air. Pose only — the geometry is the
                        // finger's until ACTION_UP.
                        val want = wantsPerch(p.y)
                        if (want != perchApplied) {
                            perchApplied = want
                            // The hand that moved it is the authority on
                            // where it sits, so this is also where the
                            // user's own claim on the ledge is taken and
                            // dropped — including over a running task's,
                            // and over a page the brain is showing.
                            perchedByUser = want
                            if (!want) {
                                perchedByTask = false
                                perchedByPage = false
                            }
                            syncWorking()
                        }
                        if (!perchApplied) {
                            homeX = p.x
                            homeY = p.y
                        }
                        runCatching { wm.updateViewLayout(this, p) }
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!tracking) return false
                    ui.removeCallbacks(longPressRunnable)
                    val wasTap = !moved && !longPressed
                    // Only a compact drag moved the window, so only it has
                    // anything to settle — a swipe switched looks, and a
                    // full-card touch never moved the window at all.
                    val wasDrag = moved && !swiping && compact
                    val flick = if (swiping) e.rawX - startX else 0f
                    tracking = false
                    moved = false
                    swiping = false
                    if (wasDrag) settleAfterDrag()
                    retimeStroll()
                    if (wasTap) registerTap()
                    // Right means forward, the way a stack of cards is dealt.
                    if (abs(flick) > slop * SWIPE_COMMIT) {
                        stepBallLook(if (flick > 0) 1 else -1)
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (!tracking) return false
                    ui.removeCallbacks(longPressRunnable)
                    val wasDrag = moved && !swiping && compact
                    tracking = false
                    moved = false
                    swiping = false
                    if (wasDrag) settleAfterDrag()
                    retimeStroll()
                    return true
                }
            }
            return false
        }
    }

    // ---- Colors / units ----

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    /** The window's own height, or the ball's side when it is not laid out. */
    private fun windowH(): Int {
        val v = root ?: return dp(COMPACT_DP)
        return if (v.height > 0) v.height else dp(COMPACT_DP)
    }

    companion object {
        /**
         * Taps in a row that mean "stop". The ball's whole vocabulary, in one
         * place: one tap asks, two type, three stop. Long-press is not part of
         * it — that one predates the other two and is about the card's size
         * rather than about the assistant.
         */
        private const val TAPS_FOR_STOP = 3

        /**
         * How much of its area the ball is drawn at in the full card.
         *
         * Was 0.5f, which — after a fixed 200dp band and a 1 : 1.15 split with
         * the list had taken their cut — drew a ball *smaller* than the folded
         * corner one. Neither the band nor the split exists any more.
         *
         * Then 1.4f against a half-screen area, which overshot the other way:
         * `min(area) × 0.48 × scale` came to ~800px on a 1200px-wide screen, two
         * thirds of the width, and a face that size reads as a cartoon rather
         * than as a presence.
         *
         * Read together with [BALL_AREA_FRACTION], which is the other half of
         * the same number: the two were cut back together so the ball would
         * shrink without leaving a band of dead space where it used to be. As
         * set they draw ~510px, about twice the folded corner ball.
         */
        private const val BALL_SCALE = 1.05f

        /**
         * How much more sideways than vertical a drag on the ball must be
         * before it counts as a look-change swipe rather than a drag of the
         * card. See [BallHostFrame.swiping].
         */
        private const val SWIPE_BIAS = 1.4f

        /**
         * Distance, in slops, a swipe must actually cover to change anything.
         *
         * Three rather than one: crossing the slop only means the gesture
         * stopped being a tap, and a look change is not something to do to
         * somebody by accident. Short of this the swipe is simply dropped —
         * nothing moved, so there is nothing to undo.
         */
        private const val SWIPE_COMMIT = 3f

        /**
         * Where the card splits: ball above, scrollback below. Also the touch
         * boundary between them (see [BallHostFrame.onSizeChanged]) — the list
         * is a ScrollView and needs every touch that lands in its half.
         *
         * This is the answer, not a starting point: the sphere's clearance from
         * the control cluster in the top-right corner is bought by dropping the
         * ball *inside* this band, never by moving the band's bottom edge — that
         * edge is where the scrollback begins, and moving it is a change to the
         * card, not to the ball. See [ballDrop].
         *
         * Note the area's *height* is what sizes the ball once it drops below
         * the screen width, since the sphere is a fraction of the shorter side.
         * Below about 0.40 the ball starts shrinking faster than the room it
         * gains the list is worth.
         */
        private const val BALL_AREA_FRACTION = 0.40f

        /**
         * Clearance between the sphere's top edge and the bottom of the control
         * cluster.
         *
         * Twelve dp is about the cluster's own inner padding, so the ball clears
         * the pill by the same margin the pill's glyphs clear the pill's edge —
         * enough to read as two things, not as one collision.
         */
        private const val BALL_TOP_GAP_DP = 12

        /**
         * How far below the band's centre the ball rests, before the cluster
         * clearance is added on top.
         *
         * Taste, not geometry, and the distinction is worth keeping: [ballDrop]
         * answers "how far must it move to clear the controls", this answers
         * "where does it look right". Centred is where the arithmetic says it
         * goes and it reads slightly high, because the band starts under the
         * status bar — the empty space below the sphere is taller than the empty
         * space above it, so the eye puts the sphere above the middle. The user
         * asked for "a little lower" and this is that; it moves the ball and
         * nothing else (the band, the split, the sphere's size and the
         * scrollback are all untouched).
         *
         * On the phone this stacks with a ~12 dp cluster clearance, on a wide
         * tablet it is the whole offset.
         */
        private const val BALL_NUDGE_DP = 26

        /**
         * Floor for the card's height while a typing field is up — see
         * [setTypingTop].
         *
         * Reached only on a device whose keyboard eats most of the screen: the
         * card is meant to keep its ball and some of its scrollback visible
         * above the field, and a card clipped to nothing would be the "it
         * vanished" behaviour this replaced.
         */
        private const val MIN_TYPING_CARD_DP = 240

        /**
         * Compact window side and its inset from the screen corner. The sphere
         * is a fixed fraction of the surface's shorter side, so this is the
         * only knob for how big the corner ball reads — the rendered ball is
         * roughly two thirds of it, and the circular hit test tracks it too.
         */
        private const val COMPACT_DP = 168
        private const val COMPACT_MARGIN_DP = 16

        /**
         * How much of the ball area's padding a held signboard's tail reaches
         * into, as a fraction of the compact side. The sphere occupies ~64% of
         * the area, centred, so the padding above the head is ~18% of the side.
         *
         * Calibrated on device, not by eye: 0.30 measured a 9px overlap with
         * the head ("戳到脑袋"), 0.14 read as detached. Each 0.01 of the
         * fraction is ~3.8px on the test phone, so 0.235 targets a ~14px
         * clearance — the tail tip visibly *near* the head without touching,
         * which is what "举着" reads as.
         */
        private const val SIGN_OVERLAP_FRACTION = 0.235f

        private const val PERCH_SLIDE_MS = 240L

        /** The last-few-pixels pull onto the ledge when a drag lets go. */
        private const val SNAP_MS = 150L

        /**
         * How far short of the ledge still counts as touching it, dp. See
         * [wantsPerch] — the only tuned number in the magnet.
         */
        private const val SNAP_GRACE_DP = 18

        /** Gap between hops within one burst. */
        private const val STROLL_HOP_MS = 640L

        /** Standing still between bursts, plus up to [STROLL_REST_JITTER_MS]. */
        private const val STROLL_REST_MS = 6_000L
        private const val STROLL_REST_JITTER_MS = 14_000
    }
}
