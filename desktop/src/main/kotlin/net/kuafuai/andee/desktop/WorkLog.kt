package net.kuafuai.andee.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.kuafuai.andee.brain.WorkStep
import java.util.concurrent.LinkedBlockingQueue

/**
 * What the turn in progress is doing, for the two places that show it: the live card at the foot of the
 * conversation (the newest four lines, scrolling) and the caption beside the ball (a status line and the newest
 * two). When the turn ends it folds into one row above the answer — 「做了 6 步 · 38 秒 ▾」 — that opens to the
 * steps.
 *
 * The model's reasoning arrives whole, after each call. It is cut into short lines and released a few at a time
 * by a feeder ([DRIP_MS] apart), so it runs past rather than appearing as a block: a turn that is thinking reads
 * as working, not as stuck. Only the first sentences of each thought are kept for the folded row.
 */
object WorkLog {

    enum class Kind { THOUGHT, SAID, ACTION }

    data class Line(val id: Long, val kind: Kind, val text: String)

    /** A turn is in progress. Compose state, read by the card and the caption. */
    var active by mutableStateOf(false)
        private set

    /** When it began, for the ticking timer. */
    var startedAt by mutableStateOf(0L)
        private set

    /** The step it is on (1-based), and whether it is waiting on the model right now. */
    var step by mutableStateOf(0)
        private set
    var waiting by mutableStateOf(false)
        private set

    /** Everything released so far this turn, oldest first. The views take the tail. */
    var lines by mutableStateOf<List<Line>>(emptyList())
        private set

    private var nextId = 1L
    private val drip = LinkedBlockingQueue<Line>()

    /** What the folded row keeps: actions and what it said in full, a thought's first sentences. */
    private val kept = mutableListOf<String>()

    /** From the brain's loop thread. */
    fun on(s: WorkStep) {
        when (s) {
            is WorkStep.Start -> {
                drip.clear()
                synchronized(kept) { kept.clear() }
                lines = emptyList()
                step = 0
                waiting = true
                startedAt = System.currentTimeMillis()
                active = true
            }
            is WorkStep.Waiting -> { step = s.step; waiting = true }
            is WorkStep.Thought -> {
                waiting = false
                val parts = cut(s.text)
                synchronized(kept) { kept += parts.take(KEEP_THOUGHT).joinToString("").let { (if (zh()) "想：" else "Thought: ") + it } }
                parts.take(MAX_THOUGHT_LINES).forEach { drip += Line(nextId++, Kind.THOUGHT, it) }
            }
            is WorkStep.Said -> {
                waiting = false
                synchronized(kept) { kept += s.text }
                drip += Line(nextId++, Kind.SAID, s.text.lineSequence().first { it.isNotBlank() }.trim())
            }
            is WorkStep.Action -> {
                waiting = false
                synchronized(kept) { kept += "› " + s.line }
                drip += Line(nextId++, Kind.ACTION, s.line)
            }
            is WorkStep.Done -> finish(s)
        }
    }

    /**
     * The folded row, written before the answer so it sits above it. A turn that only talked (no tool calls) leaves
     * nothing: a row on every chat reply would be the clutter this is meant to avoid.
     */
    private fun finish(d: WorkStep.Done) {
        drip.clear()
        active = false
        waiting = false
        if (d.actions == 0) return
        val secs = (d.ms / 1000).coerceAtLeast(1)
        val time = if (secs < 60) (if (zh()) "$secs 秒" else "${secs}s") else (if (zh()) "${secs / 60} 分 ${secs % 60} 秒" else "${secs / 60}m ${secs % 60}s")
        val head = if (zh()) "做了 ${d.actions} 步 · $time" else "${d.actions} steps · $time"
        val detail = synchronized(kept) { kept.takeLast(MAX_KEPT).joinToString("\n") { "- " + it.replace("\n", " ") } }
        TimelineEvents.body(TimelineEvents.Body.STEPS, head, detail)
    }

    /** For the screenshot harness only: an invented turn in progress. */
    internal fun seedForShot(seed: List<Pair<Kind, String>>, step: Int, secondsAgo: Int) {
        lines = seed.map { (k, t) -> Line(nextId++, k, t) }
        this.step = step
        waiting = false
        startedAt = System.currentTimeMillis() - secondsAgo * 1000L
        active = true
    }

    /** 「思考中… 0:12」 while waiting on the model, 「第 3 步 · 0:24」 while acting. */
    fun status(now: Long = System.currentTimeMillis()): String {
        val s = ((now - startedAt) / 1000).coerceAtLeast(0)
        val clock = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        return when {
            waiting && step <= 1 -> if (zh()) "思考中… $clock" else "Thinking… $clock"
            waiting -> if (zh()) "第 $step 步 · 思考中… $clock" else "Step $step · thinking… $clock"
            else -> if (zh()) "第 $step 步 · $clock" else "Step $step · $clock"
        }
    }

    /** Short lines out of a paragraph of reasoning: sentence ends, then a length cap. */
    internal fun cut(text: String): List<String> =
        Regex("""[^。！？!?\n]+[。！？!?]?""").findAll(text).map { it.value.trim() }
            .filter { it.length > 1 }
            .flatMap { s -> if (s.length <= LINE_CHARS) sequenceOf(s) else s.chunked(LINE_CHARS).asSequence() }
            .toList()

    private fun zh() = Strings.lang() == "zh"

    init {
        // The feeder: one line every DRIP_MS, faster when a backlog builds so a long thought still finishes
        // before the next step's lines arrive.
        Thread {
            while (true) {
                val l = runCatching { drip.take() }.getOrNull() ?: continue
                if (!active) continue
                lines = (lines + l).takeLast(MAX_LIVE)
                Thread.sleep(if (drip.size > 8) DRIP_MS / 3 else DRIP_MS)
            }
        }.apply { isDaemon = true; name = "worklog-drip" }.start()
    }

    private const val DRIP_MS = 160L
    private const val LINE_CHARS = 44
    private const val MAX_THOUGHT_LINES = 24
    private const val KEEP_THOUGHT = 2
    private const val MAX_KEPT = 60
    private const val MAX_LIVE = 40
}
