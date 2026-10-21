package net.kuafuai.andee.config

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class AvatarImageRulesTest {
    @Test
    fun `large photos are bounded without changing aspect ratio`() {
        assertEquals(512 to 384, AvatarImageRules.targetSize(4000, 3000))
        assertEquals(384 to 512, AvatarImageRules.targetSize(3000, 4000))
        assertEquals(512 to 512, AvatarImageRules.targetSize(4000, 4000))
    }

    @Test
    fun `small images are not enlarged`() {
        assertEquals(128 to 64, AvatarImageRules.targetSize(128, 64))
        assertEquals(1 to 1, AvatarImageRules.targetSize(1, 1))
    }

    @Test
    fun `extreme image dimensions cannot round to zero or overflow`() {
        assertEquals(1 to 512, AvatarImageRules.targetSize(1, Int.MAX_VALUE))
        assertEquals(512 to 1, AvatarImageRules.targetSize(Int.MAX_VALUE, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid image dimensions are rejected`() {
        AvatarImageRules.targetSize(0, 20)
    }

    @Test
    fun `rendering contains portrait and landscape images without stretching`() {
        assertEquals(1f to 0.5f, AvatarImageRules.aspectScale(200, 100))
        assertEquals(0.5f to 1f, AvatarImageRules.aspectScale(100, 200))
        assertEquals(1f to 1f, AvatarImageRules.aspectScale(200, 200))
    }

    @Test
    fun `an image exactly at the byte limit is accepted`() {
        val bytes = ByteArray(8192) { (it % 256).toByte() }
        assertArrayEquals(bytes, AvatarImageRules.read(ByteArrayInputStream(bytes), bytes.size))
    }

    @Test(expected = IOException::class)
    fun `oversized images are rejected even when the provider has no size metadata`() {
        AvatarImageRules.read(ByteArrayInputStream(ByteArray(8193)), 8192)
    }

    @Test(expected = IOException::class)
    fun `empty input is rejected`() {
        AvatarImageRules.read(ByteArrayInputStream(byteArrayOf()))
    }

    @Test
    fun `oversized input stops after the first byte beyond the limit`() {
        val source = ByteArrayInputStream(ByteArray(20_000))
        try {
            AvatarImageRules.read(source, 8192)
            throw AssertionError("Oversized input was accepted")
        } catch (_: IOException) {
            assertEquals(20_000 - 8193, source.available())
        }
    }

    @Test(expected = IOException::class)
    fun `provider read failures cannot return a partial image`() {
        val source = object : InputStream() {
            private var count = 0
            override fun read(): Int {
                if (count++ >= 5) throw IOException("Provider disconnected")
                return 1
            }
        }
        AvatarImageRules.read(source)
    }
}
