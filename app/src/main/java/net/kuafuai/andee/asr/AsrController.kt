package net.kuafuai.andee.asr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import net.kuafuai.andee.audio.AudioFocusGate
import net.kuafuai.andee.audio.AudioIO
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.ui.FloatingWindowUi
import java.util.concurrent.Executors

/**
 * ASR session lifecycle glued to [FloatingWindowUi].
 *
 *   IDLE ── toggle() ─→ RECORDING (mic open, WS streaming)
 *                      │
 *                      ├── partial   → subtitle PARTIAL, resets the silence clock
 *                      ├── SILENCE   → stop() exactly as if the user had tapped
 *                      │               again — see [checkSilence]
 *                      ├── final     → subtitle FINAL, IDLE, onTranscript(text)
 *                      ├── final ""  → subtitle cleared, IDLE, brain not woken.
 *                      │               The turn where nobody spoke; not an error
 *                      ├── error     → subtitle ERROR, IDLE
 *                      └── toggle()  → IDLE (user cancelled before final)
 *
 * All 火山 WS callbacks arrive on the client's IO thread; we marshal them to
 * the UI thread via FloatingWindowUi's own Handler.
 */
class AsrController(
    private val context: Context,
    private val audio: AudioIO,
    private val window: FloatingWindowUi,
    private val onTranscript: (String) -> Unit = {},
    /**
     * Voice failed. Called with the recogniser's own message, after the ball
     * has been put into ERROR.
     *
     * Routed to the owner rather than shown here because the interesting part
     * of the sentence is not the failure — it is what the user can do instead,
     * and only the service knows whether this device has a text field. Default
     * reproduces the old behaviour for any future caller that has no answer to
     * offer.
     */
    private val onError: (String) -> Unit = { msg ->
        net.kuafuai.andee.ui.CardUi.error(msg)
    },
) {
    @Volatile
    private var asr: HuoshanAsr? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AsrController").apply { isDaemon = true }
    }

    /**
     * Silence the rest of the device for the length of the turn.
     *
     * EXCLUSIVE rather than plain transient, which is the difference between the
     * media app stopping and the media app turning itself down: ducked audio is
     * still coming out of the speaker, and the speaker is roughly one inch from
     * the microphone we are about to open. Recognition against a tablet playing
     * a video is hopeless without this.
     */
    private val focus = AudioFocusGate(
        context,
        android.media.AudioAttributes.USAGE_ASSISTANT,
        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
    )

    // ---- hands-free turn taking ----

    /** Main-thread silence watchdog; see [checkSilence]. */
    private val silence = Handler(Looper.getMainLooper())
    private val silenceWatchdog = Runnable { checkSilence() }

    /** When the recognizer last reported *new* words. Written from the 火山 IO thread. */
    @Volatile
    private var lastSpeechAt = 0L

    /**
     * Last partial text we counted. Written from the 火山 IO thread. Guards
     * against a recognizer that re-sends the same hypothesis while the user is
     * silent — without it the watchdog would never fire.
     */
    @Volatile
    private var lastPartial = ""

    fun isActive(): Boolean = audio.isStreaming()

    /** Called from the UI thread when the user taps the ball. */
    fun toggle() {
        executor.execute {
            try {
                if (audio.isStreaming()) stopImpl() else startImpl()
            } catch (t: Throwable) {
                Log.e(TAG, "toggle failed", t)
                runCatching { stopImpl() }
                window.setState(FloatingWindowUi.State.ERROR)
                onError(t.message ?: "asr error")
            }
        }
    }

    fun stop() {
        executor.execute { runCatching { stopImpl() } }
    }

    fun shutdown() {
        runCatching { stopImpl() }
        executor.shutdownNow()
    }

    private fun startImpl() {
        val cfg = VoiceConfig.load(context)
        // First thing, so the media app has the whole 火山 handshake to actually
        // stop in — asking and then opening the mic in the same breath would
        // still catch the tail of whatever was playing. A phone call takes the
        // floor back: end the turn rather than transcribe one side of a call.
        focus.acquire { stop() }

        val listener = object : HuoshanAsr.Listener {
            override fun onPartial(text: String) {
                if (text.isEmpty()) return
                window.setSubtitle(text, FloatingWindowUi.SubtitleKind.PARTIAL)
                if (text != lastPartial) {
                    lastPartial = text
                    lastSpeechAt = SystemClock.uptimeMillis()
                }
            }

            override fun onFinal(text: String) {
                // Either way the band comes down. An empty turn — tapped the
                // ball, said nothing — has nothing to show; a real transcript
                // is about to become the first row of the scrollback via
                // onTranscript, and leaving it lit here as well is the same
                // sentence on screen twice.
                window.clearSubtitle()
                executor.execute {
                    runCatching { stopImpl() }
                    if (text.isNotBlank()) onTranscript(text)
                }
            }

            override fun onError(msg: String) {
                Log.w(TAG, "asr error: $msg")
                // Same reason as onFinal: the turn is over. The failure is going
                // up as a card and as a red row, and "听着…" is no longer true.
                window.clearSubtitle()
                window.setState(FloatingWindowUi.State.ERROR)
                // Qualified on purpose: this block overrides the listener's own
                // onError(msg), so a bare `onError(msg)` would resolve to *this*
                // function and recurse until the stack blows. The intent is the
                // constructor's callback — hand the failure to the owner.
                this@AsrController.onError(msg)
                executor.execute { runCatching { stopImpl() } }
            }

            override fun onClosed() {}
        }

        val client = HuoshanAsr(cfg, listener)
        client.start(sampleRate = 16_000, channels = 1)
        asr = client
        window.setState(FloatingWindowUi.State.RECORDING)
        window.setSubtitle(
            AppLocale.str(context, net.kuafuai.andee.R.string.asr_listening),
            FloatingWindowUi.SubtitleKind.PARTIAL,
        )

        audio.startStreaming(sampleRate = 16_000, chunkMs = 100) { chunk ->
            // Logged rather than swallowed: a send that throws every time looks
            // from the outside exactly like a mic that produces nothing — the
            // session stays open with no packets in it and 火山 ends it after
            // eight seconds, which reads as a network timeout and is not one.
            runCatching { asr?.sendAudio(chunk, isLast = false) }
                .onFailure { Log.w(TAG, "sendAudio failed", it) }
        }
        armSilenceWatchdog()
    }

    /**
     * Start counting towards an automatic stop.
     *
     * The mic opens with a tap and there is nothing demanding a second one:
     * the user says what they have to say and then stops talking, and waiting
     * for them to press anything again would turn a conversation into a
     * two-handed exercise — and now that talking happens from the corner ball,
     * with no card in the way, "I'm done" has to be inferable from silence.
     */
    private fun armSilenceWatchdog() {
        lastSpeechAt = SystemClock.uptimeMillis()
        lastPartial = ""
        silence.postDelayed(silenceWatchdog, SILENCE_CHECK_MS)
    }

    /**
     * Main thread only. Nothing new out of the recognizer for [SILENCE_MS] and
     * we close the mic ourselves — [stop] sends `isLast`, waits for 火山's final
     * line, and that final line is what reaches the brain and starts the task.
     * Indistinguishable from the user tapping again, which is the point.
     */
    private fun checkSilence() {
        if (!audio.isStreaming()) return  // finished already
        val quiet = SystemClock.uptimeMillis() - lastSpeechAt
        if (quiet < SILENCE_MS) {
            silence.postDelayed(silenceWatchdog, SILENCE_CHECK_MS)
            return
        }
        Log.i(TAG, "silence ${quiet}ms — auto-stop")
        runCatching { stop() }
    }

    private fun stopImpl() {
        silence.removeCallbacks(silenceWatchdog)
        val client = asr
        if (audio.isStreaming()) {
            audio.stopStreaming()
            runCatching { client?.sendAudio(ByteArray(0), isLast = true) }
                .onFailure { Log.w(TAG, "isLast failed", it) }
        }
        // Wait for 火山 to send the isLast response before closing — otherwise
        // the final transcript never arrives (server sends it ~500ms after our
        // isLast, but a 200ms fixed sleep isn't enough).
        runCatching { client?.waitFinished(3000) }
        runCatching { client?.close() }
        asr = null
        // Only now, so the media app doesn't resume into 火山's final transcript
        // — that arrives ~500 ms after our isLast and the user is still waiting
        // on it.
        focus.release()
        window.setState(FloatingWindowUi.State.IDLE)
    }

    companion object {
        private const val TAG = "Asr"

        /** Quiet this long after the last word and the turn is over. */
        private const val SILENCE_MS = 3000L

        /** How often the watchdog looks; only affects how promptly it stops. */
        private const val SILENCE_CHECK_MS = 250L
    }
}
