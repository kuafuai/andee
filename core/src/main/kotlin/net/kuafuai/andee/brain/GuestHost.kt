package net.kuafuai.andee.brain

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The device as a friend's request sees it: the same body and the same persona, and none of the owner's life.
 *
 * A friend's Andee asked this one for something, and the owner said yes. The work runs in a [LocalBrain] of its
 * own built on this host, so it starts from an empty conversation — it cannot read what the owner said today,
 * and nothing it writes lands in the owner's history. On top of that this host shows it **no notebook, no
 * scenes, no pages, no topics and no installed-app shelf**, and lets it call only the tools in [ALLOWED]: the
 * screen, asking the owner, showing a page, opening an app or a link. Everything else — the vault, contacts,
 * SMS, location, notifications, the terminal, the camera, the other devices — is not listed and is refused if
 * named anyway. Every call also goes out as `untrusted`, so [net.kuafuai.andee.net.TurnGuards] holds even if
 * this list is ever widened by mistake.
 *
 * An allow-list rather than a deny-list on purpose: a tool added next month is closed to friends until somebody
 * decides it should not be.
 */
class GuestHost(private val inner: BrainHost) : BrainHost by inner {

    override fun touchActivity() {}                 // a friend's request is not the owner speaking
    override fun notebookIndex(): String = ""
    override fun scenesIndex(): String = ""
    override fun scene(name: String) = null
    override fun activeSceneName(): String? = null
    override fun scrollback(): List<ScrollRow> = emptyList()
    override fun pagesIndex(): String = ""
    override fun topicsIndex(): String = ""
    override fun gearIndex(): String = ""
    override fun friendsIndex(): String = ""
    override fun foldTopic(title: String, summary: String, rows: List<ScrollRow>, text: String): FoldedTopic? = null

    /** Not forwarded: the owner's own turn state (the Mac's command policy) must not be moved by a guest. */
    override fun turnStarted(kind: String) {}

    /** A friend's task leaves no trace in the owner's timeline beyond the report itself. */
    override fun event(e: BrainEvent) {}

    override fun toolLists(): List<JSONArray> = inner.toolLists().map { list ->
        JSONArray().also { out -> for (i in 0 until list.length()) list.optJSONObject(i)?.takeIf { allowed(it.optString("method")) }?.let(out::put) }
    }

    override fun methodOf(toolName: String): String? = inner.methodOf(toolName)?.takeIf(::allowed)

    override fun dispatch(method: String, params: JSONObject, callId: String, silent: Boolean, untrusted: Boolean): Any? {
        check(allowed(method)) {
            "This is a request from a friend's Andee, so only the screen, asking the owner and showing a page are open: " +
                "$method is not. Say in your report what you could not do."
        }
        return inner.dispatch(method, params, callId, silent, untrusted = true)
    }

    override fun traceDir(): File = inner.traceDir()

    companion object {
        private val ALLOWED_PREFIXES = listOf("screen.")
        private val ALLOWED = setOf("ui.ask", "ui.alert", "ui.show_html", "device.launch", "device.open_url")

        fun allowed(method: String): Boolean = method in ALLOWED || ALLOWED_PREFIXES.any { method.startsWith(it) }
    }
}
