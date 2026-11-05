package net.kuafuai.andee.net

import org.java_websocket.handshake.HandshakeImpl1Client
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 9008 port can do everything the app can, so the token check is the whole lock.
 * The server is built but never started, which is all `presentsToken` needs.
 */
class BodyWsServerTokenTest {

    private val token = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val server = BodyWsServer(port = 0, token = token, registry = CallExtensionRegistry(), onCommand = { _, _ -> null })

    private fun handshake(path: String = "/", auth: String? = null) = HandshakeImpl1Client().apply {
        resourceDescriptor = path
        if (auth != null) put("Authorization", auth)
    }

    @Test
    fun `the token in a bearer header opens the port`() {
        assertTrue(server.presentsToken(handshake(auth = "Bearer $token")))
        assertTrue(server.presentsToken(handshake(auth = "bearer $token")))
    }

    @Test
    fun `the token in the query opens the port`() {
        assertTrue(server.presentsToken(handshake(path = "/?token=$token")))
        assertTrue(server.presentsToken(handshake(path = "/?x=1&token=$token")))
    }

    @Test
    fun `no token, a wrong one or part of the right one is refused`() {
        assertFalse(server.presentsToken(handshake()))
        assertFalse(server.presentsToken(handshake(auth = "Bearer ")))
        assertFalse(server.presentsToken(handshake(auth = "Bearer ${token.reversed()}")))
        assertFalse(server.presentsToken(handshake(auth = "Bearer ${token.take(20)}")))
        assertFalse(server.presentsToken(handshake(auth = token)))
        assertFalse(server.presentsToken(handshake(path = "/?token=")))
        assertFalse(server.presentsToken(handshake(path = "/?notoken=$token")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a server cannot be built without a real token`() {
        BodyWsServer(port = 0, token = "", registry = CallExtensionRegistry(), onCommand = { _, _ -> null })
    }
}
