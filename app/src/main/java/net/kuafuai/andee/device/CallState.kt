package net.kuafuai.andee.device

import java.util.concurrent.ConcurrentHashMap

/**
 * Whether the tablet is currently in a phone / VoIP call — the "call is
 * happening right now" flag every mic gate reads.
 *
 * Mirror of `MeetingRecorder.isActive()`, opposite direction. Meeting mode:
 * WE hold the mic and gate everyone else from stealing it. Call mode: someone
 * else (dialer, WeChat 语音, WhatsApp, Telegram) is trying to hold the mic
 * and WE have to gate ourselves — mainly [net.kuafuai.andee.wake.WakeWord],
 * which otherwise keeps AudioRecord open and starves the caller app.
 *
 * ## Why a set of keys, not a bool
 *
 * A notification-driven detector has to survive two edges cleanly:
 *
 *  - **Concurrent calls**: WhatsApp missed-call banner arrives while the
 *    dialer ringing notification is still up. Booleans would clear on the
 *    first `onNotificationRemoved` and open the mic mid-call.
 *  - **Notification updates**: Android reissues the same key when text
 *    changes ("calling…" → "connected 00:12"). onNotificationPosted fires
 *    for each; we deduplicate by key and never over-count.
 *
 * Both fall out of a set. [mark] / [clear] are keyed on
 * `StatusBarNotification.key` — the platform's own uniqueness cookie —
 * so posted twice looks like posted once, and removed removes only the one
 * with that key.
 *
 * ## Why not `AudioManager.getMode()`
 *
 * Tried; wrong tool. `MODE_IN_CALL` needs telephony permission on some OEMs,
 * `MODE_IN_COMMUNICATION` is only set *after* the app opens its mic (too
 * late — WakeWord has already collided), and it does not cover the ringing
 * window at all. Notifications land the moment the call event happens on
 * the system, before any of that.
 */
object CallState {

    /** SBN keys currently belonging to a live call notification. */
    private val activeKeys = ConcurrentHashMap.newKeySet<String>()

    /** True while at least one call notification is still up. */
    val isActive: Boolean get() = activeKeys.isNotEmpty()

    fun mark(key: String) {
        activeKeys.add(key)
    }

    fun clear(key: String) {
        activeKeys.remove(key)
    }

    /** Wipe everything — for service shutdown / listener rebind. */
    fun reset() {
        activeKeys.clear()
    }
}
