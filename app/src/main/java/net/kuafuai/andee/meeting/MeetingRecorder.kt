package net.kuafuai.andee.meeting

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import net.kuafuai.andee.R
import net.kuafuai.andee.asr.HuoshanAsr
import net.kuafuai.andee.audio.AudioFocusGate
import net.kuafuai.andee.audio.AudioIO
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.ui.FloatingWindowUi
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hold the microphone for a whole meeting and hand back a verbatim transcript.
 *
 * The device does not summarise anything — it has no model. It records, it
 * transcribes, and the brain turns the transcript into minutes and puts them on
 * screen with `show_html`. That split is why this class has no notion of a
 * "summary" anywhere in it.
 *
 * **Why segments.** [HuoshanAsr] reports a final transcript exactly once, when
 * the server answers our `isLast`. One session for one meeting would therefore
 * produce nothing at all until the meeting ended, and any dropped connection in
 * between would take the whole hour with it. So the recogniser is rotated: a
 * segment lives ~2 minutes, its final text is appended to disk the moment it
 * arrives, and a disconnect costs one segment instead of the meeting.
 *
 * **Why the new session opens before the old one is retired.** Retiring costs
 * up to [FINAL_WAIT_MS] of blocking (火山 sends the final ~500 ms after our
 * `isLast` — see `AsrController.stopImpl`), and connecting costs a second or
 * two. Doing either with no live session would punch a hole in the recording.
 * Opening first means audio always has somewhere to go, and it makes a failed
 * rotation free: if the replacement cannot connect, the old segment is still
 * receiving and the meeting carries on.
 */
