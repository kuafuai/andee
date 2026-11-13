package net.kuafuai.andee.desktop.dsh

import net.kuafuai.andee.config.NotebookBook
import net.kuafuai.andee.config.NotebookStore
import net.kuafuai.andee.config.Tasks
import net.kuafuai.andee.peer.PeerRemote
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
 * A paired phone using this Mac's dsh directly ([DshRemoteAgent]) over the real [DshWorkers] and the stand-in `dsh`:
 * the job it starts is its own (no 「事」 here, the report goes to it and not to this Mac's brain), it sees only its
 * own workers, and what it reads is inside a worker's folder and nowhere else.
 */
class DshRemoteTest {

    private val root = File.createTempFile("dshremote", "").also { it.delete(); it.mkdirs() }
    private val tasks = Tasks(NotebookBook(object : NotebookStore {
        var s: String? = null
        override fun read() = s
        override fun write(plain: String): Boolean { s = plain; return true }
        override fun wipe() { s = null }
    }))
    private val fake: DshWorkers.Bin = run {
        val f = File(root, "fake-dsh")
        f.writeBytes(javaClass.getResourceAsStream("/dsh/fake-dsh")!!.readBytes())
        f.setExecutable(true)
        DshWorkers.Bin(f.path, "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin")
    }
    private val ownTurns = LinkedBlockingQueue<String>()
    private val remoteTurns = LinkedBlockingQueue<Pair<String, String>>()
    private val made = mutableListOf<DshWorkers>()

    private fun workers() = DshWorkers(
        indexFile = File(root, "dsh_workers.json"), home = File(root, "workers"),
        ask = { false }, onFinished = { _, turn -> ownTurns += turn },
        locate = { fake }, zh = { false }, tasks = DshTaskLink { tasks },
        onRemoteFinished = { w, turn -> remoteTurns += w.origin to turn },
    ).also { made += it }

    @After fun tearDown() { made.forEach { it.cancelAll() }; root.deleteRecursively() }

    @Test fun aPhonesJobIsItsOwn_noTaskHereAndTheReportGoesToIt() {
        val w = workers()
        val agent = DshRemoteAgent(w, "phone-1") { true }
        val r = agent.call("dsh.delegate", JSONObject().put("task", "Write the weekly report").put("title", "周报").put("for_task", "T9"))
        assertEquals("1", r.getString("worker"))
        assertFalse("no 「事」 is opened on the Mac for it, and it may not name one", r.has("task"))
        assertTrue(tasks.all().isEmpty())
        assertEquals("phone-1", w.get("1")!!.origin)

        val (to, report) = remoteTurns.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("no report went to the phone")
        assertEquals("phone-1", to)
        assertTrue(report, report.contains("- out.md ("))
        assertTrue("the Mac's own brain heard nothing", ownTurns.isEmpty())
        assertFalse("and no task record rides on it", report.contains("This report answers something you are in the middle of"))
    }

    @Test fun aPhoneSeesOnlyItsOwnWorkers() {
        val w = workers()
        w.delegate(JSONObject().put("task", "the Mac's own job"), DshPlan.Trust.USER)
        ownTurns.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("own job did not end")
        val phone = DshRemoteAgent(w, "phone-1") { true }
        phone.call("dsh.delegate", JSONObject().put("task", "the phone's job"))
        remoteTurns.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("phone job did not end")

        val list = phone.call("dsh.status", JSONObject()).getJSONArray("workers")
        assertEquals(listOf("2"), (0 until list.length()).map { list.getJSONObject(it).getString("id") })
        for (m in listOf("dsh.status", "dsh.cancel", "dsh.read")) {
            try { phone.call(m, JSONObject().put("id", "1").put("path", "out.md")); fail("$m on the Mac's own worker should be refused") } catch (e: IllegalArgumentException) {
                assertTrue("$m: ${e.message}", e.message!!.contains("not started from this device"))
            }
        }
        val other = DshRemoteAgent(w, "phone-2") { true }
        try { other.call("dsh.status", JSONObject().put("id", "2")); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("not started from this device")) }
    }

    @Test fun theFilesCanBeListedAndFetchedFromItsFolderOnly() {
        val w = workers()
        val phone = DshRemoteAgent(w, "phone-1") { true }
        phone.call("dsh.delegate", JSONObject().put("task", "make a file"))
        remoteTurns.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("no report")
        val dir = File(w.get("1")!!.dir)
        File(dir, "big.bin").writeBytes(ByteArray(PeerRemote.CHUNK + 5) { 7 })
        File(root, "secret.txt").writeText("not yours")

        val names = phone.list("1", "").getJSONArray("entries").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
        assertTrue(names.toString(), "out.md" in names && "big.bin" in names)
        val first = phone.read("1", "big.bin", 0, PeerRemote.CHUNK)
        assertEquals(PeerRemote.CHUNK, first.bytes.size); assertFalse(first.eof)
        val rest = phone.read("1", "big.bin", PeerRemote.CHUNK.toLong(), PeerRemote.CHUNK)
        assertEquals(5, rest.bytes.size); assertTrue(rest.eof)
        for (bad in listOf("../../secret.txt", "../secret.txt")) {
            try { phone.read("1", bad, 0, 10); fail("$bad should be refused") } catch (e: IllegalArgumentException) {
                assertTrue("$bad: ${e.message}", e.message!!.contains("not inside"))
            }
        }
        try { phone.read("2", "out.md", 0, 10); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("no worker")) }
    }

    @Test fun aPhonesJobReportsItsProgressToThePhoneOnly() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<String, DshWorkers.Progress?>>()
        val w = DshWorkers(
            indexFile = File(root, "dsh_workers.json"), home = File(root, "workers"),
            ask = { false }, onFinished = { _, turn -> ownTurns += turn },
            locate = { fake }, zh = { false }, tasks = DshTaskLink { tasks },
            onRemoteFinished = { x, turn -> remoteTurns += x.origin to turn },
            onProgress = { id, p -> seen += id to p },
        ).also { made += it }
        DshRemoteAgent(w, "phone-1") { true }.call("dsh.delegate", JSONObject().put("task", "weekly"))
        remoteTurns.poll(30, TimeUnit.SECONDS) ?: throw AssertionError("no report")
        // The progress callback carries the worker id; the Mac looks the origin up from the worker, so it can tell whose it is.
        assertTrue("progress was reported while it ran", seen.any { it.second != null })
        assertTrue("and its end", seen.last().second == null)
        assertEquals("phone-1", w.get("1")!!.origin)
    }

    @Test fun finderIsNotSomethingThatReachesAPhone_andAnOffSwitchMeansNoAgent() {
        val w = workers()
        try { DshRemoteAgent(w, "phone-1") { true }.call("dsh.open", JSONObject().put("id", "1")); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Finder"))
        }
        assertFalse(DshRemoteAgent(w, "phone-1") { false }.available())
    }
}
