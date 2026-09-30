package net.kuafuai.andee.ui.ball.gl

import kotlin.math.sqrt

/**
 * Icosahedron subdivided [subdivisions] times, unit radius.
 * Matches Three.js icosahedronGeometry(1, N).
 *
 *   subdiv=1 →    42 verts,    80 tris (used for wireframe overlay)
 *   subdiv=3 →   642 verts,  1280 tris (used for core)
 *   subdiv=5 → 10242 verts, 20480 tris (used for shell)
 */
object IcoSphere {
    fun build(subdivisions: Int): Mesh {
        val t = (1f + sqrt(5f)) / 2f
        val basePos = floatArrayOf(
            -1f, t, 0f, 1f, t, 0f, -1f, -t, 0f, 1f, -t, 0f,
            0f, -1f, t, 0f, 1f, t, 0f, -1f, -t, 0f, 1f, -t,
            t, 0f, -1f, t, 0f, 1f, -t, 0f, -1f, -t, 0f, 1f,
        )
        val positions = ArrayList<Float>(basePos.size * 4)
        for (i in 0 until 12) {
            val x = basePos[i * 3];
            val y = basePos[i * 3 + 1];
            val z = basePos[i * 3 + 2]
            val inv = 1f / sqrt(x * x + y * y + z * z)
            positions.add(x * inv); positions.add(y * inv); positions.add(z * inv)
        }
        var faces = intArrayOf(
            0, 11, 5, 0, 5, 1, 0, 1, 7, 0, 7, 10, 0, 10, 11,
            1, 5, 9, 5, 11, 4, 11, 10, 2, 10, 7, 6, 7, 1, 8,
            3, 9, 4, 3, 4, 2, 3, 2, 6, 3, 6, 8, 3, 8, 9,
            4, 9, 5, 2, 4, 11, 6, 2, 10, 8, 6, 7, 9, 8, 1,
        )
        val cache = HashMap<Long, Int>()

        fun midpoint(a: Int, b: Int): Int {
            val lo = if (a < b) a else b
            val hi = if (a < b) b else a
            val key = (lo.toLong() shl 32) or hi.toLong()
            cache[key]?.let { return it }
            val ax = positions[a * 3];
            val ay = positions[a * 3 + 1];
            val az = positions[a * 3 + 2]
            val bx = positions[b * 3];
            val by = positions[b * 3 + 1];
            val bz = positions[b * 3 + 2]
            var mx = (ax + bx) * 0.5f
            var my = (ay + by) * 0.5f
            var mz = (az + bz) * 0.5f
            val inv = 1f / sqrt(mx * mx + my * my + mz * mz)
            mx *= inv; my *= inv; mz *= inv
            val idx = positions.size / 3
            positions.add(mx); positions.add(my); positions.add(mz)
            cache[key] = idx
            return idx
        }

        repeat(subdivisions) {
            val next = IntArray(faces.size * 4)
            var w = 0
            var r = 0
            while (r < faces.size) {
                val a = faces[r];
                val b = faces[r + 1];
                val c = faces[r + 2]
                val ab = midpoint(a, b)
                val bc = midpoint(b, c)
                val ca = midpoint(c, a)
                next[w++] = a; next[w++] = ab; next[w++] = ca
                next[w++] = b; next[w++] = bc; next[w++] = ab
                next[w++] = c; next[w++] = ca; next[w++] = bc
                next[w++] = ab; next[w++] = bc; next[w++] = ca
                r += 3
            }
            faces = next
            cache.clear()
        }

        val posArr = FloatArray(positions.size) { positions[it] }
        val normalsArr = posArr.copyOf()  // unit sphere: normal == position
        val uvArr = FloatArray(posArr.size / 3 * 2)  // no UVs used for icosphere
        val idxArr = ShortArray(faces.size) { faces[it].toShort() }
        return Mesh(posArr, normalsArr, uvArr, idxArr)
    }
}
