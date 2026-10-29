package net.kuafuai.andee.brain

import net.kuafuai.andee.config.BrainConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The agent loop, running on the tablet itself.
 *
 * This is the local counterpart of agentworld's `android-os` agent: it eats a
 * line of text, calls DeepSeek, executes the tool calls against this device's
 * own [CommandDispatcher], and hands back narration and a final answer. It
 * implements exactly the contract [net.kuafuai.andee.net.BodyWsClient] already
 * defined with its `onProgress` / `onFinal` lambdas, which is why everything
 * downstream — subtitles, ChatHistory, TTS, the edge glow, the fold, the stop
 * button — works unchanged. The two brains are mutually exclusive; see
 * `BodyCoreService.startBrain`.
 *
 * Four things in here are not stylistic choices:
 *
 *  * **Tool calls run one at a time.** The cloud uses `asyncio.gather`; copying
 *    that would be a bug. `CommandDispatcher.passthroughForGesture` flips the
 *    overlay non-touchable, sleeps ~50 ms, gestures, then restores — it is not
 *    reentrant, and two gestures in flight would trample each other's window
 *    state.
 *  * **Every tool call has a deadline.** `ui.ask` blocks until it is answered
 *    or times out; `ui.show_html` used to block until the user closed the page,
 *    and that is gone — see [note] and `CommandDispatcher`'s handler.
 *    Over the hub, `body_hub`'s 30 s request timeout quietly abandoned those;
 *    locally there is no such layer, so [dispatchWithTimeout] supplies one. It
 *    does **not** interrupt the work — same semantics as the hub giving up on a
 *    request that is still running.
 *  * **Screenshots are moved out of the tool result and rationed.** Every
 *    successful tap comes back with a full-screen PNG (`after_shot`), so a
 *    fifteen-step task carries fifteen images. See [attachImages] and
 *    [trimImages].
 *  * **The clock rides in on the user message, not the system prompt.** There
 *    is no clock tool on this device, and the system prompt plus the 55 tool
 *    specs are the cached prefix of every request — rewriting them each turn to
 *    stamp the time would throw that cache away for one line of text.
 *
 * Threading: [submit] returns immediately; the whole turn runs on the single
 * `LocalBrain-loop` thread, and [history] is touched from nowhere else. Tool
 * dispatch happens on a separate pool only so the loop thread can time it out.
 */
