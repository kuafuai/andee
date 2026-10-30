package net.kuafuai.andee.brain

import android.content.Context
import android.util.Log
import net.kuafuai.andee.R
import net.kuafuai.andee.config.Notebook
import net.kuafuai.andee.config.Scene
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.market.MarketTools
import net.kuafuai.andee.net.CallExtensionRegistry
import net.kuafuai.andee.net.CommandDispatcher
import net.kuafuai.andee.net.ToolSchemas
import net.kuafuai.andee.ui.ChatHistory
import net.kuafuai.andee.ui.ball.BallLooks
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * The phone's answer to [BrainHost]: every call goes to the class that already
 * did it before the brain moved into the shared module, unchanged.
 */
class AndroidBrainHost(
    context: Context,
    private val dispatcher: CommandDispatcher,
    private val registry: CallExtensionRegistry,
    /** The CodeFlying market; empty outside that mode. See [MarketTools]. */
    private val market: MarketTools,
    /** The user's other devices; `list_devices` / `ask_device` exist only while one is paired. */
    private val peers: net.kuafuai.andee.device.AndroidPeers,
) : BrainHost {

    private val ctx = context.applicationContext

    override fun text(key: BrainText, vararg args: Any): String = AppLocale.str(ctx, res(key), *args)

    override fun locale(): Locale = AppLocale.localeOf(ctx)

    override fun logInfo(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    override fun logWarn(tag: String, msg: String, t: Throwable?) {
        if (t == null) Log.w(tag, msg) else Log.w(tag, msg, t)
    }

    override fun logError(tag: String, msg: String, t: Throwable?) {
        if (t == null) Log.e(tag, msg) else Log.e(tag, msg, t)
    }

    override fun maxSteps(): Int = VoiceConfig.maxSteps(ctx)

    override fun activeSceneName(): String? = VoiceConfig.activeScene(ctx)

    override fun persona(): String = BallLooks.byName(VoiceConfig.ballLook(ctx)).persona

    override fun touchActivity() = Notebook.touchActivity(ctx)

    override fun notebookIndex(): String = Notebook.indexForPrompt(ctx)
    override fun reviewRecord(): String = Notebook.recordForReview(ctx)
    override fun todoStillActive(id: String): Boolean = Notebook.todoById(ctx, id)?.status == "active"

    override fun scenesIndex(): String = Notebook.scenesForPrompt(ctx)

    override fun scene(name: String): Scene? = Notebook.scene(ctx, name)

    // Event rows are the device's record of what the brain did, not conversation: never handed back to it.
    override fun scrollback(): List<ScrollRow> = ChatHistory.snapshot().filter { it.role != ChatHistory.Role.EVENT }.map {
        ScrollRow(
            isUser = it.role == ChatHistory.Role.USER,
            isAssistant = it.role == ChatHistory.Role.ASSISTANT,
            text = it.text,
            ts = it.ts,
            topicId = it.topicId,
            handle = it,
        )
    }

    override fun pagesIndex(): String = ChatHistory.indexForPrompt()

    override fun gearIndex(): String = dispatcher.gearShelf.indexForPrompt()

    override fun planIndex(): String = net.kuafuai.andee.config.Notebook.plans(ctx).indexForPrompt()

    override fun friendsIndex(): String = peers.tasks.indexForPrompt()

    override fun topicsIndex(): String = ChatHistory.topicsForPrompt()

    override fun event(e: net.kuafuai.andee.brain.BrainEvent) = net.kuafuai.andee.ui.PhoneTimeline.brain(ctx, e)

    override fun selfIndex(): String = Notebook.selfhood(ctx).indexForPrompt()

    override fun wikiIndex(): String = Notebook.wiki(ctx).indexForPrompt()

    override fun foldTopic(title: String, summary: String, rows: List<ScrollRow>, text: String): FoldedTopic? {
        val said = rows.map { it.handle as ChatHistory.Entry }
        val entries = (said + ChatHistory.eventsWithin(said)).sortedBy { it.id }
        val t = ChatHistory.foldTopic(title = title, summary = summary, rows = entries, text = text, parentId = null)
            ?: return null
        return FoldedTopic(id = t.id, title = t.title, summary = t.summary)
    }

    override fun toolLists(): List<JSONArray> = listOfNotNull(
        // tap_target only where a pointing model can answer it: on a backend without one it would fail every tap.
        ToolSchemas.subset { m ->
            // drag is the Mac's: on the phone a swipe already is a finger moving, and there is nothing to implement.
            m != "screen.drag" && (m != "screen.tap_target" || net.kuafuai.andee.brain.Locator.modelFor(
                net.kuafuai.andee.config.VoiceConfig.brainConfig(ctx), net.kuafuai.andee.config.VoiceConfig.locatorModel(ctx),
            ) != null)
        },
        registry.tools(), market.tools(),
        // A tool that can only ever answer "nothing is paired" is a promise made to every user who never set this up.
        net.kuafuai.andee.peer.PeerTasks.TOOLS.takeIf { peers.store.all().isNotEmpty() },
    )

    override fun methodOf(toolName: String): String? =
        ToolSchemas.methodOf(toolName)
            ?: toolName.takeIf { registry.hasMethod(it) }
            ?: toolName.takeIf { market.hasMethod(it) }
            ?: net.kuafuai.andee.peer.PeerTasks.methodOf(toolName)

    override fun dispatch(method: String, params: JSONObject, callId: String, silent: Boolean, untrusted: Boolean): Any? {
        Notebook.callBeingRun = callId
        // Not the dispatcher's: the link to the other devices is not part of this body, and the hub must never
        // see it. The turn guard still runs first — it is what keeps a stranger's notification from handing work on.
        if (method.startsWith("peer.")) {
            net.kuafuai.andee.net.TurnGuards.check(method, silent, untrusted)
            return peers.tasks.dispatch(method, params)
        }
        return dispatcher.dispatch(method, params, driving = true, silent = silent, untrusted = untrusted)
    }

    override fun traceDir(): File = ctx.filesDir

    private fun res(key: BrainText): Int = when (key) {
        BrainText.NO_KEY -> R.string.brain_no_key
        BrainText.NO_KEY_CODEFLYING -> R.string.brain_no_key_codeflying
        BrainText.NO_RESULT -> R.string.brain_no_result
        BrainText.REPEAT_STOP -> R.string.brain_repeat_stop
        BrainText.MAX_STEPS -> R.string.brain_max_steps
        BrainText.ERROR -> R.string.brain_error
        BrainText.ERROR_400 -> R.string.brain_error_400
        BrainText.ERROR_402 -> R.string.brain_error_402
        BrainText.ERROR_404 -> R.string.brain_error_404
        BrainText.ERROR_MOVED -> R.string.brain_error_moved
        BrainText.ERROR_429 -> R.string.brain_error_429
        BrainText.ERROR_5XX -> R.string.brain_error_5xx
        BrainText.ERROR_AUTH -> R.string.brain_error_auth
        BrainText.ERROR_TIMEOUT -> R.string.brain_error_timeout
        BrainText.ERROR_NETWORK -> R.string.brain_error_network
        BrainText.NARRATE_GET_SCREEN -> R.string.narrate_get_screen
        BrainText.NARRATE_SCREENSHOT -> R.string.narrate_screenshot
        BrainText.NARRATE_TAP -> R.string.narrate_tap
        BrainText.NARRATE_LONG_PRESS -> R.string.narrate_long_press
        BrainText.NARRATE_SWIPE -> R.string.narrate_swipe
        BrainText.NARRATE_TYPE -> R.string.narrate_type
        BrainText.NARRATE_SUBMIT -> R.string.narrate_submit
        BrainText.NARRATE_WAIT -> R.string.narrate_wait
        BrainText.NARRATE_LAUNCH -> R.string.narrate_launch
        BrainText.NARRATE_SYSTEM -> R.string.narrate_system
        BrainText.NARRATE_SHOW_HTML -> R.string.narrate_show_html
        BrainText.NARRATE_PAGES -> R.string.narrate_pages
        BrainText.NARRATE_TOPICS -> R.string.narrate_topics
        BrainText.NARRATE_CAMERA -> R.string.narrate_camera
        BrainText.NARRATE_DOG -> R.string.narrate_dog
        BrainText.NARRATE_MARKET_SEARCH -> R.string.narrate_market_search
        BrainText.NARRATE_MARKET_CALL -> R.string.narrate_market_call
        BrainText.REVIEW_NAME -> R.string.brain_review_name
        BrainText.NARRATE_DEFAULT -> R.string.narrate_default
    }
}
