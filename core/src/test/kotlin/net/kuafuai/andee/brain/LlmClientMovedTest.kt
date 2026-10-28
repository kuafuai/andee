package net.kuafuai.andee.brain

import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress

/** 2026-10-28: a gateway that moved answered a POST with 301 → an nginx page, and the turn died on a JSON parse error. */
class LlmClientMovedTest {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/moved/chat/completions") { ex ->
            ex.responseHeaders.add("Location", "https://www.example.net/voice/llm/chat/completions")
            ex.sendResponseHeaders(301, -1); ex.close()
        }
        createContext("/page/chat/completions") { ex ->
            val b = "<html><body>welcome</body></html>".toByteArray()
            ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        start()
    }

    @After fun stop() = server.stop(0)

    private fun call(path: String): LlmMovedException {
        val c = LlmClient("http://127.0.0.1:${server.address.port}/$path", "k", "m", false, "low")
        try {
            c.complete(listOf(JSONObject().put("role", "user").put("content", "hi")), JSONArray())
        } catch (e: LlmMovedException) {
            return e
        }
        fail("no LlmMovedException"); throw AssertionError()
    }

    @Test fun aRedirectIsReportedWithWhereItPointed() {
        val e = call("moved")
        assertEquals(301, e.code)
        assertEquals("https://www.example.net/voice/llm/chat/completions", e.where)
    }

    @Test fun aWebPageIsReportedAsNotTheApi() {
        val e = call("page")
        assertEquals(200, e.code)
        assertTrue(e.sample.startsWith("<html>"))
    }
}
