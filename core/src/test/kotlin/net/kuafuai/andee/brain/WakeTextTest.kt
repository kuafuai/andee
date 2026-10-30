package net.kuafuai.andee.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeTextTest {
    @Test fun carriesTheIdAndTheWhat() {
        val t = WakeText.due("t42", "check the build", false)
        assertTrue(t.contains("id=t42"))
        assertTrue(t.contains("check the build"))
        assertTrue(t.contains("todos(action=done, id=t42)"))
    }

    @Test fun anUntrustedTodoIsSaidToBeSomeoneElses() {
        assertTrue(WakeText.due("t1", "x", true).contains("somebody else sent"))
        assertTrue(WakeText.due("t1", "x", false).contains("you set yourself"))
    }

    @Test fun aCheckIsToldNotToWaitOnTheScreen() {
        assertTrue(WakeText.due("t1", "x", false).contains("Do not wait on the screen"))
    }

    @Test fun aRepeatingTodoIsToldNotToCloseIt() {
        val t = WakeText.due("t7", "evening check-in", false, repeats = true)
        assertTrue(t.contains("do NOT close it"))
        assertFalse(t.contains("close it with todos(action=done, id=t7)"))
        assertTrue(WakeText.todoId(t) == "t7")
    }
}
