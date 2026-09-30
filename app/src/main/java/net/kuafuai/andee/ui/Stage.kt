package net.kuafuai.andee.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import net.kuafuai.andee.R

/**
 * The one full-screen face this app puts in front of the user.
 *
 * Three surfaces open fullscreen — a page the model composed (`HtmlActivity`),
 * a message read in full (`TextPage`, same activity), and the two camera
 * screens (`LookActivity`, `ScanActivity`) — and until now each of them
 * arrived and left on its own terms. The user could see the difference: the
 * camera screens cut, a page faded, and closing any of them flashed once
 * before settling.
 *
 * ### The flash, and the two separate bugs behind it
 *
 * Reported as one symptom ("关闭的时候页面闪烁一下") and it was two:
 *
 *  **1. Nothing handed the screen back.** Closing the page ran `finish()` and
 *  nothing else; the card and the ball were only restored from `onDestroy`,
 *  by which time the window was already gone and the app underneath was
 *  visible — so the user watched a finished page vanish and *then* watched
 *  the assistant assemble itself. [onStageLeaving] fixes that half by firing
 *  the hand-back at the *start* of the exit, so the card and the ball are
 *  already moving back underneath while this window shrinks over them.
 *
 *  **2. The exit animated the wrong thing.** `stage`'s own alpha was taken to
 *  zero, which is where the black came from: a View tree going transparent
 *  reveals this Activity's `windowBackground`, not the app behind it. The
 *  measured frame trace was ~130 ms of fading page, then **~250 ms of solid
 *  black**, then the app behind. No View animation can fix that — the window
 *  background is not a View. So the exit moved to the window, via
 *  `R.anim.stage_close_exit` in [reallyFinish]; see that file for the trace.
 *
 * ### The enter is the same shape, for the same reason
 *
 * [revealStage] grows the stage from [SCALE_IN] rather than fading it up from
 * zero, so opening reads as *this thing comes toward you* instead of *the
 * screen changes*. Subclasses call it when their content is actually
 * drawable — a page has finished loading, a lens has bound — and the backstop
 * in [onCreate] covers the ones that never report.
 *
 * The enter stays a View animation, and that asymmetry is deliberate: on the
 * way in there is nothing behind the window worth revealing (it is the top
 * surface, and `Theme.Body.Stage` painted the same black), so the cheap
 * animation is also the correct one.
 */
abstract class StageActivity : AppCompatActivity() {

    /**
     * The whole window's content, and the thing that grows on the way in.
     *
     * Subclasses add their views here rather than calling `setContentView`, so
     * the entering animation moves *everything* at once — a view parented
     * outside the stage would sit at full size while the rest of the screen
     * was still growing into place.
     */
    protected val stage: FrameLayout by lazy { FrameLayout(this) }

    private val main = Handler(Looper.getMainLooper())

    private var revealed = false

    /**
     * Set by [leave] so a second ✕ tap cannot start a second exit.
     *
     * A double tap on the pill is easy — it is bottom-of-the-screen chrome and
     * the exit takes a couple of hundred milliseconds. Without this, the
     * second tap runs `onStageLeaving` again, and the caller's hand-back is not
     * idempotent in the way that matters: it unperches the ball and unfolds
     * the card twice, so the ball ends up halfway to the ledge on an animation
     * that was restarted under it.
     */
    private var leaving = false

    /**
     * Called once, the instant [leave] starts, while this window is still on
     * screen. Put the overlay back from here.
     *
     * This is a different moment from `onDestroy` and it is not a stylistic
     * choice: the caller's job here is to restore the card and unperch the
     * ball, both of which are *animations of their own* (see
     * `FloatingWindowUi.slideTo` and `exitCompact`). Kicked off at
     * `onDestroy` they run against a screen that is already showing the app
     * underneath — so the user watches a finished page vanish, then watches
     * the assistant assemble itself from nothing. Kicked off here they run
     * underneath the shrinking window and are simply revealed by it.
     */
    protected open fun onStageLeaving() {}

