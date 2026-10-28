package net.kuafuai.andee.brain

import net.kuafuai.andee.config.BrainConfig
import org.junit.Assert.assertEquals
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class LocatorTest {

    @Test fun readsAPoint() {
        assertEquals(Locator.Result.Found(384, 741), Locator.parse("<point>384 741</point>"))
        assertEquals(Locator.Result.Found(384, 741), Locator.parse("好的 <point>(384,741)</point>"))
        assertEquals(Locator.Result.Found(894, 900, "↑"), Locator.parse("<seen>↑</seen>\n<point>894 900</point>"))
    }

    @Test fun readsTheCentreOfABox() {
        assertEquals(Locator.Result.Found(150, 250), Locator.parse("<bbox>100 200 200 300</bbox>"))
    }

    @Test fun noneIsAbsentNotAGuess() {
        assertEquals(Locator.Result.Absent, Locator.parse("<none/>"))
        assertEquals(Locator.Result.Absent, Locator.parse("<none>"))
        assertEquals(Locator.Result.Absent, Locator.parse("<seen>✕ 关闭图标</seen> 不是删除。<none/>"))
    }

    @Test fun anythingElseFails() {
        assertTrue(Locator.parse("在右上角") is Locator.Result.Failed)
        assertTrue(Locator.parse("<point>1384 741</point>") is Locator.Result.Failed)
    }

    private fun cfg(url: String, mode: String = BrainConfig.BRAIN_LOCAL, key: String = "k") = BrainConfig(mode, key, url, "m", false, "low")

    @Test fun theConfiguredModelEverywhereUnlessOverridden() {
        // cfg() names the brain's model "m"; the backend does not matter.
        for (url in listOf("https://ark.cn-beijing.volces.com/api/v3", "https://api.deepseek.com", "https://api.openai-next.com/v1")) {
            assertEquals("m", Locator.modelFor(cfg(url), ""))
        }
        assertEquals("m", Locator.modelFor(cfg("https://x/voice/llm", BrainConfig.BRAIN_CODEFLYING), ""))
        assertEquals(Locator.DOUBAO, Locator.modelFor(cfg("https://api.deepseek.com"), Locator.DOUBAO))
        assertNull(Locator.modelFor(cfg("https://ark.cn-beijing.volces.com/api/v3", key = ""), ""))
    }

    // ---- falling back ----

    private fun serve(vararg replies: Pair<Int, String>): Pair<HttpServer, String> {
        var n = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { ex ->
                val (code, body) = replies[minOf(n++, replies.lastIndex)]
                val b = body.toByteArray()
                ex.sendResponseHeaders(code, b.size.toLong()); ex.responseBody.use { it.write(b) }
            }
            start()
        }
        return server to "http://127.0.0.1:${server.address.port}/v1"
    }

    private fun answer(text: String) = 200 to """{"choices":[{"message":{"role":"assistant","content":${org.json.JSONObject.quote(text)}}}],"usage":{}}"""

    @Test fun aModelThatCannotTakeImagesIsWithdrawnAtOnce() {
        val (srv, url) = serve(400 to """{"error":{"message":"image input is not supported"}}""")
        try {
            val c = cfg(url)
            val model = "no-vision-1"
            assertEquals(model, Locator.modelFor(c, model))
            assertTrue(Locator(c, model).locate("AAAA", "发送") is Locator.Result.Failed)
            assertNull("the tool disappears", Locator.modelFor(c, model))
            assertEquals("another model is unaffected", "other", Locator.modelFor(c, "other"))
        } finally { srv.stop(0) }
    }

    @Test fun threeUnreadableAnswersInARowWithdrawItButASuccessResetsTheCount() {
        val (srv, url) = serve(answer("在右上角"), answer("在右上角"), answer("<seen>⚙</seen><point>818 79</point>"), answer("不知道"), answer("不知道"), answer("不知道"))
        try {
            val c = cfg(url)
            val model = "flaky-1"
            val loc = Locator(c, model)
            assertTrue(loc.locate("AAAA", "x") is Locator.Result.Failed)
            assertTrue(loc.locate("AAAA", "x") is Locator.Result.Failed)
            assertEquals(model, Locator.modelFor(c, model))            // two misses: still offered
            assertEquals(Locator.Result.Found(818, 79, "⚙"), loc.locate("AAAA", "x"))   // a hit clears the count
            repeat(2) { loc.locate("AAAA", "x") }
            assertEquals(model, Locator.modelFor(c, model))            // two more misses: still offered
            loc.locate("AAAA", "x")
            assertNull(Locator.modelFor(c, model))                     // the third in a row: gone
        } finally { srv.stop(0) }
    }
}
