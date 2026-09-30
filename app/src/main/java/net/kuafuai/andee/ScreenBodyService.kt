package net.kuafuai.andee

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import net.kuafuai.andee.asr.AsrController
import net.kuafuai.andee.audio.AudioIO
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.net.BodyWsServer
import net.kuafuai.andee.net.CommandDispatcher
import net.kuafuai.andee.screen.ScreenController
import net.kuafuai.andee.text.stripMarkdown
import net.kuafuai.andee.tts.TtsController
import net.kuafuai.andee.ui.EdgeRippleUi
import net.kuafuai.andee.ui.FloatingWindowUi
import net.kuafuai.andee.ui.SettingsUi
import android.util.DisplayMetrics
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Body service entry point.
 *
 * Tap the ball → AsrController.toggle() → 火山 streaming ASR.
 * Long-press → TTS test (temporary).
 * ⚙       → SettingsUi (voice config).
 *
 * Exposes ScreenController over WebSocket on port 9008 so the brain (or the
 * viewer, historically) can drive tap/swipe/type/screenshot/ui_tree.
 */
class ScreenBodyService : AccessibilityService() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var window: FloatingWindowUi
    private lateinit var ripple: EdgeRippleUi
    private lateinit var marker: net.kuafuai.andee.ui.TapMarkerUi
    private var settings: SettingsUi? = null

    /**
     * The self-check card, when it is up. Separate from [settings] because both
     * can be on screen at once — see [openSelfCheck].
     */
    private var selfCheck: net.kuafuai.andee.ui.SelfCheckUi? = null
    private lateinit var audio: AudioIO
    private lateinit var asr: AsrController
    private lateinit var tts: TtsController

    /**
     * The last finished answer, kept for exactly one reason: if speaking it
     * fails and the card is folded, the scrollback row holding it is `GONE` and
     * that string is the only copy left on the device. See [showAnswerAsCard].
     */
    @Volatile
    private var lastAnswer: String? = null
    private lateinit var meeting: net.kuafuai.andee.meeting.MeetingRecorder
    private lateinit var dogLink: net.kuafuai.andee.dog.DogLink
    private lateinit var dogSense: net.kuafuai.andee.dog.DogSense
    private lateinit var dogMotion: net.kuafuai.andee.dog.DogMotion

    private lateinit var screen: ScreenController
    private lateinit var dispatcher: CommandDispatcher
    private var wsServer: BodyWsServer? = null
    private var hubClient: net.kuafuai.andee.net.BodyWsClient? = null

    /**
     * The on-device agent loop, when `brain = local`. Mutually exclusive with
     * [hubClient] — see [startBrain].
     */
    private var localBrain: net.kuafuai.andee.brain.LocalBrain? = null

    /**
     * Shared state between the inbound server (extension registers here) and
     * the outbound hub client (merges these tools into its own `register`
     * payload). See [net.kuafuai.andee.net.CallExtensionRegistry].
     */
    private val callExtensions = net.kuafuai.andee.net.CallExtensionRegistry()
    private val toolExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ScreenTools").apply { isDaemon = true }
    }
    private val stamper = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)

    // Closed by [stopEverything], reopened when the user starts the next turn.
    // Cancelling the brain's task is asynchronous, so progress / message frames
    // already in flight still arrive after the stop; without this gate the
    // subtitle reappears and TTS picks up where it left off, and the stop
    // button looks like it did nothing.
    @Volatile
    private var acceptBrainOutput = true

    /**
     * Does the last final answer leave the conversation open?
     *
     * Written by [onBrainFinal] from the brain's own judgment — a reply that
     * settles the matter ends with `[END]` (see [END_MARKER]) and flips this
     * to false — and read once, by [followUpWindow], when the answer has
     * finished being read aloud. Default true so a brain that never emits the
     * marker keeps today's always-listen behaviour: the mic closing is
     * opt-in per reply, never a device-side guess.
     */
    @Volatile
    private var expectFollowUp = true

    /**
     * Whether the card was full size when the typing field opened.
     *
     * Held across the field's lifetime and read once, in [typingFinished]: the
     * card is hidden rather than folded while the user types, so this is the
     * only record of the shape to put back.
     */
    @Volatile
    private var typingFromFullscreen = false

    override fun onServiceConnected() {
        instanceRef.set(this)
        // The manifest requests touch-exploration capability because some
        // WeChat builds expose their semantic tree only after this capability
        // is negotiated. Do not keep the request flag active at runtime: that
        // would turn ordinary tablet taps into TalkBack-style exploration.
        serviceInfo = serviceInfo.apply {
            flags = flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
        }
        window = FloatingWindowUi(this, object : FloatingWindowUi.Listeners {
            override fun onTalkClick() {
                // Deliberately no unfold. Talking from the corner ball is the
                // whole point of having one: you ask about what you are looking
                // at, and pulling the card back would cover exactly that. The
                // ball carries the listening state, and long-press is the way
                // back to the full card when you actually want it.
                // Also barge-in. Tapping the ball while Andee is talking means
                // the user wants the floor, so cutting TTS here keeps the
                // agent's own voice out of the mic.
                startTurn()
            }

            override fun onSettingsClick() {
                openSettings()
            }

            /**
             * ✓ in the control bar — the self-check card.
             *
             * The same *kind* of thing as ⚙ next to it, which is the whole
             * argument for it being a card rather than the fullscreen page it
             * used to be: those two buttons open our own surfaces rather than
             * acting on the screen, so they open the same shape of surface. See
             * [net.kuafuai.andee.ui.SelfCheckUi] for the rest.
             *
             * Deliberately not routed through the dispatcher — nothing is being
             * asked of the assistant, so this starts no task and lights no glow.
             */
            override fun onSelfCheckClick() {
                openSelfCheck()
            }

            override fun onTextInputToggle() {
                toggleTyping()
            }

            override fun onLongPress() {
                // Long-press the ball — folded or not — asks for the full card
                // back: the subtitle band and the tool row only exist at full
                // size, and tapping is taken by the mic.
                dispatcher.expand()
            }

            override fun onToolClick(tool: FloatingWindowUi.Tool) {
                runTool(tool)
            }

            override fun onStopClick() {
                stopEverything()
            }

            // The same fold the assistant's own tool calls get — same corner
            // ball, same glow — so ⤡ and the brain aren't two mechanisms. The
            // only difference is that nothing here comes back on its own.
            override fun onMinimizeClick() {
                dispatcher.compactForUser()
            }

            /**
             * Re-show a page from the scrollback, or read a message fullscreen.
             *
             * One function for both because to the user they are one thing: a
             * fullscreen screen that came out of the conversation. Deliberately
             * *not* through the dispatcher — nothing is being asked of the
             * assistant, so this starts no task and lights no glow.
             */
            override fun onHistoryPageClick(entry: net.kuafuai.andee.ui.ChatHistory.Entry) {
                val file = entry.page ?: return
                openPageFrom(entry, file)
            }

            /**
             * Double-tapping a message: read it fullscreen ([TextPage]).
             *
             * The message is written out as the same kind of page the model
             * composes, so it gets the same reader, the same ✕完成, and the same
             * way back.
             */
            override fun onTextOpen(entry: net.kuafuai.andee.ui.ChatHistory.Entry) {
                val file = net.kuafuai.andee.ui.TextPage.write(this@ScreenBodyService, entry) ?: return
                android.util.Log.i(
                    "Body",
                    "text page: ${entry.text.length} chars → ${file.name}",
                )
                openPageFrom(entry, file)
            }
        })
        // Before the card is built: FloatingWindowUi.build() reads the log to
        // lay out its first rows, and registers itself as the listener.
        net.kuafuai.andee.ui.ChatHistory.init(this)
        // Same object+init(ctx) shape as the two singletons around it, and for
        // the same reason: it wants a Context and nothing else, so nobody's
        // constructor has to grow an argument. Stays off unless switched on in
        // settings.
        net.kuafuai.andee.screen.GroundingLog.init(this)
        // Logged, because a refusal here is the quietest failure this app has:
        // `addView` without SYSTEM_ALERT_WINDOW throws, `show()` swallows it,
        // and the service goes on to build the whole object graph and answer the
        // socket with nothing at all on screen. `runStartupSelfCheck` cannot
        // report it either — the card it would raise needs the missing
        // permission. So this line is the only trace, and `ensureOverlays` is
        // the way back.
        if (!window.show()) {
            android.util.Log.e("Body", "overlay refused: no window (SYSTEM_ALERT_WINDOW?)")
        }
        // Little signboards (ask/alert cards) live in their own overlay
        // windows — they need nothing but the WindowManager, but init them
        // here where the service context is alive.
        net.kuafuai.andee.ui.CardUi.init(this)
        // The signboard's only source of work. Its listener is a separate
        // service the system binds on its own schedule, and after an APK
        // reinstall it routinely never rebinds — the permission still reads as
        // granted and notifications simply stop arriving. Ask for the rebind
        // from the one place guaranteed to run on every start.
        net.kuafuai.andee.device.NotificationRelayService.ensureBound(this)
        // Start as the card, not as the corner ball.
        //
        // This used to be `setCompact(true)`, and the reason was written above
        // it: a fullscreen overlay is touchable as a whole window — returning
        // false from its root touch handler does not pass the event to the app
        // below — so a service that comes up folded cannot cover whatever the
        // user was doing when they flipped the switch in 设置. That reasoning
        // was about *enabling*, and it cost more than it bought. The card is
        // the only thing that shows the scrollback, the tool row and the
        // minimise control, and a device that came back from a reboot showed
        // none of them: just a 168 dp ball in the corner whose one way in (a
        // long-press) nothing on screen mentions. The user turned this on to
        // have the assistant; it should be there when it starts.
        //
        // Nothing here traps them. The card carries ⤡, and every `screen.*`
        // call still folds it before the brain drives another app (see
        // CommandDispatcher.ensureCompact) — that fold is what the old note was
        // really protecting, and it is untouched.
        //
        // Queued behind `show()`, which still builds the window already compact
        // and seeds `fullGeometry` from that state. One `ui.post` later
        // exitCompact() grows it to the recorded full-screen rectangle, so this
        // arrives as the same geometry change a long-press makes — one code
        // path, not a second way for the window to be born.
        window.setCompact(false)

        // After the card: same window type, so insertion order is z-order and
        // the glow needs to sit on top of the ball, not under it.
        ripple = EdgeRippleUi(this)
        ripple.show()

        // Last of the three overlays, so a mark is never drawn under the glow.
        marker = net.kuafuai.andee.ui.TapMarkerUi(this)
        marker.show()

        audio = AudioIO()
        tts = TtsController(this, audio, window)
        // Follow-up window: when an answer has finished draining, the mic
        // opens by itself for a short beat — as if the user had pressed the
        // talk button the moment the ball went quiet. A conversation where
        // "再帮我…" lands right on the heels of the answer, no reaching for
        // the ball, no wake word. If the user says nothing the recorder's own
        // silence watchdog closes it again in ~3 s — the same close as a
        // manual press that went nowhere, not a new code path.
        tts.onDrained = { ui.post { followUpWindow() } }
        // An answer that could not be spoken is not an answer that failed: the
        // words are already a row in the scrollback by the time speaking
        // starts. Folded, though, that row is `GONE` — so this is where a
        // device with no working speaker still manages to say something.
        tts.onUnavailable = { ui.post { showAnswerAsCard() } }
        // The field the user types into. A window of its own, and an Activity
        // rather than an overlay — an overlay window cannot raise the soft
        // keyboard on this ROM. See ui/TextInputActivity.
        net.kuafuai.andee.ui.TextInputActivity.onSubmit = { submitUserTurn(it, "typed") }
        // Fires on every way out of the field; see [typingFinished].
        net.kuafuai.andee.ui.TextInputActivity.onClosed = { typingFinished() }
        // And this is how the card knows where to stop: the field's own top
        // edge, reported whenever the keyboard resizes it.
        net.kuafuai.andee.ui.TextInputActivity.onTop = {
            android.util.Log.i("Body", "field top: $it px")
            window.setTypingTop(it)
        }
        asr = AsrController(
            this, audio, window,
            onTranscript = { submitUserTurn(it, "voice") },
            onError = { msg -> reportVoiceFailure(msg) },
        )

        // The meeting recorder shares the one AudioIO with the voice turn, so
        // only one of them can ever be live — see [startTurn], where a ball tap
        // during a meeting means "end it" rather than "talk to me".
        meeting = net.kuafuai.andee.meeting.MeetingRecorder(
            this, audio, window,
            expand = { dispatcher.expand() },
        ) { payload ->
            // The user ended it (or a phone call did), so the brain has no idea
            // the meeting is over and no transcript in hand. Ask it for the
            // minutes the way the user would have: as a turn on `asr.final`.
            //
            // Deliberately a query and not a `meeting.ended` event. An event
            // kind is not in ToolSchemas, so the brain has to be taught it
            // separately or it is silently dropped — which looks, from here,
            // exactly like the ball doing nothing. `asr.final` is the one
            // channel every brain already answers, it already lights the glow
            // and starts a task, and what we want *is* a turn. The cost is
            // that the request enters the brain's context as something the
            // user said, so the text below is written to be true when read
            // that way.
            // Display-only, and it follows the interface language. The brain is
            // handed `meetingMinutesQuery` two lines down, not this text:
            // `ChatHistory` is read by the history card and by nothing else.
            // The parenthesised stage-direction shape is kept because it is
            // what the brain *does* receive looks like — the user should see
            // the same thing they would hear about.
            net.kuafuai.andee.ui.ChatHistory.addUser(
                AppLocale.str(this, R.string.svc_meeting_ended_line, payload.optInt("chars"))
            )
            val query = meetingMinutesQuery(payload)
            val client = hubClient
            val server = wsServer
            val brain = localBrain
            val event = JSONObject()
                .put("text", query)
                .put("ts", System.currentTimeMillis())
            // Off the caller's thread for the same reason task.stop is: a
            // WebSocket send blocks once the outbound buffer fills, and this
            // one is running on the recorder's teardown thread.
            Thread {
                runCatching { client?.sendEvent("asr.final", event) }
                runCatching { server?.broadcastEvent("asr.final", event) }
            }.start()
            // The local brain has none of the "teach it a new event kind"
            // problem the comment above is about — it would happily take a
            // purpose-built call. It goes through the same submit path anyway,
            // so a meeting ends identically in both modes and the text above
            // stays the one thing that explains the transcript.
            if (client != null || brain != null || (server?.clientCount() ?: 0) > 0) {
                ui.post {
                    acceptBrainOutput = true
                    dispatcher.beginTask()
                    window.setState(FloatingWindowUi.State.THINKING)
                    // After the gate opens, not before: the local brain answers
                    // on its own thread, and a stop still pending from the turn
                    // before this one would otherwise swallow the minutes.
                    brain?.submit(query)
                }
            }
        }

        screen = ScreenController(this, marker)
        // The dog hangs off USB OTG. Nothing here touches the hardware: no port
        // is opened, no permission is asked for, and a tablet with no dongle
        // plugged in pays nothing until something calls a dog_* tool. See
        // DogLink for why the connection is lazy and DogMotion for why the
        // motion tools never block.
        //
        // DogSense registers no sensors here either — the tablet is bolted to
        // the dog, so its gyroscope is the dog's, but a tablet nobody drives a
        // dog from should not be sampling one. DogMotion attaches on first use.
        dogLink = net.kuafuai.andee.dog.DogLink(this)
        dogSense = net.kuafuai.andee.dog.DogSense(this)
        dogMotion = net.kuafuai.andee.dog.DogMotion(dogLink, dogSense)
        dispatcher = CommandDispatcher(
            screen, window, ripple, marker,
            net.kuafuai.andee.device.DeviceInfoController(this),
            net.kuafuai.andee.device.DeviceActionsController(this),
            net.kuafuai.andee.device.DeviceCommsController(this),
            net.kuafuai.andee.device.PermissionsController(this),
            meeting,
            dogMotion,
            this,
            forwardExternal = { method, params ->
                // Unknown to the dispatcher → try the call extension. The
                // server enforces the timeout and turns a missing extension
                // into a proper error; the throw here becomes brain-visible.
                val srv = wsServer
                    ?: throw IllegalStateException(
                        "no local server running — cannot reach call extension"
                    )
                srv.forwardToExtension(method, params)
            },
            // The page no longer blocks the tool call, so this callback is the
            // only way the brain learns the user is done with it. See
            // [notifyPageClosed].
            onPageClosed = { title, seconds -> notifyPageClosed(title, seconds) },
            // The ask/alert cards speak their text; the mute rules live here,
            // not in the dispatcher. See [speakCardText].
            sayAloud = { text -> speakCardText(text) },
        )
        // Extension coming or going invalidates the hub's cached tool list; the
        // hub has no incremental protocol, so we bounce the outbound client and
        // let it re-register with the fresh payload. Same lever [openSettings]
        // already pulls when the hub URL changes.
        callExtensions.onChange = {
            ui.post {
                if (localBrain != null) {
                    // Nothing to re-register: the local brain rebuilds its tool
                    // list from the registry at the start of every turn, so the
                    // next thing the user says already sees the new arm. And
                    // bouncing it here would throw away the conversation.
                    android.util.Log.i("Body", "call extension state changed → local brain picks it up next turn")
                } else {
                    android.util.Log.i("Body", "call extension state changed → restart hub client")
                    stopHubClient()
                    startHubClient()
                }
            }
        }
        startWsServer()
        // Before the brain, so the very first `type_text` finds the keyboard
        // where it expects it. A no-op unless the device is resting on the
        // headless IME — see [settleKeyboardOwner].
        settleKeyboardOwner()
        // `announce = false`: this start is going to end in one card that lists
        // *everything* that is wrong, so the no-brain complaint must not also
        // fire here or the user gets two cards arguing about the same fact.
        // `runStartupSelfCheck` reports it, in context, with the other rows.
        // The settings card's factory reset still announces normally — see
        // [applyFactoryReset] — because there the user just wiped the config
        // and needs to be told the device is now mute.
        startBrain(announce = false)
        startWakeWord()
        runStartupSelfCheck()
    }

    /**
     * "Is this device actually ready?", asked once per start and only spoken up
     * about when the answer is no.
     *
     * **Why the service asks at all**, given the same list is one tap away on
     * the card (`✓`, right next to ⚙): the card is the *reference*, and this is
     * the *nudge*. The failures [SelfCheck] looks for are all
     * silent by construction — a missing overlay grant means the ball never
     * draws and the service looks healthy, a zombie notification grant means
     * notifications vanish while Settings reports them on. Nobody goes looking
     * for a checklist on a device that appears to be working; they only go
     * looking once something they expected did not happen.
     *
     * **Only [SelfCheck.Level.FAIL] and [SelfCheck.Level.WARN] may interrupt.**
     * The rest of the list (the wake phrase, the disk, a probe that was
     * skipped) is a fact, not a fault, and a startup card that fires on facts
     * is a startup card the user learns to dismiss without reading. This is the
     * whole reason the levels exist.
     *
     * **Deduped by content, not by time.** A device that boots twenty times a
     * day — this one is on a dog — would otherwise get the same card twenty
     * times for the same missing permission. The signature is the *set* of
     * problems, so a new or changed problem gets through immediately, and an
     * unchanged one is mentioned once every [REPEAT_AFTER_MS] until it is
     * fixed. Fixing everything clears the record, so a relapse is reported
     * straight away rather than waiting out the window.
     *
     * Runs on its own thread because [SelfCheck.run] probes the backend and the
     * voice host over the network, and blocks while it does.
     */
    private fun runStartupSelfCheck() {
        if (!::window.isInitialized) return
        val app = applicationContext
        Thread {
            val found = runCatching { net.kuafuai.andee.device.SelfCheck.run(app) }
                .getOrNull() ?: return@Thread
            val problems = found.filter { net.kuafuai.andee.device.SelfCheck.needsAttention(it) }
            val prefs = getSharedPreferences(SELF_CHECK_PREFS, MODE_PRIVATE)
            if (problems.isEmpty()) {
                prefs.edit().remove(SELF_CHECK_SIGNATURE).apply()
                android.util.Log.i("Body", "self-check: all clear (${found.size} checked)")
                return@Thread
            }
            val signature = problems.joinToString(",") { "${it.id}:${it.level}" }
            val now = System.currentTimeMillis()
            val lastAt = prefs.getLong(SELF_CHECK_SEEN_AT, 0L)
            if (prefs.getString(SELF_CHECK_SIGNATURE, null) == signature &&
                now - lastAt < REPEAT_AFTER_MS
            ) {
                android.util.Log.i("Body", "self-check: $signature — already reported, quiet")
                return@Thread
            }
            prefs.edit()
                .putString(SELF_CHECK_SIGNATURE, signature)
                .putLong(SELF_CHECK_SEEN_AT, now)
                .apply()
            android.util.Log.w("Body", "self-check: $signature")
            ui.post { showSelfCheckCard(problems) }
        }.start()
    }

    /**
     * The one card. [problems] is never empty — see [runStartupSelfCheck].
     *
     * The body is the *titles*, not the counts: "悬浮窗权限 · 麦克风" tells the
     * user in two words whether this is the errand they already know about, and
     * the card behind 去处理 is what explains each one. Joining with " · "
     * rather than a comma because the two languages disagree about commas and
     * agree about this.
     *
     * **去处理 opens the card, not the Activity.** This callback only runs with
     * the window up (see the guard below), which means accessibility and the
     * overlay grant are both already fine — so the card can be put up exactly
     * the way `✓` puts it up, and the launcher Activity stays what it is: the
     * door for the device where none of this works.
     */
    private fun showSelfCheckCard(problems: List<net.kuafuai.andee.device.SelfCheck.Finding>) {
        // The overlay grant is one of the things being reported, so the window
        // may well not exist — a card needs the same permission it is about to
        // complain about. The launcher page is the fallback for exactly that.
        if (!::window.isInitialized || !window.isShown()) return
        val open = AppLocale.str(this, R.string.check_card_open)
        val body = problems.joinToString(" · ") { AppLocale.str(this, it.title) }
        net.kuafuai.andee.ui.CardUi.ask(
            AppLocale.str(this, R.string.check_card_title, problems.size) + "\n" + body,
            listOf(open, AppLocale.str(this, R.string.check_card_ack)),
        ) { r ->
            if (r.button == open) openSelfCheck()
        }
    }

    /**
     * Tell the brain the user dismissed a page, **without asking it anything**.
     *
     * `ui.show_html` used to return the news itself, by blocking until the page
     * closed — which cost the conversation (one thread, one turn: a parked tool
     * parkes everything the user says after it). It now returns the moment the
     * page is up, so this is where the other half of that information goes.
     *
     * Deliberately *not* an `asr.final` turn like the meeting minutes below.
     * The minutes are a request — the model has to produce them, so a turn is
     * exactly right. This is a fact, and a turn would buy two bad things: a
     * model call and a spoken sentence every single time a page is dismissed
     * ("好的，看完了"), and an entry in the context that claims the user said
     * it. So the local brain gets it as a silent [LocalBrain.note] (visible to
     * the next turn, never spoken), and a hub gets a purpose-built
     * `page.closed` event it can ignore.
     *
     * A hub that has not been taught `page.closed` drops it silently, which is
     * the pre-existing behaviour for this class of news — nothing is waiting on
     * it, unlike `asr.final`, where a dropped event leaves the ball looking
     * broken.
     *
     * **The note below is English, like the rest of the model-facing set** —
     * see the header of `brain/LocalPrompt.kt`. `values-en/` covers what the
     * *user* reads, and this is not that: nothing at all is displayed here, so
     * no tablet loses anything either way. Same rule as [meetingMinutesQuery],
     * [callSummaryQuery] and the todo wake-up prompts. What the translation
     * does buy is a new problem: an English prompt pulls the answer towards
     * English, which is exactly what §9 of `LocalPrompt` is there to hold back.
     */
    private fun notifyPageClosed(title: String, seconds: Int) {
        // Logged whatever the brain is: this is the one device-side fact in the
        // flow that has no tool call around it any more, so it is the thing to
        // look for when a page closes and something does not happen.
        android.util.Log.i("Body", "page closed: 「$title」 after ${seconds}s")
        localBrain?.note(
            "(background note: the user just closed the page you were showing, 「$title」, after $seconds seconds. " +
                "This is not something the user said, so there is no need to answer; carry on only if that thing still has a next step.)"
        )
        val event = JSONObject()
            .put("title", title)
            .put("seconds", seconds)
            .put("ts", System.currentTimeMillis())
        // Off the caller's thread, same reason the meeting push is: a WebSocket
        // send blocks once the outbound buffer fills, and this one runs on the
        // main thread (HtmlActivity.onDestroy).
        Thread {
            runCatching { hubClient?.sendEvent("page.closed", event) }
            runCatching { wsServer?.broadcastEvent("page.closed", event) }
        }.start()
    }

    /**
     * The request the device makes of the brain when a meeting ends without
     * the brain having asked for it.
     *
     * This is **prompt**, not a log line — it is the whole of what the brain
     * knows about why a transcript just landed in its context, so it has to
     * carry the instruction, the format, and the reason the meeting stopped.
     * `show_html` is named explicitly because it is the only route minutes
     * have to the user's eyes; a brain that merely replies has produced
     * nothing the user asked for.
     *
     * **Almost nothing in here is displayed** — it only lands in the brain's
     * context. The exception is the title on the next line: it becomes the page
     * header the minutes are laid out under, so the user reads it too. It used
     * to be a Chinese literal, on the reasoning that the brain is told the same
     * title and the two must not disagree. The fix keeps that invariant and
     * drops the literal — `MeetingRecorder.start` writes the same key and this
     * reads it back — so the two still agree, *and* an English tablet is not
     * shown a Chinese page header. The prose around it is English like the rest
     * of the model-facing set — see the header of `brain/LocalPrompt.kt`.
     */
    private fun meetingMinutesQuery(p: JSONObject): String {
        val title = p.optString("title")
            .ifEmpty { AppLocale.str(this, R.string.meeting_default_title) }
        val minutes = (p.optLong("duration_ms") / 60_000).coerceAtLeast(1)
        val why = when (p.optString("stopped_by")) {
            // Worth telling it apart: the brain says this back to the user,
            // and a meeting cut short by a phone call may be missing its end.
            "focus" -> "(a phone call or another app took the microphone, so the recording ended early)"
            else -> "(I tapped the ball to end the recording)"
        }
        val truncated = if (p.optBoolean("truncated")) {
            "\nNote: the transcript was too long, so a middle section was left out; the full version is on the device at ${p.optString("file")}."
        } else {
            ""
        }
        return buildString {
            append("$why The meeting just finished, and the transcript is below.\n")
            append("Turn it into meeting minutes, then use the show_html tool to lay the minutes out as an HTML page and show it to me")
            append(" — a text-only reply is something I cannot see.\n")
            append("The minutes should contain: the subject, the key points, the conclusions reached, and action items (who does what).")
            append("The transcript comes out of speech recognition, so it will have wrong characters and missing punctuation; follow the context.\n\n")
            append("Title: $title\nDuration: about $minutes minutes$truncated\n\nTranscript:\n")
            append(p.optString("transcript"))
        }
    }

    /**
     * Open the mic for a turn — the one path, whether the user tapped the ball
     * or said the wake phrase.
     *
     * A toggle rather than a start: tapping the listening ball is how you take
     * it back. The wake word never reaches the "off" half of that, because
     * [WakeWord]'s gate closes its own microphone while this turn is live.
     *
     * During a meeting the ball means one thing only: end it. That is not a
     * special case bolted on — it is the *only* control the user has left,
     * since the recorder holds the microphone and the wake word is gated off,
     * so they cannot ask for anything by voice. It also closes a trap: the two
     * share one [AudioIO], and [AsrController.isActive] is literally
     * `audio.isStreaming()`, so without this a tap would fall into the "off"
     * half of the toggle and tear the recording down with no error anywhere.
     */
    private fun startTurn() {
        if (meeting.isActive()) {
            meeting.stopFromUser()
            return
        }

        if (net.kuafuai.andee.device.CallState.isActive) {
            window.setSubtitle(
                AppLocale.str(this, R.string.svc_call_in_progress),
                FloatingWindowUi.SubtitleKind.FINAL,
            )
            return
        }
        tts.stopSpeaking()
        // Talking takes the floor from typing. Leaving both live would put the
        // keyboard on the user's IME while the brain is about to drive another
        // app — precisely the state `type_text` fails in.
        closeTyping()
        acceptBrainOutput = true
        asr.toggle()
    }

    /**
     * One turn, one way in.
     *
     * A sentence the user **typed** and a sentence they **said** are the same
     * thing from here on, and they have to be: this is what lights the task,
     * writes the scrollback row, tells the hub and hands the text to a local
     * brain. Two copies of it would drift, and the hub event in particular is
     * easy to forget — its failure mode is a device that answers nothing.
     *
     * [how] is only for the log line.
     */
    private fun submitUserTurn(text: String, how: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        android.util.Log.i("Body", "$how: $said")
        // Hand the keyboard back before the turn exists: the brain may call
        // `type_text` inside it, and it can only do that on ADBKeyboard.
        closeTyping()
        // The user is asking for something, so the answer is theirs to see.
        acceptBrainOutput = true
        net.kuafuai.andee.ui.ChatHistory.addUser(said)
        val payload = org.json.JSONObject()
            .put("text", said)
            .put("ts", System.currentTimeMillis())
        hubClient?.sendEvent("asr.final", payload)
        wsServer?.broadcastEvent("asr.final", payload)
        // Same turn, different brain. The local one takes the text directly —
        // there is no socket in between, and no event kind it would have to be
        // taught (see the meeting callback).
        localBrain?.submit(said)
        // Task start, and deliberately at the request rather than at the
        // reply: from here the device is being worked on, whether that reaches
        // us as a tool call or as thirty seconds of thinking. Nobody listening
        // means nobody is thinking about it, so don't light up for an empty
        // room.
        if (hubClient != null || localBrain != null || (wsServer?.clientCount() ?: 0) > 0) {
            dispatcher.beginTask()
            window.setState(FloatingWindowUi.State.THINKING)
        }
    }

    /**
     * Two taps on the ball — the user wants to type instead of talk.
     *
     * Opening is two steps in one order: move the keyboard to a real IME
     * *first*, then put the field up. The other order hands the user a field
     * whose keyboard is headless, and nobody can type into one of those.
     *
     * The card does not go anywhere. It is a fullscreen overlay, and overlays
     * sort above the keyboard, so it has to *shrink* to clear the field — which
     * it learns from the field itself, through `TextInputActivity.onBand`. The
     * first cut hid the card instead and that read badly: what is behind a
     * hidden card is whatever app the user was on, so typing looked like being
     * thrown out of the assistant and then dropped back in.
     */
    private fun toggleTyping() {
        if (net.kuafuai.andee.ui.TextInputActivity.isShowing) {
            net.kuafuai.andee.ui.TextInputActivity.dismiss()
            return
        }
        // No keyboard to borrow and nothing to switch: the device's own IME is
        // the user's, and this field types on it like any other app's would.
        // The only way this fails is a tablet whose single IME is the headless
        // one — where nobody can type anywhere, and a field here would take a
        // sentence and show it to nobody.
        if (net.kuafuai.andee.ui.ImeSwitch.typable(this).isEmpty()) {
            net.kuafuai.andee.ui.CardUi.error(
                AppLocale.str(this, R.string.svc_no_typable_ime),
            )
            return
        }
        typingFromFullscreen = !window.isCompact()
        net.kuafuai.andee.ui.TextInputActivity.start(this)
    }

    /**
     * The field is gone — let the card have its height back, and unfold only if
     * it was unfolded to begin with.
     *
     * Called from `TextInputActivity.onClosed`, which fires from its `onDestroy`
     * on every way out — `✕`, BACK, send, or this service dismissing it. Hanging
     * it off the send button instead would leave the card short of the bottom of
     * the screen with nothing left to grow it back.
     *
     * The second half of the condition is the interesting one. Every `screen.*`
     * call folds the card, because the brain is about to drive another app and a
     * fullscreen card would cover it. So a card that *was* fullscreen can have
     * been folded while the field was up, and unfolding over that would hide the
     * app the brain is working. If it is folded, it stays folded: something has
     * a better reason than the user's place does.
     */
    private fun typingFinished() {
        window.setTypingTop(0)
        if (typingFromFullscreen && !window.isCompact()) dispatcher.expand()
        typingFromFullscreen = false
    }

    /**
     * Show a fullscreen page the user asked for from a row, and put the card
     * back the way they left it.
     *
     * Folding the card is an implementation detail of the page being fullscreen
     * — an overlay sorts above everything, so a card left up would sit on the
     * page — and it therefore has to be undone when the page closes. Without
     * that, ✕完成 reads as "throw me out of the assistant": you open a long
     * answer to read it, close it, and find yourself looking at a ball in the
     * corner of the home screen.
     *
     * The fold is undone unconditionally, unlike the typing field's, and the
     * difference is *who folded*. There, the card never folds, so a fold found
     * on the way out must have been the brain's (every `screen.*` call folds
     * before it drives another app) and is left alone. Here the fold is ours —
     * an implementation detail of the page being fullscreen — so leaving it
     * standing is the bug this fixes. The rare case (the brain folds the card
     * again while the user is reading the page) resolves in favour of the user's
     * place, because the alternative is ✕完成 silently throwing them out of the
     * assistant.
     */
    private fun openPageFrom(entry: net.kuafuai.andee.ui.ChatHistory.Entry, file: java.io.File) {
        val wasFullscreen = !window.isCompact()
        dispatcher.compactForUser()
        // The ball hangs off the ledge rather than sitting on the page.
        window.setPagePerched(true)
        net.kuafuai.andee.ui.HtmlActivity.showFile(this, file, entry.text.take(24)) {
            window.setPagePerched(false)
            if (wasFullscreen) dispatcher.expand()
        }
    }

    /**
     * Put the keyboard back on a real IME if it is resting on the headless one.
     *
     * Only reachable on a device set up under the old rule, or by hand — and
     * worth repairing rather than reporting: nobody chooses a keyboard with no
     * keys, and a device left on it cannot be typed on anywhere. See
     * [net.kuafuai.andee.ui.ImeSwitch.ensureHumanDefault].
     */
    private fun settleKeyboardOwner() {
        net.kuafuai.andee.ui.ImeSwitch.ensureHumanDefault(this)
    }

    /**
     * Voice failed, and the user has to be told *and* offered the way out.
     *
     * The way out belongs **on the card**, not in a sentence pointing at the
     * card's chrome. That was learned the hard way: the message used to name a
     * button in the top bar, and the top bar is `GONE` while the card is folded
     * — folded being where this device spends its life — so it was an
     * instruction to press something that was not on the screen, in the one
     * state the message existed for. A button on the card cannot be hidden by
     * the fold. (The way *in* has since moved onto the ball's double-tap, which
     * is the same lesson applied to the other end: the ball is the one control
     * that is there in both shapes.)
     *
     * The scrollback row is the one [net.kuafuai.andee.ui.CardUi.error] would
     * have left: the failure outlives the card timing out.
     */
    private fun reportVoiceFailure(msg: String) {
        android.util.Log.w("Body", "voice failed: $msg")
        net.kuafuai.andee.ui.ChatHistory.addError(
            AppLocale.str(this, R.string.svc_voice_error_line, msg)
        )
        // The labels are held here and used **twice**: once to build the card,
        // once to recognise the answer. `CardUi` hands the handler back the
        // string it displayed, so a translated label tested against a
        // hardcoded one would never match — the button would look fine and do
        // nothing. One lookup, two uses, and that failure cannot happen.
        val dismiss = AppLocale.str(this, R.string.card_ack)
        val typeInstead = AppLocale.str(this, R.string.svc_btn_type_instead)
        val choices = if (net.kuafuai.andee.ui.ImeSwitch.typable(this).isEmpty()) {
            // Nothing on this device can be typed on. Offering the button would
            // be the same lie in a different shape.
            listOf(dismiss)
        } else {
            listOf(typeInstead, dismiss)
        }
        android.util.Log.i("Body", "voice failed → offering ${choices.first()} on the card")
        net.kuafuai.andee.ui.CardUi.ask(
            AppLocale.str(this, R.string.svc_voice_failed, msg),
            choices,
        ) { r ->
            android.util.Log.i("Body", "voice-failure card answered: ${r.button} (${r.how})")
            if (r.button == typeInstead) toggleTyping()
        }
    }

    /**
     * The device is up and cannot answer anything — no brain configured, or a
     * key missing. Say so, and put the fix on the same card: the settings card
     * opens fine while the ball is folded, the ball's own chrome does not, and
     * "长按小球 → ⚙" is a three-step errand for something one button can do.
     *
     * Both brains have this failure mode and both report it through here, so
     * the sentence differs and nothing else does.
     *
     * [what] arrives already localised — the two callers are the only places
     * that know which brain is missing, so they hand in the sentence. Same
     * one-lookup-two-uses rule as [reportVoiceFailure] for the button.
     *
     * @param card false to write the scrollback row and skip the interruption.
     *   Only the service's own start passes false, and only because
     *   [runStartupSelfCheck] is about to say the same thing in a card that
     *   also carries the other rows. Two cards for one fact read as two
     *   problems.
     */
    private fun reportNoBrain(what: String, card: Boolean = true) {
        net.kuafuai.andee.ui.ChatHistory.addError(what)
        if (!card) return
        val openSettingsLabel = AppLocale.str(this, R.string.svc_btn_open_settings)
        net.kuafuai.andee.ui.CardUi.ask(
            what,
            listOf(openSettingsLabel, AppLocale.str(this, R.string.card_ack)),
        ) { r ->
            if (r.button == openSettingsLabel) openSettings()
        }
    }

    /**
     * The field goes away. Safe to call always, including when it was never up.
     *
     * There is nothing to hand back any more: the keyboard only moves inside
     * `ScreenController.typeViaAdbKeyboard`, for the length of one tool call, so
     * between turns the device sits on the user's own IME and this field simply
     * types on it. What this is still for is the *turn* boundary — a turn that
     * starts by voice must not leave a half-typed sentence in a window over the
     * app the brain is about to drive.
     */
    private fun closeTyping() {
        net.kuafuai.andee.ui.TextInputActivity.dismiss()
    }

    /**
     * The answer could not be spoken — put it up instead.
     *
     * Deliberately only when folded. Unfolded, the answer is already a row in
     * the scrollback and a card over it would be noise; folded, that row is
     * `GONE` and without this the user watches the ball go quiet with nothing
     * to read anywhere. A device whose speaker does not work should still be a
     * device that can be used.
     */
    private fun showAnswerAsCard() {
        val answer = lastAnswer ?: return
        if (!window.isCompact()) return
        net.kuafuai.andee.ui.CardUi.alert(answer) { }
    }


    /**
     * The mic window that opens by itself after an answer drains.
     *
     * Only when the answer left the conversation open — [expectFollowUp], the
     * brain's own judgment carried on the `[END]` marker ([END_MARKER]). A mic
     * that opens after every reply overhears the room: people chat near a
     * tablet that just finished a task, and answering chatter that was not
     * addressed to it is butting in, not helpfulness. A brain that never
     * emits the marker keeps the old always-listen behaviour, so this gate
     * fails open.
     *
     * Reuses [startTurn]'s machinery minus the toggle ambiguity: startTurn is
     * a press-to-toggle and would CLOSE a recording if one were somehow
     * already live. This always opens, and only when nothing else holds the
     * mic — the user may have started talking before the window fired (they
     * don't wait for our window), in which case their own press wins and
     * this is a no-op. The recorder's silence watchdog (3 s) is the close:
     * nothing said → quietly shuts, no task, no card, ball back to idle.
     *
     * The user is never locked out by a closed window: the wake word
     * ([startWakeWord]) and a tap on the ball ([startTurn]) still open a
     * turn whenever they want one.
     *
     * Main thread only (posted by [tts.onDrained]).
     */
    private fun followUpWindow() {
        if (!expectFollowUp) return
        if (asr.isActive() || tts.isSpeaking() || meeting.isActive() ||
            net.kuafuai.andee.device.CallState.isActive
        ) return
        net.kuafuai.andee.ui.ChatHistory.addError(AppLocale.str(this, R.string.svc_listening_again))
        asr.toggle()   // not streaming → starts; the 3 s silence watchdog closes it
    }

    /**
     * "嘿 Andee" as a second way to press the ball.
     *
     * The gate is the whole contract with [WakeWord]: it holds the microphone
     * only while every one of these is true, and lets go within ~100 ms of any
     * of them turning false.
     *
     * - **Screen on.** The user's choice, and it keeps a dark tablet on a
     *   bedside table from listening all night.
     * - **ASR idle.** The turn's own recorder must never lose a race to the one
     *   listening for a wake word.
     * - **TTS quiet.** Otherwise Andee hears itself say "嘿" and wakes up to
     *   its own voice.
     * - **No meeting running.** `asr.isActive()` happens to cover this today
     *   (it is `audio.isStreaming()`, which a meeting also sets), but that is a
     *   coincidence inside a method named for the ASR. Spelled out so a future
     *   narrowing of that method doesn't hand the wake word the microphone in
     *   the middle of an hour-long recording.
     */
    private fun startWakeWord() {
        screenOn = (getSystemService(POWER_SERVICE) as android.os.PowerManager).isInteractive
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            screenReceiver,
            android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
            },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        net.kuafuai.andee.wake.WakeWord.init(
            this,
            gate = {
                screenOn && !asr.isActive() && !tts.isSpeaking() &&
                        !meeting.isActive() && !net.kuafuai.andee.device.CallState.isActive &&
                        !net.kuafuai.andee.audio.Earcon.isSounding()
            },
            onWake = { ui.post { if (!asr.isActive()) startTurn() } },
        )
        net.kuafuai.andee.wake.WakeWord.start()
    }

    @Volatile
    private var screenOn = true

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
            screenOn = i?.action != android.content.Intent.ACTION_SCREEN_OFF
        }
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { net.kuafuai.andee.device.DeviceState.foregroundPkg = it }
        }
        if (::screen.isInitialized) screen.noteEvent(event)
    }

    override fun onDestroy() {
        // First, before anything else: the dog is the only thing this service
        // drives that keeps acting after we are gone. Every motion is supposed
        // to end on its own, but "supposed to" is not a reason to leave motors
        // running — cancel the timers, send the stop, let go of the cable.
        if (::dogMotion.isInitialized) dogMotion.shutdown()
        if (::dogLink.isInitialized) dogLink.close()
        stopBrain()
        stopWsServer()
        net.kuafuai.andee.wake.WakeWord.stop()
        runCatching { unregisterReceiver(screenReceiver) }
        if (::asr.isInitialized) asr.shutdown()
        if (::tts.isInitialized) tts.shutdown()
        // Before audio.release(): it ends the recording, which needs the mic
        // stream to still exist, and flushes the last segment to disk.
        if (::meeting.isInitialized) meeting.shutdown()
        if (::audio.isInitialized) audio.release()
        toolExec.shutdownNow()
        // shutdown, not shutdownNow: its queue is a few small appends and one
        // PNG, and losing them would silently truncate the record.
        net.kuafuai.andee.screen.GroundingLog.shutdown()
        settings?.hide()
        settings = null
        // Same treatment, and the extra call is the prober thread: the two
        // network probes run on their own executor and would otherwise hold a
        // thread for up to a connect timeout after the service is gone.
        selfCheck?.hide()
        selfCheck?.destroy()
        selfCheck = null
        // Before the windows go: this one has to put the keyboard back, and a
        // service that dies mid-typing would otherwise leave the tablet with
        // its keyboard on the user's IME — where every future `type_text`
        // fails, silently, with an error that blames the target app.
        closeTyping()
        if (::ripple.isInitialized) ripple.hide()
        if (::marker.isInitialized) marker.hide()
        if (::window.isInitialized) window.hide()
        instanceRef.compareAndSet(this, null)
        super.onDestroy()
    }

    private fun openSettings() {
        if (settings?.isShowing() == true) return
        // The settings card is focusable and full of text fields, so it takes
        // the keyboard the moment it appears. Leaving ours up would leave two
        // focusable windows fighting, with the user's sentence in the one that
        // lost. The keyboard goes back to the brain with it.
        closeTyping()
        // Snapshot hub and brain config before the user edits them; if either
        // changed on dismiss, bring the brain back up so the new URL / name /
        // mode / key take effect without needing to bounce the accessibility
        // service.
        val before = net.kuafuai.andee.config.VoiceConfig.hubConfig(this)
        val beforeBrain = net.kuafuai.andee.config.VoiceConfig.brainConfig(this)
        // Set when the user wiped everything from inside the card: the brain is
        // already back up on the emptied prefs by the time the card closes, and
        // running the comparison below would restart it a second time for the
        // same reason.
        var rebuiltByReset = false
        val s = SettingsUi(
            context = this,
            onDismiss = {
                settings = null
                if (rebuiltByReset) {
                    rebuiltByReset = false
                } else {
                    val after = net.kuafuai.andee.config.VoiceConfig.hubConfig(this)
                    val afterBrain = net.kuafuai.andee.config.VoiceConfig.brainConfig(this)
                    if (before.url != after.url || before.deviceName != after.deviceName ||
                        beforeBrain != afterBrain
                    ) {
                        android.util.Log.i("Body", "brain/hub config changed → restart brain")
                        // Restarting the local brain discards the conversation,
                        // which is the right trade: the user just changed the
                        // model, the key or the endpoint out from under it.
                        stopBrain()
                        startBrain()
                    }
                }
                // The self-check card can be sitting *under* this sheet: its
                // 打开设置 row opens us on top of itself. So this picker is
                // reachable while that card is on screen, and its language has
                // to follow or the user closes the sheet onto a card in the
                // language they just changed away from. The card's own picker
                // tells us outward; this is the same news arrived from the other
                // direction, so the card is only repainted, never re-announced.
                selfCheck?.repaintForLanguage()
            },
            onFactoryReset = {
                applyFactoryReset()
                rebuiltByReset = true
            },
            onClearChat = { clearChatHistory() },
            onClearNotes = { clearNotebook() },
            // The card owns its own language switch; the ball, the scrollback
            // and the pinned tips live in this service and have to be told.
            // See FloatingWindowUi.onLocaleChanged.
            onLocaleChanged = { window.onLocaleChanged() },
        )
        settings = s
        s.show()
    }

    /**
     * The self-check card, in a window of its own.
     *
     * [net.kuafuai.andee.ui.SelfCheckUi] draws it; this method is only the
     * wiring, and each wire is a decision worth naming:
     *
     *  * **A window, not the Activity.** `SelfCheckActivity` still exists and is
     *    still in the launcher, but it is now the *fallback* — it is the one
     *    surface that runs with the accessibility service off, which is exactly
     *    the state this list is most needed in. Whenever we are running, we can
     *    put the card up ourselves, and doing so keeps the check one tap from
     *    the gear rather than a round trip through a fullscreen page.
     *  * **Not exclusive with [settings].** The card offers 打开设置, which opens
     *    the settings sheet *over* it — the list stays behind to return to
     *    instead of the user losing their place. Two overlays, and
     *    [net.kuafuai.andee.ui.OwnCard] counts both.
     *  * **No `closeTyping()`.** Unlike [openSettings] there is no text field
     *    here, so there is no keyboard to hand back and nothing to compete for
     *    focus.
     */
    private fun openSelfCheck() {
        if (selfCheck?.isShowing() == true) return
        val s = net.kuafuai.andee.ui.SelfCheckUi(
            context = this,
            onDismiss = { selfCheck = null },
            // Our own sheet, reached through the service because that is where
            // it lives. Never null from here: if this method is running, we are.
            onOpenSettings = { openSettings() },
            // Give the screen back to the page we are about to open. Both windows
            // here — the folded-back assistant card and this very card — are
            // TYPE_APPLICATION_OVERLAY, and an overlay outranks every Activity on
            // the device. Without this, 去下载 opened a browser underneath the
            // card that had just asked the user to look at it, and 去开启 did the
            // same to the system Settings page. The fold is the one every
            // screen.* tool call and ⤡ already perform; this is one more place
            // that needs it. See SelfCheckUi.apply for the full argument.
            //
            // The card is closed rather than hidden, because there is no honest
            // way back: the browser is not ours and reports nothing when the user
            // returns, so a card left up would sit over whatever they actually
            // came back to. Folding the assistant is what makes the way back
            // sane instead — the ball is there, and the check reopens from ✓
            // with a fresh reading, which is what the user wants after they have
            // been off changing something anyway. Closing has to come last:
            // SelfCheckUi.hide runs onDismiss from Glass.exit's completion, and
            // that is what clears [selfCheck].
            onYieldScreen = {
                dispatcher.compactForUser()
                selfCheck?.hide()
            },
            // The card owns its language switch; the ball and the scrollback live
            // in this service and have to be told the same way [openSettings]
            // tells them.
            onLocaleChanged = { window.onLocaleChanged() },
        )
        selfCheck = s
        s.show()
        // Paint the local half immediately, then fill in the two probes behind
        // it. See SelfCheckUi.start.
        s.start()
    }

    /**
     * 恢复出厂设置: wipe everything this app owns, then rebuild out of nothing.
     *
     * Called from the settings card, after the user has read and confirmed the
     * list it showed them. The wiping is [net.kuafuai.andee.config.FactoryReset]'s
     * job; what belongs here is the half only a running service can do —
     * dropping the state the *process* still holds, and bringing the brain back
     * up against preferences that are now empty.
     *
     * [stopBrain] / [startBrain] rather than just a data wipe, because the
     * local brain holds the conversation in memory and the old key inside its
     * HTTP client: a reset that left it running would have the device keep
     * talking to the user with credentials that no longer exist. `startBrain`
     * then reads the emptied prefs, lands on the default (hub, no URL) and
     * dials nothing — which is what a fresh install does. It also re-runs
     * [catchUpPromises] with no promises left to catch.
     */
    private fun applyFactoryReset() {
        val cleared = net.kuafuai.andee.config.FactoryReset.wipe(this)
        android.util.Log.i("Body", "factory reset: $cleared target(s) cleared — rebuilding the brain")
        stopBrain()
        startBrain()
    }

    /**
     * 清空聊天记录, from the 诊断 section of the settings card.
     *
     * Two halves, and wiping only the first would be a lie: [ChatHistory.wipe]
     * empties the scrollback the user reads, but the conversation the *model*
     * is holding lives in the brain's memory — so the brain is rebuilt with
     * it. Anything less and the user has cleared a chat the device still
     * remembers word for word.
     *
     * The rebuild also aborts a turn in flight, deliberately: "forget this
     * conversation" said while one is running means that one too.
     */
    private fun clearChatHistory() {
        net.kuafuai.andee.ui.ChatHistory.wipe(this)
        android.util.Log.i("Body", "chat cleared — rebuilding the brain so the conversation goes with it")
        stopBrain()
        startBrain()
    }

    /**
     * 清空小本本, from the same place.
     *
     * **No brain rebuild, and that is the difference from [clearChatHistory].**
     * The notebook is not carried in the model's context: its index is read off
     * disk and stamped onto each user message, and `recall` goes to disk too.
     * So an empty notebook is in force from the very next turn without touching
     * the conversation — and taking the conversation down as well would be this
     * button doing the other button's job, for a user who pressed exactly one
     * of them.
     *
     * A turn already in flight can still be holding what an earlier `recall`
     * returned. Left alone on purpose: those are results the assistant was
     * given before the wipe, and yanking them mid-sentence would have it
     * contradict itself inside one answer.
     */
    private fun clearNotebook() {
        net.kuafuai.andee.config.Notebook.wipe(this)
        android.util.Log.i("Body", "notebook cleared")
    }

    /**
     * Stop the current task. Runs entirely on the caller (UI) thread because
     * every local step is non-blocking: [TtsController.stopSpeaking] cuts the
     * AudioTrack inline, [AsrController.stop] only queues onto its executor.
     * Deliberately not routed through [toolExec] — that queue may be holding a
     * swipe mid-dispatchGesture, which is seconds of exactly the wait we're
     * trying to abort.
     *
     * Three layers, and only the local ones are guaranteed: the brain is asked
     * to cancel over the hub socket, and a tool request already dispatched to
     * this device will still run to completion.
     */
    private fun stopEverything() {
        // ■ during a meeting means the same thing the ball does — end it, keep
        // what was said, let the minutes be written. Not "throw the hour away":
        // the transcript is the only copy, and the rest of this method would
        // leave the recorder holding the microphone anyway.
        if (meeting.isActive()) {
            meeting.stopFromUser()
            return
        }
        acceptBrainOutput = false
        tts.stopSpeaking()
        if (asr.isActive()) asr.stop()
        // Task over from the device's point of view: whatever the brain had
        // queued up, we've stopped wanting it to move the app.
        dispatcher.cancelTask()
        window.setState(FloatingWindowUi.State.IDLE)
        window.setSubtitle(
            AppLocale.str(this, R.string.svc_stopped),
            FloatingWindowUi.SubtitleKind.FINAL,
        )
        // The local brain is the one layer that genuinely stops: it drops the
        // turn and aborts the HTTP call in flight. A tool already dispatched
        // still runs to completion, same as over the hub.
        localBrain?.cancel()
        // Off the UI thread: WebSocket send blocks once the outbound buffer
        // fills. Silently dropped when no hub is connected, which degrades to
        // "stopped locally" — the right outcome for server-only mode.
        val client = hubClient
        if (client != null) {
            Thread {
                runCatching {
                    client.sendEvent(
                        "task.stop",
                        JSONObject().put("ts", System.currentTimeMillis()),
                    )
                }
            }.start()
        }
    }

    /**
     * Run a top-bar tool on a background thread (ScreenController.tap/swipe
     * block on dispatchGesture for up to ~2 s and would ANR the UI thread).
     * Result is echoed to the floating window subtitle.
     */
    private fun runTool(tool: FloatingWindowUi.Tool) {
        window.setSubtitle("${tool.name.lowercase()}…", FloatingWindowUi.SubtitleKind.PARTIAL)
        toolExec.execute {
            try {
                val result = when (tool) {
                    FloatingWindowUi.Tool.SWIPE_UP -> swipe(dx = 0, dy = -1)
                    FloatingWindowUi.Tool.SWIPE_DOWN -> swipe(dx = 0, dy = 1)
                    FloatingWindowUi.Tool.SWIPE_LEFT -> swipe(dx = -1, dy = 0)
                    FloatingWindowUi.Tool.SWIPE_RIGHT -> swipe(dx = 1, dy = 0)
                    // actor="user": these bypass the dispatcher, so without it
                    // the grounding log would file the user's own navigation
                    // under the brain's and the offline pairing would read a
                    // human's back-press as the agent correcting itself.
                    FloatingWindowUi.Tool.BACK -> screen.globalAction("back", actor = "user")
                    FloatingWindowUi.Tool.HOME -> screen.globalAction("home", actor = "user")
                    FloatingWindowUi.Tool.UI_TREE -> saveUiTree(screen.dumpUiTree(verbose = true))
                    // Through the dispatcher, not ScreenController directly: the
                    // overlay has to be blanked or the capture is just our own card.
                    FloatingWindowUi.Tool.SCREENSHOT ->
                        saveScreenshot(dispatcher.dispatch("screen.screenshot", null) as JSONObject)
                }
                window.setSubtitle(
                    "${tool.name.lowercase()} · $result",
                    FloatingWindowUi.SubtitleKind.FINAL,
                )
            } catch (t: Throwable) {
                net.kuafuai.andee.ui.CardUi.error(
                    "${tool.name.lowercase()} · ${t.message ?: t.javaClass.simpleName}"
                )
            }
        }
    }

    /**
     * Full-screen center swipe, 60% of the axis. dispatchGesture simulates
     * real touch events, which the overlay window would grab first — so we
     * flip the overlay to non-touchable for the duration of the swipe, then
     * restore it. Overlay stays visible either way.
     */
    private fun swipe(dx: Int, dy: Int): JSONObject {
        val dm: DisplayMetrics = resources.displayMetrics
        val cx = dm.widthPixels / 2
        val cy = dm.heightPixels / 2
        val ax = (dm.widthPixels * 0.3f).toInt()
        val ay = (dm.heightPixels * 0.3f).toInt()
        val durationMs = 300L
        window.setTouchable(false)
        // The glow window counts toward the opacity Android checks before it
        // trusts an injected gesture, so it has to step aside too — see
        // EdgeRippleUi's KDoc. Same for a tap marker still on screen.
        ripple.setVisibleForGesture(false)
        marker.setVisibleForGesture(false)
        // updateViewLayout is posted to the UI thread; give it a couple of
        // frames to actually apply before the gesture starts.
        Thread.sleep(50)
        try {
            // Goes straight to the pixel swipe, so swipeNorm's own hook never
            // fires — but the screen still moves under everything the brain
            // was aiming at, and this one was the user's doing.
            net.kuafuai.andee.screen.GroundingLog.noteSwipe(
                nx1 = (cx - dx * ax) * 1000 / dm.widthPixels,
                ny1 = (cy - dy * ay) * 1000 / dm.heightPixels,
                nx2 = (cx + dx * ax) * 1000 / dm.widthPixels,
                ny2 = (cy + dy * ay) * 1000 / dm.heightPixels,
                durationMs = durationMs,
                actor = "user",
            )
            return screen.swipe(cx - dx * ax, cy - dy * ay, cx + dx * ax, cy + dy * ay, durationMs)
        } finally {
            ripple.setVisibleForGesture(true)
            marker.setVisibleForGesture(true)
            window.setTouchable(true)
        }
    }

    /**
     * The full UI tree is huge — write to a JSON file under app-external
     * files so you can `adb pull` it, and return a small summary + path.
     * External files (Context.getExternalFilesDir) don't need storage
     * permission and are visible to `adb pull`.
     */
    private fun saveUiTree(tree: JSONObject): JSONObject {
        val dir = java.io.File(getExternalFilesDir(null), "captures").apply { mkdirs() }
        val file = java.io.File(dir, "ui_${stamper.format(java.util.Date())}.json")
        file.writeText(tree.toString(2), Charsets.UTF_8)
        android.util.Log.i("Body", "ui_tree saved: ${file.absolutePath}")
        return JSONObject()
            .put("pkg", tree.optString("pkg"))
            .put("class", tree.optString("class"))
            .put("children", tree.optJSONArray("children")?.length() ?: 0)
            .put("file", file.name)
            .put("bytes", file.length())
    }

    /**
     * Save the PNG bytes to disk (skipping the base64 round-trip in the
     * subtitle), and return a small summary + path.
     */
    private fun saveScreenshot(shot: JSONObject): JSONObject {
        val b64 = shot.optString("png_base64", "")
        val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        val dir = java.io.File(getExternalFilesDir(null), "captures").apply { mkdirs() }
        val file = java.io.File(dir, "screen_${stamper.format(java.util.Date())}.png")
        file.writeBytes(bytes)
        android.util.Log.i("Body", "screenshot saved: ${file.absolutePath}")
        return JSONObject()
            .put("width", shot.optInt("width"))
            .put("height", shot.optInt("height"))
            .put("bytes", bytes.size)
            .put("file", file.name)
    }

    private fun startWsServer() {
        try {
            val srv = BodyWsServer(
                port = WS_PORT,
                registry = callExtensions,
                onExtensionEvent = { kind, data -> onExtensionEvent(kind, data) },
                onCommand = { method, params ->
                    // driving = false: this socket is the debug / viewer path,
                    // and it polls the tree and the screen on a timer. See
                    // [CommandDispatcher.dispatch].
                    dispatcher.dispatch(method, params, driving = false)
                },
            )
            srv.start()
            wsServer = srv
            android.util.Log.i("Body", "ws server started on 0.0.0.0:$WS_PORT")
        } catch (t: Throwable) {
            android.util.Log.e("Body", "ws server start failed", t)
        }
    }

    /**
     * An event pushed up by the currently-registered call extension. Three
     * kinds are meaningful; anything else is passed through in case a client
     * on the hub wants it.
     *
     * - `call.state` — informational, forwarded so the brain knows something
     *   is happening but not asked to write minutes. Ringing/connected/hangup.
     *
     * - `call.subtitle` — what the caller (or the human) just said, and the
     *   translation of it. Drives the ball's live row; the brain does not
     *   normally read these frame-by-frame, but they broadcast anyway so a
     *   viewer client can display them.
     *
     * - `call.summary` — the call is over and there is a transcript. Same rule
     *   as the meeting recorder: no new event kind, we send it back as
     *   `asr.final` with a prompt-shaped text that asks the brain to render an
     *   HTML page. See CLAUDE.md's meeting-recorder section for why.
     *
     * Runs on the WS callback thread; only [ui.post] the parts that touch
     * views.
     */
    private fun onExtensionEvent(kind: String, data: JSONObject) {
        val client = hubClient
        val server = wsServer
        when (kind) {
            "call.subtitle" -> {
                val src = data.optString("source_text")
                val tx = data.optString("translation_text")
                val isFinal = data.optBoolean("is_final", false)
                val text = when {
                    src.isNotEmpty() && tx.isNotEmpty() -> "$src\n→ $tx"
                    tx.isNotEmpty() -> tx
                    else -> src
                }
                if (text.isNotEmpty()) {
                    ui.post {
                        window.setSubtitle(
                            text,
                            if (isFinal) FloatingWindowUi.SubtitleKind.FINAL
                            else FloatingWindowUi.SubtitleKind.PARTIAL,
                        )
                    }
                }
            }

            "call.summary" -> {
                val summaryQuery = callSummaryQuery(data)
                // Display-only, same as the meeting twin above: the brain gets
                // `callSummaryQuery`, the card gets this.
                net.kuafuai.andee.ui.ChatHistory.addUser(
                    AppLocale.str(
                        this,
                        R.string.svc_call_ended_line,
                        data.optInt("chars", data.optString("transcript").length),
                    )
                )
                val event = JSONObject()
                    .put("text", summaryQuery)
                    .put("ts", System.currentTimeMillis())
                Thread {
                    runCatching { client?.sendEvent("asr.final", event) }
                    runCatching { server?.broadcastEvent("asr.final", event) }
                }.start()
                if (client != null || (server?.clientCount() ?: 0) > 0) {
                    ui.post {
                        acceptBrainOutput = true
                        dispatcher.beginTask()
                        window.setState(FloatingWindowUi.State.THINKING)
                    }
                }
            }

            else -> {

            }
        }
    }

    /**
     * The prompt sent to the brain when a call ends and the device has no idea
     * what the brain wanted to do about it — same shape as [meetingMinutesQuery],
     * same reason: it lands in the brain's context as if the user had said it.
     *
     * The prose is English like the rest of the model-facing set. The `scene`
     * default on the line below is [meetingMinutesQuery]'s title case again: the
     * value reaches the page the summary is laid out under, so it comes from
     * `R.string.call_default_scene` through `AppLocale` rather than a literal.
     */
    private fun callSummaryQuery(p: JSONObject): String {
        val scene = p.optString("scene")
            .ifEmpty { AppLocale.str(this, R.string.call_default_scene) }
        val minutes = (p.optLong("duration_ms") / 60_000).coerceAtLeast(1)
        val transcript = p.optString("transcript")
        val reason = when (p.optString("reason")) {
            "user" -> "(I hung up from this end)"
            "brain" -> "(the AI hung up)"
            "error" -> "(the call dropped unexpectedly)"
            else -> ""
        }
        return buildString {
            append("$reason The $scene just ended, and the full record of the conversation is below.\n")
            append("Turn it into a summary of the key points, then use the show_html tool to lay the summary out as an HTML page and show it to me —")
            append(" a text-only reply is something I cannot see.\n")
            append("The summary should contain: the subject of the call, the key points, the conclusions reached, and action items.")
            append("The conversation comes out of speech recognition, so it will have wrong characters and missing punctuation; follow the context.\n\n")
            append("Scene: $scene\nDuration: about $minutes minutes\n\nConversation:\n")
            append(transcript)
        }
    }

    private fun stopWsServer() {
        val srv = wsServer ?: return
        wsServer = null
        // stop() blocks up to timeout; run off the main thread.
        Thread { runCatching { srv.stop(500) } }.start()
    }

    /**
     * Bring up whichever brain the user configured — exactly one of them.
     *
     * `brain = local` runs the agent loop on this device ([LocalBrain]) and
     * does **not** dial the hub; anything else is the hub, which is what this
     * device has always done. They are mutually exclusive on purpose: two
     * brains answering the same `asr.final` is a bug the user hears, as two
     * voices talking over each other.
     *
     * A local brain with no API key still starts. It says so on the first
     * thing the user asks, which is a better place to find out than a silent
     * fall back to a hub they thought they had switched off.
     *
     * @param announce whether the missing-configuration state also gets a card
     *   of its own ([reportNoBrain]). `true` everywhere except the service's own
     *   start, where [runStartupSelfCheck] is already about to put up one card
     *   covering this and everything else — see that method. The scrollback row
     *   is written either way: it is a log of what happened, not an
     *   interruption.
     */
    private fun startBrain(announce: Boolean = true) {
        val bc = net.kuafuai.andee.config.VoiceConfig.brainConfig(this)
        // One line that answers "is my override in effect?" without reading
        // shared_prefs over adb. The api key is overridable now, and a setting
        // whose effect cannot be seen is indistinguishable from one that did not
        // take. The speaker prints what it is so the imp's voice change is
        // verifiable the same way — it follows the ball's look, not a setting.
        android.util.Log.i(
            "Body",
            "voice: 火山 key from " +
                if (net.kuafuai.andee.config.VoiceConfig.usingOwnCredentials(this)) {
                    "settings"
                } else {
                    "factory"
                } + ", speaker=" +
                net.kuafuai.andee.config.VoiceConfig.load(this).ttsSpeaker,
        )
        if (!bc.isLocal) {
            // A device with nowhere to send a sentence is silent, and silence
            // with no explanation reads as broken rather than as unconfigured.
            // This is also exactly the state a factory reset leaves behind, so
            // it is worth the one card. Both brains have the same failure mode
            // and both get told about it here, before anything starts.
            if (net.kuafuai.andee.config.VoiceConfig.hubConfig(this).url.isEmpty()) {
                reportNoBrain(AppLocale.str(this, R.string.svc_no_brain_hub), announce)
            }
            startHubClient()
            // Not just for the local brain. Pending todos lose their
            // AlarmManager alarms when the app is replaced or the device
            // reboots, and re-arming them has nothing to do with which brain is
            // configured. With no local brain the catch-up can only notify —
            // see [catchUpPromises] — but that is still better than an alarm
            // that silently never comes back.
            catchUpPromises()
            return
        }
        android.util.Log.i(
            "Body",
            "brain=local — skipping outbound client (model=${bc.model} @ ${bc.baseUrl}, " +
                "thinking=${bc.thinking}/${bc.reasoningEffort}, key=${if (bc.apiKey.isEmpty()) "MISSING" else "set"})",
        )
        // Same silence, same explanation as the hub branch above: a local brain
        // with no key answers nothing at all, and "nothing at all" is the one
        // failure a user cannot diagnose from the outside.
        if (bc.apiKey.isEmpty()) {
            reportNoBrain(AppLocale.str(this, R.string.svc_no_brain_local), announce)
        }
        localBrain = net.kuafuai.andee.brain.LocalBrain(
            cfg = bc,
            appContext = this,
            dispatcher = dispatcher,
            registry = callExtensions,
            onProgress = ::onBrainProgress,
            onFinal = ::onBrainFinal,
            onTurnEnd = ::onTurnEnd,
            onSweepSpent = { tokens -> net.kuafuai.andee.config.Notebook.noteSweep(this, tokens) },
        )
        // Promises outlive the process: anything that came due while the
        // accessibility service was off (or the tablet was off) is dealt with
        // here, on the first start that can hear about it. This runs in hub
        // mode too — see the early return above.
        catchUpPromises()
    }

    private fun stopBrain() {
        stopHubClient()
        // No brain, no reason for a timer that would wake one.
        net.kuafuai.andee.config.Scheduler.cancelSweep(this)
        val lb = localBrain ?: return
        localBrain = null
        lb.stop()
    }

    /**
     * A turn just ended. Push the quiet-hour timer out.
     *
     * Last write wins: every turn re-arms it, so a conversation that keeps
     * going never looks finished to itself. This is the only place the timer
     * is started — see [onSweepTimer] for the other half.
     */
    private fun onTurnEnd() {
        val sweep = net.kuafuai.andee.config.VoiceConfig.sweepConfig(this)
        if (!sweep.enabled) return
        net.kuafuai.andee.config.Scheduler.armSweepIn(this, sweep.quietMinutes * 60_000L)
    }

    /**
     * The timer fired. Has the conversation actually stopped?
     *
     * The timer is a single shot armed at the end of a turn; if the user spoke
     * in the meantime — or the alarm landed late — the honest answer is "not
     * yet", and the remainder is waited out rather than sweeping mid-sentence.
     */
    fun onSweepTimer() {
        val sweep = net.kuafuai.andee.config.VoiceConfig.sweepConfig(this)
        val brain = localBrain
        if (!sweep.enabled || brain == null) {
            android.util.Log.i(
                "Body",
                "sweep timer fired with nothing to do " +
                    "(enabled=${sweep.enabled}, localBrain=${brain != null})",
            )
            return
        }
        val quietMs = sweep.quietMinutes * 60_000L
        val idle = System.currentTimeMillis() -
            net.kuafuai.andee.config.Notebook.lastActivityAt(this)
        if (idle < quietMs) {
            net.kuafuai.andee.config.Scheduler.armSweepIn(this, quietMs - idle)
            return
        }
        if (!net.kuafuai.andee.config.Notebook.sweepAllowed(this, sweep.dailyCap)) {
            val used = net.kuafuai.andee.config.Notebook.sweepCountToday(this)
            android.util.Log.i("Body", "sweep budget for today is used up ($used/${sweep.dailyCap})")
            return
        }
        brain.runSweep()
    }

    /**
     * A promise came due and the alarm got through — wake the brain up.
     *
     * Called from [net.kuafuai.andee.config.AlarmReceiver], so it may be on the
     * main thread; [net.kuafuai.andee.brain.LocalBrain.wake] only posts, and
     * defers itself if a turn is already running.
     */
    fun wakeForTodo(todo: net.kuafuai.andee.config.Notebook.Todo) {
        val brain = localBrain
        if (brain == null) {
            // Nothing on this device can run a turn (no local brain — there is
            // no hub fallback any more). Tell the user rather than swallowing
            // it: a promise that evaporates silently is the failure this
            // exists to prevent.
            android.util.Log.i("Body", "todo due, but no local brain here — notifying instead")
            net.kuafuai.andee.config.Scheduler.notifyMissed(this, todo)
            net.kuafuai.andee.config.Scheduler.markCaughtUp(this, todo.id)
            return
        }
        // An alarm is the one turn nobody asked for, so it is the one that can
        // start while the user is mid-sentence in the field. Take the field down
        // first: otherwise the brain's `type_text` borrows the keyboard out from
        // under a sentence being typed by hand, and the app the brain is driving
        // gets a keyboard raised over it instead.
        closeTyping()
        // Prompt, not display: [notifyPageClosed] on why the model-facing set
        // is English and what that costs.
        brain.wake(
            "(time's up · this is a todo you set yourself, id=${todo.id}) ${todo.what}\n" +
                "Do it now. If it cannot be done, still say honestly why and give them a next step; " +
                "when it is done (or said), close it with todos(action=done, id=${todo.id}).",
        )
    }

    /**
     * Whatever came due while nothing was listening.
     *
     * Delivered as a **silent note**, not a turn: the user did not just say
     * anything, so a voice coming out of the tablet to announce a missed
     * reminder would be startling and badly timed. The note rides into the
     * context, and the next time they talk the brain explains and either does
     * it or re-arranges it. [net.kuafuai.andee.config.Scheduler.catchUp] also
     * re-arms everything still in the future.
     *
     * The note is context, not chrome, so it is English with the rest of the
     * model-facing set — see [notifyPageClosed].
     */
    private fun catchUpPromises() {
        val missed = net.kuafuai.andee.config.Scheduler.catchUp(this)
        val brain = localBrain
        for (todo in missed) {
            if (brain != null) {
                brain.note(
                    "(missed) you had promised: ${todo.what}" +
                        " (set for ${net.kuafuai.andee.config.Scheduler.formatLocal(todo.at)}) — " +
                        "I was not running when that time came, so it was never done. The next time you talk to the user, explain this first, " +
                        "then either do it or arrange a new time with them.",
                )
            } else {
                // No brain on this device right now (there is no hub fallback
                // any more). The promise still has to reach the user, so it
                // goes out as a notification rather than evaporating.
                net.kuafuai.andee.config.Scheduler.notifyMissed(this, todo)
            }
            net.kuafuai.andee.config.Scheduler.markCaughtUp(this, todo.id)
        }
    }

    /**
     * Bring up the outbound hub connection if [VoiceConfig.hubConfig] has a
     * URL configured. No-op when empty — running body_ws server-only is a
     * valid mode (local viewer / same-LAN brain).
     *
     * Only reached from [startBrain] and from the two places that bounce the
     * hub connection in place (extension registry changes, settings dismiss).
     */
    private fun startHubClient() {
        val hc = net.kuafuai.andee.config.VoiceConfig.hubConfig(this)
        if (hc.url.isBlank()) {
            android.util.Log.i("Body", "hub_url not set — skipping outbound client")
            return
        }
        val client = net.kuafuai.andee.net.BodyWsClient(
            url = hc.url,
            deviceId = hc.deviceId,
            deviceName = hc.deviceName,
            dispatcher = dispatcher,
            registry = callExtensions,
            onProgress = ::onBrainProgress,
            onFinal = ::onBrainFinal,
        )
        client.start()
        hubClient = client
    }

    /**
     * Narration from whichever brain is answering.
     *
     * Extracted from [startHubClient] rather than copied into [LocalBrain]:
     * this and [onBrainFinal] are where the two TTS mute rules, the
     * [acceptBrainOutput] gate and the ChatHistory bookkeeping live, and two
     * brains drifting apart on any of those is a bug the user sees before we do.
     *
     * Called from a socket thread (hub) or the `LocalBrain-loop` thread
     * (local); everything it touches posts to the main Handler itself.
     */
    private fun onBrainProgress(text: String) {
        // Task start. The brain only ever speaks mid-task, so every frame
        // of narration re-marks it: thinking looks like nothing at all on
        // the wire (no tool calls), and that's exactly when the "being
        // driven" light has to stay on.
        if (acceptBrainOutput) {
            dispatcher.beginTask()
            window.setState(FloatingWindowUi.State.THINKING)
            window.setSubtitle(
                // Drafts of a settling reply may already carry the end marker;
                // it must not flash on the live line either.
                stripMarkdown(END_MARKER.replace(text, "")),
                FloatingWindowUi.SubtitleKind.PARTIAL,
            )
        }
    }

    /** The finished answer. See [onBrainProgress] for why this is not inline. */
    private fun onBrainFinal(text: String) {
        if (acceptBrainOutput) {
            // The brain's own verdict on whether this conversation is still
            // open. Written before the speak below because [followUpWindow]
            // reads it seconds later, off the TTS drain — see [expectFollowUp].
            expectFollowUp = !END_MARKER.containsMatchIn(text)
            val plain = stripMarkdown(END_MARKER.replace(text, ""))
            // The finished answer is the end of the task as far as the
            // device is concerned: nothing further will move the app.
            dispatcher.endTask()
            // Whatever draft `onBrainProgress` left on the live line is now
            // finished work — and nothing else was going to take it
            // down, so the last mid-task sentence sat at the foot of
            // the scrollback forever, under the answer that replaced it.
            window.clearSubtitle()
            // Only the final answer goes in the scrollback. `onBrainProgress`
            // fires many times per task with successive drafts of the
            // same sentence — logging those would bury the answer under
            // its own rough cuts.
            net.kuafuai.andee.ui.ChatHistory.addAssistant(plain)
            // Kept for one reason only: if speaking this fails and the card is
            // folded, the row above is `GONE` and this string is the last copy
            // of the answer on the device. See [showAnswerAsCard].
            lastAnswer = plain
            // The row above is the answer. speak() deliberately puts
            // nothing on the live line — the ball's speaking face says
            // it is being read out, and the words are already up there.
            //
            // Silent through a meeting, for two reasons and either
            // alone would be enough. Our speaker is an inch from the
            // microphone, so anything said here lands in the
            // transcript. And [TtsController] holds its own audio
            // focus gate: a second AudioFocusRequest from this same
            // process displaces the first (the focus stack is per
            // request, not per app), so speaking would fire the
            // recorder's focus-loss callback and end the meeting —
            // which is exactly what "好的，开始录音了" used to do, one
            // second in.
            //
            // Silent through a phone / VoIP call for the mirror-image
            // reason: the caller app owns the audio focus, and our
            // AudioFocusRequest from TTS would displace theirs the
            // same way it displaces the meeting recorder's. The
            // scrollback row above is already the answer; the user
            // reads it after they hang up.
            if (!meeting.isActive() && !net.kuafuai.andee.device.CallState.isActive) tts.speak(plain)
        }
    }

    /**
     * A card the brain just put up — the `ui.ask` question, the `ui.alert`
     * notice — read aloud, and the whole of the `tts.speak` tool, which is the
     * same request without a card. The card is for a user who may be looking
     * anywhere but the screen; a question nobody hears waits in silence for
     * its timeout.
     *
     * Deliberately the same gates as the `tts.speak` in [onBrainFinal], not a
     * fresh set: speaking through a meeting would both land in the transcript
     * and end the meeting via the audio-focus collision, speaking through a
     * call displaces the caller's focus, and speaking after the user hit stop
     * is a stopped turn still talking. The dispatcher cannot know any of that,
     * which is why it hands the text over ([CommandDispatcher.sayAloud])
     * instead of holding a TTS handle.
     *
     * Returns whether the words went to TTS. `ui.ask` / `ui.alert` drop the
     * answer — their card is the deliverable and it is up either way — but
     * `tts.speak` IS the utterance, so a refusal has to travel back as a
     * refusal. Its caller is usually a person recording a demo, and "nothing
     * happened and nothing said why" is the worst answer to give them.
     */
    private fun speakCardText(text: String): Boolean {
        if (!acceptBrainOutput) return false
        if (meeting.isActive() || net.kuafuai.andee.device.CallState.isActive) return false
        // A card is its own answer channel — the ask's buttons, the alert's
        // 知道了. The follow-up mic must not open over it: a spoken answer
        // would arrive as a *new turn* queued behind the very tool call the
        // card is blocking, and a mic held open while the user decides
        // overhears the room. The user who does want to talk has the wake
        // word and the ball.
        //
        // True for `tts.speak` as well, and for a different version of the same
        // reason: a rehearsal line is not a question the assistant asked, so
        // the mic must not come up hunting for an answer to it.
        expectFollowUp = false
        tts.speak(stripMarkdown(text))
        return true
    }

    private fun stopHubClient() {
        val c = hubClient ?: return
        hubClient = null
        Thread { runCatching { c.stop() } }.start()
    }

    companion object {
        private const val WS_PORT = 9008

        /**
         * The "conversation over" marker a final answer may end with — the
         * brain's judgment that this reply settles the matter and asks the
         * user nothing (see §10 of `LocalPrompt.TEXT`). Stripped before the
         * answer is spoken or shown, so the user never hears it; its presence
         * is what flips [expectFollowUp]. Case-insensitive so a model that
         * writes `[end]` still gets the marker stripped instead of having it
         * read aloud.
         */
        private val END_MARKER = Regex("""(?i)\[END]""")
        private val instanceRef = AtomicReference<ScreenBodyService?>()
        fun get(): ScreenBodyService? = instanceRef.get()

        /** Where [runStartupSelfCheck] remembers what it already said. */
        private const val SELF_CHECK_PREFS = "self_check"
        private const val SELF_CHECK_SIGNATURE = "last_signature"
        private const val SELF_CHECK_SEEN_AT = "last_seen_at"

        /**
         * How long an unchanged set of problems stays quiet after being shown.
         *
         * Six hours, and the unit is deliberately coarse: the point is to catch
         * the case where the card has *not* been acted on and the device is
         * being restarted repeatedly (a reinstall loop, a tablet that reboots
         * on the dog) without hiding a real problem for a whole day. Anything
         * that changes the set — a new failure, or one fixed — gets through
         * immediately, because the signature is compared, not the clock.
         */
        private const val REPEAT_AFTER_MS = 6 * 60 * 60 * 1000L
    }

    /**
     * The notification relay is a separate service with its own lifecycle and
     * no wiring of its own; this is how it reaches the task light. Named for
     * the caller rather than the mechanic — anything else that ever needs
     * "start the ball working" from outside the socket path goes through here.
     */
    fun dispatcherForNotification(): CommandDispatcher? =
        if (::dispatcher.isInitialized) dispatcher else null

    /**
     * The ball's window, for CardUi — signboards anchor to where the ball
     * actually is, and the relay (a separate service) has no other way to
     * reach it.
     */
    fun ballWindow(): FloatingWindowUi? =
        if (::window.isInitialized) window else null

    /**
     * Put the three overlays on screen if they are not already there, and say
     * whether the ball made it. Main thread only.
     *
     * This exists because [onServiceConnected] **throws its answer away**:
     * `window.show()` returns false when `WindowManager.addView` is rejected
     * for want of `SYSTEM_ALERT_WINDOW`, and the service then runs perfectly
     * with nothing on screen. Nothing recovers from that on its own, and
     * nothing can even complain about it — [runStartupSelfCheck] bails when the
     * ball is not shown, because the card it would put up needs the very
     * permission that is missing. So the app is a live process with no visible
     * surface and no voice, which is what "点不开了" looks like from outside.
     *
     * The state is reachable without anybody doing anything wrong: a package
     * rename or a fresh install loses the appop, the user grants it *after* the
     * accessibility service has already connected, and the grant alone does not
     * re-run [onServiceConnected]. Toggling accessibility off and on was the
     * only cure, which is knowledge no user has.
     *
     * All three windows are retried, not just the ball: the ripple and the tap
     * marker were added in the same frame and refused for the same reason, so a
     * recovery that restored only the ball would come back without the task
     * light. Each `show()` is idempotent (it returns true early when its view
     * already exists), so this is safe to call on a healthy service.
     *
     * The unfold is the same `setCompact(false)` birth does, for the same
     * reason — the card is what carries the scrollback and the control bar, and
     * a recovery that surfaced only the corner ball would be a second, worse
     * way to be born.
     */
    fun ensureOverlays(): Boolean {
        if (!::window.isInitialized) return false
        if (window.isShown()) return true
        if (!window.show()) return false
        window.setCompact(false)
        if (::ripple.isInitialized) ripple.show()
        if (::marker.isInitialized) marker.show()
        return true
    }

    /**
     * Open the settings sheet for someone outside this class — presently the
     * self-check page, whose 打开设置 row is a door to it.
     *
     * Not exposed as an Intent: the sheet is built from this service's own
     * state (which brain is configured, what the hub URL is) and a second
     * instance built from an Activity context would be a *different* sheet
     * fighting this one for the keyboard. Posted to the main thread because
     * `SettingsUi.show` adds a window.
     *
     * A null service means no sheet from *here*, which is why
     * [net.kuafuai.andee.ui.SelfCheckActivity] raises its own on the path where
     * this class does not exist — see its `openOwnSettings`. Guarded on
     * [window] rather than on any "am I alive" flag: a sheet over a service
     * that never got its overlay attached has nothing to sit on, and the add
     * would throw.
     */
    fun openSettingsForUser() {
        ui.post { if (::window.isInitialized) openSettings() }
    }
}
