package net.kuafuai.andee.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.EditorInfo

/**
 * Typing through **our own accessibility service's** input connection, which is
 * the one channel on this device that costs the user nothing to set up.
 *
 * API 33 gave an `AccessibilityService` that declares `flagInputMethodEditor` a
 * real [InputMethod]: `commitText` on the focused editor's own
 * `InputConnection` — the same path a keyboard takes, and the same reason an app
 * cannot refuse it (refusing would refuse all keyboards). What it is *not* is an
 * IME: it does not have to be installed, enabled, or selected, the person keeps
 * their own keyboard active the whole time, and nothing has to be switched.
 *
 * That is the whole point of it being here. The route it replaces —
 * `ScreenController.typeViaAdbKeyboard` — needs a third-party APK sideloaded, a
 * `Settings.Secure.DEFAULT_INPUT_METHOD` write that only
 * `adb shell pm grant … WRITE_SECURE_SETTINGS` can authorise, and ~0.75 s of
 * lend-and-give-back per call. A user who does not own a computer could not type
 * at all. It stays as the fallback for API 30–32 and for any editor this channel
 * turns out not to reach, because a silent regression in typing is the one this
 * project has paid for twice.
 *
 * Two smaller wins fall out of it, neither of them the reason but both worth
 * keeping: a vault password typed this way never leaves the process (the ADB
 * route hands it to another app over a broadcast), and readback comes from the
 * editor itself via [InputMethod.AccessibilityInputConnection.getSurroundingText]
 * rather than from the accessibility tree — so the WebViews that hide their
 * focused node's text stop being a false negative.
 *
 * Every entry point returns null / false rather than throwing when the channel
 * is not there, because "not there" is an ordinary answer: nothing is focused
 * yet, the editor is self-drawn, the platform is too old.
 */
object A11yIme {

    const val VIA = "a11y_ime"

    /**
     * Replace the focused editor's contents with [text] and read back what the
     * editor now holds.
     *
     * Null means the channel is gone (nothing focused, or pre-33) and **nothing
     * was typed** — the caller may fall back. A non-null return that differs
     * from [text] means the text was committed and the editor disagrees, which
     * is a real failure and not a missing channel.
     */
    fun replace(service: AccessibilityService, text: String): String? {
        val conn = awaitConnection(service) ?: return null
        clear(conn)
        runCatching { conn.commitText(text, 1, null) }.onFailure {
            Log.w(TAG, "commitText failed", it)
            return null
        }
        // commitText is a one-way call into the editor's process, so the value
        // is not readable the instant it returns — the same reason the ADB route
        // polls. Short budget: unlike the accessibility tree, this reads the
        // editor's own buffer, so there is no layout pass to wait for.
        var got = ""
        val deadline = SystemClock.uptimeMillis() + READBACK_BUDGET_MS
        do {
            Thread.sleep(READBACK_POLL_MS)
            got = read(conn) ?: return null
            if (got == text) break
        } while (SystemClock.uptimeMillis() < deadline)
        return got
    }

    /**
     * Fire the focused editor's own IME action.
     *
     * Asked for by name (`actionId`, then `imeOptions`) rather than assuming
     * Enter: the field says whether it is a Send, a Search or a Next, and that
     * is what its listener is registered for. Returns false when there is no
     * connection or the editor declares no action, so the caller can fall back
     * to `ACTION_IME_ENTER`.
     */
    fun editorAction(service: AccessibilityService): Boolean {
        val conn = connection(service) ?: return false
        val info: EditorInfo = runCatching { service.inputMethod?.currentInputEditorInfo }
            .getOrNull() ?: return false
        val action = if (info.actionId != 0) {
            info.actionId
        } else {
            info.imeOptions and EditorInfo.IME_MASK_ACTION
        }
        if (action == 0 || action == EditorInfo.IME_ACTION_NONE) return false
        return runCatching { conn.performEditorAction(action); true }.getOrDefault(false)
    }

    // ---- internals ----

    /**
     * [connection], but give a session that is still attaching a moment to
     * arrive.
     *
     * The caller only gets here with a focused editable node already in hand,
     * so "no connection" at this instant usually means the editor took focus
     * a few frames ago and the input session has not reached us yet — measured
     * right after the service rebinds. Returning null there is not a harmless
     * miss: it sends the caller to the ADBKeyboard route, which on a device
     * with no `WRITE_SECURE_SETTINGS` is the hard error this channel exists to
     * retire.
     */
    private fun awaitConnection(service: AccessibilityService): InputMethod.AccessibilityInputConnection? {
        val deadline = SystemClock.uptimeMillis() + SESSION_WAIT_MS
        while (true) {
            connection(service)?.let { return it }
            if (SystemClock.uptimeMillis() >= deadline) return null
            Thread.sleep(SESSION_POLL_MS)
        }
    }

    private fun connection(service: AccessibilityService): InputMethod.AccessibilityInputConnection? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val im = runCatching { service.inputMethod }.getOrNull() ?: return null
        // Started, not merely present: the object outlives the editor, and
        // committing into a finished session types into nothing at all.
        if (!runCatching { im.currentInputStarted }.getOrDefault(false)) return null
        return runCatching { im.currentInputConnection }.getOrNull()
    }

    /**
     * Empty the field, because [replace] is a replace and `commitText` inserts.
     *
     * Deletes around the cursor when the editor reports one, which is what a
     * real IME does and what every `BaseInputConnection` supports. When it does
     * not (selection `-1`, some self-drawn editors), the retrieved range is
     * selected instead and `commitText` overwrites the selection — a second
     * route rather than a guess, because appending to a field the brain believes
     * it cleared is the failure that reads as the model typing twice.
     */
    private fun clear(conn: InputMethod.AccessibilityInputConnection) {
        val st = runCatching { conn.getSurroundingText(MAX_READ, MAX_READ, 0) }.getOrNull() ?: return
        val txt = st.text?.toString().orEmpty()
        if (txt.isEmpty()) return
        val start = st.selectionStart
        val end = st.selectionEnd
        runCatching {
            if (start >= 0 && end >= 0) {
                conn.deleteSurroundingText(start, txt.length - end)
            } else {
                conn.setSelection(st.offset, st.offset + txt.length)
            }
        }
    }

    /** The editor's current contents, or null when the connection died. */
    private fun read(conn: InputMethod.AccessibilityInputConnection): String? {
        val st = runCatching { conn.getSurroundingText(MAX_READ, MAX_READ, 0) }.getOrNull()
            ?: return null
        return st.text?.toString().orEmpty()
    }

    private const val TAG = "A11yIme"

    /**
     * Both halves of the surrounding-text window. Generous because this is how
     * the field is cleared, and text left beyond the window would survive a
     * clear; a code editor's buffer can defeat it, and that is the case the
     * readback then reports honestly.
     */
    private const val MAX_READ = 8192
    private const val READBACK_BUDGET_MS = 600L
    private const val READBACK_POLL_MS = 60L
    private const val SESSION_WAIT_MS = 400L
    private const val SESSION_POLL_MS = 50L
}
