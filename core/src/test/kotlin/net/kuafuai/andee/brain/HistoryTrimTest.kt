package net.kuafuai.andee.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTrimTest {
    private fun turn(len: Int): List<Boolean> = listOf(true) + List(len - 1) { false }

    @Test fun underTheLimitDropsNothing() {
        assertEquals(emptyList<Int>(), HistoryTrim.dropIndexes(listOf(false) + turn(10), 60))
    }

    @Test fun oneLongTaskIsNeverEmptied() {
        // system + one user message + 103 assistant/tool messages: no boundary after the user message.
        val b = listOf(false) + turn(104)
        assertEquals("nothing may be dropped from the only turn", emptyList<Int>(), HistoryTrim.dropIndexes(b, 60))
    }

    @Test fun oldTurnsGoOldestFirstAndTheNewestSurvivesWhole() {
        val b = listOf(false) + turn(10) + turn(10) + turn(10) + turn(50)
        val d = HistoryTrim.dropIndexes(b, 60)
        val kept = b.indices.filter { it !in d }
        assertTrue(0 in kept)
        // The newest turn (starts at index 31) is untouched.
        assertTrue((31 until b.size).all { it in kept })
        // Dropped ranges are whole turns: the first message kept after the system message is a user message.
        assertTrue(b[kept[1]])
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), d.take(10))
    }

    @Test fun stopsAsSoonAsItIsUnderTheLimit() {
        val b = listOf(false) + turn(30) + turn(30) + turn(5)
        val d = HistoryTrim.dropIndexes(b, 60)
        assertEquals(30, d.size)
    }

    @Test fun aStoppedTaskIsKeptEvenWhenAnotherTurnIsNewer() {
        // system + a 70-message task stopped at the step limit + a todo's 2-message turn after it.
        val b = listOf(false) + turn(70) + turn(2)
        assertTrue("without the guard the task goes", 1 in HistoryTrim.dropIndexes(b, 60))
        assertEquals(emptyList<Int>(), HistoryTrim.dropIndexes(b, 60, keepFrom = 1))
    }
}
