package net.kuafuai.andee.ui.ball

/**
 * The faces the ball can wear.
 *
 * Three groups, and the split is load-bearing rather than cosmetic:
 *   - emotions the model picks from (CALM..CONCERNED)
 *   - voice-pipeline states (LISTENING, THINKING, SPEAKING), owned by
 *     AsrController / TtsController
 *   - agent work states (SEARCHING..SLEEPING), owned by the task lifecycle in
 *     CommandDispatcher
 *
 * The last group is the one the ball was missing. Voice states say "I am
 * talking to you"; work states say "I am doing the thing you asked for", which
 * is most of what this device actually spends its time on. They arbitrate in
 * [EmotionBallRenderer]: voice wins when it has something to say, work fills
 * the silence, SLEEPING fills the silence after that.
 */
enum class Mood {
    CALM, HAPPY, CURIOUS, TENSE, ANXIOUS, CONCERNED,
    LISTENING, HOLDING, THINKING, SPEAKING,
    SEARCHING, SUCCESS, FAILED, SLEEPING,
}

/**
 * All the per-mood knobs the ball tweens between. See the R3F source's FACE
 * table for the reasoning behind the first eight columns.
 *
 * The trailing four are additions: [eyeArc] swaps the round eye for a `^ ^` arc,
 * [blush] lights the cheeks, [spread] pushes the ring belt out, [breath] sets
 * how far the body swells. All exist because at the size this ball is actually
 * drawn — a corner ball a thumb wide — colour, eye aperture and body scale are
 * the whole signal.
 *
 * [blush] is no longer an occasional flourish. It used to be zero on every row
 * except HAPPY and SUCCESS, which meant the face the user looks at for most of
 * the day had no cheeks at all — and the resting face is the one that has to
 * carry "harmless". Every row now sits somewhere on the same scale, from
 * SLEEPING's drained 0.12 to SUCCESS's flushed 1.0, and the renderer spends it
 * at a fixed renderer-side multiplier (see `BLUSH_ALPHA`).
 *
 * [smile] moved with it: the resting mouth on every non-negative row is now a
 * visible curve rather than the 0.1 hairline that read as a blank slot.
 */
class Face(
    val color: FloatArray,  // rgb 0..1
    val eye: Float,         // eye open (>1 = wide)
    val brow: Float,        // brow pressure (positive = furrowed)
    val smile: Float,       // mouth curve (+ smile, − frown)
    val pulse: Float,       // breathing rate
    val tilt: Float,        // head tilt
    val skew: Float,        // brow asymmetry
    val jitter: Float,      // shake amplitude
    val eyeArc: Float = 0f,   // 0 = round eye, 1 = ^ ^ arc (happy squint)
    val blush: Float = 0f,    // cheek glow
    val spread: Float = 1f,   // ring belt radius multiplier
    /**
     * Breathing depth, as a fraction of body scale. Used to be a single
     * constant with only [pulse] varying, which meant every mood breathed by
     * the same invisible 2.2% and the rate difference had nothing to modulate.
     */
    val breath: Float = 0.022f,
    /**
     * Resting mouth opening, on top of whatever the talk envelope is doing.
     *
     * Exists for LISTENING. Colour and eye aperture are cheap to confuse with
     * a neighbouring mood; a mouth that is *open* instead of curved is a shape
     * change, and shape survives being drawn a thumb wide.
     */
    val gape: Float = 0f,
    /**
     * 鼻涕泡的浓度，0 = 没有。
     *
     * 只有 SLEEPING 用。它不是装饰性的加法：一颗睡着的球和一颗静止的球在
     * 缩略图尺寸下几乎分不出来（都是深色弧 + 一点腮红），而一个在自己呼吸的
     * 泡是**动**的，动的东西在小尺寸下比颜色更容易被认出来。
     */
    val bubble: Float = 0f,
)

