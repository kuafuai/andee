package net.kuafuai.andee.ui.ball

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import net.kuafuai.andee.ui.ball.gl.Capsule
import net.kuafuai.andee.ui.ball.gl.Ear
import net.kuafuai.andee.ui.ball.gl.Eye
import net.kuafuai.andee.ui.ball.gl.Horn
import net.kuafuai.andee.ui.ball.gl.IcoSphere
import net.kuafuai.andee.ui.ball.gl.Mesh
import net.kuafuai.andee.ui.ball.gl.PointsBuffer
import net.kuafuai.andee.ui.ball.gl.Shader
import net.kuafuai.andee.ui.ball.gl.Torus
import net.kuafuai.andee.ui.ball.gl.UvSphere
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The drawn scene is four passes: body, face, blush, rim. Animation state lives
 * in [EmotionState]; this class composes matrices, issues draw calls, and
 * decides which [Mood] is actually showing (see [resolveMood]).
 *
 * Draw order and depth are the whole trick here:
 *   1. Body — opaque sphere, writes depth. The ball's fill, and what stops
 *      the rim's far half and anything behind the ball from showing through.
 *   2. Face — opaque, writes depth, sits at z = [FACE_Z] so it is in front of
 *      the body's surface everywhere the features actually are.
 *   3. Blush, 4. Rim — additive, depth-tested, no depth write.
 *
 * The body is drawn at [BODY] and the rim at [RIM], and the body must stay the
 * *smaller* of the two: the rim is depth-tested but doesn't write depth, so a
 * body at a larger scale culls it in full and the ball loses its outline while
 * the eyes keep floating. That is how the ring-occlusion silhouette that used
 * to sit here killed the rim.
 *
 * The body used to be a *flat* near-black fill, and that was the second half of
 * why the ball read as an appliance rather than a character: a flat disc with a
 * glow around it and two white eyes is a face on a hole. It is now shaded —
 * top-lit gradient, one specular dot, a lift at the silhouette edge — which is
 * what makes it read as a solid, glossy *thing* instead of an absence. The
 * specular is aimed at the forehead rather than the front: a highlight landing
 * on the eyes eats half their contrast at exactly the size this is drawn.
 *
 * The core and ring passes below are built but not drawn; each is one line in
 * [onDrawFrame] away from coming back.
 */
class EmotionBallRenderer : GLSurfaceView.Renderer {

    // Public knobs (safe to write from UI thread)
    @Volatile
    var mood: Mood = Mood.CALM

    @Volatile
    var listening: Boolean = false

    /**
     * The face the model asked for, for the reply it is saying right now.
     *
     * A *separate* channel from [mood] rather than a value written into it, and
     * the separation is the whole trick. [mood] carries the voice pipeline's
     * state, and SPEAKING is what used to drive the mouth; swapping HAPPY in
     * there would have given the user a grinning ball with a shut mouth
     * halfway through its own sentence. So this layers on top: it supplies the
     * *face* while [EmotionState.tick]'s `talking` flag keeps supplying the
     * *mouth*.
     *
     * Only read while [mood] is SPEAKING. Null means the model said nothing
     * about how it felt, which is the overwhelmingly common case and must land
     * on exactly the behaviour that existed before this channel did.
     */
    @Volatile
    var emotion: Mood? = null

    /**
     * A task is running — set for its whole duration, not per tool call.
     *
     * [toolLeft] cannot express this: it decays [TOOL_SECONDS] after the last
     * call, and the stretches where the brain is thinking rather than tapping
     * make no calls at all. Those are exactly the stretches that used to drop
     * the ball back to a resting face while it was still working.
     */
    @Volatile
    var working: Boolean = false

    /**
     * The ball is holding up a signboard (CardUi.ask / alert) and is waiting
     * for the user's choice.
     *
     * While true — and nothing is being said aloud — the face is HOLDING:
     * steady eyes, lowered brows, closed mouth, slow breath. Waiting for a
     * decision is deliberately not the surprised LISTENING face.
     * Outranks the work faces (the ball has *paused* the task to ask), but not
     * the voice pipeline or the outcome flash — those are still mid-sentence.
     */
    @Volatile
    var holding: Boolean = false

    /**
     * The window is sitting at the ledge geometry — the perch slide (if any)
     * has finished.
     *
     * Separated from [working] because they start at different moments: a
     * task begins while the window is still travelling to the ledge, and the
     * cling (half-sunk body, paws, scissor) drawn against a window that is
     * *not yet at the edge* reads as the ball being chopped in half
     * mid-screen, then teleporting down — exactly the glitch the "帮我看看"
     * flow showed. The window owns this flag: it goes true when the perch
     * geometry is applied, false when the ball leaves the ledge or the
     * window is resized away from it.
     */
    @Volatile
    var atLedge: Boolean = false

    /**
     * Written on the GL thread, read by the UI thread: the ball has dozed off.
     *
     * Exposed so [FloatingWindowUi] can keep the idle stroll from sleepwalking.
     * The doze is decided here, from [idleFor], and there is no way for the UI
     * side to derive it — it knows what it last asked for, not how long ago.
     */
    @Volatile
    var dozing: Boolean = false

    /** When true, cycles through all moods every ~5.5 s. Handy for testing. */
    @Volatile
    var demoCycle: Boolean = false

    // Requests posted from the UI thread and consumed once, at the top of the
    // next frame. A plain @Volatile rather than a queue: both are "the latest
    // one wins" signals, and the GL thread is the only reader.
    @Volatile
    private var toolRequest = false

    @Volatile
    private var outcomeRequest: Mood? = null

    @Volatile
    private var actionRequest: Action? = null

    /** A `screen.*` command just ran — see [pulseTool]. */
    private var toolLeft = 0f

    /** Transient SUCCESS / FAILED face, outranking everything while it lasts. */
    private var overlayMood: Mood? = null
    private var overlayLeft = 0f

    /** Seconds spent with nothing to do at all, which is what puts it to sleep. */
    private var idleFor = 0f
    private var lastEffective = Mood.CALM

    // ---- Idle fidgets ----
    /** Countdown to the next fidget; only ticks while calm, awake, unheld. */
    private var fidgetIn = FIDGET_AFTER_SECONDS
    /** Last fidget played, so the picker never repeats twice in a row. */
    private var lastFidget: Action? = null
    private val fidgetRandom = java.util.Random()

    /**
     * The agent ran a tool against the device. Shows as SEARCHING plus a nod,
     * and decays on its own after [TOOL_SECONDS] — so a burst of tool calls
     * reads as continuous work, and the last one lets go without anyone having
     * to remember to clear it.
     *
     * Deliberately not a boolean "working" flag mirroring the task lifecycle:
     * a task that is only thinking already has a face (THINKING), and a flag
     * held for the whole task would hide it.
     */
    fun pulseTool() {
        toolRequest = true
    }

    /** A task finished. Two seconds of face, and a pop or a flinch. */
    fun signalOutcome(ok: Boolean) {
        outcomeRequest = if (ok) Mood.SUCCESS else Mood.FAILED
    }

    /** Play a one-shot gesture on the next frame. Safe from any thread. */
    fun request(action: Action) {
        actionRequest = action
    }

    // ---- Shaders ----
    private lateinit var shellShader: Shader
    private lateinit var rimShader: Shader
    private lateinit var coreShader: Shader
    private lateinit var flowShader: Shader
    private lateinit var pointsShader: Shader
    private lateinit var solidShader: Shader
    private lateinit var bubbleShader: Shader

    // ---- Meshes ----
    private lateinit var shell: Mesh
    private lateinit var rimSphere: Mesh
    private lateinit var core: Mesh
    private lateinit var ringMeshes: Array<Mesh>
    private lateinit var eyeArcMesh: Mesh

    /**
     * The look being drawn. Latched once per frame by [onDrawFrame]; see
     * [BallLooks] for why it must not be read live mid-frame.
     *
     * Shadowing the old top-level `LOOK` constant as a getter is what kept this
     * change to a handful of lines: the ~50 `LOOK.x` sites below did not have
     * to move, and none of them can accidentally cache a stale look.
     */
    private val LOOK: BallLook get() = BallLooks.active

    /**
     * The meshes whose *dimensions* come from the look, one set per look.
     *
     * Built for every look at surface creation rather than rebuilt on each
     * swipe, because [Mesh] allocates GL buffers and has no way to free them —
     * rebuilding on switch would leak a VBO pair per swipe, forever. Three
     * looks' worth of eye capsules and mouth tori is a few kilobytes, so the
     * trade is not close.
     *
     * The accessory meshes are null when the look has none, which is what
     * `hornH == 0f` etc. mean, and is also the test the draw calls use.
     */
    private class LookMeshes(look: BallLook) {
        /**
         * The eye, or the eye with its top sliced off flat when the look wants
         * a glare — see [BallLook.eyeCut]. Same two numbers either way: the cut
         * is a *shape*, not a size, so nothing else about the eye moves.
         *
         * Built here with everything else rather than swapped at draw time for
         * the same reason the horns are: [Mesh] allocates GL buffers and has no
         * way to free them, so a mesh built on a parameter change leaks a VBO
         * pair every frame the slider moves.
         */
        val eye: Mesh =
            if (look.eyeCut > 0f)
                Eye.build(look.eyeRadius, look.eyeCyl, look.eyeCut, 4, 12)
            else Capsule.build(look.eyeRadius, look.eyeCyl, 4, 12)
        val mouth: Mesh = Torus.build(look.mouthR, look.mouthTube, 8, 24, PI.toFloat())
        val horn: Mesh? =
            if (look.hornH > 0f) Horn.build(look.hornR, look.hornH, look.hornBend / DEG, 7, 12)
            else null
        val barb: Mesh? =
            if (look.barbH > 0f) Horn.build(look.barbR, look.barbH, 0f, 4, 10) else null
        val tooth: Mesh? =
            if (look.toothH > 0f) Horn.build(look.toothR, look.toothH, 0f, 3, 8) else null
        // A unit sphere scaled into the muzzle's ellipsoid at draw time — higher
        // subdivision than [handMesh], because the snout is a prominent facial
        // feature rather than a paw blob.
        val snout: Mesh? =
            if (look.snoutRx > 0f) IcoSphere.build(3) else null
        val ear: Mesh? =
            if (look.earH > 0f)
                Ear.build(
                    look.earW, look.earH, look.earThick, look.earBend / DEG,
                    // Rings scale with the taper: a sharp one does all its
                    // curving in the last fifth of the length, so at the pig's
                    // 6 rings that fifth is one flat step and the ear ends in a
                    // chisel. 14 puts four rings inside the rounding.
                    segs = if (look.earTaper > 3f) 14 else 6,
                    radialSegs = 10,
                    taper = look.earTaper,
                )
            else null
    }

    private lateinit var lookMeshes: List<LookMeshes>

    /** The current look's meshes. Same latching rules as [LOOK]. */
    private val meshes: LookMeshes get() = lookMeshes[BallLooks.index]

