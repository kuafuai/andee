package net.kuafuai.andee.debug

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import net.kuafuai.andee.ui.ball.Action
import net.kuafuai.andee.ui.ball.BallLooks
import net.kuafuai.andee.ui.ball.EmotionBallTextureView
import net.kuafuai.andee.ui.ball.Mood

/**
 * One ball on a plain background, driven by intent extras, for `screencap`.
 *
 *   adb shell am start -n net.kuafuai.andee/.debug.BallLabActivity \
 *       --es mood WINK --es look andee --es bg light --ei dp 360
 *
 * Extras (all optional): `mood` (a [Mood] name), `emotion` (a [Mood] name worn
 * while `mood` is SPEAKING), `look` (a BallLook name), `action` (an [Action]
 * name, fired once), `bg` (`dark` | `light` | `mid`), `dp` (the ball's side; 168
 * is the real folded size), `working`, `holding`, `ledge`, `side` (docked into the right edge).
 *
 * Debug builds only — it lives in `src/debug`, so release never carries it.
 */
class BallLabActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var label: TextView
    private var ball: EmotionBallTextureView? = null
    private var builtDp = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Long captures (the idle life takes minutes) must not end on a dark screen.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        label = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(24, 40, 24, 0)
        }
        setContentView(root)
        apply(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        apply(intent)
    }

    private fun apply(i: Intent) {
        val bg = when (i.getStringExtra("bg")) {
            "light" -> 0xFFF4F6FB.toInt()
            "mid" -> 0xFF8A93A8.toInt()
            else -> 0xFF0F172A.toInt()
        }
        root.setBackgroundColor(bg)
        label.setTextColor(if (bg == 0xFFF4F6FB.toInt()) Color.DKGRAY else Color.LTGRAY)

        i.getStringExtra("look")?.let { BallLooks.requestByName(it) }

        val dp = i.getIntExtra("dp", 360)
        if (ball == null || dp != builtDp) {
            ball?.let { root.removeView(it) }
            val px = (dp * resources.displayMetrics.density).toInt()
            ball = EmotionBallTextureView(this).also {
                root.addView(it, FrameLayout.LayoutParams(px, px, Gravity.CENTER))
            }
            builtDp = dp
            root.removeView(label)
            root.addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP,
            ))
        }
        val b = ball!!

        val mood = runCatching { Mood.valueOf(i.getStringExtra("mood")?.uppercase() ?: "CALM") }
            .getOrDefault(Mood.CALM)
        b.setMood(mood)
        b.setListening(mood == Mood.LISTENING)
        b.setEmotion(i.getStringExtra("emotion")?.let { runCatching { Mood.valueOf(it.uppercase()) }.getOrNull() })
        b.setWorking(i.getBooleanExtra("working", false))
        b.setAtLedge(i.getBooleanExtra("ledge", false))
        b.setSideLedge(i.getBooleanExtra("side", false))
        b.setHolding(i.getBooleanExtra("holding", false))
        i.getStringExtra("action")?.let { name ->
            runCatching { Action.valueOf(name.uppercase()) }.getOrNull()?.let { b.trigger(it) }
        }
        label.text = "${BallLooks.requestedLook.name} · $mood" +
            (i.getStringExtra("emotion")?.let { " · $it" } ?: "")
    }
}
