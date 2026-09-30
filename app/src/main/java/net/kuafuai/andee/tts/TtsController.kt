package net.kuafuai.andee.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import net.kuafuai.andee.audio.AudioFocusGate
import net.kuafuai.andee.audio.AudioIO
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.ui.FloatingWindowUi
import java.util.concurrent.Executors

/**
 * One-shot TTS: speak a single string via 火山 bidi TTS, stream PCM to AudioIO.
 *
 *   IDLE ── speak(text) ─→ SPEAKING (WS open, push, finish)
 *                          │
 *                          ├── sentence_start → subtitle SPEAKING
 *                          ├── done           → IDLE
 *                          └── error          → subtitle ERROR, IDLE
 *
 * Multi-sentence streaming (LLM-driven) will come later in Stage B via a
 * separate pipeline; this controller is deliberately the single-string case.
 */
class TtsController(
    private val context: Context,
    private val audio: AudioIO,
    private val window: FloatingWindowUi,
) {
    @Volatile
    private var client: HuoshanTts? = null

    // Set by [stopSpeaking] so the in-flight websocket callbacks of an
    // interrupted utterance stop touching the UI. Without it, cancelling the
    // socket can surface as onError → red ERROR ball, right as the user starts
    // talking. Cleared when the next utterance begins.
    @Volatile
    private var cancelled = false

    /**
     * Fired once the answer has finished DRAINING (the user has actually
     * stopped hearing it — see [onDone] for why that is not the same as the
     * server finishing). The service hooks this to open a short follow-up
     * mic window: as if the user pressed the talk button themselves, three
     * seconds after the answer ends. "Continue this conversation" without
     * the user having to reach for anything.
     */
    @Volatile
    var onDrained: (() -> Unit)? = null

    /**
     * The answer could not be spoken: socket error, socket death on its own, or
     * the stall watchdog giving up. Fired at most once per utterance.
     *
     * Deliberately **not** paired with a red ball any more. Nothing failed —
     * the words are already a row in the scrollback, and only the reading of
     * them aloud did not happen. The ball wearing ERROR said "your request
     * broke" about a request that had in fact succeeded, which is a worse lie
     * than saying nothing. The owner decides what to do instead: on a folded
     * card, put the answer up where it can be read.
     */
    @Volatile
    var onUnavailable: (() -> Unit)? = null

    /** Per utterance, so a socket that fails twice reports once. */
    @Volatile
    private var reported = false

    private fun reportUnavailable(why: String) {
        if (reported) return
        reported = true
        Log.w(TAG, "unavailable: $why")
        onUnavailable?.invoke()
    }

    // ---- Stalled-utterance watchdog ----------------------------------
    //
    // The ball enters SPEAKING in [speakImpl]'s onOpen and exits it in onDone
    // — which the websocket is under no obligation to deliver. Three exits
    // were missing, and each one left the ball doing the speaking face with
    // no sound until the next turn came along:
    //   1. the audio focus being yanked mid-answer (a call coming in) —
    //      stopSpeaking deliberately leaves state for its *caller* to take
    //      over, but this caller has no next state of its own;
    //   2. the socket dying on its own (network, server) — onClose arrived
    //      with nothing scheduled to follow it;
    //   3. the socket hanging silently — no done, no error, no close.
    // Exit 1 is fixed at the focus callback, exit 2 at onClose. This
    // watchdog is exit 3: it checks for PROGRESS, not total length — a long
    // answer streams pcm for minutes, a dead one stops within seconds.
    @Volatile
    private var lastProgressAt = 0L
    private val ui = Handler(Looper.getMainLooper())
    private var stallWatchdog: Runnable? = null

    private fun armStallWatchdog() {
        disarmStallWatchdog()
        lastProgressAt = SystemClock.elapsedRealtime()
        val r = object : Runnable {
            override fun run() {
                if (SystemClock.elapsedRealtime() - lastProgressAt < STALL_AFTER_MS) {
                    ui.postDelayed(this, STALL_CHECK_MS)
                    return
                }
                stallWatchdog = null
                if (cancelled) return
                val c = client ?: return
                client = null
                runCatching { c.cancel() }
                runCatching { audio.stopStreamPlayback() }
                focus.release()
                window.setState(FloatingWindowUi.State.IDLE)
                reportUnavailable("no audio and no done for ${STALL_AFTER_MS}ms")
            }
        }
        stallWatchdog = r
        ui.postDelayed(r, STALL_CHECK_MS)
    }

    private fun disarmStallWatchdog() {
        stallWatchdog?.let { ui.removeCallbacks(it) }
        stallWatchdog = null
    }

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TtsController").apply { isDaemon = true }
    }

    /**
     * Pause whatever the tablet is playing for the length of the answer.
     *
     * Plain transient, unlike the listening side's exclusive request: here we
     * only need to be heard over the media app, and a player that chooses to
     * duck instead of stop is fine — nothing is being recorded. Either way it
     * resumes by itself when we release.
     */
    private val focus = AudioFocusGate(
        context,
        android.media.AudioAttributes.USAGE_ASSISTANT,
        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
    )

    fun isSpeaking(): Boolean = client != null

    fun speak(text: String) {
        if (text.isBlank()) return
        cancelled = false
        reported = false
        executor.execute {
            try {
                speakImpl(text)
            } catch (t: Throwable) {
                Log.e(TAG, "speak failed", t)
                window.setState(FloatingWindowUi.State.IDLE)
                runCatching { audio.stopStreamPlayback() }
                focus.release()
                client = null
                reportUnavailable(t.message ?: "speak threw")
            }
        }
    }

    /**
     * Barge-in: cut playback immediately, on the CALLER's thread.
     *
     * Deliberately not routed through [executor] like [cancel] is — a queued
     * task would sit behind AudioIO.finishStreamPlayback, which sleeps for the
     * length of the audio still buffered. That sleep is exactly the wait we're
     * trying to abort. AudioTrack pause/flush/stop are cheap and safe to call
     * from here.
     *
     * Leaves the ball state alone: the caller is about to set its own (the
     * user is starting to speak, so ASR takes over the state machine).
     */
    fun stopSpeaking() {
        // Flag first, unconditionally: speak() may have queued an utterance
        // that hasn't reached speakImpl yet, so [client] can still be null
        // while an utterance is very much on its way.
        cancelled = true
        disarmStallWatchdog()
        val c = client
        client = null
        runCatching { c?.cancel() }
        runCatching { audio.stopStreamPlayback() }
        focus.release()
    }

    fun cancel() {
        executor.execute {
            disarmStallWatchdog()
            runCatching { client?.cancel() }
            runCatching { audio.stopStreamPlayback() }
            focus.release()
            client = null
            window.setState(FloatingWindowUi.State.IDLE)
        }
    }

    fun shutdown() {
        disarmStallWatchdog()
        runCatching { client?.cancel() }
        runCatching { audio.stopStreamPlayback() }
        focus.release()
        client = null
        executor.shutdownNow()
    }

    private fun speakImpl(text: String) {
        val cfg = VoiceConfig.load(context)
        // Interrupted while this utterance sat in the executor queue.
        if (cancelled) return

        // Before the socket opens, not when the first audio lands: a media app
        // takes a moment to actually stop, and the whole 火山 handshake is that
        // moment. Losing the floor mid-answer (a call arriving) ends the
        // utterance — talking underneath a ringtone helps nobody.
        //
        // The state handover is the point here: stopSpeaking leaves the ball
        // for its caller to take over (startTurn → RECORDING), but a focus
        // loss has no caller and no next state. Without this line the ball
        // would keep mouthing words in silence until the next turn.
        focus.acquire {
            stopSpeaking()
            window.setState(FloatingWindowUi.State.IDLE)
        }

        val listener = object : HuoshanTts.Listener {
            override fun onOpen(sampleRate: Int) {
                if (cancelled) return
                audio.startStreamPlayback(sampleRate)
                // The ball's speaking face is the whole signal. The sentence
                // itself is already a row in the scrollback — the caller logs it
                // before handing it here — so echoing it onto the live line put
                // the same words on screen twice, once settled and once in blue
                // right underneath.
                window.setState(FloatingWindowUi.State.SPEAKING)
                armStallWatchdog()
                executor.execute {
                    runCatching {
                        client?.push(text)
                        client?.finish()
                    }
                }
            }

            override fun onAudio(pcm: ByteArray) {
                if (cancelled) return
                lastProgressAt = SystemClock.elapsedRealtime()
                audio.feedPcm(pcm)
            }

            override fun onSentenceStart(t: String) {}

            override fun onSentenceEnd(t: String) {}

            override fun onDone() {
                if (cancelled) return
                disarmStallWatchdog()
                // SessionFinished means the *server* has stopped sending, not
                // that the user has stopped hearing: 火山 delivers audio far
                // faster than real time, so most of the utterance is usually
                // still queued at this point. The ball therefore goes IDLE on
                // the other side of the drain, not here — otherwise it drops
                // its speaking face a minute before it stops talking.
                executor.execute {
                    runCatching { audio.finishStreamPlayback() }
                    focus.release()
                    if (!cancelled) window.setState(FloatingWindowUi.State.IDLE)
                    // The follow-up window, after the drain: the mic opens only
                    // once the last word has actually been heard.
                    if (!cancelled) onDrained?.invoke()
                }
                client = null
            }

            override fun onError(msg: String) {
                // An interrupted utterance closes its socket mid-stream, which
                // the ws layer may report as an error. Not worth showing.
                if (cancelled) return
                disarmStallWatchdog()
                runCatching { audio.stopStreamPlayback() }
                focus.release()
                window.setState(FloatingWindowUi.State.IDLE)
                client = null
                reportUnavailable(msg)
            }

            override fun onClose(reason: HuoshanTts.CloseReason) {
                // Every planned exit (stopSpeaking, cancel, onDone, onError)
                // nulls [client] before the socket actually closes. Reaching
                // here with it still set therefore means the socket died on
                // its own — and no done/error is ever coming. Close the
                // utterance out, or the ball wears SPEAKING with no sound.
                if (client != null) {
                    disarmStallWatchdog()
                    client = null
                    runCatching { audio.stopStreamPlayback() }
                    focus.release()
                    window.setState(FloatingWindowUi.State.IDLE)
                    reportUnavailable("socket died: $reason")
                }
            }
        }

        val c = HuoshanTts(cfg, listener)
        client = c
        c.open()
    }

    companion object {
        private const val TAG = "Tts"

        // Watchdog cadence: check every 5s, declare the utterance dead after
        // 20s without a single pcm chunk. Generous for a slow network (volcano
        // streams pcm faster than realtime even on a bad one) and far shorter
        // than "until the next turn".
        private const val STALL_CHECK_MS = 5_000L
        private const val STALL_AFTER_MS = 20_000L
    }
}
