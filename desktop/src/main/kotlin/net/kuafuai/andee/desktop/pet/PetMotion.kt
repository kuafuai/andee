package net.kuafuai.andee.desktop.pet

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/** A rectangle in dp: the usable part of one display (its work area, not the whole display). */
data class PetArea(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * The screens the pet may use — a set of floors, not one rectangle.
 *
 * The bug this exists for: with a single screen, "the screen" and "the desk" were the same
 * rectangle, so a second display lay outside the pet's world and dragging it over was clamped
 * back. A union *bounding box* is not the answer either — two screens of different heights make
 * an L, and the box's missing corner is a hole the pet would stand in, in mid-air. So the floor
 * is asked for at the pet's own x, and only the outer edges of the whole arrangement are walls.
 *
 * The areas are the *feet-allowed* rectangles: the caller narrows each one by half the pet's
 * width, so the sprite never ends up half past a screen edge.
 */
class PetSpace(areas: List<PetArea>) {

    val areas: List<PetArea> = areas.ifEmpty { listOf(PetArea(0f, 0f, 1280f, 800f)) }

    val left: Float = this.areas.minOf { it.left }
    val right: Float = this.areas.maxOf { it.right }
    val top: Float = this.areas.minOf { it.top }

    /** The floor under [x]: that screen's bottom, or the nearest screen's when the point is in a gap. */
    fun floorAt(x: Float): Float = areaAt(x).bottom

    /** Which screen [x] is on, or the nearest one when it is in a gap between screens. */
    fun areaAt(x: Float): PetArea = areas.firstOrNull { x >= it.left && x <= it.right }
        ?: areas.minByOrNull { distanceX(it, x) }!!

    private fun distanceX(a: PetArea, x: Float): Float =
        if (x < a.left) a.left - x else if (x > a.right) x - a.right else 0f

    companion object {
        /** One screen: what a test and a single-display Mac use. */
        fun of(area: PetArea): PetSpace = PetSpace(listOf(area))
    }
}

/**
 * The pet's body: one point (the centre of its feet), a velocity, and a floor.
 *
 * Why this is its own file and not code inside the window: a desktop pet is mostly a physics
 * problem wearing a picture, and physics is the one part of it that can be wrong in a way nobody
 * sees until it matters — a body that sinks through the floor while resting, or never comes to
 * rest and burns a core all afternoon. Kept free of Compose and AWT, it is stepped by a test as
 * easily as by a frame.
 *
 * The feel, and where the numbers come from:
 *  * gravity 2600 dp/s² — about twice real gravity; at ~200 dp tall, real gravity reads as
 *    slow-motion drifting, and 4× reads as a dropped prop.
 *  * restitution 0.55 on the floor — two or three visible bounces, then rest. A ball that bounces
 *    forever is a toy; this one is supposed to be alive, not a screensaver.
 *  * walls keep 0.65 of the sideways speed, because a thrown pet that stops dead at the screen
 *    edge looks like it hit glass.
 *  * it sleeps below 220 dp/s vertical, so it stops rather than vibrating in place.
 */
class PetMotion(x: Float, y: Float) {

    /** Centre of the feet, in dp, in the same coordinate space as [PetSpace]. */
    var x: Float = x
    var y: Float = y

    var vx: Float = 0f
        private set
    var vy: Float = 0f
        private set

    /** True when it has stopped: no gravity is applied, and the window is not moved. */
    var resting: Boolean = true
        private set

    /** 0..1 impact amount, decaying — the renderer turns it into a squash. */
    var squash: Float = 0f
        private set

    /** Degrees, leaning the way it is travelling while airborne. */
    var tilt: Float = 0f
        private set

    /** Being held by the pointer: gravity is off and the caller owns the position. */
    var held: Boolean = false
        private set

    /**
     * Thrown hard enough to be *flying*, as opposed to hopping: only this shows the airborne
     * drawing. Its absence is the fix for a flickering pet — a pat's little bounce crossed the
     * floor three times in half a second, and swapping to the tumbling sprite on each crossing
     * made the whole character flash between two poses.
     */
    var launched: Boolean = false

    /**
     * The height she rests at, and whether that height is honoured.
     *
     * A desktop pet is put down where you let go of it, not dropped to the bottom of the screen —
     * "she has to stand on the floor" is a rule nobody asked for. So the floor she falls to is the
     * height she was placed at ([perchY]) while [perched]; a throw clears that and puts the real
     * screen floor back, and landing there re-perches her.
     */
    var perchY: Float = y
    var perched: Boolean = true
        private set

    /** Set by [pat]: land without bouncing. A hop that rebounds three times reads as shivering. */
    private var gentle: Boolean = false

    fun grab() {
        held = true
        resting = false
        launched = false
        gentle = false
        vx = 0f
        vy = 0f
    }

    /**
     * Follow the pointer. Bounded by the arrangement's outer edges, not by one screen: dragging
     * the pet onto a second display is the whole point, and a gap between screens is allowed to
     * pass through — it lands on the nearest floor when let go.
     */
    fun dragTo(nx: Float, ny: Float, space: PetSpace) {
        x = nx.coerceIn(space.left, space.right)
        y = ny.coerceIn(space.top, space.floorAt(x))
        // Whatever height you put her down at is the height she stays at.
        perchY = y
        perched = true
        tilt = 0f
    }

    /** Let go with the pointer's own speed, so a flick throws it. */
    fun release(throwVx: Float, throwVy: Float) {
        held = false
        gentle = false
        launched = abs(throwVx) > LAUNCH_SPEED || abs(throwVy) > LAUNCH_SPEED
        vx = throwVx.coerceIn(-MAX_SPEED, MAX_SPEED)
        vy = throwVy.coerceIn(-MAX_SPEED, MAX_SPEED)
        resting = false
        // A flick throws her off the perch; a gentle let-go leaves her exactly where she is.
        if (launched) perched = false else { perchY = y; perched = true }
    }

    /** An instant squash, so the press has feedback on the very frame it happens. */
    fun jolt(amount: Float) {
        squash = squash.coerceAtLeast(amount.coerceIn(0f, 1f))
    }

    /**
     * True when there is genuinely nothing to do: on a floor, not squashing, not held. The
     * window's frame loop sleeps on this rather than on [resting] alone, because a body can be
     * *marked* resting while hanging in mid-air — restored from a saved position, or after the
     * screen layout changed under it. Sleeping on that would leave the pet floating.
     */
    fun isSettled(space: PetSpace): Boolean = resting && squash <= 0f && y >= floorOf(space) - 0.5f

    /** Where she would come to rest: her perch, or the real floor when she is off it. */
    private fun floorOf(space: PetSpace): Float {
        val real = space.floorAt(x)
        return if (perched) kotlin.math.min(perchY, real) else real
    }

    /** A pat: a small hop when it is standing still. In the air, nothing — it is already busy. */
    fun pat() {
        if (held) return
        resting = false
        launched = false
        gentle = true
        vy = -HOP
    }

    /**
     * Advance by [dt] seconds. Returns true when the body (or its squash) changed, which is the
     * window's cue to move and repaint — false means the frame can be skipped.
     */
    fun step(dt: Float, space: PetSpace): Boolean {
        if (dt <= 0f) return false
        val wasSquashed = squash > 0f
        squash = max(0f, squash - dt * SQUASH_DECAY)
        // A body marked resting while above a floor is not resting — it is a saved position, a
        // screen that moved, or a pet that was put down in the air. Heal the flag here so the
        // caller cannot forget to (the first version left the pet hanging in mid-air).
        if (resting && !held && y < floorOf(space) - 0.5f) {
            resting = false
            vy = 0f
        }
        if (held || (resting && !wasSquashed)) {
            // Resting: only the squash is still animating, the body is not moving.
            return wasSquashed
        }
        if (!held) {
            // Capture "was it in the air" *before* integrating: after the integration the body is
            // already at the floor, and every landing would look like it had been resting there.
            val wasAirborne = y < floorOf(space) - 0.5f
            vy += GRAVITY * dt
            vx *= exp(-AIR_DRAG * dt)
            x += vx * dt
            y += vy * dt

            if (x < space.left) {
                x = space.left
                vx = -vx * WALL
            } else if (x > space.right) {
                x = space.right
                vx = -vx * WALL
            }
            // The floor is the screen under the pet, not the lowest screen on the desk: two
            // displays with different heights are two different ground levels.
            val floor = floorOf(space)
            var onGround = false
            if (y >= floor) {
                y = floor
                onGround = true
                if (vy > 0f) {
                    // Only a body that actually arrived squashes and bounces. A resting one re-armed
                    // its 2% squash every frame (gravity, clamp, impact), so it sat slightly squashed
                    // for ever and the frame loop never slept — the "stuck frame" at the end of a fall.
                    if (wasAirborne) {
                        squash = max(squash, (vy / 2400f).coerceIn(0f, 1f))
                        if (gentle || vy < SLEEP_SPEED) {
                            // A pat lands; a throw bounces. The flag is what tells them apart.
                            vy = 0f
                            gentle = false
                            launched = false
                        } else {
                            vy = -vy * RESTITUTION
                        }
                    } else {
                        vy = 0f
                        gentle = false
                        launched = false
                    }
                }
            }
            // On the floor a throw skids a little instead of stopping dead: the vertical speed at
            // contact says nothing about the horizontal one, and stopping on it read as the pet
            // hitting an invisible wall.
            if (onGround && vy == 0f) {
                vx *= exp(-GROUND_FRICTION * dt)
                if (abs(vx) < SLIDE_STOP) {
                    vx = 0f
                    resting = true
                    launched = false
                    // Landed: that height becomes the new perch.
                    perchY = y
                    perched = true
                }
            }
            // Never above the top edge: a throw that overshoots would park it under the menu bar.
            if (y < space.top) {
                y = space.top
                vy = abs(vy) * WALL
            }
        }
        tilt = if (resting) 0f else (vx * TILT_PER_SPEED).coerceIn(-MAX_TILT, MAX_TILT)
        return true
    }

    companion object {
        const val GRAVITY = 2600f
        const val RESTITUTION = 0.55f
        const val WALL = 0.65f
        const val AIR_DRAG = 0.8f
        const val GROUND_FRICTION = 3.0f
        const val SLIDE_STOP = 60f
        const val SLEEP_SPEED = 220f
        const val HOP = 820f

        /** Below this a release is a drop, not a throw: no flying pose, no bouncing drama. */
        const val LAUNCH_SPEED = 500f
        const val MAX_SPEED = 4200f
        const val SQUASH_DECAY = 5.5f
        const val TILT_PER_SPEED = 0.012f
        const val MAX_TILT = 14f
    }
}
