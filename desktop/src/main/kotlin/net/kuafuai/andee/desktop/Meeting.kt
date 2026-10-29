package net.kuafuai.andee.desktop

import net.kuafuai.andee.asr.AsrEngine
import net.kuafuai.andee.asr.GoogleAsr
import net.kuafuai.andee.asr.HuoshanAsr
import net.kuafuai.andee.config.VoiceSettings
import net.kuafuai.andee.desktop.voice.MacVoice
import net.kuafuai.andee.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/**
 * Hold the microphone for a whole meeting and hand back a verbatim transcript.
 * The phone's MeetingRecorder, on the Mac's Java Sound: same segment rotation,
 * same ordered-final flush, same no-summary split (the brain writes the minutes
 * and lays them out with show_html).
 *
 * Not runtime-tested here: the audio path needs a live microphone and a voice
 * key, which this environment cannot drive. It compiles and registers; the
 * real-meeting behaviour is a device test.
 */
object Meeting {

    private const val TAG = "Meeting"
    private const val ASR_RATE = 16_000
    private const val SEGMENT_SOFT_MS = 120_000L
    private const val SEGMENT_HARD_MS = 180_000L
    private const val PAUSE_MS = 800L
    private const val MAX_RETURN_CHARS = 60_000

    private const val BY_USER = "user"
    private const val BY_BRAIN = "brain"
    private const val BY_SHUTDOWN = "shutdown"

    /** Called when the user (not the brain) ends a recording, with the result payload; the host writes the minutes. */
    @Volatile var onUserEnded: (JSONObject) -> Unit = {}

