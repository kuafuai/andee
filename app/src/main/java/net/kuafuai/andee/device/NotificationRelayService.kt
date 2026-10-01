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
 *  - push: a qualifying notification is handed to whichever brain is running,
 *    by `ScreenBodyService.notificationLookRequested` — as a `notification`
 *    event over the hub (the channel asr.final rides, so the hub side needs
 *    zero changes) and as prose to the local brain, which has no event channel
 *    to be taught. Routing it there rather than from here is what fixed a tap
 *    that lit the ball and reached nothing; see that method.
 *
 * **The device forms its own opinion first.** This used to end in a card for
 * every qualifying notification — honest ("the user is the spam filter") and
 * also a tap on every message for the rest of their life, which trains people
 * to dismiss the ball unread. A notification that clears the gauntlet below now
 * goes to [net.kuafuai.andee.brain.NotificationTriage], and the card is what
 * happens when that cannot decide.
 *
 * The gauntlet, in order, and each stage exists because of a different cost:
 *
 *  1. **[inScope]** — the user's setting. Three tiers; 关 means the ring buffer
 *     and nothing else.
 *  2. **[isStructural]** — only in the 全部 tier. Progress bars and group
 *     summaries are a status display, not an event.
 *  3. **[offerForTriage]** — a [SETTLE_MS] debounce per package. A group chat
 *     posts one notification per message; the last one of a burst is the only
 *     one worth reading.
 *  4. **[deliver]** — not while a task is running (the brain is mid-job on this
 *     very screen), and not past [Budget]'s hourly cap.
 *
 * Only then is a model call spent. Everything upstream of that line is free;
 * everything downstream is the user's API key.
 */
class NotificationRelayService : NotificationListenerService() {

