package net.kuafuai.andee.wake

import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * The wake word — whatever phrase the user enrolled, the hands-free way to do
 * what tapping the ball does. Nothing here knows what the phrase is.
 *
 * Entirely on device: [WakeMic] → [VoiceSegmenter] → [Mfcc] → [Dtw] against
 * the three takes the user enrolled. No network, no model file, no per-call
 * cost, and nothing recoverable as speech ever leaves the microphone thread.
 * The trade is accuracy — it recognises *this user's* phrase rather than the
 * phrase, and it will occasionally miss. That is the right trade for something
 * whose failure mode is "tap the ball instead".
 *
 * ## Who owns the microphone
 *
 * Never this class, when anything else wants it. The supervisor thread asks
 * [gate] a few times a second and closes the capture the moment it says no —
 * the conversation's own recorder must never fail because something was
 * listening for a wake word. The gate is also how "only while the screen is
 * on" is enforced, and why Andee does not hear itself: TTS speaking closes it
 * too.
 *
 * Idle when nothing is enrolled, deliberately: an unconfigured install should
 * not hold the microphone open, and the platform's green recording dot should
 * not appear for a feature the user has not set up.
 */
object WakeWord {

    private const val TAG = "Wake"

    /** How often the supervisor reconsiders. Also the worst-case wake latency added. */
    private const val POLL_MS = 100L

    /** Backoff when the device refuses the microphone (another app holds it). */
    private const val RETRY_MS = 2_000L

    /** One wake per this long, so a doubled phrase can't start two turns. */
    private const val REFRACTORY_MS = 2_500L

    private var appContext: Context? = null
    private var gate: () -> Boolean = { false }
    private var onWake: () -> Unit = {}

    @Volatile private var running = false

    /** Held by enrollment, which needs the microphone to itself. */
    @Volatile private var paused = false

    /** Set on the capture thread, acted on by the supervisor — see [onSegment]. */
    @Volatile private var heard = false

    @Volatile private var lastWakeAt = 0L

    private var thread: Thread? = null
    private val mic = WakeMic { pcm, n -> segmenter.feed(pcm, n) }
    private val segmenter = VoiceSegmenter { seg -> onSegment(seg) }

    fun init(context: Context, gate: () -> Boolean, onWake: () -> Unit) {
        this.appContext = context.applicationContext
        this.gate = gate
        this.onWake = onWake
    }

    fun enrolled(): Boolean {
        val ctx = appContext ?: return false
        return WakeTemplates.enrolled(ctx)
    }

    /** Re-read the templates after enrollment changed them. */
    fun reload() {
        WakeTemplates.invalidate()
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            while (running) {
                supervise()
                Thread.sleep(POLL_MS)
            }
            mic.stop()
        }.also { it.name = "WakeWord"; it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        val t = thread
        thread = null
        mic.stop()
        runCatching { t?.join(500) }
    }

    /**
     * Enrollment takes the microphone. Wrapped rather than exposed as two calls
     * so a thrown recorder can't leave the listener switched off for good.
     */
    fun <T> withMicReleased(block: () -> T): T {
        paused = true
        mic.stop()
        segmenter.reset()
        return try {
            block()
        } finally {
            paused = false
        }
    }

    private fun supervise() {
        if (heard) {
            heard = false
            // Close before waking: the very next thing that happens is the ASR
            // path opening its own recorder, and two live captures inside one
            // app is a device-dependent coin toss we have no reason to flip.
            mic.stop()
            segmenter.reset()
            runCatching { onWake() }.onFailure { Log.w(TAG, "onWake failed", it) }
            return
        }
        val want = !paused && enrolled() && runCatching { gate() }.getOrDefault(false)
        if (want) {
            if (!mic.isOpen()) {
                segmenter.reset()
                if (!mic.start()) Thread.sleep(RETRY_MS)
            }
        } else if (mic.isOpen()) {
            mic.stop()
            segmenter.reset()
        }
    }

    /** Capture thread. Must not block: the microphone stops being read while it runs. */
    private fun onSegment(pcm: ShortArray) {
        val ctx = appContext ?: return
        val store = WakeTemplates.load(ctx) ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastWakeAt < REFRACTORY_MS) return
        val feats = Mfcc.features(pcm)
        if (feats.isEmpty()) return
        val d = WakeTemplates.bestDistance(store, feats)
        if (d <= store.threshold) {
            Log.i(TAG, "wake: d=%.2f <= %.2f (%d ms)".format(d, store.threshold, pcm.size / 16))
            lastWakeAt = now
            heard = true
        } else if (d != Dtw.NO_MATCH) {
            // The one number worth having when the user says "it didn't hear
            // me": a near miss and an unrelated sentence look identical from
            // the outside, and only this tells them apart.
            Log.d(TAG, "no wake: d=%.2f > %.2f (%d ms)".format(d, store.threshold, pcm.size / 16))
        }
    }
}
