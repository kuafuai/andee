package net.kuafuai.andee.desktop

import net.kuafuai.andee.ui.ball.Action
import net.kuafuai.andee.ui.ball.EmotionBallRenderer
import net.kuafuai.andee.ui.ball.Mood
import net.kuafuai.andee.ui.ball.gl.GLES20
import net.kuafuai.andee.util.Log
import org.lwjgl.BufferUtils
import org.lwjgl.PointerBuffer
import org.lwjgl.opengl.ARBFramebufferObject as FBO
import org.lwjgl.opengl.CGL
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * The phone's ball renderer, unchanged, on an offscreen OpenGL context of its own.
 *
 * A bare CGL context rather than a GLFW window, and that is not taste: GLFW on
 * macOS insists on the process's first thread, which Compose/AWT already owns —
 * the first version hung there forever, before drawing a frame. CGL needs no
 * window and no particular thread. It is a *legacy* profile (GL 2.1, GLSL 1.20)
 * because the shaders are GLSL ES 1.00, which [DesktopGl.adaptShader] can turn
 * into 1.20 by dropping `precision`; a core profile would mean rewriting every
 * `attribute`/`varying`.
 *
 * The renderer draws into an FBO at ~60 fps and each frame is read back into
 * [frame] as premultiplied ARGB, bottom row flipped to the top — what the window
 * paints. GL calls stay on this thread; the knobs are queued to it, so the
 * renderer's own `@Volatile` contract is kept exactly as on the phone.
 */
class BallRenderer(val width: Int, val height: Int, calmFrameNs: Long = CALM_FRAME_NS) {

    /**
     * `-Dandee.ballCalmMs=<ms>` overrides every ball's resting rate, whatever the
     * caller asked for. Only for measuring: a ball that is forced to 30 fps is a
     * constant stream of whole-window repaints, which is the only way to price one
     * repaint without being able to drive the UI by hand.
     */
    private val calmFrameNs: Long = System.getProperty("andee.ballCalmMs")
        ?.toLongOrNull()?.let { it * 1_000_000 } ?: calmFrameNs

    private val renderer = EmotionBallRenderer()
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile private var stopRequested = false

    /** Until this moment the ball is doing something, so it runs at full rate. */
    @Volatile private var activeUntilNs = 0L

    /** The latest finished frame: premultiplied BGRA bytes, top row first. Null until the first one. */
    @Volatile var frame: ByteArray? = null
        private set

    /** Why the ball cannot be drawn on this Mac, if it cannot; the window falls back to no ball. */
    @Volatile var failure: String? = null
        private set

    /** Called on the render thread after each new [frame]. */
    @Volatile var onFrame: () -> Unit = {}

    private val thread = thread(name = "BallGL", isDaemon = true) {
        runCatching { run() }.onFailure {
            if (stopRequested) return@onFailure
            failure = it.message ?: it.toString()
            Log.e(TAG, "the ball cannot be drawn", it)
        }
    }

    /**
     * Wait up to [ns], or less: a queued command (a tap, a new face) ends the wait so the very next frame
     * carries it — at the resting rate the loop used to sleep through up to 250 ms of a click. False when
     * [stop] asked us to leave.
     */
    private fun nap(ns: Long): Boolean {
        val end = System.nanoTime() + ns
        while (true) {
            if (stopRequested) return false
            if (!commands.isEmpty()) return true
            val left = end - System.nanoTime()
            if (left <= 0) return true
            java.util.concurrent.locks.LockSupport.parkNanos(left)
        }
    }

