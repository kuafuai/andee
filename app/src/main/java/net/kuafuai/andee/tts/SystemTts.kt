package net.kuafuai.andee.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import net.kuafuai.andee.config.VoiceConfig
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The device's own text-to-speech engine — `android.speech.tts.TextToSpeech`.
 *
 * Unlike [HuoshanTts] and [GoogleTts], this backend is free, works with no
 * network, and needs no API key: it speaks through whatever engine the user
 * has installed and selected in the system's "text-to-speech output" settings
 * (Google TTS, a vendor engine, MultiTTS, …). It is deliberately
 * engine-agnostic — the code never names a specific package, it binds
 * whatever the system reports as the default.
 *
 * **The one real difference from the other two backends is the sample rate.**
 * [TtsEngine]'s contract says [TtsEngine.Listener.onOpen] declares the rate
 * before the driver can push text, because 火山 learns it from an open socket
 * and Google declares a constant. A system engine only reveals its rate after
 * the WAV has been written, so this backend opens at an *unknown* rate
 * ([onOpen] with `0`) and reports the real number through [TtsEngine.Listener.onRate]
 * once the first file is read — the "build the AudioTrack late" path the
 * issue discussion settled on, instead of resampling.
 *
 * Each utterance is synthesised to a real file in one pass
 * (`synthesizeToFile`), the PCM is read back out of the WAV, and the file is
 * deleted immediately after. The utterance is not streamed: it is spoken only
 * after the whole file exists, which is the honest trade the system API
 * forces.
 */
