package net.kuafuai.andee.device

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import androidx.annotation.StringRes
import net.kuafuai.andee.R
import net.kuafuai.andee.config.VoiceConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * "Is this device actually ready?", answered in one list.
 *
 * **Why this exists.** Everything this app does fails *quietly*. A missing
 * overlay permission means the ball never draws and nothing says why. A backend
 * that cannot be reached means the ball spins once and goes silent. A stale
 * notification grant means notifications are dropped while Settings happily
 * reports the permission as on. Each of those has been reported by the user as
 * "坏了" at least once, and every one of them is decidable in advance.
 *
 * **One list, three readers.** [net.kuafuai.andee.ScreenBodyService] runs it at
 * startup and puts up a nudge card if anything is wrong; that same service puts
 * it up on demand as a card from `✓` in the assistant's control bar; and
 * [net.kuafuai.andee.ui.SelfCheckActivity] hosts the very same card from the
 * launcher icon, for the device where the service is not running at all — see
 * [net.kuafuai.andee.ui.SelfCheckUi], which is the one renderer behind all of
 * them. The list is built here and nowhere else, so no two of those can ever
 * disagree about what is wrong — which is the failure a second hand-written
 * checklist would have within a week.
 *
 * **Not a doctor.** It reports and offers the door; it does not repair.
 *
 * ### Levels
 *
 *  * [Level.FAIL] — dead right now. The user's complaint will be "没反应".
 *  * [Level.WARN] — it works, but something downstream degrades silently (a
 *    zombie grant, an adb-only permission, a nearly-full disk).
 *  * [Level.NOTE] — not a problem, just a fact worth being able to read (the
 *    wake phrase is not taught yet, a probe was skipped). **Never raises a
 *    card.**
 *  * [Level.OK] — fine. Shown on the page, counted nowhere.
 *
 * The WARN/NOTE split is what keeps this from being nagware: only [FAIL] and
 * [WARN] may interrupt the user.
 */
object SelfCheck {

    enum class Level { OK, NOTE, WARN, FAIL }

    /**
     * How a row is fixed. Deliberately a small closed set of *values* rather
     * than a lambda: the list is built off the main thread, and an `Intent`
     * described at 200 ms is still good at 3 s, where a captured closure
     * holding a Context would be a leak waiting to be filed as a bug.
     */
    sealed interface Fix {
        /**
         * A system Settings screen. [data] is an optional `package:` uri.
         *
         * [extras] exist for the one screen that cannot be named by action
         * alone. `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` is the *detail*
         * page — one app's notification-access switch — and it needs the
         * component in an extra to know which app. It is the screen worth
         * having: the plain `ACTION_NOTIFICATION_LISTENER_SETTINGS` list does
         * not resolve to anything at all on a HONOR, so the button built on it
         * was a tap that did nothing. Still values, not a closure — see the
         * interface doc — so they are `List<Pair<String, String>>` of
         * extra-name to extra-value and the renderer folds them in.
         */
        data class Screen(
            val action: String,
            val data: String? = null,
            val extras: List<Pair<String, String>> = emptyList(),
            /**
             * The button's word, when the default 去开启 would describe the
             * errand wrongly. Zero means "no opinion" — the renderer falls back
             * to 去开启.
             *
             * It exists for the notification-access row. In the zombie state the
             * switch already reads as *on*, so 去开启 invites exactly the wrong
             * action: open the page, see it on, leave — and the row stays red.
             * The errand there is 关闭再打开, and saying so on the button is the
             * whole hint. A word belongs to the instance rather than the action
             * because the same action is the right door in both states.
             */
            @StringRes val label: Int = 0,
        ) : Fix

        /**
         * Somewhere off the device — currently only the ADBKeyboard download.
         *
         * Its own member rather than `Screen(ACTION_VIEW, url)` because the
         * button it produces is a different word: 去下载 is not 去开启, and a
         * labelled-as-Settings button that opens a browser is the small lie
         * this whole list is built to avoid. The plumbing is otherwise
         * identical, which is the point — one new value, no new path.
         */
        data class Link(val url: String) : Fix

        /** Hosted by [PermissionRequestActivity] — needs a live Activity window. */
        data class Grant(val permissions: List<String>) : Fix

        /**
         * No button can do it. [command] is shown to the user to copy, not run:
         * `WRITE_SECURE_SETTINGS` is `signature|privileged|development`, so only
         * `pm grant` over adb hands it over. Saying so plainly *is* the value of
         * the row — otherwise the user hunts Settings for a switch that is not
         * there.
         */
        data class Adb(val command: String) : Fix

