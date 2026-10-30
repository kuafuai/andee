package net.kuafuai.andee.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNavigationTrackerTest {
    private val tracker = HomeNavigationTracker()
    private fun note(pkg: String, transient: Boolean = false) =
        tracker.note(pkg, "andee", "launcher", transient)

    @Test fun `send closing input through keyboard and system UI keeps conversation open`() {
        repeat(3) {
            assertFalse(note("launcher"))
            assertFalse(note("andee"))
            assertFalse(note("keyboard", true))
            assertFalse(note("android", true))
            assertFalse(note("systemui", true))
            assertFalse(note("launcher"))
            assertFalse(note("launcher"))
        }
    }

    @Test fun `reopening the launcher trampoline does not count as home navigation`() {
        assertFalse(note("launcher"))
        assertFalse(note("launcher"))
        assertFalse(note("andee"))
        assertFalse(note("systemui", true))
        assertFalse(note("launcher"))
    }

    @Test fun `return from another app still counts after transient system events`() {
        assertFalse(note("browser"))
        assertFalse(note("systemui", true))
        assertTrue(note("launcher"))
        assertFalse(note("launcher"))
    }
    @Test fun `first home event has no previous app and cannot fold`() {
        assertFalse(note("launcher"))
        assertFalse(note("launcher"))
    }

    @Test fun `IME event between real apps does not replace navigation history`() {
        assertFalse(note("browser"))
        assertFalse(note("newly_enabled_keyboard", true))
        assertFalse(note("mail"))
        assertFalse(note("keyboard", true))
        assertTrue(note("launcher"))
        assertFalse(note("launcher"))
    }

    @Test fun `unresolved launcher cannot be classified as home`() {
        assertFalse(tracker.note("browser", "andee", null, false))
        assertFalse(tracker.note("launcher", "andee", null, false))
    }

}
