package net.kuafuai.andee.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn id is the only thing tying a scrollback row to its log, and it is
 * the half of that pair the file cannot check for itself. A wrong id does not
 * fail — it opens the wrong turn, or nothing, and looks like "the log is gone".
 */
class BrainTraceTest {

    @Test
    fun `the turn id is stable to read and distinct across a restart`() {
        // Same turn, read twice, same answer: the id is copied onto the row when
        // the answer lands and matched back when the row is long-pressed, and
        // those are two different moments.
        assertEquals(BrainTrace.turnId(1_000L, 7), BrainTrace.turnId(1_000L, 7))

        // The trap, measured on the device rather than feared: `gen` is
        // LocalBrain's in-memory counter, so it restarts at 1 with every process
        // while the trace file is persistent and append-only. On the test device
        // "gen":1 covered 176 of 278 records. Same gen in two runs must not be
        // the same turn.
        assertNotEquals(BrainTrace.turnId(1_000L, 1), BrainTrace.turnId(2_000L, 1))

        // The dash is load-bearing, not formatting: run 12 / gen 3 and
        // run 1 / gen 23 would concatenate to the same string without it.
        assertNotEquals(BrainTrace.turnId(12L, 3), BrainTrace.turnId(1L, 23))
    }

    @Test
    fun `the turn in flight is announced, survives being read, and ends when told`() {
        // `init` is never called here, so every append in this test is a no-op.
        // That is deliberate: the bookkeeping the scrollback reads must not
        // depend on the trace file being writable.
        BrainTrace.clearTurn()
        assertNull(BrainTrace.currentTurn())

        BrainTrace.turn(gen = 7, kind = "user", text = "hi")
        val announced = BrainTrace.currentTurn()
        assertNotNull("turn() did not announce itself", announced)
        assertTrue("$announced does not end in its own gen", announced!!.endsWith("-7"))

        // Reading must not consume it. The last step overwrites nothing, and the
        // row is only written once the answer is final.
        assertEquals(announced, BrainTrace.currentTurn())

        // Ending is explicit rather than done on read, for one path: `submit`
        // returns early when the API key is missing and writes a device
        // complaint with no log behind it. That row must not inherit whatever
        // the previous turn recorded.
        BrainTrace.clearTurn()
        assertNull(BrainTrace.currentTurn())
    }
}
