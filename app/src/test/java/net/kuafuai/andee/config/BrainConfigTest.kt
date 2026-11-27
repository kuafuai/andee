package net.kuafuai.andee.config

import net.kuafuai.andee.config.BrainConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BrainConfig.sameBackend] decides whether closing the settings card rebuilds
 * the brain, and a rebuild discards the conversation. Getting it wrong in the
 * "too eager" direction is silent — the user flips the thinking switch and the
 * assistant forgets the last ten minutes — which is how it was found.
 */
class BrainConfigTest {

    private val base = BrainConfig(
        mode = VoiceConfig.BRAIN_LOCAL,
        apiKey = "sk-one",
        baseUrl = "https://api.example.com/v1",
        model = "m1",
        thinking = BrainConfig.THINKING_ENABLED,
        reasoningEffort = "high",
    )

    @Test
    fun `how it is asked does not change the backend`() {
        assertTrue(base.sameBackend(base.copy(thinking = BrainConfig.THINKING_AUTO)))
        assertTrue(base.sameBackend(base.copy(reasoningEffort = "low")))
        assertTrue(base.sameBackend(base.copy(model = "m2")))
    }

    @Test
    fun `who answers does`() {
        assertFalse(base.sameBackend(base.copy(apiKey = "sk-two")))
        assertFalse(base.sameBackend(base.copy(baseUrl = "https://other.example.com/v1")))
        assertFalse(base.sameBackend(base.copy(mode = VoiceConfig.BRAIN_HUB)))
    }
}