    private fun run() {
        val ctx = createContext()
        try {
            GL.createCapabilities()
            GLES20.backend = DesktopGl
            val fbo = FBO.glGenFramebuffers()
            FBO.glBindFramebuffer(FBO.GL_FRAMEBUFFER, fbo)
            val color = FBO.glGenRenderbuffers()
            FBO.glBindRenderbuffer(FBO.GL_RENDERBUFFER, color)
            FBO.glRenderbufferStorage(FBO.GL_RENDERBUFFER, GL11.GL_RGBA8, width, height)
            FBO.glFramebufferRenderbuffer(FBO.GL_FRAMEBUFFER, FBO.GL_COLOR_ATTACHMENT0, FBO.GL_RENDERBUFFER, color)
            val depth = FBO.glGenRenderbuffers()
            FBO.glBindRenderbuffer(FBO.GL_RENDERBUFFER, depth)
            FBO.glRenderbufferStorage(FBO.GL_RENDERBUFFER, org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT16, width, height)
            FBO.glFramebufferRenderbuffer(FBO.GL_FRAMEBUFFER, FBO.GL_DEPTH_ATTACHMENT, FBO.GL_RENDERBUFFER, depth)
            val status = FBO.glCheckFramebufferStatus(FBO.GL_FRAMEBUFFER)
            check(status == FBO.GL_FRAMEBUFFER_COMPLETE) { "framebuffer incomplete: 0x${status.toString(16)}" }

            // ES 2.0 always sizes points from gl_PointSize and always fills gl_PointCoord;
            // a legacy desktop context does neither until asked, and the blush and the
            // dust would draw as one-pixel squares.
            GL11.glEnable(org.lwjgl.opengl.GL20.GL_VERTEX_PROGRAM_POINT_SIZE)
            GL11.glEnable(org.lwjgl.opengl.GL20.GL_POINT_SPRITE)
            renderer.onSurfaceCreated()
            renderer.onSurfaceChanged(width, height)
            val buf = BufferUtils.createByteBuffer(width * height * 4)
            while (!stopRequested) {
                val t0 = System.nanoTime()
                while (true) commands.poll()?.invoke() ?: break
                val delay = frameDelayNs()
                // `frame == null` is the very first pass: there is nothing to hold yet,
                // so a ball that idles by holding must still draw once or it is never
                // there at all.
                if (delay == HOLD && frame != null) {
                    // Holding the last frame: draw nothing, but wake often enough that
                    // a change of face is picked up in POLL_MS, not a calm period later.
                    if (!nap(POLL_NS)) break
                    continue
                }
                val g0 = System.nanoTime()
                renderer.onDrawFrame()
                val g1 = System.nanoTime()
                buf.clear()
                GL11.glReadPixels(0, 0, width, height, org.lwjgl.opengl.GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf)
                val g2 = System.nanoTime()
                frame = flipped(buf)
                if (TRACE) GlTrace.record(g1 - g0, g2 - g1, System.nanoTime() - g2)
                onFrame()
                // Nanoseconds, not truncated milliseconds: that rounding plus the sleep's own overshoot
                // measured 25 fps for a requested 30.
                val left = delay - (System.nanoTime() - t0)
                if (left > 0 && !nap(left)) break
            }
            FBO.glDeleteRenderbuffers(depth)
            FBO.glDeleteRenderbuffers(color)
            FBO.glDeleteFramebuffers(fbo)
        } finally {
            CGL.CGLSetCurrentContext(0L)
            CGL.CGLDestroyContext(ctx)
        }
    }

    private fun createContext(): Long {
        val attrs = intArrayOf(
            CGL.kCGLPFAOpenGLProfile, CGL.kCGLOGLPVersion_Legacy,
            CGL.kCGLPFAColorSize, 24,
            CGL.kCGLPFAAlphaSize, 8,
            CGL.kCGLPFADepthSize, 16,
            CGL.kCGLPFAAccelerated,
            0,
        )
        val pix = PointerBuffer.allocateDirect(1)
        val n = IntArray(1)
        var err = CGL.CGLChoosePixelFormat(attrs, pix, n)
        check(err == 0 && pix[0] != 0L) { "CGLChoosePixelFormat failed ($err)" }
        val ctx = PointerBuffer.allocateDirect(1)
        err = CGL.CGLCreateContext(pix[0], 0L, ctx)
        CGL.CGLDestroyPixelFormat(pix[0])
        check(err == 0 && ctx[0] != 0L) { "CGLCreateContext failed ($err)" }
        check(CGL.CGLSetCurrentContext(ctx[0]) == 0) { "CGLSetCurrentContext failed" }
        return ctx[0]
    }