    @Volatile var onRecordingChanged: (Boolean) -> Unit = {}

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "meeting").apply { isDaemon = true } }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "meeting-writer").apply { isDaemon = true } }

    private val recording = AtomicBoolean(false)
    @Volatile private var active: Segment? = null
    @Volatile private var rotatePending = false
    private val nextIndex = AtomicInteger(0)
    private var title = ""
    private var startedAt = 0L
    @Volatile private var file: File? = null
    private val transcript = StringBuilder()
    private val finals = TreeMap<Int, String>()
    private var nextToFlush = 0

    fun isActive(): Boolean = recording.get()

    fun start(name: String?): JSONObject {
        if (recording.get()) throw IllegalStateException("a meeting is already being recorded — stop_meeting first")
        if (MacVoice.isListening()) throw IllegalStateException("the microphone is busy with a voice turn; try again in a moment")
        val cfg = MacVoice.settings()
        if (cfg.apiKey.isEmpty() && cfg.googleApiKey.isEmpty()) {
            throw IllegalStateException("no voice key is set, so there is nothing to transcribe with — the meeting would be silent")
        }
        title = name?.trim().orEmpty().ifEmpty { if (Strings.lang() == "zh") "会议" else "Meeting" }
        startedAt = System.currentTimeMillis()
        nextIndex.set(0)
        nextToFlush = 0
        synchronized(finals) { finals.clear() }
        synchronized(transcript) { transcript.setLength(0) }
        file = openTranscriptFile()
        val first = runCatching { openSegment() }.getOrElse {
            throw IllegalStateException("could not start the recogniser: ${it.message}", it)
        }
        active = first
        recording.set(true)
        exec.execute { micLoop() }
        onRecordingChanged(true)
        TimelineEvents.body(TimelineEvents.Body.MEETING, if (Strings.lang() == "zh") "开始录音：$title" else "Recording started: $title")
        Log.i(TAG, "meeting started: $title")
        return JSONObject().put("recording", true).put("title", title).put("started_at", startedAt)
    }

    /** The brain's own stop_meeting: the transcript is already in the tool result, so no minutes are asked for. */
    fun stop(): JSONObject {
        if (!recording.compareAndSet(true, false)) throw IllegalStateException("no meeting is being recorded")
        return stopImpl(BY_BRAIN)
    }

    /** The user's stop: end the recording and hand the transcript to the brain for minutes. */
    fun stopFromUser() {
        if (!recording.compareAndSet(true, false)) return
        exec.execute {
            runCatching { stopImpl(BY_USER) }.getOrNull()?.let { runCatching { onUserEnded(it) } }
        }
    }

    fun status(): JSONObject = JSONObject()
        .put("recording", recording.get())
        .put("title", title)
        .put("started_at", startedAt)
        .put("elapsed_ms", if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt)
        .put("segments", nextIndex.get())
        .put("chars", synchronized(transcript) { transcript.length })

    fun shutdown() {
        if (recording.compareAndSet(true, false)) runCatching { stopImpl(BY_SHUTDOWN) }
        exec.shutdown()
        io.shutdown()
        onRecordingChanged(false)
    }

    private fun stopImpl(stoppedBy: String): JSONObject {
        val seg = active
        active = null
        if (seg != null) retire(seg)
        onRecordingChanged(false)
        TimelineEvents.body(TimelineEvents.Body.MEETING, if (Strings.lang() == "zh") "录音结束" else "Recording ended")
        return result(stoppedBy)
    }

    // ---- audio --------------------------------------------------------------

    private fun micLoop() {
        runCatching {
            val fmt = AudioFormat(ASR_RATE.toFloat(), 16, 1, true, false)
            val line = AudioSystem.getTargetDataLine(fmt).apply { open(fmt, ASR_RATE * 2); start() }
            val buf = ByteArray(3200) // 100 ms
            while (recording.get()) {
                val n = line.read(buf, 0, buf.size)
                if (n > 0) onPcm(buf.copyOf(n))
            }
            runCatching { line.stop(); line.close() }
        }.onFailure { Log.e(TAG, "microphone loop failed", it) }
    }

    private fun onPcm(chunk: ByteArray) {
        val seg = active ?: return
        runCatching { seg.send(chunk) }.onFailure { Log.w(TAG, "sendAudio failed on segment ${seg.index}", it) }
        maybeRotate(seg)
    }

    private fun maybeRotate(seg: Segment) {
        if (rotatePending || !recording.get()) return
        val now = System.currentTimeMillis()
        val age = now - seg.openedAt
        if (age < SEGMENT_SOFT_MS) return
        if (age < SEGMENT_HARD_MS && now - seg.lastTextAt < PAUSE_MS) return
        rotatePending = true
        runCatching { exec.execute { rotate(seg) } }.onFailure { rotatePending = false }
    }

    private fun rotate(old: Segment) {
        try {
            if (!recording.get() || active !== old) return
            val fresh = openSegment()
            active = fresh
            retire(old)
        } catch (t: Throwable) {
            Log.w(TAG, "rotation failed, keeping segment ${old.index}", t)
            old.openedAt = System.currentTimeMillis()
        } finally {
            rotatePending = false
        }
    }

    private fun openSegment(): Segment {
        val seg = Segment(nextIndex.getAndIncrement(), MacVoice.settings())
        seg.asr.start(ASR_RATE, 1)
        return seg
    }

    private fun retire(seg: Segment, waitForFinal: Boolean = true) {
        runCatching { seg.asr.sendAudio(ByteArray(0), isLast = true) }
        if (waitForFinal) runCatching { seg.asr.waitFinished(seg.asr.finalWaitMs) }
        runCatching { seg.asr.close() }
        seg.settle()
    }

    // ---- transcript ---------------------------------------------------------

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
        val f = file ?: return
        runCatching { io.execute { runCatching { f.appendText(block) }.onFailure { Log.w(TAG, "transcript append failed", it) } } }
    }

    private fun openTranscriptFile(): File? = runCatching {
        val dir = File(AppDirs.root, "meetings").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(startedAt)
        File(dir, "meeting-$stamp.txt").apply {
            writeText("# $title\n# ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(startedAt)}\n\n")
        }
    }.onFailure { Log.w(TAG, "could not open transcript file", it) }.getOrNull()

    private fun result(stoppedBy: String): JSONObject {
        val full = synchronized(transcript) { transcript.toString() }.trim()
        val truncated = full.length > MAX_RETURN_CHARS
        return JSONObject()
            .put("ended", true)
            .put("title", title)
            .put("duration_ms", System.currentTimeMillis() - startedAt)
            .put("segments", nextIndex.get())
            .put("chars", full.length)
            .put("file", file?.let { "meetings/${it.name}" } ?: JSONObject.NULL)
            .put("stopped_by", stoppedBy)
            .put("truncated", truncated)
            .put("transcript", if (truncated) elide(full) else full)
    }

    private fun elide(full: String): String {
        val half = MAX_RETURN_CHARS / 2
        return full.take(half) + "\n\n[${full.length - MAX_RETURN_CHARS} characters elided in the middle; the full transcript is on disk]\n\n" + full.takeLast(half)
    }

    // ---- one recogniser session ---------------------------------------------

    private class Segment(val index: Int, cfg: VoiceSettings) : AsrEngine.Listener {
        val asr: AsrEngine = if (cfg.voiceProvider == VoiceSettings.VOICE_GOOGLE) GoogleAsr(cfg, this) else HuoshanAsr(cfg, this)
        @Volatile var openedAt = System.currentTimeMillis()
        @Volatile var lastTextAt = System.currentTimeMillis()
        private var partial = ""
        private val settled = AtomicBoolean(false)

        fun send(pcm: ByteArray) = asr.sendAudio(pcm, isLast = false)
        fun settle(text: String = partial) { if (settled.compareAndSet(false, true)) Meeting.noteFinal(index, text) }
        override fun onPartial(text: String) {
            if (text.isEmpty() || text == partial) return
            partial = text
            lastTextAt = System.currentTimeMillis()
        }
        override fun onFinal(text: String) = settle(text)
        override fun onError(msg: String) { Log.w(TAG, "segment $index error: $msg"); settle() }
        override fun onClosed() {}
    }
}

/** meeting.start / meeting.status / meeting.stop, so the brain sees them. */
class MeetingTools : DesktopBrainHost.ToolProvider {
    override fun handles(method: String) = method.startsWith("meeting.")
    override fun dispatch(method: String, p: JSONObject, callId: String, silent: Boolean, untrusted: Boolean): Any? = when (method) {
        "meeting.start" -> Meeting.start(p.optString("title").ifEmpty { null })
        "meeting.status" -> Meeting.status()
        "meeting.stop" -> Meeting.stop()
        else -> throw IllegalArgumentException("unknown method $method")
    }
}

/** The minutes the device asks the brain for when the user ends the recording themselves. */
fun meetingMinutesQuery(p: JSONObject): String {
    val title = p.optString("title").ifEmpty { if (Strings.lang() == "zh") "会议" else "Meeting" }
    val minutes = (p.optLong("duration_ms") / 60_000).coerceAtLeast(1)
    return buildString {
        append("(the user tapped to end the recording) The meeting just finished, and the transcript is below.\n")
        append("Turn it into meeting minutes, then use the show_html tool to lay the minutes out as an HTML page and show it to the user — a text-only reply is something they cannot see.\n")
        append("The minutes should contain: the subject, the key points, the conclusions reached, and action items (who does what).")
        append("The transcript comes out of speech recognition, so it will have wrong characters and missing punctuation; follow the context.\n\n")
        append("Title: $title\nDuration: about $minutes minutes\n\nTranscript:\n")
        append(p.optString("transcript"))
    }
}