class LocalBrain(
    private val cfg: BrainConfig,
    /**
     * Everything this device provides: words, settings, the notebook, the
     * scrollback and the tools. The same loop runs on the phone and on the
     * desktop; only the host differs. See [BrainHost].
     */
    private val host: BrainHost,
    private val onProgress: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    /**
     * A turn finished — whoever started it (user, a todo coming due, or a
     * silent one). The service uses this to re-arm the quiet-hour timer and to
     * start the sweep. Called on the loop thread; must not block.
     */
    private val onTurnEnd: () -> Unit = {},

    /**
     * What a quiet-hour sweep cost, in tokens, once it is over. The user pays
     * for these calls with their own key, so the settings screen shows the
     * running total — see `BodyCoreService`. Only [TurnKind.SWEEP] is
     * reported: a harvest is not optional, so counting it against a daily
     * budget the user set for reviews would be misleading.
     */
    private val onSweepSpent: (Int) -> Unit = {},
) {

    private val llm = LlmClient(
        baseUrl = cfg.baseUrl,
        apiKey = cfg.apiKey,
        model = cfg.model,
        thinking = cfg.thinking,
        reasoningEffort = cfg.reasoningEffort,
    )

    /**
     * Apply a settings change that is only about how the backend is asked —
     * model, thinking, effort — with the conversation intact. The caller has
     * already established [BrainConfig.sameBackend]; a
     * different endpoint, key or mode is a different brain and gets rebuilt.
     * [cfg] itself stays as built: the only things read from it after
     * construction are the key and the mode, and neither can differ here.
     */
    fun retune(next: BrainConfig) {
        llm.retune(next.model, next.thinking, next.reasoningEffort)
    }

    private val loop = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LocalBrain-loop").apply { isDaemon = true }
    }

    init {
        BrainTrace.init(host)
    }

    /**
     * Only here so the loop thread can walk away from a tool that overran. A
     * cached pool rather than a single thread because an abandoned call keeps
     * its thread — a `ui.ask` card the user never answers can sit there for
     * minutes after we have stopped waiting, and the next tool must not queue
     * behind it.
     */
    private val toolExec = Executors.newCachedThreadPool { r ->
        Thread(r, "LocalBrain-tool").apply { isDaemon = true }
    }

    /**
     * Bumped by [submit] and by [cancel]. A running turn compares it against
     * the value it started with, so "has this turn been abandoned" needs no
     * separate flag and a late [cancel] can never silence the turn that
     * replaced it.
     */
    private val generation = AtomicInteger(0)

    /**
     * A turn is in progress. Read from any thread, written on the loop thread.
     *
     * This is what makes a wake-up deferrable. [submit] bumps [generation],
     * which throws a running turn away mid-sentence — correct when the user
     * interrupts, wrong when a todo's alarm fires, because the user would hear
     * the answer to their own question vanish. So a wake that arrives mid-turn
     * waits in [pendingWakes] instead.
     */
    private val busy = AtomicBoolean(false)

    /**
     * Loop thread only. Turns that arrived while another one was running.
     *
     * **The kind rides in the queue, not just the text.** It used to be a queue
     * of strings drained into [TurnKind.WAKE], which was true while a todo was
     * the only thing that could be deferred; a deferred [TurnKind.NOTIFY] would
     * have come back out of it as a wake-up and been handed the vault it is
     * specifically not allowed to touch. A capability that depends on a turn
     * arriving at an uncontended moment is not a capability check.
     */
    private val pendingWakes = ArrayDeque<Queued>()

    /**
     * A turn waiting its turn. [onAnswer] rides with it for the same reason the
     * kind does: a task another device handed us must still send its answer back
     * when it happened to arrive while something else was running.
     */
    private data class Queued(
        val text: String,
        val kind: TurnKind,
        val onAnswer: ((String?) -> Unit)? = null,
        /** [TurnKind.WORK] only: the territory it was given, applied when it actually runs. */
        val territory: String? = null,
    )

    /**
     * One tool call a [TurnKind.WORK] turn made, as the device saw it. [effective] is false when the call was
     * refused or its write did not land (`{"written": false, …}`), so a refusal cannot count as having done
     * something. [action] is the call's `action` argument, which is what tells a read from a write on the
     * tools that do both.
     */
    data class WorkCall(val method: String, val action: String, val callId: String, val effective: Boolean)

    /** What a work turn left behind: its closing words (never spoken) and every tool call it made. */
    data class WorkResult(val summary: String?, val calls: List<WorkCall>)

    /**
     * Why a turn is running — which decides two things: whether the user hears
     * it, and whether it is allowed to touch the device.
     */
    enum class TurnKind {
        /** The user just spoke. Speaks back; may drive the device. */
        USER,

        /** A todo came due. Speaks back; may drive the device. */
        WAKE,

        /**
         * A notification was triaged as worth acting on. Speaks back and may
         * drive the device — but **not** with the user's credentials.
         *
         * The distinction exists because this is the only turn whose subject
         * matter was written by a stranger. Everything in it came off another
         * app's notification, so a hostile sender gets to put text in front of
         * the model for free, and the one irreversible thing they could buy with
         * it is the vault: a password typed into a form on a page they chose.
         * The prompt tells the model the notification is data and not an
         * instruction, which is a mitigation; [untrusted] is the half that does
         * not depend on the model agreeing.
         */
        NOTIFY,

        /**
         * Another device of this user's handed us a job (`ask_device` over there). Speaks and may drive the
         * device like [WAKE] — the user asked for it, only through a different body — and its final answer
         * is also sent back as the report. Not [untrusted]: a device refuses to hand work on from a turn that
         * was, so nothing a stranger wrote can arrive this way. It may not hand the job on again
         * (`peer.ask` is refused in [runTool]); two bodies passing a task back and forth is a loop that
         * spends the user's money with nobody watching.
         */
        PEER,

        /** The quiet-hour review. Silent; notebook tools only. */
        SWEEP,

        /** The pre-trim review: same as [SWEEP], plus a summary that replaces messages. */
        HARVEST,

        /**
         * Andee woke itself: a care of its own came due (design: `docs/design/inner-drive.zh-CN.md` §8). It is
         * the user's own Andee acting on something it was given, so it is **not** [untrusted]; but nobody asked,
         * so it does not speak — no subtitle, no voice, no card of its own accord. Its words go to the diary, a
         *待批 item or a bubble, never aloud. What it may call is a *set the territory names* (see
         * [BrainHost.workTools]), not the whole list.
         */
        WORK,
        ;

        val silent: Boolean get() = this == SWEEP || this == HARVEST

        /** Runs with tools but says nothing aloud. Distinct from [silent], which also forbids touching the device. */
        val quiet: Boolean get() = this == WORK

        /** The turn's subject matter came from outside. See [NOTIFY]. */
        val untrusted: Boolean get() = this == NOTIFY
    }

    /**
     * Loop thread only. Set by [launch] for the duration of a turn and read by
     * [runTool]: every tool call of a turn shares it, and the dispatcher is the
     * thing that must not be fooled.
     */
    private var currentKind = TurnKind.USER

    /** Loop thread only. Guards against a harvest that cannot shrink anything. */
    private var lastHarvestAtSize = -1

    /** Loop thread only. Tokens spent by the turn currently running. */
    private var turnTokens = 0

    /** Seconds this turn has already spent in `wait_for_screen`. Loop thread only; reset per turn. See [WaitBudget]. */
    private var turnWaitSeconds = 0.0

    /**
     * Loop thread only. Where the current silent turn's own messages begin, or
     * -1. A harvest sets it before sending its prompt and throws everything
     * from there on away again once the summary has been read out — its own
     * scaffolding must not fossilise into the conversation.
     */
    private var internalFromIndex = -1

    /**
     * Loop thread only. The topic-folds a review asked for, in the order asked.
     *
     * **Recorded during the turn and applied after it, never in the middle.**
     * Folding rewrites [history] — a stretch of messages is replaced by one
     * marker — and doing that while a turn is in flight would move every index
     * the loop is holding: the `tool` messages still to be appended would land
     * against the wrong `tool_call_id`, which the API answers with a 400 and
     * the session dies mid-task. So the tool only appends here, and
     * [applyPendingFolds] runs where the turn is already over. Several are safe
     * together because each is found by identity, not position — applying one
     * cannot move another.
     */
    private val pendingFolds = mutableListOf<PendingFold>()

    /**
     * Loop thread only. The user-turn messages the review's prompt numbered as
     * `U0`, `U1`, …, captured when the prompt was built.
     *
     * Message *objects*, not positions. The prompt is built in one task on the
     * loop thread and its turn runs in a second one, and a user turn queued in
     * between can `trimHistory()` from the front and shift every index. A
     * reference cannot move: a turn is found again by identity when the fold is
     * recorded and again when it is applied, and one that has been trimmed away
     * is simply not found — a refusal rather than a splice into the wrong place.
     */
    private var shownTurns: List<JSONObject> = emptyList()

    /**
     * What a review asked to fold: the user turn that opens the stretch and the
     * user turn that opens whatever came after it. The stretch is everything
     * from the first up to, not including, the second. See [pendingFolds].
     */
    private data class PendingFold(
        val first: JSONObject,
        val next: JSONObject,
        val title: String,
        val summary: String,
        val text: String,
    )

    /** Loop thread only. */
    private val history = mutableListOf<JSONObject>()

    /** Image messages currently in [history], oldest first. See [trimImages]. */
    private val imageMessages = mutableListOf<JSONObject>()

    /**
     * Photo messages the *user* attached, oldest first. Kept apart from
     * [imageMessages] so a task's screenshots cannot evict the very photo the
     * task is about; see [attachUserPhotos].
     */
    private val photoMessages = mutableListOf<JSONObject>()

    /** [images] are base64 JPEGs; with any of them, [userText] may be empty. */
    fun submit(userText: String, images: List<String> = emptyList()) {
        val text = userText.trim()
        if (text.isEmpty() && images.isEmpty()) return
        if (cfg.apiKey.isEmpty()) {
            // Said out loud rather than logged: from the ball's point of view a
            // missing key is indistinguishable from a brain that never answers.
            onFinal(noKeyText())
            return
        }
        // The quiet-hour timer measures silence from the user's last words, so
        // every real utterance stamps it. Wake-ups and sweeps do not: a sweep
        // must not look like the user spoke, or it would keep pushing its own
        // trigger out.
        host.touchActivity()
        launch(text, TurnKind.USER, images)
    }

    /**
     * Start a turn nobody just asked for: a todo came due.
     *
     * Deferred rather than interrupting. See [busy] — the alternative is
     * cancelling a turn the user is in the middle of, which is exactly the
     * thing [cancel] is for and the user did not press it.
     *
     * [untrusted] = the todo was set on a [TurnKind.NOTIFY] turn. It runs as
     * one again, or a stranger's message could get past the vault guard simply
     * by asking for it to be done later.
     */
    fun wake(text: String, untrusted: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        val kind = if (untrusted) TurnKind.NOTIFY else TurnKind.WAKE
        loop.execute {
            if (busy.get()) {
                // The same alarm twice (a check that came due while its predecessor was still waiting its
                // turn) is one check. Two copies would each do the full look, and the second would find
                // nothing new to say.
                if (pendingWakes.any { it.text == t && it.kind == kind }) {
                    host.logInfo(TAG, "wake already queued, not queueing it twice: ${t.take(60)}")
                    return@execute
                }
                host.logInfo(TAG, "wake queued behind a running turn: ${t.take(60)}")
                pendingWakes.addLast(Queued(t, kind))
                return@execute
            }
            launch(t, kind)
        }
    }

    /**
     * Start a turn for a notification the device decided was worth acting on.
     *
     * Deferred exactly like [wake] and for the same reason: a message arriving
     * while the user is being answered must not cancel the answer. It is also
     * **not** [submit], and the two differences are the point. It does not stamp
     * `Notebook.touchActivity` — the quiet-hour timer measures silence from the
     * *user*, and a chatty group would otherwise keep pushing the review out all
     * evening on their behalf. And it runs as [TurnKind.NOTIFY], which is what
     * closes the vault for the duration.
     */
    fun notified(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (cfg.apiKey.isEmpty()) return // nothing triaged it either; see NotificationTriage
        loop.execute {
            if (busy.get()) {
                host.logInfo(TAG, "notification queued behind a running turn: ${t.take(60)}")
                pendingWakes.addLast(Queued(t, TurnKind.NOTIFY))
                return@execute
            }
            launch(t, TurnKind.NOTIFY)
        }
    }

    /**
     * Run a task another of the user's devices handed us, and call [onAnswer] with the final answer — or
     * with null when the turn produced none (cut off by the user here, by a stop, or by a failure that
     * already said so). Deferred like [wake]: the person in front of *this* device goes first.
     * [onAnswer] is called on the loop thread and must not block.
     */
    fun peerTask(text: String, onAnswer: (String?) -> Unit) {
        val t = text.trim()
        if (t.isEmpty()) return onAnswer(null)
        if (cfg.apiKey.isEmpty()) return onAnswer(noKeyText())
        loop.execute {
            if (busy.get()) {
                host.logInfo(TAG, "peer task queued behind a running turn: ${t.take(60)}")
                pendingWakes.addLast(Queued(t, TurnKind.PEER, onAnswer))
                return@execute
            }
            launch(t, TurnKind.PEER, onAnswer = onAnswer)
        }
    }

    /**
     * The quiet-hour review: look back over a conversation that has stopped,
     * and put what is worth keeping into the notebook.
     *
     * Silent by construction ([internal]) — no subtitle, no voice, and the
     * dispatcher refuses every method except `note.*`. Declines to run (and
     * says why in the log) when there is nothing to review, when a turn is
     * running, or when there is no API key; the caller re-arms the timer
     * either way, so a refusal is never a lost sweep.
     */
    fun runSweep() {
        loop.execute {
            // [history] is in-memory and dies with the process, so after a kill
            // the sweep has nothing to review — the "recover the sweep" path
            // would trigger and then skip on "0 messages". Rebuild the dialogue
            // from the persisted scrollback first; a fresh conversation with no
            // rows still comes back empty and hits the usual gate below.
            if (history.isEmpty()) rebuildHistoryFromScrollback()
            when {
                cfg.apiKey.isEmpty() -> host.logInfo(TAG, "sweep skipped: no API key")
                busy.get() -> host.logInfo(TAG, "sweep skipped: a turn is running")
                history.size < MIN_MESSAGES_TO_REVIEW ->
                    host.logInfo(TAG, "sweep skipped: only ${history.size} messages to review")
                else -> launch(sweepInstruction(), TurnKind.SWEEP)
            }
        }
    }

    /**
     * Rebuild the dialogue half of [history] from the persisted scrollback.
     *
     * The sweep reviews "this conversation", but the only record that survives
     * a killed process is [net.kuafuai.andee.ui.ChatHistory] — [history] itself
     * is a memory-only list. This walks the rows and rebuilds the two roles the
     * sweep cares about (the person's words and the assistant's), each user
     * message re-stamped with the row's own clock so [TopicSplitter] can still
     * find the turns and their silences. Pages and errors are skipped: they are
     * artifacts, not conversation.
     *
     * Capped at the tail so a weeks-old scrollback does not blow the context
     * window — the sweep is about what was *just* said, and the rest is the
     * notebook's business. Caller runs on the loop thread, so touching
     * [history] here is safe.
     */
    private fun rebuildHistoryFromScrollback() {
        val rows = host.scrollback()
            .filter { it.isUser || it.isAssistant }
            .filter { it.text.isNotBlank() }
            .takeLast(REBUILD_MAX_MESSAGES)
        if (rows.isEmpty()) return
        history += JSONObject().put("role", "system").put("content", systemPrompt())
        val fmt = SimpleDateFormat("yyyy-MM-dd EEE HH:mm", host.locale())
        for (r in rows) {
            history += if (r.isUser) {
                JSONObject().put("role", "user")
                    .put("content", "[now ${fmt.format(Date(r.ts))}] ${r.text}")
            } else {
                JSONObject().put("role", "assistant").put("content", r.text)
            }
        }
        host.logInfo(TAG, "sweep rebuilt ${rows.size} messages from the scrollback")
    }

    /**
     * The quiet-hour prompt with this conversation's topic candidates folded in.
     *
     * Built here rather than kept as one constant because the candidate list is
     * a fact about the history at this instant — and because it has to be the
     * *same* list [noteFold] later indexes into. [topicCandidatesForPrompt]
     * captures it as a side effect for exactly that reason; see [shownTurns].
     *
     * When there is nothing to offer, the whole section is left out. A review
     * told to consider an empty list spends a step reasoning about nothing.
     */
    private fun sweepInstruction(): String {
        val candidates = topicCandidatesForPrompt()
        if (candidates.isEmpty()) return SWEEP_INSTRUCTION
        return SWEEP_INSTRUCTION + TOPIC_RULES.format(candidates)
    }

    /** Read from any thread. The service asks before queueing a wake-up. */
    fun isBusy(): Boolean = busy.get()

    /**
     * The single entry point for all three kinds of turn (user / wake / sweep).
     *
     * Everything that has to happen around a turn — claiming [busy], clearing
     * it, draining queued wakes, harvesting, telling the service the turn is
     * over — is here once, so no entry point can forget a step.
     */
    private fun launch(
        text: String,
        kind: TurnKind,
        images: List<String> = emptyList(),
        onAnswer: ((String?) -> Unit)? = null,
        territory: String? = null,
    ) {
        val gen = generation.incrementAndGet()
        loop.execute {
            busy.set(true)
            currentKind = kind
            aimMisses = 0
            reviewItems = if (kind.silent) mutableListOf() else null
            if (!kind.silent) justSwept = false
            when (kind) {
                TurnKind.WAKE -> emit(BrainEvent(BrainEvent.Kind.WOKE, WakeText.what(text)))
                TurnKind.NOTIFY -> emit(BrainEvent(BrainEvent.Kind.NOTIFIED, detail = text.take(400)))
                TurnKind.PEER -> emit(BrainEvent(BrainEvent.Kind.FRIEND_TASK, detail = text.take(400)))
                else -> {}
            }
            if (kind == TurnKind.WORK) {
                // Per turn, here, so a queued work turn cannot inherit the one before it.
                workTerritory = territory
                workSummary = null
                workCalls.clear()
            }
            turnAnswer = null
            host.turnStarted(kind.name)
            turnTokens = 0
            turnWaitSeconds = 0.0
            try {
                BrainTrace.turn(gen, kind.name, text)
                runTurn(text, gen, kind, images)
            } catch (t: Throwable) {
                BrainTrace.end(gen, "threw: ${t.message ?: t.javaClass.simpleName}")
                if (gen == generation.get()) {
                    host.logError(TAG, "turn $gen ($kind) failed", t)
                    // A silent turn has nobody to apologise to; it just ends. So does one Andee started itself:
                    // nobody asked, and a spoken "backend error" at 3 pm out of nowhere is the worst way to hear it.
                    if (!kind.silent && !kind.quiet) finish(explainFailure(t))
                } else {
                    // Almost always the cancelled call's own IOException.
                    host.logInfo(TAG, "turn $gen abandoned: ${t.message}")
                }
            } finally {
                // Cleared here, and not inside runTurn, because runTurn has a
                // dozen `return`s: leaving the kind set on any of them would
                // make the *next* turn refuse every device tool.
                currentKind = TurnKind.USER
                // The same place, for the same reason, and one more: the trace's
                // idea of "the turn in flight" has to end with the turn even
                // when the turn recorded almost nothing. `submit`'s missing-key
                // early return writes a scrollback row without ever reaching
                // here, which is exactly why this clears rather than being
                // consumed on read — see [BrainTrace.current].
                BrainTrace.clearTurn()
                // Whoever is waiting on this turn's answer hears exactly once, cut off or not. Only an
                // answer of the turn that is still current counts: a turn the user talked over here has
                // nothing to report, and a stale answer would read as the job being done.
                val answer = turnAnswer.takeIf { gen == generation.get() }
                turnAnswer = null
                onAnswer?.let { cb -> runCatching { cb(answer) }.onFailure { host.logWarn(TAG, "answer callback failed", it) } }
                busy.set(false)
                dropUnfinishedHarvest(kind)
                // After the turn, never during it — see [pendingFolds]. A fold
                // that succeeded leaves one message where a stretch used to be,
                // and the splice has to happen when nothing is holding an index
                // into the old shape. Guarded on the generation like everything
                // else: a cancelled turn's fold belongs to a conversation that
                // is no longer the current one.
                if (gen == generation.get()) applyPendingFolds() else pendingFolds.clear()
                if (kind == TurnKind.SWEEP) onSweepSpent(turnTokens)
                // One row per review, listing what it wrote; nothing at all when it wrote nothing.
                val reviewed = reviewItems
                reviewItems = null
                if (reviewed != null && reviewed.isNotEmpty() && gen == generation.get()) {
                    emit(BrainEvent(BrainEvent.Kind.REVIEWED, reviewed.count { it.kind != BrainEvent.Kind.WIKI_READ }.toString(), items = reviewed))
                }
                if (kind == TurnKind.SWEEP) justSwept = true
                if (gen == generation.get()) {
                    drainWakes()
                    maybeHarvest()
                    onTurnEnd()
                }
            }
        }
    }

    /**
     * The sentence the user hears when a turn dies. A backend status code is
     * the one failure where the user can actually do something about it, so it
     * gets its own words instead of `HTTP 402: {"error":…}` read aloud. 402 is
     * the one that matters most — an empty account looks, from the tablet,
     * exactly like the ball going quiet. Anything unrecognised falls back to
     * the old generic line with the raw message, so nothing is hidden.
     */
    /** The sentence for "no key": the CodeFlying one says where to get it. */
    private fun noKeyText(): String =
        host.text(if (cfg.mode == BrainConfig.BRAIN_CODEFLYING) BrainText.NO_KEY_CODEFLYING else BrainText.NO_KEY)

    private fun explainFailure(t: Throwable): String {
        fun s(id: BrainText, vararg args: Any) = host.text(id, *args)
        if (t is LlmMovedException) return s(BrainText.ERROR_MOVED, t.where.ifEmpty { "HTTP ${t.code}" })
        if (t is LlmHttpException) {
            return when (t.code) {
                402 -> s(BrainText.ERROR_402)
                401, 403 -> s(BrainText.ERROR_AUTH, t.code)
                400 -> s(BrainText.ERROR_400, t.detail.take(160).ifBlank { "HTTP 400" })
                404 -> s(BrainText.ERROR_404)
                408, 429 -> s(BrainText.ERROR_429)
                in 500..599 -> s(BrainText.ERROR_5XX, t.code)
                else -> s(BrainText.ERROR, t.message ?: t.javaClass.simpleName)
            }
        }
        return when (t) {
            is java.net.SocketTimeoutException -> s(BrainText.ERROR_TIMEOUT)
            is java.net.UnknownHostException,
            is java.net.ConnectException -> s(BrainText.ERROR_NETWORK)
            else -> s(BrainText.ERROR, t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * Run one queued turn, if any. Loop thread only.
     *
     * One at a time, and only from the end of a turn that is still current (see the caller): each queued
     * turn's own end drains the next. A user request that arrives while the queue is waiting has already
     * bumped the generation, so the turn that was cut off never drains it — the queue waits for the new
     * turn instead, which is the right order: the person who just spoke goes first.
     */
    private fun drainWakes() {
        val next = pendingWakes.removeFirstOrNull() ?: return
        host.logInfo(TAG, "running a queued ${next.kind} turn: ${next.text.take(60)}")
        launch(next.text, next.kind, onAnswer = next.onAnswer, territory = next.territory)
    }

    /**
     * Loop thread only. The final answer of the turn running now, for [launch] to hand to whoever is
     * waiting on it (see [peerTask]). Set by [finish] and by nothing else.
     */
    private var turnAnswer: String? = null

    /** Loop thread only. The closing words of a [TurnKind.WORK] turn; read by [work]'s callback. Never spoken. */
    private var workSummary: String? = null

    /**
     * Loop thread only. While a review runs, its writes are gathered here instead of each becoming a row: a review
     * is one thing that happened, and ten rows of it drowned everything else in the timeline. Null when not in a
     * review. Folds land after the turn's body, so this lives until the summary row is emitted.
     */
    private var reviewItems: MutableList<BrainEvent>? = null

    /**
     * Loop thread only. The last turn was a sweep and nothing has been said since. A harvest that follows at once
     * — the sweep's own rebuilt history is often what puts the context over budget — then only summarises: the
     * notebook was just brought up to date, and going through the same rules again wrote everything twice.
     */
    private var justSwept = false

    /** Loop thread only. Coordinate taps in a row whose screen did not change; see [AimHints]. Reset per turn. */
    private var aimMisses = 0

    /** Loop thread only. Whether this turn's tool list offers `tap_target`; set when the turn's tools are built. */
    private var locatorOffered = false

    /** Loop thread only. The tool calls of the [TurnKind.WORK] turn running now; see [WorkCall]. */
    private val workCalls = mutableListOf<WorkCall>()

    private fun workResult() = WorkResult(workSummary, workCalls.toList())

    /**
     * Wake up on a care of its own: a [TurnKind.WORK] turn. Queued behind whatever is running (never cuts a
     * user off), and answered to [onDone] with the model's closing words and whether any tool with an effect
     * ran — the device's own record, not the model's account.
     *
     * [territory] names the tool set it may use (see [BrainHost.workTools]); null = the default set.
     */
    fun work(text: String, territory: String?, onDone: (WorkResult) -> Unit) {
        val t = text.trim()
        if (t.isEmpty()) return
        loop.execute {
            val job = Queued(t, TurnKind.WORK, { onDone(workResult()) }, territory)
            if (busy.get()) {
                host.logInfo(TAG, "work queued behind a running turn: ${t.take(60)}")
                pendingWakes.addLast(job)
                return@execute
            }
            launch(t, TurnKind.WORK, onAnswer = job.onAnswer, territory = territory)
        }
    }

    /** Loop thread only. The territory the current work turn was given. */
    private var workTerritory: String? = null

    /**
     * Loop thread only. A task that stopped before it was done — at the step limit, or cut by the repeat
     * guard — and the user may yet say 继续 to.
     *
     * Without this the stop was invisible to the model and the task fragile. The sentence the user heard was
     * never in the history, so the model did not know it had been cut off; and the trim protects only the
     * newest turn, so the moment anything else became newest (a todo firing between the stop and 继续 —
     * measured, 10-22 11:30, 104 messages trimmed to 52) the whole task was dropped and 继续 meant nothing.
     * While this is set the task's messages are kept by the trim, the harvest and topic folds alike, and every
     * user message says what was stopped. Cleared by a later turn that acts and finishes, or by [UNFINISHED_TTL_MS].
     */
    private class Unfinished(val start: JSONObject, val request: String, val at: Long, val steps: Int)

    private var unfinished: Unfinished? = null

    /** Index of the unfinished task's first message, or [Int.MAX_VALUE] when there is none to keep. */
    private fun keepFrom(): Int {
        val u = unfinished ?: return Int.MAX_VALUE
        val i = history.indexOfFirst { it === u.start }
        if (i < 1) {
            unfinished = null
            return Int.MAX_VALUE
        }
        return i
    }

    /**
     * End a turn that is not done: the sentence goes into the history as well as to the user, every tool call
     * the model already made is answered (an unanswered `tool_call_id` is a 400 on the next request, which is
     * how 继续 used to fail outright after the repeat guard), and the task is marked [unfinished].
     */
    private fun stopUnfinished(start: JSONObject, request: String, steps: Int, said: String, pending: List<LlmClient.ToolCall>) {
        for (tc in pending) {
            history += JSONObject().put("role", "tool").put("tool_call_id", tc.id)
                .put("content", "Not run: the turn was stopped before this call (see your next message).")
        }
        history += JSONObject().put("role", "assistant").put("content", said)
        unfinished = Unfinished(start, request.lineSequence().firstOrNull().orEmpty().trim().take(160), System.currentTimeMillis(), steps)
        finish(said)
    }

    /**
     * Answer the tool calls a cut-off turn left hanging. A turn the user stopped (or talked over) returns
     * between one tool call and the next, so its last assistant message can name calls that never got a
     * `tool` reply — and the API refuses the whole next request for it, which is 继续 failing outright.
     * Each missing reply is placed right after that message's own replies, where the API expects it.
     */
    private fun answerDangling() {
        val a = history.indexOfLast { it.optString("role") == "assistant" && it.has("tool_calls") }
        if (a < 0) return
        val calls = history[a].optJSONArray("tool_calls") ?: return
        var end = a + 1
        val answered = HashSet<String>()
        while (end < history.size && history[end].optString("role") == "tool") {
            answered += history[end].optString("tool_call_id"); end++
        }
        var at = end
        for (i in 0 until calls.length()) {
            val id = calls.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isEmpty() || id in answered) continue
            history.add(at++, JSONObject().put("role", "tool").put("tool_call_id", id).put("content", "Not run: the turn was stopped before this call."))
        }
        if (at > end) host.logInfo(TAG, "answered ${at - end} tool call(s) a stopped turn left hanging")
    }

    /** The line every user message carries while a task is unfinished. Empty otherwise. */
    private fun unfinishedNote(): String {
        val u = unfinished ?: return ""
        val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(u.at))
        return "\n\n(unfinished: at $at you stopped a task after ${u.steps} steps before it was done — it began with " +
            "\"${u.request}\". Its steps are still above, and so is what you told the user when you stopped. If they want " +
            "you to go on (继续 / go on / keep going), carry on from where you stopped — do not start over, and do not " +
            "ask what they mean.)"
    }

    /** A turn's final answer: the one place it is both said and remembered. Loop thread only. */
    private fun finish(text: String) {
        turnAnswer = text
        onFinal(text)
    }

    /**
     * Drop a status line into the conversation **without starting a turn**.
     *
     * For things the device learns on its own that the model should know but
     * nobody asked about — currently one: the user closed an HTML page. The
     * alternative was to submit it as a user turn, and that is wrong twice
     * over: it costs a model call and a spoken sentence every time a page is
     * dismissed ("好的，看完了" — noise), and it would arrive as something the
     * user said, which it is not.
     *
     * It lands as a trailing `user` message, the same shape
     * `ChatHistory.addUser("（…）")` already uses for the meeting transcript, so
     * the *next* turn reads it as context. Consequence worth knowing: if the
     * user never says anything again, the model never sees it either. That is
     * the intended trade — this is a fact to have on hand, not a prompt.
     *
     * Callable from any thread; [history] stays loop-thread-only.
     */
    fun note(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        loop.execute {
            history += JSONObject().put("role", "user").put("content", t)
            host.logInfo(TAG, "note: $t")
        }
    }

    /**
     * Tell the brain a scene it was in has ended — a turn, not a [note], so it
     * can act on the news (a closing report, a promise to keep) instead of
     * holding it until the user next speaks.
     *
     * [wake] without a key check would turn "the user tapped ✕ on the chip"
     * into a spoken backend error, so this refuses quietly where [notified] does.
     */
    fun sceneEnded(text: String) {
        if (cfg.apiKey.isEmpty()) return
        wake(text)
    }

    /**
     * The user hit ■. Must not block — it is called from the main thread.
     *
     * Bumping [generation] is what actually stops the loop; [LlmClient.cancel]
     * only shortens the wait, because a turn parked in a 3-minute read would
     * otherwise keep the ball lit until the model finished answering a question
     * nobody is listening to anymore.
     */
    fun cancel() {
        generation.incrementAndGet()
        llm.cancel()
    }

    /** Service teardown. Threads are daemons, so this is tidiness, not safety. */
    fun stop() {
        cancel()
        loop.shutdownNow()
        toolExec.shutdownNow()
    }

    // ------------------------------------------------------------------
    // the loop
    // ------------------------------------------------------------------

    private fun runTurn(userText: String, gen: Int, kind: TurnKind, images: List<String> = emptyList()) {
        val internal = kind.silent
        // Says nothing aloud, but is a real turn: it has a user-shaped opening message, tools, and a trusted caller.
        val quiet = kind.quiet
        val harvest = kind == TurnKind.HARVEST
        if (history.isEmpty()) {
            history += JSONObject().put("role", "system").put("content", systemPrompt())
        } else {
            reseatPersona()
        }
        // The hard backstop stays on user turns only. A silent turn is the
        // thing that is supposed to bring the context back under budget — see
        // [maybeHarvest] — so trimming here would delete exactly the material
        // the harvest was about to read.
        if (!internal) trimHistory()
        // Where this turn's own messages begin. The harvest uses it afterwards
        // to throw away its own scaffolding and keep only the summary.
        answerDangling()
        if (internal) internalFromIndex = history.size
        if (!internal) unfinished?.let { if (System.currentTimeMillis() - it.at > UNFINISHED_TTL_MS) unfinished = null }
        history += if (internal) {
            // A silent turn speaks as itself, never as the user. Hanging the
            // review prompt on a `user` message would forge an utterance the
            // person never made, and the next turn would read "the user asked
            // me to review our conversation" — false, and it sticks. This is
            // the same call the cloud makes with `last_role="assistant"`.
            JSONObject().put("role", "assistant").put("content", userText)
        } else {
            // The notebook index rides in **here**, on the user message, and
            // not in the system prompt. `LocalPrompt` is a constant on purpose
            // so DeepSeek's prefix cache survives, and the system message is
            // the head of the request — anything injected there invalidates the
            // whole conversation behind it. The clock already rides in at this
            // position for the same reason.
            //
            // Empty notebook, empty string, zero cost: the feature only starts
            // paying for itself once there is something to remember.
            val index = host.notebookIndex()
            // The plan (`Plans`): what it is helping them with and what it meant to do today. Right after
            // the notebook and for the same cache reason; it is the thread every turn should be able to see.
            val plan = host.planIndex()
            // Who I am in a breath, and what is pulling at me; and what is on my wiki shelf. Same place, same reason
            // as the plan line: one line each, bodies never. (`Selfhood`, `WikiBook`.)
            val self = host.selfIndex()
            val wiki = host.wikiIndex()
            val scenes = host.scenesIndex()
            // The pages it has made, the same way and for the same reason: a
            // tool answers a question the brain thought to ask, and 「上次那个表格」
            // is exactly the question it does not know to ask. `list_pages` has
            // the full list; this is the four or five newest, to make the shelf
            // visible at all.
            val pages = host.pagesIndex()
            // And the conversations that have been put away. Same position, same
            // reasoning as the three above; the fourth is that this one is about
            // an ABSENCE — see the note where it is appended.
            val topics = host.topicsIndex()
            // What is installed and what it is for (`Gear`). Same position and same reasoning as the four
            // above: the shelf changes whenever an app is used or learned, and the system prompt is the one
            // place that must never move. Empty until there is something worth a line.
            val gear = host.gearIndex()
            val friends = host.friendsIndex()
            // Photos with no words: there is no sentence of the user's to take
            // the language from, so the bracketed fallback comes back for it.
            val wordless = userText.isEmpty()
            val said = if (wordless) {
                "(the user sent ${images.size} photo(s) with no words — they are attached right below; answer what they most likely want)"
            } else {
                userText
            }
            JSONObject()
                .put("role", "user")
                .put(
                    // The interface language rides here, not in the system
                    // prompt: see [uiLangTag] and [LocalPrompt] §9. It is what
                    // the model follows when this turn is a reminder or a
                    // review rather than something the user just said — and it
                    // is therefore **omitted when the user just said
                    // something**, which is the whole of this condition.
                    //
                    // Emitting it every turn is what made a Chinese user get
                    // English answers on an English tablet. §9's rule is right
                    // and the model can recite it; what beats it is proximity.
                    // `ui-language en` sits inline, immediately before the
                    // user's own Chinese sentence, and reads as a directive
                    // attached to *this* turn — against a precedence rule
                    // hundreds of lines back in a document that is itself
                    // English, next to 55 English tool descriptions. The near
                    // signal wins. So the fallback is present exactly when it
                    // is the answer and absent when there is a better one, and
                    // the model is never asked to arbitrate between two.
                    "content",
                    "[now ${stamp()}${if (kind == TurnKind.USER && !wordless) "" else " · ui-language ${uiLangTag()}"}] $said" +
                        if (index.isEmpty()) {
                            ""
                        } else {
                            "\n\n(what your notebook holds, for reference; use recall for detail)\n$index"
                        } +
                        if (self.isEmpty() && wiki.isEmpty()) {
                            ""
                        } else {
                            "\n\n(you, and your shelf — the Settings page shows them to the user)\n" + listOf(self, wiki).filter { it.isNotEmpty() }.joinToString("\n")
                        } +
                        if (plan.isEmpty()) {
                            ""
                        } else {
                            "\n\n(your plan — what you are helping them with and what you meant to do today; " +
                                "the user can see it in Settings; change it with day_plan / plan_directions)\n$plan"
                        } +
                        // Same position and same reason as the notebook index:
                        // a newly saved scene must not cost the prefix cache.
                        // Only the scene you are *in* sits in the system prompt.
                        if (scenes.isEmpty()) {
                            ""
                        } else {
                            "\n\n(scenes you can enter — see §11; you are in: ${host.activeSceneName() ?: "none"})\n$scenes"
                        } +
                        // And the shelf of pages it has built (§7). Same
                        // position, same reasoning: this list changes every time
                        // a page is made, and the system prompt is the one place
                        // it must never go.
                        if (pages.isEmpty()) {
                            ""
                        } else {
                            "\n\n(pages you have made on this device — §7; the newest few, `#id title (vN)`; " +
                                "use list_pages for all of them and get_page to read one back before revising " +
                                "it — reading it back is what files your revision as its next version)\n$pages"
                        } +
                        // And the conversations that have been put away. The same
                        // position and the same reason as the other three, plus
                        // one of its own: what is NOT here is the point. A topic
                        // that was folded is gone from this conversation, so a
                        // model with no index answers 「上次那个比价」 from a
                        // one-line summary it half-remembers — or says it never
                        // happened. Seeing the title is what makes it go and read
                        // the real thing instead.
                        if (topics.isEmpty()) {
                            ""
                        } else {
                            "\n\n(conversations you have finished and put away; the newest " +
                                "few, `#id title (date · N msgs)`. These are NOT in front of you and the " +
                                "summary you may have of one is not the whole story — read it back with " +
                                "get_topic before answering about a detail, and never say it did not " +
                                "happen)\n$topics"
                        } +
                        // Last, and shortest: where to look before doing a job by hand. See §3 (handing a
                        // long job over) and the `list_gear` tool.
                        if (gear.isEmpty()) {
                            ""
                        } else {
                            "\n\n(installed here and worth reaching for first, most used first — if one of these is built " +
                                "for the job, prefer handing it over to doing every step yourself; list_gear has the rest)\n$gear"
                        } +
                        // The other Andees it can ask. Same position, same reason: who is reachable changes all day.
                        if (friends.isEmpty()) {
                            ""
                        } else {
                            "\n\n(other Andees you can ask with ask_friend; list_friends has detail)\n$friends"
                        } +
                        // A task cut off at the step limit, so 继续 has something to continue. See [Unfinished].
                        unfinishedNote(),
                )
        }
        val turnStart = history.last()
        var acted = false

        if (!internal && images.isNotEmpty()) attachUserPhotos(images)

        // A work turn gets the territory's set, not all ~80: it is cheaper and, more to the point, a stranger-facing
        // errand has no business holding the vault or the shell. Everyone else is unchanged.
        val tools = if (quiet) LlmClient.toolSpecs(*host.workTools(workTerritory).toTypedArray())
        else LlmClient.toolSpecs(*host.toolLists().toTypedArray())
        locatorOffered = (0 until tools.length()).any { tools.optJSONObject(it)?.optJSONObject("function")?.optString("name") == "tap_target" }
        var lastSignature = ""
        var repeats = 0

        // Read once per turn so the setting can change without a brain
        // restart — see [VoiceConfig.maxSteps]. Values outside 1..200 are
        // clamped by the reader; a 0 saved by hand would otherwise refuse
        // every turn before it started.
        val maxSteps = host.maxSteps()
        for (step in 1..maxSteps) {
            if (gen != generation.get()) return
            // `enter_scene` mid-turn should change how the rest of this turn
            // reads, not only the next one. A string compare, free when nothing
            // moved.
            if (step > 1) reseatPersona()
            // Silent reviews ask without thinking. In thinking mode the
            // backend wants every earlier assistant turn's reasoning sent back,
            // and a review replays a long history whose reasoning was never
            // kept, so it failed with HTTP 400 (seen 2026-10-20 and 10-21). A
            // review is bookkeeping and loses little without it.
            val reply = llm.complete(history, tools, noThinking = internal)
            if (gen != generation.get()) return
            turnTokens += reply.promptTokens + reply.completionTokens

            if (reply.reasoning.isNotBlank()) {
                host.logInfo(TAG, "think[$step]: ${reply.reasoning.take(500)}")
            }
            host.logInfo(
                TAG,
                "step $step: ${reply.toolCalls.size} tool call(s), " +
                    "tokens ${reply.promptTokens}/${reply.completionTokens} " +
                    "(cached ${reply.cachedTokens}), history ${history.size} msgs",
            )

            BrainTrace.step(gen, step, reply.content, reply.reasoning, reply.toolCalls.size, reply.promptTokens)
            history += reply.assistantMessage

            if (reply.toolCalls.isEmpty()) {
                val answer = reply.content.trim()
                if (harvest) {
                    // A harvest turn's prose is not an answer to anybody: it is
                    // the summary that replaces the part of the conversation we
                    // are about to drop.
                    applyHarvest(answer)
                } else if (quiet) {
                    // The turn's closing words are for the log and the care's note, not for the user's ears.
                    workSummary = answer
                } else if (!internal) {
                    // A turn that did something and finished means the user has moved on — or that 继续 just
                    // finished the job. Either way the stopped task no longer needs keeping.
                    if (acted) unfinished = null
                    // A silent turn has nobody to answer; whatever it wrote
                    // stays in the history as its own note and that is all.
                    finish(
                        answer.ifEmpty {
                            // A tool-call-free reply with no prose is the model
                            // ending the turn with nothing in it. Saying so beats
                            // the ball going dark with no explanation.
                            host.text(BrainText.NO_RESULT)
                        },
                    )
                }
                return
            }

            if (reply.content.isNotBlank() && !internal && !quiet) onProgress(reply.content.trim())

            val images = mutableListOf<String>()
            for ((ti, tc) in reply.toolCalls.withIndex()) {
                if (gen != generation.get()) return

                // The repeated-call guard, which is *not* the one in
                // ScreenController: that one counts taps at a screen location,
                // this one counts identical tool invocations. A model stuck in
                // `wait_for_screen(3)` forever trips neither of the others.
                val sig = tc.signature()
                repeats = if (sig == lastSignature) repeats + 1 else 0
                lastSignature = sig
                if (repeats >= MAX_REPEATS) {
                    host.logWarn(TAG, "same call $MAX_REPEATS× in a row: $sig")
                    BrainTrace.end(gen, "same call $MAX_REPEATS× in a row: ${tc.name}")
                    val rest = reply.toolCalls.drop(ti)
                    if (quiet) {
                        rest.forEach { history += JSONObject().put("role", "tool").put("tool_call_id", it.id).put("content", "Not run: stopped.") }
                    } else if (!internal) {
                        stopUnfinished(turnStart, userText, step, host.text(BrainText.REPEAT_STOP, tc.name), rest)
                    } else {
                        rest.forEach { history += JSONObject().put("role", "tool").put("tool_call_id", it.id).put("content", "Not run: stopped.") }
                    }
                    return
                }

                if (reply.content.isBlank() && !internal && !quiet) onProgress(narrate(tc.name))
                val t0 = System.nanoTime() / 1_000_000L
                acted = true
                val msg = runTool(tc, images, internal, kind.untrusted)
                BrainTrace.tool(
                    gen, tc.name, host.methodOf(tc.name), tc.argumentsRaw,
                    msg.optString("content"), System.nanoTime() / 1_000_000L - t0,
                )
                history += msg
            }

            if (images.isNotEmpty()) attachImages(images)
        }

        host.logWarn(TAG, "hit max steps ($maxSteps)")
        BrainTrace.end(gen, "hit max steps ($maxSteps)")
        if (quiet) {
            workSummary = ""
            host.logWarn(TAG, "work turn ran out of steps ($maxSteps)")
        } else if (!internal) stopUnfinished(turnStart, userText, maxSteps, host.text(BrainText.MAX_STEPS, maxSteps), emptyList())
    }

    /**
     * Run one tool call and build the `tool` message that answers it.
     *
     * Never throws: the API requires a `tool` message for every `tool_call_id`
     * in the assistant message, so a failure has to come back as content the
     * model can read and react to. Images found in the result are moved into
     * [images] — see [attachImages].
     *
     * **Everything this returns is English.** It is read by the model, not by
     * the user, and the model-facing set is English throughout — this is the
     * other half of the dividing line inside this file, and it is the reason
     * `narrate` above is a resource while these strings are literals. A reader
     * who moves one across is breaking the other.
     *
     * The trap this creates is real and §9 of [LocalPrompt] exists for it: the
     * language a prompt is written in is the language the answer tends to come
     * back in, so an English tool result pulls towards an English answer even
     * when the user has been speaking Chinese. The rule, not the wording of
     * these strings, is what keeps the answer in the user's language.
     */
    private fun runTool(
        tc: LlmClient.ToolCall,
        images: MutableList<String>,
        silent: Boolean,
        untrusted: Boolean,
    ): JSONObject {
        val body: String = try {
            val args = tc.argumentsOrNull()
                ?: return toolMessage(
                    tc.id,
                    "The arguments were not valid JSON, so I did not run it. Raw: ${tc.argumentsRaw.take(300)}",
                )
            // Folding is intercepted here rather than dispatched, and the reason
            // is that it is the one `note.*` call whose subject is *this*
            // object's state: it names a stretch of [history] and rewrites it,
            // and the dispatcher has no history to name or rewrite. Handling it
            // in [CommandDispatcher] would mean threading a reference to this
            // brain through the service and back, to reach the two fields —
            // [shownTurns] and [pendingFolds] — that only exist here.
            //
            // The gate comes with it: folding is for reviews only. A live turn
            // must not fold, because folding is irreversible from the model's
            // side and a user turn is not the moment to spend it. That check
            // lives beside the state it protects rather than in the schema,
            // which is static and cannot tell one turn kind from another.
            if (host.methodOf(tc.name) == "note.fold" || tc.name == "note.fold") {
                return toolMessage(tc.id, noteFold(args))
            }
            // Same two-name translation BodyWsClient does, and the same
            // fallback: an extension registered its tool under one name and
            // that name goes straight through to the dispatcher.
            val method = host.methodOf(tc.name)
                ?: return toolMessage(tc.id, "No such tool: ${tc.name}")

            // A turn Andee started itself does not interrupt the person: no question card, no alert, no voice.
            // Whatever it wants them to see goes through a bubble (rationed, silent, fades) or the diary.
            if (currentKind == TurnKind.WORK && method in SELF_TURN_MAY_NOT) {
                return toolMessage(
                    tc.id,
                    "You woke yourself for this; nobody asked, so you do not ask or speak. If it is worth their attention, leave a " +
                        "bubble (nudge) or write it in your diary, and say it next time they talk to you.",
                )
            }
            // The list a work turn is given is a request; this is the wall. A model that names a tool it was not
            // handed (a tap, a shell command) would otherwise reach it through [BrainHost.methodOf] all the same.
            if (currentKind == TurnKind.WORK && !SelfWork.allowed(method)) {
                return toolMessage(
                    tc.id,
                    "$method is not open to a turn you started yourself: it has no screen, device or shell. Write down what you " +
                        "would do and why (wiki, diary, the care's note), or leave a bubble, and do it when the user is with you.",
                )
            }
            // A task another device handed over is done here, never handed on: see [TurnKind.PEER].
            if (method == "peer.ask" && currentKind == TurnKind.PEER) {
                return toolMessage(
                    tc.id,
                    "This turn is itself a task another of the user's devices handed you, so it cannot be handed " +
                        "on. Do what you can here, and say in your report what this device could not do.",
                )
            }

            // The wait budget. Watching somebody else's twenty-minute job by waiting and looking is the
            // most expensive thing this loop can do — every look carries a screenshot and the whole tool
            // list — and it fills the history until the conversation itself is what breaks. So the device
            // refuses, with the way out in the refusal, once the turn has waited long enough. In the
            // loop and not the prompt, for the same reason as the fold gate: a prompt is a request.
            if (method == "screen.wait") {
                val ask = args.optDouble("seconds", 2.0).coerceIn(0.0, WaitBudget.PER_CALL_MAX_S)
                WaitBudget.check(turnWaitSeconds, ask)?.let { return toolMessage(tc.id, it) }
                turnWaitSeconds += ask
            }

            val result = dispatchWithTimeout(method, args, tc.id, silent, untrusted)
            if (currentKind == TurnKind.WORK) workCalls += WorkCall(method, args.optString("action"), tc.id, landed(result))
            BrainEvent.fromTool(method, args, result)?.let(::emit)
            if (AimHints.isTap(method) && result is JSONObject) {
                val missed = result.has("screen_changed") && !result.optBoolean("screen_changed", true)
                aimMisses = if (method == "screen.tap" || method == "screen.long_press") { if (missed) aimMisses + 1 else 0 } else if (missed) aimMisses else 0
                if (locatorOffered) AimHints.afterTap(method, result, aimMisses)?.let { result.put("aim_hint", it) }
            }
            when (result) {
                is JSONObject -> {
                    extractImages(result, images)
                    result.toString()
                }
                is JSONArray -> {
                    extractImages(result, images)
                    result.toString()
                }
                null -> "ok"
                else -> result.toString()
            }
        } catch (t: Throwable) {
            host.logWarn(TAG, "tool '${tc.name}' failed: ${t.message}")
            val way = if (locatorOffered && t.message?.startsWith(AimHints.REPEAT_REFUSED) == true) AimHints.REFUSED_WAY_OUT else ""
            "Failed: ${t.message ?: t.javaClass.simpleName}$way"
        }
        return toolMessage(tc.id, body.take(MAX_TOOL_CHARS))
    }

    /** False when a handler answered that its write did not happen — `{"written": false}` and its siblings. */
    private fun landed(result: Any?): Boolean {
        val o = result as? JSONObject ?: return true
        if (o.optBoolean("timed_out")) return false
        return NOT_DONE_KEYS.none { o.has(it) && o.opt(it) == false }
    }

    private fun emit(e: BrainEvent) {
        // Inside a review: gather it for the review's one row. Reading during a review is not worth a line.
        reviewItems?.let { if (e.kind != BrainEvent.Kind.WIKI_READ) it += e; return }
        runCatching { host.event(e) }.onFailure { host.logWarn(TAG, "event ${e.kind} not shown", it) }
    }

    private fun dispatchWithTimeout(method: String, params: JSONObject, callId: String, silent: Boolean, untrusted: Boolean): Any? {
        val declared = params.optLong("timeout_ms", 0L)
        val budget = if (declared > 0) declared + TIMEOUT_SLACK_MS else DEFAULT_TOOL_TIMEOUT_MS
        val f = toolExec.submit<Any?> { host.dispatch(method, params, callId, silent, untrusted) }
        return try {
            f.get(budget, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            host.logWarn(TAG, "tool $method exceeded ${budget}ms — abandoning the wait")
            JSONObject().put("timed_out", true)
                .put("note", "This step had not returned after ${budget / 1000}s, so I stopped waiting. It may still be running in the background — read the screen before deciding the next step.")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun toolMessage(id: String, content: String): JSONObject =
        JSONObject()
            .put("role", "tool")
            .put("tool_call_id", id)
            .put("content", content)

    // ------------------------------------------------------------------
    // topics: putting a finished stretch of conversation away
    // ------------------------------------------------------------------

    /**
     * The user's turns, numbered, for the review prompt — or empty when there is
     * nothing a fold could be made of.
     *
     * The numbers are the whole interface: the model names a first and a last
     * turn and that is all it says about *where*. [shownTurns] is captured at
     * the same instant so `U3` in the prompt and index 3 there are the same
     * message. Long silences are marked between the lines as a hint — a person
     * who walked away for an hour probably came back to something else — but a
     * change of subject one minute later is just as foldable.
     */
    private fun topicCandidatesForPrompt(): String {
        val idx = TopicSplitter.userTurns(history)
        shownTurns = idx.map { history[it] }
        // One turn has nothing after it, so nothing in it can be shown to be over.
        if (idx.size < 2) return ""
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
        val sb = StringBuilder()
        for ((n, i) in idx.withIndex()) {
            val m = history[i]
            if (n > 0 && TopicSplitter.isSeam(history[idx[n - 1]], m)) {
                sb.append("   — a long silence here —\n")
            }
            val at = TopicSplitter.stampOf(m)
            val said = TopicSplitter.anchorText(m).replace('\n', ' ').take(60)
            sb.append("- U").append(n).append("  ")
                .append(if (at > 0) fmt.format(java.util.Date(at)) else "     ?     ")
                .append("  ").append(said)
            if (n == idx.size - 1) sb.append("   (the latest — a topic must end before this)")
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /**
     * Handle `note.fold`: a review saying "turns U[from]..U[to] were one topic,
     * and it is over — put it away".
     *
     * Returns the JSON the model reads, and **every refusal says which gate it
     * hit**. A review told "no" with no reason tries again next time with the
     * same input and gets the same answer, and there is nobody watching to
     * notice the loop.
     *
     * The gates are mechanical on purpose. `TOPIC_RULES` asks the model to judge
     * whether a stretch has ended; these are the half that does not depend on it
     * agreeing:
     *
     *  1. **Only the quiet-hour review may fold** — not a user turn, and not the
     *     harvest, which is silent too but is writing a summary, was never shown
     *     the turn list, and rewrites [history] itself.
     *  2. **Both numbers come from the list shown**, and the stretch is then found
     *     in [history] by the identity of those messages, so nothing that moved
     *     the indices in between can point the fold at the wrong place.
     *  3. **Some turn must come after it.** This is "another topic has started
     *     since" as arithmetic: the latest turn has nothing after it, so nothing
     *     shows that it ended. Without this the review would in time fold the
     *     conversation it is standing in.
     *  4. **Nothing in it may be still waiting** — an unanswered tool call means
     *     the stretch was interrupted, not finished.
     *  5. **It must not already be folded, in this review or an earlier one.**
     *  6. **No scene may be active.** While the user is in a scene they are, by
     *     definition, doing one thing.
     */
    private fun noteFold(args: JSONObject): String {
        fun no(reason: String) = JSONObject().put("folded", false).put("reason", reason).toString()
        if (currentKind != TurnKind.SWEEP) {
            return no("Only the quiet-hour review puts topics away; this turn is not one. Do not try again — just carry on.")
        }
        if (host.activeSceneName() != null) {
            return no("A scene is active, so this is all one piece of work. Nothing to fold.")
        }
        if (pendingFolds.size >= MAX_FOLDS_PER_REVIEW) {
            return no("That is $MAX_FOLDS_PER_REVIEW topics for this review already. Leave the rest for next time.")
        }
        val a = args.optInt("from_turn", -1)
        val b = args.optInt("to_turn", -1)
        val last = shownTurns.size - 1
        if (a !in 0..last || b !in 0..last) {
            return no("U$a..U$b is not in the turn list you were given (U0..U$last). Use numbers from that list; do not invent one.")
        }
        if (b < a) return no("to_turn ($b) comes before from_turn ($a).")
        if (b >= last) {
            return no(
                "U$b is the latest turn — nothing has started after it, so there is no way to tell this topic " +
                    "ended. A topic must end before the latest turn; leave it for a later review.",
            )
        }
        val first = shownTurns[a]
        val next = shownTurns[b + 1]
        val from = history.indexOfFirst { it === first }
        val until = history.indexOfFirst { it === next }
        if (from < 1 || until <= from) {
            return no("Those turns are no longer in the conversation, so there is nothing left to put away.")
        }
        if (history.subList(from, until).any { isFoldMarker(it) }) {
            return no("Part of U$a..U$b was already put away in an earlier review. Fold only turns that are still here.")
        }
        val clash = pendingFolds.any { p ->
            val pf = history.indexOfFirst { it === p.first }
            val pu = history.indexOfFirst { it === p.next }
            from < pu && pf < until
        }
        if (clash) return no("U$a..U$b overlaps a topic you already put away in this review.")
        if (until - from < TopicSplitter.MIN_SEGMENT_MESSAGES) {
            return no("U$a..U$b is too short to be worth putting away. Leave it.")
        }
        if (TopicSplitter.hasDanglingCall(history, from, until)) {
            return no("Something in U$a..U$b is still waiting on an answer, so it was interrupted rather than finished. Leave it.")
        }
        val title = args.optString("title").trim()
        val summary = args.optString("summary").trim()
        if (title.isEmpty() || summary.isEmpty()) return no("A topic needs both a title and a summary.")
        pendingFolds += PendingFold(
            first = first,
            next = next,
            title = title,
            summary = summary.take(MAX_TOPIC_SUMMARY_CHARS),
            text = renderTopicText(history, from, until),
        )
        return JSONObject()
            .put("folded", true)
            .put("turns", "U$a..U$b")
            .put("title", title)
            .put("note", "It goes away when this review ends. Do not fold these turns again.")
            .toString()
    }

    /** The one-line stand-in [applyPendingFolds] leaves where a topic was. */
    private fun isFoldMarker(m: JSONObject): Boolean =
        m.optString("role") == "assistant" &&
            (m.opt("content") as? String)?.startsWith(FOLD_MARKER_PREFIX) == true

    /** Every fold the review asked for, oldest request first. See [applyOneFold]. */
    private fun applyPendingFolds() {
        val folds = pendingFolds.toList()
        pendingFolds.clear()
        for (f in folds) applyOneFold(f)
        shownTurns = emptyList()
    }

    /**
     * Put away one stretch, and swap it in [history] for a marker that says
     * where it went.
     *
     * Runs after the turn is over — see [pendingFolds]. The two halves are
     * different jobs and both are needed:
     *
     *  * **[ChatHistory.foldTopic]** is the device's record: it marks the rows so
     *    the conversation list collapses them, and writes the verbatim text to
     *    its own file so `get_topic` can read it back later.
     *  * **the swap here** is the context's saving: the stretch is the most
     *    expensive thing in the request and the least likely to be needed again,
     *    so it becomes one line saying what it was and how to get it back.
     *
     * Both ends are found again by identity. **Which device rows the stretch
     * covers is anchored on the same two user sentences** — see
     * [TopicSplitter.locateRows]; the clock only breaks ties, because the
     * brain's `[now …]` is to the minute while a row's `ts` is milliseconds.
     */
    private fun applyOneFold(fold: PendingFold) {
        val from = history.indexOfFirst { it === fold.first }
        val until = history.indexOfFirst { it === fold.next }
        if (from < 1 || until <= from) {
            host.logWarn(TAG, "fold of 「${fold.title}」 finished with no live range; dropped")
            return
        }
        if (keepFrom() < until) {
            // The stretch reaches into a task stopped before it was done; putting it away would take 继续's context.
            host.logInfo(TAG, "fold of 「${fold.title}」 skipped: it reaches into an unfinished task")
            return
        }
        val firstSaid = TopicSplitter.anchorText(fold.first)
        val nextSaid = TopicSplitter.anchorText(fold.next)
        val all = host.scrollback()
        val keys = all.map {
            TopicSplitter.RowKey(
                isUser = it.isUser,
                text = it.text,
                ts = it.ts,
            )
        }
        val range = TopicSplitter.locateRows(
            keys,
            firstSaid, TopicSplitter.stampOf(fold.first),
            nextSaid, TopicSplitter.stampOf(fold.next),
        )
        val rows = range?.let { all.subList(it.first, it.last + 1) }.orEmpty()
        if (rows.isEmpty() || rows.any { it.topicId != null }) {
            host.logWarn(
                TAG,
                "fold of 「${fold.title}」 could not be matched to the device rows " +
                    "(first=${firstSaid.take(20)}, next=${nextSaid.take(20)}, rows=${rows.size}); kept as it was",
            )
            return
        }
        // Written first, and the swap happens only if it landed: a marker saying
        // "you can read this back" over a topic that was never written is worse
        // than not folding, because nothing will ever notice the difference.
        val topic = runCatching {
            host.foldTopic(
                title = fold.title,
                summary = fold.summary,
                rows = rows,
                text = fold.text,
            )
        }.getOrNull()
        if (topic != null) emit(BrainEvent(BrainEvent.Kind.FOLDED, topic.title, topic.summary.lineSequence().firstOrNull().orEmpty()))
        if (topic == null) {
            host.logWarn(TAG, "fold produced no topic; the conversation is kept as it was")
            return
        }
        val marker = JSONObject()
            .put("role", "assistant")
            .put(
                "content",
                "$FOLD_MARKER_PREFIX「${topic.title}」 (topic #${topic.id}). It is finished and has been put " +
                    "away; the back-and-forth is not in front of you any more. What it came to: " +
                    "${topic.summary} If you need the details, read it back with `get_topic` — do " +
                    "not answer about it from this summary alone, and do not say it never happened.)",
            )
        val saved = until - from
        val kept = ArrayList<JSONObject>(history.size - saved + 1)
        kept.addAll(history.subList(0, from))
        kept.add(marker)
        kept.addAll(history.subList(until, history.size))
        history.clear()
        history.addAll(kept)
        // The image tracking lists hold references to messages that are gone —
        // the same cleanup [applyHarvest] does, for the same reason: a stale
        // entry would later make [trimImages] drop a live message.
        imageMessages.retainAll { m -> history.any { it === m } }
        photoMessages.retainAll { m -> history.any { it === m } }
        if (internalFromIndex > until) internalFromIndex -= (saved - 1)
        host.logInfo(TAG, "topic put away: 「${topic.title}」 — $saved msgs, ${rows.size} rows → 1 marker, history now ${history.size}")
    }

    // ------------------------------------------------------------------
    // images
    // ------------------------------------------------------------------

    /**
     * Pull every `png_base64` out of a tool result, in place.
     *
     * Recursive because images are not only at the top level: `screen.tap_id`
     * returns one under `after_shot` when it lands and `miss_shot` when it
     * doesn't, and `screen.ui_tree` attaches one of its own when the element
     * list comes back empty. A non-recursive scan would leave those inside the
     * `tool` message, where a megabyte of base64 is charged as text and read by
     * no vision model at all.
     */
    private fun extractImages(node: Any?, out: MutableList<String>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys().asSequence().toList()
                for (k in keys) {
                    val v = node.opt(k)
                    if (k == "png_base64" && v is String && v.length > 64) {
                        out += v
                        node.put(k, "<image attached>")
                    } else {
                        extractImages(v, out)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) extractImages(node.opt(i), out)
        }
    }

    /**
     * Append the images as one vision message.
     *
     * A separate `user` message rather than part of the `tool` message, because
     * the OpenAI shape only allows text there — the same thing the cloud does
     * in `_attach_tool_images`, minus the trip through S3.
     */
    private fun attachImages(images: List<String>) {
        val content = JSONArray()
        content.put(LlmClient.textBlock("The screenshot the previous tool returned:"))
        for (b64 in images.takeLast(MAX_IMAGES_PER_STEP)) {
            content.put(LlmClient.imageBlock(b64))
        }
        val msg = JSONObject().put("role", "user").put("content", content)
        history += msg
        imageMessages += msg
        trimImages()
    }

    /**
     * The user's own photos, as a vision message right behind their words.
     *
     * Behind rather than inside: the words stay a plain-string `user` message,
     * which is what [isBoundary] cuts at, and this one is then dropped together
     * with them by [trimHistory]. Only the newest batch survives — a photo the
     * user sent ten minutes ago is not worth re-uploading on every request.
     */
    private fun attachUserPhotos(images: List<String>) {
        val content = JSONArray()
        // "这是什么" next to a photo is exactly the trigger `camera_turn`'s
        // description names, so say outright that the seeing is already done.
        content.put(
            LlmClient.textBlock(
                "The ${images.size} photo(s) the user attached to the message above. " +
                    "You can already see them here — \"这个 / this\" means these photos. " +
                    "Do NOT open the camera or take a screenshot to look at them.",
            ),
        )
        for (b64 in images) content.put(LlmClient.imageBlock(b64, "image/jpeg"))
        val msg = JSONObject().put("role", "user").put("content", content)
        history += msg
        photoMessages += msg
        while (photoMessages.size > MAX_PHOTO_MESSAGES) history.remove(photoMessages.removeAt(0))
    }

    /**
     * Keep only the newest [MAX_IMAGE_MESSAGES] image messages, dropping the
     * older ones from the history entirely.
     *
     * The cost is real and accepted: deleting a message invalidates DeepSeek's
     * prefix cache from that point on, and the tool descriptions ahead of it
     * are the largest constant in the request. Blowing the context window is
     * worse — every successful tap ships a full-screen PNG.
     */
    private fun trimImages() {
        while (imageMessages.size > MAX_IMAGE_MESSAGES) {
            val stale = imageMessages.removeAt(0)
            // Identity removal: JSONObject doesn't override equals.
            history.remove(stale)
        }
    }

    // ------------------------------------------------------------------
    // history
    // ------------------------------------------------------------------

    /**
     * The system message for this turn: the standing prompt, plus the manner of
     * whichever character the ball is currently wearing.
     *
     * Read from the preference through [net.kuafuai.andee.ui.ball.BallLooks.byName]
     * rather than from `BallLooks.active`, because `active` is the GL thread's
     * latched value and only exists once the ball has been built — this runs on
     * the loop thread and can run before there is a ball at all. The preference
     * is written the instant the user swipes, so it is never behind the screen.
     *
     * The active scene rides in the same message for the same reason, and
     * [reseatPersona] picks up entering or leaving one without knowing scenes
     * exist. A pref naming a scene that was deleted reads as no scene.
     */
    private fun systemPrompt(): String = LocalPrompt.withPersona(
        host.persona(),
        host.activeSceneName()?.let { host.scene(it) },
        host.platformPrompt(),
    )

    /**
     * The user swiped to a different character mid-conversation; make the model
     * read as that one from here on.
     *
     * **Rewriting `history[0]` rather than restarting the brain**, which is the
     * cheaper-looking option and the wrong one: a restart drops the
     * conversation, and someone who changes the ball's face has not asked to be
     * forgotten. The cost is one prefix-cache miss on the next request —
     * everything behind the system message is invalidated with it — and that is
     * the *opposite* trade from the clock and the notebook index, which ride on
     * the user message precisely to avoid this. Both are right: those change
     * every turn, this changes when a finger moves.
     *
     * Compares the whole rendered string rather than tracking the look name, so
     * there is no second piece of state to get out of step — editing a persona
     * during development takes effect on the next turn for the same reason.
     * Silent for the overwhelmingly common case of nothing having changed.
     */
    private fun reseatPersona() {
        val want = systemPrompt()
        val head = history[0]
        if (head.optString("role") != "system" || head.optString("content") == want) return
        head.put("content", want)
        host.logInfo(TAG, "persona or scene changed — system prompt rewritten, prefix cache dropped once")
    }

    /**
     * Drop whole turns off the front when the history gets long.
     *
     * Turn-at-a-time, never message-at-a-time: an orphaned `tool` message whose
     * assistant message is gone is a 400 from the API, and one that arrives
     * mid-task takes the task with it. The system message at index 0 stays.
     */
    private fun trimHistory() {
        val before = history.size
        if (before <= MAX_HISTORY) return
        for (gone in HistoryTrim.dropIndexes(history.map { isBoundary(it) }, MAX_HISTORY, keepFrom()).sortedDescending()) drop(gone)
        host.logInfo(TAG, "history trimmed to ${history.size} msgs")
    }

    private fun drop(index: Int) {
        val gone = history.removeAt(index)
        imageMessages.remove(gone)
        photoMessages.remove(gone)
    }

    // ------------------------------------------------------------------
    // harvest: review before dropping
    // ------------------------------------------------------------------

    /**
     * Characters of conversation currently in the request.
     *
     * [MAX_HISTORY] counts messages, which is the wrong unit on a device where
     * one tool result may be 24 000 characters: sixty of those is tens of
     * thousands of tokens, sixty lines of chit-chat is a few hundred. Both
     * limits apply, and whichever trips first starts a harvest.
     */
    private fun historyChars(): Int {
        var n = 0
        for (m in history) {
            val c = m.opt("content")
            n += when (c) {
                is String -> c.length
                is JSONArray -> (0 until c.length()).sumOf { i ->
                    c.optJSONObject(i)?.optString("text")?.length ?: 0
                }
                else -> 0
            }
        }
        return n
    }

    /**
     * Past the budget? Then the conversation is **reviewed before any of it is
     * dropped**, and the review's summary takes its place.
     *
     * This is the whole answer to "the context is finite". [trimHistory] alone
     * throws turns away and they are gone; the cloud avoids that by
     * consolidating old messages into an Anchor with an LLM call, and this is
     * the same idea in one message instead of a structured merge — the
     * conversation is small enough that a paragraph of "what happened, where
     * it got to, what's next" carries everything the next turn needs.
     *
     * The two outcomes of one call: the notebook gets whatever was worth
     * keeping ([REVIEW_RULES]) and the context gets a summary. Asking for both
     * at once is why this costs no more than the sweep would have.
     */
    private fun maybeHarvest() {
        if (cfg.apiKey.isEmpty()) return
        if (history.size <= MAX_HISTORY && historyChars() <= MAX_HISTORY_CHARS) return
        if (history.size == lastHarvestAtSize) {
            // Already tried at this size — a tail that cannot shrink (one huge
            // message, say) must not become an infinite review loop.
            host.logInfo(TAG, "harvest skipped: nothing changed since the last one")
            return
        }
        // Never into a task the user may still say 继续 to — see [Unfinished].
        val cut = minOf(boundaryAtOrBefore(history.size - KEEP_TAIL_MESSAGES), keepFrom())
        if (cut < MIN_HARVEST_MESSAGES) {
            host.logInfo(TAG, "harvest skipped: only ${cut - 1} messages old enough to fold in")
            return
        }
        host.logInfo(
            TAG,
            "harvest: ${history.size} msgs / ${historyChars()} chars over budget — " +
                "reviewing before trimming",
        )
        lastHarvestAtSize = history.size
        launch(if (justSwept) HARVEST_SUMMARY_ONLY else HARVEST_INSTRUCTION, TurnKind.HARVEST)
    }

    /**
     * Throw away a review turn's own messages when the review did not finish.
     *
     * The harvest appends its prompt to [history] before calling the model — see
     * the `internal` branch in [runTurn]. On success [applyHarvest] removes that
     * scaffolding along with the folded conversation. On failure nothing did, so
     * the history kept one extra message per attempt.
     *
     * That growth is exactly what made a failed review re-fire forever:
     * [maybeHarvest] skips a repeat by comparing `history.size` against the size
     * it last fired at, and one extra message per attempt deflected that check
     * every single time. Measured on a device: 218 attempts about 5 s apart,
     * until the app was reinstalled. Dropping the scaffolding puts the size back
     * where it was, the guard holds, and the next turn is an ordinary one.
     *
     * Only [TurnKind.HARVEST]: [applyHarvest] is the only consumer of
     * [internalFromIndex], and the sweep never calls it — clearing a *successful*
     * sweep's messages is not this change's business.
     */
    private fun dropUnfinishedHarvest(kind: TurnKind) {
        if (kind != TurnKind.HARVEST) return
        val from = internalFromIndex
        if (from < 0 || from >= history.size) return
        host.logInfo(TAG, "harvest did not finish; dropping ${history.size - from} scaffolding message(s)")
        history.subList(from, history.size).clear()
        internalFromIndex = -1
    }

    /**
     * Swap the reviewed part of the conversation for the summary.
     *
     * Keeps: the system prompt, the summary, and the newest [KEEP_TAIL_MESSAGES]
     * messages of the pre-review history. Drops: everything between the system
     * prompt and that tail, plus the review turn's own messages (its prompt,
     * its tool calls, its narration) — those were scaffolding, and the notebook
     * writes they produced are already durable.
     */
    private fun applyHarvest(summaryRaw: String) {
        val from = internalFromIndex
        internalFromIndex = -1
        if (from < 0 || from > history.size) {
            host.logWarn(TAG, "harvest finished without a usable start index")
            return
        }
        val summary = summaryRaw.trim().take(MAX_INTERNAL_NOTE_CHARS)
        if (summary.isEmpty()) {
            // Keeping the long conversation beats replacing it with nothing.
            host.logWarn(TAG, "harvest produced no summary; nothing dropped")
            return
        }
        val keepStart = minOf(boundaryAtOrBefore(from - KEEP_TAIL_MESSAGES), keepFrom())
        if (keepStart < 2) {
            host.logInfo(TAG, "harvest has nothing old enough to replace; kept as is")
            return
        }
        val kept = ArrayList<JSONObject>(history.size - keepStart + 2)
        kept.add(history[0])
        kept.add(
            JSONObject().put("role", "assistant")
                .put("content", "$HARVEST_PREFIX$summary"),
        )
        for (i in keepStart until from) kept.add(history[i])

        val dropped = from - keepStart
        history.clear()
        history.addAll(kept)
        // Newest-first tracking of image messages must forget the ones that no
        // longer exist, or [trimImages] would later try to remove an object
        // twice and drop a live message with it.
        imageMessages.retainAll { msg -> history.any { it === msg } }
        photoMessages.retainAll { msg -> history.any { it === msg } }
        lastHarvestAtSize = history.size
        host.logInfo(
            TAG,
            "harvest done: ${dropped} msgs folded into a ${summary.length}-char summary, " +
                "history now ${history.size} msgs / ${historyChars()} chars",
        )
    }

    /**
     * First index that may start the kept tail: walks back from [upto] to a
     * plain `user` message.
     *
     * The boundary matters for the same reason [trimHistory] insists on whole
     * turns — a `tool` message whose `assistant` parent is gone is a 400 from
     * the API, and it arrives mid-conversation where it takes the session with
     * it.
     */
    private fun boundaryAtOrBefore(upto: Int): Int {
        var i = upto.coerceAtLeast(1).coerceAtMost(history.size - 1)
        while (i > 1 && !isBoundary(history[i])) i--
        return i
    }

    private fun isBoundary(msg: JSONObject): Boolean =
        msg.optString("role") == "user" && msg.opt("content") is String

    // ------------------------------------------------------------------
    // narration
    // ------------------------------------------------------------------

    /**
     * The subtitle line for a tool the model called without saying anything.
     *
     * Same job as the cloud's `BodyRemoteTool.execute` progress line: the model
     * narrates most turns, but on the ones it doesn't, the user is looking at a
     * ball that has gone quiet in the middle of a task.
     *
     * Every branch but the last is a resource: this text goes to
     * `FloatingWindowUi.setSubtitle`, which is chrome. The tool *name* in the
     * fallback is an identifier and is passed through as it is, which is also
     * why it reads oddly in an English interface and does so on purpose — it
     * matches what a log line or a tool error would call the same thing.
     */
    private fun narrate(toolName: String): String = when {
        toolName.startsWith("get_screen") -> host.text(BrainText.NARRATE_GET_SCREEN)
        toolName == "take_screenshot" || toolName == "zoom_screen_region" ->
            host.text(BrainText.NARRATE_SCREENSHOT)
        toolName.startsWith("tap_") -> host.text(BrainText.NARRATE_TAP)
        toolName.startsWith("long_press") -> host.text(BrainText.NARRATE_LONG_PRESS)
        toolName.startsWith("swipe") -> host.text(BrainText.NARRATE_SWIPE)
        toolName == "type_text" || toolName == "fill_secret" ->
            host.text(BrainText.NARRATE_TYPE)
        toolName == "submit_input" -> host.text(BrainText.NARRATE_SUBMIT)
        toolName == "wait_for_screen" -> host.text(BrainText.NARRATE_WAIT)
        toolName == "launch_app" || toolName == "open_url" ->
            host.text(BrainText.NARRATE_LAUNCH)
        toolName == "system_action" -> host.text(BrainText.NARRATE_SYSTEM)
        toolName == "show_html" -> host.text(BrainText.NARRATE_SHOW_HTML)
        toolName == "list_pages" || toolName == "get_page" ->
            host.text(BrainText.NARRATE_PAGES)
        toolName == "get_topic" || toolName == "list_topics" ->
            host.text(BrainText.NARRATE_TOPICS)
        toolName == "camera_turn" -> host.text(BrainText.NARRATE_CAMERA)
        toolName.startsWith("dog_") -> host.text(BrainText.NARRATE_DOG)
        toolName == "market_search" -> host.text(BrainText.NARRATE_MARKET_SEARCH)
        toolName == "market_call" -> host.text(BrainText.NARRATE_MARKET_CALL)
        else -> host.text(BrainText.NARRATE_DEFAULT, toolName)
    }

    /**
     * The clock that rides in on every user message.
     *
     * Follows the device's interface language instead of being pinned to
     * `Locale.CHINA`. An English interface that stamps its own messages "周一"
     * feeds the model a Chinese signal on every single turn — which is exactly
     * the pull [LocalPrompt] §9 is written to resist, and it would be fighting
     * the very rule sitting next to it. The instant is the same either way;
     * only the weekday and the field order move.
     */
    private fun stamp(): String =
        SimpleDateFormat(
            "yyyy-MM-dd EEE HH:mm",
            host.locale(),
        ).format(Date())

    /**
     * The interface language, tagged the way a model reads a language tag:
     * `zh`, `en`.
     *
     * Handed over on the user message rather than interpolated into
     * [LocalPrompt.TEXT] — see the note on constness in its KDoc. This is the
     * value §9 tells the model to fall back on when there is no user utterance
     * to follow, so it must be somewhere the model actually looks, and the
     * bracket at the head of the message is that place.
     *
     * **On the turns where there *is* an utterance it is not sent at all**, and
     * its absence is itself the signal — see the call site for why an inline
     * `ui-language en` beat §9's own precedence rule and answered a Chinese
     * user in English.
     *
     * Empty when the tag is unknown, which `forLanguageTag` can return for a
     * malformed preference; the fallback matches `values/`, the file an
     * unmatched locale resolves to anyway.
     */
    private fun uiLangTag(): String =
        host.locale().language.ifEmpty { "zh" }

    companion object {
        private const val TAG = "Brain"

        // The per-turn step cap moved to [VoiceConfig.maxSteps] / "max_steps"
        // pref so a user who wants longer tasks can raise it from the settings
        // card. See the runTurn loop and [VoiceConfig.DEFAULT_MAX_STEPS].

        /** Lower than the cloud's 10: a local round costs the user a minute. */
        private const val MAX_REPEATS = 3

        private const val MAX_HISTORY = 60

        /** The keys a handler uses to say its write did or did not happen (`LifeTools`, the notebook). */
        private val NOT_DONE_KEYS = listOf("written", "added", "changed", "saved", "noted", "linked", "shared", "logged", "settled", "ok", "performed")

        /** What a self-started turn may not call: anything that speaks to or stops the person. */
        private val SELF_TURN_MAY_NOT = setOf("ui.ask", "ui.alert", "tts.speak")

        /** How long a task stopped at the step limit is kept ready for 继续. See [Unfinished]. */
        private const val UNFINISHED_TTL_MS = 60 * 60_000L

        /**
         * The other half of the context budget. See [historyChars] — 60
         * messages is a lot of chit-chat and very little screenshot, so the
         * character count is what actually protects the window.
         */
        private const val MAX_HISTORY_CHARS = 60_000

        /** Messages left untouched at the tail when a harvest folds the rest in. */
        private const val KEEP_TAIL_MESSAGES = 16

        /** Below this many foldable messages a harvest is not worth a call. */
        private const val MIN_HARVEST_MESSAGES = 8

        /** A conversation shorter than this has nothing to review. */
        private const val MIN_MESSAGES_TO_REVIEW = 6

        /**
         * How much of the persisted scrollback a post-restart sweep rebuilds.
         * The sweep is about the conversation that just went quiet, so the tail
         * is enough; anything older is the notebook's business, not a context
         * window to re-fill.
         */
        private const val REBUILD_MAX_MESSAGES = 40

        /** Caps what a silent turn leaves behind in the history. */
        private const val MAX_INTERNAL_NOTE_CHARS = 900

        /** Marks the message that stands in for the conversation it replaced. */
        private const val HARVEST_PREFIX = "(wrap-up summary of the conversation before this)"

        private const val MAX_IMAGE_MESSAGES = 2
        private const val MAX_IMAGES_PER_STEP = 2
        private const val MAX_PHOTO_MESSAGES = 1
        private const val MAX_TOOL_CHARS = 24_000

        /**
         * What a silent turn is for.
         *
         * Shared by the quiet-hour sweep and the pre-trim harvest: both are
         * "look back over this conversation and put what is worth keeping into
         * the notebook". The difference between them is only what happens to
         * the conversation afterwards — the sweep leaves it alone, the harvest
         * replaces the old part with the summary.
         */
        private const val REVIEW_RULES = """1. A preference / a taboo / a worry / a change in circumstances he said out loud this time → remember(type=user)
2. Something you promised but have not yet recorded → remember(type=feedback) + todo
3. Something dropped half-way (asked but not booked, said he would think about it and nobody followed up) → todo, to arrange one follow-up
4. Something you actually got done for him this time, a route you walked through → remember(type=way), with the task name as name.
   Record only what still holds once the interface changes: which route you took, which route is dead, what you needed from the user on the way, how you could tell it worked.
   No coordinates, not this time's verification code, not this time's order number.
5. Step back and look at this person: what state he is in, what you want to do next, whether your read on him has changed.
   When it has, remember under the same name to overwrite that entry. This one is a judgement left for future you, not a log.
6. A kind of session that has now come up more than once and will come again (practising a language, minding his messages, comparing prices) and is not a scene yet → remember(type=feedback) "propose saving <kind> as a scene next time we do it". Do NOT call save_scene here: scenes are saved only after he says yes (§11), and he is not here.
7. You used an installed app this time and learned what it is for, what it can do on his behalf, or a limit you hit → note_gear (only what you actually saw; a login only if you saw it signed in). If a note on the shelf turned out wrong this time, correct it. Never touch gear_visibility here: what is kept out of view is his call, made when he is present.
8. The plan (your plan line; day_plan get for detail). Keep today's plan true: if there is none, write one (day_plan write, date=today); if what happened in this conversation changed it, rewrite it; after 20:00, also draft tomorrow's — and before you do, read today's outcomes (day_plan get): keep what landed, do less of or move what he ignored or declined, carry over what was moved. If your plan line says the weekly look at the directions is due, do it now: plan_directions list, then mark done what is done, pause what has stalled, and add what he newly said he is working toward. Every item points at a long-term direction or a todo in `because`; at most 5 that interrupt (remind/ask), at the times you would really choose; a quiet day is a fine plan, and `quiet` says what you will leave alone today.
   Long-term directions (plan_directions add) come only from what he said or did — quote it in `source`; a goal you think would be good for him is not his. With no directions yet, one `ask` item a day may be getting to know him (what he is busy with lately, what he would like you to keep an eye on — in his language), until he has answered or ignored it twice. Never put back what he removed.
9. Your own record ("Your own life" in the prompt). Something he handed you to look after this time that is not among your cares → cares add, his words in `source` (do not ask the three intake questions here — he is not present; leave them for next time in the care's note). A care this conversation moved → cares update with a one-line note. Someone you dealt with other than him → person find, then upsert or log. Something long you worked out for him that should be findable later → wiki_write (wiki_search first; revise rather than duplicate). After 20:00, if tonight's diary page is not written yet → diary write, in your own voice and only about what really happened. If who you are or what you are busy with has changed → self_note write. If you have never written yourself down (self_note get comes back empty) and you have by now been through some real things with him — something he handed you, a few conversations that told you what you are here for — write the first version: who you are here, what you are busy with, what you currently judge, what you promised; only what really happened.

If this conversation genuinely produced nothing new, record nothing — ending empty-handed is allowed. Forcing one in just dirties the notebook."""

        /**
         * Putting finished topics away. Appended to the sweep only — a harvest
         * already replaces what it folds, so it has nothing to add here.
         *
         * **The model marks the topics; the device only offers the places they
         * can start and end** — the user's turns, numbered. A topic change one
         * minute later is as foldable as one after an hour, which is what the
         * gap-only version could not do and why it looked broken on the tablet.
         *
         * The latest turn can never be inside a fold, since nothing has started
         * after it. Folding is therefore always a little behind the
         * conversation, and that is the design rather than a delay to tune away.
         * See [noteFold] for the mechanical half.
         */
        private const val TOPIC_RULES = """

PUTTING FINISHED TOPICS AWAY — the user's turns in this conversation, numbered:
%s

A conversation often holds several topics: he had you build a timer, then asked about the weather, then about a trip.
A topic that is clearly over is worth putting away: it stops costing room, and it can be read back word for word later.
Decide where each topic begins and ends, and for each one that is finished call `fold_topic` with:
- `from_turn` / `to_turn` — the first and the last U-number of that topic (inclusive). Use the numbers above;
  everything you and the tools said in between goes with them.
- `title` — what it was about, in his words where you have them. 「番茄钟」「三款扫地机器人比价」, not 「用户询问产品信息」.
- `summary` — four short lines, and the last one is not optional:
    What it was about / where it got to / how it ended / **what you have left out**.
  The last line is the door: it tells future-you the summary is not the whole story, so a question about
  a detail gets answered by reading it back with `get_topic` instead of from this paragraph.

Fold a topic when:
- the subject clearly CHANGED after it — a different request, not a follow-up to the same one
  (「做个番茄钟」→「改好看点」 is ONE topic; 「做个番茄钟」→「今天天气怎么样」 is two);
- and nothing in it is still hanging — an unanswered question, a request you did not finish.

Never fold the latest turn: nothing has started after it, so you cannot tell it is over.
When unsure whether something is finished, leave it — leaving it costs a little room, folding something live loses it.
Up to three topics per review. If `fold_topic` refuses, the reason names the condition; do not retry the same numbers."""

        /** The quiet-hour review. Fired by [Scheduler.armSweepIn]. */
        private const val SWEEP_INSTRUCTION =
            "(to yourself · this conversation has been quiet for a while, the user has probably gone)\n" +
                "You are not facing the user now, and nobody is watching you. Go back over the conversation above, put only what should be kept into the notebook, and stop.\n" +
                "Do not reply to the user, make no sound, do not touch the device — touching the device will be refused.\n\n" +
                REVIEW_RULES

        /**
         * The pre-trim review. Same job as the sweep, plus the summary that
         * takes the place of everything about to be dropped.
         */
        private const val HARVEST_INSTRUCTION =
            "(to yourself · the context is nearly full, time to wrap up)\n" +
                "You are not facing the user now, and nobody is watching you. Go back over the conversation above, and put only what should be kept into the notebook.\n" +
                "Do not reply to the user, make no sound, do not touch the device — touching the device will be refused.\n\n" +
                REVIEW_RULES +
                "\n\n7. The last step (in this turn's final message) is a summary of no more than 300 words: what has happened so far, " +
                "how far it got, what the next step is, and whether anything is hanging without an answer.\n" +
                "The conversation above is about to be deleted from the context, leaving only your summary, so do not write a log — " +
                "write \"what future-you must know in order to take over\".\n" +
                "Finish items 1-6 first, and write that summary last."

        /** [HARVEST_INSTRUCTION] right after a sweep: the notebook is current, so only the summary is asked for. */
        private const val HARVEST_SUMMARY_ONLY =
            "(to yourself · the context is nearly full, time to wrap up)\n" +
                "You are not facing the user now, and nobody is watching you. You reviewed this conversation a moment ago and the " +
                "notebook is up to date, so write nothing to it now.\n" +
                "Do not reply to the user, make no sound, do not touch the device.\n\n" +
                "Write only a summary of no more than 300 words: what has happened so far, how far it got, what the next step is, " +
                "and whether anything is hanging without an answer. The conversation above is about to be deleted from the " +
                "context, leaving only your summary, so write \"what future-you must know in order to take over\"."

        /** How long a topic summary may be. Long enough to carry "where it got to". */
        private const val MAX_TOPIC_SUMMARY_CHARS = 1200

        /** See [noteFold]: enough to tidy a long morning, few enough to read. */
        private const val MAX_FOLDS_PER_REVIEW = 3

        /** How the stand-in for a folded topic begins; see [isFoldMarker]. */
        private const val FOLD_MARKER_PREFIX = "(earlier in this conversation — "

        private const val DEFAULT_TOOL_TIMEOUT_MS = 60_000L

        private const val TIMEOUT_SLACK_MS = 10_000L
    }
}
