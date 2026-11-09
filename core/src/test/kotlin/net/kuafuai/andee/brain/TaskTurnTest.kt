package net.kuafuai.andee.brain

import com.sun.net.httpserver.HttpServer
import net.kuafuai.andee.config.BrainConfig
import net.kuafuai.andee.config.LifeTools
import net.kuafuai.andee.config.NotebookBook
import net.kuafuai.andee.config.NotebookStore
import net.kuafuai.andee.config.People
import net.kuafuai.andee.config.Selfhood
import net.kuafuai.andee.config.Tasks
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A notification's turn that asks someone else and ends: the device fills in where the task came from (a forged
 * `_source` from the model is thrown away), and a turn that sent something without writing a task down is asked
 * once — not twice — before it ends.
 */
class TaskTurnTest {
    private val requests = mutableListOf<JSONObject>()
    private val book = NotebookBook(object : NotebookStore {
        var s: String? = null
        override fun read() = s
        override fun write(plain: String): Boolean { s = plain; return true }
        override fun wipe() { s = null }
    })
    private val tasks = book.tasks
    private val life = LifeTools(Selfhood(book), People(book), net.kuafuai.andee.wiki.WikiBook(book, emptyMap()), tasks = tasks)

    private fun call(id: String, name: String, args: JSONObject) =
        JSONObject().put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args.toString()))

    /** The model's script: type and send in C群, try to end, get the reminder, open + wait, end; then end again. */
    private val script = listOf(
        listOf(call("c1", "type_text", JSONObject().put("text", "老板，报价多少？")), call("c2", "tap", JSONObject())),
        emptyList(),
        listOf(call("c3", "task", JSONObject().put("action", "open").put("what", "小王问报价")
            .put(LifeTools.TASK_SOURCE, JSONObject().put("kind", "user")).put(LifeTools.TASK_UNTRUSTED, false))),
        listOf(call("c4", "task", JSONObject().put("action", "wait").put("id", "T1").put("on", "person").put("name", "老板").put("chat", "C群"))),
        emptyList(),
    )
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/chat/completions") { ex ->
            val body = JSONObject(String(ex.requestBody.readBytes()))
            val n = synchronized(requests) { requests += body; requests.size }
            val calls = script.getOrElse(n - 1) { emptyList() }
            val msg = JSONObject().put("role", "assistant").put("content", if (calls.isEmpty()) "问过老板了，等他回 [END]" else "")
                .apply { if (calls.isNotEmpty()) put("tool_calls", JSONArray(calls)) }
            val out = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg)))
                .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1)).toString().toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        start()
    }
    private val trace = File.createTempFile("trace", "").also { it.delete(); it.mkdirs() }
    private val finals = CountDownLatch(1)
    private val names = mapOf("type_text" to "screen.type", "tap" to "screen.tap", "task" to "note.task")

    private val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BrainHost::class.java)) { _, m, args ->
        when (m.name) {
            "text" -> (args[0] as BrainText).name
            "locale" -> Locale.ENGLISH
            "maxSteps" -> 10
            "persona", "platformPrompt", "notebookIndex", "scenesIndex", "pagesIndex", "topicsIndex",
            "gearIndex", "planIndex", "friendsIndex", "selfIndex", "stageIndex", "wikiIndex", "reviewRecord", "guard" -> ""
            "tasksIndex" -> tasks.indexForPrompt()
            "scrollback" -> emptyList<ScrollRow>()
            "toolLists" -> listOf(JSONArray().apply {
                names.keys.forEach { put(JSONObject().put("name", it).put("method", names[it]).put("description", it)
                    .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()))) }
            })
            "methodOf" -> names[args[0]]
            "dispatch" -> if (args[0] == "note.task") life.handle("note.task", args[1] as JSONObject) else JSONObject().put("ok", true)
            "todoStillActive" -> true
            "traceDir" -> trace
            else -> null
        }
    } as BrainHost

    private val brain = LocalBrain(
        BrainConfig(BrainConfig.BRAIN_LOCAL, "key", "http://127.0.0.1:${server.address.port}", "m", false, "low"),
        host, onProgress = {}, onFinal = { finals.countDown() },
    )

    @After fun tearDown() {
        brain.stop()
        server.stop(0)
    }

    @Test fun theDeviceSaysWhereItCameFromAndAsksOnceToWriteItDown() {
        val src = Source.fromNotification(
            JSONObject().put("pkg", "com.tencent.mm").put("app", "微信").put("chat", "group")
                .put("title", "B群").put("latest", "小王: 报价多少？").put("when", 1_800_000_000_000L),
        )
        brain.notified("<<<NOTIFICATION>>> 小王: 报价多少？", src)
        assertTrue("the turn did not finish", finals.await(20, TimeUnit.SECONDS))
        val t = tasks.get("T1")!!
        assertEquals("the model's forged source was replaced", "notify", t.from.kind)
        assertEquals("小王", t.deliverTo.sender)
        assertTrue("a notification's task carries its distrust", t.untrusted)
        assertEquals("waiting", t.status)
        val reminders = synchronized(requests) {
            requests.sumOf { r ->
                val msgs = r.getJSONArray("messages")
                (0 until msgs.length()).count { msgs.getJSONObject(it).opt("content")?.toString()?.startsWith("(device note, not from the user: this turn sent") == true }
            }.let { requests.last().getJSONArray("messages").let { m -> (0 until m.length()).count { m.getJSONObject(it).opt("content")?.toString()?.contains("this turn sent something") == true } } }
        }
        assertEquals("asked once, not on every ending", 1, reminders)
    }
}
