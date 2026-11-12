package net.kuafuai.andee.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.kuafuai.andee.desktop.dsh.DshWorkers

/**
 * The dsh workers running now, as the window draws them ([DshCard] at the foot of 动态, a line under the task in
 * 在办). Written from the worker's event thread, like [WorkLog]: a job handed off used to show one row 「交给 dsh」
 * and then nothing for twenty minutes, and the only way to look was a dsh window that read a running job as dead.
 */
object DshLive {
    var running by mutableStateOf<Map<String, DshWorkers.Progress>>(emptyMap())
        private set

    fun on(id: String, p: DshWorkers.Progress?) {
        synchronized(this) { running = if (p == null) running - id else running + (id to p) }
    }
}
