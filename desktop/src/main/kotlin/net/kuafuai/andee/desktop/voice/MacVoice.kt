package net.kuafuai.andee.desktop.voice

import net.kuafuai.andee.asr.AsrEngine
import net.kuafuai.andee.asr.GoogleAsr
import net.kuafuai.andee.asr.HuoshanAsr
import net.kuafuai.andee.brain.SpeechMood
import net.kuafuai.andee.config.VoiceSettings
import net.kuafuai.andee.desktop.Settings
import net.kuafuai.andee.tts.GoogleTts
import net.kuafuai.andee.tts.HuoshanTts
import net.kuafuai.andee.tts.TtsEngine
import net.kuafuai.andee.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Voice on the Mac: the phone's own 火山 / Google engines from the shared
 * module, on Java Sound for the microphone and the speaker.
 *
 * Listening is push-to-talk (the window's microphone button, or the shortcut):
 * a laptop has no echo cancellation here, so an always-on wake word would hear
 * Andee's own voice. The phone's wake word stays a phone feature for now.
 */
object MacVoice {

    private const val TAG = "Voice"
    private const val ASR_RATE = 16_000

    @Volatile var onPartial: (String) -> Unit = {}
    @Volatile var onFinal: (String) -> Unit = {}
    @Volatile var onState: (State) -> Unit = {}

    enum class State { IDLE, LISTENING, SPEAKING }

    /** Every change goes to the ball as well as the window, which has its own listener slot. */
    @Volatile private var current = State.IDLE

    /** The answer is being read out loud right now. */
    fun isSpeaking() = current == State.SPEAKING

    private fun state(s: State) {
        current = s
        net.kuafuai.andee.desktop.MacBall.onVoice(s)
        onState(s)
    }

    private val io = Executors.newCachedThreadPool { r -> Thread(r, "voice").apply { isDaemon = true } }
    private val listening = AtomicBoolean(false)
    @Volatile private var mic: TargetDataLine? = null
    @Volatile private var asr: AsrEngine? = null
    @Volatile private var speaker: SourceDataLine? = null
    @Volatile private var tts: TtsEngine? = null

    /** What the phone calls VoiceConfig.load(): the same keys, the same defaults. */
    fun settings(): VoiceSettings {
        // Under CodeFlying the address and key are the hosted backend's, and the voice is 火山, as on the phone.
        val cf = net.kuafuai.andee.desktop.Assistant.codeFlyingEndpoint()
            ?.takeIf { net.kuafuai.andee.desktop.Assistant.config().mode == net.kuafuai.andee.config.BrainConfig.BRAIN_CODEFLYING }
        return settings(cf)
    }

    private fun settings(codeFlying: net.kuafuai.andee.config.CodeFlyingEndpoint?): VoiceSettings = VoiceSettings(
        apiKey = if (codeFlying != null) net.kuafuai.andee.desktop.Assistant.codeFlyingKey() else Settings.get("api_key"),
        asrEndpoint = codeFlying?.asrEndpoint ?: Settings.get("asr_endpoint").ifEmpty { "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async" },
        asrResourceId = Settings.get("asr_resource_id").ifEmpty { "volc.bigasr.sauc.duration" },
        asrUid = Settings.uid(),
        ttsEndpoint = codeFlying?.ttsEndpoint ?: Settings.get("tts_endpoint").ifEmpty { "wss://openspeech.bytedance.com/api/v3/tts/bidirection" },
        ttsResourceId = Settings.get("tts_resource_id").ifEmpty { "seed-tts-2.0" },
        // The voice is the character's: read from the look the ball is wearing, per utterance, as the phone does.
        ttsSpeaker = net.kuafuai.andee.ui.ball.BallLooks.byName(Settings.get("ball").ifEmpty { null }).voice
            .ifEmpty { "zh_female_vv_uranus_bigtts" },
        ttsSampleRate = Settings.get("tts_sample_rate").toIntOrNull()?.takeIf { it in 8_000..48_000 } ?: 24_000,
        voiceProvider = if (codeFlying != null) VoiceSettings.VOICE_HUOSHAN else Settings.voiceProvider(),
        googleApiKey = Settings.get("google_api_key"),
        googleBaseUrl = Settings.get("google_base_url").ifEmpty { "https://aiplatform.googleapis.com" },
        googleProject = Settings.get("google_project"),
        googleLocation = Settings.get("google_location").ifEmpty { "asia-northeast1" },
        googleAsrModel = Settings.get("google_asr_model").ifEmpty { "gemini-live-2.5-flash-preview-native-audio" },
        googleTtsModel = Settings.get("google_tts_model").ifEmpty { "gemini-2.5-flash-preview-tts" },
        googleLanguage = Settings.get("google_language"),
    )

    fun configured(): Boolean = settings().let {
        if (it.voiceProvider == VoiceSettings.VOICE_GOOGLE) it.googleApiKey.isNotBlank() else it.apiKey.isNotBlank()
    }

    // ---- listening -------------------------------------------------------------

    fun isListening() = listening.get()

    /** Start listening; [stopListening] (or the engine's own end-of-turn) delivers [onFinal]. */
    fun startListening() {
        if (net.kuafuai.andee.desktop.Meeting.isActive()) return  // the meeting holds the microphone
        if (!listening.compareAndSet(false, true)) return
        stopSpeaking()
        val cfg = settings()
        val engine: AsrEngine = if (cfg.voiceProvider == VoiceSettings.VOICE_GOOGLE) GoogleAsr(cfg, listener) else HuoshanAsr(cfg, listener)
        asr = engine
        armWatchdog(engine)
        state(State.LISTENING)
        io.execute {
            runCatching {
                engine.start(ASR_RATE, 1)
                val fmt = AudioFormat(ASR_RATE.toFloat(), 16, 1, true, false)
                val line = AudioSystem.getTargetDataLine(fmt).apply { open(fmt, 6400); start() }
                mic = line
                val buf = ByteArray(3200) // 100 ms
                while (listening.get()) {
                    val n = line.read(buf, 0, buf.size)
                    if (n > 0) engine.sendAudio(buf.copyOf(n), isLast = false)
                }
                engine.sendAudio(ByteArray(0), isLast = true)
                engine.waitFinished(engine.finalWaitMs)
            }.onFailure {
                Log.e(TAG, "listening failed", it)
                listener.onError(it.message ?: "microphone unavailable")
            }
            runCatching { mic?.stop(); mic?.close() }
            mic = null
            runCatching { engine.close() }
            if (asr === engine) asr = null
            state(State.IDLE)
        }
    }

    /** The ball's tap and the 🎤 button: start, or say "done". False when there is no voice key. */
    fun toggle(): Boolean {
        if (!configured()) return false
        if (isListening()) stopListening() else startListening()
        return true
    }

    fun recordingMeeting(): Boolean = net.kuafuai.andee.desktop.Meeting.isActive()

    fun stopListening() {
        disarmWatchdog()
        if (listening.compareAndSet(true, false)) asr?.endOfTurn(bySilence = false)
    }

    private val partial = StringBuilder()

    // ---- the silence rule: the phone's, ported ---------------------------------------------------------
    //
    // The user says what they have to say and stops talking; nobody should have to press anything a second
    // time. The clock is fed by the recogniser's own output and by nothing else (a local energy gate is a
    // threshold that separates the user from their room, and every room is different). Three rules hold
    // the edges, none needing calibration:
    //  * nothing counts until the engine can hear (onReady) — the mic opens before the session comes up;
    //  * a turn that has heard nothing at all gets SILENT_START_GRACE_MS before silence may end it;
    //  * a turn never outlives MAX_TURN_MS, whatever the recogniser says.
    private const val SILENT_START_GRACE_MS = 4_000L
    private const val MAX_TURN_MS = 60_000L
    private const val SILENCE_CHECK_MS = 250L

    @Volatile private var turnStartedAt = 0L
    @Volatile private var lastSpeechAt = 0L
    @Volatile private var lastPartial = ""
    @Volatile private var engineReady = false
    @Volatile private var heardAnything = false
    @Volatile private var turnSilenceMs = 3_000L
    @Volatile private var watchdog: java.util.concurrent.ScheduledFuture<*>? = null
    private val watchdogExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "voice-silence").apply { isDaemon = true }
    }

    private fun armWatchdog(engine: AsrEngine) {
        turnStartedAt = System.currentTimeMillis()
        lastSpeechAt = turnStartedAt
        lastPartial = ""
        engineReady = false
        heardAnything = false
        turnSilenceMs = engine.silenceMs
        watchdog?.cancel(false)
        watchdog = watchdogExec.scheduleWithFixedDelay({ runCatching { checkSilence(engine) } }, SILENCE_CHECK_MS, SILENCE_CHECK_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun disarmWatchdog() { watchdog?.cancel(false); watchdog = null }

    private fun checkSilence(engine: AsrEngine) {
        if (asr !== engine || !listening.get()) { disarmWatchdog(); return }
        val now = System.currentTimeMillis()
        if (now - turnStartedAt >= MAX_TURN_MS) {
            Log.i(TAG, "turn hit ${MAX_TURN_MS}ms ceiling — auto-stop")
            endBySilence(engine); return
        }
        if (!engineReady) return
        // Nothing heard yet: there is nothing to have been silent *after*. Bounded, or a tap followed by
        // nothing would hold the microphone to the ceiling.
        if (!heardAnything && now - turnStartedAt < SILENT_START_GRACE_MS) return
        val quiet = now - lastSpeechAt
        if (quiet < turnSilenceMs) return
        Log.i(TAG, "silence ${quiet}ms (limit=$turnSilenceMs) — auto-stop")
        endBySilence(engine)
    }

    private fun endBySilence(engine: AsrEngine) {
        disarmWatchdog()
        // Exactly as if the user had tapped again; the engine is told it was the caller's silence, so it
        // knows the last results are already in hand.
        if (listening.compareAndSet(true, false)) engine.endOfTurn(bySilence = true)
    }

    private val listener = object : AsrEngine.Listener {
        override fun onReady() {
            lastSpeechAt = System.currentTimeMillis()
            engineReady = true
        }
        override fun onPartial(text: String) {
            if (text.isNotEmpty()) {
                heardAnything = true
                // Only a change counts: a recogniser may re-send the same hypothesis while the user is quiet.
                if (text != lastPartial) { lastPartial = text; lastSpeechAt = System.currentTimeMillis() }
            }
            onPartial.invoke(text)
        }
        override fun onFinal(text: String) {
            disarmWatchdog()
            listening.set(false)
            if (text.isNotBlank()) onFinal.invoke(text.trim())
        }
        override fun onError(msg: String) {
            disarmWatchdog()
            listening.set(false)
            Log.w(TAG, "asr: $msg")
            onPartial.invoke("")
        }
        override fun onClosed() { disarmWatchdog(); listening.set(false) }
    }

    // ---- speaking ---------------------------------------------------------------

    /** The brain's "this settles it" sentinel; the phone strips it before speaking too (case-insensitive). */
    private val END_MARKER = Regex("""(?i)\[END]""")

    /**
     * What the mouth gets: the same three steps the phone takes, in the same order — the end
     * sentinel, then Markdown, then mood markers. Whatever survives is read out loud as a word.
     */
    internal fun speakable(raw: String): String =
        SpeechMood.parse(net.kuafuai.andee.text.stripMarkdown(END_MARKER.replace(raw, ""))).text.trim()

    /** Speak [raw] (sentinel, Markdown and mood markers are stripped first). Returns at once; audio plays in the background. */
    fun speak(raw: String) {
        if (net.kuafuai.andee.desktop.Meeting.isActive()) return  // never speak into the recording
        val text = speakable(raw)
        if (text.isEmpty() || !configured()) return
        stopSpeaking()
        val cfg = settings()
        val done = AtomicBoolean(false)
        var engineRef: TtsEngine? = null
        val l = object : TtsEngine.Listener {
            override fun onOpen(sampleRate: Int) {
                // The session is ready only now; text pushed before this is dropped (as on the phone).
                engineRef?.let { it.push(text); it.finish() }
                runCatching {
                    val fmt = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
                    speaker = AudioSystem.getSourceDataLine(fmt).apply { open(fmt, sampleRate * 2); start() }
                    state(State.SPEAKING)
                }.onFailure { Log.e(TAG, "speaker unavailable", it) }
            }
            override fun onAudio(pcm: ByteArray) { speaker?.write(pcm, 0, pcm.size) }
            override fun onSentenceStart(text: String) {}
            override fun onSentenceEnd(text: String) {}
            override fun onDone() { finish() }
            override fun onError(msg: String) { Log.w(TAG, "tts: $msg"); finish() }
            override fun onClose(reason: TtsEngine.CloseReason) { finish() }
            fun finish() {
                if (!done.compareAndSet(false, true)) return
                io.execute {
                    runCatching { speaker?.drain(); speaker?.close() }
                    speaker = null
                    state(State.IDLE)
                }
            }
        }
        val engine: TtsEngine = if (cfg.voiceProvider == VoiceSettings.VOICE_GOOGLE) GoogleTts(cfg, l) else HuoshanTts(cfg, l)
        engineRef = engine
        tts = engine
        io.execute {
            runCatching { engine.open() }
                .onFailure { Log.e(TAG, "speaking failed", it); l.onError(it.message ?: "") }
        }
    }

    fun stopSpeaking() {
        runCatching { tts?.cancel() }
        tts = null
        runCatching { speaker?.stop(); speaker?.flush(); speaker?.close() }
        speaker = null
    }
}
