package net.kuafuai.andee.ui.ball.gl

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A tapered spike that may curve as it rises: a horn, or the barb on a tail.
 *
 * Built as a stack of rings walking up a discrete arc. Each step advances along
 * the local axis and then rotates that axis by `bend / segs`, so the spike
 * sweeps through a total of [bend] radians from base to tip. `bend = 0` is a
 * plain cone.
 *
 * The curve is why this is not `Capsule` with a scale on it, and why it is not
 * one either: a horn is the one part of a demon that is read *by its shape*, and
 * a straight cone reads as a party hat. Everything else on this ball can be
 * expressed as a scaled sphere, capsule or torus; this cannot.
 *
 * Grown in the +Y/+X plane (up, leaning toward +X). The caller mirrors it for
 * the other side with a negative X scale — which flips winding, and nothing here
 * depends on winding because the ball never enables face culling.
 *
 * **Normals are real, not radial.** The spike is drawn with the lit shell shader
 * rather than the flat solid one (a flat-shaded horn on a dark ball is a
 * silhouette with a hole in it where it overlaps the body), so the taper has to
 * be in the normal or the whole spike shades as a cylinder. For a cone of base
 * radius R and height H the outward normal is `normalize(H·r̂ + R·â)` — tilted
 * toward the tip by exactly the taper angle.
 */
object Horn {
    /**
     * @param radius base radius; the tip is a point.
     * @param height length along the (curved) axis.
     * @param bend total rotation of the axis from base to tip, radians, toward +X.
     * @param segs rings along the length. 6 is plenty at the size this is drawn.
     * @param radialSegs vertices around each ring.
     */
    fun build(
        radius: Float,
        height: Float,
        bend: Float = 0f,
        segs: Int = 6,
        radialSegs: Int = 10,
    ): Mesh {
        val rings = segs + 1
        val vertCount = rings * (radialSegs + 1)
        val positions = FloatArray(vertCount * 3)
        val normals = FloatArray(vertCount * 3)
        val uvs = FloatArray(vertCount * 2)

        val step = height / segs
        // Slope of the profile, constant for a linear taper. This is the (H, R)
        // pair the normal is mixed from — see the class KDoc.
        val nAxis = radius / sqrt(height * height + radius * radius)
        val nRad = height / sqrt(height * height + radius * radius)

        var cx = 0f
        var cy = 0f
        var pi = 0
        var ni = 0
        var ui = 0
        for (i in 0 until rings) {
            val t = i.toFloat() / segs
            val r = radius * (1f - t)
            // The axis at this ring, and the two directions spanning the ring
            // plane: u in the bend plane, v out of it.
            val ang = bend * t
            val ax = sin(ang)
            val ay = cos(ang)
            // u ⊥ a, in the XY plane; v is simply Z.
            val ux = ay
            val uy = -ax
            for (j in 0..radialSegs) {
                val th = j.toFloat() / radialSegs * 2f * PI_F
                val ct = cos(th)
                val st = sin(th)
                // Radial direction: ct·u + st·v
                val rx = ct * ux
                val ry = ct * uy
                val rz = st
                positions[pi++] = cx + r * rx
                positions[pi++] = cy + r * ry
                positions[pi++] = r * rz
                normals[ni++] = nRad * rx + nAxis * ax
                normals[ni++] = nRad * ry + nAxis * ay
                normals[ni++] = nRad * rz
                uvs[ui++] = j.toFloat() / radialSegs
                uvs[ui++] = t
            }
            cx += ax * step
            cy += ay * step
        }

        // Quads all the way up, including into the zero-radius tip ring. Those
        // degenerate into zero-area triangles, which costs nothing and saves a
        // separate tip fan.
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
