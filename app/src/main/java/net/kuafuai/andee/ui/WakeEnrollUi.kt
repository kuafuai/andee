package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.wake.Mfcc
import net.kuafuai.andee.wake.VoiceSegmenter
import net.kuafuai.andee.wake.WakeMic
import net.kuafuai.andee.wake.WakeTemplates
import net.kuafuai.andee.wake.WakeWord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Teaching Andee the wake phrase: say it [WakeTemplates.ENROLL_COUNT] times.
 *
 * There is no model to download and no phrase list — the three takes *are* the
 * recogniser (see [WakeWord]), and the spread between them is what sets the
 * accept threshold. So this screen has one real job beyond collecting audio:
 * get three takes that are honestly representative. Said too carefully, three
 * times in a row, the threshold comes out tight enough that normal speech
 * never matches it — hence the instruction to say it the way it will actually
 * be said.
 *
 * Looks like the other two cards because [SettingsUi] launches it — see [Glass].
 * Unlike them it stays `FLAG_NOT_FOCUSABLE` and does not touch [OwnCard]: it is
 * never the active window, so it cannot be what makes a UI dump come back empty.
 */
class WakeEnrollUi(
    private val context: Context,
    private val onDismiss: () -> Unit = {},
) {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "WakeEnroll").apply { isDaemon = true }
    }

    private var root: View? = null
    private lateinit var status: TextView
    private lateinit var progress: TextView
    private lateinit var recordBtn: TextView

    private val takes = ArrayList<Array<FloatArray>>()

    /** Whether the compositor agreed to blur. See [Glass.frost]. */
    private var frosted = false

    /**
     * Strings in the user's language, refreshed at the top of [build].
     *
     * Refreshed there so every later call site — [recordOne] and [onTake] run
     * off [build] — reads the language the card was drawn in.
     *
     * Views are still built on [context]; only text comes from here. See
     * [AppLocale] for why those two are deliberately different Contexts.
     */
    private var lctx: Context = context

    @Volatile
    private var busy = false

    fun show() {
        if (root != null) return
        val p = WindowManager.LayoutParams(
            cardWidth(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Nothing here is typed into, so the card has no reason to take
            // keyboard focus away from whatever is behind it.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        frosted = Glass.frost(context, wm, p)
        val v = build()
        runCatching {
            v.hideFromAccessibility()
            wm.addView(v, p)
            root = v
            Glass.enter(v)
        }
    }

    fun hide() {
        val v = root ?: return
        root = null
        exec.shutdownNow()
        Glass.exit(v) {
            runCatching { wm.removeView(v) }
            onDismiss()
        }
    }

    // ---- Build ----

    @SuppressLint("SetTextI18n")
    private fun build(): View {
        lctx = AppLocale.wrap(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(18))
            background = Glass.card(context, dp(28), frosted)
            clipToOutline = true
        }

        card.addView(TextView(context).apply {
            text = lctx.getString(R.string.wake_title)
            textSize = 19f
            setTextColor(Color.parseColor(Glass.TITLE))
        })

        card.addView(TextView(context).apply {
            text = lctx.getString(R.string.wake_instructions, WakeTemplates.ENROLL_COUNT)
            textSize = 13f
            setTextColor(Color.parseColor(Glass.SECONDARY))
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(10), 0, dp(14))
        })

        progress = TextView(context).apply {
            text = lctx.getString(R.string.wake_progress, 0, WakeTemplates.ENROLL_COUNT)
            textSize = 13f
            setTextColor(Color.parseColor(Glass.MUTED))
        }
        card.addView(progress)

        status = TextView(context).apply {
            text = if (WakeTemplates.enrolled(context)) {
                lctx.getString(R.string.wake_status_enrolled)
            } else {
                lctx.getString(R.string.wake_status_not_enrolled)
            }
            textSize = 14f
            setTextColor(Color.parseColor(Glass.LABEL))
            setPadding(0, dp(8), 0, dp(16))
        }
        card.addView(status)

        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        footer.addView(button(lctx.getString(R.string.wake_close), filled = false) { hide() })
        footer.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        recordBtn = button(lctx.getString(R.string.wake_start), filled = true) { recordOne() }
        footer.addView(recordBtn)
        card.addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return card
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor(if (filled) Glass.TITLE else Glass.LABEL))
            gravity = Gravity.CENTER
            background =
                if (filled) Glass.tinted(context, dp(20)) else Glass.panel(context, dp(20))
            setPadding(dp(22), dp(11), dp(22), dp(11))
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { onClick() }
        }

    // ---- Recording ----

    @SuppressLint("SetTextI18n")
    private fun recordOne() {
        if (busy) return
        busy = true
        recordBtn.text = lctx.getString(R.string.wake_listening)
        status.text = lctx.getString(R.string.wake_speak_now)
        exec.execute {
            // The listener has to let go first: it owns a recorder whenever the
            // gate allows, and two captures in one app is not a thing to rely on.
            val pcm = WakeWord.withMicReleased { captureOne() }
            val feats = if (pcm == null) emptyArray() else Mfcc.features(pcm)
            ui.post { onTake(pcm, feats) }
        }
    }

    /** Worker thread. One utterance, or null if the mic failed or nobody spoke. */
    private fun captureOne(): ShortArray? {
        val got = AtomicReference<ShortArray?>(null)
        val done = CountDownLatch(1)
        val seg = VoiceSegmenter { pcm ->
            if (got.compareAndSet(null, pcm)) done.countDown()
        }
        val mic = WakeMic { buf, n -> seg.feed(buf, n) }
        if (!mic.start()) return null
        runCatching { done.await(LISTEN_SECONDS, TimeUnit.SECONDS) }
        mic.stop()
        return got.get()
    }

    @SuppressLint("SetTextI18n")
    private fun onTake(pcm: ShortArray?, feats: Array<FloatArray>) {
        busy = false
        if (root == null) return
        if (pcm == null || feats.isEmpty()) {
            recordBtn.text = lctx.getString(R.string.wake_retry)
            status.text = lctx.getString(R.string.wake_not_heard)
            return
        }
        takes.add(feats)
        progress.text = lctx.getString(R.string.wake_progress, takes.size, WakeTemplates.ENROLL_COUNT)
        if (takes.size < WakeTemplates.ENROLL_COUNT) {
            recordBtn.text = lctx.getString(R.string.wake_say_again)
            val ms = pcm.size * 1000 / Mfcc.SAMPLE_RATE
            status.text = lctx.getString(
                R.string.wake_received,
                ms,
                WakeTemplates.ENROLL_COUNT - takes.size,
            )
            return
        }
        val threshold = WakeTemplates.save(context, takes.toList())
        WakeWord.reload()
        takes.clear()
        progress.text = lctx.getString(
            R.string.wake_progress,
            WakeTemplates.ENROLL_COUNT,
            WakeTemplates.ENROLL_COUNT,
        )
        recordBtn.text = lctx.getString(R.string.wake_rerecord)
        status.text = lctx.getString(R.string.wake_done, "%.2f".format(threshold))
    }

    private fun cardWidth(): Int =
        minOf(dp(420), (wm.currentWindowMetrics.bounds.width() * 0.92f).toInt())

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        /** Long enough to fumble the first attempt, short enough not to feel hung. */
        const val LISTEN_SECONDS = 8L
    }
}
