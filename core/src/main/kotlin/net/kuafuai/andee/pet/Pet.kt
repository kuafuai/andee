package net.kuafuai.andee.pet

import net.kuafuai.andee.ui.ball.Mood

/**
 * One drawing of a character. A pose is a file; which one is shown is decided from Andee's state (`PetMind`).
 * [fallback] is what a look without this drawing shows instead — only `girl` has the poses the ball's states added
 * on 2026-11-05, and the others must still say "listening" with something closer than the plain idle.
 */
enum class PetPose(val file: String, val fallback: PetPose? = null) {
    IDLE("idle"),
    HAPPY("happy"),

    /** Thrown or falling: hair and scarf up, knees tucked. Falls back to [IDLE] when a look has no such drawing. */
    AIR("air"),

    // The faces a pat can land on (see PetTalk). Each is a drawing of its own rather than a
    // transform of the idle one: a squashed PNG cannot frown, and the point of a pat is that
    // the pet answers with a face.
    ANGRY("angry"),
    SAD("sad"),
    SLEEPY("sleepy"),
    THINK("think"),

    /** Idle act only: holding a bubble-tea cup. A prop, not a reaction — it has no line of dialogue. */
    TEA("tea"),

    /** Idle act only: eating from a little hotpot. The kind of scene a desktop pet earns its keep with. */
    HOTPOT("hotpot"),

    // The ball's states, since the pet is Andee's body on the Mac (`PetMind` maps a `Mood` onto these).
    /** The mic is open. The one face that must never read as idle. */
    LISTEN("listen", THINK),
    /** Reading the answer out. */
    SPEAK("speak", HAPPY),
    /** A task is running and it is acting (between model calls it is [THINK]). */
    SEARCH("search", THINK),
    /** A question card is up, waiting for the user. */
    HOLDING("holding"),
    CURIOUS("curious", THINK),
    ANXIOUS("anxious", SAD),
    WINK("wink", HAPPY),
    CELEBRATE("celebrate", HAPPY),
    PROTECT("protect", THINK),
    /** Fast asleep (as opposed to [SLEEPY], a yawn). */
    SLEEP("sleep", SLEEPY),

    /**
     * Docked into the screen's right edge on the phone: holding the edge with both hands, leaning out round it.
     * The pet's version of the ball's side cling. Only the docked tab draws it.
     */
    PEEK("peek", IDLE),
}

/**
 * One preset pet: who it is, independent of where its pictures live (the Mac reads them from its jar, the phone
 * from its assets). Each is a character like a ball look — a voice and a manner of its own; blank is Andee's.
 */
data class PetCharacter(
    val id: String,
    val zh: String,
    val en: String,
    /** How tall it stands at scale 100, in dp, on the Mac's desktop. */
    val baseHeight: Int,
    val voice: String = "",
    val persona: String = "",
)

object PetCharacters {

    /**
     * Five characters (2026-11-05). Andee itself — the plush ball of the brand illustrations — is first and is the
     * default; the cream girl is plain Andee drawn as a girl. The cobalt pair that used to be here were the same two
     * drawn in blue, and the quiet girl after them was replaced by the chubby fish; a saved `blue-girl` / `lady` reads as the fish and `blue-boy` / `beau` (the gentle boyfriend, replaced by the kitten) as the kitten ([byId]).
     */
    val PRESETS = listOf(
        PetCharacter("andee", "Andee", "Andee", baseHeight = 120),
        PetCharacter("girl", "奶油金", "Cream", baseHeight = 210),
        PetCharacter("boy", "奶油金·短发", "Cream short", baseHeight = 215,
            voice = "zh_male_yangguangqingnian_uranus_bigtts", persona = PetPersonas.CREAM_SHORT),
        PetCharacter("fish", "大肥鱼", "Chubby fish", baseHeight = 200,
            voice = "zh_female_tianmeixiaoyuan_uranus_bigtts", persona = PetPersonas.FISH),
        PetCharacter("cat", "小猫", "Kitten", baseHeight = 150,
            voice = "zh_female_roumeinvyou_uranus_bigtts", persona = PetPersonas.CAT),
    )

    private val RENAMED = mapOf("blue-girl" to "fish", "blue-boy" to "cat", "lady" to "fish", "beau" to "cat")