    /**
     * Z the pupil and glint are drawn at, relative to the face plane.
     *
     * Not cosmetic: the eye is a *capsule*, so it has a z-radius of
     * [BallLook.eyeRadius] and its front surface sits that far in front of the
     * face plane. A pupil left on the plane is behind the eye it belongs to and
     * is depth-culled away entirely.
     *
     * An instance getter rather than a `companion object` constant, which is
     * what it used to be: a companion value derived from the look is computed
     * once at class load, so it would freeze at whichever look happened to be
     * active then and bury the pupils of every look with a larger eye.
     */
    private val eyeFrontZ: Float get() = LOOK.eyeRadius + 0.025f

    /** One blob, instanced eight times into two paws. See [drawHands]. */
    private lateinit var handMesh: Mesh

    /** One blob per pupil and per glint — see [drawDot]. */
    private lateinit var dotMesh: Mesh

    /** 鼻涕泡用的球体。subdiv 4：水滴靠轮廓圈出形状，边不能是折线。 */
    private lateinit var bubbleSphere: Mesh

    // ---- Dust ----
    private lateinit var heads: PointsBuffer
    private lateinit var tails: PointsBuffer

    /** Two point sprites, the cheeks. See [drawBlush]. */
    private lateinit var blush: PointsBuffer

