package net.kuafuai.andee.peer

import net.kuafuai.andee.brain.Source
import net.kuafuai.andee.config.Tasks
import org.json.JSONObject

/**
 * A job handed to the agent on another device is a 「事」 on this device until it is delivered, exactly as a job handed to
 * a dsh on this one is (`DshTaskLink` on the Mac): the task waits on the worker, the worker's report answers that wait, and
 * 在办 shows it. The device sets the wait and answers it; the model only names the task.
 *
 * The record lives here, on the device whose Andee decided to send the job. The computer holds the worker and its folder,
 * not the task (`Worker.origin`).
 */
class RemoteAgentTasks(private val tasks: () -> Tasks) {

    /**
     * Before the call: the 「事」 this job is for — the one the model named, else the one the continued worker was on, else
     * a new one. Throws, with the reason the model reads, when it names one that is not open.
     */
    fun forJob(args: JSONObject, fromUser: Boolean): String {
        val named = args.optString("for_task").trim()
        if (named.isNotEmpty()) {
            val t = tasks().get(named) ?: throw IllegalArgumentException("There is no task $named. task list shows the open ones; leave for_task out to have one opened.")
            if (!t.active) throw IllegalStateException("${t.id} is already ${t.status}. Leave for_task out to have a new one opened.")
            return t.id
        }
        val cont = args.optString("continue").trim().removePrefix("#")
        if (cont.isNotEmpty()) tasks().ofWorker(cont)?.let { return it.id }
        val title = args.optString("title").trim().ifEmpty { args.optString("task").trim().lineSequence().first().take(40) }
        val from = Source(kind = if (fromUser) "user" else "self")
        return tasks().open(title, "", 0L, from, untrusted = false, origin = ORIGIN).getOrNull()?.id.orEmpty()
    }

    /** The call went through: [taskId] now waits on worker [workerId] on the computer. */
    fun attach(taskId: String, workerId: String, title: String) {
        if (taskId.isEmpty()) return
        tasks().wait(taskId, Tasks.Wait(kind = "worker", name = workerId, asked = title)).getOrThrow()
    }

    /** A task this class opened for a job that then failed to start: let it go, so it is not left looking like work in hand. */
    fun dropOpened(taskId: String, why: String) {
        val t = tasks().get(taskId) ?: return
        if (t.origin == ORIGIN && t.waits.none { it.kind == "worker" }) tasks().close(taskId, "dropped", why)
    }

    /** The worker's report arrived: answer its wait. Returns the records of what it answered, for the report's turn. */
    fun answered(workerId: String, summary: String, seen: Boolean = false): String =
        tasks().workerAnswered(workerId, summary, seen).joinToString("\n\n") { tasks().forPrompt(it) }

    /** What the tool result says about the task, added to the call's own result. */
    fun annotate(result: JSONObject, taskId: String): JSONObject = result.apply {
        if (taskId.isNotEmpty()) put("task", taskId).put(
            "note",
            optString("note") + " The task $taskId waits on it; close it (task done) once you have delivered what came back.",
        )
    }

    companion object {
        /** [Tasks.Task.origin] of a task the device opened for a job handed to the computer with none named. */
        const val ORIGIN = "dsh"
    }
}
