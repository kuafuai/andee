package net.kuafuai.andee.net

import android.os.Handler
import android.os.Looper
import net.kuafuai.andee.R
import net.kuafuai.andee.config.CronExpr
import net.kuafuai.andee.config.Notebook
import net.kuafuai.andee.config.Scheduler
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.screen.ScreenController
import net.kuafuai.andee.ui.EdgeRippleUi
import net.kuafuai.andee.ui.FloatingWindowUi
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Routes WS `request` messages (method + params) to controller methods.
 *
 * All screen actions live under the `screen.*` namespace. TTS/ASR/audio
 * command routing will be added later under their own namespaces
 * (`tts.speak`, `asr.*`, etc.).
 *
 * Contract: return a JSON-serializable value (usually JSONObject) or throw.
 * Thrown exceptions become error responses in [BodyWsServer].
 *
 * Gesture-based commands (tap / swipe / long_press) go through
 * [ScreenController] which calls `dispatchGesture`. Those events run through
 * the real touch pipeline and would land on our own overlay first, so we
 * temporarily flip the floating window to non-touchable around each such
 * call — see [passthroughForGesture]. Eid taps (tap_id / long_press_id) are
 * gestures too — they resolve to pixel centers and dispatch a real touch —
 * so they go through the same dance. Accessibility-API-based commands
 * (type, global, ui_tree) don't touch the input layer and skip this.
 *
 * `screen.screenshot` has the mirror-image problem: it doesn't touch the input
 * layer, but the overlay *is* part of the display capture — see
 * [blankedForCapture].
 *
 * Every `screen.*` command also folds the card into the corner ball first —
 * see [ensureCompact]. What used to be a tool the brain had to remember to
 * call is compiled in now, so "the assistant is operating an app" and "the
 * user is able to see the app" never diverge. Only for callers that pass
 * `driving = true`, though: see [dispatch].
 *
 * **Two audiences.** Three strings in here go to the user — the vault subtitle,
 * and the two `ui.ask` button labels next to it — so they come from
 * `AppLocale`. *Everything else* in this file is a tool result: it is read by
 * the model, it is often a sentence telling the model off, and it is English
 * like the rest of the model-facing set (see the top of `res/values-en/`
 * `strings.xml`). A `设置 → 保险箱` left inside one of those English sentences
 * is not a leak and must not be translated: it is a literal path on the screen
 * that the model should repeat to the user verbatim.
 */