        /** Ours to fix, in our own settings card. Needs the service to be up. */
        object OurSettings : Fix

        /** Nothing to offer. */
        object Nothing : Fix
    }

    /**
     * One row.
     *
     * [detailArgs] are substituted into [detail] by the caller and never baked
     * in here, because the sentence has to come out in the user's language and
     * only the caller knows which Context that is — see [net.kuafuai.andee.i18n.AppLocale].
     */
    data class Finding(
        val id: String,
        @StringRes val title: Int,
        val level: Level,
        @StringRes val detail: Int,
        val detailArgs: List<Any> = emptyList(),
        val fix: Fix = Fix.Nothing,
    ) {
        /** Whether there is a button to press, as opposed to words to read. */
        val actionable: Boolean
            get() = fix is Fix.Screen || fix is Fix.Link || fix is Fix.Grant ||
                fix is Fix.OurSettings
    }

    /** What may interrupt the user. See the class doc for why NOTE does not. */
    fun needsAttention(f: Finding): Boolean = f.level == Level.FAIL || f.level == Level.WARN

    /**
     * The whole list, in report order.
     *
     * @param probe whether to spend the two network round-trips (the backend,
     *   then the voice host). **This blocks the calling thread** — up to ~20 s
     *   on a black-holed network — so call it off the main thread. With
     *   `probe = false` those two rows come back as [Level.NOTE] "检查中…",
     *   which is what lets the page paint instantly and fill in behind.
     */
    fun run(context: Context, probe: Boolean = true): List<Finding> {
        val online = isOnline(context)
        val a11yIme = imeChannelOpen()
        return listOf(
            accessibility(context),
            overlay(context),
            network(online),
            brain(context),
            backend(context, probe, online),
            microphone(context),
            notificationAccess(context),
            typingChannel(context),
            adbKeyboard(context, a11yIme),
            secureSettings(context, a11yIme),
            voiceKey(context),
            voiceReach(probe, online),
            wakePhrase(context),
            background(context),
            storage(context),
        )
    }

    /** [run] with no network, for the caller's first paint. */
    fun local(context: Context): List<Finding> = run(context, probe = false)

    // ---- The checks ----

