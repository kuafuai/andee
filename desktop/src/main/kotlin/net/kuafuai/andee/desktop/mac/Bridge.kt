package net.kuafuai.andee.desktop.mac

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The native bridge (desktop/native/AndeeMac.swift), loaded through JNA.
 * Strings that come back are malloc'd JSON; [Bridge] frees them.
 */
object Bridge {

    /** Called on a background queue when the notification store changes. */
    fun interface NotifCallback : com.sun.jna.Callback { fun invoke() }

    private interface Lib : Library {
        fun andee_free(p: Pointer?)
        fun andee_ax_trusted(prompt: Int): Int
        fun andee_screen_capture_allowed(request: Int): Int
        fun andee_main_display(): Pointer
        fun andee_frontmost(): Pointer
        fun andee_running_apps(): Pointer
        fun andee_ax_tree(maxNodes: Int): Pointer
        fun andee_ax_press_at(x: Double, y: Double): Int
        fun andee_ax_at(x: Double, y: Double): Pointer
        fun andee_activate_at(x: Double, y: Double): Int
        fun andee_activate_target(): Int
        fun andee_ax_set_focused_value(text: String): Int
        fun andee_ax_focused_value(): Pointer
        fun andee_click(x: Double, y: Double, button: Int, count: Int, holdMs: Int)
        fun andee_drag(x1: Double, y1: Double, x2: Double, y2: Double, ms: Int)
        fun andee_scroll(x: Double, y: Double, dx: Int, dy: Int)
        fun andee_type_text(text: String)
        fun andee_set_pasteboard(text: String): Int
        fun andee_key(keyCode: Int, flags: Int)
        fun andee_screenshot(path: String, maxSide: Int): Int
        fun andee_open(target: String): Int
        fun andee_float_window(title: String): Int
        fun andee_set_own_alpha(alpha: Double): Int
        fun andee_notif_latest_id(): Long
        fun andee_own_window_at(x: Double, y: Double): Int
        fun andee_camera_shot(path: String, front: Int, maxSide: Int): Int
        fun andee_brightness_get(): Double
        fun andee_brightness_set(b: Double): Int
        fun andee_location(): Pointer
        fun andee_notif_since(afterId: Long): Pointer
        fun andee_notif_watch(cb: NotifCallback): Int
    }

    private val lib: Lib? by lazy {
        val f = locate()
        if (f == null) {
            Log.e("Bridge", "libandeemac.dylib not found; screen tools are unavailable")
            null
        } else {
            runCatching { Native.load(f.path, Lib::class.java) }
                .onFailure { Log.e("Bridge", "could not load ${f.path}", it) }.getOrNull()
        }
    }

    val available: Boolean get() = lib != null

    private fun locate(): File? {
        System.getProperty("compose.application.resources.dir")?.let { File(it, "libandeemac.dylib") }
            ?.takeIf { it.exists() }?.let { return it }
        val cached = File(System.getProperty("user.home"), "Library/Andee-shell/libandeemac.dylib")
        val res = Bridge::class.java.getResourceAsStream("/libandeemac.dylib") ?: return cached.takeIf { it.exists() }
        res.use { input -> cached.parentFile.mkdirs(); cached.outputStream().use { input.copyTo(it) } }
        return cached
    }

    private fun need(): Lib = lib ?: throw IllegalStateException(
        "The Mac screen bridge is not loaded on this install, so screen tools cannot run. Say so to the user.",
    )

    private fun str(p: Pointer): String = try { p.getString(0, "UTF-8") } finally { need().andee_free(p) }

    fun axTrusted(prompt: Boolean = false) = lib?.andee_ax_trusted(if (prompt) 1 else 0) == 1
    fun screenCaptureAllowed(request: Boolean = false) = lib?.andee_screen_capture_allowed(if (request) 1 else 0) == 1
    fun display(): JSONObject = JSONObject(str(need().andee_main_display()))
    fun frontmost(): JSONObject = JSONObject(str(need().andee_frontmost()))
    fun runningApps(): JSONArray = JSONArray(str(need().andee_running_apps()))
    fun axTree(maxNodes: Int): JSONObject = JSONObject(str(need().andee_ax_tree(maxNodes)))
    fun axPressAt(x: Double, y: Double) = need().andee_ax_press_at(x, y) == 1
    /** Bring the app under (x, y) to the front before clicking there; true when it had to be moved. */
    fun activateAt(x: Double, y: Double) = lib?.andee_activate_at(x, y) == 1
    /** Before keystrokes: bring the driven app back if Andee itself is in front; true when it had to be moved. */
    fun activateTarget() = lib?.andee_activate_target() == 1

