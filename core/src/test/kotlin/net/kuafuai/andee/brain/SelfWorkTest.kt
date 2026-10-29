package net.kuafuai.andee.brain

import net.kuafuai.andee.config.Selfhood
import org.junit.Assert.assertEquals
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

    @Test fun lookingAtTheScreenIsALookNotWork() {
        assertEquals(Selfhood.Effect.LOOKED, SelfWork.effect(listOf(c("screen.screenshot"), c("screen.ui_tree")), "c1"))
        assertEquals(Selfhood.Effect.ACTED, SelfWork.effect(listOf(c("screen.screenshot"), c("screen.tap")), "c1"))
    }

    @Test fun theOpeningSaysWhatItIsAndThatItHasEverything() {
        val care = Selfhood.Care("c1", "社区", "他交给我的", "place", 12 * Selfhood.HOUR_MS, 0, 0, "x", "active", "上次发了一帖", 2, false, 0, 0, emptyList())
        val t = SelfWork.opening(care, 1.5, "写一份方案", now = 18 * Selfhood.HOUR_MS)
        assertTrue(t.contains("1.5× overdue"))
        assertTrue(t.contains("上次发了一帖"))
        assertTrue(t.contains("nothing came of it"))
        assertTrue(t.contains("cares(action=update, id=c1"))
        assertTrue(t.contains("the screen"))
    }
}