    /** The id a saved value means now (a renamed preset reads as its successor). */
    fun canonical(id: String): String = RENAMED[id.trim()] ?: id.trim()

    /** A preset by id, or null (a custom picture, a typo). */
    fun byId(id: String): PetCharacter? = canonical(id).let { want -> PRESETS.firstOrNull { it.id == want } }

    /** One line a settings card shows under the picked look: who this is. */
    fun blurb(id: String, zh: Boolean): String = when (canonical(id)) {
        "andee" -> if (zh) "Andee 本尊：它自己的声音和脾气。" else "Andee itself: its own voice and manner."
        "boy" -> if (zh) "阳光少年：男声，爽快直接。" else "A sunny boy: a young man's voice, quick and direct."
        "fish" -> if (zh) "大肥鱼：甜甜的女声，软软的、有点害羞，干活很认真，总惦记着吃。" else "A chubby little whale maid: a sweet voice, shy, earnest, always a little hungry."
        "cat" -> if (zh) "小猫：和小球的小猫同一个声音，有点傲娇，偶尔喵一声，其实很黏你。" else "A kitten: the ball kitten's voice, a bit proud, the odd meow, fond of you underneath."
        else -> if (zh) "就是 Andee：她自己的声音和脾气。" else "Plain Andee: her own voice and manner."
    }
}

/**
 * What the pet wears for Andee's state — one rule for both bodies, so the Mac and the phone cannot disagree about
 * which drawing means "listening".
 */
object PetMind {

    /**
     * Andee's state as a pose, or null when idle (the pet's own idle acts and naps run then). [face] is the ball's
     * own decision; [spoken] the face the model wrote for the line being read out; [acting] a task between tool
     * calls rather than waiting on the model; [holding] a question or approval card is up.
     */
    fun statePose(face: Mood, spoken: Mood?, acting: Boolean, holding: Boolean): PetPose? {
        if (holding) return PetPose.HOLDING
        return when (face) {
            Mood.LISTENING -> PetPose.LISTEN
            Mood.SPEAKING -> spoken?.let(::emotionPose) ?: PetPose.SPEAK
            Mood.THINKING, Mood.SEARCHING -> if (acting) PetPose.SEARCH else PetPose.THINK
            else -> null
        }
    }

    /** The face the model wrote for a line (`[happy]`, `[生气]` …, see `SpeechMood`), as a drawing. */
    fun emotionPose(m: Mood): PetPose = when (m) {
        Mood.HAPPY, Mood.WAVE -> PetPose.HAPPY
        Mood.CURIOUS -> PetPose.CURIOUS
        Mood.TENSE -> PetPose.ANGRY
        Mood.ANXIOUS -> PetPose.ANXIOUS
        Mood.CONCERNED, Mood.FAILED -> PetPose.SAD
        Mood.WINK -> PetPose.WINK
        Mood.PROTECT -> PetPose.PROTECT
        Mood.CELEBRATE, Mood.SUCCESS -> PetPose.CELEBRATE
        Mood.REST, Mood.SLEEPING -> PetPose.SLEEPY
        else -> PetPose.SPEAK
    }

    /** The drawing a settings card shows for an expression the user picked to look at. */
    fun previewPose(m: Mood): PetPose = when (m) {
        Mood.CALM -> PetPose.IDLE
        Mood.LISTENING -> PetPose.LISTEN
        Mood.THINKING -> PetPose.THINK
        Mood.SEARCHING -> PetPose.SEARCH
        Mood.HOLDING -> PetPose.HOLDING
        Mood.SPEAKING -> PetPose.SPEAK
        Mood.SLEEPING -> PetPose.SLEEP
        else -> emotionPose(m)
    }

    /** What she does on her own now and then while idle. */
    val IDLE_ACTS = listOf(PetPose.THINK, PetPose.HAPPY, PetPose.SLEEPY, PetPose.TEA, PetPose.HOTPOT)

    /** Three quiet minutes, then a nap of 40-80 s; she wakes by herself. */
    const val SLEEP_AFTER_MS = 180_000L
    const val NAP_MIN_MS = 40_000L
    const val NAP_SPAN_MS = 40_000L
}
