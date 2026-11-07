package net.kuafuai.andee.ui
import android.os.Build

import android.view.View
import android.view.WindowManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Our own cards stepping out of the way of one of our own Activities that needs the whole screen — today the
 * QR scanner opened from the Devices card to pair with the Mac.
 *
 * The cards are `TYPE_APPLICATION_OVERLAY` windows, which sit **above** every Activity, ours included, so
 * opening the scanner from inside a card puts the camera preview underneath the card that asked for it (and,
 * from Settings → Devices, under two). Closing the cards would lose where the user was; this keeps them and
 * makes them see-through and touch-through instead: alpha 0, not touchable, not focusable, and no blur behind —
 * a frosted window would otherwise keep defocusing the preview through an invisible card. An alpha-0 window
 * also does not count toward the untrusted-touch opacity ceiling, so the scanner's own ✕ still works.
 *
 * The unfolded main card is not a window this object can see; the service folds it through [host] and unfolds
 * it again afterwards if it was open.
 *
 * Main thread only.
 */
object StandAside {

    private class Entry(val wm: WindowManager, val view: () -> View?)
    private class Saved(val view: View, val flags: Int, val alpha: Float, val blur: Int)

    private val entries = CopyOnWriteArrayList<Entry>()
    private val saved = mutableListOf<Saved>()

    // `WindowManager.LayoutParams.blurBehindRadius` is API 31; even reading it
    // on an older platform is a NoSuchFieldError. Blur itself is already gone
    // below S (see Glass.frost), so the saved value is simply zero there.
    private fun blurRadius(p: WindowManager.LayoutParams): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) p.blurBehindRadius else 0

    private fun setBlurRadius(p: WindowManager.LayoutParams, value: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) p.blurBehindRadius = value
    }

    /** Set by the service: fold the main card (true) or put it back as it was (false). */
    @Volatile var host: ((aside: Boolean) -> Unit)? = null

    var aside = false
        private set

    /** [view] is asked each time, because a card rebuilds its root (language change) without telling anyone. */
    fun register(wm: WindowManager, view: () -> View?): Any = Entry(wm, view).also { entries += it }

    fun unregister(token: Any) { entries.remove(token) }

    fun set(on: Boolean) {
        if (on == aside) return
        aside = on
        if (on) {
            host?.invoke(true)
            for (e in entries) {
                val v = e.view() ?: continue
                val p = v.layoutParams as? WindowManager.LayoutParams ?: continue
                saved += Saved(v, p.flags, p.alpha, blurRadius(p))
                p.alpha = 0f
                p.flags = (p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                setBlurRadius(p, 0)
                runCatching { e.wm.updateViewLayout(v, p) }
            }
        } else {
            for (s in saved) {
                val p = s.view.layoutParams as? WindowManager.LayoutParams ?: continue
                p.flags = s.flags; p.alpha = s.alpha; setBlurRadius(p, s.blur)
                val wm = entries.firstOrNull { it.view() === s.view }?.wm ?: continue   // closed meanwhile
                runCatching { wm.updateViewLayout(s.view, p) }
            }
            saved.clear()
            host?.invoke(false)
        }
    }
}
