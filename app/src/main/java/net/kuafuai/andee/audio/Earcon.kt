package net.kuafuai.andee.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Two short tones: the microphone just opened, and the answer is being worked on.
 *
 * These are the audible half of the ball's face. The face only works when the
 * user happens to be looking at it, and the two moments that most need an
 * acknowledgement are exactly the moments they are not: after saying the
 * wake word they are waiting to hear whether to start talking, and after
 * finishing a sentence they are waiting to hear whether it landed.
 *
 * ## Synthesised, not shipped as assets
 *
 * A pair of .ogg files would be less code, but every parameter below is a
 * dial that wants turning on a real device, and a re-exported asset is not a
 * diff anyone can read. It is also about 40 ms of arithmetic once per process.
 *
 * ## Why they sound the way they do
 *
 * Nothing here is decoration. A sine with a fast-but-not-instant attack and an
 * exponential tail is what a struck object does, and the ear reads it as an
 * object rather than as a device beeping: a square wave or a hard-edged
 * envelope is the same information and sounds like a smoke alarm. The second
 * harmonic decays faster than the fundamental for the same reason — that is
 * the order real materials lose their partials in. The detuned copy a few
 * cents away beats slowly against the fundamental, which is the whole of the
 * "glass" quality.
 *
 * Direction carries the meaning. Opening the microphone rises (a perfect
 * fifth, the most consonant interval that still clearly moves); starting to
 * think falls, quieter and lower, because it is an acknowledgement rather
 * than an invitation and must not sound like a second prompt to speak.
 *
 * Levels are deliberately low. These play an inch from a microphone that is
 * about to open, and under a voice that is about to answer.
 */
object Earcon {

    private const val RATE = 24_000

    /** Peak of the loudest earcon, as a fraction of full scale. */
    private const val PEAK = 0.20

    /** Long enough that no edge clicks, short enough to still read as a strike. */
    private const val ATTACK_MS = 7

    /**
     * How long after a tone the wake listener must stay shut.
     *
     * Our own chime is speech-shaped enough for [net.kuafuai.andee.wake.VoiceSegmenter]
     * to cut it out as an utterance, and a tone run through CMVN lands at an
     * unpredictable distance from the templates — so the listener could
     * plausibly wake itself on the sound of having just woken up. Covering the
     * tone plus the room's own tail is cheaper than teaching the matcher to
     * recognise us.
     */
    private const val TAIL_MS = 250L

    /** Anything closer together than this is a state machine flapping, not a signal. */
    private const val MIN_GAP_MS = 150L

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Earcon").apply { isDaemon = true }
    }

    @Volatile
    private var quietUntil = 0L

    @Volatile
    private var lastPlayAt = 0L

    /** Rising fifth — the microphone is open, start talking. */
    private val listenPcm by lazy {
        render(listOf(Note(880.0, 0, 420, 1.0), Note(1318.5, 85, 470, 0.9)))
    }

    /** Falling fourth, quieter — heard you, working on it. */
    private val thinkPcm by lazy {
        render(listOf(Note(587.33, 0, 460, 0.55), Note(440.0, 110, 560, 0.5)))
    }

    fun listening() = play(listenPcm)

    fun thinking() = play(thinkPcm)

    /** True while a tone is audible, plus [TAIL_MS]. Read by the wake-word gate. */
    fun isSounding(): Boolean = SystemClock.uptimeMillis() < quietUntil

    // ---- playback ----

    private fun play(pcm: ByteArray) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPlayAt < MIN_GAP_MS) return
        lastPlayAt = now
        val ms = pcm.size.toLong() / 2 * 1000 / RATE
        quietUntil = now + ms + TAIL_MS
        exec.execute {
            runCatching {
                // USAGE_ASSISTANT, matching TtsController: this is the same
                // entity making the same kind of noise, so it belongs under the
                // same volume key. ASSISTANCE_SONIFICATION would be the
                // textbook answer and routes to the system stream, where a
                // silenced tablet would drop the chime while still speaking
                // full sentences out loud.
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(ms + 80)
                runCatching { track.stop() }
                track.release()
            }
        }
    }

    // ---- synthesis ----

    private class Note(
        val hz: Double,
        val atMs: Int,
        val lenMs: Int,
        val gain: Double,
    )

    private fun render(notes: List<Note>): ByteArray {
        val totalMs = notes.maxOf { it.atMs + it.lenMs }
        val total = RATE * totalMs / 1000
        val buf = DoubleArray(total)
        val attack = RATE * ATTACK_MS / 1000

        for (note in notes) {
            val start = RATE * note.atMs / 1000
            val len = RATE * note.lenMs / 1000
            // exp(-4.5) ≈ 0.011 at the end of the note, which is below the
            // threshold where truncating it is audible as a click.
            val tau = len / 4.5
            for (i in 0 until len) {
                val at = start + i
                if (at >= total) break
                val t = i.toDouble() / RATE
                val decay = exp(-i / tau)
                val rise = if (i < attack) 0.5 - 0.5 * cos(PI * i / attack) else 1.0
                var s = sin(2 * PI * note.hz * t)
                s += 0.5 * sin(2 * PI * note.hz * 1.004 * t)
                s += 0.12 * sin(4 * PI * note.hz * t) * exp(-i / (tau * 0.4))
                buf[at] += s * rise * decay * note.gain
            }
        }

        // One normalisation pass rather than hand-balanced gains: the partials
        // and the overlapping tails sum to something no one can predict, and
        // the note gains above are meant to read as relative loudness.
        var peak = 0.0
        for (v in buf) if (v > peak) peak = v else if (-v > peak) peak = -v
        val scale = if (peak > 0) PEAK * Short.MAX_VALUE / peak else 0.0

        val out = ByteArray(total * 2)
        for (i in 0 until total) {
            val v = (buf[i] * scale).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