    // ---- Matrices (scratch, reused every frame) ----
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val root = FloatArray(16)
    private val face = FloatArray(16)
    private val model = FloatArray(16)
    private val local = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)

    // ---- State ----
    private val state = EmotionState()

    /** Scratch for [drawBody]'s mood tint — per-frame, so never allocated. */
    private val bodyColor = FloatArray(3)

    /** Scratch for the pupil colour, which is a dark relative of the mood. */
    private val pupilColor = FloatArray(3)

    /** Scratch for the mouth colour, which is the mood coloured down on some looks. */
    private val mouthColor = FloatArray(3)

    /** Scratch for the dark outline colour — see [drawRim]. */
    private val outlineColor = FloatArray(3)

    /** `x, y, nx, ny` from [mouthPointAt]. Scratch, rewritten per tooth. */
    private val mouthPt = FloatArray(4)

    /** Filled per frame from [bodyColor] — see [BallLook.browShade]. */
    private val browColor = FloatArray(3)

    /** Filled per frame from [bodyColor] — see [BallLook.earShade]. */
    private val earColor = FloatArray(3)

    /** Scratch for the nostril dots — a dark relative of [bodyColor]. */
    private val nostrilColor = FloatArray(3)

    /** Filled per frame from [bodyColor] — see [BallLook.snoutShade]. */
    private val snoutColor = FloatArray(3)

    // Demo cycling
    private var demoIndex = 0
    private var demoNext = 0f
    // The voice-pipeline moods are promises, not poses: LISTENING says "your
    // mic is open", SPEAKING says "sound is coming out". A silent demo cycle
    // that wears them lies to the user twice over. Cycled only when someone
    // explicitly drives the renderer with them.
    private val moods = arrayOf(
        Mood.CALM, Mood.HAPPY, Mood.CURIOUS, Mood.TENSE,
        Mood.ANXIOUS, Mood.CONCERNED, Mood.SEARCHING, Mood.SLEEPING,
    )

    // Point-size projection factor: viewport-height / (2 tan(fov/2))
    private var pointFactor = 400f

    // Viewport, needed by the ledge scissor.
    private var vw = 0
    private var vh = 0

    // Time
    private val start = System.nanoTime()
    private var last = start

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)

        shellShader = Shader(SHELL_VS, SHELL_FS)
        rimShader = Shader(RIM_VS, RIM_FS)
        coreShader = Shader(CORE_VS, CORE_FS)
        flowShader = Shader(FLOW_VS, FLOW_FS)
        pointsShader = Shader(POINTS_VS, POINTS_FS)
        solidShader = Shader(SOLID_VS, SOLID_FS)
        bubbleShader = Shader(BUBBLE_VS, BUBBLE_FS)

        shell = IcoSphere.build(5)
        rimSphere = UvSphere.build(48, 32)
        core = IcoSphere.build(3)
        ringMeshes = Array(4) { i ->
            val r = RINGS[i]
            Torus.build(r.r, r.tube, 4, 160)
        }
        // The happy `^` eye. A partial torus spans [0, arc] starting at +X, so
        // its midpoint sits at arc/2 and it has to be rotated by (π/2 − arc/2)
        // to crown the eye instead of leaning off to one side — that rotation
        // is EYE_ARC_ROT_Z. Its size does not come from the look, so unlike the
        // round eye and the mouth there is only ever one of it.
        eyeArcMesh = Torus.build(0.1f, 0.03f, 6, 20, EYE_ARC_SPAN)
        lookMeshes = BallLooks.ALL.map { LookMeshes(it) }
        // Low subdivision on purpose: a paw is ~18 dp across on screen, where
        // subdiv 2 is already smooth and subdiv 4 is 16x the triangles for
        // nothing. It gets drawn eight times a frame.
        handMesh = IcoSphere.build(2)
        // Pupils and glints. Same unit blob as a paw part, and the same order of
        // cost: four of them a frame, each about 4 dp wide on screen.
        dotMesh = IcoSphere.build(2)
        bubbleSphere = IcoSphere.build(4)

        heads = PointsBuffer(DUST_COUNT)
        tails = PointsBuffer(DUST_COUNT * TRAIL_COUNT)
        blush = PointsBuffer(2)

        // Camera pushed back from the R3F reference's 3.7 to shrink the ball
        // to ~75%. Everything (rings, dust, face) scales proportionally.
        Matrix.setLookAtM(view, 0, 0f, 0f, CAM_Z, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        vw = width
        vh = height
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 42f, aspect, 0.1f, 100f)
        val fovRad = 42f * PI.toFloat() / 180f
        pointFactor = height / (2f * tan(fovRad / 2f))
    }

    override fun onDrawFrame(gl: GL10?) {
        val nowNs = System.nanoTime()
        val dt = min(0.1f, (nowNs - last) / 1e9f)
        last = nowNs
        val t = (nowNs - start) / 1e9f

        // Take the user's swipe *once*, before anything reads [LOOK]. Latching
        // here rather than letting the getter follow it live is what keeps a
        // frame from being drawn half in one character and half in another —
        // see [BallLooks].
        BallLooks.latch()

        if (demoCycle && t >= demoNext) {
            demoIndex = (demoIndex + 1) % moods.size
            mood = moods[demoIndex]
            demoNext = t + 5.5f
        }
        // Cling (half-sunk body, paws, ledge scissor) only once the window is
        // actually at the ledge — see [atLedge]. Everything else about working
        // (the face, the rim) is fine to start immediately; the ball looks
        // eager, not broken.
        state.tick(
            dt,
            t,
            resolveMood(dt),
            listening,
            working && atLedge,
            talking = mood == Mood.SPEAKING,
        )

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        clipToLedge()

        buildRoot()
        buildFaceMatrix()

        drawBody()
        drawHorns()
        drawEars()
        drawTail()
        drawSnout()
        drawFace()
        drawHands()
        drawBlush()
        drawRim()
        // drawRings() — the orbiting halo read as clutter around a ball this
        // small, and the depth silhouette it needed to stop it cutting through
        // the eyes was culling the rim as well. Meshes and shader stay built.
        // drawDust() — the orbiting specks read as smudges at the size the ball
        // is actually drawn on the card. Buffers and shader stay built so this
        // is one line to put back.

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDepthMask(true)
    }

    /**
     * Cut the frame off at the ledge while clinging, rather than letting the
     * screen edge do it.
     *
     * The perched window hangs half off the bottom of the display, so the
     * display used to provide that cut for free. It does not on a phone: the
     * navigation bar owns the bottom strip, is layered above us, and may be
     * transparent — in which case the half of the ball that is supposed to be
     * hidden is plainly visible over it and the paws are gripping the middle of
     * their own face. Owning the cut makes the perch look the same everywhere,
     * whatever is or isn't painted below the line.
     *
     * Ramped by [EmotionState.cling] so the edge rises up the ball as it sinks,
     * in step with the window sliding down, instead of lopping it off in one
     * frame. Scissor Y counts from the bottom of the viewport.
     */
    private fun clipToLedge() {
        val c = state.cling
        if (c < 0.01f) {
            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            return
        }
        val line = vh * 0.5f + HAND_LEDGE * (pointFactor / CAM_Z)
        val cut = (line * c).toInt().coerceIn(0, vh)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(0, cut, vw, vh - cut)
    }

    /**
     * Fold the three sources of "what should the face be" into one mood.
     *
     * Precedence, highest first: a finished task's SUCCESS / FAILED flash, then
     * whatever the voice pipeline says (it is mid-conversation with the user and
     * outranks housekeeping), then tool activity, then sleep.
     *
     * Tool activity only displaces CALM and THINKING because those two are the
     * "nothing is being said to you" faces. SEARCHING is strictly more
     * informative than either while the device is actually being driven, and
     * [working] holds it there across the gaps between calls.
     *
     * GL thread only.
     */
    private fun resolveMood(dt: Float): Mood {
        actionRequest?.let {
            actionRequest = null
            state.trigger(it)
        }

        if (toolRequest) {
            toolRequest = false
            toolLeft = TOOL_SECONDS
            state.trigger(Action.NOD)
        } else if (toolLeft > 0f) {
            toolLeft -= dt
        }

        outcomeRequest?.let {
            outcomeRequest = null
            overlayMood = it
            overlayLeft = OVERLAY_SECONDS
            toolLeft = 0f
            state.trigger(if (it == Mood.SUCCESS) Action.POP else Action.RECOIL)
        }
        if (overlayLeft > 0f) {
            overlayLeft -= dt
            if (overlayLeft <= 0f) overlayMood = null
        }

        val spoken = if (mood == Mood.SPEAKING) emotion else null
        val base = when {
            // An emotion the model picked for this reply outranks the work
            // faces for the same reason the voice pipeline does: the ball is
            // mid-sentence with the user, and housekeeping can wait.
            spoken != null -> spoken
            (working || toolLeft > 0f) && (mood == Mood.CALM || mood == Mood.THINKING) ->
                Mood.SEARCHING
            else -> mood
        }
        // Holding a signboard: attending to the user beats the work faces
        // (the task is *paused* on this question) but not the voice pipeline
        // or the outcome flash, which arrive through [mood] and [overlayMood]
        // above and take what they take.
        val holdingBase =
            if (holding && (base == Mood.CALM || base == Mood.SEARCHING)) Mood.HOLDING
            else base
        idleFor = if (holdingBase == Mood.CALM) idleFor + dt else 0f

        // Idle fidgets: only while genuinely idle — calm, awake, no signboard,
        // no overlay, nothing. The moment any real activity shows up the timer
        // resets, so a fidget can never land on top of (or be mistaken for
        // part of) a response. `idleFor` is exactly the right gate: it ticks
        // on the same condition that eventually puts the ball to sleep, so
        // the fidgets live in the window between "just finished" and "asleep"
        // (and stop entirely once asleep — a sleeping ball doesn't dance).
        if (idleFor > 0f && idleFor <= SLEEP_AFTER_SECONDS && actionRequest == null) {
            fidgetIn -= dt
            if (fidgetIn <= 0f) {
                // Weighted pick, never the same fidget twice in a row — a
                // repeat reads as a stuck animation, not a personality.
                var pick: Action
                do {
                    var r = fidgetRandom.nextFloat() * IDLE_ACTIONS.sumOf { it.second.toDouble() }
                    pick = IDLE_ACTIONS.first().first
                    for ((a, w) in IDLE_ACTIONS) {
                        r -= w
                        if (r <= 0) { pick = a; break }
                    }
                } while (pick == lastFidget)
                lastFidget = pick
                state.trigger(pick)
                fidgetIn = FIDGET_MIN_SECONDS +
                    fidgetRandom.nextFloat() * (FIDGET_MAX_SECONDS - FIDGET_MIN_SECONDS)
            }
        } else if (idleFor == 0f) {
            // Real activity (or sleep, or a signboard) — back to the top of
            // the patience window, so the first fidget after a conversation
            // never comes before [FIDGET_AFTER_SECONDS] of quiet.
            fidgetIn = FIDGET_AFTER_SECONDS
        }

        val effective = overlayMood
            ?: if (idleFor > SLEEP_AFTER_SECONDS) Mood.SLEEPING else holdingBase
        // Waking up is worth a hop. Nothing else in here triggers an action on
        // a mood change — a mood is a resting state, and animating every
        // transition would make the ball twitch its way through a conversation.
        if (lastEffective == Mood.SLEEPING && effective != Mood.SLEEPING) {
            state.trigger(Action.HOP)
        }
        lastEffective = effective
        dozing = effective == Mood.SLEEPING
        return effective
    }

    // ---- Transform builders ----

    private fun buildRoot() {
        Matrix.setIdentityM(root, 0)
        Matrix.translateM(root, 0, 0f, state.hopY, 0f)
        Matrix.rotateM(root, 0, state.rotX * DEG, 1f, 0f, 0f)
        Matrix.rotateM(root, 0, state.rotY * DEG, 0f, 1f, 0f)
        Matrix.rotateM(root, 0, state.rotZ * DEG, 0f, 0f, 1f)
        // Z tracks X so a squashed ball stays a spheroid — bulging sideways as
        // it flattens — rather than turning into a disc seen face-on.
        Matrix.scaleM(root, 0, state.scaleX, state.scaleY, state.scaleX)
    }

    private fun buildFaceMatrix() {
        // face = root * translate(0, 0, LOOK.faceZ)
        System.arraycopy(root, 0, face, 0, 16)
        Matrix.translateM(face, 0, 0f, 0f, LOOK.faceZ)
    }

    private fun composeModelFromRoot(applyLocal: () -> Unit) {
        Matrix.setIdentityM(local, 0)
        applyLocal()
        Matrix.multiplyMM(model, 0, root, 0, local, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)
    }

    // ---- Draws ----

    /**
     * The ball's fill: one opaque sphere, lit.
     *
     * It exists because the passes that used to give the ball a body are off,
     * and what was left — an additive rim glow plus white eyes — is invisible
     * over a light-coloured app. This used to be a dark View behind the whole
     * compact window instead, which was worse in two ways: it was bigger than
     * the sphere, so it read as a frame with a fat face inside it, and being a
     * View it could not squash or hop, so the ball visibly moved around inside
     * its own dead outline.
     *
     * [BallLook.bodyTint] is what decides how much of the mood colour the fill
     * takes on — high means most of it, low means a fifth. It was pinned low back when the body
     * was flat and the only thing keeping the character recognisable was "dark
     * ball, white eyes"; with the mood colour now running through albedo,
     * gradient and specular at once, a mood change is a change of *material*,
     * which is the point.
     */
    private fun drawBody() {
        System.arraycopy(root, 0, model, 0, 16)
        Matrix.scaleM(model, 0, LOOK.bodyScale, LOOK.bodyScale, LOOK.bodyScale)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)

        for (i in 0..2) {
            bodyColor[i] = (LOOK.bodyBase[i] +
                    (state.nowColor[i] - LOOK.bodyBase[i]) * LOOK.bodyTint) * LOOK.bodyAlbedo
        }

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(true)
        shellShader.use()
        GLES20.glUniformMatrix4fv(shellShader.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(shellShader.uniform("uMV"), 1, false, mv, 0)
        GLES20.glUniform3fv(shellShader.uniform("uColor"), 1, bodyColor, 0)
        GLES20.glUniform1f(shellShader.uniform("uGrad"), LOOK.bodyGrad)
        GLES20.glUniform1f(shellShader.uniform("uAmb"), LOOK.bodyAmb)
        GLES20.glUniform1f(shellShader.uniform("uDiff"), LOOK.bodyDiff)
        GLES20.glUniform1f(shellShader.uniform("uGloss"), LOOK.bodyGloss)
        GLES20.glUniform1f(shellShader.uniform("uAlpha"), 1f)
        shell.bind(shellShader.attrib("aPos"), shellShader.attrib("aNormal"))
        shell.draw()
    }

    /**
     * Set up [shellShader] for a part that is made of the *body's* material —
     * same gradient, ambient, diffuse and gloss, only a different colour.
     *
     * Called with [bodyColor] for the tail (it is the ball's own flesh and has
     * to follow the mood with it) and with [BallLook.hornColor] for horn and
     * barb (keratin, which does not).
     */
    private fun useShellWith(color: FloatArray) {
        shellShader.use()
        GLES20.glUniform3fv(shellShader.uniform("uColor"), 1, color, 0)
        GLES20.glUniform1f(shellShader.uniform("uGrad"), LOOK.bodyGrad)
        GLES20.glUniform1f(shellShader.uniform("uAmb"), LOOK.bodyAmb)
        GLES20.glUniform1f(shellShader.uniform("uDiff"), LOOK.bodyDiff)
        GLES20.glUniform1f(shellShader.uniform("uGloss"), LOOK.bodyGloss)
        GLES20.glUniform1f(shellShader.uniform("uAlpha"), 1f)
    }

    private fun drawShellPart(mesh: Mesh) {
        GLES20.glUniformMatrix4fv(shellShader.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(shellShader.uniform("uMV"), 1, false, mv, 0)
        mesh.bind(shellShader.attrib("aPos"), shellShader.attrib("aNormal"))
        mesh.draw()
    }

    /**
     * A pair of horns. Nothing at all if the look has no [BallLook.hornH].
     *
     * **Lit, and parented to [root].** Both matter. Lit, because a horn crosses
     * the body — a flat fill there is a hole punched in the ball rather than a
     * thing standing in front of it, which is why [Horn] bothers to emit real
     * normals. Parented to the root, because horns are part of the silhouette:
     * they have to lean with the head, breathe with the body, sink with the
     * cling and be cut by the same ledge scissor. A horn that held still while
     * the ball hopped would read as scenery the ball is standing behind.
     *
     * Drawn straight after the body and before the face, so it writes depth
     * into the same opaque pass — that is what lets the rim's fresnel be cut
     * correctly where a horn crosses it.
     *
     * The left one is the right one under a negative X scale, which flips
     * winding — harmless, because this ball never enables face culling — and
     * mirrors the normals correctly, because a reflection is its own
     * inverse-transpose.
     */
    private fun drawHorns() {
        val horn = meshes.horn ?: return
        useShellWith(LOOK.hornColor)
        for (side in -1..1 step 2) {
            composeModelFromRoot {
                if (side < 0) Matrix.scaleM(local, 0, -1f, 1f, 1f)
                Matrix.translateM(local, 0, LOOK.hornX, LOOK.hornY, LOOK.hornZ)
                // Negative Z rotation tips +Y toward +X, i.e. outward.
                Matrix.rotateM(local, 0, -LOOK.hornTilt, 0f, 0f, 1f)
            }
            drawShellPart(horn)
        }
    }

    /**
     * A pair of ears. Nothing if the look has no [BallLook.earH].
     *
     * Parented to [root] for the same reasons as the horns: they are part of the
     * silhouette and have to lean, breathe and cling with the body. Drawn with
     * the lit shell shader and made of the body's flesh, unlike a horn's
     * keratin — an ear is skin.
     *
     * **[BallLook.earTilt] is the whole read, and it is not one shape.** A pig's
     * ear hangs down and out (well past a right angle, with [BallLook.earBend]
     * curling the tip further); a *clever* ball wants the opposite — an upright
     * flap whose tip clears its own outline, which is what makes the silhouette
     * read as alert rather than as a sphere. Both come out of the same
     * [Ear.build]; the angle is the entire difference.
     *
     * **An upright ear is not a horn**, even though [drawHorns] leans its cones
     * outward by much the same number of degrees. Three things separate them and
     * all three are visible at thumbnail size: a horn is a *tapered point* where
     * an ear is a broad rounded flap (see `gl/Ear` — a stack of elliptical rings
     * closed by a quarter-ellipse tip, against `gl/Horn`'s taper to a point); a
     * horn is bone-pale while an ear is the body's own colour; and no look in
     * [BallLooks.ALL] carries both. Keep it that way — a look with upright ears
     * *and* horns is a silhouette nobody can parse, which is the failure this
     * note exists to prevent.
     *
     * The colour is [BallLook.earShade] times the live body colour rather than
     * the body colour itself, which matters most on exactly the pale looks that
     * want upright ears: an ear grows *past* the dark outline, so beyond the
     * silhouette there is no dark line holding it off the app behind the ball.
     */
    private fun drawEars() {
        val ear = meshes.ear ?: return
        for (i in 0..2) earColor[i] = bodyColor[i] * LOOK.earShade
        useShellWith(earColor)
        for (side in -1..1 step 2) {
            composeModelFromRoot {
                if (side < 0) Matrix.scaleM(local, 0, -1f, 1f, 1f)
                Matrix.translateM(local, 0, LOOK.earX, LOOK.earY, LOOK.earZ)
                Matrix.rotateM(local, 0, -LOOK.earTilt, 0f, 0f, 1f)
            }
            drawShellPart(ear)
        }
    }

    /**
     * A tapering chain of blobs ending in a barb. Nothing if [BallLook.tailLen]
     * is zero.
     *
     * A chain of spheres rather than a swept tube because the ball already owns
     * a unit blob ([handMesh]) and the tail is ~12 px wide on screen: at that
     * size the difference between a chain at this spacing and a real tube is
     * below one pixel, and a swept tube would be a whole mesh generator with a
     * per-look build.
     *
     * The walk is a constant-curvature arc — fixed step, fixed turn per step —
     * which is the shape a devil's tail is drawn as: out, down, and hooking
     * back up. The first blob is placed *inside* the body silhouette on
     * purpose, so the tail appears to grow out of the ball rather than to be
     * docked onto it.
     *
     * Parented to [root] for the same reasons as the horns.
     */
    private fun drawTail() {
        if (LOOK.tailLen <= 0f) return
        useShellWith(bodyColor)

        val step = LOOK.tailLen / TAIL_BLOBS
        val turn = TAIL_SWEEP / TAIL_BLOBS
        var x = LOOK.tailX
        var y = LOOK.tailY
        var ang = TAIL_START
        for (i in 0 until TAIL_BLOBS) {
            val r = LOOK.tailR * (1f - TAIL_TAPER * i / (TAIL_BLOBS - 1f))
            composeModelFromRoot {
                Matrix.translateM(local, 0, x, y, 0f)
                Matrix.scaleM(local, 0, r, r, r)
            }
            drawShellPart(handMesh)
            x += cos(ang) * step
            y += sin(ang) * step
            ang += turn
        }

        val barb = meshes.barb ?: return
        useShellWith(LOOK.hornColor)
        composeModelFromRoot {
            Matrix.translateM(local, 0, x, y, 0f)
            // The mesh grows +Y; ang is measured from +X, hence the quarter turn.
            Matrix.rotateM(local, 0, ang * DEG - 90f, 0f, 0f, 1f)
        }
        drawShellPart(barb)
    }

    /**
     * A rounded muzzle protruding from the face. Nothing if the look has no
     * [BallLook.snoutRx].
     *
     * Drawn lit with the body's own material, so it reads as a bump rather than
     * a sticker: the vertical gradient and the specular land on it differently
     * than on the body sphere. It is parented to the *face*, not the root,
     * because the snout is a facial feature and must ride the same plane the
     * eyes and mouth do.
     *
     * The unit sphere is scaled into an ellipsoid wider than it is tall and
     * protruding by [BallLook.snoutRz]; its centre sits on the face plane, so
     * the back half is buried in the body and the front half sticks out.
     *
     * Drawn after the body and before [drawFace], so it writes depth into the
     * opaque pass and the nostrils — drawn flat on top of it inside [drawFace] —
     * land over the bump rather than behind it.
     *
     * The colour is [BallLook.snoutShade] times the live body colour, exactly as
     * [drawEars] does for the ears, and at the default 1 that is the body colour
     * itself. The shading is still the shell shader's: a muzzle that is a *bump*
     * and a muzzle that is a lighter *patch* are different animals, and this only
     * changes the second, so the vertical gradient and the specular keep landing
     * on it the way they do on a protrusion.
     */
    private fun drawSnout() {
        val snout = meshes.snout ?: return
        for (i in 0..2) snoutColor[i] = bodyColor[i] * LOOK.snoutShade
        useShellWith(snoutColor)
        Matrix.setIdentityM(local, 0)
        Matrix.translateM(local, 0, LOOK.snoutX, LOOK.snoutY, 0f)
        Matrix.scaleM(local, 0, LOOK.snoutRx, LOOK.snoutRy, LOOK.snoutRz)
        Matrix.multiplyMM(model, 0, face, 0, local, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)
        drawShellPart(snout)
    }

    private fun drawFace() {
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(true)
        solidShader.use()
        val aPos = solidShader.attrib("aPos")
        // No default colour is set here, on purpose.
        //
        // uColor has no attribute, so it is *shared state that survives across
        // draw calls*. A default at the top of this pass looks harmless and is
        // the opposite: it silently supplies a colour to any element that
        // forgets its own, and it hides that omission from
        // `tools/check_look_sync.py`, which requires each draw to set its own
        // colour within its own branch. The `^` eye was exactly this bug —
        // it inherited the default white and drew white arcs whenever the round
        // eye didn't run. Every element below sets a colour before it draws.
        GLES20.glUniform1f(solidShader.uniform("uAlpha"), 1f)

        // Round eye and `^` eye cross-fade by *size*, not by alpha. The face is
        // drawn first, onto a cleared transparent buffer, so a half-faded white
        // shape is a visible ghost — whereas one scaled to zero simply isn't
        // there. The in-between (a flattening dot under a growing arch) also
        // happens to be what a squint looks like.
        val round = state.eyeYScale * (1f - state.eyeArc)
        val arc = state.eyeArc

        if (round > 0.01f) {
            GLES20.glUniform3fv(solidShader.uniform("uColor"), 1, LOOK.eyeColor, 0)
            setLocalFromFace(state.eyeLX, state.eyeLY, state.eyeLRotZ, round, pivotY = EYE_LID_PIVOT)
            GLES20.glUniformMatrix4fv(solidShader.uniform("uMVP"), 1, false, mvp, 0)
            meshes.eye.bind(aPos)
            meshes.eye.draw()

            setLocalFromFace(state.eyeRX, state.eyeRY, state.eyeRRotZ, round, pivotY = EYE_LID_PIVOT)
            GLES20.glUniformMatrix4fv(solidShader.uniform("uMVP"), 1, false, mvp, 0)
            meshes.eye.bind(aPos)
            meshes.eye.draw()
        }

        // The pupil and its catchlight, which are what turn a white slot into
        // something that is looking at you. Gated on the same aperture the round
        // eye is, plus a floor: under a `^ ^` squint the eye is a line, and a
        // pupil left hanging beneath it would be the one part of the face that
        // doesn't blink.
        //
        // Which of the two a look wants is [BallLook.pupilR] / [BallLook.glintR]:
        // a dark ball with white eyes needs a pupil drawn inside them; a pale
        // ball has nothing for a white eye to contrast against, so its eye *is*
        // the dark shape and only the glint goes on top.
        if (round > PUPIL_MIN_APERTURE && arc < ARC_EYE_AT &&
            (LOOK.pupilR > 0f || LOOK.glintR > 0f)
        ) {
            if (LOOK.pupilR > 0f) {
                for (i in 0..2) {
                    pupilColor[i] = state.nowColor[i] * PUPIL_TINT + PUPIL_LIFT[i]
                }
            }
            drawEyeContents(state.eyeLX, state.eyeLY, state.eyeLRotZ, round)
            drawEyeContents(state.eyeRX, state.eyeRY, state.eyeRRotZ, round)
        }

        if (arc > 0.01f) {
            // The `^` eye carries its own colour, and this line is load-bearing.
            //
            // It used to inherit whatever the previous element had left in
            // uColor, which is the pass default (white) on any frame where the
            // round eye didn't draw — and on SLEEPING it never draws: its
            // aperture works out to 0.06 × 0.15 = 0.009, just under the 0.01
            // threshold above. The result was two *white* arcs on a pale ball,
            // while the lab showed dark ones, because the lab sets this colour
            // per element. A uniform with no attribute is shared state; the
            // only safe rule in this pass is that every draw sets its own.
            GLES20.glUniform3fv(solidShader.uniform("uColor"), 1, LOOK.eyeColor, 0)
            val h = arc * state.lid
            setLocalFromFace(
                state.eyeLX, state.eyeLY - EYE_ARC_DROP,
                EYE_ARC_ROT_Z + state.eyeLRotZ, h, arc,
            )
            GLES20.glUniformMatrix4fv(solidShader.uniform("uMVP"), 1, false, mvp, 0)
            eyeArcMesh.bind(aPos)
            eyeArcMesh.draw()

            setLocalFromFace(
                state.eyeRX, state.eyeRY - EYE_ARC_DROP,
                EYE_ARC_ROT_Z + state.eyeRRotZ, h, arc,
            )
            GLES20.glUniformMatrix4fv(solidShader.uniform("uMVP"), 1, false, mvp, 0)
            eyeArcMesh.bind(aPos)
            eyeArcMesh.draw()
        }

        // Mouth — the mood colour, or a deep shade of it on a pale ball, where
        // the full-strength colour is too close in value to the body to read.
        for (i in 0..2) {
            mouthColor[i] = if (LOOK.mouthDeep) state.nowColor[i] * MOUTH_DEEP else state.nowColor[i]
        }
        setLocalFromFace(0f, state.mouthY, state.mouthRotZ, state.mouthScaleY)
        GLES20.glUniformMatrix4fv(solidShader.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform3fv(solidShader.uniform("uColor"), 1, mouthColor, 0)
        GLES20.glUniform1f(solidShader.uniform("uAlpha"), 1f)
        meshes.mouth.bind(aPos)
        meshes.mouth.draw()

        drawTongue()
        drawTeeth(aPos)
        drawBuckTeeth()
        drawNostrils()
        drawBrows(aPos)
        drawBubble()
    }

    /**
     * Where a point at parameter [u] on the mouth's own circle ends up once the
     * mouth's live transform has been applied, and which way is *out* there.
     *
     * The mouth mesh is a half-torus of radius [BallLook.mouthR] built flat in
     * the XY plane, and the renderer never draws it as built: it squashes it in
     * Y by [EmotionState.mouthScaleY] and then turns it by
     * [EmotionState.mouthRotZ], which is what makes the arch a smile and what
     * makes the smile deepen. Anything that has to sit *on* that curve — every
     * tooth, the tongue — has to go through the same two steps by hand, because
     * it is placed with its own matrix rather than riding the mouth's.
     *
     * Note the corner mapping: the mesh is built as the *upper* half of the
     * mouth circle and the rest transform turns it by π, so u near 0 lands at
     * the **left** corner and u near π at the right — the opposite of what
     * reading the mesh alone would suggest.
     *
     * The outward direction is the **ellipse normal**, not the radial
     * direction, and at rest those are nowhere near each other: the mouth is
     * squashed to about a third of its height, where the radial direction at a
     * mid-arc tooth points out and sideways while the surface it is standing on
     * runs almost flat. Teeth placed on the radial would lie over like a row of
     * dominoes. For `x²/a² + y²/b² = 1` the normal at parameter u is
     * `(cos u / a, sin u / b)`, which with `a = R`, `b = R·sY` is
     * `(cos u · sY, sin u)` up to scale.
     *
     * Results land in [mouthPt] as `x, y, nx, ny` — one scratch array rather
     * than an allocation per tooth per frame.
     */
    private fun mouthPointAt(u: Float) {
        val sY = state.mouthScaleY
        val th = state.mouthRotZ
        val cT = cos(th)
        val sT = sin(th)
        val cU = cos(u)
        val sU = sin(u)

        val px = LOOK.mouthR * cU
        val py = LOOK.mouthR * sU * sY
        mouthPt[0] = px * cT - py * sT
        mouthPt[1] = state.mouthY + px * sT + py * cT

        val nx = cU * sY
        val ny = sU
        val inv = 1f / sqrt(nx * nx + ny * ny)
        mouthPt[2] = (nx * cT - ny * sT) * inv
        mouthPt[3] = (nx * sT + ny * cT) * inv
    }

    /**
     * A zigzag of triangles along the grin. Nothing if the look has no
     * [BallLook.toothCount].
     *
     * Alternating outward and inward is the whole trick: consecutive teeth
     * interlock across the lip line, which is what the top and bottom rows of a
     * cartoon maw look like when the mouth is *shut*. Drawing two separate rows
     * would need the mouth to actually open, and this ball's mouth is a curve
     * rather than a cavity.
     *
     * Flat-shaded, unlike the horns, and that is not an oversight: a tooth is a
     * pale triangle a few pixels wide sitting on top of a mouth, so it is read
     * entirely as a silhouette and lighting it would only mute the one edge
     * that carries it. The horns have to be lit because they cross the body,
     * where a flat fill is a hole rather than a shape.
     *
     * The ends of the arc are inset by [TOOTH_PAD]: a tooth exactly on the
     * corner of the mouth points sideways, off the face, and reads as a chip
     * rather than a tooth.
     *
     * They are spaced evenly **across the grin**, not evenly in the mouth
     * circle's own parameter, and the two are nowhere near the same thing. The
     * mouth is squashed to about a third of its height, so equal steps in `u`
     * cover almost no width near the corners and a great deal of it in the
     * middle — which is what the first build drew: a clump of overlapping teeth
     * at each end of a sparse middle. Stepping `cos u` linearly instead makes
     * the *projected* spacing constant, and [BallLook.toothR] is then chosen so
     * neighbours just touch.
     */
    private fun drawTeeth(aPos: Int) {
        val tooth = meshes.tooth ?: return
        val n = LOOK.toothCount
        if (n < 2) return
        GLES20.glUniform3fv(solidShader.uniform("uColor"), 1, LOOK.toothColor, 0)
        GLES20.glUniform1f(solidShader.uniform("uAlpha"), 1f)
        val cosPad = cos(TOOTH_PAD)
        val half = PI.toFloat() / 2f
        for (i in 0 until n) {
            val t = i / (n - 1f)
            mouthPointAt(acos(cosPad * (1f - 2f * t)))
            // The mesh grows +Y, so a tooth aimed along a direction `a` is that
            // direction turned back by a quarter turn. Even teeth take the
            // outward normal, odd ones its opposite.
            val a = atan2(mouthPt[3], mouthPt[2])
            setLocalFromFace(
                mouthPt[0], mouthPt[1],
                if (i % 2 == 0) a - half else a + half,
                scaleY = 1f, zOff = LOOK.mouthTube + 0.020f,
            )
            GLES20.glUniformMatrix4fv(solidShader.uniform("uMVP"), 1, false, mvp, 0)
            tooth.bind(aPos)
            tooth.draw()
        }
    }

    /**
     * Two front teeth in the middle of the mouth. Nothing if the look has no
     * [BallLook.buckH].
     *
     * **Drawn over the lip, not peeking out from under it**, and that is the
     * decision the rest of the method is arranged around. A rodent's incisors
     * tucked behind the mouth line would be two pale slivers under a curve,
     * which at the size this ball is drawn is indistinguishable from a highlight
     * on the lip; interrupting the line is what makes them teeth. So they go in
     * front at [BallLook.mouthTube] plus a margin — ahead of the lip's own front
     * surface, which is a tube radius in front of the face plane — and the pair
     * is pushed only a third of its height down the mouth's normal, so the top
     * of each tooth is still inside the lip's own stroke.
     *
     * They are placed through [mouthPointAt] rather than from
     * [BallLook.mouthY], which is what makes them ride the mouth: the smile
     * deepens, the mood skews it, [BallLook.mouthTilt] puts a permanent roll in
     * it, and the teeth stay in the middle of whatever shape that leaves. The
     * tangent is the normal turned a quarter turn, so the pair stays square to
     * the lip instead of level with the screen.
     *
     * Flat-shaded discs, like every other face element and unlike the horns —
     * see [drawTeeth] for why a tooth is read as a silhouette and lighting it
     * would only mute the edge that carries it.
     */
    private fun drawBuckTeeth() {
        val h = LOOK.buckH
        if (h <= 0f) return
        mouthPointAt(PI.toFloat() / 2f)
        val nx = mouthPt[2]
        val ny = mouthPt[3]
        // The lip's own direction, as the normal turned a quarter turn.
        val tx = -ny
        val ty = nx
        val cx = mouthPt[0] + nx * h * BUCK_DROP
        val cy = mouthPt[1] + ny * h * BUCK_DROP
        // The mesh grows +Y, so standing a tooth along the normal means turning
        // that direction back by a quarter turn — same correction as [drawTeeth].
        val rot = atan2(ny, nx) - PI.toFloat() / 2f
        val w = LOOK.buckW
        for (side in -1..1 step 2) {
            drawDot(
                cx + tx * w * side,
                cy + ty * w * side,
                rot,
                lidScale = 1f,
                radius = h,
                z = LOOK.mouthTube + 0.016f,
                color = LOOK.buckColor,
                aspectX = w / h,
            )
        }
    }

    /**
     * Two dark nostril dots on the front of the muzzle. Nothing if the look has
     * no [BallLook.nostrilR].
     *
     * Drawn flat in the face pass, after [drawSnout] has already laid down the
     * lit muzzle, so they sit *on* the bump rather than behind it.
     *
     * ## Where the Z comes from, and why it is not a fraction of [BallLook.snoutRz]
     *
     * It used to be `snoutRz × 0.82`, i.e. "most of the way out", and that was
     * wrong in a way that only shows up on a muzzle whose nostrils are near its
     * middle. The muzzle is an **ellipsoid centred on the face plane**, so the
     * surface the dot has to clear is not a constant — it is
     * `snoutRz · √(1 − (x/snoutRx)²)`, highest at the muzzle's axis and falling
     * away toward the rim. Anything flat under that curve is depth-culled,
     * because the snout is drawn in the opaque pass and writes depth.
     *
     * Measured on the tablet before the fix:
     *
     *  - [BAJIE] — nostrils at x = ±0.095 on a 0.215-wide muzzle, so the surface
     *    there is 0.148 against a dot front face at 0.139. Each nostril rendered
     *    as an outward-facing **crescent**: only the sliver near the rim, where
     *    the ellipsoid has finally dropped behind the dot's plane, survived.
     *    That had been shipping unnoticed, read as a stylised pig nostril.
     *  - [BEAR] — nostrils at x = ±0.028, which puts the whole disc under the
     *    *apex*. Nothing survived at all: a clean tan muzzle with no nose on it.
     *
     * So the clearance is computed at the point of the dot's own footprint
     * **nearest the muzzle's axis**, `max(0, |nostrilX| − r)`, which is where the
     * ellipsoid under the disc is highest — a footprint straddling the axis
     * (the bear) correctly gets the full [BallLook.snoutRz]. Y needs no such term
     * because the dots sit at `snoutY`, the ellipsoid's own y-apex, and every
     * other y is lower.
     *
     * [NOSTRIL_LIFT] on top of that is the usual flat-element clearance. The dot
     * is camera-facing and the stand-off is under 0.02 world units even in the
     * bear's worst case, so it reads as a hole in the bump rather than a disc
     * hovering in front of one.
     *
     * The colour is the live [bodyColor] darkened, not a fixed black: the body
     * takes the mood's tint, and a fixed near-black would be right in CALM and a
     * smudge in TENSE — the same reasoning as [BallLook.browShade].
     */
    private fun drawNostrils() {
        val r = LOOK.nostrilR
        if (r <= 0f) return
        for (i in 0..2) nostrilColor[i] = bodyColor[i] * NOSTRIL_DARK
        val rx = LOOK.snoutRx
        val inner = (abs(LOOK.nostrilX) - r).coerceAtLeast(0f)
        val k = if (rx > 0f) (inner / rx).coerceAtMost(1f) else 1f
        val surface = LOOK.snoutRz * sqrt((1f - k * k).coerceAtLeast(0f))
        val z = surface + r * DISC_THICKNESS + NOSTRIL_LIFT
        for (side in -1..1 step 2) {
            drawDot(
                LOOK.snoutX + LOOK.nostrilX * side,
                LOOK.snoutY,
                0f,
                lidScale = 1f,
                radius = r,
                z = z,
                color = nostrilColor,
            )
        }
    }

    /**
     * The tongue, hanging out of the grin. Nothing if the look has no
     * [BallLook.tongueR].
     *
     * Pushed out along the mouth's own normal by half its radius, so it reads
     * as coming from behind the lip rather than as a dot parked on it, and
     * drawn *before* the teeth at a slightly shallower Z so they close over it.
     * Taller than it is wide, because a round one is a nose.
     */
    private fun drawTongue() {
        val r = LOOK.tongueR
        if (r <= 0f) return
        mouthPointAt(PI.toFloat() / 2f + LOOK.tongueSkew)
        drawDot(
            mouthPt[0] + mouthPt[2] * r * 0.5f,
            mouthPt[1] + mouthPt[3] * r * 0.5f,
            atan2(mouthPt[3], mouthPt[2]) - PI.toFloat() / 2f,
            lidScale = 1f,
            radius = r,
            z = LOOK.mouthTube + 0.008f,
            color = LOOK.tongueColor,
            aspectX = 0.82f,
        )
    }

    /**
     * Two angled brows above the eyes. Nothing if the look has no
     * [BallLook.browW] — which is now every look that wants the *glare* read,
     * since [IMP] gets it from [BallLook.eyeCut] instead. See below; the two
     * are alternatives, not a sequence.
     *
     * A flattened unit sphere rather than a bar: a scaled sphere is a lens,
     * which tapers at both ends, which is what a brow looks like.
     *
     * With [BallLook.browShade] set the mark is derived from the live
     * [bodyColor] rather than stored, because the body takes the mood's tint and
     * a fixed near-black would show as a smudge the moment the mood moved. Which
     * *kind* of mark it is depends entirely on where the look puts it:
     *
     *  - Above the eye and darker than the body ([BEAR], 0.42) it is an ordinary
     *    eyebrow, which is the only thing a brown body can have — [eyeColor] is
     *    white there, by the light-on-dark rule, so the default branch below
     *    would draw two white caterpillars on a bear's forehead. That is what it
     *    did on the first build of that look.
     *  - Overlapping the eye and *lighter* than the body it is not a brow at all
     *    but a **wedge of body colour laid over the top inner corner**, and the
     *    white that survives underneath is a slanted almond — a slanted eye is a
     *    glare where a bar above a round eye is only an eyebrow. That is the
     *    reading [IMP] used to get here; see [BallLook.browShade].
     *
     * **This draw is flat and the face behind it is lit, so the multiplier is
     * not what it looks like.** Measured against the ink body ([IMP]) with the
     * shell shader's own numbers — the body at the brow's latitude renders at
     * `0.82 × albedo`, the brow at `shade × albedo`:
     *
     * | shade | brow, in 8 bit | vs the body | vs the white eye |
     * |-------|----------------|-------------|------------------|
     * | 1.05  | (32, 33, 50)   | 1.28×       | 7.4× under       |
     * | 1.50  | (46, 47, 71)   | 1.83×       | 5.2× under       |
     * | 2.00  | (61, 63, 95)   | 2.44×       | 3.9× under       |
     * | 2.50  | (77, 79, 118)  | 3.05×       | 3.1× under       |
     * | 3.00  | (92, 95, 142)  | 3.66×       | 2.6× under       |
     *
     * Only one row works for both jobs. The brow has to clear the body *and*
     * stay under the eye, and the eye is white, so the answer is the geometric
     * midpoint of the two — ≈0.32 linear on an ink body, i.e. 2.5. The first
     * number tried was 1.05, which is 1.28× the body: a ~7/255 difference,
     * which is why the brow was reported as invisible.
     *
     * The whole table is the reason this look no longer uses the field. A
     * *range* that has to hold under every mood, to serve a mark whose two ends
     * have different jobs, is a fragile way to get a glare — and it is fragile
     * in the worst way, silently. [BallLook.eyeCut] gets the same read out of
     * the eye's own geometry, where the flat edge is the highest-contrast line
     * there is by construction.
     *
     * None of that fragility applies to the *downward* use. [BEAR]'s brow sits
     * clear of the eye — `browY` 0.168 against an eye top at 0.112, so its lower
     * edge misses by 0.010 — which means it has only one job: beat the body. The
     * lit body at that latitude is `bodyColor × 0.89`, so 0.42 is a touch over
     * 2× under it in every channel, and the mood multiplies both sides equally.
     * One number, one job, no range.
     *
     * The tilt is [BallLook.browTilt] (the look's resting scowl) plus the
     * mood's own [EmotionState.brow], so a look that starts angry can still get
     * angrier or be surprised out of it. It is mirrored per side because
     * "inner end down" is a different sign of rotation on the left and the
     * right — the one thing that, got wrong, turns a scowl into a worry.
     *
     * They ride on the live eye Y, not on the look's resting one, so a blink or
     * a glance carries them along instead of leaving them pinned to the
     * forehead.
     */
    private fun drawBrows(aPos: Int) {
        if (LOOK.browW <= 0f) return
        val color = if (LOOK.browShade > 0f) {
            for (i in 0..2) browColor[i] = bodyColor[i] * LOOK.browShade
            browColor
        } else {
            LOOK.eyeColor
        }
        GLES20.glUniform3fv(solidShader.uniform("uColor"), 1, color, 0)
        GLES20.glUniform1f(solidShader.uniform("uAlpha"), 1f)
        val tilt = LOOK.browTilt / DEG + state.brow * BROW_MOOD_TILT
        for (side in -1..1 step 2) {
            val cx = if (side < 0) state.eyeLX else state.eyeRX
            val cy = (if (side < 0) state.eyeLY else state.eyeRY) + LOOK.browY
            setLocalFromFace(
                cx, cy, tilt * side,
                scaleY = LOOK.browH,
                scaleX = LOOK.browW,
                scaleZ = LOOK.browH * DISC_THICKNESS,
                zOff = eyeFrontZ,
            )
            GLES20.glUniformMatrix4fv(solidShader.uniform("uMVP"), 1, false, mvp, 0)
            dotMesh.bind(aPos)
            dotMesh.draw()
        }
    }

    /**
     * 鼻涕泡：一颗**透明的**水滴，不是一颗白球。
     *
     * 第一版这里是两个实心圆片（浅色压在深色上），做出来是一坨白 —— 一个实心
     * 的浅色圆放在浅色球上，除了"一颗球"读不出任何别的东西。水滴之所以是水滴，
     * 全靠三件跟"实心"相反的事，都在 [BUBBLE_FS] 里：
     *
     *   * **背面被 discard**。泡是一整颗球不是一片，前后两个面会盖到同一个像素
     *     上，不裁掉背面的话轮廓那一圈会叠成两倍厚。
     *   * **中间是透的**。只留轮廓上一圈菲涅尔（[BallLook.bubblePow]）加
     *     [BallLook.bubbleInner] 那点薄雾，球体自己从中间透出来。
     *   * **一个高光点**，落在左上 —— 和球的主光同一个方向，同一个理由：
     *     高光落在别处会读成"它看向了别处"。
     *
     * 单独的着色器而不是复用 SOLID：SOLID 是 `vec4(uColor, uAlpha)`，给它一颗
     * 不透明的球只能得到那颗白球。
     *
     * 画在嘴之后：它贴着鼻子，先画嘴再压上泡，就不会出现"泡被嘴切掉一块"。
     */
    private fun drawBubble() {
        val a = state.bubble
        if (a < 0.01f) return
        val r = LOOK.bubbleR * state.bubbleScale * a

        System.arraycopy(face, 0, model, 0, 16)
        Matrix.translateM(model, 0, LOOK.bubbleX, LOOK.bubbleY, 0f)
        Matrix.scaleM(model, 0, r, r, r)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)

        // 透明，所以不写深度；但深度**测试**照旧开着 —— 正是它挡住球体的
        // 后半颗，不让它从身体里透出来。画完把 mask 恢复，因为上面那一段
        // 是按"面是不透明的"写的。
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        bubbleShader.use()
        GLES20.glUniformMatrix4fv(bubbleShader.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(bubbleShader.uniform("uMV"), 1, false, mv, 0)
        GLES20.glUniform3fv(bubbleShader.uniform("uColor"), 1, LOOK.bubbleColor, 0)
        GLES20.glUniform1f(bubbleShader.uniform("uAlpha"), a)
        GLES20.glUniform1f(bubbleShader.uniform("uPow"), LOOK.bubblePow)
        GLES20.glUniform1f(bubbleShader.uniform("uShine"), LOOK.bubbleShine)
        GLES20.glUniform1f(bubbleShader.uniform("uInner"), LOOK.bubbleInner)
        bubbleSphere.bind(bubbleShader.attrib("aPos"), bubbleShader.attrib("aNormal"))
        bubbleSphere.draw()
        GLES20.glDepthMask(true)
    }

    private fun setLocalFromFace(
        x: Float,
        y: Float,
        rotZ: Float,
        scaleY: Float,
        scaleX: Float = 1f,
        pivotY: Float = 0f,
        scaleZ: Float = 1f,
        zOff: Float = 0f,
    ) {
        Matrix.setIdentityM(local, 0)
        // Scaling about a pivot below the element instead of through its middle
        // is what makes a closing eye read as a lid coming down: the bottom edge
        // stays put and the top descends to meet it.
        Matrix.translateM(local, 0, x, y + pivotY * (1f - scaleY), zOff)
        Matrix.rotateM(local, 0, rotZ * DEG, 0f, 0f, 1f)
        Matrix.scaleM(local, 0, scaleX, scaleY, scaleZ)
        Matrix.multiplyMM(model, 0, face, 0, local, 0)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)
    }

    /**
     * One round element of the face layer: a pupil, or the glint inside it.
     *
     * Three things have to be right at once or the eye turns into a smudge:
     *
     *  - `pivotY` is 0, *not* [EYE_LID_PIVOT]. The pivot compensation exists to
     *    keep a closing eye's lower edge still, and it moves an element by
     *    `pivot * (1 - scaleY)`. For the eye, scaleY hovers near 1 and that
     *    term is nothing; for a pupil whose scaleY *is* its own radius, the same
     *    expression is a 0.12-unit shove down the face, which lands the pupil on
     *    the cheek.
     *  - `scaleZ` is a thin fraction of the radius — these are discs. Left at 1
     *    a unit sphere stays a 2-unit-tall, 2-unit-deep ball and swallows the
     *    whole frame.
     *  - `zOff` puts it in front of [eyeFrontZ], because the eye is a capsule
     *    with its own z-radius and would otherwise occlude its own pupil.
     *
     * `lidScale` is the eye's current vertical aperture, so the pupil and its
     * glint blink along with the eye instead of floating over a closed lid.
     *
     * `aspectX` squashes it horizontally — 1 is round, and anything below turns
     * the pupil into a vertical slit. It is on the *width* only, so the slit
     * still closes with the lid like a round pupil does.
     */
    private fun drawDot(
        x: Float,
        y: Float,
        rotZ: Float,
        lidScale: Float,
        radius: Float,
        z: Float,
        color: FloatArray,
        aspectX: Float = 1f,
    ) {
        setLocalFromFace(
            x, y, rotZ,
            scaleY = radius * lidScale,
            scaleX = radius * aspectX,
            pivotY = 0f,
            scaleZ = radius * DISC_THICKNESS,
            zOff = z,
        )
        GLES20.glUniformMatrix4fv(solidShader.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform3fv(solidShader.uniform("uColor"), 1, color, 0)
        dotMesh.bind(solidShader.attrib("aPos"))
        dotMesh.draw()
    }

    /**
     * One eye's contents: optionally the pupil, then the glint.
     *
     * [pupilColor] is a per-frame scratch array shared by both eyes, and is only
     * filled when [BallLook.pupilR] is non-zero — see the call site.
     *
     * The glint sits up and inboard of whatever it is sitting on, which is where
     * a window is in a room: the light the ball is standing in comes from above
     * and slightly to its left, so that is the side it lands on. Getting this
     * wrong is not subtle — a glint on the wrong side reads as the ball having
     * looked away.
     */
    private fun drawEyeContents(cx: Float, cy: Float, rotZ: Float, lidScale: Float) {
        val pr = LOOK.pupilR
        if (pr > 0f) {
            drawDot(
                cx - pr * 0.12f, cy - pr * 0.20f, rotZ, lidScale,
                pr, eyeFrontZ, pupilColor, LOOK.pupilAspect,
            )
        }
        val gr = LOOK.glintR
        if (gr <= 0f) return
        // Anchored to the pupil when there is one, to the eye itself otherwise —
        // and the offset scales with whichever it is, because the two shapes are
        // very different sizes: a glint placed as a fixed fraction of the *eye*
        // lands outside a small pupil, and one placed as a fraction of a small
        // pupil barely moves off the centre of a bare eye.
        if (pr > 0f) {
            drawDot(
                cx - pr * 0.34f, cy + pr * 0.26f, rotZ, lidScale,
                gr, eyeFrontZ + GLINT_Z_STEP, WHITE,
            )
        } else {
            drawDot(
                cx - LOOK.eyeRadius * 0.36f, cy + LOOK.eyeRadius * 0.40f, rotZ, lidScale,
                gr, eyeFrontZ + GLINT_Z_STEP, WHITE,
            )
        }
    }

    /**
     * Two soft cheek blobs. Point sprites rather than small spheres because the
     * point shader already has a radial smoothstep falloff — a flat-shaded
     * sphere would be a pink disc with a hard edge, which is a sticker, not a
     * blush.
     */
    private fun drawBlush() {
        val a = state.blush
        if (a < 0.01f) return
        val d = blush.data
        for (n in 0..1) {
            val j = n * 6
            d[j] = if (n == 0) -LOOK.blushX else LOOK.blushX
            d[j + 1] = LOOK.blushY
            d[j + 2] = -0.02f
            d[j + 3] = LOOK.blushColor[0]
            d[j + 4] = LOOK.blushColor[1]
            d[j + 5] = LOOK.blushColor[2]
        }
        blush.upload()

        Matrix.multiplyMM(mv, 0, view, 0, face, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)

        // Additive on a dark body reads as a warm patch; on a pale one it clips
        // straight to white and the warmth vanishes, so pale looks blend
        // normally instead.
        GLES20.glBlendFunc(
            GLES20.GL_SRC_ALPHA,
            if (LOOK.blushBlendNormal) GLES20.GL_ONE_MINUS_SRC_ALPHA else GLES20.GL_ONE,
        )
        GLES20.glDepthMask(false)
        pointsShader.use()
        GLES20.glUniformMatrix4fv(pointsShader.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(pointsShader.uniform("uMV"), 1, false, mv, 0)
        GLES20.glUniform1f(pointsShader.uniform("uPointFactor"), pointFactor)
        GLES20.glUniform1f(pointsShader.uniform("uSize"), LOOK.blushSize)
        GLES20.glUniform1f(pointsShader.uniform("uAlpha"), a * LOOK.blushAlpha)
        blush.bind(pointsShader.attrib("aPos"), pointsShader.attrib("aColor"))
        blush.draw()
    }

    /**
     * Two paws hooked over the bottom screen edge, drawn only while the ball is
     * hanging off it.
     *
     * Deliberately **not** parented to [root]: the paws hold still on the edge
     * while the body slips down and hauls back up underneath them. That gap is
     * the entire reason the slip reads as losing grip rather than as the ball
     * bobbing — a paw that sags along with the body is not holding anything.
     *
     * Each paw is a splayed palm plus three toes, which is more geometry than a
     * single blob needs to be and is the difference between "little hands" and
     * "two dots". They straddle [HAND_Y], just above the ledge, and
     * [clipToLedge] cuts their bottoms off — you see the top of a paw resting
     * on a ledge, which is what makes the ledge exist at all.
     *
     * Mood colour, like the mouth. Half the ball is below the edge while it
     * clings and the mouth goes down with it, so without this the mood would
     * have nothing but the rim left to show in.
     */
    private fun drawHands() {
        val c = state.cling
        if (c < 0.02f) return
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(true)
        solidShader.use()
        val uMVP = solidShader.uniform("uMVP")
        GLES20.glUniform3fv(solidShader.uniform("uColor"), 1, state.nowColor, 0)
        GLES20.glUniform1f(solidShader.uniform("uAlpha"), 1f)
        handMesh.bind(solidShader.attrib("aPos"))

        // Bearing weight splays the palm and flattens it, and the toes spread
        // and curl down over the lip — all peaking on the haul, where the body
        // is actually pulling against them.
        val g = state.grip
        val palmW = HAND_W * (1f + g * 0.2f) * c
        val palmH = HAND_H * (1f - g * 0.22f) * c
        for (side in -1..1 step 2) {
            val cx = side * (HAND_X + g * 0.03f)
            blob(uMVP, cx, HAND_Y, palmW, palmH)
            for (n in -1..1) {
                blob(
                    uMVP,
                    cx + n * HAND_TOE_GAP * (1f + g * 0.22f),
                    HAND_Y + HAND_TOE_RISE - g * 0.018f,
                    HAND_TOE * c, HAND_TOE * c,
                )
            }
        }
    }

    /** One paw part, in world space — no [root], see [drawHands]. */
    private fun blob(uMVP: Int, x: Float, y: Float, sx: Float, sy: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, x, y, HAND_Z)
        Matrix.scaleM(model, 0, sx, sy, HAND_TOE)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
        handMesh.draw()
    }

    private fun drawRim() {
        System.arraycopy(root, 0, model, 0, 16)
        Matrix.scaleM(model, 0, RIM, RIM, RIM)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)

        GLES20.glDepthMask(false)
        rimShader.use()
        GLES20.glUniformMatrix4fv(rimShader.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(rimShader.uniform("uMV"), 1, false, mv, 0)
        GLES20.glUniform1f(rimShader.uniform("uPow"), LOOK.rimPow)
        // The scan band is a *light* sweep: it adds uColor along a moving
        // horizontal line, so it is only meaningful where uColor is a light —
        // the glow branch. On the dark-contour branch uColor is a shadow shade,
        // and sweeping that paints a moving dark stripe across the ball, which
        // reads as a rendering fault rather than as a scan. Zeroed there.
        GLES20.glUniform1f(rimShader.uniform("uScanY"), state.scanY)
        GLES20.glUniform1f(rimShader.uniform("uScan"), if (LOOK.rimDark) 0f else state.scan)
        if (LOOK.rimDark) {
            // A tight dark contour instead of a glow. The body is drawn inside
            // [RIM] and this sphere sits on it, so the visible band *is* the
            // outline; a pale ball needs it to hold its edge over a pale app,
            // and a colour-additive glow cannot do that job on a white
            // background.
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            for (i in 0..2) {
                // Slightly blue-shifted so the outline reads as a shadow rather
                // than as dirt.
                outlineColor[i] = state.nowColor[i] * if (i == 2) 0.42f else 0.34f
            }
            GLES20.glUniform3fv(rimShader.uniform("uColor"), 1, outlineColor, 0)
            GLES20.glUniform1f(rimShader.uniform("uIntensity"), LOOK.rimGain * 1.15f)
            GLES20.glUniform1f(rimShader.uniform("uCap"), RIM_DARK_CAP)
        } else {
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
            GLES20.glUniform3fv(rimShader.uniform("uColor"), 1, state.nowColor, 0)
            GLES20.glUniform1f(rimShader.uniform("uIntensity"), state.rimIntensity * LOOK.rimGain)
            GLES20.glUniform1f(rimShader.uniform("uCap"), 1f)
        }
        rimSphere.bind(rimShader.attrib("aPos"), rimShader.attrib("aNormal"))
        rimSphere.draw()
    }

    private fun drawCore() {
        System.arraycopy(root, 0, model, 0, 16)
        Matrix.scaleM(model, 0, state.coreScale, state.coreScale, state.coreScale)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        coreShader.use()
        GLES20.glUniformMatrix4fv(coreShader.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform3fv(coreShader.uniform("uColor"), 1, state.nowColor, 0)
        GLES20.glUniform1f(coreShader.uniform("uAlpha"), state.coreAlpha)
        core.bind(coreShader.attrib("aPos"))
        core.draw()
    }

    private fun drawRings() {
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        flowShader.use()
        val aPos = flowShader.attrib("aPos")
        val aUV = flowShader.attrib("aUV")

        for (i in 0..3) {
            val r = RINGS[i]
            // ringLocal = spread * rings_group_rotX * ring_rotY * torus_local_orient
            Matrix.setIdentityM(local, 0)
            Matrix.scaleM(local, 0, state.spread, state.spread, state.spread)
            Matrix.rotateM(local, 0, state.ringsRotX * DEG, 1f, 0f, 0f)
            Matrix.rotateM(local, 0, state.ringRotY[i] * DEG, 0f, 1f, 0f)
            Matrix.rotateM(local, 0, r.rotX * DEG, 1f, 0f, 0f)
            Matrix.rotateM(local, 0, r.rotY * DEG, 0f, 1f, 0f)
            Matrix.rotateM(local, 0, r.rotZ * DEG, 0f, 0f, 1f)
            Matrix.multiplyMM(model, 0, root, 0, local, 0)
            Matrix.multiplyMM(mv, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)

            GLES20.glUniformMatrix4fv(flowShader.uniform("uMVP"), 1, false, mvp, 0)
            GLES20.glUniform3fv(flowShader.uniform("uColor"), 1, state.nowColor, 0)
            GLES20.glUniform1f(
                flowShader.uniform("uBase"),
                r.base * (1f + state.open * 1.1f + state.busy * 1.4f),
            )
            GLES20.glUniform1f(flowShader.uniform("uFlow"), state.flow[i])
            GLES20.glUniform1f(
                flowShader.uniform("uPulse"),
                0.3f + state.open * 0.45f + state.busy * 0.75f,
            )
            ringMeshes[i].bind(aPos, -1, aUV)
            ringMeshes[i].draw()
        }
    }

    private fun drawDust() {
        state.writeTails(tails.data)
        state.writeHeads(heads.data)
        tails.upload()
        heads.upload()

        System.arraycopy(root, 0, model, 0, 16)
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0)

        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glDepthMask(false)
        pointsShader.use()
        GLES20.glUniformMatrix4fv(pointsShader.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(pointsShader.uniform("uMV"), 1, false, mv, 0)
        GLES20.glUniform1f(pointsShader.uniform("uPointFactor"), pointFactor)

        // Tails first — heads paint over them, brightest on top.
        GLES20.glUniform1f(pointsShader.uniform("uSize"), 0.03f)
        GLES20.glUniform1f(pointsShader.uniform("uAlpha"), state.dustTailAlpha)
        tails.bind(pointsShader.attrib("aPos"), pointsShader.attrib("aColor"))
        tails.draw()

        GLES20.glUniform1f(pointsShader.uniform("uSize"), 0.052f)
        GLES20.glUniform1f(pointsShader.uniform("uAlpha"), state.dustHeadAlpha)
        heads.bind(pointsShader.attrib("aPos"), pointsShader.attrib("aColor"))
        heads.draw()
    }

    companion object {
        private const val DEG = 57.29578f  // rad → deg for Matrix.rotateM

        /** Camera distance on +Z, looking at the origin. */
        private const val CAM_Z = 4.9f

        /** The glowing outline. Defines how big the ball looks. */
        private const val RIM = 0.9f

        /** How much darker than the mood the mouth goes on a pale look. */
        private const val MOUTH_DEEP = 0.40f

        /**
         * Ceiling on the dark outline's opacity. It is drawn over the 1.7% of the
         * rim sphere that sticks out past the body, and at full strength that
         * band reads as a stroke drawn round the ball rather than as its edge.
         */
        private const val RIM_DARK_CAP = 0.82f

        // ---- Pupils ----
        //
        // A dark relative of the mood colour rather than a fixed black, so the
        // eyes carry the mood with the rest of the ball instead of punching a
        // hole in it. The lift is what keeps the pupil from ever reaching zero:
        // a pupil that is pure black on a dark ball loses its edge.
        private const val PUPIL_TINT = 0.20f
        private val PUPIL_LIFT = floatArrayOf(0.045f, 0.050f, 0.075f)

        /**
         * Vertical aperture below which the pupil is not drawn. The `^ ^` moods
         * drive the round eye below this, and a pupil without a white eye around
         * it is a dot on the face.
         */
        private const val PUPIL_MIN_APERTURE = 0.05f

        private const val DISC_THICKNESS = 0.08f
        private const val GLINT_Z_STEP = 0.02f

        /**
         * How far [EmotionState.brow] swings a look's resting brow, radians.
         *
         * The mood's brow knob is ±1-ish, so this is the whole range a mood can
         * move the angle through on top of [BallLook.browTilt]. Kept well under
         * the resting scowl: a mood must be able to soften an angry brow, not
         * to flip the character out of being angry.
         */
        private const val BROW_MOOD_TILT = 0.30f

        /**
         * How far in from each corner of the mouth the tooth row starts,
         * radians of the mouth's own arc.
         *
         * A tooth placed exactly at the corner stands perpendicular to a
         * near-vertical piece of the curve, i.e. it points sideways off the
         * face — which reads as a chip flying off rather than as a tooth.
         */
        private const val TOOTH_PAD = 0.26f

        /**
         * How far the buck-tooth pair is pushed down the mouth's normal, as a
         * multiple of [BallLook.buckH].
         *
         * A third, which means two thirds of each tooth hangs below the lip and
         * the top third is behind it — the proportion a cartoon draws, and the
         * one that keeps the lip line reading as continuous *through* the teeth
         * rather than as two separate strokes either side of them. At 1.0 the
         * teeth clear the lip entirely and become a second mouth under the
         * first; at 0 they are a white bite taken out of the middle of it.
         */
        private const val BUCK_DROP = 0.34f

        /**
         * How much darker the nostril dots are than the live [bodyColor]. A
         * multiple rather than a fixed black so the snout stays consistent as
         * the mood tints the body — see [BallLook.browShade] for the same
         * reasoning.
         */
        private const val NOSTRIL_DARK = 0.28f

        /**
         * Clearance between a nostril dot's front face and the muzzle surface
         * it is drawn on — see [drawNostrils], which computes that surface
         * rather than guessing at it.
         *
         * It only has to beat depth-buffer resolution, so it is deliberately
         * small: a flat disc standing off a curved bump is a disc floating in
         * front of it, and the whole point of the dot is to read as a hole.
         */
        private const val NOSTRIL_LIFT = 0.006f

        // ---- Tail ----
        //
        // The count is a *coverage* number, not a taste one: the blobs have to
        // overlap or the chain reads as a row of separate dots, which is what
        // five gave on the tablet — a step of len/5 = 0.11 against a lead blob
        // 0.11 across, so they met end to end and every blob after that was
        // thinner than the gap it had to bridge. Nine puts the step at ~0.06,
        // under half the lead diameter, and the taper never catches up with it.
        private const val TAIL_BLOBS = 9

        /** Direction of the first step, radians from +X. Out and downward. */
        private const val TAIL_START = -0.80f

        /** Total turn from root to barb. ~150°, which is the classic hook. */
        private const val TAIL_SWEEP = 2.60f

        /** How much thinner the last blob is than the first. */
        private const val TAIL_TAPER = 0.45f

        private val WHITE = floatArrayOf(1f, 1f, 1f)

        /** Bottom of the eye capsule (half-cylinder + cap), the lid's hinge. */
        private const val EYE_LID_PIVOT = -0.125f

        /** How much of a circle the `^` eye covers. */
        private val EYE_ARC_SPAN = 0.8f * PI.toFloat()

        /** Rotation that moves the arc's midpoint from SPAN/2 up to the top. */
        private val EYE_ARC_ROT_Z = PI.toFloat() / 2f - EYE_ARC_SPAN / 2f

        /** The arch sits above its own origin; drop it back over the eye. */
        private const val EYE_ARC_DROP = 0.06f

        /**
         * World Y of the screen edge the paws hang on, derived from how far
         * [FloatingWindowUi] pushes the window down. Hiding half the ball puts
         * it through the equator, hence 0 — but deriving it means the paws
         * follow if [CLING_HIDDEN] ever moves.
         */
        private val HAND_LEDGE = RIM * (2f * CLING_HIDDEN - 1f)

        // ---- Paw geometry, all in world units. Two paws of four blobs each:
        // ---- a wide flat palm on the ledge with three toes crowning it.
        //
        // Sized by what survives the clip rather than by what looks right in
        // isolation: everything below [HAND_LEDGE] is cut away, so only the
        // part above it is ever seen, and the first version put barely 10dp of
        // paw over the line — invisible in practice. The palm now straddles the
        // ledge with most of its bulk above, and the toes clear it by about
        // twice as much as before.
        private const val HAND_X = 0.56f
        private val HAND_Y = HAND_LEDGE + 0.05f

        /**
         * In front of the body's surface at every x the palm covers — at
         * x = 0.35 the sphere is already at z = 0.81, and a palm centred
         * shallower than this gets its inner edge swallowed.
         */
        private const val HAND_Z = 0.88f

        private const val HAND_W = 0.24f
        private const val HAND_H = 0.12f
        private const val HAND_TOE = 0.08f

        /** Under one toe diameter, so the toes fuse instead of reading as peas. */
        private const val HAND_TOE_GAP = 0.125f

        /** How far the toes crown the palm — the part that clears its top edge. */
        private const val HAND_TOE_RISE = 0.16f

        /** How long one `screen.*` command keeps the SEARCHING face. */
        private const val TOOL_SECONDS = 2.2f

        /** How long a finished task's SUCCESS / FAILED face lingers. */
        private const val OVERLAY_SECONDS = 1.9f

        /**
         * Idle time before the ball dozes off. Long enough that it never
         * happens mid-conversation, short enough that a tablet left on a desk
         * stops looking like it is waiting for something.
         */
        private const val SLEEP_AFTER_SECONDS = 150f

        // ---- Idle fidgets ----
        //
        // Random body-only gestures while awake and bored. The schedule:
        // nothing for the first FIDGET_AFTER_SECONDS after going idle (a ball
        // that starts dancing the moment you look away is exhausting), then
        // one fidget every FIDGET_MIN..FIDGET_MAX seconds — long enough apart
        // that spotting one feels like catching it doing something, not like
        // watching a screensaver.
        private const val FIDGET_AFTER_SECONDS = 12f
        private const val FIDGET_MIN_SECONDS = 20f
        private const val FIDGET_MAX_SECONDS = 50f

        // ---- Body: top-lit gradient + one specular dot + an edge lift ----
        private const val SHELL_VS = """
            uniform mat4 uMVP;
            uniform mat4 uMV;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            varying vec3 vN;
            varying vec3 vP;
            void main() {
                vN = normalize(mat3(uMV) * aNormal);
                vec4 p = uMV * vec4(aPos, 1.0);
                vP = p.xyz;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        private const val SHELL_FS = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uAlpha;
            uniform float uGrad;
            uniform float uAmb;
            uniform float uDiff;
            uniform float uGloss;
            varying vec3 vN;
            varying vec3 vP;
            void main() {
                vec3 n = normalize(vN);
                vec3 V = normalize(-vP);
                // Vertical gradient — darker underneath, so the ball reads as a
                // solid sitting in light rather than a disc with an outline.
                float vert = n.y * 0.5 + 0.5;
                vec3 base = uColor * mix(1.0, mix(0.70, 1.14, vert), uGrad);
                // Key light overhead and slightly left, and aimed that way on
                // purpose: a specular aimed frontally lands on the eyes and takes
                // half their contrast with it.
                vec3 L = normalize(vec3(-0.48, 0.85, 0.20));
                float diff = max(0.0, dot(n, L));
                vec3 lit = base * (uAmb + uDiff * diff);
                vec3 H = normalize(L + V);
                float spec = pow(max(0.0, dot(n, H)), 90.0) * uGloss;
                // A little extra colour right at the silhouette, which is what
                // holds the ball apart from a same-valued background.
                float edge = pow(1.0 - max(0.0, dot(n, V)), 3.0) * uGloss * 0.26;
                gl_FragColor = vec4(lit + vec3(spec) + uColor * edge, uAlpha);
            }
        """

        // ---- Rim + scan (direct port of RIM_VERT/FRAG) ----
        private const val RIM_VS = """
            uniform mat4 uMVP;
            uniform mat4 uMV;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            varying vec3 vN;
            varying vec3 vP;
            varying float vY;
            void main() {
                vN = normalize(mat3(uMV) * aNormal);
                vec4 p = uMV * vec4(aPos, 1.0);
                vP = p.xyz;
                vY = aPos.y;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        private const val RIM_FS = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uIntensity;
            uniform float uScan;
            uniform float uScanY;
            uniform float uPow;
            uniform float uCap;
            varying vec3 vN;
            varying vec3 vP;
            varying float vY;
            void main() {
                float f = pow(1.0 - abs(dot(normalize(vN), normalize(-vP))), uPow);
                float d = vY - uScanY;
                float slope = d > 0.0 ? 7.5 : 4.2;
                float band = pow(max(0.0, 1.0 - abs(d) * slope), 3.0);
                gl_FragColor = vec4(uColor, min(f * uIntensity + band * uScan, uCap));
            }
        """

        // ---- Bubble: a transparent droplet, not a solid blob ----
        //
        // The back half is discarded rather than depth-culled: inside one
        // indexed draw the triangles arrive in no useful order, so a front-half
        // fragment can be rasterised after the back-half fragment at the same
        // pixel and both would blend. `facing <= 0` drops the back half outright,
        // and it does so without depending on the mesh's winding.
        private const val BUBBLE_VS = """
            uniform mat4 uMVP;
            uniform mat4 uMV;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            varying vec3 vN;
            varying vec3 vP;
            void main() {
                vN = normalize(mat3(uMV) * aNormal);
                vec4 mvpos = uMV * vec4(aPos, 1.0);
                vP = mvpos.xyz;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """

        private const val BUBBLE_FS = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uAlpha;
            uniform float uPow;
            uniform float uShine;
            uniform float uInner;
            varying vec3 vN;
            varying vec3 vP;
            void main() {
                vec3 n = normalize(vN);
                vec3 V = normalize(-vP);
                float facing = dot(n, V);
                if (facing <= 0.0) discard;
                float edge = pow(1.0 - facing, uPow);
                vec3 L = normalize(vec3(-0.45, 0.80, 0.60));
                vec3 H = normalize(L + V);
                float spec = pow(max(0.0, dot(n, H)), uShine);
                float a = clamp(edge + uInner + spec, 0.0, 1.0);
                vec3 c = mix(uColor, vec3(1.0), clamp(spec * 2.5, 0.0, 1.0));
                gl_FragColor = vec4(c, a * uAlpha);
            }
        """

        // ---- Core: flat color ----
        private const val CORE_VS = """
            uniform mat4 uMVP;
            attribute vec3 aPos;
            void main() {
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        private const val CORE_FS = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uAlpha;
            void main() {
                gl_FragColor = vec4(uColor, uAlpha);
            }
        """

        // ---- Ring flow (direct port of FLOW_VERT/FRAG) ----
        private const val FLOW_VS = """
            uniform mat4 uMVP;
            attribute vec3 aPos;
            attribute vec2 aUV;
            varying vec2 vUv;
            void main() {
                vUv = aUV;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        private const val FLOW_FS = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uBase;
            uniform float uFlow;
            uniform float uPulse;
            varying vec2 vUv;
            void main() {
                float d = fract(uFlow - vUv.x);
                float head = pow(1.0 - d, 24.0);
                float tail = pow(1.0 - d, 3.0);
                gl_FragColor = vec4(
                    uColor * (1.0 + head * 1.9),
                    uBase + (head + tail * 0.34) * uPulse
                );
            }
        """

        // ---- Point sprites (dust). Size attenuates with distance so nearer
        //      particles render bigger, matching R3F's sizeAttenuation. ----
        private const val POINTS_VS = """
            uniform mat4 uMVP;
            uniform mat4 uMV;
            uniform float uSize;
            uniform float uPointFactor;
            attribute vec3 aPos;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() {
                vec4 mvpos = uMV * vec4(aPos, 1.0);
                gl_Position = uMVP * vec4(aPos, 1.0);
                gl_PointSize = uSize * uPointFactor / -mvpos.z;
                vColor = aColor;
            }
        """
        private const val POINTS_FS = """
            precision mediump float;
            uniform float uAlpha;
            varying vec3 vColor;
            void main() {
                vec2 c = gl_PointCoord - 0.5;
                float d = length(c);
                float a = smoothstep(0.5, 0.0, d);
                gl_FragColor = vec4(vColor, a * uAlpha);
            }
        """

        // ---- Solid color (face parts) ----
        private const val SOLID_VS = """
            uniform mat4 uMVP;
            attribute vec3 aPos;
            void main() {
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        private const val SOLID_FS = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uAlpha;
            void main() {
                gl_FragColor = vec4(uColor, uAlpha);
            }
        """
    }
}
