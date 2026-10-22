package net.kuafuai.andee.config

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsSpeedTest {
    @Test
    fun `fresh installs and upgrades keep normal speed`() {
        assertEquals(1f, VoiceConfig.ttsSpeedFromSaved(null), 0f)
        assertEquals(1f, VoiceConfig.ttsSpeedFromSaved(""), 0f)
    }

    @Test
    fun `saved double speed is restored`() {
        assertEquals(2f, VoiceConfig.ttsSpeedFromSaved("2"), 0f)
        assertEquals(2f, VoiceConfig.ttsSpeedFromSaved(" 2 "), 0f)
    }

    @Test
    fun `switching back to normal overrides double speed`() {
        assertEquals(1f, VoiceConfig.ttsSpeedFromSaved("1"), 0f)
    }

    @Test
    fun `invalid preferences cannot pause or distort speech`() {
        for (value in listOf("0", "-1", "3", "NaN", "Infinity", "fast")) {
            assertEquals(value, 1f, VoiceConfig.ttsSpeedFromSaved(value), 0f)
        }
    }
}
