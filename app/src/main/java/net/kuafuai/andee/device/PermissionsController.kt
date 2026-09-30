package net.kuafuai.andee.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The polite permission concierge.
 *
 * Runtime permissions die on reinstall; the special ones (notification
 * access, overlay) live in Settings the app cannot flip itself. Instead of
 * the brain parroting "go to settings", this module knows the EXACT missing
 * grants, says them in one human sentence, and — on confirm — deep-links the
 * user to the right settings screen. One [device.permissions] call replaces
 * the whole "which permission? where? how?" dance.
 *
 * Intent EXTRA for a permission's app settings page:
 *   Settings.ACTION_APPLICATION_DETAILS_SETTINGS + package uri
 * For notification access: Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS.
 */
class PermissionsController(private val context: Context) {

    /**
     * What a grant is worth to the ball — used for one honest summary line.
     *
     * @param featureRes the human phrase for this grant, as a resource id: it
     *   is shown to the user in the composed `say` sentence, so it has to
     *   follow the UI language ([AppLocale]) rather than be a literal.
     */
    private data class Perm(
        val id: String,
        val permission: String?,
        val special: String?,      // "notification" | "overlay" | null
        @StringRes val featureRes: Int,
    )

    private val catalog = listOf(
        Perm("location", "android.permission.ACCESS_FINE_LOCATION", null, R.string.dev_perm_feature_location),
        Perm("steps", "android.permission.ACTIVITY_RECOGNITION", null, R.string.dev_perm_feature_steps),
        Perm("contacts", "android.permission.READ_CONTACTS", null, R.string.dev_perm_feature_contacts),
        Perm("call_phone", "android.permission.CALL_PHONE", null, R.string.dev_perm_feature_call_phone),
        Perm("call_log", "android.permission.READ_CALL_LOG", null, R.string.dev_perm_feature_call_log),
        Perm("sms", "android.permission.READ_SMS", null, R.string.dev_perm_feature_sms),
        Perm("sms_send", "android.permission.SEND_SMS", null, R.string.dev_perm_feature_sms_send),
        Perm("calendar", "android.permission.READ_CALENDAR", null, R.string.dev_perm_feature_calendar),
        Perm("calendar_write", "android.permission.WRITE_CALENDAR", null, R.string.dev_perm_feature_calendar_write),
        Perm("notifications", null, "notification", R.string.dev_perm_feature_notifications),
        Perm("overlay", null, "overlay", R.string.dev_perm_feature_overlay),
    )

    fun status(): JSONObject {
        val granted = JSONArray()
        val missing = JSONArray()
        for (p in catalog) {
            val ok = when {
                p.permission != null ->
                    context.checkSelfPermission(p.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                // Notification: the permission flag alone lies after every
                // reinstall (zombie grant — enabled in Settings, never bound).
                // Count it as granted only when the listener is actually
                // connected; a zombie surfaces in `missing` and the guide can
                // fix it, instead of "everything looks fine" while every
                // notification quietly vanishes.
                p.special == "notification" -> NotificationRelayService.status(context)
                    .optBoolean("listener_connected")
                p.special == "overlay" -> Settings.canDrawOverlays(context)
                else -> false
            }
            val o = JSONObject()
                .put("id", p.id)
                .put("feature", AppLocale.str(context, p.featureRes))
            (if (ok) granted else missing).put(o)
        }
        return JSONObject()
            .put("granted_count", granted.length())
            .put("missing_count", missing.length())
            .put("granted", granted)
            .put("missing", missing)
    }

    /**
     * One sentence for the user + optional deep link. The brain should READ
     * this aloud (or show it), ask "开吗?", then call again with confirm=true
     * to fire the intent. Runtime permissions use the classic dialog
     * (needs an activity — we hand the request to the floating settings
     * host); special ones deep-link into Settings.
     */
    fun request(ids: JSONArray, confirm: Boolean): JSONObject {
        // The `say` sentence is shown/spoken to the user, so it is built in
        // their language. See [AppLocale] for why a Service context must wrap.
        val lctx = AppLocale.wrap(context)
        val wanted = buildList {
            for (i in 0 until ids.length()) {
                catalog.firstOrNull { it.id == ids.optString(i) }?.let { add(it) }
            }
        }
        if (wanted.isEmpty()) return JSONObject().put("error", "no known permission ids in request")

        val runtime = wanted.filter { it.permission != null }
        val special = wanted.filter { it.special == "notification" }
        val overlay = wanted.filter { it.special == "overlay" }

        val featureList = wanted.joinToString(lctx.getString(R.string.dev_perm_say_sep)) {
            lctx.getString(it.featureRes)
        }
        val say = buildString {
            append(lctx.getString(R.string.dev_perm_say_prefix, featureList))
            if (runtime.isNotEmpty()) append(lctx.getString(R.string.dev_perm_say_runtime))
            if (special.isNotEmpty()) append(lctx.getString(R.string.dev_perm_say_notification))
        }

        if (!confirm) {
            return JSONObject().put("say", say).put("confirm_needed", true).put("ids", ids)
        }

        // Fire the intents. Runtime permissions need an Activity to host the
        // dialog — the app has none in the classic sense, so route through
        // the dedicated PermissionRequestActivity (manifest-registered,
        // translucent, finishes itself).
        if (runtime.isNotEmpty()) {
            val i = Intent(context, PermissionRequestActivity::class.java).apply {
                putExtra("permissions", runtime.mapNotNull { it.permission }.toTypedArray())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(i) }
        }
        special.forEach {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        if (overlay.isNotEmpty()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        return JSONObject().put("started", true).put("say", say)
    }
}
