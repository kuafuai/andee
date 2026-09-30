package net.kuafuai.andee.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Audio in/out primitives for the physical body.
 *
 * Recorder produces raw PCM 16-bit little-endian (mono) at a configurable sample rate.
 * Player consumes the same format.
 *
 * Design notes:
 * - Single active session at a time (one recording, one playback).
 * - Recording is chunked but for MVP we accumulate into a ByteArrayOutputStream and
 *   return the full buffer on stop(). Streaming to WS is a later enhancement.
 * - Playback in this MVP is synchronous ("write all bytes and wait to finish"),
 *   which is fine because it runs on the WS handler thread, not the main UI thread.
 */
class AudioIO {

    // ---- Recorder ----

    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private val recording = AtomicBoolean(false)
    private val buffer = ByteArrayOutputStream()
    private var currentSampleRate = 16_000

    fun startRecording(sampleRate: Int = 16_000): Int {
        if (recording.get()) throw IllegalStateException("already recording")
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        require(minBuf > 0) { "AudioRecord.getMinBufferSize failed: $minBuf" }
        val bufSize = minBuf * 2
        // VOICE_RECOGNITION applies noise suppression + AGC tuned for ASR.
        // Compared to MIC (raw), it removes most of the "sha-sha" hiss on voice.
        @Suppress("MissingPermission")
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            encoding,
            bufSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord not initialized (mic permission?)")
        }
        buffer.reset()
        currentSampleRate = sampleRate
        rec.startRecording()
        recorder = rec
        recording.set(true)
        val chunk = ByteArray(bufSize)
        recordThread = Thread {
            while (recording.get()) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) synchronized(buffer) { buffer.write(chunk, 0, n) }
                else if (n < 0) break
            }
        }.also { it.name = "AudioIO-Recorder"; it.isDaemon = true; it.start() }
        return sampleRate
    }

    /** Stop recording and return the accumulated PCM bytes. */
    fun stopRecording(): Pair<ByteArray, Int> {
        if (!recording.get()) throw IllegalStateException("not recording")
        recording.set(false)
        recordThread?.join(1000)
        recordThread = null
        val rec = recorder
        recorder = null
        try {
            rec?.stop()
        } catch (_: Throwable) {
        }
        rec?.release()
        val bytes: ByteArray = synchronized(buffer) { buffer.toByteArray() }
        return bytes to currentSampleRate
    }

    fun isRecording(): Boolean = recording.get()

    // ---- Streaming record (for ASR) ----

    private var streamRecorder: AudioRecord? = null
    private var streamThread: Thread? = null
    private val streaming = AtomicBoolean(false)

    /**
     * Start reading mic in real-time chunks. Each chunk (~chunkMs of audio)
     * fires `onChunk(bytes)`. Does not accumulate; caller decides what to do
     * (usually: forward to ASR).
     *
     * ## No AcousticEchoCanceler / NoiseSuppressor, deliberately
     *
     * Both used to be attached to this session as a second line of defence
     * behind the caller's audio focus, for media apps that duck instead of
     * stopping. Attaching either stalls the audio HAL on a Xiaomi Pad 5:
     * measured on device, the first `read()` came back after 10.2 s with the
     * noise suppressor alone and 20.4 s with both, against 159 ms with
     * neither.
     *
     * Ten seconds of dead microphone is not a degradation, it is the whole
     * turn. 火山 ends a session that has gone eight seconds without a packet,
     * so the user taps the ball, says their piece into nothing, and gets back
     * a server-side timeout — a message about the network, which was never
     * the problem. That failure is worth far more than the effects were: the
     * VOICE_RECOGNITION source already runs the platform's own noise
     * suppression and AGC, tuned for exactly this.
     */
    fun startStreaming(
        sampleRate: Int = 16_000,
        chunkMs: Int = 100,
        onChunk: (ByteArray) -> Unit,
    ) {
        if (streaming.get() || recording.get()) throw IllegalStateException("audio busy")
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val chunkBytes = sampleRate * 2 /* bytes/sample */ * chunkMs / 1000
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        require(minBuf > 0) { "getMinBufferSize failed: $minBuf" }
        val bufSize = maxOf(minBuf * 2, chunkBytes * 4)

        @Suppress("MissingPermission")
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            encoding,
            bufSize,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord not initialized (mic permission?)")
        }
        streamRecorder = rec
        streaming.set(true)
        rec.startRecording()
        val startedAt = android.os.SystemClock.uptimeMillis()
        streamThread = Thread {
            val buf = ByteArray(chunkBytes)
            var chunks = 0L
            var why = "stopped"
            try {
                while (streaming.get()) {
                    var off = 0
                    while (off < buf.size && streaming.get()) {
                        val n = rec.read(buf, off, buf.size - off)
                        // A recorder that hands back nothing is the one failure
                        // mode with no symptom of its own: the loop just ends,
                        // the websocket sits there with an open session and no
                        // packets, and eight seconds later 火山 kills it for
                        // inactivity — which surfaces to the user as "ASR
                        // 连接超时", pointing at the network, which is fine.
                        if (n <= 0) {
                            why = "read=$n after $chunks chunks"
                            return@Thread
                        }
                        off += n
                    }
                    if (off > 0) {
                        // How long the first buffer took is the single number
                        // that separates "the mic is fine" from "this device
                        // hands us nothing": the symptom either way is a
                        // recognizer that times out, and the timeout is
                        // reported by the server, which makes it look like the
                        // network.
                        if (chunks == 0L) {
                            android.util.Log.i(
                                TAG,
                                "first chunk after ${android.os.SystemClock.uptimeMillis() - startedAt}ms",
                            )
                        }
                        chunks++
                        onChunk(buf.copyOf(off))
                    }
                }
            } catch (t: Throwable) {
                why = "threw ${t.javaClass.simpleName}: ${t.message} after $chunks chunks"
            } finally {
                android.util.Log.i(TAG, "streamer ended: $why")
            }
        }.also { it.name = "AudioIO-Streamer"; it.isDaemon = true; it.start() }
    }

    fun stopStreaming() {
        if (!streaming.get()) return
        streaming.set(false)
        streamThread?.join(1000)
        streamThread = null
        val rec = streamRecorder
        streamRecorder = null
        try {
            rec?.stop()
        } catch (_: Throwable) {
        }
        rec?.release()
    }

    fun isStreaming(): Boolean = streaming.get()

    // ---- Player ----

    private var track: AudioTrack? = null

    /**
     * Play a raw PCM buffer synchronously. Blocks until playback completes,
     * or ~ (pcm bytes / sample bytes) / sampleRate seconds.
     */
    fun playPcm(pcm: ByteArray, sampleRate: Int = 16_000) {
        if (pcm.isEmpty()) return
        stopPlayback()
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        require(minBuf > 0) { "AudioTrack.getMinBufferSize failed: $minBuf" }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, pcm.size))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        var offset = 0
        while (offset < pcm.size) {
            val n = t.write(pcm, offset, pcm.size - offset)
            if (n <= 0) break
            offset += n
        }
        // Wait roughly until playback drains.
        val bytesPerFrame = 2
        val durationMs = pcm.size.toLong() / bytesPerFrame * 1000L / sampleRate
        Thread.sleep(durationMs + 200)
        stopPlayback()
    }

    fun stopPlayback() {
        val t = track ?: return
        try {
            t.stop()
        } catch (_: Throwable) {
        }
        try {
            t.release()
        } catch (_: Throwable) {
        }
        track = null
    }

    fun release() {
        if (recording.get()) runCatching { stopRecording() }
        stopStreaming()
        stopPlayback()
        stopStreamPlayback()
    }

    // ---- Streaming playback (for TTS) ----

    // Written by whoever starts/stops playback, read by the player thread.
    // Barge-in stops playback from a third thread, so the player has to see
    // the null promptly.
    @Volatile
    private var streamTrack: AudioTrack? = null
    private var streamPlaySampleRate: Int = 24_000

    /** Player thread only, after [startStreamPlayback] zeroes it. */
    @Volatile
    private var streamBytesWritten: Long = 0

    /**
     * PCM waiting to be written to [streamTrack], and the thread that writes it.
     *
     * The hand-off is the whole point. `AudioTrack.write` on a MODE_STREAM track
     * blocks until the buffer has room, and the buffer holds well under a second
     * — while 火山 pushes a whole utterance's audio far faster than real time. Fed
     * directly, the websocket's reader thread therefore sat inside `write` for
     * essentially the entire utterance, which meant it never processed a pong
     * either, and Java-WebSocket's own lost-connection detector closed the socket
     * after ~120 s. Long answers stopped mid-sentence with no error anywhere; short
     * ones finished before the timer noticed, so it only ever looked like a bug in
     * long answers.
     *
     * Unbounded on purpose: any bound is a blocking producer again, which is the
     * thing being fixed. At 24 kHz mono 16-bit the backlog grows ~48 KB per second
     * of speech, and what's queued is one answer being read aloud.
     */
    private val playQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private var playThread: Thread? = null

    /** Producer's view of "there is somewhere to put audio". */
    @Volatile
    private var playing = false

    /** Queued by [finishStreamPlayback] to mean "no more audio is coming". */
    private val endOfStream = ByteArray(0)

    fun startStreamPlayback(sampleRate: Int = 24_000) {
        stopStreamPlayback()
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        require(minBuf > 0) { "AudioTrack.getMinBufferSize failed: $minBuf" }
        // USAGE_ASSISTANT, not USAGE_MEDIA: this is the device answering, not
        // the device playing something. Declaring it as media put our own voice
        // in the same bucket as whatever video the user has open, which is both
        // a lie to the audio policy and the wrong thing for the volume keys to
        // land on.
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuf * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        streamPlaySampleRate = sampleRate
        streamBytesWritten = 0
        playQueue.clear()
        t.play()
        streamTrack = t
        playing = true
        playThread = Thread {
            try {
                while (playing) {
                    val chunk = playQueue.take()
                    if (chunk === endOfStream) break
                    val track = streamTrack ?: break
                    var off = 0
                    while (off < chunk.size) {
                        val n = track.write(chunk, off, chunk.size - off)
                        if (n <= 0) break
                        off += n
                    }
                    streamBytesWritten += off
                }
            } catch (_: InterruptedException) {
                // Barge-in. Dropping the tail of an interrupted utterance is the
                // desired outcome.
            } catch (_: IllegalStateException) {
                // stopStreamPlayback released the track between the null check
                // and the write.
            }
        }.also { it.name = "AudioIO-Player"; it.isDaemon = true; it.start() }
    }

    /**
     * Hand a PCM chunk to the player. Never blocks — see [playQueue].
     *
     * Takes ownership of [pcm]; callers pass a fresh array per chunk.
     */
    fun feedPcm(pcm: ByteArray) {
        if (!playing || pcm.isEmpty()) return
        playQueue.offer(pcm)
    }

    /**
     * Called when the producer is done. Waits for the backlog to reach the track
     * and for the track to drain, then releases.
     *
     * Blocks for the full remaining length of the utterance, which is now most of
     * it rather than the tail — the audio arrives from 火山 far faster than it
     * plays. Callers must not be holding anything that a barge-in needs; see
     * [TtsController.stopSpeaking].
     */
    fun finishStreamPlayback() {
        val th = playThread ?: return
        playQueue.offer(endOfStream)
        runCatching { th.join() }
        val t = streamTrack ?: return
        val bytesPerFrame = 2  // 16-bit mono
        val totalFrames = streamBytesWritten / bytesPerFrame
        val playedFrames = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val remaining = (totalFrames - playedFrames).coerceAtLeast(0)
        val remainingMs = remaining * 1000L / streamPlaySampleRate
        try {
            Thread.sleep(remainingMs + 200)
        } catch (_: InterruptedException) {
        }
        stopStreamPlayback()
    }

    fun stopStreamPlayback() {
        playing = false
        val th = playThread
        playThread = null
        playQueue.clear()
        // Null the track before touching it: the player thread re-reads it every
        // chunk and must not start a write into one we are about to release.
        val t = streamTrack
        streamTrack = null
        th?.interrupt()
        if (t == null) return
        try {
            t.pause()
        } catch (_: Throwable) {
        }
        try {
            t.flush()
        } catch (_: Throwable) {
        }
        try {
            t.stop()
        } catch (_: Throwable) {
        }
        try {
            t.release()
        } catch (_: Throwable) {
        }
        streamBytesWritten = 0
    }

    private companion object {
        const val TAG = "AudioIO"
    }
}
