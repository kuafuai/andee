package net.kuafuai.andee.config

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything that has to happen "later": the pending todos and the quiet-hour
 * sweep timer.
 *
 * The cloud brain had a cron service polling a database. This device has no
 * process to poll with, so the clock is `AlarmManager` — and, crucially, the
 * only thing an alarm can do here is *wake the app up*; the work itself is
 * done by [net.kuafuai.andee.brain.LocalBrain] once it is awake. That is why
 * this file contains no decisions: it arms, it cancels, it re-arms, and it
 * hands a firing alarm to [ScreenBodyService] to deal with.
 *
 * Two deliberate simplifications:
 *
 *  * **No exact-alarm permission is ever requested** (decision B). If the user
 *    happens to have granted it, we use it; otherwise `setAndAllowWhileIdle`
 *    still fires under Doze but may be a few minutes late. The tool result
 *    says which one it got, and the prompt forbids claiming punctuality.
 *  * **Repeating todos re-arm themselves on every fire** rather than being
 *    handed to AlarmManager as a repeating alarm. This is the same shape as
 *    the cloud's `next_run_at` column, and it means a missed occurrence is
 *    something we can see and report instead of a schedule that silently
 *    drifts.
 */
object Scheduler {

    private const val TAG = "Body"

    const val ACTION_TODO = "net.kuafuai.andee.action.TODO_DUE"
    const val ACTION_SWEEP = "net.kuafuai.andee.action.SWEEP"
    const val ACTION_BOOT = "net.kuafuai.andee.action.BOOT"

    const val EXTRA_ID = "todo_id"

    /** One fixed request code: only one sweep timer exists at a time. */
    private const val SWEEP_REQUEST_CODE = 0x5EEB

    /**
     * The channel **id**, and it must never change.
     *
     * Android keys a channel by its id, so a different id is a *different*
     * channel — changing this string would leave a device with two "promises"
     * channels in settings instead of renaming the one it has. The id is
     * deliberately opaque and untranslated; the name shown to the user is
     * [channelName].
     */
    private const val NOTIFY_CHANNEL = "andee_promises"

    private const val NOTIFY_BASE = 4100

    /**
     * The channel name the user sees, in their language.
     *
     * **Known Android platform limitation, not a bug in this app.** A
     * notification channel's name is written into the system the *first* time
     * the channel is created, and from then on the system owns its own copy.
     * Calling `createNotificationChannel` again with a new name does not
     * rename it, and there is no API that does. So on a device where the
     * channel already exists, **switching the app language leaves the old
     * language's name showing** in system settings until the user clears the
     * app's data or uninstalls and reinstalls. Only a fresh install (or a
     * factory reset, which clears the app's data) picks up the new language.
     * This is why the name has to be read at creation time rather than baked
     * into a constant.
     */
    fun channelName(context: Context): String =
        AppLocale.str(context, R.string.sched_channel_name)

