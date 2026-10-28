package net.kuafuai.andee.brain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Proxy

/** What a friend's request can see and touch on this device: the screen, and nothing of the owner's life. */
class GuestHostTest {

    private val calls = mutableListOf<Pair<String, Boolean>>()

    /** An owner's host that answers everything with something, so an empty answer can only come from the guest. */
    private val owner: BrainHost = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(BrainHost::class.java)) { _, m, args ->
        when (m.name) {
            "notebookIndex", "scenesIndex", "pagesIndex", "topicsIndex", "gearIndex", "persona" -> "owner's ${m.name}"
            "toolLists" -> listOf(JSONArray(listOf("screen.tap", "device.vault.fill", "note.recall", "shell.run", "ui.ask", "device.sms.read", "peer.ask")
                .map { JSONObject().put("name", it.replace('.', '_')).put("method", it) }))
            "methodOf" -> (args[0] as String).replaceFirst('_', '.')
            "dispatch" -> { calls += (args[0] as String) to (args[4] as Boolean); "ok" }
            "scrollback" -> listOf(ScrollRow(true, false, "my secret", 0, null, null))
            else -> null
        }
    } as BrainHost

    private val guest = GuestHost(owner)

    @Test fun theOwnersLifeIsNotThere() {
        assertEquals("", guest.notebookIndex())
        assertEquals("", guest.pagesIndex())
        assertEquals("", guest.topicsIndex())
        assertEquals("", guest.gearIndex())
        assertTrue(guest.scrollback().isEmpty())
        // The same individual, though.
        assertEquals("owner's persona", guest.persona())
    }

    @Test fun onlyTheScreenAndAskingAreListed() {
        val listed = guest.toolLists().flatMap { a -> (0 until a.length()).map { a.getJSONObject(it).getString("method") } }
        assertEquals(listOf("screen.tap", "ui.ask"), listed)
        assertNull(guest.methodOf("device_vault.fill"))
    }

    @Test fun everythingElseIsRefusedAndWhatRunsRunsUntrusted() {
        guest.dispatch("screen.tap", JSONObject(), callId = "t1", silent = false, untrusted = false)
        assertEquals("screen.tap" to true, calls.single())
        for (m in listOf("device.vault.fill", "note.recall", "shell.run", "device.sms.read", "peer.ask", "device.contacts")) {
            try { guest.dispatch(m, JSONObject(), "t2", false, false); fail("$m reached the owner's device") } catch (e: IllegalStateException) { }
        }
        assertFalse(calls.any { it.first != "screen.tap" })
    }
}
