package net.kuafuai.andee.desktop

import net.kuafuai.andee.brain.LocalBrain
import net.kuafuai.andee.config.BrainConfig
import net.kuafuai.andee.util.Log
import org.json.JSONObject

/**
 * The running assistant: one brain, the providers it can use, and the
 * settings it was built from. Rebuilt only when *who answers* changes —
 * a different key or endpoint — never for how it is asked (model, thinking),
 * which [LocalBrain.retune] applies with the conversation intact.
 */
object Assistant {

    private const val TAG = "Assistant"

    /** Progress lines and final answers, for the window. Called off the UI thread. */
    @Volatile var onProgress: (String) -> Unit = {}
    @Volatile var onFinal: (String) -> Unit = {}
    @Volatile var onBusy: (Boolean) -> Unit = {}
    @Volatile var onSceneChanged: (String?) -> Unit = {}

    /**
     * Answers are read aloud however the question came in — the phone does the same, and a voice that
     * goes quiet when you start typing reads as broken rather than polite. Quiet is the user's call:
     * the speaker button in the chat header, remembered across launches.
     */
    @Volatile var muted: Boolean = Settings.get("mute") == "1"
        private set
    @Volatile var onMuteChanged: () -> Unit = {}

    fun setMuted(m: Boolean) {
        muted = m
        Settings.save(mapOf("mute" to if (m) "1" else "0"))
        // Muting means now, not after the sentence it was already saying.
        if (m) net.kuafuai.andee.desktop.voice.MacVoice.stopSpeaking()
        onMuteChanged()
    }

    /**
     * Shows a command for approval and returns the button pressed. Set by the
     * window; until it is, everything that needs asking is declined.
     */
    @Volatile var askApproval: (net.kuafuai.andee.desktop.shell.ShellTools.Approval) ->
        net.kuafuai.andee.desktop.shell.ShellTools.Answer = { net.kuafuai.andee.desktop.shell.ShellTools.Answer.DENY }

    /** The terminal hand; one per app so running jobs survive a brain rebuild. */
    val shell: net.kuafuai.andee.desktop.shell.ShellTools by lazy {
        net.kuafuai.andee.desktop.shell.ShellTools(
            ask = { askApproval(it) },
            secrets = { secrets() },
            record = { line -> TimelineEvents.body(TimelineEvents.Body.SHELL, line) },
        )
    }

    /** Hides Andee's own windows for the instant of a click; set by the window. */
    @Volatile var hideWindows: (Boolean) -> Unit = {}

    /** Gets the chat window out of the way before a screen tool runs; set by the window. */
    @Volatile var yieldScreen: () -> Unit = {}

    /** The screen hand: accessibility tree, clicks, keys and screenshots. */
    val screen: net.kuafuai.andee.desktop.mac.MacScreen by lazy {
        net.kuafuai.andee.desktop.mac.MacScreen({ hide -> hideWindows(hide) }, { yieldScreen() })
    }

    /** What is installed and what each thing is for: the shelf the brain checks before doing a job by hand. */
    val gear: net.kuafuai.andee.desktop.mac.MacGear by lazy { net.kuafuai.andee.desktop.mac.MacGear() }

    /** Apps, calendar, reminders, contacts, volume, battery and the vault. */
    val device: net.kuafuai.andee.desktop.mac.MacDevice by lazy {
        net.kuafuai.andee.desktop.mac.MacVault.entries()
        net.kuafuai.andee.desktop.mac.MacDevice()
    }

    /** Values that must never reach the model or a log: Andee's own keys, then the vault. */
    private fun secrets(): List<Pair<String, String>> = buildList {
        Settings.get("llm_api_key").takeIf { it.length >= 8 }?.let { add("Andee's model key" to it) }
        Settings.get("api_key").takeIf { it.length >= 8 }?.let { add("Andee's voice key" to it) }
        codeFlyingKey().takeIf { it.length >= 8 }?.let { add("Andee's CodeFlying key" to it) }
        addAll(VaultSecrets.values())
    }

    private var brain: LocalBrain? = null
    private var builtFor: BrainConfig? = null