    /**
     * The service itself.
     *
     * Only ever meaningful from an Activity: if this were false where the
     * service could ask, the service would not be running to ask it. That is
     * exactly why [net.kuafuai.andee.ui.SelfCheckActivity] still exists — it is
     * the one surface in this app that can run while the accessibility service is
     * off, and therefore the only place this row can be red and still be read.
     * Every other host of this list is the service itself, and there the row is
     * necessarily green.
     */
    private fun accessibility(context: Context): Finding {
        val cn = ComponentName(context, net.kuafuai.andee.ScreenBodyService::class.java)
        val on = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty().split(':').any { ComponentName.unflattenFromString(it) == cn }
        return Finding(
            id = "accessibility",
            title = R.string.check_title_accessibility,
            level = if (on) Level.OK else Level.FAIL,
            detail = if (on) R.string.check_detail_accessibility_on
            else R.string.check_detail_accessibility_off,
            fix = if (on) Fix.Nothing else Fix.Screen(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
    }

    /**
     * `SYSTEM_ALERT_WINDOW`.
     *
     * The overlay is `TYPE_APPLICATION_OVERLAY`, not
     * `TYPE_ACCESSIBILITY_OVERLAY`, so the accessibility service is *not* a
     * licence to draw. Without this the window add throws,
     * `FloatingWindowUi.show()` catches it and returns false, and the service
     * runs perfectly with nothing on screen — the "没反应" that looks least like
     * a permission problem.
     */
    private fun overlay(context: Context): Finding {
        val on = Settings.canDrawOverlays(context)
        return Finding(
            id = "overlay",
            title = R.string.check_title_overlay,
            level = if (on) Level.OK else Level.FAIL,
            detail = if (on) R.string.check_detail_overlay_on else R.string.check_detail_overlay_off,
            fix = if (on) {
                Fix.Nothing
            } else {
                Fix.Screen(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}",
                )
            },
        )
    }

    /**
     * Anything at all to talk over.
     *
     * Checked *before* the two probes and not after, because "no network" is
     * the cause of both of their failures — reporting all three would send the
     * user looking for three problems where there is one.
     */
    private fun network(online: Boolean): Finding = Finding(
        id = "network",
        title = R.string.check_title_network,
        level = if (online) Level.OK else Level.FAIL,
        detail = if (online) R.string.check_detail_network_on else R.string.check_detail_network_off,
        fix = if (online) Fix.Nothing else Fix.Screen(Settings.ACTION_WIRELESS_SETTINGS),
    )

    /**
     * Which brain, and is it told where to go.
     *
     * The *presence* half only. `ScreenBodyService.startBrain` already raises
     * its own card for exactly these two states, so the startup call passes
     * `announce = false` and this row is what the user sees instead — one card
     * per start, listing everything, rather than two cards arguing.
     */
    private fun brain(context: Context): Finding {
        val bc = VoiceConfig.brainConfig(context)
        val hubUrl = VoiceConfig.hubConfig(context).url
        val missing = if (bc.isLocal) bc.apiKey.isEmpty() else hubUrl.isEmpty()
        return Finding(
            id = "brain",
            title = R.string.check_title_brain,
            level = if (missing) Level.FAIL else Level.OK,
            detail = when {
                !missing -> R.string.check_detail_brain_on
                bc.isLocal -> R.string.check_detail_brain_key_missing
                else -> R.string.check_detail_brain_hub_missing
            },
            fix = if (missing) Fix.OurSettings else Fix.Nothing,
        )
    }

    /**
     * The backend, for real.
     *
     * Three ways to be configured-but-broken, worth telling apart because the
     * symptom is identical for all three — a ball that spins once and says
     * nothing:
     *
     *  * **URL wrong** → the connection itself fails.
     *  * **Key wrong** → HTTP 401/403.
     *  * **Model name wrong** → HTTP 400. The likeliest of the three, since it
     *    is the one a person types by hand.
     *
     * [probeBackend] decides them without generating a token where the gateway
     * allows it.
     */
    private fun backend(context: Context, probe: Boolean, online: Boolean): Finding {
        val bc = VoiceConfig.brainConfig(context)
        // Cloud mode has no model on this device to test: the hub owns the
        // backend, and whether the hub is reachable is the socket's business,
        // which `startHubClient` already logs.
        if (!bc.isLocal) {
            return Finding(
                id = "backend",
                title = R.string.check_title_model,
                level = Level.OK,
                detail = R.string.check_detail_model_cloud,
            )
        }
        if (bc.apiKey.isEmpty()) {
            return Finding(
                id = "backend",
                title = R.string.check_title_model,
                level = Level.OK,
                detail = R.string.check_detail_model_no_key,
            )
        }
        if (!probe || !online) {
            return Finding(
                id = "backend",
                title = R.string.check_title_model,
                level = Level.NOTE,
                detail = if (online) R.string.check_detail_checking
                else R.string.check_detail_skipped_offline,
            )
        }
        return when (val r = probeBackend(bc.baseUrl, bc.apiKey, bc.model)) {
            is Reach.Ok -> Finding(
                id = "backend",
                title = R.string.check_title_model,
                level = Level.OK,
                detail = R.string.check_detail_model_ok,
                detailArgs = listOf(bc.model),
            )

            is Reach.Throttled -> Finding(
                id = "backend",
                title = R.string.check_title_model,
                level = Level.WARN,
                detail = R.string.check_detail_model_throttled,
                fix = Fix.OurSettings,
            )

            is Reach.Bad -> Finding(
                id = "backend",
                title = R.string.check_title_model,
                level = Level.FAIL,
                detail = when (r.kind) {
                    Kind.URL -> R.string.check_detail_model_url
                    Kind.KEY -> R.string.check_detail_model_key
                    Kind.BALANCE -> R.string.check_detail_model_balance
                    Kind.MODEL -> R.string.check_detail_model_name
                    Kind.OTHER -> R.string.check_detail_model_other
                },
                // The model-name case gets two arguments (what you asked for,
                // what is on offer); the rest get the gateway's own words.
                detailArgs = if (r.kind == Kind.MODEL) listOf(bc.model, r.detail)
                else listOf(r.detail),
                fix = Fix.OurSettings,
            )
        }
    }

    /**
     * The notification listener, which has the one failure mode in this app the
     * Settings app actively lies about.
     *
     * After a reinstall the grant survives, reads as enabled, and the listener
     * is never bound — so the flag says yes while every notification is
     * dropped. `NotificationRelayService.status` already keeps the two apart;
     * this is the surface that finally says so out loud.
     *
     * **Measured on a HONOR (MagicOS / Android 15), and the state is worse
     * there than the doc above implies.** `dumpsys notification` shows this app
     * in "Allowed notification listeners" — i.e. Settings genuinely displays the
     * permission as on, which is exactly what the user reported — while it is
     * absent from "Live notification listeners", and neither a process restart
     * nor `requestRebind` brings it back. Only a real off-then-on of the grant
     * does (`cmd notification disallow_listener` + `allow_listener` fixed it on
     * the spot). Writing `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS` by
     * hand does **not**, even with `WRITE_SECURE_SETTINGS` — verified on the
     * same device — so there is no one-tap repair this app can perform and the
     * user has to do it in Settings. That makes [listenerSettings] load-bearing
     * rather than cosmetic: it has to open a screen that exists.
     */
    private fun notificationAccess(context: Context): Finding {
        val st = NotificationRelayService.status(context)
        val enabled = st.optBoolean("listener_enabled")
        val connected = st.optBoolean("listener_connected")
        return Finding(
            id = "notifications",
            title = R.string.check_title_notifications,
            level = if (connected) Level.OK else Level.WARN,
            detail = when {
                connected -> R.string.check_detail_notif_on
                enabled -> R.string.check_detail_notif_zombie
                else -> R.string.check_detail_notif_off
            },
            fix = if (connected) Fix.Nothing else listenerSettings(context, needsRepower = enabled),
        )
    }

    /**
     * The notification-access screen, by the platform name that resolves first.
     *
     * Two actions exist and they are not interchangeable in the field. The
     * *list* (`ACTION_NOTIFICATION_LISTENER_SETTINGS`) is the one every answer
     * on the internet names, and on a HONOR it matches no activity at all —
     * `cmd package resolve-activity` says "No activities found", so
     * [net.kuafuai.andee.ui.SelfCheckUi.launch] caught the exception and the
     * 去开启 button read as dead. The *detail* action
     * (`ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS`, API 30) does resolve
     * there, to `NotificationAccessDetailsActivity` — one app's switch, which is
     * the smaller page anyway: the user lands on the toggle they have to flip
     * instead of a list of every app on the phone.
     *
     * Probed rather than assumed, because "API 30" is not a promise that a given
     * ROM kept the screen. The check is a `queryIntentActivities` with
     * `CATEGORY_DEFAULT`, which is what `startActivity` itself injects into an
     * implicit intent — so a `true` here means the tap will land.
     */
    private fun listenerSettings(context: Context, needsRepower: Boolean): Fix {
        val cn = ComponentName(context, NotificationRelayService::class.java).flattenToString()
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .addCategory(Intent.CATEGORY_DEFAULT)
        val reachable = runCatching {
            context.packageManager.queryIntentActivities(detail, 0).isNotEmpty()
        }.getOrDefault(false)
        // 关闭再打开 in the zombie state, 去开启 in the merely-off one — see
        // Fix.Screen.label. The switch the user is about to see already reads
        // as on in the first case, so the button is the only place left to say
        // what has to happen; the detail says it too, in words.
        val label = if (needsRepower) R.string.check_fix_repower else 0
        return if (reachable) {
            Fix.Screen(
                Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS,
                extras = listOf(EXTRA_LISTENER_COMPONENT to cn),
                label = label,
            )
        } else {
            Fix.Screen(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, label = label)
        }
    }

    /**
     * Which of the two typing channels is actually in play.
     *
     * The primary one is our own accessibility service acting as an input method
     * ([net.kuafuai.andee.screen.A11yIme]) — API 33+, nothing for the user to
     * install, enable, switch or plug a cable in for. When it is there, the two
     * rows below it describe a fallback and stop being errands; when it is not,
     * they are the only way the assistant can type and go back to being warnings.
     *
     * So this row is the *reason* those two change colour, which is why it is
     * [Level.NOTE] in both directions and never an errand itself: there is
     * nothing a user can do about an Android 12 tablet, and a red row with no
     * fix is the thing this page exists to avoid.
     *
     * Asked of the **running service's** `serviceInfo` rather than of
     * `Build.VERSION`, because the flag is what the platform granted, not what
     * the manifest asked for. With the service down the question is moot — the
     * `accessibility` row above is already red and nothing can type at all — and
     * the answer defaults to the version check so the fallback rows still read
     * correctly on the launcher's copy of this page.
     */
    private fun typingChannel(context: Context): Finding {
        val on = imeChannelOpen()
        return Finding(
            id = "typing_channel",
            title = R.string.check_title_typing_channel,
            level = Level.NOTE,
            detail = if (on) R.string.check_detail_typing_a11y
            else R.string.check_detail_typing_adb,
            fix = Fix.Nothing,
        )
    }

    /**
     * Whether the accessibility input-method channel is open.
     *
     * Shared by [typingChannel] and the two fallback rows so they cannot
     * disagree about the same fact — the failure a second copy of this test
     * would have within a week.
     */
    private fun imeChannelOpen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val info = runCatching {
            net.kuafuai.andee.ScreenBodyService.get()?.serviceInfo
        }.getOrNull() ?: return true
        return info.flags and AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR != 0
    }

