package net.kuafuai.andee.desktop

import net.kuafuai.andee.brain.BrainHost
import net.kuafuai.andee.brain.BrainText
import net.kuafuai.andee.brain.FoldedTopic
import net.kuafuai.andee.brain.ScrollRow
import net.kuafuai.andee.config.Scene
import net.kuafuai.andee.net.ToolSchemas
import net.kuafuai.andee.net.TurnGuards
import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.Properties

/**
 * The Mac's answer to [BrainHost]. Each body capability registers here as a
 * [ToolProvider]; the brain sees exactly the tools some provider handles, and
 * nothing else — a tool listed but not implemented would be a promise the
 * model makes to the user and cannot keep.
 */
class DesktopBrainHost(
    private val providers: List<ToolProvider>,
    private val caps: MacPrompt.Capabilities,
) : BrainHost {

    /** One family of tools the Mac can run. */
    interface ToolProvider {
        fun handles(method: String): Boolean
        fun dispatch(method: String, p: JSONObject, callId: String, silent: Boolean, untrusted: Boolean): Any?
        /** Tools that exist only here and are not in the shared [ToolSchemas]. */
        fun extraTools(): JSONArray = JSONArray()
        fun methodOfExtra(toolName: String): String? = null
        /** The brain is starting a turn of this kind (USER / WAKE / NOTIFY / SWEEP / HARVEST). */
        fun turnStarted(kind: String) {}
    }

    override fun text(key: BrainText, vararg args: Any): String {
        val pattern = Strings.get(key.name)
        return if (args.isEmpty()) pattern else String.format(locale(), pattern, *args)
    }

    override fun locale(): Locale = Strings.locale()

    override fun logInfo(tag: String, msg: String) = Log.i(tag, msg)
    override fun logWarn(tag: String, msg: String, t: Throwable?) = Log.w(tag, msg, t)
    override fun logError(tag: String, msg: String, t: Throwable?) = Log.e(tag, msg, t)

    override fun maxSteps(): Int = Settings.get("max_steps").toIntOrNull()?.coerceIn(1, 200) ?: 40

    override fun activeSceneName(): String? = Settings.get("scene").ifEmpty { null }
        ?.takeIf { DesktopNotebook.book.scene(it) != null }

    override fun persona(): String = net.kuafuai.andee.ui.ball.BallLooks.byName(Settings.get("ball").ifEmpty { null }).persona

    override fun platformPrompt(): String = MacPrompt.text(caps)

    override fun touchActivity() {
        AppDirs.file("last_activity").writeText(System.currentTimeMillis().toString())
    }

    override fun notebookIndex(): String = DesktopNotebook.book.indexForPrompt()
    override fun scenesIndex(): String = DesktopNotebook.book.scenesForPrompt()
    override fun scene(name: String): Scene? = DesktopNotebook.book.scene(name)

    override fun scrollback(): List<ScrollRow> = Scrollback.snapshot().map {
        ScrollRow(
            isUser = it.role == Scrollback.Role.USER,
            isAssistant = it.role == Scrollback.Role.ASSISTANT,
            text = it.text,
            ts = it.ts,
            topicId = it.topicId,
            handle = it.id,
        )
    }

    override fun pagesIndex(): String = Pages.indexForPrompt()

    override fun gearIndex(): String = providers.filterIsInstance<net.kuafuai.andee.desktop.mac.MacGear>().firstOrNull()?.indexForPrompt().orEmpty()
    override fun topicsIndex(): String = Scrollback.topicsIndex()

    override fun planIndex(): String = DesktopNotebook.plans.indexForPrompt()

    override fun selfIndex(): String = DesktopNotebook.book.selfhood.indexForPrompt()

    override fun wikiIndex(): String = DesktopNotebook.book.wiki.indexForPrompt()

    override fun friendsIndex(): String = Peers.tasks.indexForPrompt()

    override fun foldTopic(title: String, summary: String, rows: List<ScrollRow>, text: String): FoldedTopic? {
        val t = Scrollback.fold(title, summary, rows.mapNotNull { it.handle as? Long }, text) ?: return null
        return FoldedTopic(t.id, t.title, t.summary)
    }

    override fun toolLists(): List<JSONArray> {
        val shared = ToolSchemas.subset { m -> providers.any { it.handles(m) } }
        return listOf(shared) + providers.map { it.extraTools() }.filter { it.length() > 0 }
    }

    override fun methodOf(toolName: String): String? =
        ToolSchemas.methodOf(toolName)?.takeIf { m -> providers.any { it.handles(m) } }
            ?: providers.firstNotNullOfOrNull { it.methodOfExtra(toolName) }

    override fun dispatch(method: String, params: JSONObject, callId: String, silent: Boolean, untrusted: Boolean): Any? {
        TurnGuards.check(method, silent, untrusted)
        val p = providers.firstOrNull { it.handles(method) }
            ?: throw IllegalArgumentException("unknown method $method")
        // The phone nods for every `screen.*` call: a hand moved. On the Mac the
        // terminal and the device tools are hands too; the notebook is not.
        if (!silent && (method.startsWith("screen.") || method.startsWith("shell.") || method.startsWith("device."))) {
            MacBall.onTool()
        }
        return p.dispatch(method, params, callId, silent, untrusted)
    }

    override fun turnStarted(kind: String) = providers.forEach { it.turnStarted(kind) }

    override fun traceDir(): File = AppDirs.root
}

/**
 * User-facing words, from `strings_<lang>.properties` — generated from the
 * phone's `strings.xml`, so both apps say the same thing.
 */
object Strings {
    private val cache = HashMap<String, Properties>()

    /**
     * Bumped when the language is changed from the settings picker. [lang] reads it, so every
     * composable that asks which language it is in is subscribed and redraws at once, with no
     * screen having to be told. (Reading Compose state off the UI thread is fine; it is a plain read.)
     */
    private val rev = androidx.compose.runtime.mutableStateOf(0)

    /** The language was just written; redraw everything that reads it. */
    fun changed() { rev.value++ }

    /**
     * The language in force. A saved choice wins; then the build's `DEFAULT_LANG`; then the system
     * locale.
     *
     * The middle rung is what makes a per-language package mean anything — see
     * [CodeFlyingBuild.defaultLang]. It sits here rather than in Settings so that a fresh install
     * reads as the packaged language without anything having to write a preference first.
     */
    fun lang(): String = rev.value.let { Settings.get("lang") }.ifEmpty {
        CodeFlyingBuild.defaultLang.ifEmpty {
            if (Locale.getDefault().language == "zh") "zh" else "en"
        }
    }

    fun locale(): Locale = if (lang() == "zh") Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH

    fun get(key: String): String {
        val l = lang()
        val p = synchronized(cache) {
            cache.getOrPut(l) {
                Properties().apply {
                    Strings::class.java.getResourceAsStream("/strings_$l.properties")
                        ?.use { load(it.reader(Charsets.UTF_8)) }
                }
            }
        }
        return p.getProperty(key) ?: key
    }
}