    /** Where CodeFlying is: the build's address (null only if the build was packaged without one). */
    fun codeFlyingEndpoint() =
        net.kuafuai.andee.config.CodeFlyingEndpoint.parse(CodeFlyingBuild.domain, CodeFlyingBuild.ssl)

    /** The key in effect: one typed in settings, else the build's. */
    fun codeFlyingKey(): String = Settings.get("codeflying_key").ifEmpty { CodeFlyingBuild.key }

    /**
     * The backend in force. Unset is CodeFlying, key or not — the phone's rule.
     */
    fun brainMode(): String = when (Settings.get("brain").trim().lowercase()) {
        BrainConfig.BRAIN_CODEFLYING -> BrainConfig.BRAIN_CODEFLYING
        BrainConfig.BRAIN_HUB -> BrainConfig.BRAIN_HUB
        BrainConfig.BRAIN_LOCAL -> BrainConfig.BRAIN_LOCAL
        "" -> BrainConfig.BRAIN_CODEFLYING
        else -> BrainConfig.BRAIN_LOCAL
    }

    fun config(): BrainConfig {
        val zh = Strings.lang() == "zh"
        val cf = codeFlyingEndpoint()
        val thinking = Settings.get("llm_thinking") != "disabled"
        val effort = Settings.get("llm_reasoning_effort").ifEmpty { "high" }
        if (brainMode() == BrainConfig.BRAIN_CODEFLYING && cf != null) {
            return BrainConfig(
                mode = BrainConfig.BRAIN_CODEFLYING,
                apiKey = codeFlyingKey(),
                baseUrl = cf.llmBaseUrl,
                model = Settings.get("llm_model").ifEmpty { "deepseek-flash" },
                thinking = thinking,
                reasoningEffort = effort,
            )
        }
        return BrainConfig(
            mode = BrainConfig.BRAIN_LOCAL,
            apiKey = Settings.get("llm_api_key"),
            baseUrl = Settings.get("llm_base_url").trimEnd('/').ifEmpty {
                if (zh) "https://api.deepseek.com" else "https://api.openai.com/v1"
            },
            model = Settings.get("llm_model").ifEmpty { if (zh) "deepseek-flash" else "gpt-4o-mini" },
            // Unset reads as on, as on the phone (its build default is on); only an explicit "disabled" turns it off.
            thinking = thinking,
            reasoningEffort = effort,
        )
    }

    private fun providers(): List<DesktopBrainHost.ToolProvider> {
        val notes = NotebookTools { name ->
            onSceneChanged(name)
            // Entering and leaving a scene is a row in the timeline, as on the phone.
            TimelineEvents.scene(name?.let { DesktopNotebook.book.scene(it)?.title ?: it })
        }
        return listOf(
            UiTools(),
            MeetingTools(),
            object : DesktopBrainHost.ToolProvider {
                override fun handles(method: String) = notes.handles(method)
                override fun dispatch(method: String, p: JSONObject, callId: String, silent: Boolean, untrusted: Boolean) =
                    notes.dispatchCall(method, p, callId, silent, untrusted)
            },
            shell,
            screen,
            device,
            gear,
            peerTools,
        )
    }

    /**
     * `list_devices` / `ask_device`. Listed only while some device is paired: a tool that can only ever answer
     * "nothing is paired" is a promise the model would make to every user who never set this up.
     */
    private val peerTools = object : DesktopBrainHost.ToolProvider {
        override fun handles(method: String) = method.startsWith("peer.")
        override fun dispatch(method: String, p: JSONObject, callId: String, silent: Boolean, untrusted: Boolean): Any =
            Peers.tasks.dispatch(method, p)
        override fun extraTools() =
            if (Peers.store.all().isNotEmpty()) net.kuafuai.andee.peer.PeerTasks.TOOLS else org.json.JSONArray()
        override fun methodOfExtra(toolName: String) = net.kuafuai.andee.peer.PeerTasks.methodOf(toolName)
    }