    /**
     * ADBKeyboard — the headless IME the **fallback** `type_text` goes through.
     *
     * `ScreenController.typeViaAdbKeyboard` has exactly one channel: a broadcast
     * to `com.android.adbkeyboard`, which must be an *enabled* IME for the
     * `commitText` to land. So "can the assistant type into other apps" is
     * decided by two facts on this device — is that keyboard installed, and has
     * the user enabled it — and neither has ever had a way of announcing itself.
     * The failure is a `type_text` that comes back with an error naming the
     * target app instead of the keyboard.
     *
     * **Three states, one row**, because they are three steps of one errand and
     * the fix changes as the user completes them: not there → download it; there
     * but off → enable it; enabled → [Level.OK]. Two rows would leave a
     * permanently red "not installed" beside a green "enabled".
     *
     * **[Level.NOTE] once [typingChannel] is open, [Level.WARN] when it is
     * not** — and that split is the whole point of [a11yIme] being a parameter.
     * This used to be an unconditional WARN, from a time when it was the only
     * channel: a user with no computer was told to go get one, for a capability
     * the device now has by itself. Downgraded rather than deleted because
     * API 30–32 and any editor the primary channel cannot reach still land here,
     * and a silently missing fallback is how typing breaks twice.
     *
     * The download is [ADB_KEYBOARD_APK] and the enable step is
     * `INPUT_METHOD_SETTINGS`, which is the screen that lists keyboards with the
     * toggle that actually turns this one on — not `showInputMethodPicker`,
     * which `ImeSwitch` refuses on purpose.
     */
    private fun adbKeyboard(context: Context, a11yIme: Boolean): Finding {
        val installed = net.kuafuai.andee.ui.ImeSwitch.brainInstalled(context)
        val enabled = installed &&
            net.kuafuai.andee.ui.ImeSwitch.brainEnabled(context)
        return Finding(
            id = "adb_keyboard",
            title = R.string.check_title_adb_keyboard,
            level = when {
                enabled -> Level.OK
                a11yIme -> Level.NOTE
                else -> Level.WARN
            },
            detail = when {
                enabled -> R.string.check_detail_adb_kb_on
                a11yIme -> R.string.check_detail_adb_kb_spare
                installed -> R.string.check_detail_adb_kb_off
                else -> R.string.check_detail_adb_kb_missing
            },
            fix = when {
                enabled -> Fix.Nothing
                installed -> Fix.Screen(Settings.ACTION_INPUT_METHOD_SETTINGS)
                else -> Fix.Link(ADB_KEYBOARD_APK)
            },
        )
    }

