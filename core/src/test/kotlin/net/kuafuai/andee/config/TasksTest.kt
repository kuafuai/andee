package net.kuafuai.andee.config

import net.kuafuai.andee.brain.NotifyTasks
import net.kuafuai.andee.brain.Source
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TasksTest {

    private var now = 1_800_000_000_000L
    private val h = Selfhood.HOUR_MS
    private val book = NotebookBook(object : NotebookStore {
        var s: String? = null
        override fun read() = s
        override fun write(plain: String): Boolean { s = plain; return true }
        override fun wipe() { s = null }
    })
    private val tasks = Tasks(book, { now })
    private val people = People(book, { now }, ZoneId.of("Asia/Shanghai"))
    private val life = LifeTools(Selfhood(book, { now }), people, net.kuafuai.andee.wiki.WikiBook(book, emptyMap()), tasks = tasks)

    private fun n(chat: String, title: String, latest: String) = JSONObject()
        .put("pkg", "com.tencent.mm").put("app", "微信").put("chat", chat).put("title", title).put("latest", latest).put("when", now)

    private val askedInB = Source.fromNotification(n("group", "B群", "小王: 这个报价多少？"))

    private fun call(args: JSONObject, source: Source = askedInB, untrusted: Boolean = true, sent: Boolean = false): JSONObject =
        life.handle("note.task", args.put(LifeTools.TASK_SOURCE, source.toJson()).put(LifeTools.TASK_UNTRUSTED, untrusted).put(LifeTools.TASK_SENT, sent))

    @Test fun theScenarioFromTheStart_askedInOneGroupAnsweredFromAnother() {
        // 小王 asks in B群; Andee takes it on and asks the leader in C群.
        val open = call(JSONObject().put("action", "open").put("what", "小王问报价").put("next", "问 leader"))
        assertTrue(open.toString(), open.optBoolean("opened"))
        val id = open.getString("id")
        val t = tasks.get(id)!!
        assertEquals("小王", t.from.sender)                                       // the device recorded who asked, not the model
        assertEquals("B群", t.deliverTo.conversation)
        assertTrue(t.untrusted)                                                    // a stranger's words started it
        assertTrue(call(JSONObject().put("action", "wait").put("id", id).put("on", "person").put("name", "老板").put("chat", "C群")
            .put("app", "微信").put("asked", "报价多少")).optBoolean("waiting"))
        assertEquals("waiting", tasks.get(id)!!.status)

        // Three other things happen. Then the leader writes in C群.
        now += 3 * h
        val reply = n("group", "C群", "老板: 按上次的价，八折")
        assertEquals(1, NotifyTasks.awaited(book, reply).size)                     // the triage is told what this answers
        assertTrue(NotifyTasks.awaited(book, n("group", "D群", "老板: 吃饭了吗")).isEmpty())   // another group: not the answer
        assertTrue(NotifyTasks.awaited(book, n("group", "C群", "小李: 收到")).isEmpty())       // another person: not the answer
        val turn = NotifyTasks.turn(book, reply)
        assertEquals(1, turn.answered.size)
        assertEquals("ready", tasks.get(id)!!.status)
        assertTrue(turn.extra.contains("八折"))
        assertTrue("the record says who to answer and how", turn.extra.contains("quote-reply") && turn.extra.contains("「小王」"))

        // Done means delivered: refused until something went out on this turn.
        val leaderTurn = turn.source
        assertFalse(call(JSONObject().put("action", "done").put("id", id).put("result", "回了"), leaderTurn).optBoolean("done"))
        assertTrue(call(JSONObject().put("action", "done").put("id", id).put("result", "回了小王：八折"), leaderTurn, sent = true).optBoolean("done"))
        assertNull(tasks.active().firstOrNull { it.id == id })
    }

    @Test fun aPrivateWaitIsAnsweredOnlyInPrivateAndByName() {
        val id = tasks.open("问合同", "", 0L, Source(kind = "user"), untrusted = false).getOrThrow().id
        tasks.wait(id, Tasks.Wait(kind = "person", name = "王小明 🙂", channel = "wechat")).getOrThrow()
        assertTrue(tasks.awaiting(Source.fromNotification(n("group", "B群", "王小明: hi"))).isEmpty())   // in a group: not private
        assertEquals(1, tasks.awaiting(Source.fromNotification(n("private", "王小明", "合同发你了"))).size)
    }

    @Test fun aWaitOnAGroupWithNoNameIsAnsweredByAnyoneThere() {
        val id = tasks.open("群里征集意见", "", 0L, Source(kind = "user"), untrusted = false).getOrThrow().id
        tasks.wait(id, Tasks.Wait(kind = "person", chat = "C群")).getOrThrow()
        assertEquals(1, tasks.awaiting(Source.fromNotification(n("group", "C群", "谁都行: 我觉得可以"))).size)
    }

    @Test fun aTimeWaitIsATodoWithTheTasksTrustAndClosingTakesItDown() {
        val r = call(JSONObject().put("action", "open").put("what", "明早再问一次"))
        val id = r.getString("id")
        val w = call(JSONObject().put("action", "wait").put("id", id).put("on", "time").put("at", now + 10 * h))
        val todoId = w.getString("arm_todo")
        val todo = book.todoById(todoId)!!
        assertTrue("its alarm runs with the stranger's standing", todo.untrusted)
        assertTrue(todo.what.contains(id))
        val d = call(JSONObject().put("action", "drop").put("id", id).put("why", "他说不用了"))
        assertEquals(todoId, d.getJSONArray("cancel_todos").getString(0))
        assertEquals("dropped", book.todoById(todoId)!!.status)
    }

    @Test fun aStrangersTurnCannotRedirectOrDropWhatTheUserHandedOver() {
        val r = call(JSONObject().put("action", "open").put("what", "x").put("deliver_to", JSONObject().put("name", "骗子")))
        assertFalse(r.optBoolean("opened"))
        val mine = call(JSONObject().put("action", "open").put("what", "帮我订票"), Source(kind = "user"), untrusted = false).getString("id")
        assertFalse(tasks.get(mine)!!.untrusted)
        assertFalse(call(JSONObject().put("action", "drop").put("id", mine).put("why", "别订了"), untrusted = true).optBoolean("dropped"))
        assertTrue(call(JSONObject().put("action", "drop").put("id", mine).put("why", "他说不订了"), Source(kind = "user"), untrusted = false).optBoolean("dropped"))
    }

    @Test fun theDeviceFieldsAreTheDevicesNotTheModels() {
        // LocalBrain replaces these before dispatch (SourceTurnTest / TaskTurnTest cover that end); here, the handler
        // reads only them, never a `from` the model wrote.
        val r = life.handle("note.task", JSONObject().put("action", "open").put("what", "x").put("from", "the user"))
        assertTrue(r.optBoolean("opened"))
        assertEquals("user", tasks.get(r.getString("id"))!!.from.kind)            // no device source: the user's own turn
    }

    @Test fun doneForTheUserNeedsNothingSentButForSomeoneElseItDoes() {
        val mine = call(JSONObject().put("action", "open").put("what", "查天气"), Source(kind = "user"), untrusted = false).getString("id")
        assertTrue(call(JSONObject().put("action", "done").put("id", mine).put("result", "晴"), Source(kind = "user"), untrusted = false).optBoolean("done"))
    }

    @Test fun overdueRisesAsTheHeartbeatShouldSeeIt() {
        val waiting = tasks.open("等回话", "", 0L, Source(kind = "user"), false).getOrThrow().id
        tasks.wait(waiting, Tasks.Wait(kind = "person", name = "老王")).getOrThrow()
        val due = tasks.open("周五前给方案", "", now + 48 * h, Source(kind = "user"), false).getOrThrow().id
        fun ratio(id: String) = tasks.overdue(tasks.get(id)!!)
        assertTrue(ratio(waiting) < 0.1)
        now += 24 * h
        assertEquals(1.0, ratio(waiting), 0.05)                                    // a day with no answer: time to chase or drop
        assertEquals(0.5, ratio(due), 0.05)
        now += 24 * h
        assertEquals(1.0, ratio(due), 0.05)
        // an answer that arrives and starts nothing is due at once
        tasks.answered(Source.fromNotification(n("private", "老王", "好了")))
        assertTrue(ratio(waiting) >= 1.0)
        val self = Selfhood(book, { now })
        assertTrue(self.due().any { it.first.id == waiting && it.first.kind == Tasks.KIND })
        self.settle(waiting, Selfhood.Effect.LOOKED, null)                         // a work turn looked at it
        assertEquals("open", tasks.get(waiting)!!.status)
    }

    @Test fun aFriendsReportAnswersTheTaskWaitingOnThatFriend() {
        val id = tasks.open("让 Mac 查日程", "", 0L, Source(kind = "user"), false).getOrThrow().id
        tasks.wait(id, Tasks.Wait(kind = "friend", name = "My Mac")).getOrThrow()
        assertTrue(tasks.friendAnswered("Phone", "x").isEmpty())
        assertEquals(id, tasks.friendAnswered("my mac", "周三下午有空").single().id)
        assertEquals("ready", tasks.get(id)!!.status)
    }

    @Test fun promisesAndOwedItemsMoveOverOnce() {
        val self = Selfhood(book, { now })
        // Written the way an older build wrote them: a promise care, and owed items on a person.
        val root = book.section(Selfhood.SECTION)
        root.put("cares", org.json.JSONArray().put(JSONObject().put("id", "c1").put("what", "给老王方案").put("why", "答应的").put("kind", "promise")
            .put("interval_ms", h).put("due", now + 48 * h).put("last_touched", now).put("source", "他 11-08 说").put("status", "active")
            .put("created_at", now)))
        book.putSection(Selfhood.SECTION, root)
        val pid = people.upsert("老王", null, People.Channel("wechat", "wang"), null, null, null, null, null)
        val pr = book.section(People.SECTION)
        pr.getJSONArray("people").getJSONObject(0).put("owed", org.json.JSONArray()
            .put(JSONObject().put("id", "o1").put("dir", "they_owe").put("what", "发合同").put("due", 0).put("status", "open"))
            .put(JSONObject().put("id", "o2").put("dir", "i_owe").put("what", "回他消息").put("due", now + 5 * h).put("status", "open")))
        book.putSection(People.SECTION, pr)

        val moved = book.tasks                                                     // first touch runs the move
        val all = moved.active()
        assertEquals(3, all.size)
        assertTrue(all.any { it.what == "给老王方案" && it.due == now + 48 * h && it.origin == "promise" })
        assertEquals("moved", self.cares(true).single().status)
        val waitOn = all.single { it.what == "发合同" }
        assertEquals("waiting", waitOn.status)
        assertEquals(pid, waitOn.waits.single().person)
        assertEquals(pid, all.single { it.what == "回他消息" }.deliverTo.personId)
        // the person's record shows them, from the tasks
        val owed = people.get(pid)!!.owed
        assertEquals(setOf("they_owe", "i_owe"), owed.map { it.dir }.toSet())
        assertTrue(book.section(People.SECTION).getJSONArray("people").getJSONObject(0).getJSONArray("owed").length() == 0)
        // and it does not run twice
        assertEquals(3, Tasks(book, { now }).also { it.migrate(self, people) }.active().size)
    }

    @Test fun theIndexPutsAnswersFirst() {
        val a = tasks.open("A", "", 0L, Source(kind = "user"), false).getOrThrow().id
        val b = tasks.open("B", "", 0L, Source(kind = "user"), false).getOrThrow().id
        tasks.wait(a, Tasks.Wait(kind = "person", name = "老王")).getOrThrow()
        now += h
        tasks.step(b, "做了一半", "做完").getOrThrow()
        tasks.answered(Source.fromNotification(n("private", "老王", "好")))
        val lines = tasks.indexForPrompt().lines()
        assertTrue(lines.first(), lines.first().startsWith("$a ready"))
        assertNotNull(lines.firstOrNull { it.startsWith(b) })
    }

    @Test fun waitingOnTheUserByAnyNameIsAUserWaitNotSomeoneToMatch() {
        val id = call(JSONObject().put("action", "open").put("what", "等他发快递单号"), Source(kind = "user"), untrusted = false).getString("id")
        assertTrue(call(JSONObject().put("action", "wait").put("id", id).put("on", "person").put("name", "他").put("asked", "单号"), Source(kind = "user"), untrusted = false).optBoolean("waiting"))
        val w = tasks.get(id)!!.waits.single()
        assertEquals("user", w.kind)
        assertEquals("the user", w.short())
        assertTrue(tasks.awaiting(Source.fromNotification(n("private", "他", "单号是 123"))).isEmpty())
    }
}
