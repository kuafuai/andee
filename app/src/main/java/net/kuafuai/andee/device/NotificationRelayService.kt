package net.kuafuai.andee.device

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The proactive sense: every notification on the device lands here, gets
 * distilled to (pkg, title, text, when), and is kept in a small ring for the
 * brain to read on demand. This is what turns the ball from "answers when
 * asked" into "knows something happened".
 *
 * Delivery is BOTH:
 *  - pull: `device.notifications` reads the ring (with since_ms increments)
 *  - push: a qualifying notification the user taps 看看 on is ALSO handed to
 *    whichever brain is running, by `ScreenBodyService.notificationLookRequested`
 *    — as a `notification` event over the hub (the channel asr.final rides, so
 *    the hub side needs zero changes) and as prose to the local brain, which has
 *    no event channel to be taught. Routing it there rather than from here is
 *    what fixed a tap that lit the ball and reached nothing; see that method.
 *
 * Push FILTERING (the part that decides whether the brain gets interrupted):
 * message apps (chat/SMS/mail) push immediately — a human is trying to reach
 * the user, which is exactly what "proactive" means. Everything else
 * (updates, news, system junk) only lands in the ring; if the brain wants
 * ambient awareness it can poll. Constant popups would train the user to
 * ignore the ball, which is worse than not pushing at all.
 */
class NotificationRelayService : NotificationListenerService() {

    /**
     * Bound *right now*, which is the only question worth asking — see
     * [ensureBound] for why the permission flag can't answer it.
     */
    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
        // Whatever call notifications we thought were up, we can no longer
        // observe their removal. Better to reset and let the next round of
        // posts refill than to leave a dead key in [CallState] and mute the
        // wake word forever.
        net.kuafuai.andee.device.CallState.reset()
        // The documented way back. Asking for a rebind from inside the
        // disconnect is not circular: the system has already let go of us at
        // this point, and the request is queued against the component, not
        // against this instance.
        runCatching { requestRebind(ComponentName(this, NotificationRelayService::class.java)) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        // Was this key one we counted? Read before clearing so we can log the
        // transition. Cheap; the set is a handful of entries at most.
        val wasCall = net.kuafuai.andee.device.CallState.isActive
        // Unconditional clear: cheaper than re-checking whether this was ever
        // a call, and safe — clearing a key we never marked is a no-op.
        net.kuafuai.andee.device.CallState.clear(sbn.key)
        if (wasCall && !net.kuafuai.andee.device.CallState.isActive) {
            android.util.Log.i(
                "Body",
                "call state: CLEARED (last was ${sbn.packageName}) — wake word may resume",
            )
        }
    }

