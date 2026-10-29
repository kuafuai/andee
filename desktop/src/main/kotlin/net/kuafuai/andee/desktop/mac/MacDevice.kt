package net.kuafuai.andee.desktop.mac

import net.kuafuai.andee.desktop.DesktopBrainHost
import net.kuafuai.andee.desktop.EncryptedFileStore
import net.kuafuai.andee.desktop.AppDirs
import net.kuafuai.andee.desktop.Scrollback
import net.kuafuai.andee.desktop.Strings
import net.kuafuai.andee.desktop.VaultSecrets
import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The `device.*` tools that mean something on a Mac. The ones that do not —
 * SMS, calls, call log, step counter, motion sensors, the camera scanner — are
 * simply not registered, so the model never sees them (a listed tool that
 * cannot work is a promise the model makes and breaks).
 *
 * Calendar, reminders and contacts go through the Mac's own apps with
 * AppleScript; the first use asks the user for Automation permission, which
 * is the honest place for that question.
 */
class MacDevice : DesktopBrainHost.ToolProvider {

    override fun handles(method: String) = method in METHODS

    /** id -> (english, chinese). The Mac's three special grants; the concierge only knows these. */
    private val CATALOG = mapOf(
        "accessibility" to ("operate other apps" to "控制其它 app（辅助功能）"),
        "screen_recording" to ("see the screen" to "看屏幕（录屏）"),
        "full_disk_access" to ("read notifications" to "读通知（完全磁盘访问）"),
    )

    override fun dispatch(method: String, p: JSONObject, callId: String, silent: Boolean, untrusted: Boolean): Any? = when (method) {
        "device.launch" -> launch(p)
        "device.open_url" -> openUrl(p.optString("url"))
        "device.apps" -> apps()
        "device.battery" -> battery()
        "device.connectivity" -> connectivity()
        "device.volume" -> volume(p)
        "device.facts" -> facts()
        "device.calendar" -> calendar(p.optInt("days_ahead", 7))
        "device.calendar.add" -> addEvent(p)
        "device.alarm" -> reminder(p)
        "device.contacts" -> contacts(p.optString("query"))
        "device.permissions" -> permissions()
        "device.permissions.request" -> requestPermissions(p)
        "device.notifications" -> MacNotifications.snapshot(p.optLong("since_ms", 0L), p.optInt("limit", 20).coerceIn(1, 50))
        "device.look" -> look(p.optBoolean("front", false))
        "device.look_stop" -> JSONObject().put("closed", true)
        "device.brightness" -> brightness(p)
        "device.location" -> Bridge.location()
        "device.vault.list" -> MacVault.list()
        "device.vault.get" -> MacVault.get(p.optString("entry"), p.optString("field"))
        "device.vault.fill" -> MacVault.fill(p.optString("entry"))
        else -> throw IllegalArgumentException("unknown method $method")
    }

    private fun launch(p: JSONObject): JSONObject {
        val target = p.optString("pkg").ifBlank { p.optString("label") }.trim()
        require(target.isNotEmpty()) { "launch_app needs pkg (a bundle id like com.apple.Safari) or label (an app name)" }
        val ok = if (target.contains('.') && !target.contains(' ')) Bridge.open(target) || openA(target) else openA(target)
        if (!ok) throw IllegalArgumentException("No app called \"$target\" here. list_installed_apps shows what is installed.")
        Thread.sleep(800)
        return JSONObject().put("launched", target).put("front_app", Bridge.frontmost().optString("name"))
    }

    private fun openA(name: String): Boolean = run("/usr/bin/open", "-a", name).first == 0

    private fun openUrl(url: String): JSONObject {
        require(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) { "url must be absolute, e.g. https://…" }
        require(Bridge.open(url)) { "could not open $url" }
        return JSONObject().put("opened", url)
    }

