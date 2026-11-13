package net.kuafuai.andee.peer

/**
 * The jobs the turn now running has handed to an agent (`dsh.delegate`), so the answer that closes the turn can carry them: the
 * job's card hangs under Andee's words as one piece, instead of a grey line of its own somewhere above (owner's call, 11-13).
 *
 * Reset at the start of every turn, filled as each `dsh.delegate` returns, taken once by the host when it writes the answer.
 * Loop thread writes, whichever thread writes the answer reads; a turn's own jobs only.
 */
object HandedJobs {
    private val jobs = java.util.Collections.synchronizedList(mutableListOf<String>())

    fun reset() = jobs.clear()

    fun note(worker: String) {
        val w = worker.trim().removePrefix("#")
        if (w.isNotEmpty() && w !in jobs) jobs += w
    }

    /** The workers handed work this turn, once: a second call (the next answer) starts empty. */
    fun take(): List<String> = synchronized(jobs) { jobs.toList().also { jobs.clear() } }
}
