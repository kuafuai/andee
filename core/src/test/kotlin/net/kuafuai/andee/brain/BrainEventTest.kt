package net.kuafuai.andee.brain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrainEventTest {
    @Test fun onlyWritesThatLandedAreEvents() {
        assertEquals(BrainEvent.Kind.DIARY, BrainEvent.fromTool("note.diary", JSONObject().put("action", "write").put("text", "x"), JSONObject().put("written", true))?.kind)
        assertNull(BrainEvent.fromTool("note.diary", JSONObject().put("action", "write"), JSONObject().put("written", false)))
        assertNull(BrainEvent.fromTool("note.diary", JSONObject().put("action", "read"), JSONObject()))
        assertNull(BrainEvent.fromTool("note.wiki_search", JSONObject(), JSONObject()))
        assertEquals("老王", BrainEvent.fromTool("note.person", JSONObject().put("action", "upsert").put("name", "老王"), JSONObject().put("saved", true))?.subject)
        assertNull(BrainEvent.fromTool("screen.tap", JSONObject(), JSONObject()))
        // A wiki write or read carries the article's id, which is what the timeline row opens.
        val meta = JSONObject().put("id", "w1").put("title", "运营规划")
        assertEquals("w1", BrainEvent.fromTool("note.wiki_write", JSONObject().put("title", "x"), JSONObject().put("written", true).put("meta", meta))?.ref)
        val read = BrainEvent.fromTool("note.wiki_read", JSONObject().put("id", "w1"), JSONObject().put("read", true).put("meta", meta))!!
        assertEquals(BrainEvent.Kind.WIKI_READ to "运营规划", read.kind to read.subject)
        assertNull(BrainEvent.fromTool("note.wiki_read", JSONObject().put("id", "w9"), JSONObject().put("read", false)))
        val shown = BrainEvent.fromTool("ui.show_wiki", JSONObject().put("id", "w1"), JSONObject().put("shown", true).put("title", "运营规划"))!!
        assertEquals(Triple(BrainEvent.Kind.WIKI_SHOWN, "运营规划", "w1"), Triple(shown.kind, shown.subject, shown.ref))
        // Showing is never something a quiet turn may do.
        assertEquals(false, SelfWork.allowed("ui.show_wiki"))
    }

    @Test fun aWakeLineGivesBackTheTodo() {
        assertEquals("开周会", WakeText.what(WakeText.due("t1", "开周会", false)))
    }
}
