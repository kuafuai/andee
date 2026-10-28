package net.kuafuai.andee.desktop.mac

import net.kuafuai.andee.config.Categories
import net.kuafuai.andee.config.Gear
import net.kuafuai.andee.desktop.DesktopBrainHost
import net.kuafuai.andee.desktop.DesktopNotebook
import net.kuafuai.andee.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The gear shelf on the Mac: what is installed, and what each thing is for.
 *
 * **Facts come from Spotlight, in one call.** `mdls` over every bundle returns the id, name, version, the
 * last time the app was used and a use count in a single process (0.1 s for ~275 apps), which is what makes
 * ranking by real use free here. Doing it per app, as `device.apps` does, would be 275 processes.
 * Meaning is the brain's (`gear.note`); the scan never touches it.
 */
class MacGear : DesktopBrainHost.ToolProvider {

    val gear: Gear by lazy { Gear(DesktopNotebook.book) }

    override fun handles(method: String) = method.startsWith("gear.")

    override fun dispatch(method: String, p: JSONObject, callId: String, silent: Boolean, untrusted: Boolean): Any? = when (method) {
        "gear.list" -> {
            ensureScanned()
            gear.list(p.optString("query"), p.optInt("limit", 25))
        }
        "gear.note" -> {
            val id = p.optString("id").trim().ifEmpty { throw IllegalArgumentException("note_gear needs an id from list_gear") }
            val item = gear.note(
                id, p.optString("does"), p.optString("can_do"), p.optString("signed_in"),
                p.optString("limit"), p.optString("category"),
            ) ?: throw IllegalArgumentException("\"$id\" is not on the shelf. Take the id from list_gear; do not write about something you have not seen listed.")
            JSONObject().put("saved", true).put("id", item.id).put("label", item.label)
        }
        "gear.visibility" -> visibility(p)
        else -> throw IllegalArgumentException("unknown method $method")
    }

    private fun visibility(p: JSONObject): JSONObject {
        val cat = p.optString("category").trim()
        if (cat.isEmpty() || !p.has("show")) return JSONObject().put("visible", gear.visibility())
        require(cat in Categories.ALL) { "category must be one of ${Categories.ALL.joinToString(" / ")}" }
        val on = p.optBoolean("show")
        gear.setShown(cat, on)
        Log.i(TAG, "gear: $cat ${if (on) "shown" else "hidden"} by the user's request")
        return JSONObject().put("category", cat).put("show", on).put("visible", gear.visibility())
    }

    // ---- the scan -------------------------------------------------------------------------------

    @Volatile private var lastScanAt = 0L

    /** The shelf's line for the user message. Scans first if it has never been, or is a day stale. */
    fun indexForPrompt(): String {
        ensureScanned(block = false)
        return gear.indexForPrompt()
    }

    private fun ensureScanned(block: Boolean = true) {
        val stale = System.currentTimeMillis() - lastScanAt > SCAN_TTL_MS
        if (!stale) return
        lastScanAt = System.currentTimeMillis()   // set first: two callers must not both scan
        if (block) scanNow() else Thread { scanNow() }.apply { isDaemon = true; name = "gear-scan" }.start()
    }

    fun scanNow() {
        runCatching {
            val diff = gear.applyScan(scan())
            if (diff.added.isNotEmpty() || diff.gone.isNotEmpty()) {
                Log.i(TAG, "gear: +${diff.added.size} -${diff.gone.size} (${diff.added.take(3)})")
            }
        }.onFailure { Log.w(TAG, "gear scan failed", it) }
    }

    private fun scan(): List<Gear.Item> {
        val dirs = listOf(File("/Applications"), File("/System/Applications"), File(System.getProperty("user.home"), "Applications"))
        val apps = dirs.flatMap { d -> d.listFiles { f -> f.name.endsWith(".app") }?.toList() ?: emptyList() }
        if (apps.isEmpty()) return emptyList()
        // Keyed output, not `-raw`: mdls prints its attributes in its own fixed order and `-raw` glues them
        // together with no placeholder for a missing one, so reading by position slides every later field
        // one place left the first time an app has no use count (every system app). Keys cannot slide.
        val cmd = listOf(
            "/usr/bin/mdls",
            "-name", "kMDItemCFBundleIdentifier", "-name", "kMDItemDisplayName", "-name", "kMDItemVersion",
            "-name", "kMDItemLastUsedDate", "-name", "kMDItemUseCount",
        ) + apps.map { it.path }
        val out = runCatching {
            val pr = ProcessBuilder(cmd).redirectErrorStream(false).start()
            val text = pr.inputStream.readBytes().toString(Charsets.UTF_8)
            pr.waitFor(20, TimeUnit.SECONDS)
            text
        }.getOrDefault("")
        return parse(out)
    }

    /** Records start at each `kMDItemCFBundleIdentifier` line; every other line is read by its key. */
    internal fun parse(out: String): List<Gear.Item> {
        val items = ArrayList<Gear.Item>()
        var cur = HashMap<String, String>()
        fun flush() {
            val id = cur["kMDItemCFBundleIdentifier"].orEmpty()
            if (id.isNotEmpty()) {
                items += Gear.Item(
                    id = id,
                    label = cur["kMDItemDisplayName"].orEmpty().removeSuffix(".app").ifEmpty { id },
                    version = cur["kMDItemVersion"].orEmpty(),
                    lastUsedAt = parseDate(cur["kMDItemLastUsedDate"].orEmpty()),
                    uses = cur["kMDItemUseCount"]?.toIntOrNull() ?: 0,
                )
            }
            cur = HashMap()
        }
        for (line in out.lineSequence()) {
            val m = LINE.matchEntire(line) ?: continue
            val key = m.groupValues[1]
            if (key == "kMDItemCFBundleIdentifier") flush()
            val raw = m.groupValues[2].trim()
            cur[key] = if (raw == "(null)") "" else raw.removeSurrounding("\"")
        }
        flush()
        return items
    }

    private fun parseDate(s: String): Long {
        if (s.isEmpty() || s == "(null)") return 0L
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US).parse(s)?.time ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        private val LINE = Regex("""^(kMDItem\w+)\s*=\s*(.*)$""")
        private const val TAG = "Gear"
        private const val SCAN_TTL_MS = 24L * 60 * 60 * 1000
        val METHODS = setOf("gear.list", "gear.note", "gear.visibility")
    }
}
