package net.kuafuai.andee.ui.ball.gl

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A flap — a pig's ear at the default [build] taper, a rabbit's at a high one.
 *
 * Built like [Horn] as a stack of rings walking a curved axis, but three things
 * make it an ear rather than a horn:
 *
 *  - The cross-section is **elliptical**, not round: wide in the bend plane,
 *    thin in Z. An ear is a flap, and a flap is a flattened shape.
 *  - The profile **rounds off** at the tip instead of coming to a point. A
 *    pig's ear is rounded; a pointed tip reads as a horn.
 *  - It is short and droops (the caller rotates it), where a horn is long and
 *    points.
 *
 * The third of those is the caller's business and the second turned out to be a
 * matter of degree — see [build]'s `taper`, which exists because the quarter
 * ellipse this started as is a *cone with a blunt end* once the flap is long,
 * and a long cone is a horn no matter how rounded its last millimetre is.
 *
 * Grown in the +Y/+X plane like [Horn], so the same mirror-for-the-other-side
 * trick applies. Normals are real, so the lit shell shader shades the flap
 * instead of drawing a hole in the ball.
 */
object Ear {
    /**
     * @param width half-width at the base, in the bend plane.
     * @param height length along the (curved) axis.
     * @param thickness half-depth in Z. Small relative to [width] — that flatness
     *   is what makes it a flap.
     * @param bend total rotation of the axis from base to tip, radians, toward +X.
     * @param taper how fast the flap narrows: `√(1 − t^taper)`. **2 is the
     *   quarter ellipse** this was born as and is the default, so every caller
     *   that does not pass it gets exactly the mesh it got before.
     *
     *   Higher is a *rabbit*. The exponent decides where along the length the
     *   narrowing happens: at 2 the flap is already down to 87% of its base
     *   width halfway up and 44% at nine tenths, which is a cone — stand one of
     *   those up and it reads as a horn, which is what the first 贱贱兔 shipped
     *   as. At 6 the same two points are 99% and 76%, so the flap runs parallel
     *   for most of its length and does all its rounding in the last fifth,
     *   which is the shape a rabbit's ear actually is.
     * @param segs rings along the length. Worth raising with [taper]: all the
     *   curvature moves into the last couple of rings, and at 6 rings a sharp
     *   taper rounds off in one flat step.
     * @param radialSegs vertices around each ring.
     */
    fun build(
        width: Float,
        height: Float,
        thickness: Float,
        bend: Float = 0f,
        segs: Int = 6,
        radialSegs: Int = 10,
        taper: Float = 2f,
    ): Mesh {
        val rings = segs + 1
        val vertCount = rings * (radialSegs + 1)
        val positions = FloatArray(vertCount * 3)
        val normals = FloatArray(vertCount * 3)
        val uvs = FloatArray(vertCount * 2)

        val step = height / segs
        var cx = 0f
        var cy = 0f
        var pi = 0
        var ui = 0
        for (i in 0 until rings) {
            val t = i.toFloat() / segs
            // Floored so the tip is a small rounded nub rather than a point —
            // and so the per-ring normal never divides by zero. A pointed tip
            // is a horn, not an ear.
            val k = max(sqrt(1f - t.pow(taper)), 0.08f)
            val w = width * k
            val th = thickness * k
            val ang = bend * t
            val ax = sin(ang)
            val ay = cos(ang)
            // u ⊥ a, in the XY plane; the third direction is simply Z.
            val ux = ay
            val uy = -ax
            for (j in 0..radialSegs) {
                val tht = j.toFloat() / radialSegs * 2f * PI_F
                val ct = cos(tht)
                val st = sin(tht)
                positions[pi++] = cx + w * ct * ux
                positions[pi++] = cy + w * ct * uy
                positions[pi++] = th * st
                // Ellipse (w, th) surface normal ∝ (ct/w, st/th) in the (u, Z)
                // frame, rotated into world: the u-component rides on (ux, uy).
                val nu = ct / w
                val nz = st / th
                val inv = 1f / sqrt(nu * nu + nz * nz)
                normals[pi - 3] = nu * inv * ux
                normals[pi - 2] = nu * inv * uy
                normals[pi - 1] = nz * inv
                uvs[ui++] = j.toFloat() / radialSegs
                uvs[ui++] = t
            }
            cx += ax * step
            cy += ay * step
        }

        val indices = ShortArray(segs * radialSegs * 6)
        var k = 0
        for (i in 0 until segs) {
            for (j in 0 until radialSegs) {
                val a = (i * (radialSegs + 1) + j).toShort()
                val b = (i * (radialSegs + 1) + j + 1).toShort()
                val c = ((i + 1) * (radialSegs + 1) + j).toShort()
                val d = ((i + 1) * (radialSegs + 1) + j + 1).toShort()
                indices[k++] = a; indices[k++] = c; indices[k++] = b
                indices[k++] = b; indices[k++] = c; indices[k++] = d
            }
        }
        return Mesh(positions, normals, uvs, indices)
    }

    private const val PI_F = 3.1415927f
}
