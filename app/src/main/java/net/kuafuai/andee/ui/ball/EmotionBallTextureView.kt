package net.kuafuai.andee.ui.ball

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView

/**
 * TextureView-hosted EmotionBall.
 *
 * Unlike GLSurfaceView, TextureView composites through the normal View
 * hierarchy — so sibling Views (subtitle pill, settings gear, resize handle)
 * can be drawn on top of the ball without the z-order tricks that
 * `setZOrderOnTop(true)` on a SurfaceView forces. That's the only reason we
 * carry the extra EGL boilerplate here; the [EmotionBallRenderer] itself is
 * unchanged from the GLSurfaceView version.
 *
 * Manages a dedicated GL thread + EGL context/surface tied to the TextureView's
 * SurfaceTexture lifecycle. When the surface goes away (window removed) the
 * thread and EGL resources are cleaned up.
 */
class EmotionBallTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private val renderer = EmotionBallRenderer()

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: EGLSurface? = null
    private var androidSurface: Surface? = null

    @Volatile
    private var running = false

    init {
        surfaceTextureListener = this
        isOpaque = false  // required for alpha compositing with parent views
    }

    fun setMood(m: Mood) {
        renderer.mood = m
    }

    fun setListening(l: Boolean) {
        renderer.listening = l
    }

    /** A task is running, for its whole duration — see [EmotionBallRenderer.working]. */
    fun setWorking(w: Boolean) {
        renderer.working = w
    }

    /**
     * The perch geometry is applied — the window is (or has just settled) at
     * the ledge. This is what licenses the cling animation; see
     * [EmotionBallRenderer.atLedge] for why working alone is not enough.
     */
    fun setAtLedge(at: Boolean) {
        renderer.atLedge = at
    }

    /** A signboard is up and the ball is waiting for the user's choice. */
    fun setHolding(h: Boolean) {
        renderer.holding = h
    }

    /** It has dozed off — see [EmotionBallRenderer.dozing]. Safe from any thread. */
    fun isDozing(): Boolean = renderer.dozing

    /** When true, cycles through all moods every ~5.5 s. Handy for testing. */
    fun setDemoCycle(on: Boolean) {
        renderer.demoCycle = on
    }

    /** The agent just ran a tool against the device. */
    fun pulseTool() {
        renderer.pulseTool()
    }

    /** A task finished. */
    fun signalOutcome(ok: Boolean) {
        renderer.signalOutcome(ok)
    }

    /** One-shot body gesture, e.g. a nod when the mic opens. */
    fun trigger(action: Action) {
        renderer.request(action)
    }

    // ---- SurfaceTextureListener ----

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val t = HandlerThread("EmotionBallGL").apply { start() }
        glThread = t
        val h = Handler(t.looper)
        glHandler = h
        h.post {
            initEGL(surface)
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, width, height)
            running = true
            scheduleFrame(h)
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        glHandler?.post {
            renderer.onSurfaceChanged(null, width, height)
        }
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        running = false
        val h = glHandler
        val t = glThread
        glHandler = null
        glThread = null
        h?.post { destroyEGL() }
        t?.quitSafely()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    // ---- Frame loop ----

    private fun scheduleFrame(h: Handler) {
        if (!running) return
        h.post {
            if (!running) return@post
            val d = eglDisplay ?: return@post
            val s = eglSurface ?: return@post
            renderer.onDrawFrame(null)
            EGL14.eglSwapBuffers(d, s)
            h.postDelayed({ scheduleFrame(h) }, 16)   // ~60 fps
        }
    }

    // ---- EGL ----

    private fun initEGL(surfaceTexture: SurfaceTexture) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            ),
            0, configs, 0, 1, numConfigs, 0,
        )
        val config = configs[0]!!

        val ctx = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        val surface = Surface(surfaceTexture)
        val eSurface = EGL14.eglCreateWindowSurface(
            display, config, surface,
            intArrayOf(EGL14.EGL_NONE), 0,
        )
        EGL14.eglMakeCurrent(display, eSurface, eSurface, ctx)

        eglDisplay = display
        eglContext = ctx
        eglSurface = eSurface
        androidSurface = surface
    }

    private fun destroyEGL() {
        val d = eglDisplay ?: return
        EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        eglSurface?.let { EGL14.eglDestroySurface(d, it) }
        eglContext?.let { EGL14.eglDestroyContext(d, it) }
        EGL14.eglTerminate(d)
        androidSurface?.release()
        eglSurface = null
        eglContext = null
        eglDisplay = null
        androidSurface = null
    }
}
