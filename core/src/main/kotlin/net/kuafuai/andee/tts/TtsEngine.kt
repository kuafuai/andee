package net.kuafuai.andee.tts

/**
 * One utterance, whichever backend speaks it.
 *
 * The call sequence is 火山's and both backends honour it: [open], then
 * [push] with the text (once, from the driver's `onOpen`) — for a backend that
 * synthesises on demand, however many times — then [finish]. Audio comes back
 * through [Listener.onAudio] as raw 16-bit little-endian PCM.
 *
 * **What a backend cannot promise is when the text — or the sample rate —
 * arrives relative to the network call.** 火山 accepts text into an open
 * socket, so it can report its sample rate before it has seen a word; Google
 * declares a constant. A system engine only learns its rate after writing the
 * WAV, so it opens at `0` and reports the real number through
 * [Listener.onRate]. [Listener.onOpen] is called *before* [push] by
 * construction either way.
 */
interface TtsEngine {

    /** Why a session ended. Narrated to nobody; used to describe a dead socket. */
    enum class CloseReason { USER, SERVER, ERROR }

    interface Listener {
        /**
         * The session is up and [sampleRate] is what the PCM will be in.
         *
         * Called once, before any [onAudio], and — for every backend — before
         * the driver can push text, because the driver pushes from this
         * callback.
         */
        fun onOpen(sampleRate: Int)

        /**
         * The sample rate the PCM is actually in, for a backend that could not
         * name it in [onOpen] because it only learns it after synthesising.
         * Called at most once, after [onOpen] and before the first [onAudio].
         *
         * Streaming backends (火山, Google) already declared their rate in
         * [onOpen], so this is a no-op for them and they do not override it.
         */
        fun onRate(sampleRate: Int) {}

        /**
         * This backend cannot run on this device at all — no bindable engine,
         * or no data for the language. The driver may retry once with another
         * backend; if it does not (or cannot), it treats this as [onError].
         *
         * Distinct from [onError] on purpose: a missing engine is a known,
         * expected state (a bare ROM), not a failure of a running utterance.
         */
        fun onUnavailable(reason: String) {}

        fun onAudio(pcm: ByteArray)

        fun onSentenceStart(text: String)

        fun onSentenceEnd(text: String)

        /** No more audio is coming. The driver keeps its own drain clock. */
        fun onDone()

        fun onError(msg: String)

        fun onClose(reason: CloseReason)
    }

    fun open()

    fun push(text: String)

    fun finish()

    fun cancel()
}

/**
 * Split an utterance into sentences, keeping the terminator attached to the
 * sentence it ends. `. ` rather than `.` in the pattern, so `3.5` is not cut
 * in half. Shared by the backends that synthesise one sentence at a time, so
 * they read the same terminators rather than drift apart.
 */
fun splitSentences(text: String): List<String> =
    text.split(Regex("(?<=[。！？!?\\n])|(?<=\\. )"))
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.any { ch -> ch.isLetterOrDigit() } }
