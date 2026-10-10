package net.kuafuai.andee.device

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three routes are a product decision wearing a mechanism's clothes, so
 * they get pinned down where nobody can quietly move them. Everything here is
 * decided from the id list alone — [PermissionsController.routeOf] says why
 * that is possible.
 */
class PermissionsRouteTest {

    @Test
    fun `own data is the ball to answer`() {
        assertEquals("solo", PermissionsController.routeOf(listOf("location")))
        assertEquals("solo", PermissionsController.routeOf(listOf("steps", "calendar", "calendar_write")))
    }

    @Test
    fun `other people data and acts that cannot be taken back are handed over`() {
        assertEquals("handoff", PermissionsController.routeOf(listOf("contacts")))
        assertEquals("handoff", PermissionsController.routeOf(listOf("sms", "sms_send")))
        assertEquals("handoff", PermissionsController.routeOf(listOf("call_phone", "call_log")))
    }

    @Test
    fun `a grant with no dialog is a settings errand whatever else it is asked with`() {
        assertEquals("settings", PermissionsController.routeOf(listOf("notifications")))
        assertEquals("settings", PermissionsController.routeOf(listOf("overlay")))
        // Not a severity ranking: a Settings page is the one route nothing
        // here can tap at all, so it outranks both of the others.
        assertEquals("settings", PermissionsController.routeOf(listOf("location", "notifications")))
        assertEquals("settings", PermissionsController.routeOf(listOf("contacts", "overlay")))
    }

    @Test
    fun `one non solo id hands over the whole request`() {
        // Solo and handoff dialogs stack on screen, so a solo id must not be
        // tapped through while a handoff id is queued behind it.
        assertEquals("handoff", PermissionsController.routeOf(listOf("location", "contacts")))
        assertEquals("handoff", PermissionsController.routeOf(listOf("calendar", "location", "sms")))
    }

    @Test
    fun `an id nobody classified falls on the safe side`() {
        // The guard for the day a permission joins the catalog and this file is
        // not read: being asked once too often beats being answered for.
        assertEquals("handoff", PermissionsController.routeOf(listOf("some_future_power")))
        assertEquals("handoff", PermissionsController.routeOf(listOf("location", "some_future_power")))
    }
}
