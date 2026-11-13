package net.kuafuai.andee.desktop

import net.kuafuai.andee.desktop.dsh.DshWorkers

/** One line of a running dsh job's state, for the live card and for its task in 在办. */
object DshStatus {
    fun line(p: DshWorkers.Progress, now: Long, zh: Boolean): String {
        val s = ((now - p.startedAt) / 1000).coerceAtLeast(0)
        val time = "%d:%02d".format(s / 60, s % 60)
        val tok = if (p.tokens >= 1000) " · ${p.tokens / 1000}k tok" else ""
        return (if (zh) "第 ${p.step} 步 · $time" else "step ${p.step} · $time") + tok
    }

    /** The task row's status, three lines: where it is, its plan, what it is doing now. The phone's rows read the same. */
    fun forTask(p: DshWorkers.Progress, now: Long, zh: Boolean): String =
        net.kuafuai.andee.peer.RemoteProgressText.lines(
            p.step, p.startedAt, now, p.tokens, p.todos.map { it.text to it.status }, p.recent, p.thought, zh,
        ).asList().joinToString("\n")
}
