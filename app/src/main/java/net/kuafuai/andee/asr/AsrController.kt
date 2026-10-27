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
 * Every engine's callbacks arrive on its own thread, never the main one; we
 * marshal them to the UI thread via FloatingWindowUi's own Handler.
 */
class AsrController(
    private val context: Context,
    private val audio: AudioIO,
    private val window: FloatingWindowUi,
    private val onTranscript: (String, Boolean) -> Unit = { _, _ -> },
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
    private var asr: AsrEngine? = null
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

    /** When the recognizer last reported *new* words. Written from the engine's own thread. */
    @Volatile
    private var lastSpeechAt = 0L

    /**
     * Last partial text we counted. Written from the engine's own thread. Guards
     * against a recognizer that re-sends the same hypothesis while the user is
     * silent — without it the watchdog would never fire.
     */
    @Volatile
    private var lastPartial = ""

    /** When this turn began, for the [MAX_TURN_MS] ceiling. */
    private var turnStartedAt = 0L

    /**
     * Set by [AsrEngine.Listener.onReady]; false until the engine can hear.
     * See [checkSilence] for why the clock waits on it.
     */
    @Volatile
    private var engineReady = false

    /**
     * Has the recogniser reported anything at all this turn. A plain flag
     * rather than a timestamp comparison, which is what it used to be: the two
     * timestamps involved are set by different code paths now, so comparing
     * them for equality would have been true only by luck.
     */
    @Volatile
    private var heardAnything = false

    /**
     * How long a silence ends this turn, as answered by the engine in play —
     * see [AsrEngine.silenceMs]. Seeded with 火山's value so that the watchdog
     * has something sane to use in the window before an engine is built.
     */
    @Volatile
    private var turnSilenceMs = 3000L

    fun isActive(): Boolean = audio.isStreaming()

    /** Called from the UI thread when the user taps the ball. */
    fun toggle(userInitiated: Boolean = true) {
        executor.execute {
            try {
                if (audio.isStreaming()) stopImpl() else startImpl(userInitiated)
            } catch (t: Throwable) {
                Log.e(TAG, "toggle failed", t)
                runCatching { stopImpl() }
                window.setState(FloatingWindowUi.State.ERROR)
                onError(t.message ?: "asr error")
            }
        }
    }

    /**
     * [bySilence] is true only from [checkSilence] — the caller's own silence
     * rule is the reason, which an engine uses to decide how long its last
     * results can possibly still be worth waiting for. See
     * [AsrEngine.endOfTurn].
     */
    fun stop(bySilence: Boolean = false) {
        executor.execute { runCatching { stopImpl(bySilence) } }
    }

    fun shutdown() {
        runCatching { stopImpl() }
        executor.shutdownNow()
    }

    private fun startImpl(userInitiated: Boolean) {
        val cfg = VoiceConfig.load(context)
        // Set here, not in [armSilenceWatchdog], because 火山 reports
        // [AsrEngine.Listener.onReady] from *inside* its own connect — before
        // the capture starts and before that method runs. Measuring the
        // connection from a timestamp this turn had not set yet produced
        // "engine ready after 206230301ms": the process's uptime, reported as
        // if it were a handshake.
        turnStartedAt = SystemClock.uptimeMillis()
        heardAnything = false
        // First thing, so the media app has the whole 火山 handshake to actually
        // stop in — asking and then opening the mic in the same breath would
        // still catch the tail of whatever was playing. A phone call takes the
        // floor back: end the turn rather than transcribe one side of a call.
        regrabs = 0
        focus.acquire(onLost = { stop() }, decide = ::lossEndsTurn)

        val listener = object : AsrEngine.Listener {
            override fun onReady() {
                // The session can hear. Start the silence clock here, not at the
                // microphone — see [checkSilence].
                Log.i(TAG, "engine ready after ${SystemClock.uptimeMillis() - turnStartedAt}ms")
                lastSpeechAt = SystemClock.uptimeMillis()
                engineReady = true
            }

            override fun onPartial(text: String) {
                if (text.isEmpty()) return
                heardAnything = true
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
                    // Capture the origin per session; a later tap must not
                    // turn an earlier follow-up result into an exit command.
                    if (text.isNotBlank()) onTranscript(text, userInitiated)
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

        // Which service hears. The pick is a string and the failure direction
        // is 火山 — see [VoiceConfig.voiceProvider] — so an unrecognised value
        // can only ever mean "keep doing what this device already did".
        val client: AsrEngine = when (cfg.voiceProvider) {
            VoiceConfig.VOICE_GOOGLE -> GoogleAsr(cfg, listener)
            else -> HuoshanAsr(cfg, listener)
        }
        turnSilenceMs = client.silenceMs
        Log.i(
            TAG,
            "engine: silence=${client.silenceMs}ms finalWait=${client.finalWaitMs}ms",
        )
        // **Cleared here, before the engine exists, and not in [armSilenceWatchdog].**
        // 火山 reports [AsrEngine.Listener.onReady] from inside its own connect —
        // its `start` blocks on the handshake and calls it before returning — so
        // clearing this anywhere after that would erase the readiness the engine
        // had just declared, and the silence rule would never fire: the
        // microphone would sit open until [MAX_TURN_MS].
        engineReady = false

        // **The microphone and the tap's acknowledgement come first; the
        // handshake follows them.** 火山's `start` blocks on its own connect —
        // 1.7–2.2 s measured, and it is the CodeFlying path as much as the
        // direct one, since the gateway speaks the same protocol — so under the
        // old order the ball sat unchanged for two seconds after the tap and
        // the microphone did not open until it was over. The first words of the
        // sentence were never captured at all. Google's engine never had this,
        // because its `start` returns at once and its `sendAudio` holds what
        // arrives early; [HuoshanAsr] now does the same, so the two agree.
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

        client.start(sampleRate = 16_000, channels = 1)

        // **Armed after the capture starts, and that ordering is load-bearing
        // too.** [checkSilence] opens by giving up when there is no stream — the
        // normal case being a turn that has already ended — so arming this
        // before [audio.startStreaming] means the very first tick, 250 ms in,
        // finds no stream and stops the watchdog permanently.
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
    /** How many times this turn has taken the floor back from another app. See [lossEndsTurn]. */
    @Volatile
    private var regrabs = 0

    /**
     * Whether losing audio focus ends this listening turn. It used to, always — the rule the speaking side
     * needs (half an answer at a quarter volume is no answer) applied to the side that makes no sound.
     * The cost showed up as "tap the ball mid-task, it starts listening and stops at once": the app being
     * driven is in front then, and a video that autoplays, a chat's message tone or a voice clip the last
     * tap opened is a focus request that took the user's sentence with it.
     *
     * Now only a call ends it — the call notification ([CallState]) or the audio mode, which changes the
     * moment it rings, before any notification. A duck request means nothing to a microphone. Any other
     * taker is asked to give the floor back, twice a turn at most so two apps cannot fight over it; after
     * that we keep listening without it rather than drop what the user is saying.
     */
    private fun lossEndsTurn(change: Int): Boolean {
        if (change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) return false
        val mode = (context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).mode
        if (net.kuafuai.andee.device.CallState.isActive || mode != android.media.AudioManager.MODE_NORMAL) {
            Log.i(TAG, "focus lost to a call (mode=$mode) — ending the turn")
            return true
        }
        if (regrabs < MAX_REGRABS) {
            regrabs++
            focus.retake()
        }
        return false
    }

    private fun armSilenceWatchdog() {
        lastSpeechAt = SystemClock.uptimeMillis()
        lastPartial = ""
        silence.postDelayed(silenceWatchdog, SILENCE_CHECK_MS)
    }

    /**
     * Main thread only. Nothing new out of the recogniser for the engine's own
     * [AsrEngine.silenceMs] and we close the mic ourselves — [stop] says the
     * turn is over, waits for the engine's final line, and that final line is
     * what reaches the brain and starts the task. Indistinguishable from the
     * user tapping again, which is the point.
     *
     * **The clock is fed by the recogniser's own output and by nothing else,
     * and that is a simplification made after three attempts to do better.**
     * A local energy gate was added, tuned, re-tuned, extended to the gateway
     * path, and finally deleted: every version of it was a threshold that
     * separated the user from their room, and every room is different. The
     * device has a better answer available — the recogniser is already telling
     * us what it hears, at 火山's few-hundred-millisecond cadence and at
     * roughly one result a second from Gemini — so the only thing the window
     * has to exceed is that cadence, not any property of the room.
     *
     * Two rules hold the edges, and neither needs calibrating:
     *
     *  * **Nothing counts until the engine can hear** — [AsrEngine.Listener.onReady].
     *    The microphone opens 1.4–1.9 s before the session comes up on this
     *    device, and a window measured from the former used to expire on top of
     *    the handshake.
     *  * **A turn that has heard nothing at all gets [SILENT_START_GRACE_MS]
     *    before the silence rule applies.** Otherwise a tap followed by silence
     *    would end [MAX_TURN_MS] later, and a recogniser whose first result is
     *    slow would be cut off before it ever spoke.
     */
    private fun checkSilence() {
        if (!audio.isStreaming()) return  // finished already
        val now = SystemClock.uptimeMillis()
        if (now - turnStartedAt >= MAX_TURN_MS) {
            Log.i(TAG, "turn hit ${MAX_TURN_MS}ms ceiling — auto-stop")
            runCatching { stop() }
            return
        }
        if (!engineReady) {
            silence.postDelayed(silenceWatchdog, SILENCE_CHECK_MS)
            return
        }

        // Nothing heard yet: no partial has ever arrived, so there is nothing
        // to have been silent *after*. Bounded, or a tap on the ball followed
        // by nothing would hold the microphone to [MAX_TURN_MS].
        if (!heardAnything && now - turnStartedAt < SILENT_START_GRACE_MS) {
            silence.postDelayed(silenceWatchdog, SILENCE_CHECK_MS)
            return
        }

        val quiet = now - lastSpeechAt
        if (quiet < turnSilenceMs) {
            silence.postDelayed(silenceWatchdog, SILENCE_CHECK_MS)
            return
        }
        Log.i(TAG, "silence ${quiet}ms (limit=$turnSilenceMs) — auto-stop")
        runCatching { stop(bySilence = true) }
    }

    private fun stopImpl(bySilence: Boolean = false) {
        silence.removeCallbacks(silenceWatchdog)
        val client = asr
        if (audio.isStreaming()) {
            audio.stopStreaming()
            runCatching { client?.endOfTurn(bySilence) }
            runCatching { client?.sendAudio(ByteArray(0), isLast = true) }
                .onFailure { Log.w(TAG, "isLast failed", it) }
        }
        // Wait for the final before closing, or it never arrives. The deadline
        // is the engine's own — the two need very different ones and neither
        // is the caller's business; see [AsrEngine.finalWaitMs].
        runCatching { client?.waitFinished(client.finalWaitMs) }
        runCatching { client?.close() }
        asr = null
        // Only now, so the media app doesn't resume into the final transcript
        // — that arrives some time after we said the turn was over, and the
        // user is still waiting on it.
        focus.release()
        // The band comes down here as well as in `onFinal`, because the paths
        // that never reach it still leave the microphone's own line on screen.
        // The tap's acknowledgement is now drawn *before* the connect rather
        // than after it, so a handshake that fails would otherwise strand
        // 「听着…」 on the card for good — the turn is over and there is no
        // engine left to report anything.
        window.clearSubtitle()
        window.setState(FloatingWindowUi.State.IDLE)
    }

    companion object {
        private const val TAG = "Asr"

        /**
         * The longest a single turn may run, whatever the recogniser says.
         *
         * A backstop rather than a design. Sixty seconds is far beyond any
         * question this device is asked and short enough that a failure stays
         * diagnosable rather than looking like a hang — which is exactly what
         * it was for twice tonight, when a microphone that would not close read
         * to the user as a device that would not answer.
         */
        private const val MAX_TURN_MS = 60_000L

        /** See [lossEndsTurn]. */
        private const val MAX_REGRABS = 2

        /**
         * How long a turn may run having heard nothing at all before the
         * silence rule is allowed to end it. See [checkSilence].
         *
         * Four seconds, against a measured connection time of 1.4–1.9 s: long
         * enough to survive a slow handshake and a slow first result, short
         * enough that a tap followed by silence still ends on its own rather
         * than waiting out [MAX_TURN_MS].
         */
        private const val SILENT_START_GRACE_MS = 4000L

        /** How often the watchdog looks; only affects how promptly it stops. */
        private const val SILENCE_CHECK_MS = 250L
    }
}