class CommandDispatcher(
    private val screen: ScreenController,
    private val window: FloatingWindowUi,
    private val ripple: EdgeRippleUi,
    private val marker: net.kuafuai.andee.ui.TapMarkerUi,
    private val deviceInfo: net.kuafuai.andee.device.DeviceInfoController,
    private val deviceActions: net.kuafuai.andee.device.DeviceActionsController,
    private val deviceComms: net.kuafuai.andee.device.DeviceCommsController,
    private val permissions: net.kuafuai.andee.device.PermissionsController,
    private val meeting: net.kuafuai.andee.meeting.MeetingRecorder,
    private val dog: net.kuafuai.andee.dog.DogMotion,
    private val appContext: android.content.Context,
    /**
     * Fallback for methods this dispatcher does not itself know. Wired to the
     * call extension by [ScreenBodyService]: if a call peripheral has
     * registered a tool by this name (`pickup_call`, `end_call`, …), forward
     * the request; if not, the lambda throws, and the throw becomes the brain's
     * `unknown method` reply like it always did.
     *
     * Kept as a lambda rather than a direct [CallExtensionRegistry] handle so
     * the dispatcher stays unaware of the WS transport — same shape that lets
     * tests hand it a stub.
     */
    private val forwardExternal: (method: String, params: JSONObject?) -> Any? =
        { method, _ -> throw IllegalArgumentException("unknown method: $method") },

    /**
     * Told when the user dismisses a page `ui.show_html` put up, with how long
     * it was on screen.
     *
     * A callback rather than a direct handle on the brain because the brain is
     * three things ([ScreenBodyService.hubClient], its `wsServer`, and the
     * on-device [net.kuafuai.andee.brain.LocalBrain]) and this class reaches
     * none of them — it is the device's tool surface, deliberately unaware of
     * the transports. [ScreenBodyService] wires it.
     */
    private val onPageClosed: (title: String, seconds: Int) -> Unit = { _, _ -> },

    /**
     * Read a brain-facing card's text aloud as the card appears — the
     * `ui.ask` question, the `ui.alert` notice — and the whole of
     * [`tts.speak`], which is the same request with no card attached.
     *
     * Returns whether the words were actually handed to TTS: the mute rules
     * live behind this lambda and they can refuse. `ui.ask` / `ui.alert`
     * ignore the answer (the card is up either way and that is the part they
     * promised); `tts.speak` has nothing else, so it reports it rather than
     * returning a cheerful `{spoken:true}` over a silence — a muted line and
     * a spoken one must not be indistinguishable from the caller's side.
     *
     * A callback rather than a direct [net.kuafuai.andee.tts.TtsController]
     * handle for the same reason [onPageClosed] is one: the TTS mute rules
     * (meeting, phone call), the stop gate and `stripMarkdown` all live in
     * [ScreenBodyService], where the final answer already passes through
     * them — a second copy of those rules is where this feature would rot.
     * The card is for a user who may be looking anywhere but the screen;
     * a question nobody hears just waits in silence for its timeout.
     */
    private val sayAloud: (String) -> Boolean = { false },
) {
    private val ui = Handler(Looper.getMainLooper())

    /** Leak guard, not a guess about thinking time — see [setBusy]. */
    private val endDriving = Runnable { setBusy(false) }

    /** Written on the main thread, read by worker threads: card already folded. */
    @Volatile
    private var compacted = false

    /** Main thread only: a task is being thought about or executed right now. */
    private var busy = false

    /**
     * @param driving whether the caller is operating the device on purpose —
     *   the brain over the hub socket, or the user's own top-bar buttons. False
     *   is a passive observer: the debug/viewer client on 9008 polls
     *   `screen.ui_tree` and `screen.screenshot` on a timer, and a read on a
     *   timer must not rearrange what the user is looking at. Without this the
     *   card folded itself into the corner once a minute with nobody driving —
     *   the same distinction [touchBusy] already draws for the edge glow, which
     *   the fold was simply missing.
     * @param silent the turn is the quiet-hour sweep: the brain is reviewing a
     *   finished conversation by itself. Only the `note.*` notebook methods are
     *   allowed through — see the guard at the top of this function.
     */
    fun dispatch(
        method: String,
        params: JSONObject?,
        driving: Boolean = true,
        silent: Boolean = false,
    ): Any? {
        // The quiet-hour sweep runs with nobody watching: it may think, and it
        // may write to the notebook, but it must not touch the device. Its
        // prompt says the same thing — this is the copy that cannot drift, and
        // it lives here rather than in LocalBrain because this is the single
        // place every method passes through. Checked before the fold below,
        // so a silent call cannot so much as move the card.
        if (silent && !method.startsWith("note.")) {
            throw IllegalStateException(
                "A silent turn may only use the notebook (note.*); it must not touch the device: $method",
            )
        }
        // Every command below reaches into the real device, and every one of
        // them is worth watching, so get out of the way *before* doing it.
        // Prefix rather than an explicit list, so adding a tool stays the
        // two-file edit it already was ([ToolSchemas] + here) — at the cost of
        // anything new under `screen.*` inheriting the fold, which is right
        // only as long as everything in that namespace drives the device.
        if (driving && method.startsWith("screen.")) {
            ensureCompact()
            // Unconditional among drivers, unlike [touchBusy]: "a tool just
            // ran" is true whoever asked, and a ball that nods when the top bar
            // drives the device is telling the truth. Only the glow needs to
            // know whose tool call it was.
            window.pulseTool()
        }
        return when (method) {
            // ---- device.* : read-only context queries ----------------------
            // Deliberately outside the `screen.` prefix so they never fold the
            // card: asking "am I online" is a conversation, not an actuation.
            // All bounded-latency, no listeners outlive the call. `device.scan`
            // and `device.look` fold anyway, in their own branches — they are
            // queries that draw a camera preview the user has to see.
            "device.connectivity" -> deviceInfo.connectivity()
            "device.apps" -> deviceInfo.installedApps()
            "device.location" -> deviceInfo.location(
                timeoutMs = params?.optLong("timeout_ms", 8000L) ?: 8000L
            )
            "device.steps" -> deviceInfo.steps(
                timeoutMs = params?.optLong("timeout_ms", 3000L) ?: 3000L
            )
            "device.battery" -> deviceInfo.battery()

            // ---- device.* : actions, zero/low-friction ---------------------
            "device.launch" -> deviceActions.launchApp(
                pkg = params?.optString("pkg")?.ifEmpty { null },
                label = params?.optString("label")?.ifEmpty { null },
            )
            "device.volume" -> deviceActions.volume(
                level = params?.optInt("level", -1).let { if (it == null || it < 0) null else it },
                mute = if (params?.has("mute") == true) params.optBoolean("mute") else null,
            )
            "device.brightness" -> deviceActions.brightness(
                if (params?.has("brightness") == true) params.optInt("brightness") else null
            )
            "device.open_url" -> deviceActions.openUrl(
                requireNotNull(params?.optString("url")?.ifEmpty { null }) { "device.open_url requires url" }
            )
            "device.alarm" -> deviceActions.setAlarm(
                hour = params?.optInt("hour", -1) ?: -1,
                minute = params?.optInt("minute", -1) ?: -1,
                message = params?.optString("message")?.ifEmpty { null },
            )
            "device.facts" -> deviceActions.deviceInfo()
            "device.sensors" -> deviceActions.sensors(
                params?.optLong("timeout_ms", 1500L) ?: 1500L
            )

            // ---- device.* : comms (runtime permissions, graceful denial) --
            "device.contacts" -> deviceComms.searchContacts(
                query = params?.optString("query")?.ifEmpty { null },
            )
            "device.dial" -> deviceComms.dial(
                number = requireNotNull(params?.optString("number")?.ifEmpty { null }) { "device.dial requires number" },
                direct = params?.optBoolean("direct", false) ?: false,
            )
            "device.call_log" -> deviceComms.callLog()
            "device.sms.read" -> deviceComms.readSms(
                box = params?.optString("box", "inbox") ?: "inbox",
            )
            "device.sms.send" -> deviceComms.sendSms(
                number = requireNotNull(params?.optString("number")?.ifEmpty { null }) { "device.sms.send requires number" },
                body = requireNotNull(params?.optString("body")?.ifEmpty { null }) { "device.sms.send requires body" },
            )
            "device.calendar" -> deviceComms.calendarEvents()
            "device.calendar.add" -> deviceComms.addCalendarEvent(
                title = requireNotNull(params?.optString("title")?.ifEmpty { null }) { "device.calendar.add requires title" },
                beginMs = params?.optLong("begin_ms", 0L) ?: 0L,
                endMs = params?.optLong("end_ms", 0L).let { if (it == null || it == 0L) null else it },
                location = params?.optString("location")?.ifEmpty { null },
                description = params?.optString("description")?.ifEmpty { null },
            )

            // ---- device.* : the user's own credentials --------------------
            // Read-only lookups plus one write-only action. Not under
            // `screen.` even though `fill` types: reading the vault is not
            // operating the device, and `fill` does its own folding below.
            "device.vault.list" -> {
                val arr = org.json.JSONArray()
                for (e in net.kuafuai.andee.config.Vault.entries(appContext)) {
                    val fields = org.json.JSONObject()
                    for ((k, v) in e.masked()) fields.put(k, v)
                    arr.put(
                        JSONObject()
                            .put("id", e.id)
                            .put("label", e.label)
                            .put("fields", fields)
                            .put("secrets", org.json.JSONArray(e.secrets()))
                    )
                }
                JSONObject().put("entries", arr).put("count", arr.length())
            }
            "device.vault.get" -> {
                val who = requireNotNull(params?.optString("entry")?.ifEmpty { null }) {
                    "device.vault.get requires entry"
                }
                val field = params?.optString("field")?.ifEmpty { null } ?: "phone"
                val e = net.kuafuai.andee.config.Vault.find(appContext, who)
                    ?: throw IllegalArgumentException(
                        "no vault entry matching '$who' — call list_vault to see what the user saved"
                    )
                if (field == "password" || field in e.secrets()) {
                    // The whole point of the vault. Refusing here rather than
                    // masking keeps the brain from pasting "****" into a form
                    // and reporting success.
                    throw IllegalArgumentException(
                        "'$field' is a secret and is never returned. Tap the field " +
                        "first, then call fill_secret — the device types it."
                    )
                }
                val value = e.readable()[field]
                    ?: throw IllegalArgumentException(
                        "entry '${e.label}' has no '$field' — it has ${e.readable().keys}"
                    )
                JSONObject().put("entry", e.label).put("field", field).put("value", value)
            }
            "device.vault.fill" -> {
                ensureCompact()
                window.pulseTool()
                val who = requireNotNull(params?.optString("entry")?.ifEmpty { null }) {
                    "device.vault.fill requires entry"
                }
                val field = params?.optString("field")?.ifEmpty { null } ?: "password"
                val e = net.kuafuai.andee.config.Vault.find(appContext, who)
                    ?: throw IllegalArgumentException(
                        "no vault entry matching '$who' — call list_vault to see what the user saved"
                    )
                // Which secret, asked of the entry rather than decided here:
                // the set of names lives in `Entry.secrets`, and a second copy
                // of it in this branch is one that drifts the day a field is
                // added. A non-secret name (phone, note) lands in the same
                // error as an empty one, and the message names both ways out.
                val value = e.secretValue(field)
                    ?: throw IllegalArgumentException(
                        "entry '${e.label}' has no secret '$field'. It has ${e.secrets()}; " +
                        "read the rest with get_vault, or ask the user to add it in 设置 → 保险箱"
                    )
                // The device is typing the user's own secret for them, so say
                // so. `pulseTool()` above is the live tell while the card is
                // folded; the subtitle is the record, readable the moment the
                // user unfolds. Not a Toast: toasts are gated on the
                // notification appop, which is off for a service with no
                // launcher icon, and a silently dropped promise is worse than
                // a quiet one.
                window.setSubtitle(
                    AppLocale.str(
                        appContext, R.string.cmd_vault_filling, e.label, secretLabel(field),
                    )
                )
                screen.typeText(value, id = null, secret = true)
            }

            "device.notifications" -> {
                val since = params?.optLong("since_ms", 0L) ?: 0L
                val limit = params?.optInt("limit", 20) ?: 20
                net.kuafuai.andee.device.NotificationRelayService.status(appContext)
                    .put("items", net.kuafuai.andee.device.NotificationRelayService.Store.snapshot(since, limit))
            }

            // ---- device.* : permissions concierge + camera eye -------------
            "device.permissions" -> permissions.status()
            "device.permissions.request" -> permissions.request(
                ids = requireNotNull(params?.optJSONArray("ids")) { "device.permissions.request requires ids[]" },
                confirm = params?.optBoolean("confirm", false) ?: false,
            )
            "device.scan" -> {
                // The two exceptions to "device.* never folds" above: both put a
                // camera preview on screen, and our overlay sits above every
                // activity. Expanded, the user aims the tablet at a QR code and
                // sees our face — the one thing they need to look at is the one
                // thing covered.
                //
                // Not gated on [driving], unlike the `screen.` fold. That gate
                // exists so a viewer polling reads on a timer can't rearrange
                // what the user is looking at; these two start a fullscreen
                // activity, which is already far past rearranging. Whoever asked,
                // a camera nobody can see is not a camera.
                ensureCompact()
                val timeoutMs = params?.optLong("timeout_ms", 30_000L) ?: 30_000L
                net.kuafuai.andee.device.ScanActivity.LastResult = null
                val i = android.content.Intent(appContext, net.kuafuai.andee.device.ScanActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(net.kuafuai.andee.device.ScanActivity.EXTRA_AUTO_CLOSE_MS, timeoutMs)
                runCatching { appContext.startActivity(i) }
                // Bounded wait for the scan result (user aims the camera).
                val deadline = System.currentTimeMillis() + timeoutMs + 2000
                while (System.currentTimeMillis() < deadline) {
                    val r = net.kuafuai.andee.device.ScanActivity.LastResult
                    if (r != null) {
                        val out = org.json.JSONObject()
                        r.getString("value")?.let { out.put("value", it) }
                        r.getString("format")?.let { out.put("format", it) }
                        r.getString("error")?.let { out.put("error", it) }
                        return out
                    }
                    Thread.sleep(250)
                }
                org.json.JSONObject().put("error", "scan timed out")
            }

            // ---- device.* : the live eye -----------------------------------
            "device.look" -> {
                // See device.scan: the preview is the point of the command.
                ensureCompact()
                val front = params?.optBoolean("front", false) ?: false
                val sinceUserClosed = android.os.SystemClock.uptimeMillis() -
                    net.kuafuai.andee.device.LookActivity.userClosedAt
                if (!net.kuafuai.andee.device.lookSessionOpen() &&
                    sinceUserClosed < USER_CLOSED_GRACE_MS
                ) {
                    // Do NOT silently reopen. The user just shut the lens; the
                    // old reply here ("camera not ready yet — retry") was both
                    // untrue and an instruction to push the camera straight
                    // back into their face. Same rule the meeting recorder
                    // follows: a stop reason reported to the brain has to be
                    // the real one.
                    org.json.JSONObject().put(
                        "error",
                        "The user just switched the camera off. Do not reopen it straight away — " +
                            "ask them first, and only call camera_turn once they agree."
                    )
                } else {
                    // Open (or re-point) the session, then hand back one frame.
                    if (!net.kuafuai.andee.device.lookSessionOpen() ||
                        net.kuafuai.andee.device.LookActivity.usingFront != front
                    ) {
                        net.kuafuai.andee.device.LookActivity.open(appContext, front)
                    }
                    // The first frame takes a moment to arrive; bounded retry.
                    var b64: String? = null
                    val deadline = System.currentTimeMillis() + 4000
                    while (System.currentTimeMillis() < deadline) {
                        b64 = net.kuafuai.andee.device.LookActivity.sampleFrame()
                        if (b64 != null) break
                        Thread.sleep(200)
                    }
                    if (b64 == null) {
                        org.json.JSONObject().put("error", "camera not ready yet — retry look in a second")
                    } else {
                        org.json.JSONObject()
                            .put("png_base64", b64)
                            .put("camera", if (front) "front" else "back")
                            .put("note", "session stays open; call look again to sample more, look_stop to close")
                    }
                }
            }
            "device.look_stop" -> {
                net.kuafuai.andee.device.LookActivity.close()
                org.json.JSONObject().put("closed", true)
            }

            // ---- meeting.* : the long microphone ----------------------------
            // Not `screen.*`, deliberately: recording is not operating the
            // device, and the ball has to stay where the user can reach it —
            // tapping it is the only way they can end a meeting, since the
            // microphone is held and they cannot ask us to.
            "meeting.start" -> meeting.start(params?.optString("title")?.ifEmpty { null })
            "meeting.stop" -> meeting.stop()
            "meeting.status" -> meeting.status()

            // ---- dog.* : the little robot dog on the USB cable ---------------
            // Outside `screen.` for the same reason meeting.* is, and one more:
            // the dog is not this device. Driving it says nothing about what the
            // user is looking at on the tablet, so it must not fold the card.
            //
            // None of these block for the length of the movement they start.
            // That is not a style choice — BodyWsClient serves requests on a
            // single thread, so a `dog_move` that waited out its own duration
            // would also be holding back the `dog_stop` that interrupts it. See
            // DogMotion's class note; the stop is a timer's job.
            "dog.move" -> dog.move(
                motion = requireNotNull(params?.optString("motion")?.ifEmpty { null }) {
                    "dog_move requires motion (forward|back|left|right)"
                },
                seconds = params?.optDouble("seconds", Double.NaN) ?: Double.NaN,
                gait = params?.optBoolean("gait", false) ?: false,
            )
            "dog.sequence" -> dog.sequence(
                requireNotNull(params?.optJSONArray("steps")) {
                    "dog_sequence requires steps[] — each {motion, seconds, gait?}"
                }
            )
            // Closed-loop, but still not blocking: the gyroscope is read on a
            // motion thread and this returns as soon as the turn is under way.
            // The measured result lands in dog_status.last_turn.
            "dog.turn" -> dog.turn(
                motion = requireNotNull(params?.optString("motion")?.ifEmpty { null }) {
                    "dog_turn requires motion (left|right)"
                },
                degrees = params?.optDouble("degrees", Double.NaN) ?: Double.NaN,
                gait = params?.optBoolean("gait", true) ?: true,
            )
            "dog.stop" -> dog.stop()
            "dog.status" -> dog.status()
            // Settings, not motions: they change how the *next* move behaves and
            // deliberately do not interrupt one in progress.
            "dog.speed" -> dog.speed(
                requireNotNull(params?.optString("level")?.ifEmpty { null }) {
                    "dog_speed requires level (slow|normal|fast|turbo)"
                }
            )
            "dog.pose" -> dog.pose(
                requireNotNull(params?.optString("stance")?.ifEmpty { null }) {
                    "dog_pose requires stance (high|normal)"
                }
            )
            // The one dog call that is safe to make mid-motion: one frame out,
            // one ack back, no change to what the dog is doing.
            "dog.sense" -> dog.poll()
            // Touches no radio at all — it only re-reads the tablet's own
            // sensors, so it cannot disturb whatever the dog is holding.
            "dog.calibrate" -> dog.calibrate()

            // ---- tts.* : speak without putting anything on the screen ------
            //
            // Deliberately **not** in [ToolSchemas], so no brain can reach it.
            // A turn already speaks through its final answer, and a second way
            // for the model to make the ball talk is a way for it to talk over
            // itself. This is the authoring/demo port's tool: it is what
            // `tools/body.py tts.speak '{"text":"…"}'` calls when a fixed line
            // has to be heard on cue with the screen left alone.
            //
            // The mute rules are [sayAloud]'s, not a second set: a stopped
            // turn, a meeting and a phone call all still refuse. When they do,
            // this says so instead of reporting a success nobody can hear.
            "tts.speak" -> {
                val text = requireNotNull(params?.optString("text")?.ifEmpty { null }) { "tts.speak requires text" }
                val spoken = sayAloud(text)
                JSONObject()
                    .put("spoken", spoken)
                    .put("chars", text.length)
                    .apply {
                        if (!spoken) {
                            put(
                                "note",
                                "The words were refused by the device's mute rules: " +
                                    "the turn was stopped, a meeting is recording, or a call is in progress.",
                            )
                        }
                    }
            }

            // ---- brain-facing UI: cards + full-screen HTML ------------------
            "ui.ask" -> {
                val question = requireNotNull(params?.optString("question")?.ifEmpty { null }) { "ui.ask requires question" }
                val buttons = run {
                    // Localised defaults only when the brain sent none. A
                    // caller-supplied list is passed through untouched — those
                    // are the model's words, not ours.
                    val arr = params?.optJSONArray("buttons")
                        ?: org.json.JSONArray()
                            .put(AppLocale.str(appContext, R.string.cmd_ask_yes))
                            .put(AppLocale.str(appContext, R.string.cmd_ask_no))
                    List(arr.length()) { arr.optString(it) }
                }
                val timeoutMs = params?.optLong("timeout_ms", 30_000L) ?: 30_000L
                // CardUi callbacks fire on the main thread; we are on a WS
                // worker — bridge through a latch.
                val latch = java.util.concurrent.CountDownLatch(1)
                var answer: net.kuafuai.andee.ui.CardUi.Result? = null
                net.kuafuai.andee.ui.CardUi.ask(question, buttons, timeoutMs) { r ->
                    answer = r; latch.countDown()
                }
                // The card is up in the user's hands, but they may be looking
                // anywhere else — the ball is a voice-first device, so the
                // question is spoken as well as shown. The service applies the
                // same mute rules it applies to the final answer.
                sayAloud(question)
                latch.await(timeoutMs + 3000, java.util.concurrent.TimeUnit.MILLISECONDS)
                val r = answer ?: net.kuafuai.andee.ui.CardUi.Result(null, "no_answer")
                org.json.JSONObject()
                    .put("button", r.button)
                    .put("how", r.how)
                    .put("answered", r.button != null)
            }
            "ui.alert" -> {
                val text = requireNotNull(params?.optString("text")?.ifEmpty { null }) { "ui.alert requires text" }
                val timeoutMs = params?.optLong("timeout_ms", 30_000L) ?: 30_000L
                val latch = java.util.concurrent.CountDownLatch(1)
                var answer: net.kuafuai.andee.ui.CardUi.Result? = null
                net.kuafuai.andee.ui.CardUi.alert(text, timeoutMs) { r ->
                    answer = r; latch.countDown()
                }
                // Same voice-first reasoning as ui.ask: a notice nobody hears
                // is a notice that was never delivered.
                sayAloud(text)
                latch.await(timeoutMs + 3000, java.util.concurrent.TimeUnit.MILLISECONDS)
                val r = answer ?: net.kuafuai.andee.ui.CardUi.Result(null, "no_answer")
                org.json.JSONObject()
                    .put("how", r.how)
                    .put("read", true)
            }
            "ui.show_html" -> {
                val html = requireNotNull(params?.optString("html")?.ifEmpty { null }) { "ui.show_html requires html" }
                // Reuses `html_default_title`, the same resource
                // [net.kuafuai.andee.ui.HtmlActivity] shows — one title, one
                // key, so the page and its scrollback entry cannot drift apart.
                val defaultTitle = AppLocale.str(appContext, R.string.html_default_title)
                val title = params?.optString("title")?.ifEmpty { defaultTitle } ?: defaultTitle
                if (net.kuafuai.andee.ui.HtmlActivity.pageUp) {
                    // **Returns at once, and this is the reason it has to.**
                    // This call used to wait on a latch for the user to close
                    // the page, up to five minutes, and that wait was the whole
                    // problem: [net.kuafuai.andee.brain.LocalBrain] runs a turn on
                    // one thread and runs tools synchronously on it, so a parked
                    // `show_html` parked the conversation — the user's next
                    // sentence was accepted and then sat in the queue until the
                    // page went away. Refusing the second page instead of
                    // stacking it is the other half of the same constraint:
                    // `HtmlActivity` keeps ONE page (see its `pageUp`), so two
                    // in flight would mean the shared static file losing to
                    // whichever activity read it last.
                    org.json.JSONObject()
                        .put("error", "There is still a page open on screen (${net.kuafuai.andee.ui.HtmlActivity.titleText}). " +
                            "Ask the user to dismiss it first, or fold the new content into what they are already looking at.")
                } else {
                    // The page is the whole point of the call, and our own card
                    // is a fullscreen opaque overlay that sits above every
                    // activity — so without this the brain renders a UI nobody
                    // can see. Same fold as `screen.*` gets, for the same
                    // reason, but it can't ride the prefix: `ui.*` is where the
                    // cards live and a card must not fold the thing it is drawn
                    // on.
                    ensureCompact()
                    // Folded is not out of the way — a folded ball is still a
                    // thumb-sized opaque disc, and it sits wherever it last
                    // was, which is as likely as not the middle of the page.
                    // Hang it off the bottom edge for as long as the page is
                    // up; [onPageClosed] puts it back.
                    window.setPagePerched(true)
                    // Archived first, so the bytes on screen and the bytes in
                    // the scrollback are the same file. Falls back to a scratch
                    // copy in the cache dir if the archive can't be written — a
                    // page that shows but isn't re-openable beats no page.
                    val archived = net.kuafuai.andee.ui.ChatHistory.addPage(title, html)
                    val openedAt = android.os.SystemClock.elapsedRealtime()
                    val opened = if (archived != null) {
                        net.kuafuai.andee.ui.HtmlActivity.showFile(appContext, archived, title) {
                            pageClosed(title, openedAt)
                        }
                        archived
                    } else {
                        net.kuafuai.andee.ui.HtmlActivity.show(appContext, html, title) {
                            pageClosed(title, openedAt)
                        }
                    }
                    if (opened == null) {
                        window.setPagePerched(false)
                        org.json.JSONObject().put("error", "failed to stage the html")
                    } else {
                        // `closed: false` is not a failure — it is the state of
                        // a page that is up right now, and it is what the old
                        // `closed` flag meant at this instant anyway. There is
                        // no wait left to make it true.
                        org.json.JSONObject()
                            .put("shown", true)
                            .put("closed", false)
                            .put("title", title)
                            .put(
                                "note",
                                "The page is open on screen now; this step ends here — do not wait for the user " +
                                    "to close it, and do not restate it. I will tell you when they close it.",
                            )
                    }
                }
            }

            "screen.tap" -> {
                val x = params?.optInt("x", Int.MIN_VALUE) ?: Int.MIN_VALUE
                val y = params?.optInt("y", Int.MIN_VALUE) ?: Int.MIN_VALUE
                require(x != Int.MIN_VALUE && y != Int.MIN_VALUE) { "screen.tap requires int x,y (0-1000 relative)" }
                passthroughForGesture { screen.tapNorm(x, y) }
            }

            "screen.tap_id" -> {
                // tap_screen_element: by eid from the latest element list. The old
                // resource-id form is gone — WeChat nodes mostly have none,
                // and e-numbers cover every node the list shows.
                val eid = params?.optString("eid").orEmpty()
                require(eid.isNotEmpty()) { "screen.tap_id requires string eid (e.g. \"e7\", from get_screen_element's element list)" }
                passthroughForGesture { screen.tapByEid(eid) }
            }

            "screen.swipe" -> {
                requireNotNull(params) { "screen.swipe requires params" }
                val x1 = params.getInt("x1");
                val y1 = params.getInt("y1")
                val x2 = params.getInt("x2");
                val y2 = params.getInt("y2")
                val dur = params.optLong("duration_ms", 300L)
                passthroughForGesture { screen.swipeNorm(x1, y1, x2, y2, dur) }
            }

            "screen.long_press" -> {
                val x = params?.optInt("x", Int.MIN_VALUE) ?: Int.MIN_VALUE
                val y = params?.optInt("y", Int.MIN_VALUE) ?: Int.MIN_VALUE
                val dur = params?.optLong("duration_ms", 600L) ?: 600L
                require(x != Int.MIN_VALUE && y != Int.MIN_VALUE) { "screen.long_press requires int x,y (0-1000 relative)" }
                passthroughForGesture { screen.longPressNorm(x, y, dur) }
            }

            "screen.long_press_id" -> {
                val eid = params?.optString("eid").orEmpty()
                require(eid.isNotEmpty()) { "screen.long_press_id requires string eid" }
                passthroughForGesture { screen.longPressByEid(eid, 600L) }
            }

            "screen.type" -> {
                val text = params?.optString("text").orEmpty()
                val id = params?.optString("id")?.ifEmpty { null }
                screen.typeText(text, id)
            }

            "screen.submit_input" -> {
                val id = params?.optString("id")?.ifEmpty { null }
                screen.submitInput(id)
            }

            "screen.screenshot" -> blankedForCapture { screen.screenshot() }

            "screen.look_region" -> blankedForCapture {
                requireNotNull(params) { "screen.look_region requires params" }
                screen.lookRegion(
                    params.optInt("x", Int.MIN_VALUE),
                    params.optInt("y", Int.MIN_VALUE),
                    params.optInt("w", 0),
                    params.optInt("h", 0),
                )
            }

            "screen.ui_tree" -> screen.dumpUiTree(
                verbose = params?.optBoolean("verbose", false) ?: false,
                withShot = params?.optBoolean("with_shot", ToolSchemas.UI_TREE_WITH_SHOT_DEFAULT)
                    ?: ToolSchemas.UI_TREE_WITH_SHOT_DEFAULT,
                // The dump's own screenshot has never been blanked — it called
                // screenshot() directly, so every with_shot image has shipped
                // with our ball and toolbar in it. Harmless while the shot was
                // a bonus next to a full element list; not harmless now that
                // the only shots left are the ones on self-drawn panes, where
                // pixels are all the brain has. ScreenController stays unaware
                // of the overlay — it just calls what it is handed.
                capture = { reason -> blankedForCapture { screen.screenshot(reason) } },
            )

            // Blocking the worker here is the point — the brain asked to wait,
            // so a slow answer *is* the answer (unlike a timed side effect,
            // which must arm a timer and return). It is bounded instead:
            // body_hub gives a request 30s, and the dump plus screenshot that
            // follow need a couple of those seconds.
            "screen.wait" -> {
                val asked = params?.optDouble("seconds", Double.NaN) ?: Double.NaN
                require(!asked.isNaN() && asked > 0) { "screen.wait requires positive seconds" }
                val waitMs = (asked * 1000).toLong().coerceAtMost(WAIT_MAX_MS)
                Thread.sleep(waitMs)
                screen.dumpUiTree(
                    verbose = false,
                    withShot = true,
                    capture = { reason -> blankedForCapture { screen.screenshot(reason) } },
                ).put("waited_ms", waitMs)
            }

            "screen.global" -> {
                val action = params?.optString("action").orEmpty()
                require(action.isNotEmpty()) { "screen.global requires string action" }
                screen.globalAction(action)
            }

            // ---- note.* : the notebook -------------------------------------
            //
            // The only tools here that keep state on this device, and the only
            // ones a silent sweep turn may call. They are marked `localOnly` in
            // [ToolSchemas] and filtered out of the hub register payload: what
            // this person likes and what they were promised does not leave the
            // tablet. Nothing below touches the screen, so none of it folds the
            // card.
            "note.remember" -> {
                val name = requireNotNull(params?.optString("name")?.trim()?.ifEmpty { null }) {
                    "note.remember requires name"
                }
                val desc = requireNotNull(params?.optString("description")?.trim()?.ifEmpty { null }) {
                    "note.remember requires description"
                }
                val type = requireNotNull(params?.optString("type")?.trim()?.ifEmpty { null }) {
                    "note.remember requires type"
                }
                val (saved, overwrote) = Notebook.remember(
                    context = appContext,
                    name = name,
                    description = desc,
                    content = params?.optString("content").orEmpty(),
                    type = type,
                    // A silent turn is the brain drawing its own conclusions
                    // from what it overheard; a live turn is the user saying it.
                    // Nothing reads this yet — it is written now so that a later
                    // "prefer the user's own words" rule has the data.
                    source = if (silent) Notebook.SOURCE_INFERRED else Notebook.SOURCE_HUMAN,
                )
                JSONObject()
                    .put("saved", true)
                    .put("name", saved.name)
                    .put("overwrote", overwrote)
                    .put("total", Notebook.memories(appContext).size)
                    .put(
                        "note",
                        if (overwrote) {
                            "The entry with that name was updated (the old version is archived), not added anew."
                        } else {
                            "Saved."
                        },
                    )
            }

            "note.recall" -> {
                val hits = Notebook.recall(
                    context = appContext,
                    query = params?.optString("query").orEmpty(),
                    type = params?.optString("type").orEmpty(),
                    limit = params?.optInt("limit", 8) ?: 8,
                )
                val arr = org.json.JSONArray()
                for (m in hits) {
                    arr.put(
                        JSONObject()
                            .put("name", m.name)
                            .put("summary", m.description)
                            .put("type", m.type)
                            .put("content", if (m.content == m.description) "" else m.content)
                            .put("updated", Scheduler.formatLocal(m.updatedAt))
                    )
                }
                JSONObject()
                    .put("items", arr)
                    .put("count", arr.length())
                    .put(
                        "note",
                        if (arr.length() == 0) {
                            "Nothing in the notebook matches. Do not invent something from impression — " +
                                "treat it as not knowing."
                        } else {
                            ""
                        },
                    )
            }

            "note.forget" -> {
                val name = requireNotNull(params?.optString("name")?.trim()?.ifEmpty { null }) {
                    "note.forget requires name"
                }
                val hit = Notebook.forget(appContext, name)
                JSONObject()
                    .put("forgotten", hit)
                    .put("name", name)
                    .put(
                        "note",
                        if (hit) {
                            "Deleted."
                        } else {
                            "There is nothing in the notebook under that name — recall first to see what it is " +
                                "actually called, and do not delete something else on impression."
                        },
                    )
            }

            "note.todo" -> {
                val what = requireNotNull(params?.optString("what")?.trim()?.ifEmpty { null }) {
                    "note.todo requires what"
                }
                val atRaw = params?.optString("at")?.trim().orEmpty()
                val cronRaw = params?.optString("cron")?.trim().orEmpty()
                require(atRaw.isNotEmpty() != cronRaw.isNotEmpty()) {
                    "at (one-shot) and cron (recurring) are an either/or: fill exactly one, " +
                        "not both and not neither"
                }
                val now = System.currentTimeMillis()
                val todo: Notebook.Todo
                if (cronRaw.isNotEmpty()) {
                    val parsed = CronExpr.parse(cronRaw)
                    val gap = parsed.minGapMinutes()
                    require(gap >= CronExpr.MIN_INTERVAL_MINUTES) {
                        "That interval is too tight (about once every $gap minutes). " +
                            "The tightest the system allows is once every " +
                            "${CronExpr.MIN_INTERVAL_MINUTES} minutes — tell the user plainly that it " +
                            "cannot be done, and do not agree to it in different words."
                    }
                    val next = CronExpr.nextAfter(parsed, now)
                        ?: throw IllegalArgumentException(
                            "That cron expression can never match (check the day / month / weekday " +
                                "fields): $cronRaw",
                        )
                    todo = Notebook.Todo(
                        id = Notebook.newTodoId(),
                        what = what,
                        mode = "cron",
                        at = 0L,
                        cronExpr = cronRaw,
                        nextRunAt = next,
                        lastRunAt = 0L,
                        lastStatus = "",
                        lastError = "",
                        status = "active",
                        createdAt = now,
                    )
                } else {
                    val at = Scheduler.parseLocal(atRaw)
                        ?: throw IllegalArgumentException(
                            "at must be written as \"YYYY-MM-DD HH:mm\" (local time); received: $atRaw",
                        )
                    require(at > now) {
                        "That moment has already passed ($atRaw). It is already " +
                            "${Scheduler.formatLocal(now)}; give a time in the future."
                    }
                    todo = Notebook.Todo(
                        id = Notebook.newTodoId(),
                        what = what,
                        mode = "once",
                        at = at,
                        cronExpr = "",
                        nextRunAt = at,
                        lastRunAt = 0L,
                        lastStatus = "",
                        lastError = "",
                        status = "active",
                        createdAt = now,
                    )
                }
                Notebook.addTodo(appContext, todo)
                Scheduler.armTodo(appContext, todo)
                JSONObject()
                    .put("id", todo.id)
                    .put("mode", todo.mode)
                    .put("next_run_local", Scheduler.formatLocal(todo.nextRunAt))
                    .put("scheduled", if (Scheduler.exactAllowed(appContext)) "exact" else "inexact")
                    .put(
                        "note",
                        "Set. When it comes due and is dealt with, close it with " +
                            "todos(action=done, id=…) — otherwise it stays in the " +
                            "\"promised but not done\" list forever." +
                            if (Scheduler.exactAllowed(appContext)) {
                                ""
                            } else {
                                " The precise-alarm permission was not granted, so it may land a few " +
                                    "minutes off — do not tell the user it is exactly on time."
                            },
                    )
            }

            "note.todos" -> {
                val action = params?.optString("action")?.trim()?.lowercase().orEmpty()
                require(action in listOf("list", "done", "drop", "pause", "resume")) {
                    "note.todos requires action=list|done|drop|pause|resume"
                }
                if (action == "list") {
                    val filter = params?.optString("filter")?.ifEmpty { "pending" } ?: "pending"
                    val rows = Notebook.todos(appContext, filter)
                        // Soonest first; rows with no next run (an occurrence that
                        // fired and was never closed) sort last on purpose — they
                        // are the outstanding ones, not the urgent ones.
                        .sortedBy { if (it.nextRunAt > 0) it.nextRunAt else Long.MAX_VALUE }
                    val arr = org.json.JSONArray()
                    for (t in rows) {
                        arr.put(
                            JSONObject()
                                .put("id", t.id)
                                .put("what", t.what)
                                .put("mode", t.mode)
                                .put(
                                    "when",
                                    if (t.mode == "cron") t.cronExpr else Scheduler.formatLocal(t.at),
                                )
                                .put(
                                    "next_run_local",
                                    if (t.nextRunAt > 0) Scheduler.formatLocal(t.nextRunAt) else "",
                                )
                                .put("status", t.status)
                                .put("last_status", t.lastStatus)
                        )
                    }
                    JSONObject().put("items", arr).put("count", arr.length())
                } else {
                    val id = requireNotNull(params?.optString("id")?.trim()?.ifEmpty { null }) {
                        "note.todos action=$action requires an id (take it from the list result; " +
                            "do not make one up)"
                    }
                    val existing = Notebook.todoById(appContext, id)
                        ?: throw IllegalArgumentException("No such id: $id")

                    when (action) {
                        "pause" -> {
                            Notebook.patchTodo(appContext, id, status = "paused")
                            Scheduler.cancelTodo(appContext, id)
                            JSONObject().put("id", id).put("status", "paused")
                        }
                        "resume" -> {
                            val now = System.currentTimeMillis()
                            val next = when {
                                existing.mode == "cron" && existing.cronExpr.isNotBlank() ->
                                    CronExpr.nextAfter(existing.cronExpr, now)
                                existing.at > now -> existing.at
                                else -> null
                            } ?: throw IllegalArgumentException(
                                "The time this one was due has already passed " +
                                    "(${Scheduler.formatLocal(existing.at)}); it cannot be resumed — " +
                                    "use todo to set a new one.",
                            )
                            val patched = Notebook.patchTodo(
                                appContext, id, status = "active", nextRunAt = next,
                            )!!
                            Scheduler.armTodo(appContext, patched)
                            JSONObject()
                                .put("id", id)
                                .put("status", "active")
                                .put("next_run_local", Scheduler.formatLocal(next))
                        }
                        else -> {
                            val status = if (action == "done") "done" else "dropped"
                            Notebook.patchTodo(
                                appContext, id,
                                status = status,
                                // A closed todo must stop having a next run, or a
                                // later `list` reads as if it were still coming.
                                nextRunAt = 0L,
                            )
                            Scheduler.cancelTodo(appContext, id)
                            JSONObject().put("id", id).put("status", status)
                        }
                    }
                }
            }

            // No built-in match. Hand it to the call extension (or whatever
            // else has been wired into forwardExternal). The default lambda
            // throws with the same "unknown method" line the else used to
            // produce, so a body with no peripherals reads exactly the same
            // as before this fallback existed.
            else -> forwardExternal(method, params)
        }
    }

    /**
     * Fold the card to the corner ball — the assistant's default shape while
     * it drives the device: one ball bottom-right, edge glow lit, every other
     * pixel the user's.
     *
     * This was a tool the brain called (`screen.reveal`), and both reasons it
     * died are the same reason it exists now: a model that forgot to call it
     * left the user staring at our face instead of the app being operated, and
     * its deadline made the assistant decide how long the user gets to look.
     * Folded in here there is no deadline to get wrong — the user taps the
     * ball ([expand]) when they want the card back, which is also what they
     * already do to talk.
     *
     * Runs on the caller's (worker) thread and only actually waits on the
     * first call of a fold: transitioning costs a layout pass, and without
     * waiting, the gesture that triggered it lands while the card still covers
     * the screen. The tap succeeds — [passthroughForGesture] sees to that —
     * but nobody sees it happen, which is the whole point of folding. Every
     * call after that returns immediately; once folded, we stay folded.
     *
     * Keeps a running task marked running ([touchBusy]) but cannot start one:
     * folding the card is about this command, the glow is about the task, and
     * the two only usually coincide.
     */
    /**
     * The user dismissed a page. Runs on the main thread, from
     * `HtmlActivity.onDestroy`.
     *
     * Two jobs, and the second one is why the page can be non-blocking at all:
     * put the ball back (it was hung off the ledge for as long as the page was
     * up) and tell whoever the brain is, since the model is no longer sitting
     * in a tool call waiting to find out.
     */
    private fun pageClosed(title: String, openedAt: Long) {
        window.setPagePerched(false)
        val seconds = ((android.os.SystemClock.elapsedRealtime() - openedAt) / 1000L).toInt()
        runCatching { onPageClosed(title, seconds) }
            .onFailure { android.util.Log.w("Body", "page-closed notify failed", it) }
    }

    /**
     * The name of a secret in the user's language, for the fill subtitle.
     *
     * 「正在填入「招行」的密码…」 has to be true, and with a card in the same
     * entry the same call can be typing a CVV. Unknown names fall through to
     * the wire name rather than to 密码: a raw `card_expiry` on screen says
     * something is out of step, where a confident wrong word does not.
     */
    private fun secretLabel(field: String): String {
        val id = when (field) {
            "password" -> R.string.vault_field_password
            "card_number" -> R.string.vault_field_card_number
            "card_expiry" -> R.string.vault_field_card_expiry
            "card_cvv" -> R.string.vault_field_card_cvv
            else -> return field
        }
        return AppLocale.str(appContext, id)
    }

    private fun ensureCompact() {
        // The window, not the cache. [compacted] is written on the main thread
        // by everything that folds, but the card can be unfolded by paths that
        // never come back through here — birth, `ensureOverlays`, a card that
        // was hidden and re-shown — and a cache that says "already folded"
        // about a fullscreen card leaves it covering the app for the whole tool
        // call. `isCompact()` is a field read, so asking is free.
        val wait = !compacted || !window.isCompact()
        val settled = CountDownLatch(1)
        ui.post {
            touchBusy()
            if (wait) {
                window.setCompact(true)
                compacted = true
                // Counted down a turn later rather than inline: `setCompact`
                // queues its own layout runnable, which is therefore already
                // done by the time this one runs. Getting the pixels onto the
                // screen after that is the compositor's business, not
                // something we can wait for from here.
                ui.post { settled.countDown() }
            } else {
                settled.countDown()
            }
        }
        // Bounded, and only a hint: a busy main thread costs us the wait, not
        // the command.
        if (!wait) return
        runCatching { settled.await(COMPACT_SETTLE_MS, TimeUnit.MILLISECONDS) }
        awaitUncovered()
    }

    /**
     * Wait for the *system* to notice the fold, which is a separate event from
     * the fold itself and the one that actually matters.
     *
     * `updateViewLayout` only asks; occlusion is recomputed a frame or two
     * later. Until it is, the app behind the card still reports every node as
     * `isVisibleToUser == false` and the dump comes back with zero nodes from a
     * screen that is fine — the same signature as a stale accessibility
     * connection, which is how this shipped as "WeChat's tree cannot be read
     * any more" and sent the user off to toggle a permission that was working.
     *
     * A poll rather than a longer sleep, for the same reason the typing readback
     * is one: the settle is a few tens of ms when the main thread is free and
     * much longer when it is not, and paying the worst case on every tool call
     * is a tax on all of them. Bounded, and a miss is not fatal — the dump's own
     * hint can then name the card (see [net.kuafuai.andee.ui.FullscreenCard]).
     *
     * Only reached when a fold actually happened, so the usual path — a card
     * that was already folded, which is most of a task — pays nothing.
     */
    private fun awaitUncovered() {
        val deadline = System.currentTimeMillis() + UNCOVER_BUDGET_MS
        while (!screen.activeWindowVisible()) {
            if (System.currentTimeMillis() >= deadline) return
            runCatching { Thread.sleep(UNCOVER_POLL_MS) }
        }
    }

    /**
     * Light the edge glow for the duration of a task, and *only* for the
     * duration of a task. The corner ball rides along: it perches half off the
     * screen edge with a busy face for exactly the same span, and steps back
     * out when this clears — which is the moment the answer starts being read
     * aloud.
     *
     * The obvious design — light it on each tool call, release it a few
     * seconds after the last one — is wrong in exactly the case the glow
     * exists for: a model thinking its way through something for thirty
     * seconds makes no tool calls, and the device sits there dark while it is
     * in fact still being driven. So there is no idle timer here. Something
     * has to say the task is over ([endTask]); short of that, nothing dims it.
     *
     * Repeated calls are cheap and are how thinking stays marked as busy —
     * see [ensureCompact].
     *
     * Main thread only.
     */
    private fun setBusy(v: Boolean) {
        if (busy == v) {
            // Still re-arm the backstop: a long tool loop is activity, and the
            // whole point is that quiet stretches don't count as finished.
            if (v) rearmBackstop()
            return
        }
        busy = v
        if (v) {
            ripple.setBreathing(true)
            window.setWorking(true)
            rearmBackstop()
        } else {
            ui.removeCallbacks(endDriving)
            ripple.setBreathing(false)
            window.setWorking(false)
        }
    }

    /** Main thread only. */
    private fun rearmBackstop() {
        ui.removeCallbacks(endDriving)
        ui.postDelayed(endDriving, BUSY_BACKSTOP_MS)
    }

    /**
     * Proof a task is alive, not proof one exists. Every `screen.*` command
     * calls this; only [beginTask] can actually light the glow.
     *
     * The distinction is the difference between the agent working and someone
     * poking the device: the top-bar tool buttons and the debug client on 9008
     * arrive through the same [dispatch] door as the brain, and neither of them
     * is a task. Treating them as one lit the edges for the full backstop with
     * nobody driving — which is precisely what the glow is supposed to mean.
     *
     * Main thread only.
     */
    private fun touchBusy() {
        if (busy) rearmBackstop()
    }

    /**
     * The brain has started working on something — thinking, narrating
     * progress, whatever precedes the first tap. Called repeatedly as the task
     * runs, and it is what keeps the glow lit across gaps between tool calls.
     */
    fun beginTask() {
        ui.post { setBusy(true) }
    }

    /**
     * A task is currently running — the ball is lit and (when folded)
     * clinging to the ledge.
     *
     * Read by the notification relay to decide whether a signboard is
     * welcome: a question card popping up mid-task yanks the window out from
     * under the perch the ball is gripping, and the user's answer would
     * interleave with the task's own screen actions. While busy, message
     * notifications land in the ring buffer silently — the brain can still
     * see them via device.notifications, and whoever sent them gets read when
     * the current job is done, not in the middle of it.
     *
     * Main thread only (like [busy] itself).
     */
    fun isTaskActive(): Boolean = busy

    /**
     * Task over because the final answer arrived. This is the one thing that
     * puts the glow out — the card stays folded either way, so whatever the
     * assistant leaves on screen stays visible.
     *
     * The ball gets a brief pleased face out of it. "The answer came back" is
     * the only success signal this side of the wire has: the brain reports
     * failures as prose, indistinguishable here from any other answer, so a
     * task that ended badly still ends with a pop. Overclaiming a little beats
     * a device that never looks pleased about anything.
     */
    fun endTask() {
        ui.post {
            setBusy(false)
            window.signalOutcome(true)
        }
    }

    /**
     * Task over because the user hit stop. Same dimming as [endTask] and
     * deliberately no face: being interrupted is neither success nor failure,
     * and the user who just pressed the button doesn't need to be told what
     * they did.
     */
    fun cancelTask() {
        ui.post { setBusy(false) }
    }

    /**
     * Make the overlay non-touchable, wait a couple of frames for the
     * WindowManager update to actually take effect (updateViewLayout is
     * posted to the UI thread), run the gesture, restore.
     *
     * The glow window goes too. It never eats touches, but Android sums the
     * opacity of *all* our overlays when deciding whether an injected gesture
     * is trustworthy, and two non-touchable overlays are over the limit on
     * their own — so a tap issued while the glow is still breathing down from
     * the previous screenshot would be dropped without a trace.
     *
     * The previous tap's marker is the same hazard with worse aim: it is
     * parked on the exact coordinate a repeated tap is about to land on.
     */
    private inline fun <T> passthroughForGesture(block: () -> T): T {
        window.setTouchable(false)
        ripple.setVisibleForGesture(false)
        marker.setVisibleForGesture(false)
        try {
            Thread.sleep(50)
            return block()
        } finally {
            ripple.setVisibleForGesture(true)
            marker.setVisibleForGesture(true)
            window.setTouchable(true)
        }
    }

    /**
     * Blank the overlay for the duration of a display capture. The overlay is
     * fullscreen and opaque, and takeScreenshot composites it like any other
     * window — without this the brain gets a picture of our own card.
     *
     * Longer settle than [passthroughForGesture]: getting this wrong yields a
     * screenshot the brain will confidently describe as an empty screen, which
     * is worse than being 30 ms slower.
     *
     * The glow is blanked inside this too — it's a separate window and would
     * otherwise be photographed as a glowing border on every frame the brain
     * ever sees. Both blanks are `ui.post`ed, so the one settle covers them.
     *
     * Note it only *hides* the glow; whether the glow is lit at all belongs to
     * [setBusy]. Lighting it here was wrong in both directions: a capture with
     * no task behind it (top bar, debug client) lit the edges for no reason,
     * and the release in `finally` put them out mid-task — after which `busy`
     * was still true, so nothing ever turned them back on.
     *
     * The tap marker is pointedly *not* blanked. It is the one piece of our own
     * UI the brain is meant to see: it is how a coordinate it guessed comes
     * back as a place on the screen.
     */
    private inline fun <T> blankedForCapture(block: () -> T): T {
        window.setVisibleForCapture(false)
        ripple.setVisibleForCapture(false)
        try {
            Thread.sleep(80)
            return block()
        } finally {
            ripple.setVisibleForCapture(true)
            window.setVisibleForCapture(true)
        }
    }

    /**
     * The user asking for their screen back, via ⤡ in the subtitle band.
     *
     * Same fold the assistant's own tool calls get, same way back, but it says
     * nothing about the glow: that one tracks the task ([beginTask]), and
     * there is no task here — the user took the screen, not something the agent
     * is driving.
     */
    fun compactForUser() {
        ui.post {
            window.setCompact(true)
            compacted = true
        }
    }

    /**
     * Bring the card back to full size. For the user tapping the corner ball:
     * they want the assistant, and what they have to say — and its reply —
     * belongs in a subtitle band that only exists at full size.
     *
     * Safe to call when the card is already full. Deliberately leaves the glow
     * alone too: unfolding is the user asking to talk, not the task being
     * over, and a device that is still operating an app should still say so.
     */
    fun expand() {
        ui.post {
            window.setCompact(false)
            compacted = false
        }
    }

    private companion object {
        /**
         * Ceiling for `screen.wait`. The hub drops a request at 30s, and the
         * dump plus screenshot afterwards are part of the same request — so
         * the cap leaves them room rather than spending the whole budget
         * waiting and timing out with nothing to show for it.
         */
        const val WAIT_MAX_MS = 20_000L

        /**
         * How long after the user closes the camera `device.look` refuses to
         * reopen it silently. Long enough to cover the brain noticing the look
         * failed and trying once more; short enough that a later, genuinely
         * new request still works without the user doing anything.
         */
        const val USER_CLOSED_GRACE_MS = 15_000L

        /** How long to let the main thread take to apply the fold. */
        const val COMPACT_SETTLE_MS = 200L

        /**
         * And how long to then let the *system* take to notice it — see
         * [awaitUncovered]. Measured on this tablet at 4 polls / ~140 ms,
         * repeatably, against the handful of milliseconds the fold itself takes
         * to return: that gap is the whole bug. The budget is several times the
         * measurement because overrunning it costs an empty tree, and it is paid
         * once per unfold rather than per tool call.
         */
        const val UNCOVER_BUDGET_MS = 600L
        const val UNCOVER_POLL_MS = 30L

        /**
         * Not a timeout on thinking — [setBusy] has none, on purpose. This only
         * catches a brain that walks away mid-task (crash, dropped socket) so
         * the device isn't left claiming to be driven by nobody. Generous
         * exactly because anything shorter would occasionally be wrong.
         */
        const val BUSY_BACKSTOP_MS = 5 * 60 * 1000L
    }
}
