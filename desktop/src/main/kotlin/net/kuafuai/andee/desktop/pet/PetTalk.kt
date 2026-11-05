package net.kuafuai.andee.desktop.pet

import kotlin.random.Random

/**
 * What the pet says when you touch it, and the face it says it with.
 *
 * The lines are the character, not filler: Andee is an individual living on this Mac —
 * curious, a little proud, occasionally lazy, warm but nobody's servant. No 主人, no
 * "有什么可以帮您" — that voice belongs to a different product.
 *
 * Kept short on purpose: the bubble is two lines wide, and a pet that delivers a paragraph
 * is a notification, which is the one thing it must not be.
 */
object PetTalk {

    /** One line, in both interface languages. */
    data class Line(val zh: String, val en: String)

    /** The moods a pat can land on (IDLE and AIR are states, not reactions). */
    val MOODS = listOf(PetPose.HAPPY, PetPose.ANGRY, PetPose.SAD, PetPose.SLEEPY, PetPose.THINK)

    private val LINES: Map<PetPose, List<Line>> = mapOf(
        PetPose.HAPPY to listOf(
            Line("在呢在呢，别戳了。", "Here, here — quit poking."),
            Line("今天这台 Mac 归我管。", "This Mac is mine today."),
            Line("你终于想起我了。", "You finally remembered me."),
            Line("摸头是要收费的。", "Head pats are not free, you know."),
        ),
        PetPose.ANGRY to listOf(
            Line("再戳我就把你的窗口关上。", "Poke me again and I'll close your windows."),
            Line("我很忙的（其实没有）。", "I'm busy. (I'm not.)"),
            Line("哼。", "Hmph."),
            Line("你手很闲是吧。", "Nothing better to do with that hand?"),
        ),
        PetPose.SAD to listOf(
            Line("你是不是忘了我会饿。", "Did you forget I get hungry too?"),
            Line("算了，我自己待着。", "Never mind. I'll sit here."),
            Line("今天没人跟我说话。", "Nobody has talked to me all day."),
            Line("我在想一个很难的问题。", "I'm thinking about something hard."),
        ),
        PetPose.SLEEPY to listOf(
            Line("困。别吵。", "Sleepy. Quiet."),
            Line("五分钟，就五分钟。", "Five minutes. Just five."),
            Line("呼……", "Zzz…"),
            Line("你自己玩一会儿。", "Play by yourself for a bit."),
        ),
        PetPose.THINK to listOf(
            Line("我在想你这台 Mac 该整理一下了。", "I'm thinking this Mac needs tidying."),
            Line("刚才那个问题我还在算。", "Still working out that last question."),
            Line("如果我关掉一个窗口，你会不会发现。", "If I closed a window, would you notice?"),
            Line("别看我，我在思考。", "Don't look at me, I'm thinking."),
        ),
    )

    private val LANDING = listOf(
        Line("喂！", "Hey!"),
        Line("我又不是球。", "I'm not a ball."),
        Line("摔到了，你赔。", "That hurt. You owe me."),
        Line("再扔一次试试。", "Try that again. See what happens."),
    )

    private val WAKING = listOf(
        Line("唔……醒了。", "Mm… I'm up."),
        Line("谁？", "Who's there?"),
        Line("我才没有睡着。", "I was not asleep."),
    )

    private val NAPPING = listOf(
        Line("我先眯一会儿。", "Napping for a bit."),
        Line("没人管我，那我睡了。", "Nobody needs me, so I'm sleeping."),
    )

    /** A random reaction: the mood to wear and the line to say with it. */
    fun reaction(random: Random = Random.Default): Pair<PetPose, Line> {
        val mood = MOODS[random.nextInt(MOODS.size)]
        val lines = LINES.getValue(mood)
        return mood to lines[random.nextInt(lines.size)]
    }

    /** The face it makes after being thrown and landing. */
    fun landing(random: Random = Random.Default): Pair<PetPose, Line> =
        PetPose.ANGRY to LANDING[random.nextInt(LANDING.size)]

    fun waking(random: Random = Random.Default): Line = WAKING[random.nextInt(WAKING.size)]

    fun napping(random: Random = Random.Default): Line = NAPPING[random.nextInt(NAPPING.size)]
}
