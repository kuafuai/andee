package net.kuafuai.andee.device

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Last foreground app, fed from the accessibility event stream. Cheap context. */
object DeviceState {
    @Volatile
    var foregroundPkg: String? = null
}

/**
 * Zero/low-friction device ACTUATION + info: launch apps, volume, brightness,
 * open URLs, set alarms, hardware facts, one-shot sensors. Everything here is
 * either permission-free or a plain intent handoff to a system app.
 *
 * Lives in `device.*` (not `screen.*`) — none of this folds the card.
 */
class DeviceActionsController(private val context: Context) {

    // ---- launch app ----------------------------------------------------

    fun launchApp(pkg: String?, label: String?): JSONObject {
        val target = when {
            !pkg.isNullOrEmpty() -> pkg
            !label.isNullOrEmpty() -> resolveLabel(label)
                ?: return JSONObject().put("error", "no installed app matches label '$label'")
            else -> return JSONObject().put("error", "launch requires pkg or label")
        }
        val intent = context.packageManager.getLaunchIntentForPackage(target)
            ?: return JSONObject().put("error", "app '$target' has no launchable activity")
        // The destination must be resolvable BEFORE the pad opens, so the pad
        // can never strand the user in a stage that has nothing to hand on.
        if (intent.resolveActivity(context.packageManager) == null) {
            return JSONObject().put("error", "app '$target' cannot be opened right now")
        }
        // HyperOS aborts ANY Activity start from a background app — ours
        // included — EXCEPT when the launcher is the foreground app (measured
        // on the test tablet: from Settings, every start aborts with
        // "Abort background activity starts"; from the home screen it goes
        // through). So when the screen is on some other app, go home first.
        // The home trip must be GLOBAL_ACTION_HOME: the ROM executes that as
        // the system (uid 0) and never aborts it, unlike a HOME intent which
        // is just another background start. Already home or already on the
        // target app → no trip needed (the pad re-launch of the current
        // foreground app still settles, because the poll below reads the
        // target package either way).
        val home = launcherPkg()
        val current = DeviceState.foregroundPkg
        if (!current.isNullOrEmpty() && current != home && target != current && !current.startsWith("$target.")) {
            homeFromBackground(home)
        }
        // The pad is our own Activity, and with the launcher in front its
        // start is allowed; it hands the intent on from the foreground. The
        // intent itself carries no NEW_TASK; the pad's does. See
        // [LaunchPadActivity]. Then VERIFY: poll for the target package
        // actually reaching the foreground, and retry once from the launcher
        // if the start was aborted — "success while nothing opened" was the
        // whole bug this path replaces. Xiaomi's chained-launch guard is a
        // third outcome: it asks THE USER whether to allow the open, but
        // Andee has the user's standing permission to drive the screen — so
        // tap 允许 on its own dialog and keep waiting, instead of asking the
        // user or looping.
        var opened = false
        var guardShown = false
        for (attempt in 1..2) {
            guardShown = false
            runCatching { LaunchPadActivity.show(context, intent) }
            waitForeground { now ->
                when {
                    now == MIUI_GUARD_PKG -> {
                        guardShown = true
                        true
                    }
                    now == target || now.startsWith("$target.") -> true
                    else -> false
                }
            }
            if (arrivedAt(target)) {
                opened = true
                break
            }
            if (guardShown && tapGuardConfirm()) {
                // The dialog said yes — the system lets the pending start
                // through. Keep waiting for the target; no new pad needed.
                waitForeground { now -> now == target || now.startsWith("$target.") }
                if (arrivedAt(target)) {
                    opened = true
                    break
                }
                break
            }
            if (guardShown) break
            homeFromBackground(home)
        }
        return when {
            opened -> JSONObject().put("launched", target).put("via", "pad")
            guardShown -> JSONObject().put(
                "error",
                "小米安全中心的链式启动确认框挡在屏幕上（正在询问是否允许打开 '$target'），自动点击「允许」" +
                    "没有成功 —— 请用户在弹窗上点一下「允许」，点完重新运行即可。不要反复 launch_app 空转。",
            )
            else -> JSONObject().put(
                "error",
                "the system blocked the background launch of '$target' (HyperOS aborts background activity " +
                    "starts) — open it by hand, then the flow can resume from the next step.",
            )
        }
    }