    /**
     * The other half of [peerTools]: requests from friends, and reports coming back.
     *
     * One of the user's own devices is the user: its request runs in this conversation, like a todo. A friend is
     * somebody else: the user is asked first (unless they chose "always" for that friend), and the work runs in a
     * brain of its own over [net.kuafuai.andee.brain.GuestHost] — no memory, no history, no vault, no terminal —
     * which is dropped when it answers, so one friend's request never sees another's.
     */
    private val peerBrain = object : net.kuafuai.andee.peer.PeerTasks.Brain {
        override fun run(
            from: net.kuafuai.andee.peer.PeerProtocol.Peer, trust: net.kuafuai.andee.peer.PeerStore.Trust, text: String,
            reply: (String?) -> Unit, decline: (String) -> Unit,
        ): Boolean {
            if (config().apiKey.isEmpty()) return false
            val zh = Strings.lang() == "zh"
            if (trust == net.kuafuai.andee.peer.PeerStore.Trust.OWN) {
                // In the scrollback as the user's words, because they are — said on another device. The tag is
                // what tells the person in front of the Mac why Andee just started working on its own.
                Scrollback.add(Scrollback.Role.USER, (if (zh) "（${from.name} 交来的任务）" else "(task from ${from.name}) ") + text)
                whenQuiet("peer-task") {
                    if (brain?.isBusy() != true) busy(true)
                    ensure().peerTask(net.kuafuai.andee.peer.PeerTasks.ownTaskText(from, Peers.tasks.platformOf(from.id), text), reply)
                }
                return true
            }
            Scrollback.add(Scrollback.Role.USER, (if (zh) "（朋友 ${from.name} 请求）" else "(${from.name}, a friend, asks) ") + text)
            whenQuiet("friend-ask") {
                if (!Peers.store.autoAccept(from.id)) {
                    val yes = if (zh) "允许" else "Allow"
                    val always = if (zh) "总是允许" else "Always allow"
                    val no = if (zh) "拒绝" else "Decline"
                    val q = (if (zh) "朋友 ${from.name} 的 Andee 想请我做：" else "${from.name}, a friend's Andee, asks me to: ") + text.take(300)
                    // The card sits beside the ball, which may be on a screen the user is not looking at — and
                    // nobody here asked for anything, so nothing else says a question is waiting. A sound and a
                    // wave are the knock on the door. (Measured: the first friend request timed out unseen.)
                    runCatching { ProcessBuilder("afplay", "/System/Library/Sounds/Glass.aiff").start() }
                    MacBall.onAnswer("[wave]")
                    val a = AskGate.ask(q, listOf(yes, always, no), FRIEND_ASK_MS)
                    when (a.button) {
                        always -> Peers.setAutoAccept(from.id, true)
                        yes -> Unit
                        else -> {
                            Scrollback.add(Scrollback.Role.ASSISTANT, if (zh) "没有替 ${from.name} 做。" else "Did not do it for ${from.name}.")
                            return@whenQuiet decline(
                                if (a.button == no) "Its owner declined this request." else "Its owner did not answer, so it was not done.",
                            )
                        }
                    }
                }
                runAsGuest(from, text, reply)
            }
            return true
        }

        override fun answered(
            from: net.kuafuai.andee.peer.PeerProtocol.Peer, trust: net.kuafuai.andee.peer.PeerStore.Trust,
            task: String, report: String, done: Boolean,
        ) {
            if (config().apiKey.isEmpty()) {
                Scrollback.add(Scrollback.Role.ASSISTANT, report)
                return
            }
            whenQuiet("peer-report") {
                if (brain?.isBusy() != true) busy(true)
                // A friend's report is somebody else's words, and is run as such: no vault, no commands from it.
                ensure().wake(
                    net.kuafuai.andee.peer.PeerTasks.answerText(from, trust, task, report, done),
                    untrusted = trust != net.kuafuai.andee.peer.PeerStore.Trust.OWN,
                )
            }
        }
    }

    /** The friend's request running now, if any. One at a time: two brains driving one screen is chaos. */
    @Volatile private var guest: LocalBrain? = null

