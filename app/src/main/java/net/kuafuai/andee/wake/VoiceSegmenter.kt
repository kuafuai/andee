package net.kuafuai.andee.wake

import kotlin.math.sqrt

/**
 * Cuts a continuous microphone stream into "somebody just said something"
 * clips.
 *
 * Plain energy VAD against an adaptive noise floor, because the wake word only
 * needs to know *where* an utterance is, not what it is — [Mfcc] and [Dtw]
 * decide the rest. Anything heavier would be running a model on every second
 * of room tone, all day, for a phrase that arrives twice an hour.
 *
 * The floor adapts because the alternative is a fixed threshold, and a fixed
 * threshold is deaf in a kitchen and permanently triggered next to a fan. It
 * only moves while the room is quiet — a floor that learned during speech
 * would chase the user's own voice upward and cut them off mid-phrase.
 *
 * Pre-roll ([PREROLL_FRAMES]) matters more than it looks: a voice crosses the
 * threshold a few frames *after* it starts, and the missing onset is the
 * consonant — the 'h' of "嘿" — which is most of what distinguishes the wake
 * phrase from every other short utterance.
 *
 * Not thread-safe; it belongs to whichever thread is reading the microphone.
 */
class VoiceSegmenter(private val onSegment: (ShortArray) -> Unit) {

    private val frame = ShortArray(FRAME)
    private var filled = 0

    private var floor = -1f
    private var speech = false
    private var loudRun = 0
    private var quietRun = 0

    /** Pre-roll ring, and the utterance once one starts. */
    private val preroll = ArrayDeque<ShortArray>()
    private var collected = ArrayList<ShortArray>()

    fun feed(pcm: ShortArray, count: Int) {
        var off = 0
        while (off < count) {
            val take = minOf(FRAME - filled, count - off)
            System.arraycopy(pcm, off, frame, filled, take)
            filled += take
            off += take
            if (filled == FRAME) {
                filled = 0
                onFrame()
            }
        }
    }

    /** Forget any partial utterance — used when the microphone is handed away. */
    fun reset() {
        filled = 0
        speech = false
        loudRun = 0
        quietRun = 0
        preroll.clear()
        collected = ArrayList()
        floor = -1f
    }

    private fun onFrame() {
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        val rms = sqrt(sum / FRAME).toFloat()

        if (floor < 0f) floor = rms
        val startAt = maxOf(floor * START_MULT, ABS_MIN)
        val endAt = maxOf(floor * END_MULT, ABS_MIN * 0.7f)

        if (!speech) {
            // Only while idle, and only downward-biased: rising slowly means a
            // fan that starts up is eventually accepted as silence, while a
            // voice that lasts a second barely moves it.
            floor = if (rms < floor) floor * 0.9f + rms * 0.1f else floor * 0.995f + rms * 0.005f

            preroll.addLast(frame.copyOf())
            if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()

            if (rms > startAt) {
                loudRun++
                if (loudRun >= START_FRAMES) {
                    speech = true
                    quietRun = 0
                    collected = ArrayList(preroll)
                    preroll.clear()
                }
            } else {
                loudRun = 0
            }
            return
        }

        collected.add(frame.copyOf())
        if (rms < endAt) {
            quietRun++
            if (quietRun >= END_FRAMES) {
                emit()
                return
            }
        } else {
            quietRun = 0
        }
        // Too long to be a two-syllable wake phrase. Dropped rather than
        // truncated: a sentence clipped at 1.8 s matched against the templates
        // is a coin toss, and the user talking to somebody else in the room is
        // by far the most common way to get here.
        if (collected.size > MAX_FRAMES) {
            speech = false
            loudRun = 0
            quietRun = 0
            collected = ArrayList()
        }
    }

    private fun emit() {
        val frames = collected
        speech = false
        loudRun = 0
        quietRun = 0
        collected = ArrayList()
        preroll.clear()
        if (frames.size < MIN_FRAMES) return
        val out = ShortArray(frames.size * FRAME)
        var i = 0
        for (f in frames) {
            System.arraycopy(f, 0, out, i, FRAME)
            i += FRAME
        }
        onSegment(out)
    }

    private companion object {
        /** 10 ms at 16 kHz — same grid as [Mfcc.HOP], so segment bounds land on frames. */
        const val FRAME = Mfcc.HOP

        const val PREROLL_FRAMES = 15      // 150 ms of run-up
        const val START_FRAMES = 3         // 30 ms above the line before it counts
        const val END_FRAMES = 30          // 300 ms of quiet ends the utterance
        const val MIN_FRAMES = 35          // 350 ms — shorter than "嘿 Andee" can be
        const val MAX_FRAMES = 180         // 1.8 s — longer than it can be

        const val START_MULT = 3.5f
        const val END_MULT = 2.0f

        /** RMS in 16-bit units; below this it is room tone however quiet the room. */
        const val ABS_MIN = 350f
    }
}
