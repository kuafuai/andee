package net.kuafuai.andee.brain

import net.kuafuai.andee.config.Scene
import org.json.JSONArray
import java.io.File
import java.util.Locale

/**
 * Everything the brain needs from the device it runs on, and nothing else.
 *
 * The agent loop ([LocalBrain]) is the same code on the phone and on the
 * desktop. What differs is underneath it: where strings come from, where the
 * notebook and the scrollback are stored, which tools exist and how they are
 * run. Each platform implements this once; the brain never sees an Android or
 * a desktop type.
 *
 * Every member is called from the brain's loop thread or its tool pool, never
 * from a UI thread, so implementations may block briefly (a file read, a
 * preference) but must not wait on the UI.
 */
interface BrainHost {

    // ---- words the user reads or hears ----

    /** A user-facing sentence in the interface language, formatted with [args]. */
    fun text(key: BrainText, vararg args: Any): String

    /** The interface language; decides the clock's weekday and the `ui-language` tag. */
    fun locale(): Locale

    // ---- logging ----

    fun logInfo(tag: String, msg: String)
    fun logWarn(tag: String, msg: String, t: Throwable? = null)
    fun logError(tag: String, msg: String, t: Throwable? = null)

    // ---- settings ----

    /** The per-turn step cap. */
    fun maxSteps(): Int

    /** The scene the user is in, or null. */
    fun activeSceneName(): String?

    /** The persona text of the look the ball is wearing; empty for none. */
    fun persona(): String

    /**
     * A section placed ahead of the whole system prompt saying what this body
     * is, when it is not the phone the prompt was written for. The phone
     * returns empty, so its prompt does not change by a byte.
     */
    fun platformPrompt(): String = ""

    // ---- notebook ----

    /** The user just spoke: restart the quiet-hour clock. */
    fun touchActivity()

    /** The notebook's newest memories, one line each; empty when there are none. */
    fun notebookIndex(): String

    /** The scenes the user can enter, one line each; empty when there are none. */
    fun scenesIndex(): String

    fun scene(name: String): Scene?

    // ---- scrollback ----

    /** The persisted conversation rows, oldest first. */
    fun scrollback(): List<ScrollRow>

    /** The newest pages the brain made, one line each; empty when there are none. */
    fun pagesIndex(): String

    /** The newest topics that were folded away, one line each; empty when none. */
    fun topicsIndex(): String

    /**
     * One line naming what is installed and worth reaching for first (see `Gear`), empty when there is
     * nothing to say. A default, so a host without a gear shelf changes by not a byte.
     */
    fun gearIndex(): String = ""

    /**
     * One line naming the other Andees this one can ask (see `PeerTasks`), empty when it has none. Without it the
     * model answers "do you have friends" from nothing; a default, so a host without friends changes by not a byte.
     */
    fun friendsIndex(): String = ""

    /**
     * Archive [rows] as one finished topic. Returns null when nothing was
     * written — the caller then keeps the conversation as it was.
     */
    fun foldTopic(title: String, summary: String, rows: List<ScrollRow>, text: String): FoldedTopic?

    // ---- the body ----

    /** Every tool the model may call on this device, in the schema format [LlmClient.toolSpecs] reads. */
    fun toolLists(): List<JSONArray>

    /**
     * The internal method behind a model-facing tool name, or null when no tool
     * on this device has that name.
     */
    fun methodOf(toolName: String): String?

    /**
     * Run one tool call. Return a JSON-serialisable value or throw; a throw is
     * turned into a message the model reads. [silent] and [untrusted] describe
     * the turn, and the implementation must refuse what they forbid — see
     * [net.kuafuai.andee.net.TurnGuards].
     */
    fun dispatch(method: String, params: org.json.JSONObject, silent: Boolean, untrusted: Boolean): Any?

    /**
     * Why the current turn is running, set by the brain before its tools run.
     * Most hosts can ignore it; one that runs commands needs to tell a user
     * turn from a todo that fired while nobody may be at the screen.
     */
    fun turnStarted(kind: String) {}

    /** Where the trace file lives. */
    fun traceDir(): File
}

/** One row of the persisted conversation, as the brain needs to see it. */
data class ScrollRow(
    val isUser: Boolean,
    val isAssistant: Boolean,
    val text: String,
    val ts: Long,
    val topicId: Long?,
    /** The platform's own row object, handed back unchanged in [BrainHost.foldTopic]. */
    val handle: Any?,
)

data class FoldedTopic(val id: Long, val title: String, val summary: String)

/**
 * The user-facing sentences the brain says itself. Each platform maps every key
 * to its own string resources; a missing key is a compile error there, not a
 * blank sentence on the device.
 */
enum class BrainText {
    NO_KEY,
    /** The CodeFlying backend is selected but no key is set: names where to get one. */
    NO_KEY_CODEFLYING,
    NO_RESULT,
    REPEAT_STOP,
    MAX_STEPS,
    ERROR,
    ERROR_400,
    ERROR_402,
    ERROR_404,
    ERROR_429,
    ERROR_5XX,
    ERROR_AUTH,
    ERROR_TIMEOUT,
    ERROR_NETWORK,
    NARRATE_GET_SCREEN,
    NARRATE_SCREENSHOT,
    NARRATE_TAP,
    NARRATE_LONG_PRESS,
    NARRATE_SWIPE,
    NARRATE_TYPE,
    NARRATE_SUBMIT,
    NARRATE_WAIT,
    NARRATE_LAUNCH,
    NARRATE_SYSTEM,
    NARRATE_SHOW_HTML,
    NARRATE_PAGES,
    NARRATE_TOPICS,
    NARRATE_CAMERA,
    NARRATE_DOG,
    NARRATE_MARKET_SEARCH,
    NARRATE_MARKET_CALL,
    NARRATE_DEFAULT,
}