    private fun apps(): JSONObject {
        val arr = JSONArray()
        val dirs = listOf(File("/Applications"), File("/System/Applications"), File(System.getProperty("user.home"), "Applications"))
        for (d in dirs) d.listFiles { f -> f.name.endsWith(".app") }?.sortedBy { it.name }?.forEach { app ->
            val id = run("/usr/bin/mdls", "-raw", "-name", "kMDItemCFBundleIdentifier", app.path).second.trim()
            arr.put(JSONObject().put("label", app.name.removeSuffix(".app")).put("pkg", if (id == "(null)") "" else id))
        }
        return JSONObject().put("apps", arr).put("count", arr.length())
    }

    private fun battery(): JSONObject {
        val out = run("/usr/bin/pmset", "-g", "batt").second
        val pct = Regex("(\\d+)%").find(out)?.groupValues?.get(1)?.toIntOrNull()
        return JSONObject().put("percent", pct ?: JSONObject.NULL)
            .put("charging", out.contains("charging") && !out.contains("discharging") || out.contains("charged"))
            .put("plugged", out.contains("AC Power"))
            .apply { if (pct == null) put("note", "This Mac has no battery.") }
    }

    private fun connectivity(): JSONObject {
        val online = runCatching { Socket().use { it.connect(InetSocketAddress("223.5.5.5", 53), 2000); true } }.getOrDefault(false) ||
            runCatching { Socket().use { it.connect(InetSocketAddress("1.1.1.1", 53), 2000); true } }.getOrDefault(false)
        val wifi = run("/usr/sbin/networksetup", "-getairportnetwork", "en0").second
        val type = when {
            wifi.contains("Current Wi-Fi Network") -> "wifi"
            online -> "ethernet"
            else -> "none"
        }
        return JSONObject().put("online", online).put("type", type)
    }

    private fun volume(p: JSONObject): JSONObject {
        if (p.has("mute")) osa("set volume output muted ${p.optBoolean("mute")}")
        if (p.has("level")) osa("set volume output volume ${p.optInt("level").coerceIn(0, 100)}")
        val now = osa("output volume of (get volume settings)").trim()
        return JSONObject().put("level", now.toIntOrNull() ?: JSONObject.NULL)
            .put("muted", osa("output muted of (get volume settings)").trim() == "true")
    }

    private fun facts(): JSONObject = JSONObject()
        .put("device", "Mac").put("model", run("/usr/sbin/sysctl", "-n", "hw.model").second.trim())
        .put("os", "macOS " + run("/usr/bin/sw_vers", "-productVersion").second.trim())
        .put("name", run("/usr/sbin/scutil", "--get", "ComputerName").second.trim())
        .put("now", SimpleDateFormat("yyyy-MM-dd EEE HH:mm", Locale.US).format(Date()))
        .put("display", Bridge.display())

    // ---- calendar / reminders / contacts via the Mac's apps -------------------

    private fun calendar(days: Int): JSONObject {
        val d = days.coerceIn(0, 60)
        val script = """
            set out to ""
            set t0 to current date
            set t1 to t0 + ($d * days)
            tell application "Calendar"
              repeat with c in calendars
                repeat with e in (every event of c whose start date ≥ t0 and start date ≤ t1)
                  set out to out & (summary of e) & tab & ((start date of e) as «class isot» as string) & tab & ((end date of e) as «class isot» as string) & tab & (name of c) & linefeed
                end repeat
              end repeat
            end tell
            return out
        """.trimIndent()
        val arr = JSONArray()
        for (line in osa(script, timeoutS = 30).lines().filter { it.isNotBlank() }) {
            val f = line.split('\t')
            arr.put(JSONObject().put("title", f.getOrElse(0) { "" }).put("begin", f.getOrElse(1) { "" })
                .put("end", f.getOrElse(2) { "" }).put("calendar", f.getOrElse(3) { "" }))
        }
        return JSONObject().put("events", arr).put("count", arr.length()).put("days_ahead", d)
    }

