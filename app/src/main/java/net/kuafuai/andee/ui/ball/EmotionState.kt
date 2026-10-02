package net.kuafuai.andee.ui.ball

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Per-frame animated state for the ball. Ported from the R3F useFrame body.
 *
 * Call [tick] every frame; then read the public fields to drive uniforms and
 * matrices. Anything that needs to lerp toward a target lives here, not in the
 * renderer.
 */
class EmotionState {

    /**
     * The look being drawn, re-read every access.
     *
     * A getter rather than a stored field because the user can swipe the ball
     * to another look at any moment; a `val` captured at construction would
     * leave this class animating the previous character's face. Everything it
     * feeds is recomputed in [tick], so following the switch costs nothing —
     * the only values that would lag are the resting positions below, and they
     * are overwritten on the first frame after the change.
     */
    private val LOOK: BallLook get() = BallLooks.active

    // ---- Face + color, lerped toward FACE[mood] ----
    val nowColor = FloatArray(3).also { FACE[Mood.CALM]!!.color.copyInto(it) }
    var eye = 1f; private set
    var brow = 0f; private set
    var smile = 0.1f; private set
    var tilt = 0f; private set
    var skew = 0f; private set
    var jitter = 0f; private set
    var open = 0f; private set     // mouth open (talk envelope)
    var eyeArc = 0f; private set   // 0 = round eye, 1 = ^ ^ arc
    var blush = 0f; private set
    var spread = 1f; private set   // ring belt radius multiplier

    /**
     * 鼻涕泡的浓度，0..1。跟 [blush] 一样是**淡入淡出**的：它决定泡在不在，
     * 不决定泡多大 —— 大小是 [bubbleScale] 的事。
     */
    var bubble = 0f; private set

    /** 泡自己的一呼一吸，0.30（瘪）..1.00（鼓）。 */
    var bubbleScale = 1f; private set

    private var bubblePhase = 0f

    /** Blink, 1 = open, 0 = shut. Applies to both eye shapes. */
    var lid = 1f; private set

    /** 0..1, smoothed "how much am I thinking" */
    var think = 0f; private set

    /**
     * 0..1, smoothed mirror of the `listening` flag.
     *
     * Smoothed because the rim pulse it drives has to fade in and out; a hard
     * boolean stepped the outline brightness by a factor of two between frames,
     * which reads as a glitch rather than as attention.
     */
    private var listen = 0f

    /** listening? 0.9 : think — drives ring/scan/dust speed */
    var busy = 0f; private set

    // ---- Root transform (root group has these applied in order Rx*Ry*Rz*Scale) ----
    var rotX = 0f; private set
    var rotY = 0f; private set
    var rotZ = 0f; private set
    var breathe = 1f; private set

    /**
     * Lerped [Face.breath]. Tweened rather than read straight off the target
     * face so a mood change doesn't step the body scale — that would land as a
     * flinch, not a breath.
     */
    private var breatheDepth = 0.022f

    /**
     * Breathing folded together with the current action's squash, as the two
     * axes the renderer actually scales by. Volume is roughly conserved — a ball
     * that only flattens reads as deflating, not as landing.
     */
    var scaleX = 1f; private set
    var scaleY = 1f; private set

    /** Vertical body offset, in world units. Non-zero only during a HOP. */
    var hopY = 0f; private set

    /**
     * 0..1, smoothed: how much the ball is hanging off the bottom edge. The
     * renderer reads it to fade the paws in and to decide whether to draw them
     * at all. Public because the paws are a *drawn* thing, unlike everything
     * else cling does, which is only a transform.
     */
    var cling = 0f; private set

    /**
     * How hard it is currently gripping, 0..1. Peaks during the haul back up —
     * the paws flatten and spread on the edge exactly when the body is pulling
     * against them, which is what makes the pull look like it costs something.
     */
    var grip = 0f; private set

    // ---- Rings ----
    var ringsRotX = 0f; private set
    val ringRotY = FloatArray(4)
    val flow = FloatArray(4)

    // ---- Rim + scan ----
    var scanY = -2f; private set
    var scan = 0f; private set
    var rimIntensity = 0.9f; private set

