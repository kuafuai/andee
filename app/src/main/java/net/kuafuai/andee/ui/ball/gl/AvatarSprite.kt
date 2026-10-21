package net.kuafuai.andee.ui.ball.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import net.kuafuai.andee.config.AvatarImageRules

/** A front-facing image, retaining its aspect ratio and transparent pixels. GL thread only. */
internal class AvatarSprite {
    private val shader = Shader(VERTEX, FRAGMENT)
    private val quad = Mesh(
        floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, 1f, 0f),
        FloatArray(12),
        floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f),
        shortArrayOf(0, 1, 2, 2, 1, 3),
    )
    private var texture = 0
    private var uploaded: Bitmap? = null

    fun draw(bitmap: Bitmap, mvp: FloatArray) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (texture == 0) {
            val id = IntArray(1)
            GLES20.glGenTextures(1, id, 0)
            texture = id[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (uploaded !== bitmap) {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            uploaded = bitmap
        }
        val (x, y) = AvatarImageRules.aspectScale(bitmap.width, bitmap.height)
        shader.use()
        GLES20.glUniformMatrix4fv(shader.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform2f(shader.uniform("uAspect"), x, y)
        GLES20.glUniform1i(shader.uniform("uImage"), 0)
        // Android bitmaps are premultiplied; multiplying their RGB by alpha again darkens edges.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        quad.bind(shader.attrib("aPosition"), aUV = shader.attrib("aUV"))
        quad.draw()
        GLES20.glDepthMask(true)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun clear() {
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0
        uploaded = null
    }

    private companion object {
        const val VERTEX = """
            uniform mat4 uMVP;
            uniform vec2 uAspect;
            attribute vec3 aPosition;
            attribute vec2 aUV;
            varying vec2 vUV;
            void main() {
                vUV = aUV;
                gl_Position = uMVP * vec4(aPosition.xy * uAspect, aPosition.z, 1.0);
            }
        """
        const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uImage;
            varying vec2 vUV;
            void main() {
                gl_FragColor = texture2D(uImage, vUV);
            }
        """
    }
}
