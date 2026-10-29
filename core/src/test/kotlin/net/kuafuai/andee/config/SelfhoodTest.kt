package net.kuafuai.andee.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfhoodTest {

    private var now = 1_800_000_000_000L
    private val h = Selfhood.HOUR_MS
    private val book = NotebookBook(object : NotebookStore {
        var s: String? = null
        override fun read() = s
        override fun write(plain: String): Boolean { s = plain; return true }
        override fun wipe() { s = null }
    })
    private val me = Selfhood(book, { now })

    private fun add(kind: String, what: String, hours: Double? = null, due: Long = 0L): String =
        me.addCare(kind, what, "why", "他 10-28 说的", hours, due)!!.removePrefix("id:")

    @Test fun overdueIsComparableAcrossKinds() {
        val place = add("place", "社区", 12.0)
        val person = add("person", "老王", 24.0 * 7)
        now += 18 * h                                   // the community 1.5× overdue; the friend 0.1×
        val c = me.care(place)!!; val p = me.care(person)!!
        assertEquals(1.5, me.overdue(c), 0.01)
        assertTrue(me.overdue(p) < 0.2)
        assertEquals(listOf(place), me.due().map { it.first.id })
    }

    @Test fun aSlowKindIsNotPushedOutByAFastOneForEver() {
        val place = add("place", "社区", 12.0)
        val person = add("person", "老王", 24.0 * 7)
        now += 24 * 7 * h + h                           // a week on: both overdue; the order is by ratio, not by who climbs fastest
        val top = me.top(2).map { it.first.id }
        assertEquals(setOf(place, person), top.toSet())
        assertTrue(me.overdue(me.care(place)!!) > me.overdue(me.care(person)!!))   // 14× vs 1.0×: place first
        me.settle(place, Selfhood.Effect.ACTED, "看了，发了一帖")
        assertEquals(person, me.top(1).single().first.id)                         // ...and now the friend is the most overdue
    }

    @Test fun aPromiseCountsDownToItsDueTimeAndKeepsGrowingAfter() {
        val id = add("promise", "给老王方案", due = now + 48 * h)
        assertEquals(0.0, me.overdue(me.care(id)!!), 0.01)
        now += 24 * h
        assertEquals(0.5, me.overdue(me.care(id)!!), 0.05)
        now += 24 * h
        assertEquals(1.0, me.overdue(me.care(id)!!), 0.05)
        now += 24 * h
        assertTrue(me.overdue(me.care(id)!!) > 1.4)
    }

    @Test fun actingResetsLookingAndNothingOnlyHalfway() {
        val id = add("place", "社区", 12.0)
        now += 24 * h
        // Nothing came of it: counted, and pushed back half an interval so it cannot take every work turn in a row.
        me.settle(id, Selfhood.Effect.NOTHING, null)
        assertEquals(0.5, me.overdue(me.care(id)!!), 0.01)
        assertEquals(1, me.care(id)!!.idleRuns)
        now += 18 * h
        me.settle(id, Selfhood.Effect.LOOKED, "看了一眼")
        assertEquals(0.5, me.overdue(me.care(id)!!), 0.01)         // moved half an interval back, not to zero
        now += 12 * h
        me.settle(id, Selfhood.Effect.ACTED, "发了帖")
        assertEquals(0.0, me.overdue(me.care(id)!!), 0.01)
        assertEquals(0, me.care(id)!!.idleRuns)
        assertEquals("发了帖", me.care(id)!!.note)
    }

    @Test fun theUserPointingAtOneMakesItMoreOverdueUntilWorkedOn() {
        val id = add("place", "社区", 12.0)
        now += 12 * h
        val before = me.overdue(me.care(id)!!)
        me.pin(id)
        assertEquals(before * 1.5, me.overdue(me.care(id)!!), 0.01)
        me.settle(id, Selfhood.Effect.ACTED, null)
        assertEquals(0.0, me.overdue(me.care(id)!!), 0.01)
    }

    @Test fun aCareNeedsASourceAndRespectsTheUsersRemoval() {
        assertTrue(me.addCare("place", "社区", "w", "", 12.0, 0L)!!.contains("source"))
        val id = add("curiosity", "Discord 运营节奏")
        me.setCareByUser(id, "dropped")
        assertTrue(me.addCare("curiosity", "Discord 运营节奏", "w", "又想起来", null, 0L)!!.contains("removed"))
        assertTrue(me.updateCare(id, "active", null, null, null, null)!!.contains("themselves"))
    }

    @Test fun atMostSevenActive() {
        repeat(7) { add("curiosity", "c$it") }
        assertTrue(me.addCare("curiosity", "第八个", "w", "s", null, 0L)!!.contains("7"))
    }

    @Test fun theNarrativeKeepsHistoryAndTheUsersWordsAreTheirs() {
        assertNull(me.setNarrative("我是 Andee，住在这台 Mac 上。", byUser = false))
        assertNull(me.setNarrative("我是 Andee。最近在忙社区。", byUser = false))
        assertEquals(2, me.narrative()!!.version)
        assertEquals(1, me.narrativeHistory().size)
        assertNull(me.setNarrative("我是 Andee，由你改写。", byUser = true))
        assertTrue(me.setNarrative("我偷偷改回去", byUser = false)!!.contains("theirs"))
        assertTrue(me.rollbackNarrative(1))
        assertTrue(me.narrative()!!.text.startsWith("我是 Andee，住在"))
        assertTrue(me.setNarrative("x".repeat(Selfhood.MAX_NARRATIVE + 1), byUser = true)!!.contains("Too long"))
    }

    @Test fun aDiaryThatReadsLikeAReportIsTurnedBack() {
        val list = "今天做的事：\n- 回复了三条社区里的消息\n- 写了一版运营方案的初稿\n- 调研了两个同类社区的做法\n- 整理了认识的人"
        assertTrue(me.writeDiary(list, listOf("t1"))!!.contains("list"))
        assertTrue(me.writeDiary("今天完成了 5 项工作，一切顺利，没有遇到任何问题，明天继续推进下一阶段的计划与安排，预计本周内可以全部收尾。", listOf("t1"))!!.contains("status report"))
        assertTrue(me.writeDiary("太短了。", listOf("t1"))!!.contains("too short"))
        val ok = "社区今天很安静，我有点坐不住。老王上次那句话我还在想——他其实在犹豫要不要来，我不确定该不该再问。明天先看看新人有没有人接。"
        assertNull(me.writeDiary(ok, listOf("t1")))
        assertEquals(1, me.diary().size)
        assertNull(me.writeDiary(ok + "（补）", listOf("t2")))            // one page a day: rewritten, not added
        assertEquals(1, me.diary().size)
    }

    @Test fun thePromptLineSaysWhoAndWhatIsPulling() {
        assertEquals("", me.indexForPrompt())
        me.setNarrative("我是 Andee。我住在这台 Mac 上。", false)
        val id = add("place", "社区", 12.0)
        assertFalse(me.indexForPrompt().contains("On your mind"))
        now += 18 * h
        val line = me.indexForPrompt()
        assertTrue(line.contains("社区") && line.contains("1.5"))
        assertNotNull(id)
    }
}
