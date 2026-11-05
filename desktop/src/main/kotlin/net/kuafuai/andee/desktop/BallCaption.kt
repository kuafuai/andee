package net.kuafuai.andee.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.key
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment

/** The caption window's title; the native side finds the window by it. */
const val CAPTION_TITLE = "Andee Caption"

/**
 * What the ball is doing, in a few short lines beside it: the model's own words between steps and
 * the one-line narration of each tool. Not the whole transcript (the chat window has that), just
 * enough that a ball glowing for twenty seconds is not a mystery. The newest line is brightest and
 * the oldest fades, and the whole thing fades out when the turn ends.
 */
object BallCaption {
    private const val MAX_LINES = 3
    private const val MAX_CHARS = 34

    /** Newest last. Written from the brain's thread, read by the composable. */
    var lines by mutableStateOf<List<String>>(emptyList())
        private set

    fun thinking() = push(if (Strings.lang() == "zh") "思考中…" else "Thinking…")

    fun push(raw: String) {
        // A progress line may be a paragraph; the first sentence is the gist.
        val one = raw.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (one.isEmpty()) return clear()
        val cut = if (one.length > MAX_CHARS) one.take(MAX_CHARS - 1) + "…" else one
        if (lines.lastOrNull() == cut) return
        lines = (lines + cut).takeLast(MAX_LINES)
    }

    fun clear() { if (lines.isNotEmpty()) lines = emptyList() }
}

private val CAPTION_W = 252.dp

/** Window height for [n] lines. */
private fun heightFor(n: Int) = (20 + 22 * n.coerceIn(1, 3)).dp

/**
 * A second borderless, fully transparent window under (or, near the bottom of the screen, over) the
 * ball: just words with a shadow, no pane. It is a window of its own because the ball's is exactly
 * the ball's size; it exists only while there is something to say and fades in and out.
 */
@Composable
fun ApplicationScope.BallCaptionWindow(ball: java.awt.Window?, suppressed: Boolean = false, gap: Int = -14) {
    // While a turn is working: its status (ticking) over the newest two lines of what it is thinking and doing
    // ([WorkLog]), scrolling as they arrive. Otherwise the plain caption.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(WorkLog.active) { while (WorkLog.active) { now = System.currentTimeMillis(); delay(500) } }
    val working = WorkLog.active
    val workLines = if (working) WorkLog.lines.takeLast(2) else emptyList()
    val lines = if (working) listOf(WorkLog.status(now)) + workLines.map { it.text.let { t -> if (t.length > 34) t.take(33) + "…" else t } }
        else BallCaption.lines
    // A status line keeps one key while its clock ticks; a work line is keyed by its id, so a repeat still rises.
    val keys = if (working) listOf("status") + workLines.map { "w${it.id}" } else lines
    // Keep the last words on screen while they fade out.
    var shown by remember { mutableStateOf<List<String>>(emptyList()) }
    var shownKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    if (lines.isNotEmpty()) { shown = lines; shownKeys = keys }
    val alpha by animateFloatAsState(if (lines.isEmpty()) 0f else 1f, tween(if (lines.isEmpty()) 600 else 280))
    val visible = ball != null && !suppressed && (lines.isNotEmpty() || alpha > 0.01f)

    val state = rememberWindowState(size = DpSize(CAPTION_W, heightFor(1)), position = WindowPosition(0.dp, 0.dp))
    state.size = DpSize(CAPTION_W, heightFor(shown.size))
    Window(
        onCloseRequest = {},
        state = state,
        title = CAPTION_TITLE,
        visible = visible,
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
    ) {
        val w = window
        LaunchedEffect(visible, ball, gap) {
            if (!visible) return@LaunchedEffect
            ball?.let { followMoves(w, it, gap) }
            var floated = false
            while (true) {
                ball?.let { place(w, it, gap) }
                if (!floated && w.isShowing) floated = net.kuafuai.andee.desktop.mac.Bridge.floatWindow(CAPTION_TITLE)
                delay(80)
            }
        }
        // Glass lettering, no pane: the words are a top-lit gradient (bright above, a touch clearer
        // below), wrapped in a faint cool glow and a tight dark shadow so they hold on a white page
        // and a black one. Older lines recede (dimmer, slightly defocused); the ends of the block
        // dissolve into the desktop instead of stopping.
        val face = Brush.verticalGradient(listOf(Color.White, Color(0xFFDCE8FF)))
        Column(
            Modifier.fillMaxSize().alpha(alpha).padding(horizontal = 6.dp, vertical = 6.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(0f to Color.Transparent, 0.14f to Color.Black, 0.86f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            shown.forEachIndexed { i, line ->
                val newest = i == shown.lastIndex || (working && i == 0)
                key(shownKeys.getOrElse(i) { line }) {
                    // Each line rises a few dp into place as it arrives.
                    var risen by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { risen = true }
                    val rise by animateDpAsState(if (risen) 0.dp else 7.dp, tween(320))
                    val born by animateFloatAsState(if (risen) 1f else 0f, tween(320))
                    val dim by animateFloatAsState(if (newest) 1f else 0.55f, tween(300))
                    Box(Modifier.offset(y = rise).alpha(born * dim).padding(vertical = 1.dp)) {
                        Text(  // glow
                            line, color = Color(0xFF9CC4FF).copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.blur(7.dp, BlurredEdgeTreatment.Unbounded),
                        )
                        Text(  // contact shadow
                            line, color = Color.Black.copy(alpha = 0.55f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.offset(y = 1.dp).blur(2.5.dp, BlurredEdgeTreatment.Unbounded),
                        )
                        Text(  // the lettering
                            line, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = TextStyle(brush = face, letterSpacing = 0.3.sp),
                            modifier = if (newest) Modifier else Modifier.blur(0.7.dp, BlurredEdgeTreatment.Unbounded),
                        )
                    }
                }
            }
        }
    }
}

/** Centred under the ball, or above it when there is no room below. */
internal fun place(w: java.awt.Window, ball: java.awt.Window, gap: Int = -14) {
    val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.defaultConfiguration.bounds }.firstOrNull { it.contains(ball.x + ball.width / 2, ball.y + ball.height / 2) }
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    var x = ball.x + ball.width / 2 - w.width / 2
    x = x.coerceIn(screen.x + 4, maxOf(screen.x + 4, screen.x + screen.width - w.width - 4))
    val below = ball.y + ball.height + gap
    val y = if (below + w.height < screen.y + screen.height - 8) below else ball.y - w.height - gap
    if (w.x != x || w.y != y) w.setLocation(x, y)
}

/**
 * Re-place [w] the moment [ball] moves, until the calling effect ends. The 80 ms poll alone trailed a dragged
 * pet visibly; the poll stays for size changes. The effect is keyed on the ball, because the body's window is
 * replaced when the user switches between the ball and the pet, and a loop holding the old one followed a window
 * that was gone.
 */
internal fun kotlinx.coroutines.CoroutineScope.followMoves(w: java.awt.Window, ball: java.awt.Window, gap: Int = -14) {
    val l = object : java.awt.event.ComponentAdapter() {
        override fun componentMoved(e: java.awt.event.ComponentEvent?) { if (w.isShowing) place(w, ball, gap) }
    }
    ball.addComponentListener(l)
    coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { java.awt.EventQueue.invokeLater { ball.removeComponentListener(l) } }
}
