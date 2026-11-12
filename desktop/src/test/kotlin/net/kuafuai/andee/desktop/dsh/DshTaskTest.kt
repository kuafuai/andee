package net.kuafuai.andee.desktop.dsh

import net.kuafuai.andee.brain.Source
import net.kuafuai.andee.config.NotebookBook
import net.kuafuai.andee.config.NotebookStore
import net.kuafuai.andee.config.Tasks
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A job handed to dsh is a 「事」 until delivered: the wait, the answer, the stop and the restart, all through the real
 * [DshWorkers] and [DshTaskLink] against a stand-in `dsh` (`resources/dsh/fake-dsh`) that speaks the same JSON-RPC
 * with no model behind it — so it runs everywhere, in seconds, with no key.
 */
class DshTaskTest {

    private val root = File.createTempFile("dshtask", "").also { it.delete(); it.mkdirs() }
    private val book = NotebookBook(object : NotebookStore {
        var s: String? = null
        override fun read() = s
        override fun write(plain: String): Boolean { s = plain; return true }
        override fun wipe() { s = null }
    })
    private val tasks = Tasks(book)
    private val fake: DshWorkers.Bin = run {
        val f = File(root, "fake-dsh")
        f.writeBytes(javaClass.getResourceAsStream("/dsh/fake-dsh")!!.readBytes())
        f.setExecutable(true)
        DshWorkers.Bin(f.path, "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin")
    }
    private val turns = LinkedBlockingQueue<String>()
    private val rows = LinkedBlockingQueue<Triple<String, String, String>>()
    private val made = mutableListOf<DshWorkers>()
    private val progress = java.util.concurrent.CopyOnWriteArrayList<Pair<String, DshWorkers.Progress?>>()

    private fun workers() = DshWorkers(
        indexFile = File(root, "dsh_workers.json"), home = File(root, "workers"),
        ask = { false }, onFinished = { _, turn -> turns += turn },
        onEvent = { a, b, c -> rows += Triple(a, b, c) },
        locate = { fake }, zh = { false }, tasks = DshTaskLink { tasks },
        onProgress = { id, p -> progress += id to p },
    ).also { made += it }

    @After fun tearDown() {
        made.forEach { it.cancelAll() }
        root.deleteRecursively()
    }

    private fun turn(): String = turns.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("no report came back")

    @Test fun aJobHandedOverWithNoTaskOpensOne_andItsReportAnswersIt() {
        val w = workers()
        val r = w.delegate(JSONObject().put("task", "Write the weekly report").put("title", "周报"), DshPlan.Trust.USER)
        val id = r.getString("task")
        val t = tasks.get(id)!!
        assertEquals("周报", t.what)
        assertEquals(DshTaskLink.ORIGIN, t.origin)
        assertEquals("user", t.from.kind)
        assertEquals("the task waits on the worker", "1", t.waits.single { it.kind == "worker" }.name)
        assertTrue(rows.poll(5, TimeUnit.SECONDS)!!.let { it.first.contains(id) && it.third == "1" })

        val report = turn()
        assertTrue(report, report.contains("- out.md ("))
        assertTrue("the task's record rides on the report", report.contains("This report answers something you are in the middle of") && report.contains("<<<TASK $id"))
        assertEquals("ready", tasks.get(id)!!.status)
        assertTrue(tasks.get(id)!!.waits.single().answer.contains("out.md"))
        assertEquals(id, w.get("1")!!.task)
    }

    @Test fun aRunningJobShowsWhatItIsDoing_andIsGoneWhenItEnds() {
        val w = workers()
        w.delegate(JSONObject().put("task", "weekly"), DshPlan.Trust.USER)
        turn()
        val seen = progress.filter { it.first == "1" }.mapNotNull { it.second }
        val last = seen.last()
        assertEquals(1, last.step)
        assertEquals(listOf("Search: week 46 numbers +1", "Write: out.md"), last.recent)
        assertEquals("write it up", last.current!!.text)
        assertEquals(1, last.done)
        assertEquals("Wrote out.md with the week.", last.thought)
        assertTrue("the card goes when the job ends", progress.last().let { it.first == "1" && it.second == null })
        assertEquals(null, w.progress("1"))
    }

