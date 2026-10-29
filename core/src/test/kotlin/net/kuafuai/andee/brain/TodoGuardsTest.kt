package net.kuafuai.andee.brain

import com.sun.net.httpserver.HttpServer
import net.kuafuai.andee.config.BrainConfig
import net.kuafuai.andee.config.NotebookBook
import net.kuafuai.andee.config.NotebookStore
import net.kuafuai.andee.config.Todo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.Locale

class TodoGuardsTest {

    @Test fun aTodoThatBundlesSeveralThingsIsRefused() {
        assertNotNull(NotebookBook.bundledTodo("1) 问他留言发了没 2) 看看码上飞的测试结果"))
        assertNotNull(NotebookBook.bundledTodo("两件事：①问评论 ②查测试"))
        assertNull(NotebookBook.bundledTodo("问他留言发了没，顺便问他睡得怎么样"))
        assertNull(NotebookBook.bundledTodo("下午 3 点提醒他开会"))
    }

    @Test fun theTodoIdRidesOnTheWakeLine() {
        assertEquals("t42", WakeText.todoId(WakeText.due("t42", "开会", false)))
        assertNull(WakeText.todoId("hello"))
    }

    @Test fun theReviewSeesWhatIsAlreadyRecorded() {
        val book = NotebookBook(object : NotebookStore {
            var s: String? = null
            override fun read() = s
            override fun write(plain: String): Boolean { s = plain; return true }
            override fun wipe() { s = null }
        })
        book.remember("他的作息", "晚睡晚起", "", "user")
        book.addTodo(Todo("t1", "周五问他发布进度", "once", 2_000_000_000_000L, "", 2_000_000_000_000L, 0, "", "", "paused", 0))
        val r = book.recordForReview()
        assertTrue(r.contains("他的作息") && r.contains("晚睡晚起"))
        assertTrue(r.contains("t1") && r.contains("paused") && r.contains("周五问他发布进度"))
    }

    // ---- a todo paused while its turn waited does not run ----

    private val said = mutableListOf<String>()
    private var active = true
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/chat/completions") { ex ->
            val msgs = JSONObject(ex.requestBody.readBytes().toString(Charsets.UTF_8)).getJSONArray("messages")
            synchronized(said) { said += msgs.getJSONObject(msgs.length() - 1).optString("content") }
            Thread.sleep(500)
            val out = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", "ok"))))
                .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1)).toString().toByteArray()
            runCatching { ex.sendResponseHeaders(200, out.size.toLong()); ex.responseBody.use { it.write(out) } }
        }
        start()
    }
    private val trace = File.createTempFile("trace", "").also { it.delete(); it.mkdirs() }
    private val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BrainHost::class.java)) { _, m, args ->
        when (m.name) {
            "text" -> (args[0] as BrainText).name
            "locale" -> Locale.ENGLISH
            "maxSteps" -> 5
            "persona", "platformPrompt", "notebookIndex", "scenesIndex", "pagesIndex", "topicsIndex",
            "gearIndex", "planIndex", "friendsIndex", "selfIndex", "wikiIndex", "reviewRecord" -> ""
            "scrollback" -> emptyList<ScrollRow>()
            "toolLists" -> listOf(JSONArray())
            "todoStillActive" -> active
            "traceDir" -> trace
            else -> null
        }
    } as BrainHost
    private val brain = LocalBrain(BrainConfig(BrainConfig.BRAIN_LOCAL, "key", "http://127.0.0.1:${server.address.port}", "m", false, "low"), host, onProgress = {}, onFinal = {})

    @After fun tearDown() { brain.stop(); server.stop(0) }

    @Test fun aQueuedTodoPausedMeanwhileDoesNotRun() {
        brain.submit("pause that reminder")
        Thread.sleep(150)
        brain.wake(WakeText.due("t1", "the reminder", false))   // queued behind the turn above
        active = false                                           // that turn paused it
        Thread.sleep(2000)
        assertEquals(1, synchronized(said) { said.size })
        assertTrue(said.single().contains("pause that reminder"))
    }
}