    /** What "home" resolves to — the default launcher's package. */
    private fun launcherPkg(): String =
        runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                0,
            )?.activityInfo?.packageName
        }.getOrNull().orEmpty()

    private fun arrivedAt(target: String): Boolean {
        val now = DeviceState.foregroundPkg ?: return false
        return now == target || now.startsWith("$target.")
    }

    /**
     * Tap the 「允许」 button on Xiaomi's chained-launch confirmation dialog.
     * Andee has the user's standing permission to drive the screen, and the
     * dialog is only there because of a launch the user already asked for —
     * clicking its allow button is finishing the user's own request, not a
     * new decision. The dialog may not have drawn its buttons yet: settle,
     * then walk the accessibility tree for a clickable node whose text is an
     * allow-form in either language.
     */
    private fun tapGuardConfirm(): Boolean {
        val svc = net.kuafuai.andee.ScreenBodyService.get() ?: return false
        val allowTexts = setOf("允许", "始终允许", "允许一次", "Allow", "Always allow", "Allow once")
        var found = false
        // Two passes: the dialog is animating in when the guard package first
        // shows up, and its buttons may not be in the tree yet.
        repeat(2) {
            if (found) return@repeat
            if (it > 0) {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            val root = svc.rootInActiveWindow ?: return@repeat
            val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            queue.addLast(root)
            while (queue.isNotEmpty() && !found) {
                val n = queue.removeFirst()
                val text = n.text?.toString()?.trim().orEmpty()
                if (n.isClickable && text in allowTexts) {
                    found = n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                }
                if (!found) {
                    for (i in 0 until n.childCount) n.getChild(i)?.let { queue.addLast(it) }
                }
            }
            // The dialog is gone the moment the tap lands; the tree nodes are
            // recycled by the framework. One dangling reference per attempt
            // is cheaper than a full-tree recycle walk while clicking.
        }
        return found
    }

    /**
     * The home trip before a background launch. GLOBAL_ACTION_HOME is the only
     * route the ROM will not abort; the HOME intent fallback exists for the
     * moment the accessibility bridge is gone, and may itself be aborted on
     * strict ROMs. Then wait for the launcher to actually BE in front — the
     * abort rule keys on the foreground app at start time, and a cold launcher
     * takes longer than any fixed pause (measured: 800ms was not enough after
     * a cold HOME, the pad still got aborted).
     */
    private fun homeFromBackground(home: String) {
        val ok = runCatching {
            net.kuafuai.andee.ScreenBodyService.get()?.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME,
            )
        }.getOrNull()
        if (ok != true) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
        }
        waitForeground { now -> now == home }
        // The abort window outlives the launcher's come-forward: measured on
        // the test tablet, a pad taking off <1s after HOME is aborted while
        // the same pad 5s after HOME goes through (zero aborts). Wait the
        // window out before the pad starts.
        try {
            Thread.sleep(HOME_SETTLE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Poll the foreground package until [predicate] is satisfied or the wait
     * times out. The accessibility event stream feeds [DeviceState], which
     * settles within a few hundred milliseconds of a real window change; a
     * launch of a cold app is slow (measured ~10s), a launcher come-forward
     * is fast.
     */
    private fun waitForeground(predicate: (String) -> Boolean) {
        val deadline = System.currentTimeMillis() + FOREGROUND_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            DeviceState.foregroundPkg?.let(predicate)?.let { if (it) return }
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun resolveLabel(label: String): String? {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in pm.queryIntentActivities(launcher, 0)) {
            val l = runCatching { ri.loadLabel(pm).toString() }.getOrDefault("")
            if (l.equals(label, ignoreCase = true)) return ri.activityInfo.packageName
        }
        return null
    }

    // ---- volume ----------------------------------------------------

    /** level == null && mute == null → read-only. */
    fun volume(level: Int?, mute: Boolean?): JSONObject {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (mute == true) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        } else if (mute == false) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        } else if (level != null) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
        }
        return JSONObject()
            .put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("max", max)
            .put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    // ---- brightness ----------------------------------------------------

    fun brightness(v: Int?): JSONObject {
        val cr = context.contentResolver
        if (v != null) {
            // Manual mode first, or the write is decoration on auto screens.
            runCatching {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            }
            val ok = runCatching {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(1, 255))
            }.isSuccess
            if (!ok) return JSONObject().put("error", "write failed — needs WRITE_SETTINGS grant (Settings → Special app access)")
        }
        return JSONObject().put("brightness", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1))
    }

    // ---- open url / dial / map ------------------------------------------

    /** Handles http(s), tel:, geo: — the URL's scheme decides where it lands. */
    fun openUrl(url: String): JSONObject {
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
            ?: return JSONObject().put("error", "unparseable url")
        if (parsed.scheme !in setOf("http", "https", "tel", "geo", "mailto")) {
            return JSONObject().put("error", "unsupported scheme '${parsed.scheme}' (allowed: http/https/tel/geo/mailto)")
        }
        val intent = Intent(Intent.ACTION_VIEW, parsed)
        // Same pre-flight as launchApp: no handler means the pad would open
        // with nothing to hand on.
        if (intent.resolveActivity(context.packageManager) == null) {
            return JSONObject().put("error", "no app on this device can open that link")
        }
        // Same background-start rule as launchApp: HyperOS aborts any start
        // whose take-off point is not the launcher, so go home first (the
        // accessibility route, which is never aborted) and let the pad open
        // the link from there. Then verify the screen actually LEFT the
        // launcher — and retry once if the start was aborted. Xiaomi's
        // chained-launch guard is tapped through, not looped on: same rule
        // as launchApp, the user already asked for the open.
        val home = launcherPkg()
        val current = DeviceState.foregroundPkg
        if (!current.isNullOrEmpty() && current != home) homeFromBackground(home)
        var opened = false
        var guardShown = false
        for (attempt in 1..2) {
            guardShown = false
            runCatching { LaunchPadActivity.show(context, intent) }
            waitForeground { now ->
                when {
                    now == MIUI_GUARD_PKG -> {
                        guardShown = true
                        true
                    }
                    now != home && now != context.packageName -> true
                    else -> false
                }
            }
            val now = DeviceState.foregroundPkg
            if (!now.isNullOrEmpty() && now != home && now != context.packageName && now != MIUI_GUARD_PKG) {
                opened = true
                break
            }
            if (guardShown && tapGuardConfirm()) {
                waitForeground { fg -> !fg.isNullOrEmpty() && fg != home && fg != context.packageName && fg != MIUI_GUARD_PKG }
                val after = DeviceState.foregroundPkg
                if (!after.isNullOrEmpty() && after != home && after != context.packageName && after != MIUI_GUARD_PKG) {
                    opened = true
                    break
                }
                break
            }
            if (guardShown) break
            homeFromBackground(home)
        }
        return when {
            opened -> JSONObject().put("opened", url).put("via", "pad")
            guardShown -> JSONObject().put(
                "error",
                "小米安全中心的链式启动确认框挡在屏幕上（正在询问是否允许打开这个链接），自动点击「允许」" +
                    "没有成功 —— 请用户在弹窗上点一下「允许」，点完重新打开即可。不要反复重试空转。",
            )
            else -> JSONObject().put(
                "error",
                "the system blocked opening this link in the background (HyperOS aborts background activity " +
                    "starts) — open it by hand.",
            )
        }
    }

    // ---- alarm ----------------------------------------------------

    fun setAlarm(hour: Int, minute: Int, message: String?): JSONObject {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message ?: "")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        if (i.resolveActivity(context.packageManager) == null) {
            return JSONObject().put("error", "no clock app accepted the request")
        }
        // Same background-start rule as launchApp/openUrl: take off from the
        // launcher or HyperOS aborts the start and the alarm silently is not
        // set. The clock app with EXTRA_SKIP_UI may not come forward at all,
        // so this one is fire-and-forget: the pad makes the start legal, and
        // there is no foreground change to poll for.
        val home = launcherPkg()
        val current = DeviceState.foregroundPkg
        if (!current.isNullOrEmpty() && current != home) homeFromBackground(home)
        return runCatching {
            LaunchPadActivity.show(context, i)
            JSONObject().put("alarm_set", "%02d:%02d".format(hour, minute)).put("message", message ?: "")
        }.fold(
            { it },
            { JSONObject().put("error", "no clock app accepted the request: ${it.message}") },
        )
    }

    // ---- device facts ----------------------------------------------------

    fun deviceInfo(): JSONObject {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val data = StatFs(Environment.getDataDirectory().path)
        return JSONObject()
            .put("model", android.os.Build.MODEL)
            .put("brand", android.os.Build.BRAND)
            .put("android", android.os.Build.VERSION.RELEASE)
            .put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("ram_total_mb", mem.totalMem / 1048576)
            .put("ram_avail_mb", mem.availMem / 1048576)
            .put("storage_total_gb", data.totalBytes / 1073741824.0.let { Math.round(it) })
            .put("storage_avail_gb", data.availableBytes / 1073741824.0.let { Math.round(it) })
    }

    // ---- one-shot sensors ----------------------------------------------------

    fun sensors(timeoutMs: Long = 1500): JSONObject {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val out = JSONObject()

        fun readOnce(sensor: Sensor?, key: String, transform: (FloatArray) -> JSONArray) {
            if (sensor == null) { out.put(key, "no sensor"); return }
            val latch = CountDownLatch(1)
            val got = arrayOfNulls<FloatArray>(1)
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    got[0] = e.values.clone(); latch.countDown(); sm.unregisterListener(this)
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!ok) sm.unregisterListener(l)
            out.put(key, if (ok && got[0] != null) transform(got[0]!!) else "no reading")
        }

        readOnce(sm.getDefaultSensor(Sensor.TYPE_LIGHT), "light_lux") { JSONArray(listOf(it[0])) }
        readOnce(sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), "accel_mps2") {
            JSONArray(listOf(it[0], it[1], it[2]))
        }
        return out
    }

    companion object {
        /**
         * How long a foreground poll may wait. Covers a cold app coming up
         * (measured ~10s on the test tablet) and, for the launcher
         * come-forward, a cold launcher taking longer than any fixed pause.
         */
        private const val FOREGROUND_WAIT_MS = 10_000L

        /**
         * The extra settle after the launcher is confirmed in front. The
         * HyperOS background-start abort window outlives the come-forward
         * animation: a pad at <1s after HOME was aborted, the same pad at 5s
         * went through with zero aborts.
         */
        private const val HOME_SETTLE_MS = 3_000L

        /**
         * Xiaomi's security center, the foreground package while its
         * "chained-launch" confirmation dialog (打开 TikTok？允许/始终允许)
         * is on screen. It is a USER question, not a launch failure — detect
         * it, report it, and never loop on it.
         */
        private const val MIUI_GUARD_PKG = "com.miui.securitycenter"
    }
}
