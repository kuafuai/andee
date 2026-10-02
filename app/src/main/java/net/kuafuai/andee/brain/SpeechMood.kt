package net.kuafuai.andee.brain

import net.kuafuai.andee.ui.ball.Mood

/**
 * Emotion markers the model writes into its own reply.
 *
 * `[happy] 找到了，在第三页。[calm] 要我打开吗？`
 *
 * The ball has had six "emotions the model picks from" since it was written —
 * see [Mood]'s own KDoc — and nothing has ever picked one: four of the six were
 * unreachable, and the ball wore CALM through every answer it ever gave. This
 * is the channel that was missing. It is deliberately *in the text* rather than
 * a tool call or a JSON field, because the one thing the model reliably does is
 * write the next token, and a face is worth nothing if it arrives a round trip
 * after the sentence it belonged to.
 *
 * Three rules hold this together:
 *
 *  - **The vocabulary is wide, and it is a vocabulary rather than an enum.**
 *    It started as the six [Mood] names spelled exactly, and that failed the
 *    first time a user asked for a face it did not have: told to look angry,
 *    the model wrote `[angry]`, which matched nothing and was therefore shown
 *    on screen and read aloud as a word. The model is picking a *feeling*, so
 *    every ordinary name for one maps to the nearest of the six — in English
 *    and in Chinese, because this device mostly answers in Chinese.
 *  - **A bracket is only eaten when it is clearly not words.** `[12条]` out of
 *    a notification summary, `[END]` out of our own prompt, and the label half
 *    of every markdown link all have to survive. Letters-only excludes the
 *    first, SHOUTING excludes the second and a following `(` excludes the
 *    third; what is left is a lowercase word the model wrote in brackets,
 *    which is a face attempt whether or not we know the word. An unrecognised
 *    one is dropped silently and changes no face — the previous face holds,
 *    which is what a reply with one marker does anyway.
 *  - **Parsing happens before the text is logged or spoken, never after.** A
 *    marker that reaches [net.kuafuai.andee.ui.ChatHistory] is visible junk in
 *    the user's scrollback, and one that reaches 火山 gets read out loud as
 *    "happy". Both failures are silent in the code and obvious on the device.
 */
object SpeechMood {

    /**
     * What the model asked for, and where in the cleaned text it asked for it.
     *
     * [at] is an index into [Script.text] — the string *after* the markers are
     * gone — because that is the only string anything downstream ever sees.
     */
    data class Cue(val at: Int, val mood: Mood)

    /** A reply with its markers removed and recorded. */
    data class Script(val text: String, val cues: List<Cue>) {
        /** The face to wear from the first word. Null when the reply opened without one. */
        val opening: Mood? get() = cues.firstOrNull()?.takeIf { it.at == 0 }?.mood
    }

    /**
     * The six faces, by every name a model actually reaches for.
     *
     * It started as six words and that was the bug: asked to look angry, the
     * model wrote `[angry]` — a word with no face behind it — so the tag was
     * left alone, printed in the scrollback and read out loud as "angry",
     * which is all three of the failures this class exists to prevent, at
     * once. The vocabulary is wide because the model is choosing a *feeling*,
     * not calling an API, and the nearest of six faces is a far better answer
     * than the word itself in the user's ear.
     *
     * Chinese is in here for the same reason. This device answers in Chinese
     * most of the time, and a model writing Chinese reaches for 「生气」 well
     * before it reaches for `tense` no matter what the prompt lists.
     *
     * The voice-pipeline and work moods (LISTENING, SEARCHING, …) are
     * deliberately absent: those describe what the *device* is doing and are
     * owned by the code that does it. A model claiming to be SEARCHING while
     * it speaks is describing something that is not happening.
     */
    private val VOCABULARY: Map<String, Mood> = buildMap {
        fun List<String>.all(m: Mood) = forEach { put(it, m) }

        listOf("calm", "neutral", "normal", "relaxed", "平静", "冷静", "正常")
            .all(Mood.CALM)
        listOf(
            "happy", "glad", "pleased", "excited", "cheerful", "proud", "delighted",
            "开心", "高兴", "快乐", "兴奋", "得意",
        ).all(Mood.HAPPY)
        listOf(
            "curious", "interested", "surprised", "puzzled", "confused", "wondering",
            "好奇", "疑惑", "困惑", "惊讶", "惊奇",
        ).all(Mood.CURIOUS)
        listOf(
            "tense", "angry", "mad", "annoyed", "frustrated", "irritated", "upset",
            "生气", "愤怒", "恼火", "烦躁", "不满",
        ).all(Mood.TENSE)
        listOf(
            "anxious", "nervous", "worried", "scared", "afraid", "uneasy", "panicked",
            "焦虑", "紧张", "担心", "不安", "害怕",
        ).all(Mood.ANXIOUS)
        listOf(
            "concerned", "sad", "sorry", "apologetic", "disappointed", "unhappy", "serious",
            "难过", "抱歉", "遗憾", "失望", "担忧", "严肃",
        ).all(Mood.CONCERNED)
    }

