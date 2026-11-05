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
 * The pet in one window just big enough for her and her line — the default.
 *
 * The window is her bounding box, not the screen: that is what keeps it cheap (a 250×350 transparent
 * window instead of 1728×1117, measured ~18% against ~38% for a full canvas) and what keeps the
 * speech bubble honest — it is drawn in the same window, in the same coordinate space, so it cannot
 * drift away from her. The window does move with her: one mover, one move per frame.
 */
@Composable
fun ApplicationScope.PetWindow(cfg: PetConfig, onOpenChat: () -> Unit) {
    val aspect = remember(cfg.look) { PetSprites.aspectOf(cfg.look) }
    val widthDp = cfg.height * aspect
    val (space, _) = remember(widthDp, cfg.height) { deskAreas(widthDp / 2f) }
    val runtime = rememberPetRuntime(cfg, space, widthDp)

    val winW = maxOf(widthDp, BUBBLE_W.toFloat())
    val winH = (cfg.height + BUBBLE_H + 8).toFloat()
    val state = rememberWindowState(size = DpSize(winW.dp, winH.dp), position = WindowPosition(0.dp, 0.dp))

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
        // The window is her bounding box plus the bubble, and a box has empty corners: without this
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
        LaunchedEffect(runtime, space) {
            var wasDown = false
            var down = false
            var downStreak = 0
            var lastRaw = false
            var pressedOnHer = false
            var lastPress = 0L
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
                val (x, y) = cursor() ?: continue
                // Debounce: a raw read can flicker, and each flicker used to look like a new press —
                // which re-patted her and re-latched the grab point mid-drag, so she drifted away
                // from the pointer instead of following it.
                val raw = Bridge.mouseDown()
                // Count consecutive *raw* samples: comparing raw to the filtered state never
                // accumulates (true vs false differ on every tick), so the filter used to latch
                // `down` at false and the pet never reacted to anything.
                if (raw == lastRaw) downStreak++ else downStreak = 1
                lastRaw = raw
                if (downStreak >= 2) down = raw
                if (down && !wasDown) {
                    val now = System.currentTimeMillis()
                    val onHer = onHer(runtime, x, y, widthDp, cfg.height)
                    pressedOnHer = onHer
                    if (onHer) {
                        if (now - lastPress < 320) onOpenChat() else runtime.pressStill()
                        lastPress = now
                        grabX = x; grabY = y
                        grabFeetX = runtime.motion.x; grabFeetY = runtime.motion.y
                        lastX = x; lastY = y; lastT = System.nanoTime()
                        dragging = false
                    }
                }
                if (down && grabFeetX != 0f) {
                    val moved = kotlin.math.abs(x - grabX) + kotlin.math.abs(y - grabY)
                    if (!dragging && moved > 3f) {
                        dragging = true
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
                    if (dragging) runtime.release(vx, vy) else if (pressedOnHer) runtime.hop()
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
            runtime.bubble?.let { text ->
                Box(Modifier.size(BUBBLE_W.dp, BUBBLE_H.dp)) {
                    SpeechBubble(text, tailAt = (winW / 2f).coerceIn(18f, BUBBLE_W - 18f))
                }
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
private fun onHer(runtime: PetRuntime, x: Float, y: Float, widthDp: Float, height: Int): Boolean {
    val boxLeft = runtime.motion.x - widthDp / 2f
    val boxTop = runtime.motion.y - height
    if (x < boxLeft || x > boxLeft + widthDp || y < boxTop || y > boxTop + height) return false
    val pose = if (runtime.airborne) PetPose.AIR else runtime.pose
    val mask = PetSprites.mask(runtime.look, pose) ?: return true
    val px = (x - boxLeft) / widthDp * mask.w
    val py = (y - boxTop) / height * mask.h
    return mask.at(px, py)
}

private fun cursor(): Pair<Float, Float>? =
    java.awt.MouseInfo.getPointerInfo()?.location?.let { it.x.toFloat() to it.y.toFloat() }