    /**
     * Whether [sbn] is a live phone / VoIP call notification.
     *
     * Two layers, because no single signal covers every app:
     *
     *  - `CATEGORY_CALL` — the platform's own contract. AOSP dialer,
     *    WhatsApp, Telegram set it. That is the free path; when it fires we
     *    do not need to look at anything else.
     *
     *  - **WeChat/QQ do not set `CATEGORY_CALL`** (measured on real device,
     *    2026-09-22: voice call notif came in with `cat=null`). Their voice
     *    call notif carries a distinctive text — `语音通话中` / `视频通话
     *    中` / `来电` — and they are the *only* WeChat/QQ notifications with
     *    that text; ordinary chat messages read "13个联系人发来66条消息" or
     *    "张三: 在吗", never those exact strings. So text pattern is a safe
     *    fallback here.
     *
     * The text patterns are Chinese-only on purpose: this fallback exists
     * for WeChat/QQ specifically. Anything else uses the CATEGORY_CALL path
     * or is not something we can reliably tell apart from a chat message.
     */
    private fun isCallNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.notification.category == Notification.CATEGORY_CALL) return true
        if (sbn.packageName in CN_VOICE_APPS) {
            val extras = sbn.notification.extras ?: return false
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            for (pat in CN_CALL_KEYWORDS) {
                if (pat in title || pat in text) return true
            }
        }
        return false
    }

    /** Apps whose call notifications skip CATEGORY_CALL, so we fall back on text. */
    private val CN_VOICE_APPS = setOf(
        "com.tencent.mm",       // 微信
        "com.tencent.mobileqq", // QQ
    )

    /**
     * Text patterns that identify a voice/video call notification inside
     * [CN_VOICE_APPS]. Ordered by specificity; the more specific patterns
     * (e.g. "语音通话中") are unambiguous, "来电" is broad but only checked
     * against apps in [CN_VOICE_APPS].
     *
     * **Never route these through `AppLocale`.** They are compared against text
     * WeChat and the other apps in [CN_VOICE_APPS] write into their own
     * notifications, and those follow *their* language setting, not ours.
     * Translating this list does not change a word the user reads — it makes
     * call detection stop working on exactly the devices where the strings
     * would have changed, which is silent: a missed call simply never becomes
     * an event. Same rule for the unread-count pattern in
     * [onNotificationPosted] and for `ScreenController`'s clipboard labels.
     */
    private val CN_CALL_KEYWORDS = listOf(
        "语音通话中",
        "视频通话中",
        "正在语音通话",
        "正在视频通话",
        "来电",
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return // our own stuff, e.g. ASR toast

        val extras: Bundle? = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        val isCall = isCallNotification(sbn)
        android.util.Log.i(
            "Body",
            "notif posted pkg=${sbn.packageName} cat=${sbn.notification.category} " +
                    "isCall=$isCall title='${title.take(40)}' text='${text.take(60)}' key=${sbn.key}",
        )
        if (isCall) {
            net.kuafuai.andee.device.CallState.mark(sbn.key)
            android.util.Log.i(
                "Body",
                "call state: ACTIVE (${sbn.packageName}) — wake word will release mic",
            )
        }
        if (extras == null) return
        if (title.isEmpty() && text.isEmpty()) return
        // Notification.text can be "隋小波: 在吗" (one message) or
        // "[12条]隋小波: 在吗" (a stack: N unread, latest shown). The brain
        // needs the difference — "reply to one message" vs "a conversation
        // with 12 unread happened while you were away" — so it is parsed here
        // rather than leaving a "[12条]" blob for the LLM to guess at.
        //
        // The "条" in the pattern is the sending app's word, not ours. Do not
        // translate it — see [CN_CALL_KEYWORDS].
        var count = 1
        var headline = text
        val m = Regex("^\\[(\\d+)条]\\s*(.*)$").find(text.trim())
        if (m != null) {
            count = m.groupValues[1].toIntOrNull() ?: 1
            headline = m.groupValues[2]
        }
        val n = JSONObject()
            .put("pkg", sbn.packageName)
            .put("app", appLabel(sbn.packageName))
            .put("title", title.take(80))
            .put("text", text.take(160))
            .put("count", count)
            .put("latest", headline.take(160))
            .put("when", sbn.postTime)
        Store.add(n)
        if (sbn.packageName in PUSH_PKGS) {
            // One hop to the main thread, where the task flag and CardUi both
            // live. onNotificationPosted arrives on a binder thread; reading
            // dispatcher state cross-thread is exactly the kind of stale read
            // the hop exists to avoid.
            net.kuafuai.andee.ui.CardUi.post {
                if (net.kuafuai.andee.ScreenBodyService.get()
                        ?.dispatcherForNotification()?.isTaskActive() == true
                ) {
                    // A task is running: the ball is busy gripping the ledge
                    // and the brain is mid-job on this very screen. A
                    // signboard now would yank the window out from under the
                    // perch and queue the user's answer against the task's
                    // own actions. The message stays in the ring buffer
                    // (device.notifications) — it gets read when the job is
                    // done, not in the middle of it.
                    return@post
                }
                showSignboardFor(sbn, n)
            }
        }
    }

    private fun showSignboardFor(
        sbn: StatusBarNotification,
        n: JSONObject,
    ) {
        // Card-first, not brain-first: a quiet signboard asking the user
        // beats waking the brain on every ping. Only a tap on 看看
        // actually forwards to the brain — the user is the spam filter.
        //
        // The labels come from [lctx] because this is a Service: per-app locale
        // does not reach a Service-owned window, so the signboard is built in
        // the user's language explicitly. Wrap locally rather than caching, so a
        // language switch takes effect on the next notification.
        val lctx = AppLocale.wrap(this)
        val latest = n.optString("latest")
        val count = n.optInt("count", 1)
        val appLabel = appLabel(sbn.packageName)
        val cardTitle = if (count > 1) {
            lctx.getString(R.string.dev_notif_card_unread, appLabel, count)
        } else {
            lctx.getString(R.string.dev_notif_card_title, appLabel)
        }
        val lookLabel = lctx.getString(R.string.dev_notif_card_look)
        val ignoreLabel = lctx.getString(R.string.dev_notif_card_ignore)
        net.kuafuai.andee.ui.CardUi.ask(
            "$cardTitle\n$latest",
            listOf(lookLabel, ignoreLabel),
            timeoutMs = 20_000L,
        ) { result ->
            if (result.button == lookLabel) {
                // "Heard you, on it" — the same reaction the ball gives to
                // a spoken instruction: perch on the edge, light the rim,
                // stay lit while the brain works. Reused rather than
                // reimplemented so the notification path can never drift
                // from the voice path in what "working" looks like. The
                // 5-minute leak-guard backstop applies too: if the brain
                // never answers, the glow still goes out by itself.
                //
                // Lighting it is the service's job now, together with the
                // routing — it used to be these two lines, and the second one
                // reached the hub and nothing else, so on a local-brain device
                // the first one lit a glow that nothing would put out. See
                // `ScreenBodyService.notificationLookRequested`, which also
                // keeps the ordering this comment used to explain: the task is
                // lit before `CardUi`'s pending `hideSignboard` runs, which is
                // what tells it "the ledge is wanted" so the shrink-then-slide
                // happens as one motion.
                net.kuafuai.andee.ScreenBodyService.get()?.notificationLookRequested(n)
            }
        }
    }

    private fun appLabel(pkg: String): String = when (pkg) {
        "com.tencent.mm" -> "微信"
        "com.tencent.mobileqq" -> "QQ"
        "com.alibaba.android.rimet" -> "钉钉"
        "com.ss.android.lark" -> "飞书"
        "com.whatsapp" -> "WhatsApp"
        "com.android.mms", "com.android.messaging" -> "短信"
        else -> pkg.substringAfterLast('.')
    }

    /** Who is allowed to interrupt the brain. Chat first; trim by taste. */
    private val PUSH_PKGS = setOf(
        "com.tencent.mm",            // 微信
        "com.tencent.mobileqq",      // QQ
        "com.alibaba.android.rimet", // 钉钉
        "com.ss.android.lark",       // 飞书
        "com.whatsapp",
        "com.immomo.momo", "com.sdu.didi.psnger",
        "com.android.mms", "com.android.messaging", // 短信(含验证码)
        "com.gmail", "com.sina.weibo",
    )

    object Store {
        private const val CAP = 50
        private val lock = Any()
        private val ring = ArrayDeque<JSONObject>()

        fun add(n: JSONObject) = synchronized(lock) {
            ring.addLast(n)
            while (ring.size > CAP) ring.removeFirst()
        }

        fun snapshot(sinceMs: Long, limit: Int): JSONArray = synchronized(lock) {
            val arr = JSONArray()
            for (n in ring) {
                if (n.optLong("when", 0) >= sinceMs) {
                    arr.put(n)
                    if (arr.length() >= limit) break
                }
            }
            arr
        }

        fun count(): Int = synchronized(lock) { ring.size }
    }

    companion object {

        /**
         * Whether the system currently has us bound. Not derivable from the
         * permission flag — see [ensureBound].
         */
        @Volatile
        private var connected = false

        /**
         * Kick the system into rebinding us if the permission is granted but
         * nothing is listening.
         *
         * This exists because of a failure mode that looks exactly like the
         * feature being deleted: reinstalling the APK kills the process, and
         * the system frequently does *not* rebind the listener afterwards. The
         * permission row in Settings still reads as on, `status()` used to
         * agree with it, and every notification silently went nowhere — no
         * signboard, no error, nothing in the log. A developer reinstalling
         * twenty times a day hits this constantly, and so does a user after a
         * store update.
         *
         * `requestRebind` is the platform's own answer to it. Cheap, idempotent
         * and a no-op when we are already bound, so it is safe to fire on every
         * service start.
         */
        fun ensureBound(context: Context) {
            if (connected) return
            val cn = ComponentName(context, NotificationRelayService::class.java)
            if (!isPermitted(context, cn)) return // nothing to rebind; the user has to grant it
            runCatching { requestRebind(cn) }
        }

        private fun isPermitted(context: Context, cn: ComponentName): Boolean =
            Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            )?.contains(cn.flattenToString()) == true

        /** Is the listener permitted *and* actually bound? Says so honestly if not. */
        fun status(context: Context): JSONObject {
            val cn = ComponentName(context, NotificationRelayService::class.java)
            val permitted = isPermitted(context, cn)
            return JSONObject()
                .put("listener_enabled", permitted)
                // Reported separately from the permission on purpose: the two
                // disagree after every reinstall, and the old single flag
                // reported the permission — i.e. it claimed everything was fine
                // in precisely the case where notifications were being dropped.
                .put("listener_connected", connected)
                .put("buffered", Store.count())
                .put(
                    "hint",
                    when {
                        !permitted ->
                            "Notification access is off. Ask the user: Settings → Notifications → Notification access → allow this app."

                        !connected ->
                            "Notification access is granted but the listener is not bound; a rebind has been requested. If this persists, ask the user to toggle the permission off and on."

                        else -> ""
                    }
                )
        }
    }
}
