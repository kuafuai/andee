package net.kuafuai.andee.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay

/** The prompt window's title; the native side finds the window by it. */
const val PROMPT_TITLE = "Andee Prompt"

private val PROMPT_W = 460.dp

/**
 * A command to approve or a question to answer, as a card beside the ball, above every app.
 *
 * It used to be drawn inside the chat window, which had to be forced open and brought forward to
 * show it: with the chat closed and only the ball on screen, the user saw nothing and the task sat
 * waiting for its two-minute timeout. The phone's card is a child of the ball's own window for the
 * same reason. This one is a window of its own (the ball's is exactly the ball's size), focusable
 * because the "type 确定 to confirm" box needs the keyboard, and it exists only while a prompt is up.
 */
@Composable
fun ApplicationScope.PromptWindow(ball: java.awt.Window?, pending: ApprovalGate.Pending?, ask: AskGate.Pending?) {
    val up = ball != null && (pending != null || ask != null)
    val density = LocalDensity.current
    // The card sizes itself; the window follows it. Measured, because a transparent window cannot
    // wrap its content and one big enough for the tallest card would swallow clicks around the small ones.
    var height by remember { mutableStateOf(320.dp) }
    val state = rememberWindowState(size = DpSize(PROMPT_W, height), position = WindowPosition(0.dp, 0.dp))
    state.size = DpSize(PROMPT_W, height)
    Window(
        onCloseRequest = {},
        state = state,
        title = PROMPT_TITLE,
        visible = up,
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = true,
    ) {
        val w = window
        LaunchedEffect(up, pending, ask, ball) {
            if (!up) return@LaunchedEffect
            ball?.let { followMoves(w, it, gap = 8) }
            var floated = false
            var raised = false
            // Re-placed while it is up: the card grows when the confirm box opens, and the ball can be dragged.
            while (true) {
                ball?.let { place(w, it, gap = 8) }
                if (!floated && w.isShowing) floated = net.kuafuai.andee.desktop.mac.Bridge.floatWindow(PROMPT_TITLE)
                if (!raised && w.isShowing) { w.toFront(); w.requestFocus(); raised = true }
                delay(80)
            }
        }
        Box(
            Modifier.wrapContentHeight(align = Alignment.Top, unbounded = true)
                .onSizeChanged { height = with(density) { it.height.toDp() } },
        ) {
            when {
                pending != null -> ApprovalCard(pending)
                ask != null -> AskCard(ask)
            }
        }
    }
}
