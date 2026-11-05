package net.kuafuai.andee.desktop.pet

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import net.kuafuai.andee.desktop.mac.Bridge
import kotlin.math.roundToInt

/**
 * The pet: **Andee's body on the Mac** when it is on (2026-11-05) — the floating ball is not shown then, and
 * everything that stood beside the ball (the caption, the prompt card, the bubble) stands beside her, through
 * [onWindow]. She takes the ball's whole vocabulary ([actions]: one tap talks, two type, three stop, a long press
 * opens the window) and keeps her own drag and throw. What she shows is what Andee is doing ([assistantPose]);
 * her own little life runs only while it is idle.
 *
 * The window is her bounding box, not the screen: that is what keeps it cheap (a 250×350 transparent
 * window instead of 1728×1117, measured ~18% against ~38% for a full canvas). Andee's words are the caption
 * window's, placed above her by `BallCaption.place`, so this window holds no bubble of its own any more.
 * The window does move with her: one mover, one move per frame.
 */
@Composable
fun ApplicationScope.PetWindow(
    cfg: PetConfig,
    actions: net.kuafuai.andee.desktop.BallActions,
    /** A question or an approval card is up and waiting for the user. */
    holding: Boolean,
    onWindow: (java.awt.Window) -> Unit,
) {
    val aspect = remember(cfg.look) { PetSprites.aspectOf(cfg.look) }
    val widthDp = cfg.height * aspect
    val (space, _) = remember(widthDp, cfg.height) { deskAreas(widthDp / 2f) }
    val runtime = rememberPetRuntime(cfg, space, widthDp)
    runtime.assistant = assistantPose(holding)

    val winW = widthDp
    val winH = cfg.height.toFloat()
    val state = rememberWindowState(size = DpSize(winW.dp, winH.dp), position = WindowPosition(0.dp, 0.dp))
    // The size follows the settings (大小, the look): remembered once, it kept the first size forever and the
    // setting did nothing. Since the window is her box exactly, a stale size also clipped her.
    state.size = DpSize(winW.dp, winH.dp)

    Window(
        onCloseRequest = {},
        state = state,
        title = "Andee Pet",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
    ) {
        val w = window
        onWindow(w)
        // The window is her bounding box, and a box has empty corners: without this
        // a click beside her head would be swallowed by our window and lost to the app behind.
        // Clicks are handled from the global pointer state, so the window itself has nothing to
        // receive — it only needs to stop ignoring the mouse where she actually is.
        LaunchedEffect(runtime, space) {
            var interactive: Boolean? = null
            while (true) {
                delay(12)
                val (cx, cy) = cursor() ?: continue
                val hit = onHer(runtime, cx, cy, widthDp, cfg.height) || runtime.motion.held
                if (hit != interactive) {
                    Bridge.setClickThrough("Andee Pet", !hit)
                    interactive = hit
                }
            }
        }
        LaunchedEffect(runtime, space, actions) {
            var wasDown = false
            var down = false
            var downStreak = 0
            var lastRaw = false
            var pressedOnHer = false
            var downAt = 0L
            var longFired = false
            // Taps of the current run (the ball's `ballTaps`: one talks, two type, three stop), resolved once
            // [MULTI_TAP_MS] passes with no further tap.
            var taps = 0
            var lastTapAt = 0L
            var dragging = false
            var grabX = 0f
            var grabY = 0f
            var grabFeetX = 0f
            var grabFeetY = 0f
            var lastX = 0f
            var lastY = 0f
            var lastT = 0L
            var vx = 0f
            var vy = 0f
            while (true) {
                delay(8)
                val now = System.currentTimeMillis()
                if (taps > 0 && !down && now - lastTapAt > MULTI_TAP_MS) {
                    val n = taps
                    taps = 0
                    when {
                        n >= 3 -> actions.onTripleTap()
                        n == 2 -> actions.onDoubleTap()
                        else -> { runtime.hop(); actions.onTap() }
                    }
                }
                val (x, y) = cursor() ?: continue
                // Debounce: a raw read can flicker, and each flicker used to look like a new press —
                // which re-latched the grab point mid-drag, so she drifted away from the pointer.
                val raw = Bridge.mouseDown()
                // Count consecutive *raw* samples: comparing raw to the filtered state never
                // accumulates (true vs false differ on every tick), so the filter used to latch
                // `down` at false and the pet never reacted to anything.
                if (raw == lastRaw) downStreak++ else downStreak = 1
                lastRaw = raw
                if (downStreak >= 2) down = raw
                if (down && !wasDown) {
                    pressedOnHer = onHer(runtime, x, y, widthDp, cfg.height) ||
                        (taps > 0 && now - lastTapAt < MULTI_TAP_MS && inBox(runtime, x, y, widthDp, cfg.height))
                    if (pressedOnHer) {
                        downAt = now
                        longFired = false
                        grabX = x; grabY = y
                        grabFeetX = runtime.motion.x; grabFeetY = runtime.motion.y
                        lastX = x; lastY = y; lastT = System.nanoTime()
                        dragging = false
                    }
                }
                // A long press opens the window, like the ball's — and ends the tap run, so its release is not a tap.
                if (down && pressedOnHer && !dragging && !longFired && now - downAt > LONG_PRESS_MS) {
                    longFired = true
                    taps = 0
                    actions.onLongPress()
                }
                if (down && grabFeetX != 0f) {
                    val moved = kotlin.math.abs(x - grabX) + kotlin.math.abs(y - grabY)
                    if (!dragging && !longFired && moved > 3f) {
                        dragging = true
                        taps = 0
                        runtime.grab()
                    }
                    if (dragging) {
                        runtime.motion.dragTo(grabFeetX + (x - grabX), grabFeetY + (y - grabY), space)
                        w.setLocation((runtime.motion.x - winW / 2f).roundToInt(), (runtime.motion.y - winH).roundToInt())
                        val span = (System.nanoTime() - lastT) / 1e9f
                        if (span > 0.01f) {
                            vx = (0.4f * ((x - lastX) / span) + 0.6f * vx).coerceIn(-4200f, 4200f)
                            vy = (0.4f * ((y - lastY) / span) + 0.6f * vy).coerceIn(-4200f, 4200f)
                            lastX = x; lastY = y; lastT = System.nanoTime()
                        }
                    }
                }
                if (!down && wasDown) {
                    if (dragging) {
                        runtime.release(vx, vy)
                    } else if (pressedOnHer && !longFired) {
                        // A tap counts toward the run. No hop here: she would jump out from under the pointer and
                        // the second click of a double-click would land on the air where she was. The hop is
                        // the single tap's, once the run is known to be one.
                        taps++
                        lastTapAt = now
                    }
                    pressedOnHer = false
                    dragging = false
                    grabFeetX = 0f
                }
                wasDown = down
            }
        }
        LaunchedEffect(w) {
            while (!w.isShowing) delay(50)
            repeat(20) {
                if (w.isShowing && Bridge.floatWindow("Andee Pet")) return@LaunchedEffect
                delay(100)
            }
        }
        LaunchedEffect(w, runtime, winW, winH) {
            var lastX = Int.MIN_VALUE
            var lastY = Int.MIN_VALUE
            while (true) {
                val x = (runtime.motion.x - winW / 2f).roundToInt()
                val y = (runtime.motion.y - winH).roundToInt()
                if (!runtime.motion.held && (x != lastX || y != lastY)) {
                    lastX = x; lastY = y
                    w.setLocation(x, y)
                }
                delay(16)
            }
        }
        Box(Modifier.fillMaxSize()) {
            val petLeft = (winW - widthDp) / 2f
            Box(
                Modifier.offset { IntOffset(petLeft.roundToInt(), (winH - cfg.height).roundToInt()) }
                    .size(widthDp.dp, cfg.height.dp),
            ) {
                val leaving = runtime.previousPose
                if (leaving != null) {
                    PetFace(
                        height = cfg.height.dp, look = runtime.look, pose = leaving,
                        tilt = runtime.tilt, squash = runtime.squash, lift = runtime.lift,
                        breath = runtime.breath, sway = runtime.sway, bob = runtime.bob,
                        alpha = 1f - runtime.fade.value, drawShadow = false,
                    )
                }
                PetFace(
                    height = cfg.height.dp, look = runtime.look, pose = runtime.currentPose,
                    tilt = runtime.tilt, squash = runtime.squash, lift = runtime.lift,
                    breath = runtime.breath, sway = runtime.sway, bob = runtime.bob,
                    frame = runtime.activeFrame, alpha = runtime.fade.value,
                )
            }
        }
    }
}