    /**
     * Called once, just before [revealStage] grows the stage, for whatever the
     * subclass has to put away *inside* that growth.
     *
     * A spinner is the reason this hook exists. It is the one piece of chrome
     * a subclass adds that is wrong the moment its content is ready, and
     * fading it on its own clock means the user sees it pop out of an
     * already-full-size stage. It has to be part of the same gesture.
     */
    protected open fun onStageRevealing() {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stage.setBackgroundColor(BACKDROP)
        stage.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        stage.scaleX = SCALE_IN
        stage.scaleY = SCALE_IN
        stage.alpha = 0f
        setContentView(stage)
        // Not for the fast case — for the ones that never report. A page whose
        // top-level script throws leaves `onPageFinished` pending forever, and
        // a lens on a device where the bind is refused never draws a frame;
        // either way a half-drawn stage beats a black screen.
        main.postDelayed({ revealStage() }, REVEAL_BACKSTOP_MS)
    }

    /**
     * Content is drawable: grow the stage into place. Idempotent, and safe to
     * call from any thread that can post to the main one.
     */
    fun revealStage() {
        main.post {
            if (revealed || leaving) return@post
            revealed = true
            onStageRevealing()
            stage.animate().cancel()
            stage.animate()
                .scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(ENTER_MS)
                .setInterpolator(Glass.EASE)
                .start()
        }
    }

    /**
     * Shrink away, then close. The only way any of these screens may leave.
     *
     * **This hands the exit to the window, not to the View tree**, and that is
     * the whole fix for the flash the user reported. Animating `stage`'s own
     * alpha looks identical right up until it reaches zero — at which point
     * what is showing is not the app underneath but this Activity's own
     * `windowBackground` (black, see `Theme.Body.Stage`). Measured frame by
     * frame on the tablet: ~130 ms of the page fading, then **~250 ms of solid
     * black**, then the app behind fades in. A View animation cannot reach the
     * window background because it is not a View; only a window-level
     * transition can take the content and that black plate away together, and
     * it is what lets what is behind show through *during* the animation
     * instead of after it. See `anim/stage_close_exit.xml`.
     *
     * The hand-back ([onStageLeaving]) still fires first and synchronously, so
     * the card and the ball are already on their way back underneath while
     * this window shrinks over them.
     *
     * A back press routes through here too — see [onBackPressed].
     */
    fun leave() {
        main.post {
            if (leaving) return@post
            leaving = true
            // Hand the screen back *now*, not after the animation — see
            // onStageLeaving. This is also what makes the shrinking window
            // reveal something real.
            onStageLeaving()
            reallyFinish()
        }
    }

    /**
     * Close for real, with the window's own exit animation.
     *
     * `overridePendingTransition`'s second argument is the animation for *this*
     * window leaving, which after `finish()` is exactly what is about to
     * happen. It beats `Theme.Body.Stage`'s `windowAnimationStyle="@null"` —
     * that setting is there to stop the platform substituting its own exit
     * (which, with our result-blocking tools, used to fade an already-empty
     * window), and an explicit override is the documented way to say "no, this
     * one, now".
     *
     * Passed even though `finish()` is already called: the two are separate,
     * and the transition has to be named for the frame the window actually
     * leaves.
     */
    private fun reallyFinish() {
        if (isFinishing || isDestroyed) return
        finish()
        runCatching {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, R.anim.stage_close_exit)
        }
            .onFailure { android.util.Log.w(TAG, "exit transition refused", it) }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        // Back is the user saying the same thing as ✕ 完成. It has to get the
        // same exit, and on these screens there is nowhere behind them anyway
        // — the card is folded, so "back" would otherwise drop the user on a
        // home screen with only a ball in the corner.
        leave()
    }

    companion object {
        private const val TAG = "Body"

        /**
         * Pure black, the same colour `Theme.Body.Stage` paints the window
         * with. A gap of one black frame at either end is invisible; a gap of
         * one white frame is the flash.
         */
        const val BACKDROP = android.graphics.Color.BLACK

        /** How far away the stage starts. Enough to read as depth, not a zoom. */
        const val SCALE_IN = 0.94f

        const val ENTER_MS = 240L

        /** See the class KDoc: the ones that never report. */
        const val REVEAL_BACKSTOP_MS = 2500L
    }
}