    private fun addEvent(p: JSONObject): JSONObject {
        val title = p.optString("title").trim().ifEmpty { throw IllegalArgumentException("title is required") }
        val begin = p.optLong("begin_ms", 0L).takeIf { it > 0 } ?: throw IllegalArgumentException("begin_ms is required")
        val end = p.optLong("end_ms", begin + 3_600_000L)
        fun asDate(ms: Long) = "(date \"" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms)) + "\")"
        val loc = p.optString("location").replace("\"", "'")
        val desc = p.optString("description").replace("\"", "'")
        osa(
            """
            tell application "Calendar"
              tell (first calendar whose writable is true)
                make new event with properties {summary:"${title.replace("\"", "'")}", start date:${asDate(begin)}, end date:${asDate(end)}, location:"$loc", description:"$desc"}
              end tell
            end tell
            """.trimIndent(),
            timeoutS = 20,
        )
        return JSONObject().put("added", true).put("title", title).put("begin", Date(begin).toString())
    }

    /** "Set an alarm" on a Mac is a Reminders reminder with an alert. */
    private fun reminder(p: JSONObject): JSONObject {
        val h = p.optInt("hour", -1); val m = p.optInt("minutes", p.optInt("minute", 0))
        require(h in 0..23) { "hour 0-23 is required" }
        val label = p.optString("label").ifBlank { p.optString("message") }.ifBlank { "Andee" }.replace("\"", "'")
        osa(
            """
            set d to current date
            set hours of d to $h
            set minutes of d to $m
            set seconds of d to 0
            if d < (current date) then set d to d + (1 * days)
            tell application "Reminders" to make new reminder with properties {name:"$label", remind me date:d}
            """.trimIndent(),
            timeoutS = 20,
        )
        return JSONObject().put("set", true).put("time", String.format("%02d:%02d", h, m)).put("label", label)
            .put("note", "On a Mac this is a Reminders reminder with an alert, not an alarm clock.")
    }

    private fun contacts(query: String): JSONObject {
        val q = query.replace("\"", "'").trim()
        val script = """
            set out to ""
            tell application "Contacts"
              repeat with p in (every person whose name contains "$q")
                set ph to ""
                repeat with x in phones of p
                  set ph to ph & (value of x) & ","
                end repeat
                set em to ""
                repeat with x in emails of p
                  set em to em & (value of x) & ","
                end repeat
                set out to out & (name of p) & tab & ph & tab & em & linefeed
              end repeat
            end tell
            return out
        """.trimIndent()
        val arr = JSONArray()
        for (line in osa(script, timeoutS = 30).lines().filter { it.isNotBlank() }.take(20)) {
            val f = line.split('\t')
            arr.put(JSONObject().put("name", f.getOrElse(0) { "" })
                .put("phones", JSONArray(f.getOrElse(1) { "" }.split(',').filter { it.isNotBlank() }))
                .put("emails", JSONArray(f.getOrElse(2) { "" }.split(',').filter { it.isNotBlank() })))
        }
        return JSONObject().put("contacts", arr).put("count", arr.length())
    }

    /** Brightness uses the phone's 1..255 scale on the wire; CoreDisplay wants 0–1. */
    private fun brightness(p: JSONObject): JSONObject {
        val raw = p.optInt("brightness", -1)
        if (raw < 0) return JSONObject().put("brightness", (Bridge.brightnessGet() * 255).toInt().coerceIn(1, 255))
        Bridge.brightnessSet(raw / 255.0)
        return JSONObject().put("brightness", raw)
    }

    /** One frame through the lens. The Mac has no persistent viewfinder, so each call is one shot; the privacy tell is the scrollback row. */
    private fun look(front: Boolean): JSONObject {
        val f = File.createTempFile("andee-look", ".png")
        try {
            if (!Bridge.cameraShot(f.path, front)) {
                return JSONObject().put("error", "The camera could not be used on this Mac (no camera, or the Camera permission is missing). Say so; do not claim to see.")
            }
            val b64 = java.util.Base64.getEncoder().encodeToString(f.readBytes())
            net.kuafuai.andee.desktop.TimelineEvents.body(net.kuafuai.andee.desktop.TimelineEvents.Body.LOOK, (if (Strings.lang() == "zh") "用摄像头看了一眼（" else "Looked through the camera (") + (if (front) "front" else "back") + ")")
            return JSONObject().put("png_base64", b64).put("camera", if (front) "front" else "back")
        } finally { f.delete() }
    }

    /**
     * The polite permission concierge, the phone's PermissionsController on the
     * Mac: says exactly what is missing in one sentence, and on confirm fires
     * the system prompt (accessibility, screen recording) or the Settings pane
     * (full disk access). `confirm=false` answers with a `say` line the brain
     * reads aloud and asks; `confirm=true` reports what actually happened, not a
     * hard-coded "started".
     */
    private fun requestPermissions(p: JSONObject): JSONObject {
        val confirm = p.optBoolean("confirm", false)
        val ids = p.optJSONArray("ids") ?: throw IllegalArgumentException("device.permissions.request requires ids[]")
        val wanted = (0 until ids.length()).map { ids.optString(it) }.filter { it in CATALOG }
        if (wanted.isEmpty()) return JSONObject().put("error", "no known permission ids in request")
        val zh = Strings.lang() == "zh"
        val names = wanted.joinToString(if (zh) "、" else ", ") { val (en, zh2) = CATALOG[it]!!; if (zh) zh2 else en }
        val say = (if (zh) "还差这几项权限：" else "Andee still needs: ") + names + ". " +
            (if (zh) "要我现在打开系统设置，你来开一下吗？" else "Shall I open System Settings for you to allow them?")
        if (!confirm) return JSONObject().put("say", say).put("confirm_needed", true).put("ids", ids)
        var result = "waiting"
        var next = if (zh) "系统已经弹出提示或打开了设置页。等你看完，再用 device.permissions 确认是否已经开好。" else
            "The prompt is up or the Settings pane is open. Once you are done, check device.permissions again."
        for (id in wanted) {
            when (id) {
                "accessibility" -> if (Bridge.axTrusted(prompt = true)) { result = "granted"; next = if (zh) "已经开好了，继续。" else "Already on — carry on." }
                "screen_recording" -> if (Bridge.screenCaptureAllowed(request = true)) { result = "granted"; next = if (zh) "已经开好了，继续。" else "Already on — carry on." }
                "full_disk_access" -> runCatching {
                    Bridge.open("x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles")
                }.onFailure { Log.w("MacDevice", "could not open Full Disk Access pane", it) }
            }
        }
        return JSONObject().put("result", result).put("next", next).put("say", say)
    }

    private fun permissions(): JSONObject {
        val granted = JSONArray(); val missing = JSONArray()
        (if (Bridge.axTrusted()) granted else missing).put(JSONObject().put("id", "accessibility").put("feature", "operate other apps"))
        (if (Bridge.screenCaptureAllowed()) granted else missing).put(JSONObject().put("id", "screen_recording").put("feature", "see the screen"))
        // A store that is not there says nothing about the grant either way, so it is reported as neither.
        if (MacNotifications.state != MacNotifications.State.NOT_FOUND) {
            (if (MacNotifications.state != MacNotifications.State.NO_ACCESS) granted else missing)
                .put(JSONObject().put("id", "full_disk_access").put("feature", "read notifications"))
        }
        return JSONObject().put("granted", granted).put("missing", missing)
            .put("note", "Calendar, Reminders and Contacts ask for permission the first time they are used.")
            .also { o ->
                if (!Bridge.axTrusted()) o.put("accessibility_fix", PermissionHelp.ACCESSIBILITY)
                if (!Bridge.screenCaptureAllowed()) o.put("screen_recording_fix", PermissionHelp.SCREEN_RECORDING)
                if (MacNotifications.state == MacNotifications.State.NO_ACCESS) o.put("full_disk_access_fix", MacNotifications.NO_ACCESS_NOTE)
                if (MacNotifications.state == MacNotifications.State.NOT_FOUND) o.put("notifications_unavailable", MacNotifications.NOT_FOUND_NOTE)
            }
    }

    // ---- helpers --------------------------------------------------------------

    private fun run(vararg cmd: String): Pair<Int, String> = runCatching {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); -1 to out } else p.exitValue() to out
    }.getOrDefault(-1 to "")

    private fun osa(script: String, timeoutS: Long = 10): String {
        val p = ProcessBuilder("/usr/bin/osascript", "-e", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutS, TimeUnit.SECONDS)) { p.destroyForcibly(); throw IllegalStateException("timed out waiting for the app") }
        if (p.exitValue() != 0) {
            if (out.contains("-1743") || out.contains("not allowed")) throw IllegalStateException(
                "macOS has not given Andee permission to control that app. The user can allow it in System Settings → " +
                    "Privacy & Security → Automation (Andee → that app).",
            )
            throw IllegalStateException(out.trim().take(300))
        }
        return out
    }

    companion object {
        val METHODS = setOf(
            "device.launch", "device.open_url", "device.apps", "device.battery", "device.connectivity", "device.volume",
            "device.facts", "device.calendar", "device.calendar.add", "device.alarm", "device.contacts",
            "device.permissions", "device.permissions.request", "device.notifications", "device.look", "device.look_stop", "device.brightness", "device.location", "device.vault.list", "device.vault.get", "device.vault.fill",
        )
    }
}

