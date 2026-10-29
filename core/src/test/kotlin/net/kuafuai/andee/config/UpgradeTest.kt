package net.kuafuai.andee.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeTest {
    @Test fun onlyStrictlyNewerCounts() {
        assertTrue(Upgrade.newer("0.7.2", "0.7.1"))
        assertTrue(Upgrade.newer("0.7.10", "0.7.9"))
        assertTrue(Upgrade.newer("v0.8", "0.7.9"))
        assertFalse(Upgrade.newer("0.7.1", "0.7.1"))
        assertFalse(Upgrade.newer("0.7.0", "0.7.1"))   // ahead of the release: no reminder
        assertFalse(Upgrade.newer("0.7", "0.7.0"))
        assertFalse(Upgrade.newer("?", "0.7.1"))
    }

    @Test fun theLinkFollowsTheLanguage() {
        assertEquals("https://www.codeflying.net/andee", Upgrade.url("zh"))
        assertEquals("https://www.codeflying.app/andee", Upgrade.url("en"))
    }
}
