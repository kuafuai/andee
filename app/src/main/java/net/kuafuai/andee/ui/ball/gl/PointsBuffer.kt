package net.kuafuai.andee.ui.ball.gl

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Dynamic point cloud: interleaved (pos 3, color 3) = 6 floats per point.
 * Positions are computed on the CPU each frame (dust particles orbit with
 * per-particle rates), then uploaded to the GPU as DYNAMIC_DRAW.
 *
 * Editing pattern:
 *   1. state.writeHeads(pointsBuffer.data)     — fill CPU array
 *   2. pointsBuffer.upload()                   — push to GPU
 *   3. pointsBuffer.bind(aPos, aColor); .draw()
 */
class PointsBuffer(val capacity: Int) {

    val data = FloatArray(capacity * STRIDE_FLOATS)

    private val vbo: Int
    private val fbuf: FloatBuffer

    init {
        fbuf = ByteBuffer.allocateDirect(capacity * STRIDE_FLOATS * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        val out = IntArray(1)
        GLES20.glGenBuffers(1, out, 0)
        vbo = out[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            capacity * STRIDE_FLOATS * 4,
            null,
            GLES20.GL_DYNAMIC_DRAW,
        )
    }

    fun upload() {
        fbuf.position(0)
        fbuf.put(data)
        fbuf.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferSubData(
            GLES20.GL_ARRAY_BUFFER, 0,
            capacity * STRIDE_FLOATS * 4,
            fbuf,
        )
    }

    fun bind(aPos: Int, aColor: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val stride = STRIDE_FLOATS * 4
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, stride, 3 * 4)
    }

    fun draw() {
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, capacity)
    }

    companion object {
        private const val STRIDE_FLOATS = 6  // px py pz + r g b
    }
}
