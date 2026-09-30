package net.kuafuai.andee.config

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The alarm clock, and the only entry point into this app that does not come
 * from the user.
 *
 * It decides nothing. A firing alarm is turned into either a wake-up call for
 * the running service ([net.kuafuai.andee.ScreenBodyService.wakeForTodo] /
 * [net.kuafuai.andee.ScreenBodyService.onSweepTimer]) or, when there is no
 * service to call, into a notification that says plainly that the promise was
 * not acted on. The state transitions themselves live in [Notebook] and
 * [Scheduler] so that this class stays a dispatcher — an alarm firing during a
 * reboot is exactly the situation where "clever" code loses someone's data.
 */
class AlarmReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "Body"

        /** Some OEMs send this instead of BOOT_COMPLETED. */
        const val QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Scheduler.ACTION_TODO ->
                fire(context, intent.getStringExtra(Scheduler.EXTRA_ID))

            Scheduler.ACTION_SWEEP ->
                // The quiet-hour check needs the clock of the last message and
                // the right to start a turn; both live in the service. No
                // service means no brain to sweep for, and the timer gets
                // re-armed when one starts.
                net.kuafuai.andee.ScreenBodyService.get()?.onSweepTimer()
                    ?: Log.i(TAG, "sweep timer fired with no service; ignored")

            Intent.ACTION_BOOT_COMPLETED, QUICKBOOT ->
                onBoot(context)

            else ->
                Log.w(TAG, "AlarmReceiver got an unexpected action: ${intent.action}")
        }
    }

    /**
     * A todo came due.
     *
     * The re-arm happens **before** the turn is started, so that a brain that
     * takes three minutes to fail cannot cost the schedule its next
     * occurrence. A repeating todo whose next run cannot be computed (the
     * expression somehow went bad) is left with no next run rather than being
     * dropped — visible in `todos list`, fixable by the model.
     */
    private fun fire(context: Context, id: String?) {
        if (id.isNullOrBlank()) {
            Log.w(TAG, "todo alarm without an id")
            return
        }
        val todo = Notebook.todoById(context, id)
        if (todo == null) {
            Log.w(TAG, "todo alarm for unknown id=$id")
            return
        }
        if (todo.status != "active") {
            Log.i(TAG, "todo ${todo.id} is ${todo.status}; alarm ignored")
            return
        }

        val now = System.currentTimeMillis()
        val next = if (todo.mode == "cron" && todo.cronExpr.isNotBlank()) {
            CronExpr.nextAfter(todo.cronExpr, now)
        } else {
            null
        }

        val service = net.kuafuai.andee.ScreenBodyService.get()
        val patched = Notebook.patchTodo(
            context,
            todo.id,
            nextRunAt = next ?: 0L,
            lastRunAt = now,
            lastStatus = if (service != null) "fired" else "missed",
        ) ?: todo

        if (next != null) Scheduler.armTodo(context, patched) else Scheduler.cancelTodo(context, todo.id)

        if (service != null) {
            Log.i(TAG, "todo due: ${todo.what.take(60)} — handing it to the service")
            service.wakeForTodo(patched)
        } else {
            // Nothing is running, so nothing can act on it. Say so — a silent
            // drop here is the exact failure the whole feature exists to
            // prevent.
            Log.w(TAG, "todo due with no service; posting a notification instead")
            Scheduler.notifyMissed(context, patched)
        }
    }

    /**
     * After a reboot.
     *
     * Every live todo has lost its alarm (AlarmManager is not persistent), and
     * whatever came due while the device was off never fired at all.
     * [Scheduler.catchUp] re-arms the future and reports the past; the past
     * becomes a notification here because at boot time the service is never
     * up yet.
     */
    private fun onBoot(context: Context) {
        Log.i(TAG, "boot: re-arming todos and the sweep timer")
        val missed = runCatching { Scheduler.catchUp(context) }.getOrElse {
            Log.e(TAG, "catch-up after boot failed", it)
            emptyList()
        }
        for (todo in missed.take(3)) Scheduler.notifyMissed(context, todo)
        // The sweep timer is deliberately NOT armed here: it is armed at the
        // end of a turn, and arming it at boot would sweep a conversation the
        // user has not had yet.
    }
}
