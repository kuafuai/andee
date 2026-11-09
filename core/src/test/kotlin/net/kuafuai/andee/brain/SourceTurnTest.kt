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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A notification's turn carries who it came from ([Source]) in its user message — including when it had to wait
 * behind another turn, which is exactly when "who asked" used to be lost — and the next turn does not inherit it.
 */
class SourceTurnTest {
    private val requests = mutableListOf<JSONObject>()
    private val firstAsked = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/chat/completions") { ex ->
            val body = JSONObject(String(ex.requestBody.readBytes()))
            val n = synchronized(requests) { requests += body; requests.size }
            if (n == 1) { firstAsked.countDown(); release.await(10, TimeUnit.SECONDS) }   // hold the first turn open
            val msg = JSONObject().put("role", "assistant").put("content", "好的 [END]")
            val out = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", msg)))
                .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1)).toString().toByteArray()
            ex.sendResponseHeaders(200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        executor = java.util.concurrent.Executors.newCachedThreadPool()
        start()
    }
    private val trace = File.createTempFile("trace", "").also { it.delete(); it.mkdirs() }
    private val finals = CountDownLatch(3)

    private val host = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BrainHost::class.java)) { _, m, args ->
        when (m.name) {
            "text" -> (args[0] as BrainText).name
            "locale" -> Locale.ENGLISH
            "maxSteps" -> 10
            "persona", "platformPrompt", "notebookIndex", "scenesIndex", "pagesIndex", "topicsIndex",
            "gearIndex", "planIndex", "friendsIndex", "selfIndex", "stageIndex", "wikiIndex", "reviewRecord", "guard" -> ""
            "scrollback" -> emptyList<ScrollRow>()
            "toolLists" -> listOf(JSONArray())
            "methodOf" -> null
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
        release.countDown()
        brain.stop()
        server.stop(0)
    }

    private fun lastUser(req: JSONObject): String {
        val msgs = req.getJSONArray("messages")
        for (i in msgs.length() - 1 downTo 0) {
            val m = msgs.getJSONObject(i)
            if (m.optString("role") == "user") return m.opt("content").toString()
        }
        return ""
    }

    @Test fun aQueuedNotificationStillKnowsWhoAskedAndTheNextTurnDoesNot() {
        brain.submit("帮我看看天气")
        assertTrue(firstAsked.await(10, TimeUnit.SECONDS))
        val src = Source.fromNotification(
            JSONObject().put("pkg", "com.tencent.mm").put("app", "微信").put("chat", "group")
                .put("title", "B群").put("latest", "小王: 报价多少？").put("when", 1_800_000_000_000L),
        )
        brain.notified("<<<NOTIFICATION>>> 小王: 报价多少？", src)   // arrives while the first turn runs: queued
        Thread.sleep(300)
        release.countDown()
        Thread.sleep(500)
        brain.submit("谢谢")
        assertTrue("turns did not finish", finals.await(20, TimeUnit.SECONDS))
        val users = synchronized(requests) { requests.map(::lastUser) }
        assertEquals(3, users.size)
        assertFalse(users[0].contains("where this turn came from"))
        assertTrue(users[1], users[1].contains("group 「B群」") && users[1].contains("from 「小王」") && users[1].contains("报价多少"))
        assertFalse("the user's next turn inherited the notification's source", users[2].contains("where this turn came from"))
    }
}
