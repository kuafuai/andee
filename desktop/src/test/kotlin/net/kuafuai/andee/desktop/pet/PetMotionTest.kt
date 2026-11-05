package net.kuafuai.andee.desktop.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desk pet's body, stepped by hand.
 *
 * These are not idle unit tests: every one of them is a way the pet can be visibly wrong on the
 * desktop, and each is invisible in a screenshot — a body that sinks through the floor while
 * resting, one that never settles, one thrown off the screen, one that cannot reach the second
 * display.
 */
class PetMotionTest {

    private val area = PetArea(left = 0f, top = 25f, right = 1728f, bottom = 1117f)
    private val space = PetSpace.of(area)
    private val dt = 1f / 60f

    /** Two screens side by side with different heights — the layout that made the old single-rectangle model clamp the pet. */
    private val twoScreens = PetSpace(
        listOf(
            PetArea(left = 0f, top = 25f, right = 1728f, bottom = 1030f),
            PetArea(left = 1728f, top = 0f, right = 3648f, bottom = 1080f),
        ),
    )

    private fun settle(m: PetMotion, space: PetSpace = this.space, frames: Int = 600) {
        repeat(frames) { m.step(dt, space) }
    }

    @Test
    fun aHopComesBackToWhereSheStands() {
        // She stands where she was put, not on the bottom of the screen, so a hop has to land back
        // on her perch rather than on the floor.
        val m = PetMotion(x = 800f, y = 400f)
        m.pat()
        settle(m)
        assertTrue("should be resting, was $m", m.resting)
        assertEquals(400f, m.y, 1f)
        assertEquals(0f, m.vy, 0.01f)
    }

    @Test
    fun aDropBouncesThenRests() {
        val m = PetMotion(x = 800f, y = area.bottom)
        m.release(0f, -1500f)         // thrown up hard
        var bounces = 0
        var wasFalling = false
        repeat(600) {
            m.step(dt, space)
            if (m.vy > 0) wasFalling = true
            if (wasFalling && m.vy < 0) { bounces++; wasFalling = false }
        }
        assertTrue("expected at least one bounce, got $bounces", bounces >= 1)
        assertTrue("should come to rest, was $m", m.resting)
    }

    @Test
    fun aRestingBodyDoesNotMoveOrCostAFrame() {
        val m = PetMotion(x = 800f, y = area.bottom)
        settle(m)
        assertTrue(m.resting)
        val y = m.y
        assertTrue("a resting body must not ask for frames", !m.step(dt, space))
        assertEquals(y, m.y, 0f)
    }

    @Test
    fun aThrowTravelsAndBouncesOffTheWall() {
        val m = PetMotion(x = 200f, y = area.bottom)
        m.release(-2500f, 0f)
        var turned = false
        val startX = m.x
        repeat(240) {
            m.step(dt, space)
            if (m.vx > 0f) turned = true
            assertTrue("escaped the desk at x=${m.x}", m.x >= space.left - 0.01f && m.x <= space.right + 0.01f)
        }
        assertTrue("should have bounced off the left wall", turned)
        assertTrue("should have moved right of where it started", m.x > startX)
    }

    @Test
    fun nothingEverLeavesTheArea() {
        val m = PetMotion(x = 900f, y = 300f)
        val rnd = java.util.Random(7)
        repeat(60) { round ->
            m.release((rnd.nextFloat() - 0.5f) * 8000f, (rnd.nextFloat() - 0.5f) * 8000f)
            repeat(90) {
                m.step(dt, space)
                assertTrue("round $round: x=${m.x} y=${m.y}", m.x in space.left - 0.01f..space.right + 0.01f)
                assertTrue("round $round: y=${m.y}", m.y in space.top - 0.01f..space.floorAt(m.x) + 0.01f)
            }
        }
    }

    @Test
    fun draggingIsClampedInsideTheArea() {
        val m = PetMotion(x = 900f, y = area.bottom)
        m.grab()
        m.dragTo(-5000f, 9999f, space)
        assertEquals(space.left, m.x, 0.01f)
        assertEquals(space.floorAt(m.x), m.y, 0.01f)
        // While held, gravity is off: the body must not creep away from the pointer.
        repeat(10) { m.step(dt, space) }
        assertEquals(space.left, m.x, 0.01f)
    }

    @Test
    fun aPatOnlyHopsWhenItIsStanding() {
        val m = PetMotion(x = 900f, y = area.bottom)
        settle(m)
        assertTrue(m.resting)
        m.pat()
        assertTrue("a pat lifts it off the floor", !m.resting && m.vy < 0f)
        settle(m, space, 300)
        assertTrue(m.resting)
        assertEquals(space.floorAt(m.x), m.y, 0.01f)
    }

    @Test
    fun aHeldPetDoesNotFall() {
        val m = PetMotion(x = 900f, y = 200f)
        m.grab()
        repeat(30) { m.step(dt, space) }
        assertEquals(200f, m.y, 0.01f)
    }

