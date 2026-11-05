package net.kuafuai.andee.desktop.pet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.kuafuai.andee.desktop.Settings
import net.kuafuai.andee.desktop.Strings
import net.kuafuai.andee.ui.ball.Mood
import net.kuafuai.andee.util.Log
import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Everything the pet *is*, independent of how it is put on screen.
 *
 * It used to live inside the pet's window composable, which is why a second surface (the speech
 * bubble) had to invent its own copy of the state and drifted: the pose, the frame loop, the bubble's
 * text and the physics were one block of window content, and anything outside that window had to
 * guess. Now the window(s) are a *view* over this object: one window per screen draws it in canvas
 * coordinates, the fallback window draws it in its own box. Nothing is derived twice.
 */
class PetRuntime(
    val cfg: PetConfig,
    private val space: PetSpace,
    widthDp: Float,
) {
    val look: PetLook = cfg.look
    val aspect: Float = PetSprites.aspectOf(look)
    val height: Int = cfg.height
    val width: Float = widthDp

    val motion = startFeet(space, mainRect(space)).let { PetMotion(it.first, it.second) }

    var pose by mutableStateOf(PetPose.IDLE)
    var bubble by mutableStateOf<String?>(null)
    var bubbleFeet by mutableStateOf(motion.x to motion.y)
    var asleep by mutableStateOf(false)
    var tilt by mutableStateOf(0f)
    var squash by mutableStateOf(0f)
    var lift by mutableStateOf(0f)
    var breath by mutableStateOf(1f)
    var sway by mutableStateOf(0f)
    var bob by mutableStateOf(0f)
    var airborne by mutableStateOf(false)

    /**
     * True between a press and its release. The canvas is only interactive while the pointer is over
     * her *or* this is set: a fast drag leaves her box within a few milliseconds, and if the window
     * turns click-through at that moment the drag never starts — it worked only when you dragged
     * slowly enough to stay inside her silhouette.
     */
    var pointerDown by mutableStateOf(false)
    var frameIndex by mutableStateOf(0)

    /** Bumped whenever something asks the frame clock to wake up (a tap, a grab, a release). */
    var wake by mutableStateOf(0)

    var currentPose by mutableStateOf(PetPose.IDLE)
    var previousPose by mutableStateOf<PetPose?>(null)
    val fade = Animatable(1f)

    val idleFrames: List<ImageBitmap> = PetSprites.frames(look)
    var touchedAt = System.currentTimeMillis()

    /**
     * What Andee is doing, as a pose — listening, speaking, working, waiting for an answer — or null when it is
     * idle and the pet's own life (the acts, the nap) may run. Set by the window from [assistantPose]. It wins
     * over everything but being in the air: the pet is Andee's body, not company beside it.
     */
    var assistant by mutableStateOf<PetPose?>(null)

    /** What is actually drawn right now. */
    val shown: PetPose get() = if (airborne) PetPose.AIR else assistant ?: pose

    val activeFrame: ImageBitmap?
        get() = if (shown == PetPose.IDLE && previousPose == null) idleFrames.getOrNull(frameIndex) else null

    fun say(text: String, mood: PetPose) {
        pose = mood
        bubble = text
        bubbleFeet = motion.x to motion.y
        touchedAt = System.currentTimeMillis()
        wake++
    }

    /** The hop, for a click that never became a drag. */
    fun hop() {
        asleep = false
        touchedAt = System.currentTimeMillis()
        motion.pat()
        wake++
    }

    fun grab() {
        bubble = null
        asleep = false
        touchedAt = System.currentTimeMillis()
        wake++
        motion.grab()
    }

    fun release(vx: Float, vy: Float) {
        wake++
        bubble = null
        motion.release(vx, vy)
        Settings.save(mapOf("pet_pos" to "${motion.x.roundToInt()},${motion.y.roundToInt()}"))
    }

    /** The pet's box on the desk, in dp: top-left and size. */
    fun petBox(): PetArea = PetArea(
        motion.x - width / 2f,
        motion.y - height,
        motion.x + width / 2f,
        motion.y,
    )

    /** The bubble's box on the desk, or null when there is nothing to say. */
    fun bubbleBox(): PetArea? {
        if (bubble == null) return null
        val x = (bubbleFeet.first - BUBBLE_W / 2f).coerceIn(space.left, (space.right - BUBBLE_W).coerceAtLeast(space.left))
        val y = (bubbleFeet.second - height - BUBBLE_H - 4f).coerceAtLeast(space.top)
        return PetArea(x, y, x + BUBBLE_W, y + BUBBLE_H)
    }

    /**
     * The clock. One loop for the body, the breath and the idle loop, owned by the runtime rather
     * than by a window, so a second window cannot start a second pet.
     */
    @Composable
    fun run() {
        LaunchedEffect(motion, height, aspect, wake) {
            var last = 0L
            var tick = 0
            while (true) {
                if (motion.isSettled(space)) {
                    if (airborne) airborne = false
                    last = 0L
                    delay(150)
                    continue
                }
                if (motion.held) {
                    last = 0L
                    delay(16)
                    continue
                }
                val now = withFrameNanos { it }
                val dt = if (last == 0L) 1f / 60f else (now - last) / 1_000_000_000.0f
                last = now
                if (dt > 0f) motion.step(dt.coerceAtMost(0.05f), space)
                tick++
                if (tick % 2 == 0) {
                    tilt = motion.tilt
                    squash = motion.squash
                    lift = ((space.floorAt(motion.x) - motion.y) / 260f).coerceIn(0f, 1f)
                    val inAir = motion.launched && !motion.held
                    if (inAir != airborne) airborne = inAir
                }
            }
        }
        // The idle life: a slow breath, at 60 fps — measured to cost the same as 8 fps, because the
        // expense is compositing the window, not sampling it.
        LaunchedEffect(Unit) {
            val started = System.nanoTime()
            while (true) {
                delay(if (asleep) 250L else 16L)
                if (airborne || motion.held) continue
                val t = (System.nanoTime() - started) / 1_000_000_000f
                breath = 1f + 0.018f * sin(t * 2.6f)
                sway = 1.1f * sin(t * 1.9f)
                bob = if (pose == PetPose.TEA || pose == PetPose.HOTPOT) 1.1f * sin(t * 4.2f) else 1.6f * sin(t * 2.6f + 1.2f) - 1.6f
            }
        }
        // The drawn loop (warp frames), ~20 fps.
        LaunchedEffect(look, idleFrames.size) {
            Log.i(TAG, "idle loop: ${idleFrames.size} frames for ${look.id}")
            if (idleFrames.isEmpty()) return@LaunchedEffect
            while (true) {
                delay(50)
                if (shown != PetPose.IDLE) continue
                frameIndex = (frameIndex + 1) % idleFrames.size
            }
        }
        // A reaction wears off; the line goes with it.
        LaunchedEffect(bubble) {
            if (bubble == null) return@LaunchedEffect
            delay(3600)
            bubble = null
            pose = if (asleep) PetPose.SLEEP else PetPose.IDLE
        }
        // Something happens on its own every so often, and after three quiet minutes she sleeps.
        LaunchedEffect(look) {
            var lastAct = PetPose.IDLE
            var sinceWake = System.currentTimeMillis()
            var napEnds = 0L
            while (true) {
                delay(5_000)
                val quiet = System.currentTimeMillis() - touchedAt
                if (assistant != null) { touchedAt = System.currentTimeMillis(); continue }
                // She naps, she does not stay asleep (the ball's rule): up again after a while, with a yawn, and the
                // idle acts resume. Only a nap she fell into here ends here; nothing else sets asleep.
                if (asleep) {
                    if (napEnds == 0L) napEnds = System.currentTimeMillis() + NAP_MIN_MS + kotlin.random.Random.nextLong(NAP_SPAN_MS)
                    if (System.currentTimeMillis() > napEnds && bubble == null) {
                        asleep = false
                        napEnds = 0L
                        touchedAt = System.currentTimeMillis()
                        sinceWake = System.currentTimeMillis()
                        pose = PetPose.SLEEPY
                        delay(1_800)
                        if (pose == PetPose.SLEEPY) pose = PetPose.IDLE
                    }
                    continue
                }
                napEnds = 0L
                if (!asleep && bubble == null && pose == PetPose.IDLE && motion.resting && !airborne) {
                    if (quiet > SLEEP_AFTER_MS) {
                        asleep = true
                        val line = PetTalk.napping()
                        say(if (Strings.lang() == "zh") line.zh else line.en, PetPose.SLEEP)
                    } else if (System.currentTimeMillis() - sinceWake > 7_000 + kotlin.random.Random.nextLong(7_000)) {
                        val acts = net.kuafuai.andee.pet.PetMind.IDLE_ACTS
                            .filter { it != lastAct }
                        val act = acts.random()
                        lastAct = act
                        pose = act
                        sinceWake = System.currentTimeMillis()
                        val hold = if (act == PetPose.TEA || act == PetPose.HOTPOT) 3_200L else 1_600L
                        delay(hold)
                        if (pose == act) pose = PetPose.IDLE
                    }
                }
            }
        }
        // Andee became busy: whatever she was doing on her own stops, and a nap ends.
        LaunchedEffect(assistant) {
            Log.i(TAG, "body: ${assistant ?: "idle"}")
            if (assistant == null) return@LaunchedEffect
            asleep = false
            bubble = null
            pose = PetPose.IDLE
            touchedAt = System.currentTimeMillis()
        }
        // An answer landed: a hop (the ball's pop). It raised a bubble by itself: a wave, so the eye finds her.
        LaunchedEffect(net.kuafuai.andee.desktop.MacBall.answerTick) {
            if (net.kuafuai.andee.desktop.MacBall.answerTick == 0) return@LaunchedEffect
            hop()
        }
        LaunchedEffect(net.kuafuai.andee.desktop.MacBall.greetTick) {
            if (net.kuafuai.andee.desktop.MacBall.greetTick == 0) return@LaunchedEffect
            asleep = false
            pose = PetPose.HAPPY
            hop()
            delay(1_600)
            if (pose == PetPose.HAPPY) pose = PetPose.IDLE
        }
        // Crossfade whenever the drawn pose changes.
        LaunchedEffect(shown) {
            if (shown == currentPose) return@LaunchedEffect
            previousPose = currentPose
            currentPose = shown
            fade.snapTo(0f)
            fade.animateTo(1f, tween(90))
            previousPose = null
            if (shown == PetPose.IDLE) frameIndex = 0
        }
    }

    companion object {
        const val TAG = "Pet"
        const val SLEEP_AFTER_MS = net.kuafuai.andee.pet.PetMind.SLEEP_AFTER_MS
        const val NAP_MIN_MS = net.kuafuai.andee.pet.PetMind.NAP_MIN_MS
        const val NAP_SPAN_MS = net.kuafuai.andee.pet.PetMind.NAP_SPAN_MS
    }
}