class MeetingRecorder(
    private val context: Context,
    private val audio: AudioIO,
    private val window: FloatingWindowUi,
    /**
     * Unfold the card to full screen. Routed through the dispatcher rather
     * than straight at [FloatingWindowUi.setCompact] because the dispatcher
     * caches whether it has already folded, and a window unfolded behind its
     * back would stop it folding for the next `screen.*` call.
     */
    private val expand: () -> Unit,
    /** Fired for every end except the brain's own `stop_meeting` — see [endAndNotify]. */
    private val onEnded: (JSONObject) -> Unit,
) {

    /** Session open/close only. Never touched by the audio thread. */
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MeetingRecorder").apply { isDaemon = true }
    }

    /** Transcript appends, off both the WebSocket IO threads and [exec]. */
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MeetingWriter").apply { isDaemon = true }
    }

    private val ui = Handler(Looper.getMainLooper())

    /**
     * Same exclusive floor a voice turn takes, and for the same reason: the
     * speaker is an inch from the microphone, and a media app that merely ducks
     * is still playing into the recording — for an hour, this time.
     */
    private val focus = AudioFocusGate(
        context,
        android.media.AudioAttributes.USAGE_ASSISTANT,
        android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
    )

    private val recording = AtomicBoolean(false)

    /** Read once per audio chunk; swapped by [rotate]. */
    @Volatile
    private var active: Segment? = null

    @Volatile
    private var rotatePending = false

    private val nextIndex = AtomicInteger(0)

    private var title = ""
    private var startedAt = 0L

    @Volatile
    private var file: File? = null

    /** Guarded by itself. */
    private val transcript = StringBuilder()

    /** Out-of-order finals waiting for their predecessors. Guarded by itself. */
    private val finals = TreeMap<Int, String>()
    private var nextToFlush = 0

    /** The live segment's partial — the un-committed tail of [renderLive]. */
    @Volatile
    private var livePartial = ""

    fun isActive(): Boolean = recording.get()

    // ---- Start / stop ---------------------------------------------------

    /**
     * Open the microphone and the first recogniser session, then return.
     *
     * Deliberately synchronous even though the tool contract says do not block:
     * the handshake is normally under a second, and the alternative is a tool
     * that reports success and then records an hour of nothing because the
     * recogniser never connected. A meeting silently not being recorded is the
     * one failure here with no symptom until it is far too late to fix.
     */
    fun start(name: String?): JSONObject {
        if (recording.get()) {
            throw IllegalStateException("a meeting is already being recorded — stop_meeting first")
        }
        if (audio.isStreaming()) {
            throw IllegalStateException("the microphone is busy with a voice turn; try again in a moment")
        }
        // The fallback is a resource, not a literal: this title ends up as the
        // page header the minutes are laid out under, so it has to follow the
        // interface language. `ScreenBodyService.meetingMinutesQuery` reads the
        // same key back — see the note on it in `values/strings.xml`.
        title = name?.trim().orEmpty()
            .ifEmpty { AppLocale.str(context, R.string.meeting_default_title) }
        startedAt = System.currentTimeMillis()
        nextIndex.set(0)
        nextToFlush = 0
        synchronized(finals) { finals.clear() }
        synchronized(transcript) { transcript.setLength(0) }
        file = openTranscriptFile()

        // A phone call takes the floor and the microphone with it. Ending the
        // meeting keeps what was said so far instead of recording a call the
        // user did not ask us to record.
        //
        // Reported as its own reason, not as "user": the brain reads
        // `stopped_by` out loud, and a meeting killed by a focus loss used to
        // have it tell the user they had touched the ball.
        focus.acquire { endAndNotify(BY_FOCUS) }

        val first = try {
            openSegment()
        } catch (t: Throwable) {
            focus.release()
            throw IllegalStateException("could not start the recogniser: ${t.message}", t)
        }
        active = first
        recording.set(true)

        try {
            audio.startStreaming(sampleRate = 16_000, chunkMs = 100) { chunk -> onPcm(chunk) }
        } catch (t: Throwable) {
            recording.set(false)
            active = null
            runCatching { first.asr.close() }
            focus.release()
            throw t
        }

        ui.post {
            // Full screen for the length of the meeting. The transcript
            // scrolling past is the only evidence the recogniser is alive, and
            // a thumb-sized ball cannot carry it — the tablet is face-up on the
            // table with nothing else to do, so it may as well show the work.
            expand()
            window.setState(FloatingWindowUi.State.RECORDING)
            ui.removeCallbacks(tick)
            tick.run()
        }
        Log.i(TAG, "meeting started: $title → ${file?.name}")
        return JSONObject()
            .put("recording", true)
            .put("title", title)
            .put("started_at", startedAt)
            .put("file", fileRef())
    }

    /** Synchronous stop, for the dispatcher. Blocks for up to [FINAL_WAIT_MS]. */
    fun stop(): JSONObject {
        if (!recording.compareAndSet(true, false)) {
            throw IllegalStateException("no meeting is being recorded")
        }
        return stopImpl(BY_BRAIN)
    }

    /** The ball tap and ■ — see [endAndNotify]. */
    fun stopFromUser() = endAndNotify(BY_USER)

    /**
     * End the meeting and push the transcript at the brain, because nobody
     * asked it for one.
     *
     * Safe from the main thread and from a binder thread: the teardown blocks
     * on 火山's last word, which would ANR the UI, so it goes to [exec] and the
     * result comes back through [onEnded].
     *
     * @param reason reaches the brain verbatim as `stopped_by`, and it says
     *   this out loud to the user — so a focus loss must not claim they
     *   touched the ball.
     */
    private fun endAndNotify(reason: String) {
        if (!recording.compareAndSet(true, false)) return
        runCatching {
            exec.execute {
                val out = runCatching { stopImpl(reason) }.getOrNull() ?: return@execute
                runCatching { onEnded(out) }
            }
        }
    }

    fun status(): JSONObject = JSONObject()
        .put("recording", recording.get())
        .put("title", title)
        .put("started_at", startedAt)
        .put("elapsed_ms", if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt)
        .put("segments", nextIndex.get())
        .put("chars", synchronized(transcript) { transcript.length })
        .put("file", fileRef())

    /**
     * Service teardown. Deliberately the silent stop, not [stopFromUser]:
     * there is no brain to write minutes for a device that is going away, and
     * the callback would post work onto a window that is about to be removed.
     *
     * Blocks, because the caller releases the microphone the instant this
     * returns and the last segment's text exists only in memory until [io]
     * runs — but without [FINAL_WAIT_MS], which would put three seconds into
     * `onDestroy` to gain a final that the settled partial already covers.
     */
    fun shutdown() {
        if (recording.compareAndSet(true, false)) {
            runCatching { stopImpl(BY_SHUTDOWN, waitForFinal = false) }
        }
        exec.shutdown()
        io.shutdown()
        runCatching { io.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) }
    }

    private fun stopImpl(stoppedBy: String, waitForFinal: Boolean = true): JSONObject {
        runCatching { audio.stopStreaming() }
        val seg = active
        active = null
        if (seg != null) retire(seg, waitForFinal)
        focus.release()
        ui.post {
            ui.removeCallbacks(tick)
            window.clearSubtitle()
            window.setState(FloatingWindowUi.State.IDLE)
        }
        val out = result(stoppedBy)
        Log.i(TAG, "meeting ended ($stoppedBy): ${out.optInt("segments")} segments, ${out.optInt("chars")} chars")
        return out
    }

    // ---- Audio ----------------------------------------------------------

    /** `AudioIO-Streamer` thread. Must stay cheap — it is the read loop. */
    private fun onPcm(chunk: ByteArray) {
        val seg = active ?: return
        runCatching { seg.send(chunk) }
            .onFailure { Log.w(TAG, "sendAudio failed on segment ${seg.index}", it) }
        maybeRotate(seg)
    }

    /**
     * Rotate once the segment is old enough *and* the recogniser has gone quiet
     * — the seam then falls between sentences, where the hundred milliseconds
     * that both sessions briefly receive is silence and the duplication costs
     * nothing. [SEGMENT_HARD_MS] is for the meeting where nobody ever pauses.
     */
    private fun maybeRotate(seg: Segment) {
        if (rotatePending || !recording.get()) return
        val now = SystemClock.uptimeMillis()
        val age = now - seg.openedAt
        if (age < SEGMENT_SOFT_MS) return
        if (age < SEGMENT_HARD_MS && now - seg.lastTextAt < PAUSE_MS) return
        rotatePending = true
        // The audio thread can reach here a beat after [shutdown] closed the
        // executor; a rejection there would kill the read loop.
        runCatching { exec.execute { rotate(seg) } }
            .onFailure { rotatePending = false }
    }

    private fun rotate(old: Segment) {
        try {
            if (!recording.get() || active !== old) return
            val fresh = openSegment()
            active = fresh
            // The old segment's words are about to arrive as its final and
            // become committed text; leaving its partial up would show them
            // twice until they do.
            livePartial = ""
            retire(old)
        } catch (t: Throwable) {
            // The replacement never connected. The old session is still live
            // and still receiving, so the meeting is unharmed; push its clock
            // forward so the next attempt is a couple of minutes away rather
            // than on the very next chunk.
            Log.w(TAG, "rotation failed, keeping segment ${old.index}", t)
            old.openedAt = SystemClock.uptimeMillis()
        } finally {
            rotatePending = false
        }
    }

    private fun openSegment(): Segment {
        val seg = Segment(nextIndex.getAndIncrement())
        seg.asr.start(sampleRate = 16_000, channels = 1)
        return seg
    }

    private fun retire(seg: Segment, waitForFinal: Boolean = true) {
        runCatching { seg.asr.sendAudio(ByteArray(0), isLast = true) }
        if (waitForFinal) runCatching { seg.asr.waitFinished(FINAL_WAIT_MS) }
        runCatching { seg.asr.close() }
        // The latch also trips on error and on close, so a final is not
        // guaranteed to have arrived. Settling here keeps the last partial
        // rather than dropping two minutes of the meeting, and — because the
        // ordered flush below stalls on a missing index — guarantees every
        // segment reports something.
        seg.settle()
    }

    // ---- Transcript -----------------------------------------------------

    /**
     * Finals arrive on whichever WebSocket IO thread owns that segment, and a
     * retired segment's final races the live segment's. Hold them in index
     * order and release only a contiguous run, so the transcript can never
     * silently reorder what was said.
     */
    private fun noteFinal(index: Int, text: String) {
        val ready = ArrayList<String>()
        synchronized(finals) {
            finals[index] = text
            while (finals.containsKey(nextToFlush)) {
                val t = finals.remove(nextToFlush).orEmpty().trim()
                nextToFlush++
                if (t.isNotEmpty()) ready.add(t)
            }
        }
        if (ready.isEmpty()) return
        val block = ready.joinToString("\n", postfix = "\n")
        synchronized(transcript) { transcript.append(block) }
        renderLive()
        val f = file ?: return
        // runCatching around the submit too: a segment can settle from its
        // WebSocket thread after [shutdown] closed the writer, and a rejected
        // task must not surface as a crash inside 火山's callback.
        runCatching {
            io.execute {
                runCatching { f.appendText(block) }
                    .onFailure { Log.w(TAG, "transcript append failed", it) }
            }
        }
    }

    private fun openTranscriptFile(): File? = runCatching {
        val dir = File(context.getExternalFilesDir(null), "meetings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(startedAt)
        File(dir, "meeting-$stamp.txt").apply {
            writeText("# $title\n# ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(startedAt)}\n\n")
        }
    }.onFailure { Log.w(TAG, "could not open transcript file", it) }.getOrNull()

    private fun fileRef(): Any = file?.let { "meetings/${it.name}" } ?: JSONObject.NULL

    private fun result(stoppedBy: String): JSONObject {
        val full = synchronized(transcript) { transcript.toString() }.trim()
        val truncated = full.length > MAX_RETURN_CHARS
        return JSONObject()
            .put("ended", true)
            .put("title", title)
            .put("started_at", startedAt)
            .put("ended_at", System.currentTimeMillis())
            .put("duration_ms", System.currentTimeMillis() - startedAt)
            .put("segments", nextIndex.get())
            .put("chars", full.length)
            .put("file", fileRef())
            .put("stopped_by", stoppedBy)
            .put("truncated", truncated)
            .put("transcript", if (truncated) elide(full) else full)
    }

    /**
     * Keep both ends. A meeting long enough to overflow states its purpose at
     * the start and its decisions at the end; the middle is the part minutes
     * are allowed to lose.
     */
    private fun elide(full: String): String {
        val half = MAX_RETURN_CHARS / 2
        val dropped = full.length - MAX_RETURN_CHARS
        return full.take(half) +
            "\n\n[$dropped characters elided in the middle; the full transcript is on the " +
            "device at ${fileRef()}]\n\n" +
            full.takeLast(half)
    }

    // ---- Ball ----------------------------------------------------------

    /**
     * The clock plus the tail of what has actually been heard.
     *
     * Written to the live row rather than the scrollback: an hour of meeting
     * is ~30 segments, and committing each one as its own row would evict the
     * real conversation out of a history that keeps only the last 80 rows. The
     * tail is a window onto the transcript, not a second copy of it — the file
     * on disk is the record.
     *
     * Called from the 1 Hz [tick] so the clock stays honest, and from every
     * partial so the words don't arrive a second late.
     */
    private fun renderLive() {
        if (!recording.get()) return
        val s = (System.currentTimeMillis() - startedAt) / 1000
        val head = AppLocale.str(
            context,
            net.kuafuai.andee.R.string.meeting_live_head,
            s / 60,
            s % 60,
        )
        val committed = synchronized(transcript) {
            transcript.substring(maxOf(0, transcript.length - TAIL_CHARS))
        }
        val heard = (committed.replace('\n', ' ') + livePartial)
            .takeLast(TAIL_CHARS)
            .trim()
        window.setSubtitle(
            if (heard.isEmpty()) head else "$head\n$heard",
            FloatingWindowUi.SubtitleKind.PARTIAL,
        )
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!recording.get()) return
            renderLive()
            ui.postDelayed(this, 1000L)
        }
    }

    // ---- One recogniser session ----------------------------------------

    private inner class Segment(val index: Int) : HuoshanAsr.Listener {
        val asr = HuoshanAsr(VoiceConfig.load(context), this)

        /** Reset by [rotate] when a replacement could not be opened. */
        @Volatile
        var openedAt = SystemClock.uptimeMillis()

        /** Last time the recogniser produced *new* words — the pause detector. */
        @Volatile
        var lastTextAt = SystemClock.uptimeMillis()

        @Volatile
        private var partial = ""

        private val settled = AtomicBoolean(false)

        fun send(pcm: ByteArray) = asr.sendAudio(pcm, isLast = false)

        /** Contribute this segment's text to the transcript exactly once. */
        fun settle(text: String = partial) {
            if (settled.compareAndSet(false, true)) noteFinal(index, text)
        }

        override fun onPartial(text: String) {
            if (text.isEmpty() || text == partial) return
            partial = text
            lastTextAt = SystemClock.uptimeMillis()
            // Only the live segment drives the display; a retiring one's late
            // partial would drag the tail backwards.
            if (active === this) {
                livePartial = text
                renderLive()
            }
        }

        override fun onFinal(text: String) = settle(text)

        override fun onError(msg: String) {
            Log.w(TAG, "segment $index error: $msg")
            settle()
        }

        override fun onClosed() {}
    }

    private companion object {
        const val TAG = "Meeting"

        /**
         * `stopped_by` values. The brain narrates this to the user, so each
         * one has to be true: "user" is the ball or ■, "focus" is something
         * else on the device taking the audio floor (a phone call), "brain" is
         * its own `stop_meeting`, "shutdown" is the service going away.
         */
        const val BY_USER = "user"
        const val BY_FOCUS = "focus"
        const val BY_BRAIN = "brain"
        const val BY_SHUTDOWN = "shutdown"

        /** Earliest a segment may be rotated out, given a pause to do it in. */
        const val SEGMENT_SOFT_MS = 120_000L

        /** Rotate regardless — nobody in this meeting is stopping to breathe. */
        const val SEGMENT_HARD_MS = 180_000L

        /** Quiet this long and the seam is safe to put here. */
        const val PAUSE_MS = 800L

        /** 火山 sends its final ~500 ms after our isLast; this is the ceiling. */
        const val FINAL_WAIT_MS = 3000L

        /** Roughly a four-hour Chinese meeting. Beyond it, see [elide]. */
        const val MAX_RETURN_CHARS = 60_000

        /**
         * How much of the transcript the live row shows.
         *
         * Deliberately short. The row is `maxLines = 4` ellipsized at the
         * *end*, so a tail that overflows hides its own last words — the
         * newest ones, the only ones worth watching. One line goes to the
         * clock, leaving three; this fits them on a narrow screen and simply
         * scrolls sooner on a wide one.
         */
        const val TAIL_CHARS = 80
    }
}
