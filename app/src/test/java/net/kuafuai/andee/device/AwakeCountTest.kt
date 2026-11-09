package net.kuafuai.andee.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wake lock's bookkeeping (review of 04341bc): the deadline is re-armed, and a missed exit heals. */
class AwakeCountTest {
    private var now = 0L
    private var heldUntil = 0L           // what the platform would do: a non-refcounted lock with a deadline
    private val acquires = mutableListOf<Long>()
    private val count = AwakeCount(
        60_000L, { now },
        acquire = { ms -> heldUntil = now + ms; acquires += now },
        release = { heldUntil = 0L },
    )
    private val held get() = now < heldUntil

    @Test fun overlappingBurstsKeepTheLockPastTheFirstDeadline() {
        count.enter()                     // app A
        now += 30_000; count.enter()      // app B, while A settles: depth never returns to 0
        now += 10_000; count.exit()       // A done
        now += 25_000                     // 65 s after the first acquire
        assertTrue("re-armed by B's enter", held)
        count.exit()
        assertTrue(!held)
    }

    @Test fun refreshGivesASlowStepItsOwnDeadline() {
        count.enter()
        now += 50_000; count.refresh()    // the settle used 50 s; the triage gets a full 60 from here
        now += 40_000
        assertTrue(held)
        count.exit()
        assertTrue(!held)
    }

    @Test fun aMissedExitDoesNotDisableTheLockForever() {
        count.enter()                     // its exit never comes
        now += 61_000
        assertTrue("the platform let it go at the deadline", !held)
        count.enter()                     // a new message, long after
        assertTrue("taken again, not swallowed by a stale depth", held)
        assertEquals(1, count.depth())
        count.exit()
        assertTrue(!held)
    }

    @Test fun exitWithNothingHeldIsHarmless() {
        count.exit()
        assertEquals(0, count.depth())
        count.enter(); count.exit(); count.exit()
        assertEquals(0, count.depth())
        assertTrue(!held)
    }
}
