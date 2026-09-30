package net.kuafuai.andee.ui

import android.os.Build
import android.view.View
import java.util.concurrent.atomic.AtomicInteger

/**
 * Take an overlay's whole view tree out of the accessibility node tree.
 *
 * ## The bug this exists for
 *
 * Every window this app adds — the card, the settings sheet, the tap marker,
 * the edge glow — is a real window with real `View`s, and by default every one
 * of them is exposed to accessibility exactly like an app's own UI. We are an
 * `AccessibilityService`, so we read our own furniture back:
 *
 * - [net.kuafuai.andee.screen.ScreenController.tryOtherWindows] sweeps every
 *   window looking for one with content. It runs *precisely* when the real
 *   pane has none — a mini-program, a WebView, a game canvas — and on those
 *   screens the only tree with `useful > 0` on the whole display is our own
 *   toolbar. It wins, and the brain is handed an element list describing our
 *   buttons instead of the app it is supposed to be driving.
 * - `rootInActiveWindow` is us whenever a focusable overlay is up, which makes
 *   `typeText`'s `findFocus(FOCUS_INPUT)` return the settings sheet's own
 *   `EditText` — the brain's next sentence gets typed into the ASR endpoint
 *   field.
 *
 * Note what is *not* broken: the brain cannot actually touch the ball.
 * `CommandDispatcher.passthroughForGesture` drops every overlay to
 * non-touchable before dispatching, so an injected tap always reaches the app
 * underneath. The damage is upstream of the touch — the brain aims at a
 * control that is ours, and the tap lands on whatever real content happens to
 * sit under it. Which is why the fix belongs here, at the point where the
 * elements become visible to it, and *not* in a geometric "refuse taps inside
 * the ball's rectangle" rule: app content under the ball is legitimately
 * tappable and passthrough makes tapping it work, so such a rule would reject
 * correct taps.
 *
 * `NO_HIDE_DESCENDANTS` rather than `NO`: plain `NO` hides only this view and
 * reports its children in its place, which for a root view removes nothing at
 * all.
 *
 * ## Why that flag alone does not work *here*
 *
 * `accessibility_service_config.xml` asks for `flagIncludeNotImportantViews`,
 * and that flag makes `View.includeForAccessibility()` return true regardless
 * of `importantForAccessibility`. So `NO_HIDE_DESCENDANTS` hides our overlays
 * from every service *except the one that needed it* — precisely backwards.
 * Measured: with the vault form open, `get_ui_tree` returned the form,
 * labels, phone number and all.
 *
 * `accessibilityDataSensitive` is the flag that survives it. It hides the
 * subtree from services that do not declare `android:isAccessibilityTool`,
 * which we do not and TalkBack does — so it is also strictly *better* than the
 * blunt instrument above: the screen reader keeps the ball, we lose it. It
 * inherits down the tree, so one call on the root still covers the window.
 * API 34+, hence the guard; below that we are back to the old
 * `tryOtherWindows`/`treeEmpty` guards in `ScreenController`, which is what
 * shipped before and is merely imperfect rather than broken.
 *
 * ## The cost, stated plainly
 *
 * On a pre-34 device this still hides the overlay from *every* accessibility
 * service — a screen reader can no longer reach the ball or the settings
 * sheet. That is a real accessibility regression, accepted because this is a
 * single-purpose appliance whose own UI is a ball and six buttons, and because
 * the alternative is the brain misreading the screen on exactly the panes
 * where it is already weakest.
 *
 * Call once on the root view, before or after `addView` — the flags are read
 * at node-construction time, not at attach.
 */
fun View.hideFromAccessibility() {
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }
}

/**
 * Whether one of the body's own **focusable** cards — [SettingsUi], [VaultUi] —
 * is currently in front.
 *
 * Hiding those from accessibility is correct, but it leaves the dump looking
 * exactly like a dead accessibility connection: our card is the active window
 * and it now reports zero nodes, which is the same signature
 * `ScreenController.reviveNote` reads as "the service went stale, have the user
 * toggle Andee off and on". That message is aimed at a human, so it has to be
 * true — same rule as the meeting recorder's `stopped_by`. The cards therefore
 * announce themselves and the dump says the real reason instead.
 *
 * Counted rather than boolean because the vault opens on top of settings.
 * `show()`/`hide()` in both classes are already idempotent, so the pairs balance.
 */
object OwnCard {
    private val depth = AtomicInteger(0)

    val showing: Boolean get() = depth.get() > 0

    fun shown() {
        depth.incrementAndGet()
    }

    fun hidden() {
        depth.updateAndGet { if (it > 0) it - 1 else 0 }
    }
}

/**
 * Whether the assistant's own card is unfolded over the whole display.
 *
 * A second way to get zero nodes, by a different mechanism than [OwnCard] and
 * with a different answer. The card is `FLAG_NOT_FOCUSABLE`, so it never
 * becomes the active window and the app behind it stays focused and keeps its
 * root — but it *covers* the display, and every node of an occluded window
 * reports `isVisibleToUser == false`. [ScreenController.buildNode] drops those
 * before it counts them, so `nodes_total` is 0 with a perfectly healthy tree
 * underneath. Measured on this device: with the card unfolded, Chrome's window
 * is present, `active`, `focused`, `root: true` and yields 0 nodes; folding the
 * card returns 75 from the same screen.
 *
 * It exists because that is the same signature [ScreenController.reviveNote]
 * reads as a stale accessibility connection, and it sent a user off to toggle a
 * permission that was working fine — the dump has to be able to say the real
 * reason. Normally unreachable now that [net.kuafuai.andee.net.CommandDispatcher]
 * waits for the fold to land, so a dump that still sees this is either a passive
 * observer (`driving = false`) or the fold failing, and both want naming rather
 * than a guess about accessibility.
 *
 * Set by [FloatingWindowUi] alone, from every place that changes whether the
 * card is on screen at full size.
 */
object FullscreenCard {
    @Volatile
    var covering: Boolean = false
}