class SystemTts(
    private val context: Context,
    private val listener: TtsEngine.Listener,
) : TtsEngine {

    private val text = StringBuilder()

    @Volatile
    private var cancelled = false

    /** The in-flight engine, so [cancel] can stop a sentence mid-synthesis. */
    @Volatile
    private var tts: TextToSpeech? = null

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "SystemTts").apply { isDaemon = true }
    }

    override fun open() {
        io.execute {
            if (cancelled) return@execute
            val initLatch = CountDownLatch(1)
            val inited = AtomicBoolean(false)

            val engine = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) inited.set(true)
                initLatch.countDown()
            }
            tts = engine

            // The onInit callback lands on the main looper; wait for it here.
            if (!initLatch.await(10, TimeUnit.SECONDS)) {
                Log.w(TAG, "init timeout")
                engine.shutdown()
                tts = null
                listener.onError("system tts init timeout")
                return@execute
            }
            if (cancelled) {
                engine.shutdown()
                tts = null
                return@execute
            }
            if (!inited.get()) {
                Log.w(TAG, "no system TTS engine on this device")
                engine.shutdown()
                tts = null
                listener.onError("no system tts engine")
                return@execute
            }

            // The reply follows the UI language, so ask the engine for that
            // language. A strict `isLanguageAvailable` gate is deliberately
            // *not* used here: engines report their capabilities as zh, zh-CN,
            // zh-Hans-CN … and refusing on a mismatch would reject an engine
            // that speaks the language perfectly well. Only a hard "missing
            // data" is fatal; "not supported" is left to the synthesis itself
            // to prove, so a fallback engine whose default is Chinese is not
            // wrongly gated out.
            val lang = if (VoiceConfig.uiLanguage(context) == VoiceConfig.LANG_EN) {
                Locale.US
            } else {
                Locale.SIMPLIFIED_CHINESE
            }
            val langResult = engine.setLanguage(lang)
            if (langResult == TextToSpeech.LANG_MISSING_DATA) {
                Log.w(TAG, "engine has no data for $lang")
                engine.shutdown()
                tts = null
                listener.onError("system tts: no language data for $lang")
                return@execute
            }

            // Rate unknown until the first file is read.
            listener.onOpen(0)
        }
    }

    override fun push(text: String) {
        synchronized(this) { this.text.append(text) }
    }

    override fun finish() {
        val pending = synchronized(this) { this.text.toString() }
        io.execute {
            runCatching { synth(pending) }.onFailure {
                if (cancelled) return@onFailure
                Log.e(TAG, "synth failed", it)
                listener.onError(it.message ?: "system tts failed")
            }
        }
    }

    override fun cancel() {
        cancelled = true
        // Interrupting must stop the engine, or the abandoned sentence keeps
        // the engine busy — the same rule the issue discussion spelled out.
        val engine = tts
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        tts = null
    }

    private fun synth(pending: String) {
        val engine = tts ?: run {
            listener.onError("system tts not initialized")
            return
        }
        if (cancelled) return

        val trimmed = pending.trim()
        // Nothing pronounceable — punctuation or whitespace. Saying nothing is
        // the correct reading of it, same as GoogleTts.
        if (trimmed.isEmpty() || trimmed.none { it.isLetterOrDigit() }) {
            release(engine)
            listener.onDone()
            return
        }

        listener.onSentenceStart(trimmed)
        val got = synthOne(engine, trimmed)
        listener.onSentenceEnd(trimmed)
        release(engine)
        if (cancelled) return

        if (!got) {
            listener.onError("system tts returned no audio")
            return
        }
        listener.onDone()
    }

    /**
     * Hand the engine back once the utterance is spoken. The PCM has already
     * been handed to the player by this point, so stopping the engine cannot
     * cut off what is still playing — but it does release the binder session,
     * which otherwise leaks one engine per utterance.
     */
    private fun release(engine: TextToSpeech) {
        runCatching { engine.stop() }
        runCatching { engine.shutdown() }
        if (tts === engine) tts = null
    }

    /** One whole utterance to a file, read back, file deleted. */
    private fun synthOne(engine: TextToSpeech, sentence: String): Boolean {
        val file = File(context.cacheDir, "andee_tts_${System.nanoTime()}.wav")
        val uid = "andee_${System.nanoTime()}"
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                if (utteranceId == uid) {
                    ok.set(true)
                    done.countDown()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == uid) {
                    ok.set(false)
                    done.countDown()
                }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == uid) {
                    ok.set(false)
                    done.countDown()
                }
            }
        })

        val result = engine.synthesizeToFile(sentence, null, file, uid)
        if (result != TextToSpeech.SUCCESS) {
            file.delete()
            return false
        }
        if (!done.await(30, TimeUnit.SECONDS)) {
            file.delete()
            return false
        }
        if (cancelled || !ok.get() || !file.exists() || file.length() == 0L) {
            file.delete()
            return false
        }

        val (rate, pcm) = readWav(file)
        // The temporary file must not survive the sentence; a pile of *.wav is
        // a bug, not a backlog.
        file.delete()

        if (rate <= 0 || pcm.isEmpty()) return false
        // Report the real rate once the first file tells us what it is.
        listener.onRate(rate)
        listener.onAudio(pcm)
        return true
    }

    /**
     * Pull the sample rate and the raw 16-bit mono PCM out of a WAV. Only the
     * common system-engine shape (PCM, 16-bit, mono) is returned; anything
     * else hands back an empty result so the caller falls back rather than
     * plays garbage at the wrong rate.
     */
    private fun readWav(file: File): Pair<Int, ByteArray> {
        val bytes = file.readBytes()
        if (bytes.size < 44) return 0 to ByteArray(0)

        var offset = 12
        var sampleRate = 0
        var channels = 1
        var bitsPerSample = 16
        var dataOffset = -1
        var dataSize = 0

        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = (bytes[offset + 4].toInt() and 0xff) or
                ((bytes[offset + 5].toInt() and 0xff) shl 8) or
                ((bytes[offset + 6].toInt() and 0xff) shl 16) or
                ((bytes[offset + 7].toInt() and 0xff) shl 24)

            if (id == "fmt ") {
                val audioFormat =
                    (bytes[offset + 8].toInt() and 0xff) or ((bytes[offset + 9].toInt() and 0xff) shl 8)
                if (audioFormat != 1) return 0 to ByteArray(0)
                channels =
                    (bytes[offset + 10].toInt() and 0xff) or ((bytes[offset + 11].toInt() and 0xff) shl 8)
                sampleRate = (bytes[offset + 12].toInt() and 0xff) or
                    ((bytes[offset + 13].toInt() and 0xff) shl 8) or
                    ((bytes[offset + 14].toInt() and 0xff) shl 16) or
                    ((bytes[offset + 15].toInt() and 0xff) shl 24)
                bitsPerSample =
                    (bytes[offset + 22].toInt() and 0xff) or ((bytes[offset + 23].toInt() and 0xff) shl 8)
            } else if (id == "data") {
                dataOffset = offset + 8
                dataSize = size
                break
            }
            offset += 8 + size + (size and 1)
        }

        if (dataOffset < 0 || dataOffset + dataSize > bytes.size) return 0 to ByteArray(0)
        if (channels != 1 || bitsPerSample != 16) return 0 to ByteArray(0)

        val pcm = bytes.copyOfRange(dataOffset, dataOffset + dataSize)
        return sampleRate to pcm
    }

    companion object {
        private const val TAG = "SystemTts"
    }
}