    private fun runAsGuest(from: net.kuafuai.andee.peer.PeerProtocol.Peer, text: String, reply: (String?) -> Unit) {
        // The owner's own turn goes first, and so does a friend's request already running.
        while (brain?.isBusy() == true || guest != null) Thread.sleep(500)
        val zh = Strings.lang() == "zh"
        val g = LocalBrain(
            cfg = config(),
            host = net.kuafuai.andee.brain.GuestHost(DesktopBrainHost(providers(), MacPrompt.Capabilities(shell = false, screen = true, voice = false))),
            onProgress = { BallCaption.push(it); onProgress(it) },
            onFinal = { answer ->
                val clean = net.kuafuai.andee.brain.SpeechMood.parse(answer).text.replace("[END]", "").trim()
                Scrollback.add(Scrollback.Role.ASSISTANT, (if (zh) "（回复 ${from.name}）" else "(to ${from.name}) ") + clean)
                BallCaption.clear()
            },
        )
        guest = g
        busy(true)
        g.peerTask(net.kuafuai.andee.peer.PeerTasks.friendTaskText(from, text)) { answer ->
            reply(answer)
            guest = null
            busy(false)
            // Not from inside its own loop thread: stop() shuts that thread down.
            Thread { g.stop() }.apply { isDaemon = true }.start()
        }
    }

    /** Long enough to come back to the desk. Nothing else waits on it but the friend, who was told it may take a while. */
    private const val FRIEND_ASK_MS = 300_000L

    @Synchronized
    fun ensure(): LocalBrain {
        val cfg = config()
        brain?.let { b ->
            val old = builtFor
            if (old != null && old.sameBackend(cfg)) {
                if (old != cfg) b.retune(cfg)
                builtFor = cfg
                return b
            }
            b.stop()
        }
        Log.i(TAG, "building the brain (${cfg.mode}, ${cfg.model})")
        val b = LocalBrain(
            cfg = cfg,
            host = DesktopBrainHost(providers(), MacPrompt.Capabilities(shell = true, screen = true, voice = true)),
            onProgress = { BallCaption.push(it); onProgress(it) },
            onFinal = { text ->
                val clean = net.kuafuai.andee.brain.SpeechMood.parse(text).text.replace("[END]", "").trim()
                Scrollback.add(Scrollback.Role.ASSISTANT, clean)
                MacBall.onAnswer(text)
                BallCaption.clear()
                onFinal(clean)
                busy(false)
                if (!muted) net.kuafuai.andee.desktop.voice.MacVoice.speak(text)
            },
            onTurnEnd = { busy(false) },
            onSweepSpent = { Sweep.spent(it) },
        )
        brain = b
        builtFor = cfg
        return b
    }

    /**
     * 新对话. Puts the stretch on screen away as a topic (no model call: the title is the
     * first thing the user said, the text is the verbatim exchange) and gives the brain a
     * clean context. Same fold the quiet-hour review makes, started by the user instead.
     * Does nothing to a conversation too short to be worth keeping; never touches a turn
     * that is still running — that one is stopped first, as ■ would.
     */
    @Synchronized
    fun newConversation() {
        if (brain?.isBusy() == true) stop()
        val open = Scrollback.snapshot().filter { it.topicId == null && it.role != Scrollback.Role.ERROR }
        if (open.count { it.role == Scrollback.Role.USER } >= 1 && open.size >= 2) {
            val first = open.first { it.role == Scrollback.Role.USER }.text.replace('\n', ' ').trim()
            val text = open.joinToString("\n\n") { (if (it.role == Scrollback.Role.USER) "User: " else "Andee: ") + it.text }
            Scrollback.fold(
                title = first.take(28) + if (first.length > 28) "…" else "",
                summary = first.take(120),
                members = open.map { it.id },
                text = text,
            )
        }
        brain?.stop()
        brain = null
        builtFor = null
        progressClear()
    }

    /**
     * 清空聊天记录: the conversation is gone — the rows the user reads **and** the one the model is holding.
     *
     * The settings button used to wipe only the rows (`Scrollback.wipe()`), and the brain kept its in-memory
     * history word for word: the window was empty and the next sentence was still answered from the old chat
     * (reported 2026-10-28; the phone's `clearChatHistory` has always rebuilt the brain for exactly this reason).
     * Unlike [newConversation], nothing is folded away as a topic — the user asked to forget it, not to file it.
     * A summary the review wrote into the history ("the user is waiting for…") goes with it, which is the
     * other half of why this matters.
     */
    @Synchronized
    fun clearConversation() {
        stop()
        Scrollback.wipe()
        Pages.wipe()
        brain?.stop()
        brain = null
        builtFor = null
        progressClear()
    }

