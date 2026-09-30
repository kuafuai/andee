package net.kuafuai.andee.ui.ball.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Indexed triangle mesh, interleaved (pos, normal, uv) = 8 floats/vertex.
 *
 * SHORT indices — enough for anything we generate (icosphere subdiv 5 = 10242 verts).
 */
class Mesh(
    positions: FloatArray,
    normals: FloatArray,
    uvs: FloatArray,
    indices: ShortArray,
) {
    private val vboVertices: Int
    private val vboIndices: Int
    val indexCount = indices.size

    init {
        val n = positions.size / 3
        require(normals.size == positions.size) { "normals length mismatch" }
        require(uvs.size == n * 2) { "uvs length mismatch" }
        val interleaved = FloatArray(n * STRIDE_FLOATS)
        for (i in 0 until n) {
            val j = i * STRIDE_FLOATS
            interleaved[j] = positions[i * 3]
            interleaved[j + 1] = positions[i * 3 + 1]
            interleaved[j + 2] = positions[i * 3 + 2]
            interleaved[j + 3] = normals[i * 3]
            interleaved[j + 4] = normals[i * 3 + 1]
            interleaved[j + 5] = normals[i * 3 + 2]
            interleaved[j + 6] = uvs[i * 2]
            interleaved[j + 7] = uvs[i * 2 + 1]
        }
        val vb = ByteBuffer.allocateDirect(interleaved.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(interleaved); position(0) }
        val ib = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
            .apply { put(indices); position(0) }
        val out = IntArray(2)
        GLES20.glGenBuffers(2, out, 0)
        vboVertices = out[0]
        vboIndices = out[1]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboVertices)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, interleaved.size * 4, vb, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboIndices)
        GLES20.glBufferData(
            GLES20.GL_ELEMENT_ARRAY_BUFFER,
            indices.size * 2,
            ib,
            GLES20.GL_STATIC_DRAW
        )
    }

    /**
     * Binds VBOs and enables the given attribute pointers. Pass -1 for
     * attributes the current shader doesn't consume.
     */
    fun bind(aPos: Int, aNormal: Int = -1, aUV: Int = -1) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboVertices)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vboIndices)
        val stride = STRIDE_FLOATS * 4
        if (aPos >= 0) {
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, 0)
        }
        if (aNormal >= 0) {
            GLES20.glEnableVertexAttribArray(aNormal)
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, stride, 3 * 4)
        }
        if (aUV >= 0) {
            GLES20.glEnableVertexAttribArray(aUV)
            GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, stride, 6 * 4)
        }
    }

    fun draw() {
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0)
    }

    companion object {
        private const val STRIDE_FLOATS = 8  // px py pz + nx ny nz + u v
    }
}