val FACE: Map<Mood, Face> = mapOf(
    Mood.CALM to Face(
        rgb(0x9db4ff), 1f, 0f, 0.26f, 1f, 0f, 0f, 0f,
        blush = 0.38f,
    ),
    Mood.HAPPY to Face(
        rgb(0x5eead4), 0.72f, -0.16f, 0.68f, 1.25f, 0.04f, 0f, 0f,
        eyeArc = 0.9f, blush = 0.85f,
    ),
    Mood.CURIOUS to Face(
        rgb(0xf0abfc), 1.22f, -0.06f, 0.28f, 1.15f, 0.17f, 0.055f, 0f,
        blush = 0.72f,
    ),
    Mood.TENSE to Face(
        rgb(0xfb923c), 0.88f, 0.3f, -0.2f, 1.95f, 0f, 0.02f, 0.55f,
        blush = 0.26f,
    ),
    Mood.ANXIOUS to Face(
        rgb(0xfb7185), 1.26f, 0.42f, -0.44f, 2.5f, 0f, 0.03f, 1f,
        blush = 0.24f,
    ),
    Mood.CONCERNED to Face(
        rgb(0xfbbf24), 1.1f, 0.34f, -0.34f, 1.15f, -0.05f, 0f, 0.12f,
        blush = 0.32f,
    ),

    // Wide-eyed, mouth slightly open, head cocked. This is the one state the
    // user is waiting on before they start talking, so it has to be
    // unmistakable from across a desk — and unmistakable from CALM, which it
    // used to differ from only by a shade of pale blue and a taller eye.
    //
    // The tilt is *negative*: this is the one mood that cocks its head, so
    // nothing else competes for the pose.
    Mood.LISTENING to Face(
        rgb(0x7dd3fc), 1.6f, -0.14f, 0.28f, 1.7f, -0.13f, 0f, 0f,
        blush = 0.46f, breath = 0.055f, gape = 0.12f,
    ),
    // Serious, steady attention: brows lowered, eyes fixed on the decision,
    // mouth closed. This is a waiting face, not a surprised listening face.
    Mood.HOLDING to Face(
        rgb(0xfacc15), 0.92f, 0.48f, -0.18f, 0.72f, 0f, 0f, 0f,
        blush = 0.3f, breath = 0.018f,
    ),
    // The resting face, in the mood's own colour. It used to be the opposite of
    // CALM — eyes squeezed to a slot, brows down and uneven, head cocked — and
    // on the tablet that read as straining or stuck rather than as thinking.
    // What says "thinking" now is not the face but what moves around it: the
    // eyes' quick left-right drift ([EmotionState] `gaze`) and the rim, core and
    // scan light that swell with `think`, none of which come from this row.
    Mood.THINKING to Face(
        rgb(0xc084fc), 1f, 0f, 0.26f, 1f, 0f, 0f, 0f,
        blush = 0.38f,
    ),
    Mood.SPEAKING to Face(
        rgb(0x9db4ff), 1.05f, 0f, 0.32f, 1.5f, 0f, 0f, 0f,
        blush = 0.44f,
    ),

    // Driving an app. Eyes down-and-narrow with the head cocked reads as
    // "looking at something that isn't you", which is exactly true, and the
    // belt flares to carry the speed the rings already pick up from `busy`.
    Mood.SEARCHING to Face(
        rgb(0x7dd3fc), 0.84f, 0.1f, 0.16f, 1.6f, 0.13f, 0.05f, 0f,
        blush = 0.34f, spread = 1.16f, breath = 0.036f,
    ),
    // Transient, ~2 s, always paired with a POP — see `overlay`.
    Mood.SUCCESS to Face(
        rgb(0x5eead4), 0.5f, -0.22f, 0.86f, 1.3f, 0f, 0f, 0f,
        eyeArc = 1f, blush = 1.0f, spread = 1.1f,
    ),
    Mood.FAILED to Face(
        rgb(0xfb7185), 0.68f, 0.45f, -0.5f, 1.1f, -0.07f, 0.02f, 0.22f,
        blush = 0.42f,
    ),
    // 和 CALM 同一个颜色 —— 这一条是反过来的决定。
    //
    // 这里原本是全表唯一一行去饱和的（连同脸颊一起褪色），理由是"睡着的球
    // 应该一眼看出是熄了"。但实际做出来的是**换了一种材质**：奶油球睡着时身体
    // 转灰、眼睛和嘴跟着转灰，于是"睡着"看起来像被关掉了，而不是它累了。
    // 睡是一种形状 —— 那条 `^ ^` 眼睛加上鼻子下面那颗水滴 —— 颜色不必替它
    // 承担这件事，而一旦它承担了，这颗球在一天里最安静的那几个小时里就不是
    // 它自己了。
    Mood.SLEEPING to Face(
        rgb(0x9db4ff), 0.1f, 0.06f, 0.16f, 0.45f, 0.11f, 0f, 0f,
        eyeArc = 0.85f, spread = 0.9f, blush = 0.16f, bubble = 1f,
    ),
)

/**
 * One-shot body gestures, layered on top of whatever [Face] is current.
 *
 * Separate from mood because they are events, not states: a mood is what the
 * ball *is*, an action is something that just *happened* to it. The ball can
 * nod while staying calm, and a task finishing is a single pop rather than a
 * new resting expression.
 */
enum class Action {
    NONE, HOP, NOD, SHAKE, POP, RECOIL,
    STRETCH, LOOK_AROUND, WIGGLE, BOUNCE,
}

/**
 * Seconds each [Action] takes. Indexed by ordinal — keep in sync with the
 * enum above. The idle fidgets run longer than the event gestures: a stretch
 * or a look-around is a "waking up my body" motion, and snapping through one
 * in half a second reads as a glitch rather than a stretch.
 */