    // ---- Core ----
    var coreScale = 0.42f; private set
    var coreAlpha = 0.16f; private set

    // ---- Face element transforms ----
    // Bases come from [BallLook] — the renderer sizes its eye and mouth meshes
    // off the same object, so the two can never disagree about where the face is.
    var eyeLX = -LOOK.eyeX;
    var eyeLY = LOOK.eyeY;
    var eyeLRotZ = 0f;
    var eyeYScale = 1f
    var eyeRX = LOOK.eyeX;
    var eyeRY = LOOK.eyeY;
    var eyeRRotZ = 0f
    var mouthY = LOOK.mouthY;
    var mouthScaleY = 0.16f;
    var mouthRotZ = PI.toFloat()

    // ---- Dust particles ----
    private class Particle(
        val sinPhi: Float, val cosPhi: Float,
        val a0: Float, val rate: Float,
        val r: Float, val b: Float,
    )

    private val particles = Array(DUST_COUNT) {
        val phi = acos(2f * Random.nextFloat() - 1f)
        Particle(
            sinPhi = sin(phi),
            cosPhi = cos(phi),
            a0 = Random.nextFloat() * 2f * PI.toFloat(),
            rate = 0.55f + Random.nextFloat() * 1.5f,
            r = 1.28f + Random.nextFloat() * 0.46f,
            b = 0.55f + Random.nextFloat() * 0.45f,
        )
    }

    /** Alpha targets that the renderer reads for the two dust passes. */
    var dustHeadAlpha = 0.6f; private set
    var dustTailAlpha = 0.5f; private set

    // ---- Internal integrated angles (see comment on `spin` in R3F source:
    // ---- speeds vary over time, so we must integrate dt*rate per frame instead
    // ---- of setting angle = t * rate) ----
    private var ringsXAngle = 0f
    private var scanAngle = 0f
    private var dustAngle = 0f
    private var dustRate = 0f
    private var dustLag = 0f
    private val ringAngle = FloatArray(4)
    private val flowAngle = FloatArray(4)

    // ---- Blink ----
    private var blinkNext = 2.4f
    private var blinkLeft = 0f

    // ---- One-shot action ----
    private var action = Action.NONE
    private var actionT = 0f
    private var actionDur = 0f

    // ---- Clinging to the bottom screen edge ----
    /** Runs 0→1 over one sag-and-haul cycle. See the slip block in [tick]. */
    private var slipPhase = 0f

    /**
     * Start a body gesture, cancelling any gesture already running.
     *
     * Cancelling rather than queueing is deliberate: these are 0.5 s reactions
     * to things that just happened, and a queue would play them after the thing
     * they were reacting to is over. A tool call arriving mid-nod should restart
     * the nod, not book a second one.
     *
     * Call from the GL thread (i.e. from inside the renderer's frame callback).
     */
    fun trigger(a: Action) {
        action = a
        actionT = 0f
        actionDur = ACTION_SECONDS[a.ordinal]
    }

