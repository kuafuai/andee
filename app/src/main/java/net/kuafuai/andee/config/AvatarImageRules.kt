package net.kuafuai.andee.config

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object AvatarImageRules {
    const val MAX_BYTES = 10 * 1024 * 1024
    const val MAX_SIDE = 512

    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val scale = min(1.0, MAX_SIDE.toDouble() / max(width, height))
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    fun aspectScale(width: Int, height: Int): Pair<Float, Float> {
        require(width > 0 && height > 0)
        val side = max(width, height).toFloat()
        return width / side to height / side
    }

    // Do not trust provider metadata: it may omit the size or report a smaller one.
    fun read(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
        require(limit in 1..MAX_BYTES)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, min(buffer.size, limit - output.size() + 1))
            if (count < 0) break
            if (count == 0) continue
            if (count > limit - output.size()) throw IOException("Avatar image exceeds size limit")
            output.write(buffer, 0, count)
        }
        if (output.size() == 0) throw IOException("Empty avatar image")
        return output.toByteArray()
    }
}
