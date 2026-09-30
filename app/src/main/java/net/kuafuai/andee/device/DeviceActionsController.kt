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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }
            .fold(
                { JSONObject().put("launched", target) },
                { JSONObject().put("error", "launch failed: ${it.message}") },
            )
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
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.fold(
            { JSONObject().put("opened", url) },
            { JSONObject().put("error", "open failed: ${it.message}") },
        )
    }

    // ---- alarm ----------------------------------------------------

    fun setAlarm(hour: Int, minute: Int, message: String?): JSONObject {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message ?: "")
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(i) }
            .fold(
                { JSONObject().put("alarm_set", "%02d:%02d".format(hour, minute)).put("message", message ?: "") },
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
}
