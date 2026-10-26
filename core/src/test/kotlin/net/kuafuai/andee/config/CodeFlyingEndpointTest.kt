package net.kuafuai.andee.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeFlyingEndpointTest {
    @Test fun bareHostTakesTheDefaultScheme() {
        val e = CodeFlyingEndpoint.parse(" 1.2.3.4:8080/ ", sslDefault = false)!!
        assertEquals("http://1.2.3.4:8080/voice/llm", e.llmBaseUrl)
        assertEquals("ws://1.2.3.4:8080/voice/asr/bigmodel_async", e.asrEndpoint)
        assertEquals("http://1.2.3.4:8080/baas-api", e.marketBaseUrl)
    }

    @Test fun typedSchemeWins() {
        val e = CodeFlyingEndpoint.parse("https://cf.example.com", sslDefault = false)!!
        assertEquals("wss://cf.example.com/voice/tts/bidirection", e.ttsEndpoint)
    }

    @Test fun junkIsNull() {
        assertNull(CodeFlyingEndpoint.parse("", false))
        assertNull(CodeFlyingEndpoint.parse("a b", false))
        assertNull(CodeFlyingEndpoint.parse("host/path", false))
    }
}