/** Andee's state as a pose on the Mac, or null when idle — the shared rule ([PetMind.statePose]) fed from `MacBall`. */
fun assistantPose(holding: Boolean): PetPose? = net.kuafuai.andee.pet.PetMind.statePose(
    face = net.kuafuai.andee.desktop.MacBall.face,
    spoken = net.kuafuai.andee.desktop.MacBall.spokenEmotion,
    acting = net.kuafuai.andee.desktop.WorkLog.active && !net.kuafuai.andee.desktop.WorkLog.waiting,
    holding = holding,
)

/** One runtime per config, kept across recompositions and across the windows that draw it. */
@Composable
fun rememberPetRuntime(cfg: PetConfig, space: PetSpace, widthDp: Float): PetRuntime {
    val runtime = remember(cfg.look, cfg.height, space) { PetRuntime(cfg, space, widthDp) }
    runtime.run()
    return runtime
}

internal fun mainRect(space: PetSpace): PetArea = space.areaAt(space.left + 1f)

/** The work area of every display, narrowed by [inset] so the pet cannot stand half off an edge. */
internal fun deskAreas(inset: Float): Pair<PetSpace, PetArea> {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val toolkit = java.awt.Toolkit.getDefaultToolkit()
    val devices = env.screenDevices
    val areas = devices.map { device ->
        val b = device.defaultConfiguration.bounds
        val i = toolkit.getScreenInsets(device.defaultConfiguration)
        val pad = if (b.width > inset * 2f + 8f) inset else 0f
        PetArea(b.x + i.left + pad, (b.y + i.top).toFloat(), (b.x + b.width - i.right - pad).toFloat(), (b.y + b.height - i.bottom).toFloat())
    }
    val space = PetSpace(areas)
    val mainBounds = env.defaultScreenDevice.defaultConfiguration.bounds
    val mainIndex = devices.indexOfFirst { it.defaultConfiguration.bounds == mainBounds }.coerceAtLeast(0)
    return space to space.areas.getOrElse(mainIndex) { space.areas.first() }
}

/** Full display frames in dp, one per screen — what a canvas window covers. */
internal fun displayFrames(): List<PetArea> = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map {
    val b = it.defaultConfiguration.bounds
    PetArea(b.x.toFloat(), b.y.toFloat(), (b.x + b.width).toFloat(), (b.y + b.height).toFloat())
}

internal fun startFeet(space: PetSpace, mainArea: PetArea): Pair<Float, Float> {
    Settings.get("pet_pos").split(',').mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == 2 }?.let { (x, y) ->
        val inside = x in space.left..space.right && y in space.top..space.floorAt(x)
        if (inside) return x to y
    }
    return (mainArea.left + 24f) to mainArea.bottom
}
