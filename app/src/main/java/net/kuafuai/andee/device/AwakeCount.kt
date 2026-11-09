package net.kuafuai.andee.device

/**
 * The bookkeeping half of [KeepAwake], kept free of Android so it can be tested with a fake clock.
 *
 * Two rules, each from a review of the first version (04341bc):
 *
 * - **Every [enter] re-arms the deadline.** The first version set it only on the 0→1 transition, so two
 *   apps' bursts overlapping (depth never back to 0) or one slow triage outlived it, and the lock lapsed
 *   while the count still said someone needed it.
 * - **A count past its deadline is stale and is reset by the next [enter].** With a pure counter one
 *   missed [exit] left the depth above zero for the life of the process, and every later [enter] took the
 *   "already held" branch — the lock was never taken again. The platform has already let the lock go at
 *   the deadline, so the count is the only thing left believing in it.
 *
 * The cost of the second rule is bounded and accepted: an [exit] owed by a caller from before the reset
 * arrives late and releases a moment early. That is a few seconds of settle on uptime, against the old
 * failure of never holding the lock again.
 */
class AwakeCount(
    private val maxMs: Long,
    private val clock: () -> Long,
    /** Hold the lock until [ms] from now; called again to push the deadline back. */
    private val acquire: (ms: Long) -> Unit,
    private val release: () -> Unit,
) {
    private val guard = Any()
    private var depth = 0
    private var deadline = 0L

    fun enter() = synchronized(guard) {
        val now = clock()
        if (depth > 0 && now >= deadline) depth = 0
        depth++
        deadline = now + maxMs
        acquire(maxMs)
    }

    /** Push the deadline back without counting another holder — before a step that may take a while. */
    fun refresh() = synchronized(guard) {
        if (depth == 0) return@synchronized
        deadline = clock() + maxMs
        acquire(maxMs)
    }

    fun exit() = synchronized(guard) {
        if (depth == 0) return@synchronized
        if (--depth > 0) return@synchronized
        deadline = 0L
        release()
    }

    /** Tests and logs. */
    fun depth(): Int = synchronized(guard) { depth }
}
