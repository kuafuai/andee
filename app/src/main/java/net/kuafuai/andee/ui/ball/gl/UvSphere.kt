package net.kuafuai.andee.ui.ball.gl

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * UV sphere (lat/lon segments). Matches Three.js SphereGeometry(radius, w, h).
 * Used for the rim/scan shader where we need a smooth silhouette.
 */
object UvSphere {
    fun build(widthSegments: Int, heightSegments: Int, radius: Float = 1f): Mesh {
        val vertCount = (widthSegments + 1) * (heightSegments + 1)
        val positions = FloatArray(vertCount * 3)
        val normals = FloatArray(vertCount * 3)
        val uvs = FloatArray(vertCount * 2)
        var pi = 0
        var ui = 0
        for (iy in 0..heightSegments) {
            val v = iy.toFloat() / heightSegments
            val phi = v * PI.toFloat()
            val sPhi = sin(phi)
            val cPhi = cos(phi)
            for (ix in 0..widthSegments) {
                val u = ix.toFloat() / widthSegments
                val theta = u * 2f * PI.toFloat()
                val x = -radius * cos(theta) * sPhi
                val y = radius * cPhi
                val z = radius * sin(theta) * sPhi
                positions[pi] = x
                positions[pi + 1] = y
                positions[pi + 2] = z
                normals[pi] = x / radius
                normals[pi + 1] = y / radius
                normals[pi + 2] = z / radius
                uvs[ui] = u
                uvs[ui + 1] = 1f - v
                pi += 3; ui += 2
            }
        }
        val stride = widthSegments + 1
        val idxRaw = ShortArray(widthSegments * heightSegments * 6)
        var w = 0
        for (iy in 0 until heightSegments) {
            for (ix in 0 until widthSegments) {
                val a = iy * stride + ix + 1
                val b = iy * stride + ix
                val c = (iy + 1) * stride + ix
                val d = (iy + 1) * stride + ix + 1
                if (iy != 0) {
                    idxRaw[w++] = a.toShort()
                    idxRaw[w++] = b.toShort()
                    idxRaw[w++] = d.toShort()
                }
                if (iy != heightSegments - 1) {
                    idxRaw[w++] = b.toShort()
                    idxRaw[w++] = c.toShort()
                    idxRaw[w++] = d.toShort()
                }
            }
        }
        val idx = ShortArray(w).also { idxRaw.copyInto(it, 0, 0, w) }
        return Mesh(positions, normals, uvs, idx)
    }
}
