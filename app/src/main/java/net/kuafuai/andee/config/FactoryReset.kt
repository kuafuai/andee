package net.kuafuai.andee.config

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.screen.GroundingLog
import net.kuafuai.andee.ui.ChatHistory
import net.kuafuai.andee.wake.WakeTemplates
import java.io.File

/**
 * Back to a fresh install, on purpose.
 *
 * This is the whole of "恢复出厂设置": one list, walked in order, each entry
 * carrying both the deed and the line the user reads before agreeing to it.
 * The two live together on purpose — a confirmation screen that enumerates by
 * hand is a confirmation screen that goes stale, and a reset that deletes
 * something the user was not shown is worse than no reset at all.
 *
 * What is *not* here, and why:
 *
 *  * the APK's own files, its caches and `cacheDir` — the system owns those and
 *    clears them on its own schedule (the camera's `look_*.jpg` are the only
 *    thing we put there);
 *  * the accessibility service's enabled state and its runtime permissions —
 *    those belong to the system settings app, not to us, and a device whose
 *    assistant has just lost its permissions *and* its configuration is much
 *    harder to bring back than one that only lost the configuration.
 *
 * Nothing here is reversible: there is no backup, and the encrypted blobs
 * ([Notebook], [Vault]) cannot be read once their prefs are gone — the
 * Keystore key that would open them is deleted with the app, and clearing the
 * blob leaves nothing to open anyway.
 */
object FactoryReset {

    private const val TAG = "Body"

    /**
     * One thing that gets wiped.
     *
     * @param label string resource for the order line in the confirmation.
     *   A resource id rather than two strings, so a third language is a file
     *   and not an edit here — see [net.kuafuai.andee.i18n.AppLocale].
     * @param clear the deed. Failures are logged and skipped rather than
     *   aborting the rest: a reset that stops halfway because one directory
     *   was already gone is worse than one that finishes and says so.
     */
    private class Target(
        @StringRes val label: Int,
        val clear: (Context) -> Unit,
    )

    private val TARGETS: List<Target> = listOf(
        Target(R.string.factory_reset_target_keys) { prefs(it, "voice_prefs") },

        Target(R.string.factory_reset_target_notebook) { Notebook.wipe(it) },

        Target(R.string.factory_reset_target_vault) { Vault.wipe(it) },

        Target(R.string.factory_reset_target_wake) {
            WakeTemplates.clear(it)
            WakeTemplates.invalidate()
        },

        Target(R.string.factory_reset_target_history) {
            ChatHistory.wipe(it)
            net.kuafuai.andee.brain.BrainTrace.wipe(it)
        },

        Target(R.string.factory_reset_target_web) { webviewData() },

        Target(R.string.factory_reset_target_grounding) { grounding(it) },
    )

    /**
     * The order lines, for the confirmation screen.
     *
     * Takes a Context rather than a `english: Boolean` flag: the flag existed
     * only to pick between two hardcoded strings here, and it cannot be
     * extended to a third language without touching every call site. The caller
     * passes the *localised* Context — see [net.kuafuai.andee.ui.SettingsUi.lctx].
     */
    fun items(context: Context): List<String> = TARGETS.map { context.getString(it.label) }

    /**
     * Run every target. Returns how many were cleared, which is all the caller
     * needs to log; the per-target outcome is in logcat.
     */
    fun wipe(context: Context): Int {
        // First, and before the notebook goes: the alarms are keyed by todo id
        // and those ids live in the file we are about to erase. Cancel them
        // afterwards and a promise would fire into a device that no longer
        // remembers making it — a notification for something nobody can find
        // any trace of.
        Scheduler.cancelSweep(context)
        for (todo in Notebook.todos(context, "all")) Scheduler.cancelTodo(context, todo.id)

        var cleared = 0
        for (t in TARGETS) {
            runCatching { t.clear(context) }
                .onSuccess {
                    cleared++
                    Log.i(TAG, "factory reset: cleared ${AppLocale.str(context, t.label)}")
                }
                .onFailure {
                    Log.w(TAG, "factory reset: could not clear '${AppLocale.str(context, t.label)}'", it)
                }
        }
        Log.i(TAG, "factory reset: $cleared/${TARGETS.size} targets cleared")
        return cleared
    }

    // ---- the individual deeds ----

    private fun prefs(context: Context, name: String) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /**
     * What the page renderer left behind.
     *
     * `HtmlActivity` loads every composed page as a string against its own base
     * URL (`https://brain.local/`) with DOM storage and JavaScript on — which
     * makes it a real origin: a page can write cookies and localStorage, and
     * both survive in `app_webview/`.
     *
     * Cleared through WebView's own API rather than by deleting that directory.
     * A recursive delete would race whatever the renderer happens to be doing,
     * and what is left in there afterwards (`variations_seed`, `pref_store`,
     * the GPU cache) is WebView's own machinery rather than the user's.
     *
     * Two things deliberately left alone, both because they are not ours:
     * the HTTP cache (clearing it needs a live `WebView` instance, and standing
     * up all of Chromium from a service to delete fetched bytes is a worse
     * trade than the bytes), and `files/profileInstalled` (written by the
     * platform's baseline-profile installer — checked, nothing in this repo
     * writes it, and it carries no user data).
     */
    private fun webviewData() {
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
    }

    /**
     * The grounding log lives on the *external* app dir (it is meant to be
     * pulled off with adb), so it needs a real recursive delete rather than a
     * prefs clear. `commit()` above and `deleteRecursively()` here both report
     * failure honestly — the caller logs which ones did not take.
     *
     * Not [GroundingLog.shutdown]: that closes the writer until the next
     * service start, and the switch going off in prefs is already enough to
     * stop it writing. A log file recreated by a note already in flight is
     * harmless; a log that stays shut after the user turns grounding back on
     * is not.
     */
    private fun grounding(context: Context) {
        val root = File(context.getExternalFilesDir(null), "grounding")
        if (root.exists() && !root.deleteRecursively()) {
            throw IllegalStateException("grounding dir survived deleteRecursively: $root")
        }
    }
}
