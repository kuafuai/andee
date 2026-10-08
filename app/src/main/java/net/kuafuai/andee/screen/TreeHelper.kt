package net.kuafuai.andee.screen

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * Switches on the one system service that makes WeChat hand over its tree.
 *
 * Measured on a Xiaomi Pad 5 (MIUI, WeChat 8.0.78, 2026-10-08): WeChat gives
 * every accessibility client a blank placeholder root unless MIUI's own
 * `MiuiEnhanceTBService` (the "TalkBack enhance" helper) is enabled — then the
 * same screen dumps 219 nodes. MIUI also flips that service on or off by
 * itself whenever Andee is re-bound (reinstall, crash, toggle), which is the
 * "有时候可以有时候不行" the user saw.
 *
 * Only ever *adds* to the enabled list, never removes, and only when the
 * component exists on this device and `WRITE_SECURE_SETTINGS` was granted —
 * everywhere else [wake] is a no-op returning false. Throttled so a user who
 * switches it off on purpose is not overruled on every dump.
 */
object TreeHelper {
    private const val TAG = "Body"

    private val HELPER = ComponentName(
        "com.miui.accessibility",
        "com.miui.accessibility.enhance.tb.MiuiEnhanceTBService",
    )

    private const val RETRY_AFTER_MS = 60_000L

    @Volatile private var lastTryAt = 0L

    /** True when this call just added the helper to the enabled list. */
    fun wake(context: Context): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        if (runCatching { context.packageManager.getServiceInfo(HELPER, 0) }.isFailure) return false
        val now = SystemClock.uptimeMillis()
        if (lastTryAt != 0L && now - lastTryAt < RETRY_AFTER_MS) return false
        lastTryAt = now
        val cr = context.contentResolver
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val flat = HELPER.flattenToString()
        val short = HELPER.flattenToShortString()
        if (current.split(':').any { it.equals(flat, true) || it.equals(short, true) }) return false
        val next = if (current.isBlank()) flat else "$current:$flat"
        return runCatching {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next)
        }.onFailure { Log.w(TAG, "tree helper: enable failed", it) }
            .getOrDefault(false)
            .also { if (it) Log.i(TAG, "tree helper: enabled $flat") }
    }
}
