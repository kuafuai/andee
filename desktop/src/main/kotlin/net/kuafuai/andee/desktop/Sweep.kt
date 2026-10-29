package net.kuafuai.andee.desktop

import net.kuafuai.andee.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The quiet-hour review, on the Mac: when the conversation has been silent for a
 * while, the brain looks back over it, keeps what is worth keeping in the notebook,
 * and folds finished stretches into topics — which is where the sidebar's topic
 * list comes from. Nothing else on the desktop ever calls `runSweep`, so without
 * this the list would stay empty for ever.
 *
 * Same gates as the phone's onSweepTimer, for the same reasons: it is the user's
 * own key paying for it, so it is on a daily cap; it waits for real silence
 * (measured from the *user's* last words, which `touchActivity` stamps); and it
 * never fires while a meeting holds the microphone or a turn is running.
 *
 * A sweep that is skipped (fewer than six messages) must not be retried every
 * minute: [lastAttemptAt] remembers that this stretch of silence was already
 * tried, and the next try waits for new activity.
 */
object Sweep {

    private const val TAG = "Sweep"
    private const val DEFAULT_QUIET_MINUTES = 20
    private const val DEFAULT_DAILY_CAP = 12
    private const val CHECK_EVERY_S = 60L

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sweep").apply { isDaemon = true } }
    private val metaFile get() = AppDirs.file("sweep.json")
    private val lock = Any()

    /**
     * On disk, like the phone's `Notebook.lastSweepAt`: kept in memory only, it was forgotten by every restart, and the
     * first tick after one reviewed a conversation that had already been reviewed — measured 10-29, the same stretch
     * gone over twice, every write in it made again.
     */
    private var lastAttemptAt: Long
        get() = runCatching { AppDirs.file("last_sweep_attempt").readText().trim().toLong() }.getOrDefault(0L)
        set(v) { runCatching { AppDirs.file("last_sweep_attempt").writeText(v.toString()) } }

    fun enabled(): Boolean = Settings.get("sweep").lowercase() != "off"
    private fun quietMs(): Long =
        (Settings.get("sweep_quiet_minutes").toIntOrNull() ?: DEFAULT_QUIET_MINUTES).coerceIn(5, 240) * 60_000L
    private fun dailyCap(): Int =
        (Settings.get("sweep_daily_cap").toIntOrNull() ?: DEFAULT_DAILY_CAP).coerceIn(0, 100)

    fun start() {
        exec.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { Log.w(TAG, "tick failed", it) } },
            CHECK_EVERY_S, CHECK_EVERY_S, TimeUnit.SECONDS)
    }

    private fun lastActivity(): Long =
        runCatching { AppDirs.file("last_activity").readText().trim().toLong() }.getOrDefault(0L)

    /**
     * Whether a review should start now. Pure, because the retry rule is the part that is
     * easy to get wrong and expensive to get wrong: without the `activity <= lastAttemptAt`
     * line a conversation too short to review is retried every minute for ever, each time
     * waking the brain.
     */
    internal fun shouldSweep(
        now: Long, activity: Long, lastAttemptAt: Long, quietMs: Long,
        countToday: Int, cap: Int, enabled: Boolean, meeting: Boolean,
    ): Boolean = when {
        !enabled || meeting -> false
        activity == 0L -> false                      // the user has never spoken
        activity <= lastAttemptAt -> false           // this silence was already tried
        now - activity < quietMs -> false            // not quiet long enough yet
        countToday >= cap -> false                   // today's budget is spent
        else -> true
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        if (!shouldSweep(now, lastActivity(), lastAttemptAt, quietMs(), countToday(), dailyCap(), enabled(), Meeting.isActive())) return
        lastAttemptAt = now
        Log.i(TAG, "quiet for a while; reviewing the conversation")
        Assistant.runSweep()
    }

    /** The "整理" button: review now, quiet or not. Still respects the busy and key checks inside the brain. */
    fun now() {
        lastAttemptAt = System.currentTimeMillis()
        Assistant.runSweep()
    }

    /** A sweep finished: count it against today's cap. Called from the brain's onSweepSpent. */
    fun spent(tokens: Int) = synchronized(lock) {
        val day = today()
        val o = read()
        val sameDay = o.optString("day") == day
        o.put("day", day)
            .put("count", (if (sameDay) o.optInt("count") else 0) + 1)
            .put("tokens", (if (sameDay) o.optInt("tokens") else 0) + tokens)
            .put("last", System.currentTimeMillis())
        runCatching { metaFile.writeText(o.toString()) }
        Log.i(TAG, "sweep done (${o.optInt("count")} today, ~$tokens tokens)")
    }

    fun countToday(): Int = synchronized(lock) { read().let { if (it.optString("day") == today()) it.optInt("count") else 0 } }
    fun tokensToday(): Int = synchronized(lock) { read().let { if (it.optString("day") == today()) it.optInt("tokens") else 0 } }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    private fun read(): JSONObject = runCatching { JSONObject(metaFile.readText()) }.getOrDefault(JSONObject())
}
