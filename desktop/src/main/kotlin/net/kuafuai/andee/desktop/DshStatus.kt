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

    /** The task row's line: the status, then the checklist item in hand or the newest action. */
    fun forTask(p: DshWorkers.Progress, now: Long, zh: Boolean): String {
        // No token count here: on a sidebar row it pushed out the part worth reading, what it is on.
        val s = ((now - p.startedAt) / 1000).coerceAtLeast(0)
        val head = (if (zh) "第 ${p.step} 步 · " else "step ${p.step} · ") + "%d:%02d".format(s / 60, s % 60)
        val what = p.current?.text ?: p.recent.lastOrNull().orEmpty()
        return head + if (what.isNotBlank()) " · $what" else ""
    }
}
