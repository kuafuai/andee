package net.kuafuai.andee.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import net.kuafuai.andee.R
import net.kuafuai.andee.brain.WorkStep
import net.kuafuai.andee.i18n.AppLocale

/**
 * The phone's twin of the Mac's `WorkLog`: what the turn in progress is doing, for the two places that show it —
 * the work card at the foot of the unfolded card ([WorkCardView], the newest four lines) and the tiny caption under
 * the Andee docked into the right edge (status and the newest two). When the turn ends it folds into one row above
 * the answer, 「做了 6 步 · 38 秒 ▾」, that opens to the steps.
 *
 * The model's reasoning arrives whole after each call; it is cut into short lines and released one every
 * [DRIP_MS] on the main thread, so it runs past rather than appearing as a block.
 *
 * [on] is called on the brain's loop thread. The folded row is written there, synchronously, because the answer is
 * written on that same thread right after — posting it would put the row under the answer. Everything a view
 * reads is main-thread state, changed by posted runnables.
 */
object PhoneWorkLog {

    enum class Kind { THOUGHT, SAID, ACTION }

    data class Line(val id: Long, val kind: Kind, val text: String)

    private val main = Handler(Looper.getMainLooper())

    // ---- main-thread state, read by the views ----
    var active = false; private set
    var startedAt = 0L; private set
    var step = 0; private set
    var waiting = false; private set
    var lines: List<Line> = emptyList(); private set

    private val listeners = mutableListOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { runCatching { it() } }

    private var nextId = 1L
    private val queue = ArrayDeque<Line>()

    /** Loop thread: what the folded row keeps. */
    private val kept = mutableListOf<String>()

    @Volatile private var appContext: Context? = null
    fun attach(c: Context) { appContext = c.applicationContext }

    /** From the brain's loop thread. */
    fun on(s: WorkStep) {
        val c = appContext ?: return
        when (s) {
            is WorkStep.Start -> {
                synchronized(kept) { kept.clear() }
                main.post {
                    queue.clear(); main.removeCallbacks(drip)
                    lines = emptyList(); step = 0; waiting = true
                    startedAt = System.currentTimeMillis(); active = true
                    changed()
                }
            }
            is WorkStep.Waiting -> main.post { step = s.step; waiting = true; changed() }
            is WorkStep.Thought -> {
                val parts = cut(s.text)
                synchronized(kept) { kept += AppLocale.str(c, R.string.work_thought_prefix) + parts.take(KEEP_THOUGHT).joinToString("") }
                enqueue(parts.take(MAX_THOUGHT_LINES).map { Kind.THOUGHT to it })
            }
            is WorkStep.Said -> {
                synchronized(kept) { kept += s.text.replace("\n", " ") }
                enqueue(listOf(Kind.SAID to s.text.lineSequence().first { it.isNotBlank() }.trim()))
            }
            is WorkStep.Action -> {
                synchronized(kept) { kept += "› " + s.line }
                enqueue(listOf(Kind.ACTION to s.line))
            }
            is WorkStep.Done -> {
                if (s.actions > 0) {
                    val detail = synchronized(kept) { kept.takeLast(MAX_KEPT).joinToString("\n") { "- $it" } }
                    PhoneTimeline.body(PhoneTimeline.Body.STEPS, AppLocale.str(c, R.string.tl_steps, s.actions, duration(c, s.ms)), detail)
                }
                main.post {
                    queue.clear(); main.removeCallbacks(drip)
                    active = false; waiting = false
                    changed()
                }
            }
        }
    }

    private fun enqueue(items: List<Pair<Kind, String>>) {
        if (items.isEmpty()) return
        main.post {
            if (!active) return@post
            items.forEach { (k, t) -> queue.addLast(Line(nextId++, k, t)) }
            waiting = false
            if (!dripping) { dripping = true; main.post(drip) }
            changed()
        }
    }

    private var dripping = false

    /** One line at a time; faster when a backlog builds, so a long thought finishes before the next step's lines. */
    private val drip = object : Runnable {
        override fun run() {
            val l = queue.removeFirstOrNull()
            if (l == null || !active) { dripping = false; return }
            lines = (lines + l).takeLast(MAX_LIVE)
            changed()
            main.postDelayed(this, if (queue.size > 8) DRIP_MS / 3 else DRIP_MS)
        }
    }

    /**
     * `debug.worklog`: an invented turn through [on] — waiting, thinking, acting — for [seconds], then Done. For
     * looking at the views without a model. Blocks its own thread.
     */
    fun rehearse(seconds: Int) {
        val t0 = System.currentTimeMillis()
        onRehearse?.invoke(true)
        on(WorkStep.Start)
        var step = 0
        val script = listOf(
            "用户要查明天北京的天气。先打开浏览器，搜索框应该在页面顶部。" to "打开应用…",
            "浏览器打开了。搜索框在中间偏上，先点它，再输入关键词。" to "点一下…",
            "输入「北京明天天气」，然后提交。" to "输入文字…",
            "结果出来了，第一条是中国天气网。看温度那一栏：晴，最高 18 度。" to "看一眼屏幕…",
        )
        while (System.currentTimeMillis() - t0 < seconds * 1000L) {
            val (thought, action) = script[step % script.size]
            step++
            on(WorkStep.Waiting(step))
            Thread.sleep(2200)
            on(WorkStep.Thought(step, thought))
            Thread.sleep(900)
            on(WorkStep.Action(step, action))
            Thread.sleep(1600)
        }
        on(WorkStep.Done(step, step, System.currentTimeMillis() - t0))
        onRehearse?.invoke(false)
    }

    /** The task light a real turn turns on and off (the dispatcher's begin/end task), for [rehearse]. */
    @Volatile var onRehearse: ((Boolean) -> Unit)? = null

    /** 「思考中… 0:12」 while waiting on the model, 「第 3 步 · 0:24」 while acting. Main thread. */
    fun status(c: Context, now: Long = System.currentTimeMillis()): String {
        val s = ((now - startedAt) / 1000).coerceAtLeast(0)
        val clock = "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        return when {
            waiting && step <= 1 -> AppLocale.str(c, R.string.work_status_thinking, clock)
            waiting -> AppLocale.str(c, R.string.work_status_step_thinking, step, clock)
            else -> AppLocale.str(c, R.string.work_status_step, step, clock)
        }
    }

    private fun duration(c: Context, ms: Long): String {
        val secs = (ms / 1000).coerceAtLeast(1)
        return if (secs < 60) AppLocale.str(c, R.string.work_secs, secs.toInt())
        else AppLocale.str(c, R.string.work_mins_secs, (secs / 60).toInt(), (secs % 60).toInt())
    }

    /** Short lines out of a paragraph of reasoning: sentence ends, then a length cap. */
    internal fun cut(text: String): List<String> =
        Regex("""[^。！？!?\n]+[。！？!?]?""").findAll(text).map { it.value.trim() }
            .filter { it.length > 1 }
            .flatMap { s -> if (s.length <= LINE_CHARS) sequenceOf(s) else s.chunked(LINE_CHARS).asSequence() }
            .toList()

    private const val DRIP_MS = 160L
    private const val LINE_CHARS = 40
    private const val MAX_THOUGHT_LINES = 24
    private const val KEEP_THOUGHT = 2
    private const val MAX_KEPT = 60
    private const val MAX_LIVE = 40
}
