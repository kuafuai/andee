package net.kuafuai.andee.peer

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * What a worker on another device is doing now: the same facts the Mac shows for its own (step, time, its checklist, the
 * newest actions), sent to the device that handed the job over so 在办 can show them there too.
 *
 * A job handed off used to be one row and then nothing for twenty minutes; on the Mac that is fixed by `DshLive`, and this
 * is the same for a phone. Memory only: it describes a process that is alive somewhere else.
 */
object RemoteProgress {

    data class Todo(val text: String, val status: String)

    data class State(
        val worker: String,
        val title: String,
        val startedAt: Long,
        val step: Int = 0,
        val recent: List<String> = emptyList(),
        val thought: String = "",
        val todos: List<Todo> = emptyList(),
        val tokens: Long = 0,
        val files: Int = 0,
        /** When this was last heard, on this device's clock: a job not heard from for a while is shown as quiet, not as dead. */
        val heardAt: Long = System.currentTimeMillis(),
    ) {
        val current: Todo? get() = todos.firstOrNull { it.status == "in_progress" } ?: todos.firstOrNull { it.status == "pending" }
        val done: Int get() = todos.count { it.status == "completed" }

        fun toJson(): JSONObject = JSONObject().put("type", "remote.progress").put("worker", worker).put("title", title)
            .put("started", startedAt).put("step", step).put("recent", JSONArray(recent)).put("thought", thought.take(200))
            .put("todos", JSONArray(todos.map { JSONObject().put("text", it.text.take(120)).put("status", it.status) }))
            .put("tokens", tokens).put("files", files)
    }

    /** Parse a `remote.progress` message. */
    fun parse(m: JSONObject): State = State(
        worker = m.optString("worker"), title = m.optString("title"), startedAt = m.optLong("started"), step = m.optInt("step"),
        recent = m.optJSONArray("recent")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
        thought = m.optString("thought"),
        todos = m.optJSONArray("todos")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Todo(it.optString("text"), it.optString("status")) } }.orEmpty(),
        tokens = m.optLong("tokens"), files = m.optInt("files"),
    )

    private val live = ConcurrentHashMap<String, State>()

    /** Workers running on the other device now, newest first. */
    fun running(): List<State> = live.values.sortedByDescending { it.startedAt }

    fun of(worker: String): State? = live[worker.trim().removePrefix("#")]

    fun update(s: State) { if (s.worker.isNotEmpty()) live[s.worker] = s.copy(heardAt = System.currentTimeMillis()) }

    /** The job ended (its report is on its way, or it was stopped). */
    fun end(worker: String) { live.remove(worker.trim().removePrefix("#")) }

    /** Everything, when the link to the computer is gone for good (unpaired): nothing here can still be heard from. */
    fun clear() = live.clear()

    /** One line for a task's row in 在办: step, time, then what it is on. The same shape as the Mac's `DshStatus.forTask`. */
    fun line(s: State, now: Long, zh: Boolean): String {
        val sec = ((now - s.startedAt) / 1000).coerceAtLeast(0)
        val head = (if (zh) "第 ${s.step} 步 · " else "step ${s.step} · ") + "%d:%02d".format(sec / 60, sec % 60)
        val what = s.current?.text ?: s.recent.lastOrNull().orEmpty()
        return head + if (what.isNotBlank()) " · $what" else ""
    }
}
