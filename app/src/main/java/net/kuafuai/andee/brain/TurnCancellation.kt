package net.kuafuai.andee.brain

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger

/** Cancels work owned by the previous turn, including calls still waiting on an executor. */
internal class TurnCancellation {
    private val generation = AtomicInteger(0)
    private val lock = Any()
    private val pending = mutableSetOf<() -> Unit>()

    fun isCurrent(gen: Int): Boolean = gen == generation.get()

    fun next(): Int = synchronized(lock) {
        val gen = generation.incrementAndGet()
        val old = pending.toList()
        pending.clear()
        old.forEach { runCatching(it) }
        gen
    }

    fun ifCurrent(gen: Int, action: () -> Unit): Unit = synchronized(lock) {
        if (isCurrent(gen)) action()
    }

    fun onCancel(gen: Int, cancel: () -> Unit): Closeable = synchronized(lock) {
        if (isCurrent(gen)) pending.add(cancel) else cancel()
        Closeable { synchronized(lock) { pending.remove(cancel) } }
    }

    fun <T> submit(gen: Int, executor: Executor, action: () -> T): Future<T> {
        val task = FutureTask<T> {
            if (!isCurrent(gen)) throw CancellationException("Task stopped")
            action()
        }
        val registration = onCancel(gen) { task.cancel(true) }
        try {
            executor.execute {
                try {
                    task.run()
                } finally {
                    // Keep tracking after a wait times out: the tool can still be running.
                    registration.close()
                }
            }
        } catch (t: Throwable) {
            registration.close()
            task.cancel(true)
            throw t
        }
        return task
    }
}
