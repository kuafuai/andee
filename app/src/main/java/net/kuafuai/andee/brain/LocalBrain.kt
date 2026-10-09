package net.kuafuai.andee.brain

import android.util.Log
import net.kuafuai.andee.R
import net.kuafuai.andee.config.Notebook
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.market.MarketTools
import net.kuafuai.andee.net.CallExtensionRegistry
import net.kuafuai.andee.net.CommandDispatcher
import net.kuafuai.andee.net.ToolSchemas
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
 * `ScreenBodyService.startBrain`.
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
    private val cfg: VoiceConfig.Companion.BrainConfig,
    /**
     * The notebook's context. This class reads and writes memories on every
     * turn, so it needs one of its own rather than reaching through the
     * dispatcher, which deliberately knows nothing about the brain.
     */
    private val appContext: android.content.Context,
    private val dispatcher: CommandDispatcher,
    private val registry: CallExtensionRegistry,
    /**
     * The CodeFlying market, as a second external tool source beside the call
     * extension. Empty outside CodeFlying mode, so a local or hub brain sees the
     * same tool list it always did — the gate lives in [MarketTools] rather than
     * here, because "which backend is this" is the service's question, not the
     * brain's.
     */
    private val market: MarketTools,
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
     * running total — see `ScreenBodyService`. Only [TurnKind.SWEEP] is
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

    private val loop = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LocalBrain-loop").apply { isDaemon = true }
    }

    init {
        BrainTrace.init(appContext)
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
    private val pendingWakes = ArrayDeque<Pair<String, TurnKind>>()

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

        /** The quiet-hour review. Silent; notebook tools only. */
        SWEEP,

        /** The pre-trim review: same as [SWEEP], plus a summary that replaces messages. */
        HARVEST,
        ;

        val silent: Boolean get() = this == SWEEP || this == HARVEST

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

    /**
     * Loop thread only. Where the current silent turn's own messages begin, or
     * -1. A harvest sets it before sending its prompt and throws everything
     * from there on away again once the summary has been read out — its own
     * scaffolding must not fossilise into the conversation.
     */
    private var internalFromIndex = -1

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
            onFinal(AppLocale.str(appContext, R.string.brain_no_key))
            return
        }
        // The quiet-hour timer measures silence from the user's last words, so
        // every real utterance stamps it. Wake-ups and sweeps do not: a sweep
        // must not look like the user spoke, or it would keep pushing its own
        // trigger out.
        net.kuafuai.andee.config.Notebook.touchActivity(appContext)
        launch(text, TurnKind.USER, images)
    }

    /**
     * Start a turn nobody just asked for: a todo came due.
     *
     * Deferred rather than interrupting. See [busy] — the alternative is
     * cancelling a turn the user is in the middle of, which is exactly the
     * thing [cancel] is for and the user did not press it.
     */
    fun wake(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        loop.execute {
            if (busy.get()) {
                Log.i(TAG, "wake queued behind a running turn: ${t.take(60)}")
                pendingWakes.addLast(t to TurnKind.WAKE)
                return@execute
            }
            launch(t, TurnKind.WAKE)
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
                Log.i(TAG, "notification queued behind a running turn: ${t.take(60)}")
                pendingWakes.addLast(t to TurnKind.NOTIFY)
                return@execute
            }
            launch(t, TurnKind.NOTIFY)
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
            when {
                cfg.apiKey.isEmpty() -> Log.i(TAG, "sweep skipped: no API key")
                busy.get() -> Log.i(TAG, "sweep skipped: a turn is running")
                history.size < MIN_MESSAGES_TO_REVIEW ->
                    Log.i(TAG, "sweep skipped: only ${history.size} messages to review")
                else -> launch(SWEEP_INSTRUCTION, TurnKind.SWEEP)
            }
        }
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
    private fun launch(text: String, kind: TurnKind, images: List<String> = emptyList()) {
        val gen = generation.incrementAndGet()
        loop.execute {
            busy.set(true)
            currentKind = kind
            turnTokens = 0
            try {
                BrainTrace.turn(gen, kind.name, text)
                runTurn(text, gen, kind, images)
            } catch (t: Throwable) {
                BrainTrace.end(gen, "threw: ${t.message ?: t.javaClass.simpleName}")
                if (gen == generation.get()) {
                    Log.e(TAG, "turn $gen ($kind) failed", t)
                    // A silent turn has nobody to apologise to; it just ends.
                    if (!kind.silent) {
                        onFinal(
                            AppLocale.str(
                                appContext,
                                R.string.brain_error,
                                t.message ?: t.javaClass.simpleName,
                            )
                        )
                    }
                } else {
                    // Almost always the cancelled call's own IOException.
                    Log.i(TAG, "turn $gen abandoned: ${t.message}")
                }
            } finally {
                // Cleared here, and not inside runTurn, because runTurn has a
                // dozen `return`s: leaving the kind set on any of them would
                // make the *next* turn refuse every device tool.
                currentKind = TurnKind.USER
                busy.set(false)
                dropUnfinishedHarvest(kind)
                if (kind == TurnKind.SWEEP) onSweepSpent(turnTokens)
                if (gen == generation.get()) {
                    drainWakes()
                    maybeHarvest()
                    onTurnEnd()
                }
            }
        }
    }

    /** Run one queued turn, if any. Loop thread only. */
    private fun drainWakes() {
        val (next, kind) = pendingWakes.removeFirstOrNull() ?: return
        Log.i(TAG, "running a queued $kind turn: ${next.take(60)}")
        launch(next, kind)
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
            Log.i(TAG, "note: $t")
        }
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
        if (internal) internalFromIndex = history.size
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
            val index = net.kuafuai.andee.config.Notebook.indexForPrompt(appContext)
            val scenes = Notebook.scenesForPrompt(appContext)
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
                        // Same position and same reason as the notebook index:
                        // a newly saved scene must not cost the prefix cache.
                        // Only the scene you are *in* sits in the system prompt.
                        if (scenes.isEmpty()) {
                            ""
                        } else {
                            "\n\n(scenes you can enter — see §11; you are in: ${VoiceConfig.activeScene(appContext) ?: "none"})\n$scenes"
                        },
                )
        }

        if (!internal && images.isNotEmpty()) attachUserPhotos(images)

        val tools = LlmClient.toolSpecs(ToolSchemas.all(), registry.tools(), market.tools())
        var lastSignature = ""
        var repeats = 0

        // Read once per turn so the setting can change without a brain
        // restart — see [VoiceConfig.maxSteps]. Values outside 1..200 are
        // clamped by the reader; a 0 saved by hand would otherwise refuse
        // every turn before it started.
        val maxSteps = net.kuafuai.andee.config.VoiceConfig.maxSteps(appContext)
        for (step in 1..maxSteps) {
            if (gen != generation.get()) return
            // `enter_scene` mid-turn should change how the rest of this turn
            // reads, not only the next one. A string compare, free when nothing
            // moved.
            if (step > 1) reseatPersona()
            val reply = llm.complete(history, tools)
            if (gen != generation.get()) return
            turnTokens += reply.promptTokens + reply.completionTokens

            if (reply.reasoning.isNotBlank()) {
                Log.d(TAG, "think[$step]: ${reply.reasoning.take(500)}")
            }
            Log.i(
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
                } else if (!internal) {
                    // A silent turn has nobody to answer; whatever it wrote
                    // stays in the history as its own note and that is all.
                    onFinal(
                        answer.ifEmpty {
                            // A tool-call-free reply with no prose is the model
                            // ending the turn with nothing in it. Saying so beats
                            // the ball going dark with no explanation.
                            AppLocale.str(appContext, R.string.brain_no_result)
                        },
                    )
                }
                return
            }

            if (reply.content.isNotBlank() && !internal) onProgress(reply.content.trim())

            val images = mutableListOf<String>()
            for (tc in reply.toolCalls) {
                if (gen != generation.get()) return

                // The repeated-call guard, which is *not* the one in
                // ScreenController: that one counts taps at a screen location,
                // this one counts identical tool invocations. A model stuck in
                // `wait_for_screen(3)` forever trips neither of the others.
                val sig = tc.signature()
                repeats = if (sig == lastSignature) repeats + 1 else 0
                lastSignature = sig
                if (repeats >= MAX_REPEATS) {
                    Log.w(TAG, "same call $MAX_REPEATS× in a row: $sig")
                    BrainTrace.end(gen, "same call $MAX_REPEATS× in a row: ${tc.name}")
                    if (!internal) {
                        onFinal(
                            AppLocale.str(
                                appContext,
                                R.string.brain_repeat_stop,
                                tc.name,
                            )
                        )
                    }
                    return
                }

                if (reply.content.isBlank() && !internal) onProgress(narrate(tc.name))
                val t0 = android.os.SystemClock.uptimeMillis()
                val msg = runTool(tc, images, internal, kind.untrusted)
                BrainTrace.tool(
                    gen, tc.name, ToolSchemas.methodOf(tc.name), tc.argumentsRaw,
                    msg.optString("content"), android.os.SystemClock.uptimeMillis() - t0,
                )
                history += msg
            }

            if (images.isNotEmpty()) attachImages(images)
        }

        Log.w(TAG, "hit max steps ($maxSteps)")
        BrainTrace.end(gen, "hit max steps ($maxSteps)")
        if (!internal) {
            onFinal(
                AppLocale.str(
                    appContext,
                    R.string.brain_max_steps,
                    maxSteps,
                )
            )
        }
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
            // Same two-name translation BodyWsClient does, and the same
            // fallback: an extension registered its tool under one name and
            // that name goes straight through to the dispatcher.
            val builtin = ToolSchemas.methodOf(tc.name)
            val method = builtin
                ?: tc.name.takeIf { registry.hasMethod(it) }
                ?: tc.name.takeIf { market.hasMethod(it) }
                ?: return toolMessage(tc.id, "No such tool: ${tc.name}")

            val result = dispatchWithTimeout(method, args, silent, untrusted)
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
            Log.w(TAG, "tool '${tc.name}' failed: ${t.message}")
            "Failed: ${t.message ?: t.javaClass.simpleName}"
        }
        return toolMessage(tc.id, body.take(MAX_TOOL_CHARS))
    }

    /**
     * [CommandDispatcher.dispatch] with a deadline.
     *
     * The deadline is derived from the tool's own `timeout_ms` where it has one
     * (`ui.ask` and `ui.alert` both take it) plus slack, so a tool the model
     * deliberately asked to wait a long time is allowed to. The future is
     * **not** cancelled on timeout: interrupting a card mid-flight would leave
     * a hold on screen that nobody is waiting for, and the honest thing to tell
     * the model is that the call may still be running.
     */
    private fun dispatchWithTimeout(
        method: String,
        params: JSONObject,
        silent: Boolean,
        untrusted: Boolean,
    ): Any? {
        val declared = params.optLong("timeout_ms", 0L)
        val budget = if (declared > 0) declared + TIMEOUT_SLACK_MS else DEFAULT_TOOL_TIMEOUT_MS
        val f = toolExec.submit<Any?> {
            dispatcher.dispatch(
                method, params, driving = true, silent = silent, untrusted = untrusted,
            )
        }
        return try {
            f.get(budget, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            Log.w(TAG, "tool $method exceeded ${budget}ms — abandoning the wait")
            JSONObject()
                .put("timed_out", true)
                .put("note", "This step had not returned after ${budget / 1000}s, so I stopped waiting. It may still be running in the background — read the screen before deciding the next step.")
        } catch (e: ExecutionException) {
            // Unwrap so the model sees the dispatcher's own message, which is
            // written for it ("re-dump, don't guess").
            throw e.cause ?: e
        }
    }

    private fun toolMessage(id: String, content: String): JSONObject =
        JSONObject()
            .put("role", "tool")
            .put("tool_call_id", id)
            .put("content", content)

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
        net.kuafuai.andee.ui.ball.BallLooks
            .byName(VoiceConfig.ballLook(appContext)).persona,
        VoiceConfig.activeScene(appContext)?.let { Notebook.scene(appContext, it) },
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
        Log.i(TAG, "persona or scene changed — system prompt rewritten, prefix cache dropped once")
    }

    /**
     * Drop whole turns off the front when the history gets long.
     *
     * Turn-at-a-time, never message-at-a-time: an orphaned `tool` message whose
     * assistant message is gone is a 400 from the API, and one that arrives
     * mid-task takes the task with it. The system message at index 0 stays.
     */
    private fun trimHistory() {
        if (history.size <= MAX_HISTORY) return
        while (history.size > MAX_HISTORY && history.size > 1) {
            drop(1)
            // Then keep going to the next clean boundary — a plain `user`
            // message is the only place a conversation can be cut.
            while (history.size > 1 && !isBoundary(history[1])) drop(1)
        }
        Log.i(TAG, "history trimmed to ${history.size} msgs")
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
            Log.i(TAG, "harvest skipped: nothing changed since the last one")
            return
        }
        val cut = boundaryAtOrBefore(history.size - KEEP_TAIL_MESSAGES)
        if (cut < MIN_HARVEST_MESSAGES) {
            Log.i(TAG, "harvest skipped: only ${cut - 1} messages old enough to fold in")
            return
        }
        Log.i(
            TAG,
            "harvest: ${history.size} msgs / ${historyChars()} chars over budget — " +
                "reviewing before trimming",
        )
        lastHarvestAtSize = history.size
        launch(HARVEST_INSTRUCTION, TurnKind.HARVEST)
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
        Log.i(TAG, "harvest did not finish; dropping ${history.size - from} scaffolding message(s)")
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
            Log.w(TAG, "harvest finished without a usable start index")
            return
        }
        val summary = summaryRaw.trim().take(MAX_INTERNAL_NOTE_CHARS)
        if (summary.isEmpty()) {
            // Keeping the long conversation beats replacing it with nothing.
            Log.w(TAG, "harvest produced no summary; nothing dropped")
            return
        }
        val keepStart = boundaryAtOrBefore(from - KEEP_TAIL_MESSAGES)
        if (keepStart < 2) {
            Log.i(TAG, "harvest has nothing old enough to replace; kept as is")
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
        Log.i(
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
        toolName.startsWith("get_screen") -> AppLocale.str(appContext, R.string.narrate_get_screen)
        toolName == "take_screenshot" || toolName == "zoom_screen_region" ->
            AppLocale.str(appContext, R.string.narrate_screenshot)
        toolName.startsWith("tap_") -> AppLocale.str(appContext, R.string.narrate_tap)
        toolName.startsWith("long_press") -> AppLocale.str(appContext, R.string.narrate_long_press)
        toolName.startsWith("swipe") -> AppLocale.str(appContext, R.string.narrate_swipe)
        toolName == "type_text" || toolName == "fill_secret" ->
            AppLocale.str(appContext, R.string.narrate_type)
        toolName == "submit_input" -> AppLocale.str(appContext, R.string.narrate_submit)
        toolName == "wait_for_screen" -> AppLocale.str(appContext, R.string.narrate_wait)
        toolName == "launch_app" || toolName == "open_url" ->
            AppLocale.str(appContext, R.string.narrate_launch)
        toolName == "system_action" -> AppLocale.str(appContext, R.string.narrate_system)
        toolName == "show_html" -> AppLocale.str(appContext, R.string.narrate_show_html)
        toolName == "camera_turn" -> AppLocale.str(appContext, R.string.narrate_camera)
        toolName.startsWith("dog_") -> AppLocale.str(appContext, R.string.narrate_dog)
        else -> AppLocale.str(appContext, R.string.narrate_default, toolName)
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
            AppLocale.localeOf(appContext),
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
        AppLocale.localeOf(appContext).language.ifEmpty { "zh" }

    companion object {
        private const val TAG = "Brain"

        // The per-turn step cap moved to [VoiceConfig.maxSteps] / "max_steps"
        // pref so a user who wants longer tasks can raise it from the settings
        // card. See the runTurn loop and [VoiceConfig.DEFAULT_MAX_STEPS].

        /** Lower than the cloud's 10: a local round costs the user a minute. */
        private const val MAX_REPEATS = 3

        private const val MAX_HISTORY = 60

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

If this conversation genuinely produced nothing new, record nothing — ending empty-handed is allowed. Forcing one in just dirties the notebook."""

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

        private const val DEFAULT_TOOL_TIMEOUT_MS = 60_000L
        private const val TIMEOUT_SLACK_MS = 10_000L
    }
}