    private fun progressClear() { BallCaption.clear(); onProgress("") }

    /** The quiet-hour review (also the sidebar's 整理 button). Silent: no answer, no voice, no task light. */
    fun runSweep() {
        if (config().apiKey.isEmpty()) { Log.i(TAG, "sweep skipped: no API key"); return }
        ensure().runSweep()
    }

    /** A turn started or ended: the window's stop button and the ball's working face. */
    private fun busy(b: Boolean) {
        MacBall.onBusy(b)
        if (b) BallCaption.thinking() else BallCaption.clear()
        onBusy(b)
    }

    /** What the user tapped 继续 on and has not yet said what to do with. Cleared by the next words, or by stop. */
    sealed class Pending {
        data class Edit(val page: Pages.Page) : Pending()
        data class Topic(val topic: Scrollback.Topic) : Pending()
    }
    @Volatile var pending: Pending? = null
        private set
    @Volatile var onPendingChanged: () -> Unit = {}

    fun beginEdit(page: Pages.Page) { pending = Pending.Edit(page); onPendingChanged() }
    fun beginTopic(topic: Scrollback.Topic) { pending = Pending.Topic(topic); onPendingChanged() }
    fun cancelPending() { if (pending != null) { pending = null; onPendingChanged() } }

    fun say(text: String, attachments: List<Attachment> = emptyList()) {
        val t = text.trim()
        // A question card is up and the brain is blocked on it: these words are its answer, not a new
        // turn (which would queue behind the very tool call waiting for them). Attachments cannot be
        // an answer, so a message carrying any is still a message.
        if (attachments.isEmpty() && AskGate.tryAnswer(t)) return
        // The owner speaking outranks a friend's request on the same screen: it is cut off, and told so.
        guest?.cancel()
        val photos = attachments.filterIsInstance<Attachment.Photo>()
        val files = attachments.filterIsInstance<Attachment.FileRef>()
        // Pictures alone are a complete message ("what is this"); so are files.
        if (t.isEmpty() && attachments.isEmpty()) return
        // A 继续 waiting for its words: the scrollback gets what they said, the brain gets that plus
        // what it is about. The artifact is named here, before the brain can call show_html, so the
        // revision is filed under it without the model having to keep an id straight.
        val p = pending
        pending = null
        if (p != null) onPendingChanged()
        // A new request replaces whatever is running: cut the old turn off before anything else, the way
        // ■ does, so it stops talking and stops driving the screen while the new one starts.
        if (brain?.isBusy() == true || net.kuafuai.andee.desktop.voice.MacVoice.isSpeaking()) interruptForNewRequest()
        val zh = Strings.lang() == "zh"
        val row = when (p) {
            is Pending.Edit -> if (zh) "修改「${p.page.title}」：$t" else "Revise “${p.page.title}”: $t"
            is Pending.Topic -> if (zh) "继续「${p.topic.title}」：$t" else "Continue “${p.topic.title}”: $t"
            null -> t
        }
        // The record says what was sent in words, since a picture has no text to show.
        val zh0 = Strings.lang() == "zh"
        val tag = buildList {
            if (photos.isNotEmpty()) add(if (zh0) "[${photos.size} 张图片]" else "[${photos.size} picture${if (photos.size > 1) "s" else ""}]")
            files.forEach { add("[${it.label}]") }
        }.joinToString(" ")
        Scrollback.add(Scrollback.Role.USER, listOf(tag, row).filter { it.isNotEmpty() }.joinToString(" "))
        busy(true)
        // submit() begins the turn, which clears the revision target; so arm after, from the turn's own start.
        val forBrain = when (p) {
            is Pending.Edit -> { Pages.armNextTurn(p.page.groupId); editQuery(p.page, row) }
            is Pending.Topic -> topicQuery(p.topic, row)
            null -> t
        }
        val withFiles = if (files.isEmpty()) forBrain else forBrain + Attachments.fileNote(files)
        ensure().submit(withFiles, photos.map { it.jpegBase64 })
    }

