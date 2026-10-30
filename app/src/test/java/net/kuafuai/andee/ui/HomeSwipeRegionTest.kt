package net.kuafuai.andee.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSwipeRegionTest {
    @Test fun `input row and resized card edge cannot start home`() {
        // 1200x2664 phone at 3.25 px per dp; 24 dp navigation edge.
        for (y in listOf(1537f, 1600f, 2390f, 2500f, 2585f)) {
            assertFalse(HomeSwipeRegion.contains(y, 2664, 78))
        }
        assertTrue(HomeSwipeRegion.contains(2586f, 2664, 78))
        assertTrue(HomeSwipeRegion.contains(2645f, 2664, 78))
        assertFalse(HomeSwipeRegion.contains(2664f, 2664, 78))
    }

    @Test fun `landscape uses the current screen edge`() {
        assertFalse(HomeSwipeRegion.contains(1100f, 1200, 78))
        assertTrue(HomeSwipeRegion.contains(1190f, 1200, 78))
    }
}
