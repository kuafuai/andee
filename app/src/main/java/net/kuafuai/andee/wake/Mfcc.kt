package net.kuafuai.andee.wake

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mel-frequency cepstral coefficients, hand-rolled.
 *
 * Twelve numbers per 10 ms of audio, and the only thing the wake word ever
 * compares. Written out rather than pulled in because the alternative is a
 * speech library measured in megabytes for the one routine below, and because
 * the pipeline has to match *exactly* between enrollment and listening — a
 * template stored with one filterbank and matched against another is silently
 * wrong, not broken, which is the worst way for this to fail.
 *
 * The parameters are the textbook ASR front end: 25 ms window every 10 ms,
 * 26 mel bands from 300 Hz to 8 kHz, keep c1..c12. Two deliberate choices on
 * top of that, both aimed at "the same phrase counts as the same phrase":
 *
 * - **c0 is dropped.** It is overall loudness, so keeping it would make the
 *   same words said louder into a different phrase.
 * - **CMVN** ([normalise]) — per-utterance mean and variance normalisation.
 *   This is what makes the distance scale stable enough for a *fixed* reject
 *   threshold to mean anything across rooms, distances and microphones; a raw
 *   cepstrum carries the channel, and the channel changes every time the user
 *   picks the tablet up.
 */
object Mfcc {

    const val SAMPLE_RATE = 16_000
    const val COEFFS = 12

    /** 25 ms at 16 kHz. */
    private const val FRAME = 400

    /** 10 ms — the standard hop, and what makes DTW's time axis meaningful. */
    const val HOP = 160

    private const val FFT_N = 512
    private const val MEL_BANDS = 26
    private const val LOW_HZ = 300f
    private const val HIGH_HZ = 8000f
    private const val PRE_EMPHASIS = 0.97f

    private val window = FloatArray(FRAME) {
        (0.54 - 0.46 * cos(2.0 * PI * it / (FRAME - 1))).toFloat()
    }

    /** `[band][fftBin]` triangular weights; mostly zero, kept dense for clarity. */
    private val filters: Array<FloatArray> = buildFilters()

    /** `[coeff][band]`, skipping k=0 so the output is already c1..c12. */
    private val dct: Array<FloatArray> = Array(COEFFS) { k ->
        FloatArray(MEL_BANDS) { m ->
            cos(PI * (k + 1) * (m + 0.5) / MEL_BANDS).toFloat()
        }
    }

    /**
     * PCM (16-bit mono, [SAMPLE_RATE]) → one `[COEFFS]`-long vector per 10 ms,
     * already normalised. Empty if the clip is shorter than a single window.
     */
    fun features(pcm: ShortArray, from: Int = 0, to: Int = pcm.size): Array<FloatArray> {
        val n = to - from
        if (n < FRAME) return emptyArray()

        // Pre-emphasis: tilts the spectrum up so the high formants, which carry
        // most of what distinguishes one word from another, aren't buried under
        // the low-frequency energy of the voice.
        val x = FloatArray(n)
        x[0] = pcm[from] / 32768f
        for (i in 1 until n) {
            x[i] = (pcm[from + i] - PRE_EMPHASIS * pcm[from + i - 1]) / 32768f
        }

        val frames = (n - FRAME) / HOP + 1
        val out = Array(frames) { FloatArray(COEFFS) }
        val re = FloatArray(FFT_N)
        val im = FloatArray(FFT_N)
        val energies = FloatArray(MEL_BANDS)

        for (f in 0 until frames) {
            val off = f * HOP
            java.util.Arrays.fill(re, 0f)
            java.util.Arrays.fill(im, 0f)
            for (i in 0 until FRAME) re[i] = x[off + i] * window[i]
            fft(re, im)

            for (b in 0 until MEL_BANDS) {
                var sum = 0f
                val w = filters[b]
                for (k in w.indices) {
                    if (w[k] == 0f) continue
                    sum += w[k] * (re[k] * re[k] + im[k] * im[k])
                }
                // Floor rather than guard: log of a silent band is -inf, and one
                // -inf poisons every distance the frame takes part in.
                energies[b] = ln(sum.coerceAtLeast(1e-10f))
            }
            val row = out[f]
            for (k in 0 until COEFFS) {
                var s = 0f
                val d = dct[k]
                for (b in 0 until MEL_BANDS) s += d[b] * energies[b]
                row[k] = s
            }
        }
        normalise(out)
        return out
    }

    /**
     * Subtract the mean and divide by the standard deviation, per coefficient,
     * over the whole clip.
     *
     * The mean half removes the channel (room, distance, which microphone);
     * the variance half is what puts every recording on one scale, so a
     * distance of 2.5 means the same thing tomorrow as it did at enrollment.
     * Without it the threshold would have to be re-learned per environment,
     * which for a wake word means it just stops working one day.
     */
    private fun normalise(frames: Array<FloatArray>) {
        if (frames.isEmpty()) return
        for (k in 0 until COEFFS) {
            var mean = 0f
            for (f in frames) mean += f[k]
            mean /= frames.size
            var varSum = 0f
            for (f in frames) {
                val d = f[k] - mean
                varSum += d * d
            }
            val sd = sqrt(varSum / frames.size).coerceAtLeast(1e-6f)
            for (f in frames) f[k] = (f[k] - mean) / sd
        }
    }

    private fun buildFilters(): Array<FloatArray> {
        fun toMel(hz: Float) = 2595f * log10(1f + hz / 700f)
        fun toHz(mel: Float) = 700f * (Math.pow(10.0, (mel / 2595f).toDouble()).toFloat() - 1f)

        val lo = toMel(LOW_HZ)
        val hi = toMel(HIGH_HZ)
        val points = IntArray(MEL_BANDS + 2) { i ->
            val mel = lo + (hi - lo) * i / (MEL_BANDS + 1)
            ((FFT_N + 1) * toHz(mel) / SAMPLE_RATE).toInt().coerceIn(0, FFT_N / 2)
        }
        return Array(MEL_BANDS) { b ->
            val w = FloatArray(FFT_N / 2 + 1)
            val a = points[b]
            val peak = points[b + 1]
            val c = points[b + 2]
            for (k in a until peak) if (peak > a) w[k] = (k - a).toFloat() / (peak - a)
            for (k in peak until c) if (c > peak) w[k] = (c - k).toFloat() / (c - peak)
            if (peak in w.indices) w[peak] = 1f
            w
        }
    }

    /** In-place iterative radix-2 Cooley-Tukey; [re].size must be a power of two. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val pr = re[i + k + half]
                    val pi = im[i + k + half]
                    val vr = pr * cr - pi * ci
                    val vi = pr * ci + pi * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + half] = ur - vr
                    im[i + k + half] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}
