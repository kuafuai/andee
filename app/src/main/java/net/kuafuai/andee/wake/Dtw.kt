package net.kuafuai.andee.wake

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dynamic time warping over [Mfcc] frames.
 *
 * What it buys over comparing the two clips frame-for-frame: the user does not
 * say "嘿 Andee" at the same speed twice, and a rigid comparison turns a 15%
 * slower take into a different phrase. DTW finds the cheapest alignment that
 * keeps time moving forward in both clips, so tempo stops mattering and the
 * sounds start to.
 *
 * Two restrictions on the classic algorithm, both load-bearing here:
 *
 * - **Sakoe-Chiba band.** The alignment may not stray more than [BAND_FRAC] of
 *   the clip away from the diagonal. Unbanded DTW will happily match one
 *   syllable against a whole sentence by standing still on one axis, which is
 *   exactly the false accept a wake word cannot afford.
 * - **Length gate.** Clips whose durations differ by more than [MAX_RATIO] are
 *   rejected outright rather than warped into agreement.
 *
 * The returned cost is divided by the path length, so it is a per-frame
 * average and comparable between long and short takes — that comparability is
 * what lets one stored threshold decide every match.
 */
object Dtw {

    private const val BAND_FRAC = 0.25f
    private const val MAX_RATIO = 1.7f
    private const val MIN_BAND = 4

    /** Returned instead of a distance when the two clips cannot align at all. */
    const val NO_MATCH = Float.MAX_VALUE

    fun distance(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return NO_MATCH
        val ratio = max(n, m).toFloat() / min(n, m)
        if (ratio > MAX_RATIO) return NO_MATCH

        val band = max(MIN_BAND, (BAND_FRAC * max(n, m)).toInt()) + abs(n - m)
        var prev = FloatArray(m + 1) { Float.MAX_VALUE }
        var cur = FloatArray(m + 1) { Float.MAX_VALUE }
        prev[0] = 0f

        for (i in 1..n) {
            java.util.Arrays.fill(cur, Float.MAX_VALUE)
            // Centre of the band for this row, in b's coordinates.
            val centre = (i.toLong() * m / n).toInt()
            val from = max(1, centre - band)
            val to = min(m, centre + band)
            for (j in from..to) {
                val best = min(min(prev[j], cur[j - 1]), prev[j - 1])
                if (best == Float.MAX_VALUE) continue
                cur[j] = best + frameDistance(a[i - 1], b[j - 1])
            }
            val t = prev; prev = cur; cur = t
        }
        val total = prev[m]
        if (total == Float.MAX_VALUE) return NO_MATCH
        // Path length is between max(n,m) and n+m; n+m is the conventional
        // normaliser and the only one that is cheap to get exactly right
        // without tracking the path itself.
        return total / (n + m)
    }

    private fun frameDistance(x: FloatArray, y: FloatArray): Float {
        var s = 0f
        for (k in x.indices) {
            val d = x[k] - y[k]
            s += d * d
        }
        return sqrt(s)
    }
}
