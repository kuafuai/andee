package net.kuafuai.andee.brain

import net.kuafuai.andee.config.Selfhood
import net.kuafuai.andee.net.ToolSchemas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfWorkTest {
    private fun c(method: String, action: String = "", ok: Boolean = true) = LocalBrain.WorkCall(method, action, "id-$method-$action", ok)

    @Test fun theDeviceJudgesWhatTheTurnDid() {
        assertEquals(Selfhood.Effect.NOTHING, SelfWork.effect(emptyList(), "c1"))
        assertEquals(Selfhood.Effect.LOOKED, SelfWork.effect(listOf(c("note.wiki_search"), c("note.person", "find")), "c1"))
        assertEquals(Selfhood.Effect.ACTED, SelfWork.effect(listOf(c("note.wiki_search"), c("note.wiki_write")), "c1"))
    }

    @Test fun writingBackTheCaresOwnNoteIsNotWork() {
        assertEquals(Selfhood.Effect.NOTHING, SelfWork.effect(listOf(c("note.cares", "update")), "c1"))
    }

    @Test fun aRefusedWriteDoesNotCount() {
        assertEquals(Selfhood.Effect.LOOKED, SelfWork.effect(listOf(c("note.recall"), c("note.wiki_write", ok = false)), "c1"))
    }

    @Test fun noScreenNoDeviceNothingOutward() {
        val methods = SelfWork.tools(listOf(ToolSchemas.all())).flatMap { a -> (0 until a.length()).map { a.getJSONObject(it).getString("method") } }
        assertTrue("note.wiki_write" in methods)
        assertTrue("ui.nudge" in methods)
        for (m in methods) assertTrue(m, SelfWork.allowed(m))
        for (m in listOf("screen.tap", "screen.ui_tree", "device.sms.send", "device.vault.fill", "ui.ask", "ui.show_html", "note.fold", "note.forget", "note.wiki_share", "peer.ask")) {
            assertFalse(m, SelfWork.allowed(m))
        }
    }

    @Test fun theOpeningSaysWhatItIsAndWhatItCannotDo() {
        val care = Selfhood.Care("c1", "社区", "他交给我的", "place", 12 * Selfhood.HOUR_MS, 0, 0, "x", "active", "上次发了一帖", 2, false, 0, 0, emptyList())
        val t = SelfWork.opening(care, 1.5, "写一份方案", now = 18 * Selfhood.HOUR_MS)
        assertTrue(t.contains("1.5× overdue"))
        assertTrue(t.contains("上次发了一帖"))
        assertTrue(t.contains("nothing came of it"))
        assertTrue(t.contains("cares(action=update, id=c1"))
    }
}