/**
 * Whether the pointer is on a drawn pixel of her, not merely inside the window.
 *
 * Sampled from the same bitmap that is on screen (see [PetSprites.mask]) so the empty corners of her
 * bounding box — beside the head, between the legs — pass the click through to whatever is behind.
 */
/** Inside her box at all (a second click of a run, which may miss her silhouette by a hair). */
private fun inBox(runtime: PetRuntime, x: Float, y: Float, widthDp: Float, height: Int): Boolean {
    val l = runtime.motion.x - widthDp / 2f
    val t = runtime.motion.y - height
    return x in l..(l + widthDp) && y in t..(t + height)
}

private fun onHer(runtime: PetRuntime, x: Float, y: Float, widthDp: Float, height: Int): Boolean {
    val boxLeft = runtime.motion.x - widthDp / 2f
    val boxTop = runtime.motion.y - height
    if (x < boxLeft || x > boxLeft + widthDp || y < boxTop || y > boxTop + height) return false
    val art = PetSprites.get(runtime.look, runtime.shown) ?: return true
    val mask = PetSprites.mask(runtime.look, runtime.shown) ?: return true
    // The drawing sits at the look's one scale, centred and on the floor of the box (PetFace).
    val drawnH = height * PetSprites.heightShare(runtime.look, runtime.shown)
    val drawnW = drawnH * art.width / art.height
    val left = boxLeft + (widthDp - drawnW) / 2f
    val top = boxTop + height - drawnH
    val px = (x - left) / drawnW * mask.w
    val py = (y - top) / drawnH * mask.h
    return mask.at(px, py)
}

/** The ball's: a tap waits this long for another before it counts. */
private const val MULTI_TAP_MS = 320L

/** Held this long without moving, a press is a long press (opens the window). */
private const val LONG_PRESS_MS = 520L

private fun cursor(): Pair<Float, Float>? =
    java.awt.MouseInfo.getPointerInfo()?.location?.let { it.x.toFloat() to it.y.toFloat() }