    fun tick(
        dt: Float,
        t: Float,
        mood: Mood,
        listening: Boolean,
        working: Boolean,
        talking: Boolean = mood == Mood.SPEAKING,
    ) {
        val want = FACE[mood]!!

        // Lerp toward the target face. 0.14 s to reach = min(1, dt*7).
        val k = min(1f, dt * 7f)
        eye += (want.eye - eye) * k
        brow += (want.brow - brow) * k
        smile += (want.smile - smile) * k
        tilt += (want.tilt - tilt) * k
        skew += (want.skew - skew) * k
        jitter += (want.jitter - jitter) * min(1f, dt * 2.4f)  // jitter decays slower
        eyeArc += (want.eyeArc - eyeArc) * k
        blush += (want.blush - blush) * k
        spread += (want.spread - spread) * min(1f, dt * 3.5f)  // belt is heavy
        for (i in 0..2) nowColor[i] += (want.color[i] - nowColor[i]) * k

        // Thinking intensity — half-sec in/out, so answers don't snap the ring
        // speed back to idle mid-glance. SEARCHING counts: driving an app is
        // thinking as far as everything downstream of `busy` is concerned.
        val thinkTarget = if (mood == Mood.THINKING || mood == Mood.SEARCHING) 1f else 0f
        think += (thinkTarget - think) * min(1f, dt * 2.2f)
        val th = think
        listen += ((if (listening) 1f else 0f) - listen) * min(1f, dt * 3.4f)
        busy = if (listening) 0.9f else th

        // Gaze — eye drift while thinking. Three incommensurate low-freq sines
        // so there's no recognizable period.
        val gaze = th * (sin(t * 0.9f) + sin(t * 1.31f) * 0.5f)
        val gazeY = th * (sin(t * 0.73f) * 0.6f + sin(t * 1.07f) * 0.35f)

        // Jitter — tense/anxious shake, two incommensurate high-freq sines
        val jx = (sin(t * 23.7f) + sin(t * 37.1f) * 0.6f) * jitter
        val jy = (sin(t * 29.3f) + sin(t * 41.7f) * 0.6f) * jitter

        // Mouth open — no voice.level() yet, use sine envelope when SPEAKING
        // (matches the R3F fallback path when the audio source can't report
        // level). Everything else falls back to the mood's resting [Face.gape],
        // which is how LISTENING gets a mouth that is open rather than curved.
        //
        // Gated on [talking], not on `mood == SPEAKING`, because the two stopped
        // being the same question once the model could pick a face for its own
        // reply: a HAPPY answer is still an answer being said out loud, and
        // reading the mood here would have left it grinning in silence.
        val talkTarget = if (talking) {
            0.34f + sin(t * 17f) * 0.16f + sin(t * 29f) * 0.09f
        } else want.gape
        open += (max(0f, talkTarget) - open) * min(1f, dt * 22f)

        // Blink. More frequent when listening (like nodding), less when thinking.
        //
        // ...and not at all once the eye on screen is the `^ ^` arc. A blink is
        // a lid coming down over an *open* eye; when the eye is already drawn
        // shut there is no lid to lower, and what the blink actually does is
        // twitch the arc — a sleeping ball flinching every three seconds. The
        // threshold is shared with the renderer's "does this eye still want a
        // pupil", which is the same question asked from the other end.
        val eyeShut = eyeArc >= ARC_EYE_AT
        blinkNext -= dt
        if (blinkNext <= 0f) {
            if (eyeShut) {
                // Cancel anything in flight and re-check shortly, so waking up
                // doesn't mean waiting out a full blink interval.
                blinkLeft = 0f
                blinkNext = 0.35f
            } else {
                blinkLeft = 0.12f
                val base = (if (listening) 1.8f else 3.2f) + Random.nextFloat() * 2.6f
                blinkNext = (base * (1f + th * 0.9f)) / (1f + jitter * 1.6f)
            }
        }
        var lid = 1f
        if (blinkLeft > 0f) {
            blinkLeft -= dt
            lid = abs(blinkLeft / 0.06f - 1f)  // triangle: 1 → 0 → 1
        }
        this.lid = lid

        // One-shot action envelope. Everything below is a pure function of `p`,
        // which runs 0→1 over the action's duration — so the gesture is simply
        // over when p reaches 1, with no tail left to decay and no way to get
        // stuck half-deformed if a frame is dropped.
        var sqx = 0f
        var sqy = 0f
        var hop = 0f
        var actRotX = 0f
        var actRotY = 0f
        if (action != Action.NONE) {
            actionT += dt
            val p = min(1f, actionT / actionDur)
            val arch = sin(p * PI.toFloat())  // 0 → 1 → 0
            when (action) {
                Action.HOP -> {
                    hop = arch * 0.22f
                    // Squash only while in contact with the ground, i.e. at the
                    // two ends. Squashing mid-air reads as a wobble, not a jump.
                    val contact = max(0f, 1f - p / 0.2f) + max(0f, (p - 0.8f) / 0.2f)
                    sqx = contact * 0.13f
                    sqy = contact * -0.17f
                }
                Action.NOD -> actRotX = sin(p * 2f * PI.toFloat()) * 0.26f
                Action.SHAKE -> actRotY = sin(p * 3f * PI.toFloat()) * 0.2f
                Action.POP -> {
                    sqx = arch * 0.15f
                    sqy = arch * 0.15f
                    hop = arch * 0.06f
                }
                Action.RECOIL -> {
                    sqx = arch * 0.17f
                    sqy = arch * -0.2f
                    actRotX = -arch * 0.14f
                }
                // ---- Idle fidgets: body-only, face untouched ----
                Action.STRETCH -> {
                    // Tall and thin with the eyes shut for a yawn-adjacent
                    // elongation, easing at both ends so it reads as a muscle
                    // pulling, not a scale keyframe.
                    val ease = sin(p * PI.toFloat())
                    sqx = -ease * 0.12f
                    sqy = ease * 0.2f
                    actRotX = -ease * 0.08f
                }
                Action.LOOK_AROUND -> {
                    // Two glances left and right, unhurried — the classic
                    // "waiting room look". One and a quarter sine cycles.
                    actRotY = sin(p * 2.5f * PI.toFloat()) * 0.3f
                    actRotX = sin(p * 1.25f * PI.toFloat()) * 0.05f
                }
                Action.WIGGLE -> {
                    // Side-to-side squash dance, three small beats. Pure rotZ
                    // so the face stays front-on while the body sways.
                    actRotY = sin(p * 3f * PI.toFloat()) * 0.14f
                }
                Action.BOUNCE -> {
                    // Two quick happy hops, smaller than the real HOP: this
                    // is impatience/excitement in miniature, an idle jig.
                    hop = abs(sin(p * 2f * PI.toFloat())) * 0.09f
                    val contact = max(0f, 1f - p / 0.15f) + max(0f, (p - 0.85f) / 0.15f)
                    sqx = contact * 0.07f
                    sqy = contact * -0.09f
                }
                Action.NONE -> {}
            }
            if (p >= 1f) action = Action.NONE
        }

        // ---- Hanging off the bottom edge by two paws ----
        // Sunk half below the screen edge, so only the top of the head shows,
        // with two paws hooked over the line holding it there.
        //
        // The paws are pinned to the edge and the *body* moves under them —
        // that ordering is what makes this read as hanging rather than as a
        // ball with decorations. It slides down for three seconds, hauls itself
        // back up in one, and the haul is the only motion in here that isn't a
        // sine, which is why it's the only one that looks like effort.
        cling += ((if (working) 1f else 0f) - cling) * min(1f, dt * 2.6f)
        var slipY = 0f
        var haul = 0f
        if (cling > 0.01f) {
            slipPhase += dt / SLIP_SECONDS
            if (slipPhase >= 1f) slipPhase -= floor(slipPhase)
            if (slipPhase < SLIP_SAG) {
                // Sliding down. Squared so it starts imperceptibly and picks up
                // — a linear slide reads as being lowered, not as slipping.
                val s = slipPhase / SLIP_SAG
                slipY = -s * s * SLIP_DEPTH
            } else {
                // Scrabbling back up, with a small overshoot at the top.
                val s = (slipPhase - SLIP_SAG) / (1f - SLIP_SAG)
                val eased = 1f - (1f - s) * (1f - s)
                slipY = -(1f - eased) * SLIP_DEPTH + sin(s * PI.toFloat()) * 0.035f
                haul = sin(s * PI.toFloat())
            }
        }
        grip = cling * haul
        // Hanging by the arms stretches you: taller and narrower the further
        // you have slipped, and narrower again on the pull. A ball that sagged
        // without deforming would just be a ball being translated downward.
        val stretch = cling * (-slipY / SLIP_DEPTH)
        val clingSqX = -cling * 0.03f - stretch * 0.05f - haul * 0.04f
        val clingSqY = cling * 0.03f + stretch * 0.07f + haul * 0.05f
        // Swinging. Slow and small, and it hangs off the slip so the swing is
        // widest at the bottom of the sag — that is where a pendulum is.
        val clingLean = cling * sin(t * 0.85f) * (0.06f + stretch * 0.07f)

        // Root transform
        breatheDepth += (want.breath - breatheDepth) * min(1f, dt * 3f)
        breathe = 1f + sin(t * want.pulse) * breatheDepth + open * 0.03f
        scaleX = breathe * (1f + sqx + clingSqX)
        scaleY = breathe * (1f + sqy + clingSqY)
        hopY = hop + slipY * cling
        // A task can run a whole minute with no tool call in it, and a resting
        // face doesn't read as activity — a working ball has to be visibly
        // *doing* something. Amplitude is a fifth of the one-shot NOD's on
        // purpose, so an actual tool call still lands as an event on top of it
        // rather than disappearing into the bob.
        val workBob = cling * sin(t * 3.4f) * 0.04f
        rotY = sin(t * 0.22f) * 0.12f + jx * 0.012f + gaze * 0.09f + actRotY
        rotX = sin(t * 0.17f) * 0.06f + jy * 0.009f - gazeY * 0.05f + actRotX + workBob
        rotZ = tilt + th * sin(t * 0.51f) * 0.09f - clingLean

        // Rings group tumble. Small on purpose: the belt orientation in [RINGS]
        // is chosen to keep the rings edge-on and off the face, and a large
        // tumble swings them right back across it.
        ringsXAngle += dt * (0.21f + busy * 0.42f)
        ringsRotX = sin(ringsXAngle) * 0.22f

        // Per-ring spin + flow
        for (i in 0..3) {
            val r = RINGS[i]
            ringAngle[i] += dt * r.spin * (1f + busy * 2.6f)
            ringRotY[i] = ringAngle[i]
            flowAngle[i] += dt * r.flow * (0.55f + busy * 2.4f)
            flow[i] = flowAngle[i] - floor(flowAngle[i])
        }

        // Scan band
        scanAngle += dt * (0.19f + busy * 0.55f)
        val scanF = scanAngle - floor(scanAngle)
        scanY = -1.35f + scanF * 2.7f
        scan = 0.08f + th * 0.5f + listen * 0.42f + open * 0.12f
        // The rim is one of only two places the mood colour is visible at all
        // (the mouth is the other), so it has to carry what the rings, dust and
        // core used to. `think` had no term here and thinking was therefore
        // almost entirely a hue change; it gets a slow swell of its own now,
        // deliberately off the breathing rate so the two drift against each
        // other instead of pumping in lockstep.
        //
        // Listening throbs rather than just brightening, and faster than any
        // breathing rate — a steady lift was indistinguishable from CALM's own
        // outline at a glance, and waiting for someone to speak is not a
        // resting state.
        val thinkBeat = th * (0.5f + sin(t * 1.9f) * 0.5f)
        val listenBeat = listen * (0.5f + sin(t * 4.2f) * 0.5f)
        rimIntensity = 0.85f + open * 0.7f + listen * 0.45f + listenBeat * 0.95f +
                thinkBeat * 0.55f + sin(t * want.pulse * 2f) * 0.05f

        // Core — small pulse plus a slower "beat" that only exists while thinking
        val beat = th * (0.5f + sin(t * 2.6f) * 0.5f)
        coreScale = 0.4f + open * 0.16f + sin(t * want.pulse * 1.6f) * 0.012f + beat * 0.14f
        coreAlpha = 0.16f + open * 0.5f + beat * 0.3f

        // Dust — remember rate/lag for writeDust
        dustRate = 0.18f + busy * 0.72f
        dustLag = dustRate * 0.36f
        dustAngle += dt * dustRate
        dustHeadAlpha = min(1f, 0.6f + open * 0.2f + busy * 0.35f)
        dustTailAlpha = min(1f, 0.5f + busy * 0.4f)

        // Face elements. Right brow gets skew added; left doesn't — asymmetry
        // reads as "thinking about it", symmetry reads as "having a feeling".
        val browR = brow + skew
        val browL = brow
        // Peering up over the edge it is holding. Eyes lift and the whole face
        // rides a little higher on the head, which is the difference between a
        // ball that happens to be low on screen and one looking up at you from
        // under a ledge. Without it the face aims into the bezel.
        val peek = cling * (0.055f + grip * 0.02f)
        eyeLY = LOOK.eyeY - browL * 0.06f + gazeY * 0.05f + peek
        eyeRY = LOOK.eyeY - browR * 0.06f + gazeY * 0.05f + peek
        eyeLX = -LOOK.eyeX + jx * 0.014f + gaze * 0.075f
        eyeRX = LOOK.eyeX + jx * 0.014f + gaze * 0.075f
        // Eye roll. Two terms, and they are on the same axis on purpose:
        //
        //  * the look's own resting tilt, mirrored per side, which is what makes
        //    an angry *shape* rather than an angry moment — see [BallLook.eyeTilt],
        //    where the whole argument for it lives; and
        //  * the mood, ±0.4 rad at full anger. Same sign convention as the old
        //    brow bar used, so a look that had one and has a cut now gets the
        //    same motion: the resting scowl deepens instead of being replaced.
        //
        // Both are radians here, hence the /DEG on the look's degrees.
        eyeLRotZ = -LOOK.eyeTilt / DEG - browL * 0.4f
        eyeRRotZ = LOOK.eyeTilt / DEG + browR * 0.4f
        // Vertical aperture. The renderer anchors this scale at the *top* of
        // the eye rather than its centre, so closing reads as a lid coming down
        // instead of the whole eye shrinking toward a point.
        eyeYScale = max(0.06f, eye * lid - smile * 0.34f)

        // Snot bubble. The phase is deliberately *not* tied to breathing: the
        // sleep rate is 0.45, which is a 14 s cycle — a bubble that holds still
        // for 14 s is a bubble that isn't moving at all. The rate still scales
        // with [Face.pulse], so "deeper sleep, slower bubble" survives.
        bubble += (want.bubble - bubble) * min(1f, dt * 2.2f)
        bubblePhase += dt * (0.9f + want.pulse * 1.6f)
        bubbleScale = 0.30f + 0.70f * (0.5f + 0.5f * sin(bubblePhase))

        // Thickness tracks how much curve there is to see. At the flat base
        // scale the mouth torus is a hairline nobody can read, so a real smile
        // has to make it taller as well as more curved — and so does the
        // resting one, which is why [BallLook.mouthThick] is in here rather
        // than only riding on `smile`.
        mouthScaleY = 0.16f + open * 1.5f + abs(smile) * 0.5f + LOOK.mouthThick
        mouthY = LOOK.mouthY + smile * 0.06f
        mouthRotZ = PI.toFloat() + smile * 0.9f + skew * 2.2f
    }