    private fun editQuery(page: Pages.Page, said: String): String {
        val cap = net.kuafuai.andee.ui.ScrollbackLimits.ARTIFACT_SOURCE_MAX
        val html = page.file?.let { runCatching { it.readText() }.getOrNull() }.orEmpty()
        val cut = html.length > cap
        return buildString {
            append(said)
            append("\n\n(I picked this from the Artifacts panel: it is about the page you made earlier titled \"")
            append(page.title)
            append("\". Apply my change above to that page and show the full revised page with show_html,")
            append(" keeping everything I did not ask to change. Its current source follows")
            if (cut) append(" — truncated at $cap characters; rebuild the missing tail in the same style")
            append(".)\n\n<<<CURRENT PAGE SOURCE>>>\n")
            append(if (cut) html.take(cap) else html)
            append("\n<<<END PAGE SOURCE>>>")
        }
    }

    private fun topicQuery(t: Scrollback.Topic, said: String): String {
        val cap = net.kuafuai.andee.ui.ScrollbackLimits.TOPIC_TEXT_MAX
        val text = Scrollback.topicText(t.id)
        return buildString {
            append(said)
            append("\n\n(I picked this from a finished topic in our conversation, topic #").append(t.id)
            append(" \"").append(t.title).append("\", and want to carry on with it. What it came to: ").append(t.summary)
            if (text == null) {
                append("\nIts full text is no longer stored — work from the summary and say so if a detail is missing.)")
            } else {
                val cut = text.length > cap
                append("\nThe conversation as it happened follows")
                if (cut) append(" — cut at $cap characters, the beginning only")
                append(".)\n\n<<<TOPIC CONVERSATION>>>\n").append(if (cut) text.take(cap) else text)
                append("\n<<<END TOPIC CONVERSATION>>>")
            }
        }
    }

    /** A turn is running, or its answer is being read out: what a tap on the ball should cut short. */
    /**
     * The user tapped a bubble's button (or the body of one with buttons): that is a turn, at the trust of
     * the turn that wrote the bubble — a 「好」 under a bubble a notification's turn raised must not come
     * back as the user's own request (the phone's `answerNudge`, and `Todo.untrusted` for the alarm clock).
     */
    fun answerNudge(n: net.kuafuai.andee.brain.Nudge, button: String?) {
        val text = net.kuafuai.andee.brain.NudgeBook.answerText(n, button)
        if (n.untrusted) {
            if (brain?.isBusy() != true) busy(true)
            ensure().wake(text, untrusted = true)
        } else {
            // A tap is the user asking, like [say]: it replaces whatever is running.
            guest?.cancel()
            if (brain?.isBusy() == true || net.kuafuai.andee.desktop.voice.MacVoice.isSpeaking()) interruptForNewRequest()
            busy(true)
            ensure().submit(text)
        }
    }

    /** Bring the window up on a screen (`wiki:<id>`, …). Set by the window once it exists; any thread. */
    @Volatile var openScreen: (String) -> Unit = {}

    /** The brain as built, if it is; never builds one. For the heartbeat, which must not wake a brain nobody is using. */
    fun built(): LocalBrain? = brain

    fun working(): Boolean = brain?.isBusy() == true || net.kuafuai.andee.desktop.voice.MacVoice.isSpeaking()

    /**
     * A new request replaces the running one: the same cut as ■ (the turn, the commands it started, and
     * the voice still reading the old answer). [say] has already taken the 继续 target before this, so the
     * stop clearing [pending] loses nothing.
     */
    private fun interruptForNewRequest() = stop()

    /** The user's stop: the turn ends, and every command it started ends with it. */
    fun stop() {
        cancelPending()
        brain?.cancel()
        guest?.cancel()
        shell.stopAll()
        net.kuafuai.andee.desktop.voice.MacVoice.stopSpeaking()
        busy(false)
    }

