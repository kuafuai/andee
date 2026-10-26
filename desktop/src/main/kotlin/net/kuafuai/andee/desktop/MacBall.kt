package net.kuafuai.andee.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import net.kuafuai.andee.brain.SpeechMood
import net.kuafuai.andee.desktop.voice.MacVoice
import net.kuafuai.andee.ui.ball.Action
import net.kuafuai.andee.ui.ball.BallLooks
import net.kuafuai.andee.ui.ball.Mood
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ImageInfo

/**
 * The ball's face on the Mac, decided the way the phone's FloatingWindowUi
 * decides it: the voice pipeline's state, a task in progress, a nod per tool
 * call, a pop when an answer lands, and the face the model picked for what it
 * is saying. Everything here is a fact about Andee; [BallView] only paints.
 *
 * State lives here rather than in the renderer so a renderer that comes and
 * goes with the window (or never starts, on a Mac with no OpenGL) loses nothing.
 */
object MacBall {

    /**
     * Every ball on screen: the floating one, and the one above the conversation in the chat window. They are
     * one Andee, so they wear one face — a set, not a single slot.
     */
    private val renderers = java.util.concurrent.CopyOnWriteArraySet<BallRenderer>()
    @Volatile private var voice = MacVoice.State.IDLE
    @Volatile private var busy = false
    @Volatile private var recording = false
    @Volatile private var emotion: Mood? = null

    /** The face the model asked for in the reply about to be spoken. */
    @Volatile private var nextEmotion: Mood? = null

    /** Restores the saved look; by name, like the phone's `ball` key. */
    fun restoreLook() = BallLooks.requestByName(Settings.get("ball").ifEmpty { null })

    /** One character along, with a pop — the phone's sideways swipe across the full card's ball. */
    fun stepLook(delta: Int) {
        val look = BallLooks.step(delta)
        Settings.save(mapOf("ball" to look.name))
        renderers.forEach { it.trigger(Action.POP) }
    }

    /** Switch to another character and remember it. */
    fun pickLook(name: String) {
        BallLooks.requestByName(name)
        Settings.save(mapOf("ball" to name))
        renderers.forEach { it.trigger(Action.POP) }
    }

    fun onVoice(s: MacVoice.State) {
        val was = voice
        voice = s
        // The model's face belongs to one utterance (see FloatingWindowUi.setState).
        emotion = if (s == MacVoice.State.SPEAKING) nextEmotion else null
        if (s == MacVoice.State.LISTENING && was != s) renderers.forEach { it.trigger(Action.HOP) }
        apply()
    }

    fun onBusy(b: Boolean) {
        busy = b
        apply()
    }

    /** A meeting is being recorded: the ball is listening, whatever else is happening. */
    fun onRecording(b: Boolean) {
        recording = b
        apply()
    }

    /** A tool ran: a nod, then back to the working face on its own. */
    fun onTool() { renderers.forEach { it.pulseTool() } }

    /**
     * An answer came back. The opening face it was written with is held for
     * when (if) it is spoken — the phone's fallback when the cue clock is
     * unknown, and on the Mac it always is. Then the pop: the same overclaim
     * the phone's `endTask` makes, for the same reason.
     */
    fun onAnswer(raw: String) {
        nextEmotion = SpeechMood.parse(raw).cues.firstOrNull()?.mood
        renderers.forEach { it.signalOutcome(true) }
    }

    internal fun attach(r: BallRenderer) {
        renderers += r
        apply()
    }

    internal fun detach(r: BallRenderer) {
        renderers -= r
    }

    private fun apply() {
        if (renderers.isEmpty()) return
        val mood = when {
            recording -> Mood.LISTENING
            voice == MacVoice.State.LISTENING -> Mood.LISTENING
            voice == MacVoice.State.SPEAKING -> Mood.SPEAKING
            busy -> Mood.THINKING
            else -> Mood.CALM
        }
        for (r in renderers) {
            r.setState(mood, listening = mood == Mood.LISTENING, emotion = emotion)
            r.setWorking(busy)
        }
    }
}

/**
 * The ball, [size] across, painted from [BallRenderer]'s frames at the
 * display's own rate. Rendered at device pixels so it is sharp on Retina.
 * A Mac where OpenGL cannot be had simply shows no ball.
 */
@Composable
fun BallView(
    size: Dp,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val renderer = remember(px) { BallRenderer(px, px) }
    DisposableEffect(renderer) {
        MacBall.attach(renderer)
        onDispose { MacBall.detach(renderer); renderer.stop() }
    }
    // One bitmap for the life of the view, refilled in place; a new image per
    // frame was most of the app's CPU. Redraws are pushed by the render thread,
    // so nothing here runs between frames.
    val bitmap = remember(px) {
        Bitmap().apply { allocPixels(ImageInfo(px, px, ColorType.BGRA_8888, ColorAlphaType.PREMUL)) }
    }
    val image = remember(bitmap) { bitmap.asComposeImageBitmap() }
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(renderer) {
        // At most one repaint queued: a busy UI thread (a drag) skips frames instead of piling them up.
        val queued = java.util.concurrent.atomic.AtomicBoolean(false)
        fun paint(f: ByteArray) {
            if (queued.compareAndSet(false, true)) javax.swing.SwingUtilities.invokeLater {
                queued.set(false)
                val t0 = System.nanoTime()
                bitmap.installPixels(f)
                bitmap.notifyPixelsChanged()
                tick++
                if (System.getProperty("andee.ballTrace") != null) PaintTrace.record(System.nanoTime() - t0)
            }
        }
        renderer.onFrame = { renderer.frame?.let(::paint) }
        // The renderer starts in its constructor, so it may have already drawn its
        // one and only frame before this callback existed — a ball that idles by
        // holding draws exactly once. Adopt whatever is there now, or it stays blank.
        renderer.frame?.let(::paint)
        onDispose { renderer.onFrame = {} }
    }
    val click = if (onClick == null) Modifier else Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
    Canvas(modifier.size(size).then(click)) {
        if (tick > 0) drawImage(image, dstOffset = IntOffset.Zero, dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()))
    }
}

/**
 * What the ball's taps mean, the same on the phone and on both of the Mac's balls:
 * one tap talks (and stops whatever was running first), two type, three stop, a long press opens the window.
 */
class BallActions(
    val onTap: () -> Unit,
    val onDoubleTap: () -> Unit,
    val onTripleTap: () -> Unit,
    val onLongPress: () -> Unit,
)

/** The gesture interval: a tap waits this long for a second one before it counts as one tap. */
private const val MULTI_TAP_MS = 320L

/**
 * One, two or three taps, told apart by waiting out [MULTI_TAP_MS] after the last — Compose has no triple-tap,
 * and its double-tap swallows the second tap of a third. A long press fires at once and ends the sequence.
 */
fun Modifier.ballTaps(a: BallActions): Modifier = this.pointerInput(a) {
    val scope = this
    var count = 0
    var timer: kotlinx.coroutines.Job? = null
    kotlinx.coroutines.coroutineScope {
        val outer = this
        scope.detectTapGestures(
            onLongPress = { timer?.cancel(); count = 0; a.onLongPress() },
            onTap = {
                count++
                timer?.cancel()
                timer = outer.launch {
                    kotlinx.coroutines.delay(MULTI_TAP_MS)
                    val n = count
                    count = 0
                    when {
                        n >= 3 -> a.onTripleTap()
                        n == 2 -> a.onDoubleTap()
                        else -> a.onTap()
                    }
                }
            },
        )
    }
}
