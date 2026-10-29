package net.kuafuai.andee.brain

import net.kuafuai.andee.config.BrainConfig
import net.kuafuai.andee.config.Drive
import net.kuafuai.andee.config.NotebookBook
import net.kuafuai.andee.config.NotebookStore
import net.kuafuai.andee.config.Selfhood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class HeartbeatTest {
    private val zone = ZoneOffset.UTC
    // 2027-01-15 10:00 UTC — daytime.
    private var now = java.time.LocalDateTime.of(2027, 1, 15, 10, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val h = Selfhood.HOUR_MS
    private val book = NotebookBook(object : NotebookStore {
        var s: String? = null
        override fun read() = s
        override fun write(plain: String): Boolean { s = plain; return true }
        override fun wipe() { s = null }
    })
    private val self = Selfhood(book, { now }, zone)
    private val drive = Drive(book, { now }, zone)
    private var answer: String? = """{"verdict":"sleep","why":"nothing to do"}"""
    private var calls = 0
    private val cfg = BrainConfig(BrainConfig.BRAIN_LOCAL, "", "http://x", "m", false, "low")
    private val beat = Heartbeat(cfg, self, drive, clock = { now }, zone = zone, ask = { _, _ -> calls++; answer })

    private fun care(hours: Double = 12.0): String = self.addCare("place", "社区", "他交给我的", "他 01-14 说的", hours, 0L)!!.removePrefix("id:")

    @Test fun nothingDueCostsNothing() {
        care()
        now += 2 * h                     // 0.2× — under the line
        assertNull(beat.beat("zh"))
        assertEquals(0, calls)
        assertEquals(0, drive.beatsToday())
    }

    @Test fun aDueCareReachesTheModelAndTheVerdictMustNameIt() {
        val id = care()
        now += 18 * h                    // 1.5×; 04:00 the next day is night, so step to daytime
        now += 6 * h
        answer = """{"verdict":"work","care_id":"$id","why":"写一份方案"}"""
        val v = beat.beat("zh") as Heartbeat.Verdict.Work
        assertEquals(id, v.care.id)
        assertEquals(1, calls)
        assertEquals(1, drive.beatsToday())
    }

    @Test fun aCareThatIsNotDueCannotBeChosen() {
        care()
        now += 24 * h
        answer = """{"verdict":"work","care_id":"made-up","why":"x"}"""
        assertNull(beat.beat("zh"))
    }

    @Test fun thePaceAndTheWorkGapStopItBeforeTheCallButTheNightDoesNot() {
        val id = care()
        now += 24 * h
        answer = """{"verdict":"work","care_id":"$id","why":"x"}"""
        beat.beat("zh")
        now += 5 * 60_000L               // five minutes later: too soon
        assertEquals("pace", beat.gate())
        drive.recordWork(id, "社区", "acted", "", emptyList())
        now += Heartbeat.INTERVAL_MS
        assertEquals("work gap", beat.gate())   // an hour between work turns
        now += Heartbeat.WORK_GAP_MS
        self.settle(id, Selfhood.Effect.LOOKED, null); now += 24 * h
        now = java.time.LocalDateTime.of(2027, 1, 18, 3, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertNull(beat.gate())          // three in the morning: no hours of its own
    }

    @Test fun theWorkBudgetHoldsWhateverTheModelSays() {
        val id = care()
        now += 24 * h
        answer = """{"verdict":"work","care_id":"$id","why":"x"}"""
        repeat(Heartbeat.MAX_WORKS) { drive.markWork() }
        assertNull(beat.beat("zh"))
        assertEquals(0, calls)           // spent: not even the call, which could only answer sleep
        assertEquals("work budget", beat.gate())
    }

    @Test fun aNoteIsKeptAsAThoughtAndAnUnreadableAnswerIsSleep() {
        care()
        now += 24 * h
        answer = """{"verdict":"note","text":"社区最近很安静，我想知道为什么"}"""
        assertTrue(beat.beat("zh") is Heartbeat.Verdict.Note)
        assertEquals("社区最近很安静，我想知道为什么", drive.thoughtsToday().single().text)
        now += Heartbeat.INTERVAL_MS
        answer = "我觉得应该去看看"
        assertNull(beat.beat("zh"))
    }
}
