package net.kuafuai.andee.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * The wake word's only testable half.
 *
 * Whether it recognises a voice can only be answered by a person saying "嘿
 * Andee" at a device — but whether the front end produces finite, comparable
 * numbers is arithmetic, and getting that wrong is silent: a NaN from a log of
 * zero, or a distance scale that drifts, turns into "it just never triggers"
 * with nothing in the log to say why.
 */
class WakeDspTest {

    private fun tone(hz: Double, ms: Int, amp: Double = 8000.0, seed: Int = 1): ShortArray =
        phrase(listOf(hz to ms), amp, seed)

    /**
     * A crude stand-in for an utterance: a run of differently-pitched segments.
     *
     * A single steady tone will not do, and the reason is worth keeping. CMVN
     * ([Mfcc]) divides each coefficient by its variance *across the clip*, so a
     * stationary signal — where every frame is the same — normalises down to
     * its own noise and the distances come out random. Speech is never
     * stationary; a fan is, which is one reason room tone does not match the
     * templates.
     */
    private fun phrase(
        parts: List<Pair<Double, Int>>,
        amp: Double = 8000.0,
        seed: Int = 1,
        tempo: Double = 1.0,
    ): ShortArray {
        val rnd = Random(seed)
        val out = ArrayList<Short>()
        for ((hz, ms) in parts) {
            val n = (Mfcc.SAMPLE_RATE * ms * tempo / 1000).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / Mfcc.SAMPLE_RATE
                // Two harmonics plus a little noise: a flat sine has almost no
                // spectral structure and every mel band ends up at the floor.
                val v = amp * (sin(2 * PI * hz * t) + 0.5 * sin(4 * PI * hz * t)) +
                    rnd.nextDouble(-200.0, 200.0)
                out.add(v.toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        return ShortArray(out.size) { out[it] }
    }

    private val hello = listOf(200.0 to 150, 620.0 to 150, 320.0 to 200, 880.0 to 150)
    private val somethingElse = listOf(900.0 to 150, 260.0 to 150, 940.0 to 200, 420.0 to 150)

    @Test
    fun featuresAreFiniteAndShaped() {
        val f = Mfcc.features(tone(220.0, 500))
        assertEquals(500 / 10 - 2, f.size)   // 10 ms hop, minus the 25 ms window tail
        for (frame in f) {
            assertEquals(Mfcc.COEFFS, frame.size)
            for (v in frame) assertTrue("non-finite coefficient: $v", v.isFinite())
        }
    }

    @Test
    fun silenceDoesNotProduceNaN() {
        val f = Mfcc.features(ShortArray(8000))
        for (frame in f) for (v in frame) assertTrue("non-finite: $v", v.isFinite())
    }

    @Test
    fun identicalClipsAlignAtZero() {
        val f = Mfcc.features(phrase(hello))
        assertEquals(0f, Dtw.distance(f, f), 1e-4f)
    }

    /** The property the threshold rests on: same sound near, different sound far. */
    @Test
    fun sameSoundIsCloserThanDifferentSound() {
        val a = Mfcc.features(phrase(hello, seed = 1))
        // Same "phrase" said 15% slower and a lot quieter — what a second take
        // from the same person actually looks like.
        val again = Mfcc.features(phrase(hello, amp = 3000.0, seed = 2, tempo = 1.15))
        val other = Mfcc.features(phrase(somethingElse, seed = 3))
        val near = Dtw.distance(a, again)
        val far = Dtw.distance(a, other)
        assertTrue("same sound scored $near, different scored $far", near < far)
    }

    /** Clips too different in length are refused rather than warped into agreement. */
    @Test
    fun wildLengthMismatchIsRejected() {
        val short = Mfcc.features(phrase(hello, tempo = 0.5))
        val long = Mfcc.features(phrase(hello, tempo = 2.0))
        assertEquals(Dtw.NO_MATCH, Dtw.distance(short, long), 0f)
    }
}
