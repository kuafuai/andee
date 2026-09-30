package net.kuafuai.andee.ui

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager

/**
 * The keyboard, and who is holding it.
 *
 * ## The rule
 *
 * **The device's keyboard belongs to the person. Always — except while the
 * brain is in the middle of typing into somebody else's app.**
 *
 * So the resting IME is whatever the user has set for themselves (a real
 * keyboard they can type on anywhere), and [lendToBrain] / [giveBack] bracket
 * each `type_text`: the brain borrows ADBKeyboard for one call and hands it
 * straight back. The window in which nobody can type by hand is then as short
 * as it can possibly be — one tool call.
 *
 * This is the reverse of where the project started, and the reversal is the
 * point. `type_text` has exactly one channel: a broadcast to ADBKeyboard, which
 * must be *the active IME* for the `commitText` to land. ADBKeyboard is
 * headless — no keyboard UI at all — so while it is the default, a human cannot
 * type a single character in any app on the tablet. The original product
 * decision said that was fine ("平板/手机是 Andee 本体, 无真人键盘场景" — see
 * `ScreenController`), and it was, right up until the card grew a ⌨ button and
 * the phone became a phone again.
 *
 * ## Why the swapping is per call and not per turn
 *
 * Fewer switches would be cheaper — a three-field form is six IME transitions
 * this way instead of two. It is still the right trade, because every transition
 * back to a real keyboard can raise that keyboard over the app the brain is
 * driving, and `type_text`'s read-back is a poll against that app's own tree. A
 * long borrow buys a cheaper automation and pays for it in a screen the brain
 * can no longer see. One call is the smallest unit that does not change what
 * the brain is looking at.
 *
 * ## The write can fail silently, which is why it is read back
 *
 * A settings write a ROM does not honour reports nothing at all — no exception,
 * no log. That is the same shape as the bug that cost the project a day on the
 * ADBKeyboard broadcast action names, so nothing here is believed without
 * reading `DEFAULT_INPUT_METHOD` back.
 *
 * Measured on the tablet this was written for (Xiaomi Pad 5, HyperOS): a normal
 * app calling `InputMethodManager.setInputMethod` **and**
 * `switchToNextInputMethod` is refused both times, while a
 * `Settings.Secure.DEFAULT_INPUT_METHOD` *write* goes through and really does
 * rebind the IME (`dumpsys input_method` → `mCurMethodId` changes ~400 ms
 * later). So the settings write is the only layer — both IMM calls are gone
 * from this file rather than left as dead fallbacks, since they also need a
 * window token and there is no window of ours up while the brain types.
 * `WRITE_SECURE_SETTINGS` is what makes the write possible: granted once at
 * provisioning, see the manifest.
 *
 * Note what is deliberately not a layer either: `showInputMethodPicker`. It is
 * the one option no ROM can refuse, because the *user* makes the change — and it
 * is still wrong, because it can move the keyboard somewhere this app cannot
 * move it back from. Failing is better than succeeding at that.
 */
object ImeSwitch {

    private const val TAG = "Ime"

    /**
     * What the brain needs the keyboard to be while it types.
     *
     * Written out rather than derived from "the one that is not the user's":
     * there is exactly one brain IME, and the other IME could be anything.
     */
    const val BRAIN_IME = "com.android.adbkeyboard/.AdbIME"

    /** The package [BRAIN_IME] lives in, derived so the two cannot drift. */
    private val BRAIN_PKG: String = BRAIN_IME.substringBefore('/')

    /**
     * How long to wait after choosing [BRAIN_IME] before broadcasting into it.
     *
     * The setting and the *binding* are not the same event: `ADB_INPUT_TEXT` is
     * received by the IME's own `onCreate`-registered receiver, so an IME that
     * has been selected but not yet bound is an IME nobody is listening in.
     * Measured: the setting reads back immediately, the IME is bound within
     * ~400 ms of the write. Waiting is the only option — the framework exposes
     * no "is the IME bound" to a normal app, and a broadcast sent too early
     * disappears with no error of any kind.
     */
    private const val BIND_SETTLE_MS = 350L

