package net.kuafuai.andee.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import net.kuafuai.andee.audio.AudioFocusGate
import net.kuafuai.andee.audio.AudioIO
import net.kuafuai.andee.brain.SpeechMood
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

    // ---- Emotion cues: lining the model's faces up with its own voice ----
    //
    // The hard part is that there is no clock in common: the markers are at
    // character offsets into our string, and the thing the user is actually
    // hearing is an AudioTrack. The obvious bridge is 火山's own
    // TTSSentenceStart, which would pin each sentence in both clocks at once —
    // and it does not work. Measured on device: the server sends that event
    // **once per [HuoshanTts.push]**, not once per sentence, and with an
    // **empty** text field, so there is nothing to locate and nothing to
    // locate it with. Pushing each sentence as its own call would get one
    // event each, but then the events race the audio (the server may announce
    // all of them before much sound has arrived) and serialising the pushes
    // risks an audible gap mid-reply.
    //
    // So the mapping is arithmetic instead of protocol: assume the utterance
    // is read at a constant number of characters per millisecond. That is
    // false in detail — a comma is a pause, a digit is three syllables — but
    // the question being answered is "which of two faces", at one-sentence
    // resolution, where a few hundred milliseconds of error is invisible.
    // Total length comes from [AudioIO.queuedMs] at [onDone] (the producer's
    // clock, known within the first fraction of playback because 火山 streams
    // far faster than real time) and the position from [AudioIO.playedMs]
    // (the track's own head, so real time). Until the total is known the
    // opening face holds, which is the same fallback as having no cues.
    //
    // Everything below is touched from [ui] only, except [cueText] /
    // [cueList] / [cueTotalMs], which are written off the ui thread and are
    // therefore volatile.

    /** The clean utterance; only its length is used, as the character clock's span. */
    @Volatile
    private var cueText: String = ""

    @Volatile
    private var cueList: List<SpeechMood.Cue> = emptyList()

    /**
     * Length of the whole utterance in ms, or 0 until the server has sent all
     * of it. Set from [onDone] on the websocket thread, after the last
     * [onAudio] on that same thread — so it is complete by construction.
     */
    @Volatile
    private var cueTotalMs: Long = 0

    private var cueNext = 0
    private var cueTicker: Runnable? = null

    private fun startCueTicker() {
        stopCueTicker()
        if (cueList.size < 2) return  // one face for the whole reply needs no walking
        val r = object : Runnable {
            override fun run() {
                // Deliberately not `client == null`: the socket is nulled at
                // [onDone], which is near the *start* of what the user hears,
                // not the end. Gating on it stopped the walk before any
                // mid-utterance face was due. The speaker is the clock here,
                // so the speaker decides when the walk is over.
                if (cancelled || !audio.isStreamPlaying()) {
                    cueTicker = null
                    return
                }
                val total = cueTotalMs
                if (total > 0) {
                    val heard = cueText.length * audio.playedMs() / total
                    while (cueNext < cueList.size && cueList[cueNext].at <= heard) {
                        window.setEmotion(cueList[cueNext].mood)
                        cueNext++
                    }
                }
                if (cueNext < cueList.size) ui.postDelayed(this, CUE_CHECK_MS)
                else cueTicker = null
            }
        }
        cueTicker = r
        ui.postDelayed(r, CUE_CHECK_MS)
    }

    private fun stopCueTicker() {
        cueTicker?.let { ui.removeCallbacks(it) }
        cueTicker = null
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

    /**
     * Say it, optionally wearing the faces the model asked for.
     *
     * [cues] are [SpeechMood.Cue]s into [text] — already stripped of their
     * markers by the caller, which has to be the caller because the same clean
     * string is what goes into the scrollback. Empty is the normal case and
     * behaves exactly as this method did before the channel existed.
     */
    fun speak(text: String, cues: List<SpeechMood.Cue> = emptyList()) {
        if (text.isBlank()) return
        cancelled = false
        reported = false
        executor.execute {
            try {
                speakImpl(text, cues)
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
        stopCueTicker()
        val c = client
        client = null
        runCatching { c?.cancel() }
        runCatching { audio.stopStreamPlayback() }
        focus.release()
    }

    fun cancel() {
        executor.execute {
            disarmStallWatchdog()
            stopCueTicker()
            runCatching { client?.cancel() }
            runCatching { audio.stopStreamPlayback() }
            focus.release()
            client = null
            window.setState(FloatingWindowUi.State.IDLE)
        }
    }

    fun shutdown() {
        disarmStallWatchdog()
        stopCueTicker()
        runCatching { client?.cancel() }
        runCatching { audio.stopStreamPlayback() }
        focus.release()
        client = null
        executor.shutdownNow()
    }

    private fun speakImpl(text: String, cues: List<SpeechMood.Cue>) {
        val cfg = VoiceConfig.load(context)
        // Interrupted while this utterance sat in the executor queue.
        if (cancelled) return

        cueText = text
        cueList = cues
        cueTotalMs = 0
        ui.post { cueNext = 0 }

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
                // Straight after SPEAKING and on the same handler, so the face
                // the model opened with is already on when the first word is.
                cues.firstOrNull()?.takeIf { it.at == 0 }?.let { window.setEmotion(it.mood) }
                ui.post { startCueTicker() }
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
                // The whole utterance has now been received, so this is its
                // length — the span the cue walk measures character offsets
                // against. Read here rather than incrementally because it is
                // the one moment the producer's clock is known to be final,
                // and it is read on the same thread the audio arrived on.
                cueTotalMs = audio.queuedMs()
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

        /**
         * How often the cue walk asks the track where it is.
         *
         * A face arriving 120 ms into the sentence it belongs to is
         * indistinguishable from one arriving on the first syllable — the
         * crossfade in [net.kuafuai.andee.ui.ball.EmotionState] takes longer
         * than that by itself. Anything faster is a wake-up per frame for a
         * change nobody can see.
         */
        private const val CUE_CHECK_MS = 120L
    }
}
