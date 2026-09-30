package net.kuafuai.andee.ui.ball.gl

import android.opengl.GLES20

/**
 * Compiles + links a vertex/fragment shader pair. Caches attribute and
 * uniform locations.
 */
class Shader(vertSrc: String, fragSrc: String) {
    val program: Int
    private val cache = HashMap<String, Int>()

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("shader link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
    }

    fun use() = GLES20.glUseProgram(program)

    fun uniform(name: String): Int =
        cache.getOrPut("u:$name") { GLES20.glGetUniformLocation(program, name) }

    fun attrib(name: String): Int =
        cache.getOrPut("a:$name") { GLES20.glGetAttribLocation(program, name) }

    companion object {
        private fun compile(type: Int, src: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, src)
            GLES20.glCompileShader(id)
            val status = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES20.glGetShaderInfoLog(id)
                GLES20.glDeleteShader(id)
                throw RuntimeException("shader compile failed: $log\n---\n$src")
            }
            return id
        }
    }
}
