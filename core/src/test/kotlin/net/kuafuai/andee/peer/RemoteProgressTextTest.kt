package net.kuafuai.andee.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProgressTextTest {
    private val t0 = 1_000_000L

    @Test fun threeLines_whereItIs_itsPlan_andWhatItIsDoing() {
        val l = RemoteProgressText.lines(
            step = 12, startedAt = t0, now = t0 + 154_000, tokens = 8200,
            todos = listOf("collect" to "completed", "write" to "in_progress", "check" to "pending"),
            recent = listOf("searched", "读网页 kafka.apache.org"), thought = "", zh = true,
        )
        assertEquals("第 12 步 · 2:34 · 8k tok", l.where)
        assertEquals("清单 1/3 · write", l.plan)
        assertEquals("› 读网页 kafka.apache.org", l.doing)
        assertEquals(3, l.asList().size)
    }

    @Test fun withNoChecklistItsThoughtSaysWhatItIsAbout_andAtTheStartItSaysSo() {
        val l = RemoteProgressText.lines(3, t0, t0 + 5_000, 0, emptyList(), listOf("ran ls"), "Looking at the repo layout first.\nThen the tests.", zh = false)
        assertEquals("step 3 · 0:05", l.where)
        assertEquals("Looking at the repo layout first.", l.plan)
        val start = RemoteProgressText.lines(0, t0, t0, 0, emptyList(), emptyList(), "", zh = false)
        assertEquals("getting started…", start.plan)
        assertTrue("nothing done yet, so no third line", start.doing.isEmpty() && start.asList().size == 2)
    }
}

class HandedJobsTest {
    @Test fun aTurnsJobsAreTakenOnceByTheAnswer_andTheNextTurnStartsEmpty() {
        HandedJobs.reset()
        HandedJobs.note("7"); HandedJobs.note("#8"); HandedJobs.note("7"); HandedJobs.note("")
        assertEquals(listOf("7", "8"), HandedJobs.take())
        assertTrue("taken once", HandedJobs.take().isEmpty())
        HandedJobs.note("9"); HandedJobs.reset()
        assertTrue("a new turn forgets the old", HandedJobs.take().isEmpty())
    }
}