    /**
     * `WRITE_SECURE_SETTINGS`, which the **fallback** `type_text` needs.
     *
     * The one row with no button on purpose. It is
     * `signature|privileged|development`: `pm grant` over adb is the only way.
     * When it goes missing — a factory reset, a new tablet — the honest answer
     * is "plug the cable in", not a Settings screen that does not contain the
     * switch.
     *
     * [Level.NOTE] / [Level.WARN] on the same rule as [adbKeyboard], and for the
     * same reason: this grant only ever existed to let the device swap its own
     * default IME for one tool call, and with [typingChannel] open there is no
     * IME to swap.
     */
    private fun secureSettings(context: Context, a11yIme: Boolean): Finding {
        val on = context.checkSelfPermission(WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        return Finding(
            id = "secure_settings",
            title = R.string.check_title_secure_settings,
            level = when {
                on -> Level.OK
                a11yIme -> Level.NOTE
                else -> Level.WARN
            },
            detail = when {
                on -> R.string.check_detail_secure_on
                a11yIme -> R.string.check_detail_secure_spare
                else -> R.string.check_detail_secure_missing
            },
            fix = if (on) {
                Fix.Nothing
            } else {
                Fix.Adb("adb shell pm grant ${context.packageName} $WRITE_SECURE_SETTINGS")
            },
        )
    }

    /**
     * The microphone.
     *
     * Absent from [PermissionsController]'s catalog until now, and that is the
     * gap this row closes: the permission is declared in the manifest and asked
     * for on the first tap, so "denied once, months ago" left no trace anywhere
     * the user could find.
     */
    private fun microphone(context: Context): Finding {
        val p = Manifest.permission.RECORD_AUDIO
        val on = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        return Finding(
            id = "microphone",
            title = R.string.check_title_microphone,
            level = if (on) Level.OK else Level.FAIL,
            detail = if (on) R.string.check_detail_mic_on else R.string.check_detail_mic_off,
            fix = if (on) Fix.Nothing else Fix.Grant(listOf(p)),
        )
    }

    /**
     * Is there a 火山 key at all.
     *
     * Free, offline, and it sits *before* [voiceReach] because it answers the
     * cheaper half of the same question. Without a key ASR and TTS fail during
     * the WebSocket handshake, which surfaces as a connect error — the device
     * looks like it has a network problem when what it has is an empty field.
     * [VoiceConfig.API_KEY] ships empty in the public source (see its doc), so
     * this is the state every fresh checkout starts in, and it has to be said
     * out loud rather than discovered.
     *
     * WARN, not FAIL: the brain, the screen tools and the notebook all work
     * mute. What is dead is hearing and speaking, which is a degraded device
     * rather than a dead one.
     */
    private fun voiceKey(context: Context): Finding {
        val has = VoiceConfig.load(context).apiKey.isNotBlank()
        return Finding(
            id = "voice_key",
            title = R.string.check_title_voice_key,
            level = if (has) Level.OK else Level.WARN,
            detail = if (has) R.string.check_detail_voice_key_ok
            else R.string.check_detail_voice_key_missing,
            fix = if (has) Fix.Nothing else Fix.OurSettings,
        )
    }

    /**
     * Can we reach 火山 at all.
     *
     * A TCP connect, **not** a credential check: the ASR/TTS handshake carries
     * the key in headers, and reproducing it here would mean a second copy of
     * that protocol living next to `HuoshanAsr`. What this catches is the
     * failure this device actually had — `ASR ws connect timeout` — where the
     * network is up and the voice host still is not reachable from it. A green
     * row here beside a failing ASR therefore means the key, and says so by
     * elimination — a deduction that is only usable because [voiceKey] has
     * already ruled out the empty field.
     */
    private fun voiceReach(probe: Boolean, online: Boolean): Finding {
        if (!probe || !online) {
            return Finding(
                id = "voice_host",
                title = R.string.check_title_voice_host,
                level = Level.NOTE,
                detail = if (online) R.string.check_detail_checking
                else R.string.check_detail_skipped_offline,
            )
        }
        val err = tcpProbe(VOICE_HOST, VOICE_PORT)
        return Finding(
            id = "voice_host",
            title = R.string.check_title_voice_host,
            level = if (err == null) Level.OK else Level.WARN,
            detail = if (err == null) R.string.check_detail_voice_host_ok
            else R.string.check_detail_voice_host_bad,
            detailArgs = if (err == null) listOf(VOICE_HOST) else listOf(VOICE_HOST, err),
        )
    }

    /** A fact, not a fault — see the [Level] doc for why this is a NOTE. */
    private fun wakePhrase(context: Context): Finding {
        val on = net.kuafuai.andee.wake.WakeTemplates.enrolled(context)
        return Finding(
            id = "wake",
            title = R.string.check_title_wake,
            level = if (on) Level.OK else Level.NOTE,
            detail = if (on) R.string.check_detail_wake_on else R.string.check_detail_wake_off,
            fix = if (on) Fix.Nothing else Fix.OurSettings,
        )
    }

    /**
     * Battery optimisation.
     *
     * Pending todos are `AlarmManager` alarms, so doze delays them, and the
     * wake listener stops with the screen either way. Being on the list is not
     * a broken device — it is one whose reminders may arrive late, which the
     * user should be able to find out without reading `dumpsys deviceidle`.
     *
     * The read is live, not cached: [PowerManager.isIgnoringBatteryOptimizations]
     * answers from the current whitelist, so adding or removing the exemption
     * and tapping 重新检查 flips this row on the spot — verified both ways on a
     * HONOR with `cmd deviceidle whitelist +/-` against `am get-standby-bucket`
     * (10 ACTIVE ↔ 5 EXEMPTED). Nothing was wrong with the judgement; the door
     * beside it was the problem, which is what [batteryFix] is about.
     */
    private fun background(context: Context): Finding {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignored = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        return Finding(
            id = "battery",
            title = R.string.check_title_battery,
            level = if (ignored) Level.OK else Level.WARN,
            detail = if (ignored) R.string.check_detail_battery_on
            else R.string.check_detail_battery_off,
            fix = if (ignored) Fix.Nothing else batteryFix(context),
        )
    }

    /**
     * The one-tap exemption, or the list page on a device that has no request
     * page.
     *
     * **Measured on a HONOR (MagicOS / Android 15): the list page is a dead end
     * for a third-party app, so the request page is the only door.** That page
     * lists ten preinstalled apps and nothing else — this package is absent even
     * while `dumpsys deviceidle whitelist` lists it, force-stopping
     * `com.android.settings` does not change it, and the page's own search box
     * answers 「没有匹配的结果」 for the package name. A row whose fix was that
     * page therefore had a 去开启 button that could not possibly turn its own row
     * green, which is the failure this method removes.
     *
     * The request page is one dialog that calls
     * `PowerWhitelistManager.addToWhitelist`, i.e. exactly the state this row
     * reads. On the HONOR it reads 「是否忽略电池优化？」/「取消」+「忽略」 —
     * note the affirmative button is 忽略, not 允许, which is why
     * `check_detail_battery_off` names that word rather than the AOSP one. It is
     * also silent about its own refusals —
     * `RequestIgnoreBatteryOptimizations.onCreate` finishes without a dialog when
     * this package does not declare `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
     * (`DEBUG = false` there, so no log line either) — which is why the manifest
     * declaration is load-bearing; see AndroidManifest.
     *
     * Probed like [listenerSettings], and for the same reason: an action that
     * resolves nowhere must fall back rather than leave a tap that does nothing.
     */
    private fun batteryFix(context: Context): Fix {
        val pkg = context.packageName
        val request = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$pkg"),
        )
        val reachable = runCatching {
            context.packageManager.queryIntentActivities(request, 0).isNotEmpty()
        }.getOrDefault(false)
        return if (reachable) {
            Fix.Screen(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                data = "package:$pkg",
            )
        } else {
            Fix.Screen(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }

    /**
     * Room to write.
     *
     * Pages, screenshots, the grounding log and the notebook all land in
     * `filesDir`. When it fills, the failures are scattered and unrelated
     * looking — a page that will not open, a screenshot that saves as zero
     * bytes — so the number is worth having in one place.
     */
    private fun storage(context: Context): Finding {
        val free = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(-1L)
        val mb = if (free < 0) -1L else free / (1024 * 1024)
        return Finding(
            id = "storage",
            title = R.string.check_title_disk,
            level = when {
                mb < 0 -> Level.NOTE
                mb < DISK_FAIL_MB -> Level.FAIL
                mb < DISK_WARN_MB -> Level.WARN
                else -> Level.OK
            },
            detail = when {
                mb < 0 -> R.string.check_detail_disk_unknown
                mb < DISK_FAIL_MB -> R.string.check_detail_disk_full
                mb < DISK_WARN_MB -> R.string.check_detail_disk_low
                else -> R.string.check_detail_disk_ok
            },
            detailArgs = if (mb < 0) emptyList() else listOf(mb),
        )
    }

    // ---- Plumbing ----

    fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork ?: return@runCatching false
        cm.getNetworkCapabilities(nw)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    private sealed interface Reach {
        object Ok : Reach
        object Throttled : Reach
        data class Bad(val kind: Kind, val detail: String) : Reach
    }

    private enum class Kind { URL, KEY, BALANCE, MODEL, OTHER }

    /**
     * Ask the backend who it is, without paying for an answer.
     *
     * `GET /models` is the listing every gateway aimed at this format exposes,
     * and it decides all three cases at once: the key is exercised (401/403),
     * the URL is exercised (a connect failure), and if it answers, its list can
     * be compared against the configured name — so a typo comes back with *what
     * was on offer* rather than with a bare 400.
     *
     * The chat probe is the fallback for a gateway with no `/models`. It is the
     * expensive path, so it is bounded twice: `max_tokens = 1` and thinking
     * off. All three misconfigurations are decided by validation, before any
     * generation happens, so there is nothing to learn from letting it write.
     */
    private fun probeBackend(baseUrl: String, apiKey: String, model: String): Reach {
        val http = OkHttpClient.Builder()
            .connectTimeout(PROBE_CONNECT_S, TimeUnit.SECONDS)
            .readTimeout(PROBE_READ_S, TimeUnit.SECONDS)
            .build()
        val auth = "Bearer $apiKey"

        val listed: List<String>? = try {
            val req = Request.Builder()
                .url("$baseUrl/models")
                .addHeader("Authorization", auth)
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> runCatching {
                        val data = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data")
                        (0 until (data?.length() ?: 0)).mapNotNull { i ->
                            data?.optJSONObject(i)?.optString("id")?.ifEmpty { null }
                        }
                    }.getOrDefault(emptyList())

                    resp.code == 401 || resp.code == 403 -> return Reach.Bad(Kind.KEY, "HTTP ${resp.code}")

                    resp.code == 402 -> return Reach.Bad(Kind.BALANCE, "HTTP 402")

                    resp.code == 429 -> return Reach.Throttled

                    // No such endpoint on this gateway: not an error in the
                    // user's config, just a gateway shaped differently. Fall
                    // through to the chat probe.
                    resp.code == 404 || resp.code == 405 -> null

                    else -> return Reach.Bad(
                        Kind.OTHER,
                        "HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(200)}",
                    )
                }
            }
        } catch (e: Exception) {
            // IOException is the expected one (DNS, refused, timeout). Anything
            // else here is still "we could not talk to this URL", which is the
            // same sentence as far as the user is concerned.
            return Reach.Bad(Kind.URL, e.message ?: e.javaClass.simpleName)
        }

        if (listed != null) {
            // An empty list on a 200 is a gateway that answers but tells
            // nothing; treat it as "reachable" rather than inventing a model
            // complaint out of it.
            return if (listed.isEmpty() || listed.contains(model)) Reach.Ok
            else Reach.Bad(Kind.MODEL, listed.take(MODEL_SAMPLE).joinToString(", "))
        }

        return try {
            val body = JSONObject()
                .put("model", model)
                .put("stream", false)
                .put("max_tokens", 1)
                .put("thinking", JSONObject().put("type", "disabled"))
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
                )
            val req = Request.Builder()
                .url("$baseUrl/chat/completions")
                .addHeader("Authorization", auth)
                .addHeader("Content-Type", "application/json")
                .post(body.toString().toRequestBody(JSON_MEDIA))
                .build()
            http.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> Reach.Ok
                    resp.code == 401 || resp.code == 403 -> Reach.Bad(Kind.KEY, "HTTP ${resp.code}")
                    resp.code == 402 -> Reach.Bad(Kind.BALANCE, "HTTP 402")
                    resp.code == 429 -> Reach.Throttled
                    // 400 is what a bad model name comes back as, and the body
                    // is where the gateway says which part of it it disliked.
                    else -> Reach.Bad(
                        Kind.MODEL,
                        "HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(200)}",
                    )
                }
            }
        } catch (e: IOException) {
            Reach.Bad(Kind.URL, e.message ?: e.javaClass.simpleName)
        }
    }

    /** @return null when the connection opened, else why it did not. */
    private fun tcpProbe(host: String, port: Int): String? = try {
        Socket().use { s -> s.connect(InetSocketAddress(host, port), PROBE_CONNECT_MS) }
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    /**
     * Which app `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` is about.
     *
     * Spelled out rather than taken from `Settings.EXTRA_NOTIFICATION_LISTENER_
     * COMPONENT_NAME` for the same reason [Fix.Adb]'s command is spelled out:
     * this string travels inside a [Fix] value and is read back by a different
     * process's Activity, so it is part of the payload and not a convenience
     * alias. The value is the platform's, verbatim, from `android.provider.
     * Settings` (API 30).
     */
    private const val EXTRA_LISTENER_COMPONENT =
        "android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME"

    /**
     * Where to get ADBKeyboard.
     *
     * The **v2.5-dev release asset**, not the `master/ADBKeyboard.apk` that
     * every blog post links to. That one is upstream's own "[Old] Release APK
     * download", and v2.5-dev exists for one stated reason: "fix keyboard
     * unusable on android-36" — the newest Android, which on this project is
     * the one a newly bought tablet arrives on. A pinned tag, so it does not
     * drift.
     *
     * Both files were read before choosing: each is ~18 KB, and each declares
     * `com.android.adbkeyboard.AdbIME`, broadcasts `ADB_INPUT_TEXT` /
     * `ADB_CLEAR_TEXT` / `ADB_EDITOR_CODE` and types through `commitText` — the
     * exact component and the exact actions `ScreenController` and
     * `ImeSwitch.BRAIN_IME` name. So either installs something this app can
     * drive, and the newer one is the one that keeps working.
     *
     * Not vendored into `assets/` and installed through a `FileProvider`, which
     * would make this one tap instead of three: redistributing someone else's
     * APK is a licensing decision, not a build detail, and the row as written
     * asks nothing of the user that the app cannot already explain.
     */
    private const val ADB_KEYBOARD_APK =
        "https://github.com/senzhk/ADBKeyBoard/releases/download/v2.5-dev/keyboardservice-debug.apk"

    private const val VOICE_HOST = "openspeech.bytedance.com"
    private const val VOICE_PORT = 443
    private const val PROBE_CONNECT_S = 6L
    private const val PROBE_CONNECT_MS = 6000
    private const val PROBE_READ_S = 20L
    private const val MODEL_SAMPLE = 8
    private const val DISK_WARN_MB = 500L
    private const val DISK_FAIL_MB = 100L
}