    /**
     * The debounce runs here, and so does [deliver].
     *
     * Not a new thread: [offerForTriage]'s whole job is to let a later
     * notification cancel an earlier one, which needs the two to be ordered
     * against each other, and a `Handler` is the cheapest ordering there is.
     * Nothing on it blocks — [deliver] hands the model call to a worker.
     */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Package → the settle timer waiting on it. [main] only. */
    private val pending = HashMap<String, Runnable>()

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
        if (!inScope(sbn)) return
        offerForTriage(n)
    }

    /**
     * Is this notification one the device is allowed to think about?
     *
     * Scope only — *what* happens to it is
     * [net.kuafuai.andee.brain.NotificationTriage]'s call. Three tiers, because
     * the honest answers to "which apps?" are "none", "the ones a person
     * writes to you from", and "everything"; a per-app checklist is a screen
     * nobody finishes and a list that goes stale the next time the user
     * installs something.
     */
    private fun inScope(sbn: StatusBarNotification): Boolean =
        when (net.kuafuai.andee.config.VoiceConfig.notifyScope(this)) {
            net.kuafuai.andee.config.VoiceConfig.NOTIFY_OFF -> false
            net.kuafuai.andee.config.VoiceConfig.NOTIFY_CHAT -> sbn.packageName in PUSH_PKGS
            else -> !isStructural(sbn)
        }

    /**
     * Notifications that are a *status display* rather than an event, filtered
     * out of the 全部 tier.
     *
     * Not taste, and not a blocklist of apps: these are the ones the platform
     * itself marks as not-an-event. An ongoing notification is a thing that is
     * *still happening* — a download, a music player, a foreground service, our
     * own ball — and it re-posts every time its progress bar moves, so a device
     * watching "everything" would otherwise triage a file copy forty times.
     * A group summary is a duplicate of children we are already seeing.
     *
     * `CATEGORY_CALL` is in here for a different reason: a ringing phone is
     * genuinely an event, and it is one [CallState] and the wake-word gate
     * already handle between them. The brain reading out "someone is calling
     * you" over the top of the ringtone helps nobody.
     *
     * The 聊天类 tier does not consult this — [PUSH_PKGS] is already the
     * narrower statement, and a chat app that marks a message ongoing (some do,
     * for a pinned conversation) should still get through.
     */
    private fun isStructural(sbn: StatusBarNotification): Boolean {
        val flags = sbn.notification.flags
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return true
        return sbn.notification.category in STRUCTURAL_CATEGORIES
    }

    /**
     * Hold a notification briefly, then hand the newest one over.
     *
     * **The wait is the point, not a politeness.** A group chat does not post
     * one notification, it posts one per message, each re-using the same key
     * and carrying a higher unread count than the last. Triaging each would
     * spend a model call per message to answer a question that gets a better
     * answer by waiting: the final post of a burst carries the whole thread's
     * count and its latest line, which is what the user would want read out
     * anyway. So a later notification from the same package **replaces** the
     * pending one rather than queueing behind it.
     *
     * Per package, not globally: two people messaging on two apps are two
     * things to know about, and coalescing them would lose one.
     *
     * Debounce state is main-thread-only; [onNotificationPosted] arrives on a
     * binder thread, hence the hop.
     */
    private fun offerForTriage(n: JSONObject) {
        val pkg = n.optString("pkg")
        main.post {
            pending.remove(pkg)?.let { main.removeCallbacks(it) }
            val fire = Runnable {
                pending.remove(pkg)
                deliver(n)
            }
            pending[pkg] = fire
            main.postDelayed(fire, SETTLE_MS)
        }
    }

    /**
     * The burst has settled. Main thread.
     *
     * Everything with a brain in it is the service's
     * ([net.kuafuai.andee.ScreenBodyService.handleNotification]) and everything
     * about the card is still ours, which is the same division of labour
     * `notificationLookRequested` already established. `handled == false` means
     * the device could form no opinion — then the signboard goes up and the user
     * decides, exactly as this service did before any of this existed.
     */
    private fun deliver(n: JSONObject) {
        val svc = net.kuafuai.andee.ScreenBodyService.get()
        if (svc?.dispatcherForNotification()?.isTaskActive() == true) {
            // A task is running: the ball is busy gripping the ledge and the
            // brain is mid-job on this very screen. Interrupting it now — with a
            // signboard, or worse by starting a second turn — would yank the
            // window out from under the perch and queue work against the task's
            // own actions. The message stays in the ring buffer
            // (device.notifications) and gets read when the job is done.
            android.util.Log.i("Body", "notification held: a task is running (${n.optString("app")})")
            return
        }
        if (!Budget.take()) {
            // Over the cap: ring buffer only, and said out loud in the log
            // because a user whose messages stopped being read deserves a reason
            // to be findable. The cap is what stops a pathological app from
            // turning the user's API key into a subscription.
            android.util.Log.w(
                "Body",
                "notification triage at the hourly cap; ring buffer only (${n.optString("app")})",
            )
            return
        }
        // The triage is a network round trip, so it cannot happen here.
        Thread {
            val handled = runCatching { svc?.handleNotification(n) }.getOrNull() ?: false
            if (!handled) net.kuafuai.andee.ui.CardUi.post { showSignboardFor(n) }
        }.start()
    }

    private fun showSignboardFor(n: JSONObject) {
        // The fallback, not the default anymore: a quiet signboard for the
        // notifications the device could form no opinion about. Only a tap on
        // 看看 forwards to the brain — on this path the user is still the spam
        // filter, which is what the whole service used to be.
        //
        // The labels come from [lctx] because this is a Service: per-app locale
        // does not reach a Service-owned window, so the signboard is built in
        // the user's language explicitly. Wrap locally rather than caching, so a
        // language switch takes effect on the next notification.
        val lctx = AppLocale.wrap(this)
        val latest = n.optString("latest")
        val count = n.optInt("count", 1)
        val appLabel = n.optString("app")
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

    /**
     * The 聊天类 tier: apps a *person* writes to the user from.
     *
     * This is the default scope, and the reason it is a hardcoded list rather
     * than a per-app screen the user fills in is that the question it answers is
     * "which apps carry a human on the other end", which does not change when
     * they install something. The 全部 tier is there for everyone who disagrees.
     *
     * [isStructural] is deliberately *not* applied to this tier — see its KDoc.
     */
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
         * How long a package's burst is allowed to settle before the newest
         * notification in it is triaged. See [offerForTriage].
         *
         * Three seconds is a compromise between two things that both hurt. Too
         * short and a twelve-message burst costs twelve model calls and reads
         * out the second message of a conversation. Too long and the user has
         * already picked the tablet up and read it themselves, which makes the
         * device's announcement an echo.
         */
        private const val SETTLE_MS = 3_000L

        /**
         * `Notification.category` values that mean "this is a status display",
         * dropped from the 全部 tier by [isStructural].
         *
         * `CATEGORY_CALL` is in here for a different reason than the rest: a
         * ringing phone genuinely is an event, and it is one [CallState] and the
         * wake-word gate already handle between them.
         */
        private val STRUCTURAL_CATEGORIES = setOf(
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_SYSTEM,
            Notification.CATEGORY_CALL,
        )

        /**
         * A ceiling on how many notifications an hour may be triaged.
         *
         * **This is about the user's money, not about taste.** Everything
         * upstream of [deliver] is free; a triage is a request to a paid
         * endpoint. The debounce handles a chatty group, and the scope setting
         * handles a chatty device, but neither bounds the case this is for: one
         * app with a bug, re-posting a distinct notification in a loop at three
         * in the morning, against a key that bills per call. Without a cap that
         * is a subscription the user did not buy.
         *
         * A fixed window rather than a sliding one, deliberately: the question
         * being answered is "has something gone wrong", and a window boundary
         * that lets a burst through at :59 and again at :00 is not a failure of
         * that question. A ring of timestamps would be more accurate about a
         * thing nobody needs accuracy about.
         *
         * Running out is not an error — the notification is still in the ring
         * buffer and `device.notifications` still reads it back. It is the
         * *opinion* that is skipped, not the record.
         *
         * [deliver] is the only caller and it runs on [main], so no lock.
         */
        private object Budget {
            private const val PER_HOUR = 30
            private const val WINDOW_MS = 60 * 60 * 1000L
            private var windowStart = 0L
            private var used = 0

            /** Claim one. False means the cap is reached for this window. */
            fun take(): Boolean {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - windowStart >= WINDOW_MS) {
                    windowStart = now
                    used = 0
                }
                if (used >= PER_HOUR) return false
                used++
                return true
            }
        }

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
