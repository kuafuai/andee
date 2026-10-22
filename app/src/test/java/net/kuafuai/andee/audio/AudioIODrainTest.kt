package net.kuafuai.andee.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioIODrainTest {
    @Test
    fun `double speed halves the remaining drain wait`() {
        assertEquals(1000L, AudioIO.drainDurationMs(24_000, 24_000, 1f))
        assertEquals(500L, AudioIO.drainDurationMs(24_000, 24_000, 2f))
    }

    @Test
    fun `drain uses the providers sample rate`() {
        assertEquals(500L, AudioIO.drainDurationMs(12_000, 24_000, 1f))
        assertEquals(250L, AudioIO.drainDurationMs(12_000, 24_000, 2f))
        assertEquals(500L, AudioIO.drainDurationMs(24_000, 48_000, 1f))
        assertEquals(250L, AudioIO.drainDurationMs(24_000, 48_000, 2f))
    }

    @Test
    fun `partial milliseconds round up to avoid clipping the tail`() {
        assertEquals(1L, AudioIO.drainDurationMs(1, 24_000, 2f))
        assertEquals(0L, AudioIO.drainDurationMs(0, 24_000, 2f))
    }
}
