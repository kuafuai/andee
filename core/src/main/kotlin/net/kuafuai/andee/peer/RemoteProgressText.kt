package net.kuafuai.andee.peer

/**
 * A running worker's status as three short lines, for a row in 在办 on either body: where it is (step and time), where it is
 * in its own checklist (or its thought, with none), and the newest thing it did. The same words on the Mac and the phone,
 * so the two cannot drift. Lines may be empty (nothing yet); callers cut each to one line with an ellipsis.
 */
object RemoteProgressText {

    data class Lines(val where: String, val plan: String, val doing: String) {
        fun asList(): List<String> = listOf(where, plan, doing).filter { it.isNotBlank() }
    }

    fun lines(
        step: Int, startedAt: Long, now: Long, tokens: Long, todos: List<Pair<String, String>>, recent: List<String>, thought: String, zh: Boolean,
    ): Lines {
        val s = ((now - startedAt) / 1000).coerceAtLeast(0)
        val time = "%d:%02d".format(s / 60, s % 60)
        val tok = if (tokens >= 1000) " · ${tokens / 1000}k tok" else ""
        val where = (if (zh) "第 $step 步 · $time" else "step $step · $time") + tok
        val done = todos.count { it.second == "completed" }
        val current = todos.firstOrNull { it.second == "in_progress" } ?: todos.firstOrNull { it.second == "pending" }
        val plan = when {
            current != null -> (if (zh) "清单 $done/${todos.size} · " else "checklist $done/${todos.size} · ") + current.first
            thought.isNotBlank() -> thought.lineSequence().first { it.isNotBlank() }.trim()
            step == 0 -> if (zh) "正在起步…" else "getting started…"
            else -> ""
        }
        val doing = recent.lastOrNull().orEmpty().let { if (it.isBlank()) "" else "› $it" }
        return Lines(where, plan, doing)
    }

    fun of(s: RemoteProgress.State, now: Long, zh: Boolean) =
        lines(s.step, s.startedAt, now, s.tokens, s.todos.map { it.text to it.status }, s.recent, s.thought, zh)
}