    /**
     * Anything bracket-shaped that *might* be a marker. Deciding whether it
     * really is one is [parse]'s job — this only has to be cheap and to
     * exclude the shapes that obviously are not.
     *
     * Letters only, so `[12条]` from a notification summary can never match.
     * Three bracket families: ASCII, fullwidth `［］`, and 【】, which a model
     * writing Chinese types about as readily as either.
     */
    private val MARKER = Regex("[\\[\\uFF3B\\u3010]\\s*([A-Za-z\\u4e00-\\u9fff]{1,12})\\s*[\\]\\uFF3D\\u3011]")

    /**
     * Is this unknown tag one the model meant as a face, or words it meant the
     * user to hear?
     *
     * Leaving unknown tags alone was the original rule and it is what put
     * "angry" in the user's ear. But deleting *every* unknown bracket is worse
     * — `[END]` is a sentinel in our own prompt, and a notification badge
     * reads `[SALE]`. The discriminator that actually separates them is case:
     * a model writing a feeling types it in lowercase, and sentinels and
     * badges are SHOUTED. Markdown link labels are excluded by the `(` that
     * always follows them.
     *
     * A dropped tag changes no face. Holding the previous one is the same
     * degradation as a reply with a single marker, which is already the
     * common case.
     */
    private fun droppable(word: String, after: Char?): Boolean =
        word.all { it in 'a'..'z' || it in 'A'..'Z' } &&
            word != word.uppercase() &&
            after != '('

    /**
     * Pull the markers out, keeping where each one fell.
     *
     * The fiddly part is the whitespace the model typed *around* the tag, and
     * it has to be fiddly because the two languages want opposite things.
     * `[happy] 找到了` must not keep a leading space (it is audible as a pause
     * at the top of the reply), and `ok [happy] yes` must not lose the one
     * between the words. So: whitespace on either side of a removed marker
     * collapses to exactly one space, and to nothing at all when the marker
     * opened the reply.
     */
    fun parse(raw: String): Script {
        if (raw.isEmpty()) return Script(raw, emptyList())
        val matches = MARKER.findAll(raw).toList()
        if (matches.isEmpty()) return Script(raw, emptyList())

        val out = StringBuilder(raw.length)
        val cues = ArrayList<Cue>(matches.size)
        var cursor = 0
        for (m in matches) {
            val word = m.groupValues[1]
            val mood = VOCABULARY[word.lowercase()]
            // Not a face. Either drop it silently or leave it to be spoken —
            // and "leave it" means leaving the brackets too, so this branch
            // must not touch [cursor] or the whitespace around the match.
            if (mood == null &&
                !droppable(word, raw.getOrNull(m.range.last + 1))
            ) continue

            out.append(raw, cursor, m.range.first)
            cursor = m.range.last + 1

            val spaceBefore = out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')
            var spaceAfter = false
            // Only spaces and tabs: a newline is the model's own paragraph
            // break and belongs to the text, not to the marker.
            while (cursor < raw.length && (raw[cursor] == ' ' || raw[cursor] == '\t')) {
                cursor++
                spaceAfter = true
            }
            while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) {
                out.setLength(out.length - 1)
            }
            // The space is a *separator*, so it is only owed when there is
            // something on both sides. `好的 [calm]` ends the reply and must
            // not end it on a space.
            if (out.isNotEmpty() && cursor < raw.length && (spaceBefore || spaceAfter)) {
                out.append(' ')
            }

            if (mood == null) continue  // dropped, but it names no face

            // Two markers with nothing between them: the later one wins, which
            // is what the model meant by writing it second.
            if (cues.isNotEmpty() && cues.last().at == out.length) cues.removeAt(cues.size - 1)
            cues.add(Cue(out.length, mood))
        }
        out.append(raw, cursor, raw.length)

        val text = out.toString()
        // A reply that was *nothing but* markers has had its words removed by
        // something other than us, and speaking "" is a socket opened for
        // silence. Give the raw text back and keep the faces.
        if (text.isBlank() && raw.isNotBlank()) return Script(raw, emptyList())
        return Script(text, cues.filter { it.at <= text.length })
    }

    /** [parse] for callers that only need the words. */
    fun strip(raw: String): String = parse(raw).text
}