    fun am(context: Context): AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun exactAllowed(context: Context): Boolean = runCatching {
        am(context).canScheduleExactAlarms()
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // todos
    // ------------------------------------------------------------------

    /**
     * Arm the next occurrence of [todo]. No-op (and cancels) when the todo has
     * no future run left — a fired one-shot, a paused row, a dropped one.
     */
    fun armTodo(context: Context, todo: Notebook.Todo) {
        if (todo.status != "active" || todo.nextRunAt <= 0L) {
            cancelTodo(context, todo.id)
            return
        }
        val pending = todoIntent(context, todo.id)
        val trigger = todo.nextRunAt
        try {
            if (exactAllowed(context)) {
                am(context).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            } else {
                am(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
            }
            Log.i(
                TAG,
                "alarm armed for todo ${todo.id} at ${formatLocal(trigger)} " +
                    "(exact=${exactAllowed(context)})",
            )
        } catch (t: Throwable) {
            // SecurityException when a permission was revoked between the check
            // and the call. Losing the alarm silently would mean the promise is
            // forgotten; say so loudly instead.
            Log.e(TAG, "alarm arming failed for todo ${todo.id}", t)
        }
    }

    fun cancelTodo(context: Context, id: String) {
        runCatching {
            am(context).cancel(todoIntent(context, id))
        }
    }

    private fun todoIntent(context: Context, id: String): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_TODO)
            .putExtra(EXTRA_ID, id)
        // The id becomes the request code so two todos cannot overwrite each
        // other's alarm. FLAG_IMMUTABLE: nothing outside this app builds them.
        return PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ------------------------------------------------------------------
    // sweep timer
    // ------------------------------------------------------------------

    /**
     * (Re-)arm the "has the conversation gone quiet?" timer.
     *
     * Called at the end of every turn, so it is deliberately last-write-wins:
     * a conversation that keeps going never reaches its own quiet threshold,
     * because each turn pushes the timer out again.
     */
    fun armSweepIn(context: Context, delayMs: Long) {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_SWEEP)
        val pending = PendingIntent.getBroadcast(
            context, SWEEP_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = System.currentTimeMillis() + delayMs
        runCatching {
            // A quiet-hour check is not worth an exact alarm, and asking for
            // one for this would be a permission prompt to explain away.
            am(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }.onFailure { Log.w(TAG, "sweep timer arming failed", it) }
    }

    fun cancelSweep(context: Context) {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_SWEEP)
        val pending = PendingIntent.getBroadcast(
            context, SWEEP_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { am(context).cancel(pending) }
    }

    // ------------------------------------------------------------------
    // catch-up
    // ------------------------------------------------------------------

    /**
     * Bring the stored todos back in line with the clock, and return the ones
     * whose moment passed while nothing was listening.
     *
     * Runs on service start and on boot. Two cases:
     *
     *  * an occurrence fired while the app was dead → `last_status = missed`,
     *    and for a repeating todo the *next* occurrence is computed from now
     *    (missed runs are not replayed — a backlog of "it's 8am!" at 3pm is
     *    noise, not service);
     *  * a todo is due in the future but has no live alarm (device rebooted,
     *    app reinstalled, alarm lost) → re-armed.
     *
     * The returned list is what the caller turns into either a silent note to
     * the brain (service alive) or a notification (service gone).
     */
    fun catchUp(context: Context): List<Notebook.Todo> {
        val now = System.currentTimeMillis()
        val missed = mutableListOf<Notebook.Todo>()
        for (todo in Notebook.todos(context, "all")) {
            if (todo.status != "active") {
                cancelTodo(context, todo.id)
                continue
            }
            val due = todo.nextRunAt
            if (due <= 0L) continue
            if (due <= now) {
                val next = if (todo.mode == "cron" && todo.cronExpr.isNotBlank()) {
                    CronExpr.nextAfter(todo.cronExpr, now)
                } else {
                    null
                }
                val patched = Notebook.patchTodo(
                    context,
                    todo.id,
                    nextRunAt = next ?: 0L,
                    lastRunAt = now,
                    lastStatus = "missed",
                )
                if (patched != null) missed += patched
            } else {
                armTodo(context, todo)
            }
        }
        if (missed.isNotEmpty()) {
            Log.i(TAG, "catch-up: ${missed.size} todo(s) were missed while nothing was listening")
        }
        return missed
    }

    /** Clear the `missed` flag once it has been handed to the brain or the user. */
    fun markCaughtUp(context: Context, id: String) {
        Notebook.patchTodo(context, id, lastStatus = "caught_up")
    }

    // ------------------------------------------------------------------
    // notifications
    // ------------------------------------------------------------------

    /**
     * The only thing this app can do for a promise when its brain is not
     * running. It says what was promised and that it could not act on it —
     * a notification that just said "提醒：吃药" would be a lie about who is
     * talking.
     */
    fun notifyMissed(context: Context, todo: Notebook.Todo) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            if (mgr.getNotificationChannel(NOTIFY_CHANNEL) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        NOTIFY_CHANNEL,
                        // Only reached on the first install that posts a
                        // promise — see the known-limitation note on
                        // [channelName] for why a later language switch cannot
                        // reach a channel that already exists.
                        channelName(context),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = AppLocale.str(context, R.string.sched_channel_desc) }
                )
            }
            val text = if (todo.mode == "cron") {
                AppLocale.str(context, R.string.sched_notify_recurring, todo.what)
            } else {
                // `todo.what` is the user's own words — data, printed as stored.
                todo.what
            }
            val n = android.app.Notification.Builder(context, NOTIFY_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(AppLocale.str(context, R.string.sched_notify_title))
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(
                    AppLocale.str(context, R.string.sched_notify_body, text)
                ))
                .setAutoCancel(true)
                .build()
            mgr.notify(NOTIFY_BASE + (todo.id.hashCode() and 0xFF), n)
        }.onFailure {
            // Almost always a missing POST_NOTIFICATIONS on Android 13+. The
            // todo stays in the notebook either way, so this is a lost nudge,
            // not lost data.
            Log.w(TAG, "could not post the missed-promise notification", it)
        }
    }

    // ------------------------------------------------------------------
    // time helpers
    // ------------------------------------------------------------------

    private val LOCAL_FORMATS = listOf(
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "HH:mm",
    )

    /**
     * Parse what the model wrote. `HH:mm` is accepted and read as **today**,
     * or tomorrow when that moment has already passed — "remind me at 8" said
     * at 9pm means tomorrow morning, and making the model work that out is a
     * needless chance to get it wrong.
     */
    fun parseLocal(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.matches(Regex("\\d{1,2}:\\d{2}"))) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            return parseLocal("$today $s")?.let { ms ->
                if (ms <= System.currentTimeMillis()) ms + 86_400_000L else ms
            }
        }
        for (pattern in LOCAL_FORMATS) {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            runCatching { fmt.parse(s) }.getOrNull()?.let { return it.time }
        }
        return null
    }

    fun formatLocal(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
}
