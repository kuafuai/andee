package net.kuafuai.andee.ui.ball.gl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Torus centered at origin, main axis is Z. UV: u along the tubular direction
 * (main ring circumference), v around the tube cross-section — matches Three.js
 * TorusGeometry so the FLOW shader's `vUv.x` sweeps around the ring.
 *
 * [arc] < 2π gives a partial torus (used for the mouth as a half-torus).
 */
object Torus {
    fun build(
        radius: Float,
        tube: Float,
        radialSegs: Int,
        tubularSegs: Int,
        arc: Float = 2f * PI.toFloat(),
    ): Mesh {
        val vertCount = (radialSegs + 1) * (tubularSegs + 1)
        val positions = FloatArray(vertCount * 3)
        val normals = FloatArray(vertCount * 3)
        val uvs = FloatArray(vertCount * 2)
        var pi = 0;
        var ui = 0
        for (j in 0..radialSegs) {
            val v = j.toFloat() / radialSegs * 2f * PI.toFloat()
            val cV = cos(v);
            val sV = sin(v)
            for (i in 0..tubularSegs) {
                val u = i.toFloat() / tubularSegs * arc
                val cU = cos(u);
                val sU = sin(u)
                val rr = radius + tube * cV
                val px = rr * cU
                val py = rr * sU
                val pz = tube * sV
                positions[pi] = px
                positions[pi + 1] = py
                positions[pi + 2] = pz
                val cx = radius * cU
                val cy = radius * sU
                val nx = px - cx
                val ny = py - cy
                val nz = pz
                val inv = 1f / sqrt(nx * nx + ny * ny + nz * nz)
                normals[pi] = nx * inv
                normals[pi + 1] = ny * inv
                normals[pi + 2] = nz * inv
                uvs[ui] = i.toFloat() / tubularSegs
                uvs[ui + 1] = j.toFloat() / radialSegs
                pi += 3; ui += 2
            }
        }
        val stride = tubularSegs + 1
        val idx = ShortArray(radialSegs * tubularSegs * 6)
        var w = 0
        for (j in 0 until radialSegs) {
            for (i in 0 until tubularSegs) {
                val a = j * stride + i
                val b = j * stride + i + 1
                val c = (j + 1) * stride + i + 1
                val d = (j + 1) * stride + i
                idx[w++] = a.toShort(); idx[w++] = b.toShort(); idx[w++] = c.toShort()
                idx[w++] = a.toShort(); idx[w++] = c.toShort(); idx[w++] = d.toShort()
            }
        }
        return Mesh(positions, normals, uvs, idx)
    }
}
