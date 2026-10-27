package net.kuafuai.andee.config

import net.kuafuai.andee.brain.NudgeBook
import net.kuafuai.andee.net.TurnGuards
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class PlansTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    /** 2026-10-27 09:00 local. */
    private var now = LocalDateTime.of(2026, 10, 27, 9, 0).atZone(zone).toInstant().toEpochMilli()
    private val book = NotebookBook(object : NotebookStore {
        var s: String? = null
        override fun read() = s
        override fun write(plain: String): Boolean { s = plain; return true }
        override fun wipe() { s = null }
    })
    private val plans = Plans(book, { now }, zone)
    private val nudges = NudgeBook { now }

    private fun item(time: String, what: String, kind: String = "remind", vararg buttons: String) = JSONObject()
        .put("time", time).put("kind", kind).put("what", what).put("because", "规律喝水")
        .apply { if (buttons.isNotEmpty()) put("buttons", JSONArray(buttons.toList())).put("dismiss", buttons.last()) }

    private fun write(vararg items: JSONObject, date: String = "today") = plans.handle(
        "note.day_plan",
        JSONObject().put("action", "write").put("date", date).put("intent", "今天少打扰").put("items", JSONArray(items.toList())),
        8,
    )

    @Test fun timedRemindersBecomeBubbleTodos() {
        write(item("10:00", "该喝水啦"), item("08:00", "早上好"), item("", "看看发布进度", "prepare"))
        val sync = plans.reconcile()
        // 08:00 is already past and the prepare item has no bubble: one todo.
        assertEquals(1, sync.arm.size)
        val spec = JSONObject(sync.arm[0].nudge)
        assertEquals("该喝水啦", spec.getString("text"))
        assertEquals("2026-10-27#p1", spec.getString("plan_ref"))
        // Reconciling again changes nothing.
        val again = plans.reconcile()
        assertTrue(again.arm.isEmpty() && again.cancel.isEmpty())
    }

    @Test fun rewritingCancelsTheOldBubble() {
        write(item("10:00", "该喝水啦"))
        val first = plans.reconcile().arm.single()
        write(item("11:00", "该喝水啦"))
        val sync = plans.reconcile()
        assertEquals(listOf(first.id), sync.cancel)
        assertEquals(1, sync.arm.size)
    }

    @Test fun whatTheUserRemovedCannotComeBack() {
        write(item("10:00", "该喝水啦"))
        plans.reconcile()
        plans.dropItemByUser("2026-10-27", "p1")
        assertEquals(1, plans.reconcile().cancel.size)
        val r = write(item("10:30", "该喝水啦"))
        assertEquals(1, r.getJSONArray("refused").length())
        assertTrue(plans.reconcile().arm.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun everyItemNeedsABecause() {
        write(JSONObject().put("time", "10:00").put("kind", "remind").put("what", "该喝水啦"))
    }

    @Test(expected = IllegalArgumentException::class) fun atMostFiveInterruptions() {
        write(*(10..15).map { item("$it:00", "第 $it 点") }.toTypedArray())
    }

    @Test fun dueBubbleCarriesItsPlanRefAndOutcomeIsWrittenBack() {
        write(item("10:00", "要帮你点外卖吗？", "ask", "好", "不用"))
        val todo = plans.reconcile().arm.single()
        now = todo.at
        val n = plans.bubbleFor(todo, nudges, NudgeBook.Level.NORMAL, emptySet())
        assertNotNull(n)
        assertEquals("2026-10-27#p1", n!!.planRef)
        assertEquals(todo.at + 60 * 60_000L, n.expiresAt)
        plans.recordOutcome(n, "button", "不用")
        assertEquals("declined", plans.day("2026-10-27")!!.items.single().status)
        assertEquals("done", book.todoById(todo.id)!!.status)
    }

    @Test fun aBubbleLongPastItsTimeIsMissed() {
        write(item("10:00", "该喝水啦"))
        val todo = plans.reconcile().arm.single()
        now = todo.at + 2 * 60 * 60_000L
        assertNull(plans.bubbleFor(todo, nudges, NudgeBook.Level.NORMAL, emptySet()))
        assertEquals("missed", plans.day("2026-10-27")!!.items.single().status)
    }

    @Test fun overTheUsersLimitIsSkippedWithTheReason() {
        write(item("10:00", "该喝水啦"))
        val todo = plans.reconcile().arm.single()
        now = todo.at
        assertNull(plans.bubbleFor(todo, nudges, NudgeBook.Level.OFF, emptySet()))
        val it = plans.day("2026-10-27")!!.items.single()
        assertEquals("skipped", it.status)
        assertTrue(it.note.contains("turned bubbles off"))
    }

    @Test fun keptHistorySurvivesARewrite() {
        write(item("10:00", "该喝水啦"))
        val todo = plans.reconcile().arm.single()
        now = todo.at
        plans.recordOutcome(plans.bubbleFor(todo, nudges, NudgeBook.Level.NORMAL, emptySet())!!, "timeout", null)
        write(item("15:00", "起来活动一下"))
        val items = plans.day("2026-10-27")!!.items
        assertEquals(listOf("done", "planned"), items.map { it.status })
        assertEquals(listOf("p1", "p2"), items.map { it.id })
    }

    @Test fun directionsComeFromTheUserAndStayRemovedWhenTheyRemoveThem() {
        val add = plans.handle(
            "note.directions",
            JSONObject().put("action", "add").put("title", "Andee 发布").put("source", "10-26: 这周要发 0.6.2"),
            8,
        )
        assertTrue(add.getBoolean("added"))
        plans.setDirectionByUser(add.getString("id"), "dropped")
        val again = plans.handle(
            "note.directions",
            JSONObject().put("action", "add").put("title", "Andee 发布").put("source", "x"),
            8,
        )
        assertFalse(again.getBoolean("added"))
    }

    @Test fun indexNamesTodayAndTheDirections() {
        assertTrue(plans.indexForPrompt().startsWith("No plan yet"))
        plans.handle("note.directions", JSONObject().put("action", "add").put("title", "规律喝水").put("source", "他说的"), 8)
        write(item("10:00", "该喝水啦"))
        val line = plans.indexForPrompt()
        assertTrue(line.contains("\"今天少打扰\""))
        assertTrue(line.contains("10:00 remind \"该喝水啦\""))
        assertTrue(line.contains("Long-term: 规律喝水"))
    }

    @Test fun tomorrowsPlanIsScheduledToo() {
        write(item("09:30", "早"), date = "tomorrow")
        val t = plans.reconcile().arm.single()
        assertTrue(JSONObject(t.nudge).getString("plan_ref").startsWith("2026-10-28#"))
    }

    @Test fun aStrangersTurnCannotWriteThePlan() {
        for (m in listOf("note.day_plan", "note.directions")) {
            val thrown = runCatching { TurnGuards.check(m, silent = false, untrusted = true) }.exceptionOrNull()
            assertTrue(thrown is IllegalStateException)
        }
        TurnGuards.check("note.day_plan", silent = true, untrusted = false)
    }

    @Test fun morningOncePerDayAndNotBeforeFive() {
        assertTrue(plans.morningDue())
        plans.markMorning()
        assertFalse(plans.morningDue())
        now = LocalDateTime.of(2026, 10, 28, 4, 30).atZone(zone).toInstant().toEpochMilli()
        assertFalse(plans.morningDue())
        now = LocalDateTime.of(2026, 10, 28, 7, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue(plans.morningDue())
    }

    private fun slotItem(slot: String, what: String) = JSONObject()
        .put("slot", slot).put("kind", "ask").put("what", what).put("because", "规律喝水")
        .put("buttons", JSONArray(listOf("好", "不用"))).put("dismiss", "不用")

    @Test fun museOnlyWithALooseTimeItemSpacedAndNotAtNight() {
        write(item("10:00", "该喝水啦"))
        assertFalse(plans.museDue())          // only clock-time items: nothing to decide
        write(slotItem("午饭前", "要帮你点外卖吗？"))
        assertTrue(plans.museDue())
        assertTrue(plans.museContext()!!.contains("午饭前"))
        plans.markMuse()
        assertFalse(plans.museDue())          // within the gap
        now += Plans.MUSE_GAP_MS
        assertTrue(plans.museDue())
        now = LocalDateTime.of(2026, 10, 27, 23, 0).atZone(zone).toInstant().toEpochMilli()
        assertFalse(plans.museDue())          // quiet hours
    }

    @Test fun museCapPerDay() {
        write(slotItem("下午", "起来活动一下？"))
        repeat(Plans.MUSE_PER_DAY) { plans.markMuse(); now += Plans.MUSE_GAP_MS }
        assertFalse(plans.museDue())
    }

    @Test fun theMusesChoiceBecomesABubbleOrIsSkippedWithTheReason() {
        write(slotItem("午饭前", "要帮你点外卖吗？"))
        val n = plans.bubbleForItem("p1", nudges, NudgeBook.Level.NORMAL, emptySet())!!
        assertEquals("2026-10-27#p1", n.planRef)
        assertEquals("不用", n.dismiss)
        assertNull(plans.bubbleForItem("p1", nudges, NudgeBook.Level.OFF, emptySet()))
        assertEquals("skipped", plans.day("2026-10-27")!!.items.single().status)
        assertNull(plans.bubbleForItem("nope", nudges, NudgeBook.Level.NORMAL, emptySet()))
    }

    @Test fun weeklyLookAtDirectionsIsAskedForThenGoesAway() {
        plans.handle("note.directions", JSONObject().put("action", "add").put("title", "规律喝水").put("source", "他说的"), 8)
        assertTrue(plans.indexForPrompt().contains("A week since"))
        plans.handle("note.directions", JSONObject().put("action", "list"), 8)
        assertFalse(plans.indexForPrompt().contains("A week since"))
        now += Plans.WEEK_MS
        assertTrue(plans.indexForPrompt().contains("A week since"))
    }

    @Test fun morningContextCarriesYesterdaysOutcomes() {
        write(item("10:00", "该喝水啦"))
        val todo = plans.reconcile().arm.single()
        now = todo.at
        plans.recordOutcome(plans.bubbleFor(todo, nudges, NudgeBook.Level.NORMAL, emptySet())!!, "button", null)
        now = LocalDateTime.of(2026, 10, 28, 8, 0).atZone(zone).toInstant().toEpochMilli()
        val c = plans.morningContext()
        assertTrue(c.contains("Yesterday's plan"))
        assertTrue(c.contains("\"该喝水啦\" → done"))
        assertTrue(c.contains("Today's draft:\n(none)"))
    }
}
