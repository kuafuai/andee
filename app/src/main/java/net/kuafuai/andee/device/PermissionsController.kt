package net.kuafuai.andee.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
     *
     * confirm=true answers with what actually happened. It used to answer
     * `{"started": true}` — a hard-coded true meaning "startActivity did not
     * throw" — and the brain read that as "it is on" and carried on against a
     * permission it did not have. Those are two different facts and only one
     * of them is worth acting on, so the reply now carries:
     *
     *   mode    solo | handoff | settings — WHO answers the dialog, decided
     *           here rather than by the brain's own judgement, because a
     *           request that mixes them is answered as a whole
     *   result  granted | denied | no_dialog | waiting
     *   next    one line saying what to do about that result
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

        val mode = routeOf(wanted.map { it.id })

        // Drop what is already on. Re-asking a granted permission draws no
        // dialog and answers nothing, so the brain would sit waiting on a
        // sheet that was never going to appear.
        val missing = runtime.filter { !isGranted(it.permission!!) }

        // Fire the intents. Runtime permissions need an Activity to host the
        // dialog — the app has none in the classic sense, so route through
        // the dedicated PermissionRequestActivity (manifest-registered,
        // translucent, finishes itself).
        var fired = false
        if (missing.isNotEmpty()) {
            PermissionRequestActivity.arm()
            val i = Intent(context, PermissionRequestActivity::class.java).apply {
                putExtra("permissions", missing.mapNotNull { it.permission }.toTypedArray())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            fired = runCatching { context.startActivity(i) }.isSuccess
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

        // Three ways this ends, in the order they can be known:
        //  - nothing was missing   → it is on already; no dialog was owed
        //  - a dialog was fired    → wait out the grace, because that is the
        //                            window in which a silent block answers
        //  - only a Settings page  → nothing to wait on but the user
        val result = when {
            runtime.isNotEmpty() && missing.isEmpty() -> GRANTED
            missing.isNotEmpty() && !fired -> NO_DIALOG
            missing.isNotEmpty() -> PermissionRequestActivity.await(DIALOG_GRACE_MS) ?: WAITING
            else -> WAITING
        }

        return JSONObject()
            .put("mode", mode)
            .put("result", result)
            .put("next", nextFor(mode, result))
            .put("say", say)
    }

    private fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * One line telling the brain what this result means for its next call.
     * It lives here rather than in the tool description because it depends on
     * both the mode and the result, and a description cannot say "if what you
     * just got back was X".
     */
    private fun nextFor(mode: String, result: String): String = when (result) {
        GRANTED -> "It is on — carry on with what you needed it for."
        DENIED -> "They saw the sheet and refused. Do not fire it again; say what you cannot do without it."
        NO_DIALOG -> "The system never drew a sheet, so firing again is silently ignored. " +
            "Do not retry — say it did not go through and that this step needs their hand in Settings."
        // Nobody has answered yet, so the screen is the only thing that can
        // still tell the two cases apart: a sheet waiting on a finger, or a
        // sheet that never came and never will. Look first — firing again is
        // the one move that cannot help.
        //
        // And note what is NOT offered here: tapping the sheet. Measured, a
        // system permission sheet exposes no elements and ignores an injected
        // tap, so "tap Allow yourself" would be advice that cannot be taken.
        else -> "Look at the screen before anything else: if nothing was drawn, do not " +
            "fire again — say it did not go through and that this step needs their hand. " +
            when (mode) {
                "solo" -> "If the sheet is there, it is waiting on their finger — you cannot " +
                    "tap it. Say one line that it is up, and carry on with what does not need it."
                "handoff" -> "If the sheet is there it is theirs to answer — ask with ask_user, " +
                    "saying what you are waiting for."
                else -> "A Settings page is open and only they can flip that switch — " +
                    "ask with ask_user and name the switch."
            }
    }

    companion object {
        /**
         * How long a fired dialog gets to answer before it counts as "still
         * waiting". This is a grace period, not a wait for the human: a
         * permission the system has stopped asking about answers in
         * milliseconds with no sheet drawn, while a real dialog stays up for
         * as long as it takes. Waiting on the person is somebody else's job —
         * the ball reading the screen in solo mode, or ask_user in handoff.
         */
        internal const val DIALOG_GRACE_MS = 800L

        internal const val WAITING = "waiting"
        private const val GRANTED = PermissionRequestActivity.GRANTED
        private const val DENIED = PermissionRequestActivity.DENIED
        private const val NO_DIALOG = PermissionRequestActivity.NO_DIALOG

        /**
         * The grants the ball may answer itself: data about the user's own
         * body and their own diary, and nothing that leaves the phone.
         *
         * 🔴 This is a whitelist on purpose. Everything not named here routes
         * to handoff — including a permission added to `catalog` later by
         * someone who never read this — so the failure direction is "the user
         * was asked once more than necessary" rather than "the ball agreed to
         * something on their behalf".
         */
        private val SOLO_IDS = setOf("location", "steps", "calendar", "calendar_write")

        /** Ids with no dialog at all — only a switch inside Settings. */
        private val SETTINGS_IDS = setOf("notifications", "overlay")

        /**
         * Which route a request takes, from the ids alone, so it is testable
         * without a device. Conservative by construction: one non-solo id
         * hands over the whole request, because the dialogs arrive stacked and
         * tapping one's way through a stack is how the wrong one gets answered.
         */
        internal fun routeOf(ids: List<String>): String = when {
            ids.any { it in SETTINGS_IDS } -> "settings"
            ids.isNotEmpty() && ids.all { it in SOLO_IDS } -> "solo"
            else -> "handoff"
        }
    }
}
