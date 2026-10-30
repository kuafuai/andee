package net.kuafuai.andee.ui.ball

/**
 * Everything a mood adds on top of [Face], and the reason the two are separate
 * classes: [Face] is a row of numbers every look interprets, and each number is
 * a *continuous* dial. These are the discrete things — a wink, a star, a shield —
 * that only the looks with [BallLook.gestures] draw, which is every look there is;
 * the flag is kept because a look without it ignores the whole table rather than
 * part of it, so adding a mood here cannot reach a look that has opted out.
 *
 * Each value is a target, not a state. [EmotionState] tweens toward it, which is
 * what makes a star pop in instead of appearing.
 */
class Pose(
    /** The viewer's-right eye closes into an arc and the other stays open. */
    val wink: Float = 0f,
    /** Where the eyes look, −1..1; positive X is the viewer's right, positive Y is up. */
    val lookX: Float = 0f,
    val lookY: Float = 0f,
    /**
     * 0 = round eyes, 1 = the top of each eye sliced flat and rolled inward: a
     * determined look. **Negative rolls the other way and slices nothing** — the
     * slant of a worried brow, which is what the sad rows need: their [Face.brow]
     * rolls the eyes the angry way, and this is what turns it back.
     */
    val lidCut: Float = 0f,
    /** Resting body squash on top of breathing. Negative Y flattens, positive X widens. */
    val sqx: Float = 0f,
    val sqy: Float = 0f,
    /**
     * Where the horns rest, in degrees from their usual lean: **negative stands them
     * up, positive lets them flop outward.** The spring in [EmotionState] settles
     * on it, which is why a mood change makes them spring rather than snap. Perked
     * for curious, listening and celebrating; flopped for sad, tired and failed —
     * the one part of the head that can show a mood while the face is looking away.
     */
    val hornLean: Float = 0f,
    val stars: Float = 0f,
    /**
     * The fan of motion strokes beside the ball. It used to be drawn around a hand
     * raised at the side; the hand is gone and the strokes are not, deliberately —
     * see the [POSES] row for [Mood.WAVE].
     */
    val sparkL: Float = 0f,
    /** A small heart floating beside the face. */
    val heart: Float = 0f,
    val sparkTop: Float = 0f,
    val shield: Float = 0f,
    val drop: Float = 0f,
    /** Closed eyes drawn as `⌣` instead of `⌢`: a lid, not a laugh. Not tweened — it flips. */
    val arcFlip: Boolean = false,
) {
    companion object {
        val NONE = Pose()
    }
}

const val PROP_STARS = 0
const val PROP_SPARK_L = 1
const val PROP_HEART = 2
const val PROP_SPARK_TOP = 3
const val PROP_SHIELD = 4
const val PROP_DROP = 5
const val PROP_COUNT = 6

/**
 * The poses. The numbers were read off the reference sketches (wink, wave, think,
 * rest, protect, celebrate) the same way the look's own were off the picture:
 * measured on the sketch, divided by the head's width, times the body's 1.74
 * diameter. Where there was no sketch — the work moods, the worried ones — they
 * are the nearest gesture a person would make.
 *
 * Most of these used to raise a hand and no longer do — the ball only grows paws
 * now, and only to hang off the screen edge ([EmotionBallRenderer.drawHands]).
 * A row that lost its whole pose is not a broken row: a mood is read through its
 * own [Face] row and its body motion first, and those never came from here.
 * [Mood.HOLDING] is the one left with nothing else at all, on purpose.
 */
val POSES: Map<Mood, Pose> = mapOf(
    Mood.CALM to Pose(),

    // A pleased face with a little burst above the head.
    Mood.HAPPY to Pose(sparkTop = 1f, hornLean = -8f),

    // Eyes up and to one side, as if something is on the shelf above you, and the
    // horns stand straight up to listen to it.
    Mood.CURIOUS to Pose(lookX = 0.6f, lookY = 0.6f, hornLean = -18f),

    // Annoyed rather than hurt: the lids come down, the horns lie back a little.
    Mood.TENSE to Pose(lidCut = 0.8f, hornLean = 10f),

    // A bead of sweat, and the shiver the Face row already supplies.
    Mood.ANXIOUS to Pose(drop = 1f, lookY = 0.1f, lidCut = -1f, hornLean = 14f),

    // Eyes cast a little down: sorry, and about to say so.
    Mood.CONCERNED to Pose(lookY = -0.2f, lidCut = -1f, hornLean = 18f),

    // wink.webp — a closed eye and a heart floating beside the cheek. The reference
    // has a pointing hand; four hands were tried here (a V, a pointing finger, a
    // thumbs up, a fat thumb) and every one misread at eighty dp — the pointer as the
    // rude finger — so the wink carries a heart, which cannot be misread.
    Mood.WINK to Pose(wink = 1f, heart = 1f, hornLean = -6f),

    // wave.webp — the fan of motion strokes that used to come off a hand raised at
    // the side. **The hand is gone and the strokes are kept on purpose**: three marks
    // beside a ball that is leaning and breathing still read as an event happening
    // to it, which is all this gesture ever said at eighty dp. One field to delete
    // here if they read as stray marks instead.
    Mood.WAVE to Pose(sparkL = 1f, hornLean = -10f),

    // protect.webp — brows set, a shield held out.
    Mood.PROTECT to Pose(lidCut = 1f, shield = 1f, hornLean = -8f),

    // celebrate.webp — stars, a grin; the horns spring.
    Mood.CELEBRATE to Pose(stars = 1f, sparkTop = 1f, hornLean = -16f),

    // rest.webp — eyes shut, horns slack. The body used to squash (sqx 0.08,
    // sqy −0.13) and the ball flattened into a puddle; a round sleeping ball with
    // the lid-arc eyes and the floppy horns reads as napping on its own.
    Mood.REST to Pose(arcFlip = true, hornLean = 24f),

    // The mic is open. What says "listening" is the horns standing up and the head
    // cocking, both of which are the Face row's — plus its open mouth [Face.gape],
    // which is the shape change that survives being drawn a thumb wide.
    Mood.LISTENING to Pose(hornLean = -16f),

    // The signboard is up, and it is a real card in the ball's own window
    // ([FloatingWindowUi.showSignboard]), not a prop. This row is empty on purpose:
    // the mood exists to give the ball a waiting face while it holds the card, and
    // the yellow [Face] row is where that lives. It used to raise both fists, which
    // said "holding" to nobody at this size.
    Mood.HOLDING to Pose(),

    // think.webp — eyes up and to the side, and the gaze drift the rim and scan light
    // ride on.
    Mood.THINKING to Pose(lookX = 0.12f, lookY = 0.7f, hornLean = -8f),

    // The mouth is the talk envelope's, which opens the thin smile rather than
    // swapping it for another shape. No pose, so the face is clear while it is
    // being listened to.
    Mood.SPEAKING to Pose(),

    // Driving an app: eyes down and to the side with the head cocked, which reads as
    // "looking at something that isn't you" and is exactly true. The speed is carried
    // by the rim, core and scan light off `busy`, not from here.
    Mood.SEARCHING to Pose(lookY = -0.15f, hornLean = -4f),

    Mood.SUCCESS to Pose(stars = 1f, sparkTop = 1f, hornLean = -16f),

    // Eyes down, a bead of sweat, slumped, horns flopped.
    Mood.FAILED to Pose(lookY = -0.5f, lidCut = -1.2f, drop = 1f, sqy = -0.05f, hornLean = 24f),

    Mood.SLEEPING to Pose(arcFlip = true, hornLean = 24f),
)
