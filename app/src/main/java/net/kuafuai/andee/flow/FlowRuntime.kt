package net.kuafuai.andee.flow

import org.json.JSONObject

/**
 * The short memory of a flow run — the piece that lets a stop become a
 * conversation instead of a dead end.
 *
 * A card run happens with no model in the room. When it stops, the service
 * hands the brain the report as a device turn; this object is what keeps that
 * report readable afterwards (`flow.status`) and, more importantly, what
 * stops the device from waking the brain forever on a flow it cannot fix.
 * Three takeovers on the same stuck step inside two minutes is the signal
 * that the repair is not working — after that the user gets an honest report
 * instead of another loop.
 *
 * In-memory only: like a half-finished recording, a run report belongs to
 * this session. Saved flows and their statistics live in the notebook.
 */
object FlowRuntime {

    private const val HEAL_WINDOW_MS = 120_000L
    private const val MAX_AUTO_HEALS = 2

    private val lock = Any()

    @Volatile
    private var last: JSONObject? = null

    /** Timestamps of automatic takeovers, keyed by `flowName:failedStep`. */
    private val heals = HashMap<String, MutableList<Long>>()

    fun record(report: JSONObject) {
        synchronized(lock) { last = JSONObject(report.toString()) }
    }

    fun lastReport(): JSONObject? = synchronized(lock) { last?.let { JSONObject(it.toString()) } }

    /**
     * Whether the device may wake the brain on this stop. Called once per
     * card-run failure, never for a model-driven `run_flow` (that turn is
     * already in the model's hands — it has to finish it itself).
     */
    fun allowAutoHeal(flow: String, failedAt: Int): Boolean = allow("$flow:$failedAt")

    /**
     * A separate budget for `ai` steps. A paused flow is not broken, but a
     * card run that keeps waking the brain on the same hand-off is still a
     * loop worth stopping: three times in two minutes and the user gets a
     * plain report instead of another identical wake-up.
     */
    fun allowAutoHandoff(flow: String, step: Int): Boolean = allow("$flow:handoff:$step")

    private fun allow(key: String): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        val list = heals.getOrPut(key) { mutableListOf() }
        list.removeAll { now - it > HEAL_WINDOW_MS }
        if (list.size >= MAX_AUTO_HEALS) return false
        list.add(now)
        return true
    }

    /** A completed run clears the debt, so tomorrow's stuck step starts fresh. */
    fun clearHeals(flow: String) {
        synchronized(lock) {
            heals.keys.removeAll { it.startsWith("$flow:") }
        }
    }

    fun healCount(flow: String, failedAt: Int): Int = synchronized(lock) {
        heals["$flow:$failedAt"]?.size ?: 0
    }
}