    /**
     * The active IME, as the system records it. Readable without a permission.
     *
     * **Except on a ROM that lies about "readable".** Measured on a HONOR
     * (MagicOS / Android 15): `Settings.Secure` hands this app `null` for the
     * keyboard settings while `adb shell settings get` reads them back fine, so
     * `current` is the empty string and every caller that compares it against an
     * id — [select]'s read-back, [lendToBrain], [ensureHumanDefault] — is
     * suddenly looking at "not the IME you wanted" no matter what is installed.
     * Nothing here can repair that (there is no second public API for "which IME
     * is active"), so it is logged once and left to [enabled], which *can* fall
     * back. See its doc for the half of this that is fixable.
     */
    fun current(context: Context): String {
        val v = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
            )
        }.getOrNull().orEmpty()
        if (v.isEmpty() && !warnedNoCurrent) {
            warnedNoCurrent = true
            Log.w(TAG, "DEFAULT_INPUT_METHOD is unreadable on this ROM — type_text cannot confirm the keyboard")
        }
        return v
    }

    /** One warning per process is enough for a condition that cannot change. */
    @Volatile
    private var warnedNoCurrent = false

    /**
     * Every IME the user has enabled, id to human label, in the order the
     * system cycles them.
     *
     * ### Two sources, because one of them is empty on some ROMs
     *
     * The list lives in `Settings.Secure.ENABLED_INPUT_METHODS`, and reading it
     * is the documented way. It is also **the half that stops working on a
     * HONOR**: measured on MagicOS / Android 15 with two keyboards enabled —
     * Sogou and ADBKeyboard, `settings get secure enabled_input_methods` showing
     * both — this app read back nothing, so [typable] came out empty and the
     * ball's double-tap answered "这台设备上只有 ADBKeyboard" to a phone that had
     * a perfectly good human keyboard on it. The very same call returns the very
     * same string to `adb shell`, and granting this app
     * `WRITE_SECURE_SETTINGS` changed nothing, so it is the ROM's read
     * restriction and not a permission of ours.
     *
     * `InputMethodManager.getEnabledInputMethodList()` is the other way, API 34
     * and up, and it is *not* affected: it reports through the input-method
     * service rather than through the settings provider. So the two are unioned.
     * Neither alone is right — the settings value is the only source below API
     * 34, and it is the one that carries the user's ordering — and the union is
     * safe in both directions because both name the same components.
     *
     * Ids are normalised through [shortId] before the union: `InputMethodInfo
     * .getId()` and a hand-written settings value are the same component written
     * two ways (`pkg/Full.Class` against `pkg/.Class`), and an un-normalised
     * union would list one keyboard twice and — worse — fail to recognise
     * [BRAIN_IME] in the manager's spelling, which is the exact comparison
     * [typable] and [brainEnabled] are built on.
     *
     * Labels come from `getInputMethodList()`, which only lists *installed*
     * IMEs — an id with no label (possible on a ROM that hides one) falls back
     * to the id, which is ugly but true.
     */
    fun enabled(context: Context): List<Pair<String, String>> {
        val fromSettings = settingsEnabled(context)
        val fromManager = managerEnabled(context)
        val ids = (fromSettings + fromManager).distinct()
        if (ids.isEmpty()) return emptyList()
        val labels = runCatching {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .inputMethodList
                .associate { shortId(it.id) to it.loadLabel(context.packageManager).toString() }
        }.getOrDefault(emptyMap())
        return ids.map { it to (labels[it] ?: it) }
    }

    /**
     * The documented source, and the one that can come back empty — see [enabled].
     *
     * An entry may carry subtypes after a ';'. The IME's own id is the first
     * field, and the id is what setInputMethod wants.
     */
    private fun settingsEnabled(context: Context): List<String> {
        val raw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS,
            )
        }.getOrNull().orEmpty()
        val ids = raw.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { shortId(it.substringBefore(';')) }
            .distinct()
        if (ids.isEmpty()) {
            // Loud on purpose: this is the state that makes the assistant tell a
            // user with a working keyboard that they have none. "The ROM gave us
            // nothing" and "nothing is enabled" are the same empty list here, so
            // the raw value is the only thing that tells them apart.
            Log.w(TAG, "ENABLED_INPUT_METHODS unreadable (raw=\"$raw\") — using InputMethodManager only")
        }
        return ids
    }

    /**
     * API 34's own answer, which survives the ROM above — see [enabled].
     *
     * Empty below API 34 rather than approximated: this method was added in 34,
     * and the settings value is already the whole list on every older device
     * this app ships to (minSdk 30).
     */
    private fun managerEnabled(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return emptyList()
        return runCatching {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList
                .map { shortId(it.id) }
        }.getOrDefault(emptyList())
    }

    /**
     * One component, one spelling.
     *
     * `InputMethodInfo.getId()` and the settings value describe the same IME as
     * `pkg/Full.Class` and `pkg/.Class` respectively, so both are flattened
     * through `ComponentName` before anything compares them.
     */
    private fun shortId(id: String): String =
        runCatching { ComponentName.unflattenFromString(id)?.flattenToShortString() }
            .getOrNull() ?: id

    /**
     * The IMEs a person could actually type on: everything enabled except the
     * headless one.
     *
     * Empty is a real state worth saying out loud rather than a bug to code
     * around — a device whose only IME is ADBKeyboard cannot be typed on by
     * hand anywhere, and no amount of swapping changes that.
     */
    fun typable(context: Context): List<Pair<String, String>> =
        enabled(context).filterNot { it.first == BRAIN_IME }

    /** The human name of an IME, for a sentence the user has to act on. */
    fun label(context: Context, id: String): String =
        enabled(context).firstOrNull { it.first == id }?.second ?: id

    /**
     * Whether an IME from [BRAIN_IME]'s package is installed at all.
     *
     * Asked through `getInputMethodList()` and not
     * `PackageManager.getPackageInfo`: "the package exists" and "there is a
     * keyboard in it" are the same answer for this app, and the second is the
     * true one. An APK that installs but declares no IME service — a truncated
     * download, the wrong file — would otherwise read as present, and the row
     * built on this would send the user to a keyboard list that cannot contain
     * it. `packageName` rather than the exact component, because the broadcast
     * is addressed to the package: a build that renamed its service would still
     * receive `ADB_INPUT_TEXT`.
     *
     * Used by `device/SelfCheck` — see the row there for what is decided from
     * this and [brainEnabled] together. It only works because the manifest
     * declares `QUERY_ALL_PACKAGES`: on API 30+ `getInputMethodList()` is
     * filtered by package visibility like any other query, so without that
     * permission an installed keyboard would read as absent and the row would
     * send the user to download something they already have.
     */
    fun brainInstalled(context: Context): Boolean = runCatching {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .inputMethodList
            .any { it.packageName == BRAIN_PKG }
    }.getOrDefault(false)

    /**
     * Whether the brain's IME is one the user has enabled.
     *
     * The **exact** id, not the package, and the difference is load-bearing:
     * [select] and [lendToBrain] name `com.android.adbkeyboard/.AdbIME`
     * literally, so a build whose service has another name is a build this app
     * cannot switch to. Testing the package here would show a green row beside a
     * `type_text` that cannot land — which is the one thing a check must never
     * do. Red in that case is the truth.
     */
    fun brainEnabled(context: Context): Boolean = enabled(context).any { it.first == BRAIN_IME }

    /**
     * Whether this app is allowed to write the setting directly.
     *
     * Granted once at provisioning (`pm grant`), never at install — the
     * permission is `signature|privileged|development`. Without it the app
     * cannot move the keyboard in either direction, which on MIUI means it
     * cannot type into another app at all; that has to be a loud failure, not a
     * silent one.
     */
    fun canSwitch(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------
    // Borrowing
    // ------------------------------------------------------------------

    /**
     * Take the keyboard for the brain, and return the id that gives it back.
     *
     * `null` means "there was nothing to borrow" — the device was already on
     * [BRAIN_IME], so the caller has nothing to hand back. It does **not** mean
     * the borrow succeeded: the caller must check [current] afterwards, because
     * a refused switch is the failure that matters (the broadcast would go
     * nowhere).
     *
     * Blocks for [BIND_SETTLE_MS] on the way in. Callers are tool threads, never
     * the main one.
     */
    fun lendToBrain(context: Context): String? {
        val was = current(context)
        if (was == BRAIN_IME) return null
        if (!select(context, BRAIN_IME)) return null
        Thread.sleep(BIND_SETTLE_MS)
        return was.ifEmpty { null }
    }

    /**
     * Hand it back. Restores whatever was active when it was borrowed rather
     * than naming a keyboard: the user's choice is theirs, and if they have
     * changed it since, that is the one to go back to. A null [to] — the
     * "nothing was borrowed" case — is a no-op.
     */
    fun giveBack(context: Context, to: String?) {
        if (to.isNullOrEmpty()) return
        select(context, to)
    }

    /**
     * Start-up repair: if the device is sitting on the headless IME as its
     * *default*, put it back on a real keyboard.
     *
     * Only reachable on a device that was set up under the old rule (or by
     * hand). Nobody chooses ADBKeyboard deliberately — it has no keys — and
     * leaving it there means the person cannot type anywhere, which is exactly
     * the bug this class was rewritten to remove. Silent and safe the rest of
     * the time: it does nothing unless the current IME is the brain's *and*
     * there is a real keyboard to move to.
     *
     * @return the id it settled on, or null if it left things alone.
     */
    fun ensureHumanDefault(context: Context): String? {
        if (current(context) != BRAIN_IME) return null
        val human = typable(context).firstOrNull() ?: return null
        if (!canSwitch(context)) {
            Log.w(TAG, "default IME is the headless one and we may not change it")
            return null
        }
        Log.i(TAG, "默认输入法是 ADBKeyboard（它没有键盘界面）→ 换回 ${human.second}")
        return if (select(context, human.first)) human.first else null
    }

    /**
     * Put the device on [want], and report whether it actually is.
     *
     * One layer, because there is one that works and the others cannot even be
     * attempted: `InputMethodManager.setInputMethod` / `switchToNextInputMethod`
     * need a window token belonging to this app, and the brain types while no
     * window of ours is up (the ⌨ field is a separate Activity and is closed by
     * then). They were also both refused by this ROM when they *were* reachable
     * — silently, which is the whole reason this reads the setting back.
     *
     * `false` is not "nothing happened, carry on": it means the keyboard is
     * still on the wrong IME, and a caller about to broadcast into ADBKeyboard
     * must not.
     */
    fun select(context: Context, want: String): Boolean {
        if (want.isEmpty()) return false
        // Already there. This is the restore path's common case in reverse: the
        // device sits on the user's own IME, so putting it back is usually a
        // no-op rather than a second switch.
        if (current(context) == want) return true
        if (!canSwitch(context)) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS — cannot move the keyboard to $want")
            return false
        }
        runCatching {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                want,
            )
        }.onFailure { Log.w(TAG, "settings write refused", it) }
        // Read back. A refused write reports nothing at all, and "it worked"
        // assumed from a silent call is how the broadcast-action-name bug
        // survived as long as it did.
        val took = current(context) == want
        if (took) Log.i(TAG, "→ $want (secure settings)")
        else Log.w(TAG, "keyboard refused to move to $want")
        return took
    }
}