    @Test
    fun thePetCanBeDraggedOntoTheSecondScreen() {
        val m = PetMotion(x = 1600f, y = 1030f)
        m.grab()
        m.dragTo(2400f, 700f, twoScreens)
        assertEquals("must not be clamped to the first screen", 2400f, m.x, 0.01f)
        assertEquals(700f, m.y, 0.01f)
        // And it is put down there, not dumped onto that screen's floor: placing is not throwing.
        m.release(0f, 0f)
        settle(m, twoScreens)
        assertTrue("should stay on the right-hand screen, x=${m.x}", m.x > 1728f)
        assertEquals(700f, m.y, 1f)
    }

    @Test
    fun aThrowCrossesTheGapBetweenScreensAndLandsOnTheFarOne() {
        val m = PetMotion(x = 1500f, y = 1030f)
        m.release(2000f, -900f)
        settle(m, twoScreens)
        assertTrue("should end up on the second screen, x=${m.x}", m.x > 1728f)
        assertEquals("the second screen's floor, not the first one's", 1080f, m.y, 0.01f)
    }

    @Test
    fun aPatLandsWithoutBouncing() {
        // The flicker: a pat's rebound crossed the floor three times in half a second, and each
        // crossing swapped the drawing. A hop is a hop — it comes down and stays down.
        val m = PetMotion(x = 900f, y = area.bottom)
        settle(m)
        m.pat()
        var touched = false
        var rebounds = 0
        repeat(240) {
            val wasAbove = m.y < area.bottom - 0.01f
            m.step(dt, space)
            if (wasAbove && m.y >= area.bottom - 0.01f) touched = true
            if (touched && m.vy < 0f) rebounds++
        }
        assertEquals("a pat must not rebound", 0, rebounds)
        assertTrue("and must come to rest", m.resting)
    }

    @Test
    fun onlyAThrowCountsAsLaunched() {
        val m = PetMotion(x = 900f, y = area.bottom)
        settle(m)
        m.pat()
        assertFalse("a hop is not a throw", m.launched)
        settle(m)
        m.release(0f, -200f)
        assertFalse("a small drop is not a throw", m.launched)
        settle(m)
        m.release(1800f, -400f)
        assertTrue(m.launched)
        settle(m)
        assertFalse("landing clears it", m.launched)
    }

    @Test
    fun aPetPutDownInMidAirStaysThere() {
        // "She must stand on the bottom of the screen" is a rule nobody asked for: a desktop pet is
        // put down where you let go.
        val m = PetMotion(x = 500f, y = 400f)
        m.grab()
        m.dragTo(520f, 420f, space)
        m.release(0f, 0f)
        repeat(300) { m.step(dt, space) }
        assertEquals("she must not fall to the floor", 420f, m.y, 1f)
        assertTrue("and the loop must be able to sleep", m.isSettled(space))
    }

    @Test
    fun aThrowStillFallsToTheFloor() {
        val m = PetMotion(x = 500f, y = 400f)
        m.grab(); m.dragTo(500f, 400f, space); m.release(0f, 900f)
        repeat(400) { m.step(dt, space) }
        assertEquals("a throw is not a placement", space.floorAt(500f), m.y, 1f)
    }

    @Test
    fun aBouncingThrowStaysLaunchedUntilItRests() {
        // The window draws the tumbling pose while `launched` is set. It must survive the bounces and
        // only clear once — otherwise the drawing flips on every bounce, which is what flashed at the
        // end of a fall.
        val m = PetMotion(x = 900f, y = 900f)
        m.release(0f, -1400f)
        var bounced = false
        repeat(300) {
            val before = m.vy
            m.step(dt, space)
            if (before > 200f && m.vy < 0f) bounced = true
            if (bounced && !m.resting) assertTrue("must stay launched between bounces", m.launched)
        }
        assertTrue("the throw should have bounced", bounced)
        assertTrue(m.resting)
        assertFalse("and clear it once at rest", m.launched)
    }

    @Test
    fun aLandedBodyStopsSquashingAndSettles() {
        // What "the end of the fall looks stuck" was: a resting body re-landed every frame, re-armed
        // its undocumented 2% squash, and the frame loop never slept.
        val m = PetMotion(x = 900f, y = 900f)
        m.release(0f, 600f)
        settle(m, space, 240)
        assertTrue(m.resting)
        assertEquals("squash must decay to zero", 0f, m.squash, 0.0001f)
        assertTrue("and the loop must be allowed to sleep", m.isSettled(space))
    }

    @Test
    fun aRestoredPositionIsWhereSheStands() {
        // The saved position is a placement, like a drag: she is restored onto that height and
        // stays there. (It used to be treated as a body left in the air, which fell to the floor.)
        val m = PetMotion(x = 1700f, y = 400f)
        settle(m)
        assertTrue(m.resting)
        assertEquals(400f, m.y, 1f)
        assertTrue(m.isSettled(space))
    }

    @Test
    fun aPointInTheGapUsesTheNearestFloor() {
        // A gap between two screens: nothing there to stand on, so the nearest screen decides.
        val gapped = PetSpace(
            listOf(
                PetArea(0f, 25f, 1000f, 900f),
                PetArea(1200f, 25f, 2000f, 900f),
            ),
        )
        assertEquals(900f, gapped.floorAt(1100f), 0.01f)
        assertEquals(PetArea(0f, 25f, 1000f, 900f), gapped.areaAt(1050f))
        assertEquals(PetArea(1200f, 25f, 2000f, 900f), gapped.areaAt(1150f))
    }
}