    /** Notifications the triage judged worth acting on, or telling. */
    private val notifyHost = object : net.kuafuai.andee.desktop.mac.MacNotifications.Host {
        override fun brainConfig() = config()
        override fun langTag() = Strings.lang()
        override fun scene() = Settings.get("scene").ifEmpty { null }?.let { DesktopNotebook.book.scene(it) }
        override fun busy() = brain?.isBusy() == true
        override fun tell(n: JSONObject, line: String) {
            // No turn and no task light: the sentence is already written, nothing is being driven.
            val clean = net.kuafuai.andee.brain.SpeechMood.parse(line).text.trim()
            Scrollback.add(Scrollback.Role.ASSISTANT, clean)
            onFinal(clean)
            if (!muted) net.kuafuai.andee.desktop.voice.MacVoice.speak(line)
        }
        override fun act(text: String) {
            busy(true)
            ensure().notified(text)
        }
    }

    fun start() {
        Sweep.start()
        Meeting.onUserEnded = { payload ->
            // The user ended the recording themselves, so the brain does not have the
            // transcript — ask it for the minutes on a fresh turn (the phone's asr.final path).
            busy(true)
            ensure().submit(meetingMinutesQuery(payload))
        }
        Meeting.onRecordingChanged = { MacBall.onRecording(it) }
        net.kuafuai.andee.desktop.mac.MacNotifications.start(notifyHost)
        DesktopScheduler.onDue = { todo -> wakeFor(todo) }
        Peers.tasks.brain = peerBrain
        DesktopScheduler.catchUp()
        DesktopScheduler.syncPlan()
        PlanTicks.start()
        VersionCheck.remindAtLaunch()
    }

    /**
     * A todo came due. Deferred rather than interrupting — a wake-up is the one turn nobody asked for, so
     * it must never cut off something the person is in the middle of:
     *  * a turn running (theirs or another wake-up): it queues behind it inside the brain, and an identical
     *    alarm already queued is not queued twice;
     *  * the answer to them still being read aloud: wait for the voice to finish, so two voices never
     *    overlap;
     *  * a question card waiting on them, or they are typing: wait until that is over, because the wake-up
     *    would otherwise start driving the screen or speaking over it.
     * The wait is bounded, so a card nobody answers cannot hold an alarm back forever — it fires anyway
     * after [WAKE_PATIENCE_MS], when the card has timed out on its own.
     */
    private fun wakeFor(todo: net.kuafuai.andee.config.Todo) {
        // A planned bubble: shown by the device, no model involved, nothing to wait for.
        if (todo.nudge.isNotEmpty()) return Nudges.postPlanned(todo)
        wakeBrain(todo)
    }

    private fun wakeBrain(todo: net.kuafuai.andee.config.Todo) = whenQuiet("wake-${todo.id}") {
        // busy() is the window's "working" light: only turn it on when a turn really starts, not when
        // the wake-up is merely parked behind another one.
        if (brain?.isBusy() != true) busy(true)
        ensure().wake(dueText(todo), untrusted = todo.untrusted)
    }

    /**
     * Run [then] once nobody is mid-exchange with the Mac — no answer being read aloud, no question card
     * waiting, no mic open — or after [WAKE_PATIENCE_MS] regardless. On its own thread. Shared by every turn
     * nobody here just asked for: a todo, a task from another device, a report coming back.
     */
    private fun whenQuiet(name: String, then: () -> Unit) {
        Thread {
            val until = System.currentTimeMillis() + WAKE_PATIENCE_MS
            while (System.currentTimeMillis() < until && (net.kuafuai.andee.desktop.voice.MacVoice.isSpeaking() ||
                    AskGate.isWaiting || net.kuafuai.andee.desktop.voice.MacVoice.isListening())) {
                Thread.sleep(500)
            }
            then()
        }.apply { isDaemon = true; this.name = name }.start()
    }

    private const val WAKE_PATIENCE_MS = 150_000L

    private fun dueText(todo: net.kuafuai.andee.config.Todo): String =
        net.kuafuai.andee.brain.WakeText.due(todo.id, todo.what, todo.untrusted)
}
