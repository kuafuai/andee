package net.kuafuai.andee.screen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An unchanged `screens` swipe used to come back as
 * `list_end: "true — ... will do nothing."` A spinner, a skeleton, or a page
 * still loading hashes the same as the end of a list, and the model treated
 * that sentence as permission to stop the batch. The stop is now an observation.
 */
class SwipeScreensTest {

    private fun fp(
        pkg: String = "com.example",
        sig: Int = 1,
        blind: Boolean = false,
        noRoot: Boolean = false,
    ) = JSONObject().put("pkg", pkg).put("sig", sig).apply {
        if (blind) put("blind", true)
        if (noRoot) put("no_root", true)
    }

    private fun causes(out: JSONObject): List<String> {
        val arr = out.getJSONArray("possible_causes")
        return (0 until arr.length()).map { arr.getString(it) }
    }

    @Test
    fun `an unchanged readable swipe is not reported as the end of the list`() {
        val before = fp()
        val after = fp()
        assertEquals(
            ScreenController.Companion.SwipeStop.UNCHANGED,
            ScreenController.swipeStepStop(before, after, pixelTold = false, pxMoved = false),
        )
        val out = ScreenController.swipeScreensResult(
            10, 800, 10, 200, 300L,
            requested = 3, moved = 1, stop = ScreenController.Companion.SwipeStop.UNCHANGED,
        )
        assertFalse(out.getBoolean("screen_changed"))
        assertEquals(listOf("end_of_list", "content_loading"), causes(out))
        val note = out.getString("screen_changed_note")
        assertTrue(note.contains("Wait briefly"))
        assertTrue(note.contains("get_screen_element"))
        assertTrue(note.contains("take_screenshot"))
        assertFalse(out.has("list_end"))
        assertFalse(out.has("position_hint"))
        assertFalse(out.toString().contains("will do nothing"))
    }

    @Test
    fun `swipes that moved report a count and a position, not a cause`() {
        assertEquals(
            ScreenController.Companion.SwipeStop.NONE,
            ScreenController.swipeStepStop(fp(pkg = "a"), fp(pkg = "b"), pixelTold = false, pxMoved = false),
        )
        assertEquals(
            ScreenController.Companion.SwipeStop.NONE,
            ScreenController.swipeStepStop(fp(sig = 1), fp(sig = 2), pixelTold = false, pxMoved = false),
        )
        val blind = fp(blind = true)
        assertEquals(
            ScreenController.Companion.SwipeStop.NONE,
            ScreenController.swipeStepStop(blind, fp(blind = true), pixelTold = true, pxMoved = true),
        )
        val out = ScreenController.swipeScreensResult(
            10, 800, 10, 200, 300L,
            requested = 3, moved = 3, stop = ScreenController.Companion.SwipeStop.NONE,
        )
        assertEquals(3, out.getInt("screens_moved"))
        assertEquals(3, out.getInt("requested"))
        assertEquals("10,800", out.getString("from"))
        assertEquals("10,200", out.getString("to"))
        assertEquals(300L, out.getLong("duration_ms"))
        assertFalse(out.getBoolean("fling"))
        assertTrue(out.getBoolean("screen_changed"))
        assertEquals(
            "You moved 3 screens at one full finger-travel each. Count from where you started: " +
                "that is how you get back, and how you know you have passed something.",
            out.getString("position_hint"),
        )
        assertFalse(out.has("possible_causes"))
        assertFalse(out.has("list_end"))
        assertFalse(out.toString().contains("will do nothing"))
    }

    @Test
    fun `partial progress is kept, and still pixels on a blind tree are unchanged`() {
        val blind = fp(blind = true)
        assertEquals(
            ScreenController.Companion.SwipeStop.UNCHANGED,
            ScreenController.swipeStepStop(blind, fp(blind = true), pixelTold = true, pxMoved = false),
        )
        val out = ScreenController.swipeScreensResult(
            1, 900, 1, 200, 300L,
            requested = 3, moved = 2, stop = ScreenController.Companion.SwipeStop.UNCHANGED,
        )
        assertEquals(2, out.getInt("screens_moved"))
        assertEquals(3, out.getInt("requested"))
        assertFalse(out.getBoolean("screen_changed"))
        assertEquals(listOf("end_of_list", "content_loading"), causes(out))
        assertFalse(out.has("list_end"))
    }

    @Test
    fun `a blind tree with no pixels is unreadable, not the end of the list`() {
        val noRoot = fp(blind = true, noRoot = true)
        val withheld = fp(blind = true)
        assertEquals(
            ScreenController.Companion.SwipeStop.UNREADABLE,
            ScreenController.swipeStepStop(noRoot, fp(blind = true, noRoot = true), pixelTold = false, pxMoved = false),
        )
        assertEquals(
            ScreenController.Companion.SwipeStop.UNREADABLE,
            ScreenController.swipeStepStop(withheld, fp(blind = true), pixelTold = false, pxMoved = false),
        )
        assertEquals(
            ScreenController.Companion.SwipeStop.UNREADABLE,
            ScreenController.swipeStepStop(fp(), fp(blind = true), pixelTold = false, pxMoved = false),
        )
        val out = ScreenController.swipeScreensResult(
            1, 900, 1, 200, 300L,
            requested = 1, moved = 0, stop = ScreenController.Companion.SwipeStop.UNREADABLE,
        )
        assertFalse(out.getBoolean("screen_changed"))
        assertEquals(listOf("screen_unreadable"), causes(out))
        val note = out.getString("screen_changed_note")
        assertTrue(note.contains("UNKNOWN"))
        assertTrue(note.contains("could read the screen"))
        assertFalse(out.has("list_end"))
        assertFalse(out.toString().contains("will do nothing"))
    }
}
