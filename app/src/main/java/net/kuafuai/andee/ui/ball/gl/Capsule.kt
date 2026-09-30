package net.kuafuai.andee.ui.ball.gl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Capsule: two hemispherical caps of [radius] separated by a cylinder of
 * [cylinderHeight]. Axis is Y. Matches Three.js CapsuleGeometry.
 *
 * Built as two rings-of-vertices strips: top hemisphere (phi 0..π/2 shifted up
 * by half the cylinder height) then bottom hemisphere (phi π/2..π shifted
 * down). The two equator rings sit at exactly ±half — connecting them via
 * quads gives the cylinder side implicitly, no separate cylinder pass needed.
 */
object Capsule {
    fun build(
        radius: Float,
        cylinderHeight: Float,
        capSegs: Int = 4,
        radialSegs: Int = 12,
    ): Mesh {
        val half = cylinderHeight / 2f
        val totalRings = 2 * (capSegs + 1)
        val vertCount = totalRings * (radialSegs + 1)
        val positions = FloatArray(vertCount * 3)
        val normals = FloatArray(vertCount * 3)
        val uvs = FloatArray(vertCount * 2)
        var pi = 0;
        var ui = 0
        var ringIdx = 0

        // Top hemisphere: phi = 0 → π/2, shifted up
        for (i in 0..capSegs) {
            val phi = (i.toFloat() / capSegs) * (PI.toFloat() / 2f)
            val sPhi = sin(phi);
            val cPhi = cos(phi)
            for (j in 0..radialSegs) {
                val theta = (j.toFloat() / radialSegs) * 2f * PI.toFloat()
                val nx = -cos(theta) * sPhi
                val ny = cPhi
                val nz = sin(theta) * sPhi
                positions[pi] = radius * nx
                positions[pi + 1] = radius * ny + half
                positions[pi + 2] = radius * nz
                normals[pi] = nx
                normals[pi + 1] = ny
                normals[pi + 2] = nz
                uvs[ui] = j.toFloat() / radialSegs
                uvs[ui + 1] = 1f - ringIdx.toFloat() / (totalRings - 1)
                pi += 3; ui += 2
            }
            ringIdx++
        }

        // Bottom hemisphere: phi = π/2 → π, shifted down
        for (i in 0..capSegs) {
            val phi = (PI.toFloat() / 2f) + (i.toFloat() / capSegs) * (PI.toFloat() / 2f)
            val sPhi = sin(phi);
            val cPhi = cos(phi)
            for (j in 0..radialSegs) {
                val theta = (j.toFloat() / radialSegs) * 2f * PI.toFloat()
                val nx = -cos(theta) * sPhi
                val ny = cPhi
                val nz = sin(theta) * sPhi
                positions[pi] = radius * nx
                positions[pi + 1] = radius * ny - half
                positions[pi + 2] = radius * nz
                normals[pi] = nx
                normals[pi + 1] = ny
                normals[pi + 2] = nz
                uvs[ui] = j.toFloat() / radialSegs
                uvs[ui + 1] = 1f - ringIdx.toFloat() / (totalRings - 1)
                pi += 3; ui += 2
            }
            ringIdx++
        }

        val stride = radialSegs + 1
        val idx = ShortArray((totalRings - 1) * radialSegs * 6)
        var w = 0
        for (r in 0 until totalRings - 1) {
            for (s in 0 until radialSegs) {
                val a = r * stride + s
                val b = r * stride + s + 1
                val c = (r + 1) * stride + s + 1
                val d = (r + 1) * stride + s
                idx[w++] = a.toShort(); idx[w++] = b.toShort(); idx[w++] = c.toShort()
                idx[w++] = a.toShort(); idx[w++] = c.toShort(); idx[w++] = d.toShort()
            }
        }
        return Mesh(positions, normals, uvs, idx)
    }
}