/**
 * The user's own accounts on the Mac: one encrypted file under a Keychain key,
 * the same rules as the phone's vault. `get` refuses the password outright;
 * `fill` types it into the focused field and never returns it.
 */
object MacVault {

    private val store = EncryptedFileStore(AppDirs.file("vault.enc"), "vault-key")

    init {
        VaultSecrets.source = { entries().flatMap { e -> listOfNotNull(e.optString("password").takeIf { it.isNotEmpty() }?.let { "vault «${e.optString("label")}»" to it }) } }
    }

    fun entries(): List<JSONObject> {
        val raw = store.read() ?: return emptyList()
        val a = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    /** 恢复出厂: the encrypted file goes, and the Keychain key opens nothing. */
    fun wipe() = store.wipe()

    fun save(all: List<JSONObject>) {
        check(store.write(JSONArray(all).toString())) { "the vault could not be saved (is the login Keychain locked?)" }
    }

    private fun mask(s: String) = if (s.length <= 4) "••••" else s.take(2) + "•".repeat((s.length - 4).coerceAtMost(6)) + s.takeLast(2)

    fun list(): JSONObject {
        val arr = JSONArray()
        for (e in entries()) arr.put(
            JSONObject().put("entry", e.optString("label"))
                .put("username", mask(e.optString("username"))).put("phone", mask(e.optString("phone")))
                .put("email", mask(e.optString("email"))).put("has_password", e.optString("password").isNotEmpty()),
        )
        return JSONObject().put("entries", arr).put("count", arr.length())
    }

    private fun find(label: String) = entries().firstOrNull { it.optString("label") == label }
        ?: throw IllegalArgumentException("No vault entry called \"$label\" — list_vault shows the names.")

    fun get(label: String, field: String): JSONObject {
        val f = field.ifBlank { "username" }
        require(f != "password") { "Passwords are never read out. Use fill_secret with the field focused." }
        val e = find(label)
        return JSONObject().put("entry", label).put("field", f).put("value", e.optString(f))
    }

    fun fill(label: String): JSONObject {
        val pw = find(label).optString("password")
        require(pw.isNotEmpty()) { "That entry has no password saved." }
        require(Bridge.focusedValue().optBoolean("focused")) { "Click into the password field first." }
        net.kuafuai.andee.desktop.TimelineEvents.body(net.kuafuai.andee.desktop.TimelineEvents.Body.VAULT, if (Strings.lang() == "zh") "正在填入「$label」的密码" else "Filling the password for “$label”")
        // Keystrokes, not AXValue: a web login form only believes what arrives as input events, and writing
        // the control's value directly fills the box on screen while the page still holds an empty password.
        // And not the pasteboard either — a password left there would sit in the user's clipboard history.
        Bridge.key(Bridge.Keys.A, Bridge.Keys.CMD)
        Bridge.typeText(pw)
        val ok = true
        val len = Bridge.focusedValue().optString("value").length
        Log.i("Vault", "filled $label (${pw.length} chars)")
        return JSONObject().put("performed", ok).put("len", pw.length).put("field_len", len)
    }
}
