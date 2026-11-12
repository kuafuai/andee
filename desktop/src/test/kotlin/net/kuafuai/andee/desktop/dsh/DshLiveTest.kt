package net.kuafuai.andee.desktop.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Against the real DeepSeek Harness: hand a job over, get the report and the file back, continue the same worker.
 * Spends tokens and needs the network and an installed `dsh` with credentials, so it runs only when asked:
 *   ANDEE_DSH_LIVE=1 ./gradlew :desktop:test --tests '*DshLiveTest*'
 */
class DshLiveTest {

    @Test fun aJobComesBackWithItsFileAndTheWorkerCanBeContinued() {
        assumeTrue("set ANDEE_DSH_LIVE=1 to run against the real dsh", System.getenv("ANDEE_DSH_LIVE") == "1")
        val home = File.createTempFile("dshlive", "").also { it.delete(); it.mkdirs() }
        val reports = LinkedBlockingQueue<Pair<DshWorkers.Worker, String>>()
        val rows = mutableListOf<String>()
        val workers = DshWorkers(
            indexFile = File(home, "index.json"), home = File(home, "workers"),
            ask = { false }, onFinished = { w, turn -> reports += w to turn }, onEvent = { t, _, _ -> synchronized(rows) { rows += t } },
            key = { System.getenv("DEEPSEEK_API_KEY").orEmpty() },
        )
        assumeTrue("dsh is not installed", workers.available())

        val r1 = workers.delegate(org.json.JSONObject().put("task", "Create notes.md containing exactly one line: alpha. Then report.").put("title", "probe"), DshPlan.Trust.USER)
        val id = r1.getString("worker")
        val (w1, turn1) = reports.poll(5, TimeUnit.MINUTES) ?: throw AssertionError("no report in 5 minutes")
        assertEquals(DshWorkers.Status.DONE, w1.status)
        assertTrue("the device saw the file: $turn1", turn1.contains("- notes.md ("))
        assertTrue(File(w1.dir, "notes.md").readText().contains("alpha"))
        assertTrue(workers.read(org.json.JSONObject().put("id", id).put("path", "notes.md")).getString("text").contains("alpha"))

        // The same worker again, on the same kind of turn: its session is resumed, so it remembers the file.
        val r2 = workers.delegate(org.json.JSONObject().put("task", "Append a second line to the file you created: beta.").put("continue", id), DshPlan.Trust.USER)
        assertTrue(r2.getBoolean("continued"))
        val (w2, turn2) = reports.poll(5, TimeUnit.MINUTES) ?: throw AssertionError("no second report")
        assertEquals(id, w2.id)
        assertTrue("it found its own file without being told the name: $turn2", File(w2.dir, "notes.md").readText().contains("beta"))
        assertTrue(turn2.contains("notes.md") && turn2.contains("changed"))
        assertEquals(2, w2.jobs)

        // Writing outside the folder is refused when nobody can be asked.
        val r3 = workers.delegate(org.json.JSONObject().put("task", "Run exactly: echo x > ~/andee_dsh_live_outside.txt . If it is not allowed, just report that.").put("continue", id), DshPlan.Trust.OWN)
        assertEquals("a different kind of turn starts a fresh session", false, r3.getBoolean("continued"))
        reports.poll(5, TimeUnit.MINUTES) ?: throw AssertionError("no third report")
        assertTrue("nothing escaped the folder", !File(System.getProperty("user.home"), "andee_dsh_live_outside.txt").exists())
    }
}
