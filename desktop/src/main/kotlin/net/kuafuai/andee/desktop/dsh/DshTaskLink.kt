package net.kuafuai.andee.desktop.dsh

import net.kuafuai.andee.brain.Source
import net.kuafuai.andee.config.Tasks

/**
 * A job handed to dsh is a 「事」 until it is delivered: the task waits on the worker, and the report answers that
 * wait — the same shape as a friend's report (`Tasks.friendAnswered`), so the heartbeat, 在办 and `task get` see a
 * job in hand like anything else Andee has taken on. The wait is the device's to set and answer; the model only
 * names the task.
 */
class DshTaskLink(private val tasks: () -> Tasks) : DshWorkers.TaskLink {

    override fun check(taskId: String) {
        val t = tasks().get(taskId)
            ?: throw IllegalArgumentException("There is no task $taskId. task list shows the open ones; leave for_task out to have one opened.")
        if (!t.active) throw IllegalStateException("${t.id} is already ${t.status}. Leave for_task out to have a new one opened.")
    }

    override fun lastWorker(taskId: String) = tasks().lastWorker(taskId)

    override fun ofWorker(workerId: String) = tasks().ofWorker(workerId)?.id

    override fun open(title: String, trust: DshPlan.Trust): String? {
        val from = Source(kind = if (trust == DshPlan.Trust.USER) "user" else "self")
        return tasks().open(title, "", 0L, from, untrusted = false, origin = ORIGIN).getOrNull()?.id
    }

    override fun attach(taskId: String, w: DshWorkers.Worker) {
        tasks().wait(taskId, Tasks.Wait(kind = "worker", name = w.id, asked = w.title)).getOrThrow()
    }

    override fun answered(w: DshWorkers.Worker, summary: String, seen: Boolean): String =
        tasks().workerAnswered(w.id, summary, seen).joinToString("\n\n") { tasks().forPrompt(it) }

    companion object {
        /** [Tasks.Task.origin] of a task the device opened for a job handed over with none named. */
        const val ORIGIN = "dsh"
    }
}