    /** What a click at (x, y) would reach: `{found, chain: [{role, label}, …]}`, the hit element first. */
    fun axAt(x: Double, y: Double): JSONObject = JSONObject(str(need().andee_ax_at(x, y)))
    fun setFocusedValue(text: String) = need().andee_ax_set_focused_value(text) == 1
    fun focusedValue(): JSONObject = JSONObject(str(need().andee_ax_focused_value()))
    fun click(x: Double, y: Double, right: Boolean = false, count: Int = 1, holdMs: Int = 40) =
        need().andee_click(x, y, if (right) 1 else 0, count, holdMs)
    fun drag(x1: Double, y1: Double, x2: Double, y2: Double, ms: Int) = need().andee_drag(x1, y1, x2, y2, ms)
    fun scroll(x: Double, y: Double, dx: Int, dy: Int) = need().andee_scroll(x, y, dx, dy)
    fun typeText(text: String) = need().andee_type_text(text)
    /** Text onto the general pasteboard, for a Cmd+V that the page sees as a real paste. */
    fun setPasteboard(text: String) = need().andee_set_pasteboard(text) == 1
    fun key(code: Int, flags: Int = 0) = need().andee_key(code, flags)
    fun screenshot(path: String, maxSide: Int) = need().andee_screenshot(path, maxSide) == 1
    fun open(target: String) = need().andee_open(target) == 1
    /** See andee_float_window: above every app, on every Space. False if the bridge or the window is missing. */
    fun floatWindow(title: String) = lib?.andee_float_window(title) == 1
    /** Every Andee window to [alpha] (0 = stepped aside for a click). */
    fun setOwnAlpha(alpha: Double) = lib?.andee_set_own_alpha(alpha) == 1

    /** True when one of Andee's visible windows covers (x, y); unknown counts as yes. */
    fun ownWindowAt(x: Double, y: Double) = lib?.andee_own_window_at(x, y) != 0

    /** Capture one camera frame to [path] as PNG; false when there is no camera or permission. */
    fun cameraShot(path: String, front: Boolean, maxSide: Int = 1280) = lib?.andee_camera_shot(path, if (front) 1 else 0, maxSide) == 1

    /** The main display's brightness, 0–1. */
    fun brightnessGet(): Double = lib?.andee_brightness_get() ?: -1.0
    fun brightnessSet(b: Double) = lib?.andee_brightness_set(b) == 1
    fun location(): JSONObject = JSONObject(str(need().andee_location()))

    /** Newest record id in the system notification store; -1 when it refuses us (Full Disk Access), -2 when there is no store where this macOS keeps it. */
    fun notifLatestId(): Long = lib?.andee_notif_latest_id() ?: -1
    /** Records after [afterId]: id, bundle, app, title, subtitle, body, when. */
    fun notifSince(afterId: Long): JSONObject = JSONObject(str(need().andee_notif_since(afterId)))
    /** Keep a strong reference to [cb]: JNA does not, and a collected callback is a crash. */
    fun notifWatch(cb: NotifCallback): Boolean = lib?.andee_notif_watch(cb) == 1

    /** macOS virtual key codes the tools use. */
    object Keys {
        const val RETURN = 36
        const val TAB = 48
        const val ESCAPE = 53
        const val DELETE = 51
        const val LEFT_BRACKET = 33
        const val A = 0
        const val V = 9
        const val SPACE = 49
        const val F3 = 160
        const val UP = 126
        const val DOWN = 125
        const val CMD = 1
        const val SHIFT = 2
        const val OPTION = 4
        const val CONTROL = 8
    }
}
