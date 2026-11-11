package net.kuafuai.andee.brain

import com.sun.net.httpserver.HttpServer
import net.kuafuai.andee.config.BrainConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
* The context's three ways of letting go, against a scripted model: old bulk is pruned instead of a harvest,
* a harvest archives what its summary replaces and leaves the note a new brain resumes from, and a new brain
* picks the conversation up from that note and the scrollback.
*/
class ContextCompactionTest {

    private val requests = mutableListOf<JSONObject>()
    private val finals = LinkedBlockingQueue<String>()

    /** The harvest's request answers 500, so it fails and the trim is what runs. */
    @Volatile private var harvestFails = false

    /** Tool calls to answer with, by request number; anything else is a plain answer. */
    private var script: (Int, JSONObject) -> JSONObject? = { _, _ -> null }

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/chat/completions") { ex ->
            val body = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            val n = synchronized(requests) { requests += body; requests.size }
            if (harvestFails && isHarvest(body)) {
                ex.sendResponseHeaders(500, 0)
                ex.responseBody.close()
                return@createContext
            }
            val msg = script(n, body) ?: JSONObject().put("role", "assistant").put(
                "content",
                if (isHarvest(body)) "[quiet] Settled — SUMMARY-OF-THE-START" else "reply $n",
            )
            val out = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg)))
                .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1)).toString().toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }

    private fun isHarvest(body: JSONObject): Boolean = messages(body).any {
        it.optString("role") == "assistant" && it.optString("content").contains("the context is nearly full")
    }

    private fun messages(body: JSONObject): List<JSONObject> {
        val a = body.getJSONArray("messages")
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun toolCall(id: String, name: String, args: String = "{}") = JSONObject().put("role", "assistant").put("content", "").put(
        "tool_calls",
        JSONArray().put(JSONObject().put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args))),
    )

    private val home = File.createTempFile("andee", "").also { it.delete(); it.mkdirs() }
    private val archiveDir = File(home, "context")
    private var rows: List<ScrollRow> = emptyList()

    private val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BrainHost::class.java)) { _, m, args ->
        when (m.name) {
            "text" -> (args[0] as BrainText).name
            "locale" -> Locale.ENGLISH
            "maxSteps" -> 10
            "persona", "platformPrompt", "notebookIndex", "scenesIndex", "pagesIndex", "topicsIndex", "gearIndex",
            "planIndex", "friendsIndex", "selfIndex", "stageIndex", "wikiIndex", "tasksIndex", "peopleIndex", "reviewRecord", "guard" -> ""
            "scrollback" -> rows
            "toolLists" -> listOf(
                JSONArray().put(
                    JSONObject().put("name", "look").put("method", "screen.ui_tree").put("description", "look")
                        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())),
                ),
            )
            "methodOf" -> mapOf("look" to "screen.ui_tree", "read_earlier" to "note.earlier")[args[0] as String]
            "dispatch" -> "x".repeat(30_000)
            "todoStillActive" -> true
            "traceDir", "archiveDir" -> if (m.name == "traceDir") home else archiveDir
            else -> null
        }
    } as BrainHost

    private var brain: LocalBrain? = null

    private fun newBrain() = LocalBrain(
        BrainConfig(BrainConfig.BRAIN_LOCAL, "key", "http://127.0.0.1:${server.address.port}", "m", false, "low"),
        host, onProgress = {}, onFinal = { finals += it },
    ).also { brain = it }

    @After fun tearDown() {
        brain?.stop()
        server.stop(0)
    }

    private fun final(): String = finals.poll(20, TimeUnit.SECONDS) ?: throw AssertionError("no answer")

    /** Until the brain has been idle for a moment — so a harvest a turn's end starts runs before the next sentence. */
    private fun settle(b: LocalBrain) {
        var quiet = 0
        val deadline = System.currentTimeMillis() + 10_000
        while (quiet < 4 && System.currentTimeMillis() < deadline) { Thread.sleep(50); quiet = if (b.isBusy()) 0 else quiet + 1 }
    }

    @Test fun oldToolResultsArePrunedInsteadOfHarvested() {
        script = { n, _ -> if (n <= 3) toolCall("c$n", "look") else null }
        val b = newBrain()
        b.submit("看看屏幕")          // three 24k results: over budget, but all of it is the newest turn
        final()
        b.submit("好")
        final()
        b.submit("再说一句")
        final()
        val last = messages(requests.last())
        val tools = last.filter { it.optString("role") == "tool" }
        assertEquals(3, tools.size)
        assertTrue("old results are pruned", tools.all { it.getString("content").startsWith(ContextPruner.STUB_PREFIX) })
        assertFalse("pruning was enough; no harvest ran", requests.any(::isHarvest))
    }

    @Test fun aHarvestArchivesWhatItReplacesAndANewBrainResumes() {
        val b = newBrain()
        // 31 short turns: 63 messages, over the count, with plenty old enough to fold.
        for (i in 1..31) { b.submit("第${i}句话"); final(); settle(b) }

        assertTrue("the replaced stretch is archived", File(archiveDir, "ctx_1.txt").readText().contains("第1句话"))
        val session = ContextArchive(archiveDir).loadSession()!!
        assertTrue(session.summary.contains("SUMMARY-OF-THE-START"))
        assertFalse("a voice marker is not part of a summary", session.summary.contains("[quiet]"))
        assertTrue("the summary says where the text went", session.summary.contains("read_earlier(id=1)"))

        // The model can read it back.
        script = { n, body ->
            val read = messages(body).any { it.optString("role") == "tool" }
            if (!read && messages(body).last().optString("content").contains("一开始")) toolCall("r$n", "read_earlier", "{\"id\":1}") else null
        }
        b.submit("我一开始说的是什么")
        final()
        val readBack = messages(requests.last()).last { it.optString("role") == "tool" }.getString("content")
        assertTrue(readBack.contains("第1句话"))

        // A restart: a new brain, the scrollback on disk, and the user's next sentence already written as a row.
        b.stop()
        script = { _, _ -> null }
        rows = listOf(
            ScrollRow(true, false, "第1句话", 1L, null, null),
            ScrollRow(true, false, "第30句话", System.currentTimeMillis(), null, null),
            ScrollRow(false, true, "reply 30", System.currentTimeMillis(), null, null),
            ScrollRow(true, false, "接着来", System.currentTimeMillis(), null, null),
        )
        val before = requests.size
        val b2 = newBrain()
        b2.submit("接着来")
        final()
        val first = messages(requests[before])
        assertTrue("it opens on the restart note and the summary", first[1].getString("content").contains("SUMMARY-OF-THE-START"))
        assertTrue(first[1].getString("content").contains("the device restarted"))
        val user = first.filter { it.optString("role") == "user" }.map { it.getString("content") }
        assertTrue("what was said since the summary is back", user.any { it.endsWith("第30句话") })
        assertFalse("what the summary already covers is not", user.any { it.endsWith("第1句话") })
        assertEquals("the new sentence is there once", 1, user.count { it.contains("接着来") })
    }

    @Test fun trimsInARowShareOneArchive() {
        harvestFails = true
        val b = newBrain()
        for (i in 1..40) { b.submit("第${i}句话"); final(); settle(b) }
        val index = org.json.JSONArray(File(archiveDir, "index.json").readText())
        assertEquals("one archive, grown by every trim", 1, index.length())
        val text = File(archiveDir, "ctx_${index.getJSONObject(0).getLong("id")}.txt").readText()
        assertTrue(text.contains("第1句话"))
        assertTrue(text.contains("第5句话"))
        val head = messages(requests.last())[1].getString("content")
        assertTrue("the marker names the archive", head.contains("read_earlier(id=${index.getJSONObject(0).getLong("id")})"))
    }

    @Test fun aHarvestTheUserTalkedOverRunsAgain() {
        val b = newBrain()
        for (i in 1..29) { b.submit("第${i}句话"); final(); settle(b) } // 59 messages: under the count
        // What the phone did (2026-11-11): over the count, and the user answers every turn at once — each harvest is
        // launched, then talked over before its answer is back. The trim holds the size where the last one started.
        script = { _, body -> if (isHarvest(body)) { Thread.sleep(400); null } else null }
               for (i in 30..39) { b.submit("第${i}句话"); final(); Thread.sleep(100) }
        // Then they stop for a moment: the harvest after the last turn must get its go, not be skipped as "already
        // tried at this size" — which is what happened to every one of them on the phone.
 script = { _, _ -> null }
 settle(b)
        assertTrue("the harvest ran to the end once the user paused", File(archiveDir, "session.json").exists())
    }
}
