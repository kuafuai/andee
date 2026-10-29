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
    }

    @Test fun aWakeLineGivesBackTheTodo() {
        assertEquals("开周会", WakeText.what(WakeText.due("t1", "开周会", false)))
    }
}