val ACTION_SECONDS = floatArrayOf(
    0f,      // NONE
    0.52f,   // HOP
    0.42f,   // NOD
    0.5f,    // SHAKE
    0.46f,   // POP
    0.55f,   // RECOIL
    1.6f,    // STRETCH
    1.9f,    // LOOK_AROUND
    1.1f,    // WIGGLE
    0.9f,    // BOUNCE
)

/**
 * The idle fidget pool, with weights — the renderer picks one at random from
 * here (not from every [Action]) when the idle fidget timer fires. Body-only
 * on purpose: the face stays CALM throughout, so nothing in here can be
 * mistaken for a response, a failure or a speaker being active.
 */
val IDLE_ACTIONS: List<Pair<Action, Float>> = listOf(
    Action.STRETCH to 1.0f,
    Action.LOOK_AROUND to 1.0f,
    Action.WIGGLE to 1.0f,
    Action.BOUNCE to 0.7f,
    Action.HOP to 0.5f,
)

/**
 * Orbital ring config. Four rings, each with own radius, tilt, spin, flow speed.
 *
 * A [Torus] circles the XY plane about Z, and the camera looks down −Z — so
 * `rotX` near 0 leaves a ring face-on to the viewer, which draws a full ellipse
 * straight across the face. Every [rotX] here is therefore clustered near
 * ±π/2, where the ring is edge-on and reads as a belt around the body rather
 * than a hoop in front of it. Two of these used to sit at 0.4 and −1.1 and cut
 * through the eyes; the ball read as a planet instead of a character.
 *
 * Deviation from ±π/2 is the whole visual interest, so it's kept — just small.
 */
class RingSpec(
    val r: Float,
    val tube: Float,
    val rotX: Float, val rotY: Float, val rotZ: Float,  // local orientation (radians)
    val spin: Float,   // Y-axis spin rate (+/- so they don't tumble as a rigid body)
    val flow: Float,   // light-flow speed around the ring
    val base: Float,   // ambient alpha
)

val RINGS = arrayOf(
    RingSpec(1.10f, 0.012f, 1.46f, 0.2f, 0.2f, 0.5f, 0.42f, 0.18f),
    RingSpec(1.22f, 0.009f, 1.72f, 0f, -0.16f, -0.33f, -0.29f, 0.2f),
    RingSpec(1.33f, 0.0065f, -1.38f, 0.6f, 0.3f, 0.22f, 0.36f, 0.15f),
    RingSpec(1.44f, 0.005f, 1.9f, -0.5f, -0.26f, -0.14f, -0.19f, 0.1f),
)

const val DUST_COUNT = 96
const val TRAIL_COUNT = 2
val FADE = floatArrayOf(0.42f, 0.16f)

/**
 * How much of the ball sinks below the screen edge while it hangs off it.
 *
 * Two places have to agree on this and neither can derive it from the other:
 * `FloatingWindowUi` moves the window down by exactly this much, and
 * [EmotionBallRenderer] draws the paws on the line that creates. A half is the
 * value that puts that line through the ball's equator, which is where the
 * paws are — change this and they stop sitting on the edge.
 */
const val CLING_HIDDEN = 0.5f

/**
 * Radius of the *rendered* sphere as a fraction of the ball surface's shorter
 * side — what anyone placing something against the ball's outline needs.
 *
 * Not a taste value: it falls straight out of the camera. A 42° vertical fov at
 * z = 4.9 makes one world unit `1 / (2 tan 21°) / 4.9` of the viewport height,
 * and the ball is drawn at `RIM` = 0.9 of those. Measured on device at 546 px
 * square: 270 px across, i.e. 0.247 including the rim's glow.
 *
 * The 0.32 that used to be written by hand at the call sites is the *hit-test*
 * radius, deliberately generous so grazing taps count, and using it as the
 * drawn radius put CardUi's signboard a good 13 dp above the head it was
 * supposed to be resting on.
 *
 * The ball's *face* geometry — eye size and position, mouth, the z the face
 * plane sits at — lives in [BallLook], next to the material it has to agree
 * with. This file stays about moods.
 */
/**
 * 超过这个值，屏幕上那只眼睛就是 `^ ^` 这条弧，圆眼不是主角了。
 *
 * 两处共用一个数、一个意思：
 *   * 渲染器还要不要往眼睛里画瞳孔和高光；
 *   * 状态机还要不要眨眼 —— 眼皮是给睁着的眼睛合上的，当眼睛本身就是一条
 *     "已经合上"的弧时，眨一下只会把弧抽动一下。睡着的球每三秒抖一下就是
 *     这么来的。
 */
const val ARC_EYE_AT = 0.5f

const val SPHERE_RADIUS_FRACTION = 0.24f

private fun rgb(hex: Int): FloatArray = floatArrayOf(
    ((hex shr 16) and 0xff) / 255f,
    ((hex shr 8) and 0xff) / 255f,
    (hex and 0xff) / 255f,
)