    fun writeHeads(buf: FloatArray) {
        val cr = nowColor[0];
        val cg = nowColor[1];
        val cb = nowColor[2]
        for (i in 0 until DUST_COUNT) {
            val p = particles[i]
            val a = p.a0 + dustAngle * p.rate
            val rs = p.r * p.sinPhi
            val y = p.r * p.cosPhi
            val j = i * 6
            buf[j] = cos(a) * rs
            buf[j + 1] = y
            buf[j + 2] = sin(a) * rs
            buf[j + 3] = cr * p.b
            buf[j + 4] = cg * p.b
            buf[j + 5] = cb * p.b
        }
    }

    fun writeTails(buf: FloatArray) {
        val cr = nowColor[0];
        val cg = nowColor[1];
        val cb = nowColor[2]
        for (i in 0 until DUST_COUNT) {
            val p = particles[i]
            val a = p.a0 + dustAngle * p.rate
            val rs = p.r * p.sinPhi
            val y = p.r * p.cosPhi
            for (n in 0 until TRAIL_COUNT) {
                val ak = a - dustLag * p.rate * (n + 1)
                val q = (i * TRAIL_COUNT + n) * 6
                buf[q] = cos(ak) * rs
                buf[q + 1] = y
                buf[q + 2] = sin(ak) * rs
                val fade = p.b * FADE[n]
                buf[q + 3] = cr * fade
                buf[q + 4] = cg * fade
                buf[q + 5] = cb * fade
            }
        }
    }

    private companion object {
        /** Degrees per radian, for the one place a look's angle meets the mood's. */
        const val DEG = 57.29578f

        /** One sag-and-haul cycle, seconds. */
        const val SLIP_SECONDS = 3.6f

        /** Fraction of it spent sliding down; the rest is hauling back up. */
        const val SLIP_SAG = 0.76f

        /**
         * How far the body slips, in world units, before it catches itself.
         * The ball's radius is ~0.885, so this is about a seventh of it —
         * enough to open a visible gap under the paws, not so much that the
         * face disappears below the screen edge.
         */
        const val SLIP_DEPTH = 0.13f
    }
}