    @Test fun aJobForANamedTask_continuesTheWorkerThatTaskUsedLast() {
        val w = workers()
        val id = w.delegate(JSONObject().put("task", "first draft").put("title", "方案"), DshPlan.Trust.USER).getString("task")
        turn()
        val again = w.delegate(JSONObject().put("task", "make it shorter").put("for_task", id), DshPlan.Trust.USER)
        assertEquals("the same worker, its session resumed", "1", again.getString("worker"))
        assertTrue(again.getBoolean("continued"))
        assertEquals(id, again.getString("task"))
        turn()
        assertEquals(listOf("session/working-directory/set", "session/resume"),
            File(root, "workers/1/.fake-calls").readLines().map { it.substringBefore(' ') })
        assertEquals(2, tasks.get(id)!!.waits.count { it.kind == "worker" })

        // continue=1 with no task named finds the task worker #1 is working for.
        assertEquals(id, w.delegate(JSONObject().put("task", "and a title page").put("continue", "1"), DshPlan.Trust.USER).getString("task"))
        turn()
    }

    @Test fun aSessionHeldByAnotherDshWindow_goesOnInANewSession_withTheDraftsItLeft() {
        val w = workers()
        val id = w.delegate(JSONObject().put("task", "dig into it").put("title", "幕后"), DshPlan.Trust.USER).getString("task")
        turn()
        val first = w.get("1")!!.sessionId
        File(root, "workers/1/draft.md").writeText("half a report")
        File(root, "workers/1/.held").writeText("")                     // the user's own dsh web has it open
        val again = w.delegate(JSONObject().put("task", "继续").put("continue", "1"), DshPlan.Trust.USER)
        assertEquals(id, again.getString("task"))
        val report = turn()
        assertTrue("it finished, it did not fail: $report", report.contains("has finished"))
        assertEquals(DshWorkers.Status.DONE, w.get("1")!!.status)
        val second = w.get("1")!!.sessionId
        assertTrue("a new session, not the held one", second != first)
        assertEquals(listOf("session/working-directory/set $first", "session/working-directory/set $second"),
            File(root, "workers/1/.fake-calls").readLines())
        val brief = File(root, "workers/1/.fake-brief").readText()
        assertTrue("told what the old session reported: $brief", brief.contains("Wrote out.md with the week."))
        assertTrue("told which drafts are there: $brief", brief.contains("- draft.md") && brief.contains("- out.md"))
        assertTrue(rows.any { it.first.contains("新会话") || it.first.contains("new one") })
        assertTrue(w.status(JSONObject().put("id", "1")).getJSONArray("in_folder").toList().contains("draft.md"))
    }

    @Test fun aTaskThatIsNotOpenIsRefused_beforeAnyWorkerStarts() {
        val w = workers()
        try { w.delegate(JSONObject().put("task", "x").put("for_task", "T99"), DshPlan.Trust.USER); fail() } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("no task T99"))
        }
        val done = tasks.open("old", "", 0L, Source(kind = "user"), false).getOrThrow().id
        tasks.close(done, "done", "ok").getOrThrow()
        try { w.delegate(JSONObject().put("task", "x").put("for_task", done), DshPlan.Trust.USER); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("already done"))
        }
        assertFalse(File(root, "workers/1").exists())
    }

    @Test fun aStoppedJobAnswersTheWait_withoutBringingTheTaskBackAsReady() {
        val w = workers()
        val id = w.delegate(JSONObject().put("task", "[slow] take your time"), DshPlan.Trust.USER).getString("task")
        Thread.sleep(1_500)
        w.cancel(JSONObject().put("id", "1"))
        val deadline = System.currentTimeMillis() + 20_000
        while (w.get("1")!!.status == DshWorkers.Status.RUNNING && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals(DshWorkers.Status.CANCELLED, w.get("1")!!.status)
        assertEquals("open", tasks.get(id)!!.status)
        assertTrue(tasks.get(id)!!.waits.single().answer.contains("cancelled"))
        assertTrue("a stopped job wakes no turn", turns.poll(1, TimeUnit.SECONDS) == null)
    }

    @Test fun aJobRunningWhenAndeeClosed_comesBackAsAReadyTask() {
        val id = tasks.open("long research", "", 0L, Source(kind = "user"), false).getOrThrow().id
        tasks.wait(id, Tasks.Wait(kind = "worker", name = "7")).getOrThrow()
        File(root, "dsh_workers.json").writeText(
            org.json.JSONArray().put(JSONObject().put("id", "7").put("title", "research").put("dir", File(root, "workers/7").path)
                .put("trust", "USER").put("session", "andee-7-1").put("status", "RUNNING").put("task", id)).toString(),
        )
        val w = workers()
        assertEquals(DshWorkers.Status.INTERRUPTED, w.get("7")!!.status)
        assertEquals("ready", tasks.get(id)!!.status)
        assertTrue(tasks.get(id)!!.waits.single().answer.contains("continue=7"))
    }
}
