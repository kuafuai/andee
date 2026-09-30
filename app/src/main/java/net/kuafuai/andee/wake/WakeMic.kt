package net.kuafuai.andee.wake

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A 16 kHz mono capture with its own thread, opened and closed many times a
 * day.
 *
 * Separate from [net.kuafuai.andee.audio.AudioIO] on purpose. That class is the
 * conversation's microphone and refuses to run two sessions at once
 * (`audio busy`); this one is the wake word's, and the two must never be open
 * together — the wake listener releases the device before the user's turn
 * begins and takes it back afterwards. Sharing one recorder would mean one
 * owner, and the owner would have to be the wake word, which is the wrong way
 * round: the turn must never fail because something was listening for "嘿".
 *
 * Deliberately short-lived. Holding the microphone open while the screen is
 * off, or while Andee is talking, costs battery and lights the platform's
 * green recording dot for no reason.
 */
class WakeMic(private val onPcm: (ShortArray, Int) -> Unit) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun isOpen(): Boolean = running.get()

    /** False if the device refused the microphone — caller should back off and retry. */
    fun start(): Boolean {
        if (running.get()) return true
        val minBuf = AudioRecord.getMinBufferSize(
            Mfcc.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        // VOICE_RECOGNITION for the same reason the ASR path uses it: the
        // platform's own noise suppression and AGC, without the audiofx
        // attachments that stalled the HAL on this hardware.
        @Suppress("MissingPermission")
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                Mfcc.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord ctor failed", t)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        running.set(true)
        return try {
            rec.startRecording()
            thread = Thread {
                val buf = ShortArray(CHUNK)
                try {
                    while (running.get()) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) break
                        onPcm(buf, n)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "capture ended", t)
                } finally {
                    runCatching { rec.stop() }
                    rec.release()
                    running.set(false)
                }
            }.also { it.name = "WakeMic"; it.isDaemon = true; it.start() }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "startRecording failed", t)
            running.set(false)
            runCatching { rec.release() }
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        val t = thread
        thread = null
        runCatching { t?.join(500) }
    }

    private companion object {
        const val TAG = "Wake"

        /** 20 ms; two [VoiceSegmenter] frames per read, small enough to stop promptly. */
        const val CHUNK = Mfcc.HOP * 2
    }
}