    /**
     * GL's bottom-up rows → Skia's top-down. Already premultiplied: blending
     * onto a cleared-transparent target is what the phone's non-opaque
     * TextureView composites as premultiplied too, so both apps read it alike.
     */
    private fun flipped(buf: java.nio.ByteBuffer): ByteArray {
        val row = width * 4
        val out = ByteArray(row * height)
        for (y in 0 until height) {
            buf.position((height - 1 - y) * row)
            buf.get(out, y * row, row)
        }
        buf.position(0)
        return out
    }

    fun setState(mood: Mood, listening: Boolean, emotion: Mood?) = post {
        renderer.mood = mood
        renderer.listening = listening
        renderer.emotion = emotion
        markActive()
    }

    // Also a reason to draw: the face can change while the mood stays calm, and a
    // ball that holds its last frame would keep the old face.
    fun setEmotion(m: Mood?) = post { renderer.emotion = m; markActive() }
    fun setWorking(w: Boolean) = post { renderer.working = w; if (w) markActive() }
    fun pulseTool() = post { renderer.pulseTool(); markActive() }
    fun signalOutcome(ok: Boolean) = post { renderer.signalOutcome(ok); markActive() }
    fun trigger(action: Action) = post { renderer.request(action); markActive() }

    private fun post(f: () -> Unit) { commands.offer(f); java.util.concurrent.locks.LockSupport.unpark(thread) }

    /**
     * Full rate while there is something to watch — listening, speaking, thinking,
     * or an animation just started — and a slow breath when there is not.
     *
     * This is the single biggest lever on a Mac that renders in software: there,
     * every frame the ball publishes costs a fresh raster and blit of the whole
     * window it lives in, whether or not anything else changed. Measured idle,
     * a 30 fps ball kept the UI thread busy ~15% of the time doing nothing but
     * redrawing an unchanged window, and every click queued behind it. The
     * breathing reads the same at 4 fps as at 30.
     */
    private fun frameDelayNs(): Long = when {
        System.nanoTime() < activeUntilNs -> FRAME_NS
        renderer.mood == Mood.CALM && !renderer.working && !renderer.listening -> calmFrameNs
        else -> FRAME_NS
    }

    /** Hold full rate for a beat, so a pop or a nod finishes before it slows. */
    private fun markActive() { activeUntilNs = System.nanoTime() + ACTIVE_NS }

    /**
     * Ask the render thread to finish and return at once. It used to `join(2000)` on the caller, which is
     * the UI thread (this runs from a view's onDispose): a thread parked in its frame sleep, or in a GL call,
     * then cost the whole 2 s on every page switch — and when the join timed out the thread simply stayed,
     * with its GL context and framebuffer, one more on every switch. The thread now notices on its own
     * (an unpark ends the wait), frees its GL objects and exits.
     */
    fun stop() {
        stopRequested = true
        onFrame = {}
        java.util.concurrent.locks.LockSupport.unpark(thread)
    }

    companion object {
        private val TRACE = System.getProperty("andee.ballTrace") != null
        const val TAG = "Ball"
        /**
         * 30 fps. The ball's motion is slow springs and breathing, which read the
         * same at 30 as at 60; the frame is then read back and handed to another
         * window, so every frame costs twice, and halving them is most of the CPU.
         */
        const val FRAME_NS = 33_333_333L

        /** 4 fps: the rate of a ball that is only breathing. */
        const val CALM_FRAME_NS = 250_000_000L

        /** How long a triggered animation keeps the full rate. */
        const val ACTIVE_NS = 1_200_000_000L

        /** Passed as [calmFrameNs] to stop drawing entirely at rest; see [frameDelayNs]. */
        const val HOLD = 0L

        /** While holding, how often to wake and check whether the face changed. */
        const val POLL_NS = 100_000_000L
    }
}
