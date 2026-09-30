package net.kuafuai.andee.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Ask the rest of the device to be quiet for the length of a voice turn, and
 * shut up ourselves when something more important asks us to.
 *
 * Without this the tablet's own video or music player just keeps going: our TTS
 * mixes on top of it, and — worse — the speaker output walks straight back into
 * the microphone and wrecks recognition. Android has exactly one mechanism for
 * "I need the audio device for a moment", and neither half of the voice pipeline
 * was using it.
 *
 * One gate per voice direction, because the two want different things from the
 * other app; see [gain] at the call sites. Both are transient — the media app
 * resumes on its own when we release, which is the whole reason to use focus
 * rather than reaching for [android.media.AudioManager.adjustStreamVolume].
 *
 * Thread-safe in the only way that matters: [acquire] and [release] may be
 * called from any thread and [release] is idempotent, because a focus loss and
 * the controller's own teardown routinely race.
 *
 * @param usage what we sound like to the audio policy — `USAGE_ASSISTANT` for
 *   both directions, which is what this device is.
 * @param gain `AUDIOFOCUS_GAIN_TRANSIENT` to speak over a paused media app, or
 *   `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` to listen. The exclusive form is not
 *   politeness: the plain one lets a well-behaved player duck instead of stop,
 *   and ducked music is still coming out of the speaker and into the mic.
 */
class AudioFocusGate(
    context: Context,
    usage: Int,
    gain: Int,
) {
    private val am = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Set by [acquire], cleared by whichever of loss/[release] happens first. */
    @Volatile
    private var onLost: (() -> Unit)? = null

    @Volatile
    private var held = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // All three are "stop": a phone call (LOSS_TRANSIENT), something
            // taking over for good (LOSS), or a notification that would rather
            // we ducked (CAN_DUCK). Ducking a voice turn is pointless — half an
            // answer at a quarter volume is not an answer — so we treat it as
            // the end of the turn too, which is what setWillPauseWhenDucked
            // below asks the system to let us decide.
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val cb = onLost
                onLost = null
                held = false
                Log.i(TAG, "focus lost ($change)")
                runCatching { cb?.invoke() }
            }
        }
    }

    private val request: AudioFocusRequest = AudioFocusRequest.Builder(gain)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setOnAudioFocusChangeListener(listener)
        .setWillPauseWhenDucked(true)
        .build()

    /**
     * Take the floor. Returns false if the system refused, in which case the
     * caller should carry on anyway — a refused request means something like an
     * in-progress phone call, and failing the user's turn outright is worse than
     * talking over whatever it was.
     *
     * @param onLost invoked, on a binder thread, when the floor is taken back
     *   mid-turn. Fires at most once per [acquire].
     */
    fun acquire(onLost: () -> Unit): Boolean {
        this.onLost = onLost
        val ok = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        held = ok
        if (!ok) {
            this.onLost = null
            Log.w(TAG, "focus request denied")
        }
        return ok
    }

    /** Give the floor back so the media app can resume. Safe to call twice. */
    fun release() {
        onLost = null
        if (!held) return
        held = false
        runCatching { am.abandonAudioFocusRequest(request) }
    }

    private companion object {
        const val TAG = "AudioFocus"
    }
}
