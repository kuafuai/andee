package net.kuafuai.andee.compat

import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager

/**
 * Display geometry on the devices this app can be installed on.
 *
 * `WindowManager.currentWindowMetrics` / `maximumWindowMetrics` and
 * `WindowInsets.Type` arrived in API 30. Before that the same questions were
 * answered by `Display.getRealMetrics` plus the system-bar resource heights.
 * Every card, ball and tap marker asks these questions, so the branch lives in
 * one object rather than in fifteen `if (SDK_INT >= 30)` blocks that drift apart.
 *
 * This is deliberately a *geometry* shim, not an abstraction over the whole
 * window system: on API < 30 the assistant can still be installed and can still
 * talk, but the accessibility screenshot the screen tools are built on does not
 * exist. [net.kuafuai.andee.device.SelfCheck.platformVersion] is where that is
 * told to the user.
 */
object WindowMetricsCompat {

    /** System bars and cutout, ignoring visibility. */
    data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Bounds of the current window, which for these overlays is the display. */
    fun bounds(wm: WindowManager): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.currentWindowMetrics.bounds
        else legacyBounds(wm)

    /** Full display including bars and cutout — the space gesture coordinates use. */
    fun maxBounds(wm: WindowManager): Rect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) wm.maximumWindowMetrics.bounds
        else legacyBounds(wm)

    fun insets(wm: WindowManager): Insets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return Insets(i.left, i.top, i.right, i.bottom)
        }
        return legacyInsets(wm)
    }

    /** Width and height a centred card can actually occupy. */
    fun usableSize(wm: WindowManager): Pair<Int, Int> {
        val b = bounds(wm)
        val i = insets(wm)
        return (b.width() - i.left - i.right) to (b.height() - i.top - i.bottom)
    }

    @Suppress("DEPRECATION")
    private fun legacyBounds(wm: WindowManager): Rect {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    /**
     * `getMetrics` describes the area an app window is given; `getRealMetrics`
     * describes the whole display. Their difference is **both** system bars,
     * not just the navigation one: the app area excludes the status bar as
     * well, so `real.height - app.height` already carries it. Subtracting the
     * status bar a second time here put the bottom inset a status bar too low
     * — the ball rested that far above the navigation bar, and every card was
     * that much shorter.
     *
     * Which side the navigation bar sits on is not in the difference either:
     * it is empty width when the bar is at the bottom and empty height when it
     * is on a side, so whichever axis differs is the one that holds it. The
     * width difference is still assumed to be the *right* bar, which is where
     * every ROM this was measured on puts it in landscape.
     */
    @Suppress("DEPRECATION")
    private fun legacyInsets(wm: WindowManager): Insets {
        val real = DisplayMetrics()
        val app = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(real)
        wm.defaultDisplay.getMetrics(app)
        val res = Resources.getSystem()
        val statusId = res.getIdentifier("status_bar_height", "dimen", "android")
        val top = if (statusId > 0) res.getDimensionPixelSize(statusId) else 0
        val side = (real.widthPixels - app.widthPixels).coerceAtLeast(0)
        // The height difference counts the status bar too — take it out, or the
        // two fields below describe three bars' worth of inset for a phone with
        // two of them.
        val bottom = (real.heightPixels - app.heightPixels - top).coerceAtLeast(0)
        return Insets(left = 0, top = top, right = side, bottom = bottom)
    }
}
