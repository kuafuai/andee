package net.kuafuai.andee.desktop

import net.kuafuai.andee.config.CronExpr
import net.kuafuai.andee.config.NotebookBook
import net.kuafuai.andee.config.Todo
import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The desktop's notebook: the shared logic over an encrypted file keyed in the Keychain. */
object DesktopNotebook {
    /** For the screenshot harness only: it runs without the Keychain, so it brings a store that does not need it. */
    @Volatile var storeOverride: net.kuafuai.andee.config.NotebookStore? = null

    val book: NotebookBook by lazy {
        NotebookBook(storeOverride ?: EncryptedFileStore(AppDirs.file("notebook.enc"), "notebook-key"))
    }

    /** Directions and day plans, in the same book. One instance: its lock serialises the tool and the settings page. */
    val plans: net.kuafuai.andee.config.Plans by lazy { net.kuafuai.andee.config.Plans(book) }
}

/** Local-time formats the model writes todos in — the same list the phone accepts. */
object LocalTime {
    private val FORMATS = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm", "HH:mm")

    fun parse(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.matches(Regex("\\d{1,2}:\\d{2}"))) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            return parse("$today $s")?.let { ms -> if (ms <= System.currentTimeMillis()) ms + 86_400_000L else ms }
        }
        for (p in FORMATS) {
            val f = SimpleDateFormat(p, Locale.US).apply { isLenient = false }
            runCatching { f.parse(s) }.getOrNull()?.let { return it.time }
        }
        return null
    }

    fun format(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
}

/**
 * Wakes the brain when a todo comes due — while the app is running.
 *
 * The phone has AlarmManager; the desktop has a timer in its own process and
 * a catch-up pass at launch for anything that came due while it was closed.
 * It deliberately does not install a launchd agent: writing to
 * `~/Library/LaunchAgents` is exactly what the shell sandbox forbids, and
 * Andee should not do to itself what it refuses to let a command do.
 */
object DesktopScheduler {

    private const val TAG = "Scheduler"
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "todo-timer").apply { isDaemon = true } }
    private val armed = HashMap<String, ScheduledFuture<*>>()

    /** Called with a due todo; set by the app once the brain exists. */
    @Volatile
    var onDue: ((Todo) -> Unit)? = null

    fun arm(t: Todo) {
        cancel(t.id)
        if (t.status != "active" || t.nextRunAt <= 0) return
        val delay = (t.nextRunAt - System.currentTimeMillis()).coerceAtLeast(0)
        synchronized(armed) { armed[t.id] = exec.schedule({ fire(t.id) }, delay, TimeUnit.MILLISECONDS) }
    }

    fun cancel(id: String) {
        synchronized(armed) { armed.remove(id)?.cancel(false) }
    }

    /**
     * Bring the plan's bubble timers in line with the plan ([net.kuafuai.andee.config.Plans.reconcile]). After
     * every plan write — the model's or the user's in settings — and at launch.
     */
    fun syncPlan() {
        val s = DesktopNotebook.plans.reconcile()
        s.cancel.forEach(::cancel)
        s.arm.forEach(::arm)
        if (s.arm.isNotEmpty() || s.cancel.isNotEmpty()) Log.i(TAG, "plan: armed ${s.arm.size}, cancelled ${s.cancel.size}")
    }

    /** At launch: re-arm the future, fire what was missed while the app was closed. */
    fun catchUp() {
        val now = System.currentTimeMillis()
        for (t in DesktopNotebook.book.todos("pending")) {
            if (t.status != "active") continue
            if (t.nextRunAt in 1..now) fire(t.id) else arm(t)
        }
    }

    private fun fire(id: String) {
        val book = DesktopNotebook.book
        val t = book.todoById(id) ?: return
        if (t.status != "active") return
        val now = System.currentTimeMillis()
        val next = if (t.mode == "cron" && t.cronExpr.isNotBlank()) CronExpr.nextAfter(t.cronExpr, now) else null
        val patched = book.patchTodo(id, nextRunAt = next ?: 0L, lastRunAt = now, lastStatus = "fired") ?: t
        if (next != null) arm(patched) else cancel(id)
        Log.i(TAG, "todo due: ${t.what.take(60)}")
        onDue?.invoke(patched)
    }
}

/**
 * The `note.*` and `scene.*` tools on the desktop, answering with the same
 * shapes as the phone's dispatcher so the brain reads identical results.
 */
class NotebookTools(private val onSceneChanged: (String?) -> Unit) {

    private val book get() = DesktopNotebook.book

    fun handles(method: String) = method.startsWith("note.") || method.startsWith("scene.")

    fun dispatch(method: String, p: JSONObject, silent: Boolean, untrusted: Boolean): Any = when (method) {
        // The plan (`Plans`): shared logic in core; here only the timers it asks for.
        "note.directions", "note.day_plan" -> {
            val r = DesktopNotebook.plans.handle(method, p, Nudges.level().perDay)
            if (method == "note.day_plan") DesktopScheduler.syncPlan()
            r
        }
        "note.remember" -> {
            val (saved, overwrote) = book.remember(
                name = req(p, "name", method),
                description = req(p, "description", method),
                content = p.optString("content"),
                type = req(p, "type", method),
                source = if (silent) NotebookBook.SOURCE_INFERRED else NotebookBook.SOURCE_HUMAN,
            )
            JSONObject().put("saved", true).put("name", saved.name).put("overwrote", overwrote)
                .put("total", book.memories().size)
                .put(
                    "note",
                    if (overwrote) "The entry with that name was updated (the old version is archived), not added anew."
                    else "Saved.",
                )
        }

        "note.recall" -> {
            val arr = JSONArray()
            for (m in book.recall(p.optString("query"), p.optString("type"), p.optInt("limit", 8))) {
                arr.put(
                    JSONObject().put("name", m.name).put("summary", m.description).put("type", m.type)
                        .put("content", if (m.content == m.description) "" else m.content)
                        .put("updated", LocalTime.format(m.updatedAt)),
                )
            }
            JSONObject().put("items", arr).put("count", arr.length()).put(
                "note",
                if (arr.length() == 0) {
                    "Nothing in the notebook matches. Do not invent something from impression — treat it as not knowing."
                } else "",
            )
        }

        "note.forget" -> {
            val name = req(p, "name", method)
            val hit = book.forget(name)
            JSONObject().put("forgotten", hit).put("name", name).put(
                "note",
                if (hit) "Deleted."
                else "There is nothing in the notebook under that name — recall first to see what it is " +
                    "actually called, and do not delete something else on impression.",
            )
        }

        "note.todo" -> todo(p, untrusted)
        "note.todos" -> todos(p)

        "scene.list" -> {
            val active = Settings.get("scene").ifEmpty { null }
            val arr = JSONArray()
            for (s in book.scenes().sortedByDescending { it.lastUsedAt }) {
                arr.put(
                    JSONObject().put("name", s.name).put("title", s.title).put("summary", s.summary)
                        .put("trigger_apps", JSONArray(s.triggerApps)).put("uses", s.uses)
                        .put("active", s.name == active),
                )
            }
            JSONObject().put("scenes", arr).put("count", arr.length()).put("active", active ?: JSONObject.NULL)
        }

        "scene.get" -> {
            val s = scene(p, method)
            JSONObject().put("name", s.name).put("title", s.title).put("summary", s.summary).put("prompt", s.prompt)
                .put("trigger_apps", JSONArray(s.triggerApps)).put("uses", s.uses)
                .put("active", s.name == Settings.get("scene"))
        }

        "scene.save" -> {
            val apps = p.optJSONArray("trigger_apps")
            val (s, created) = book.saveScene(
                name = req(p, "name", method),
                title = p.optString("title"),
                summary = p.optString("summary"),
                prompt = p.optString("prompt"),
                triggerApps = if (apps == null) emptyList() else (0 until apps.length()).map { apps.optString(it) },
            )
            JSONObject().put("name", s.name).put("created", created).put("prompt_chars", s.prompt.length)
                .put("trigger_apps", JSONArray(s.triggerApps))
        }

        "scene.enter" -> {
            val s = scene(p, method)
            setScene(s.name)
            book.markSceneUsed(s.name)
            JSONObject().put("active", s.name).put("title", s.title)
                .put("note", "The scene's instructions apply from your next reply.")
        }

        "scene.exit" -> {
            val was = Settings.get("scene").ifEmpty { null }
            setScene(null)
            JSONObject().put("left", was ?: JSONObject.NULL).put("active", JSONObject.NULL)
        }

        "scene.delete" -> {
            val s = scene(p, method)
            if (Settings.get("scene") == s.name) setScene(null)
            book.deleteScene(s.name)
            JSONObject().put("deleted", s.name)
        }

        else -> throw IllegalArgumentException("unknown method $method")
    }

    private fun setScene(name: String?) {
        if (Settings.get("scene") == (name ?: "")) return
        Settings.save(mapOf("scene" to (name ?: "")))
        onSceneChanged(name)
    }

    private fun scene(p: JSONObject, method: String) =
        book.scene(req(p, "name", method)) ?: throw IllegalArgumentException(
            "No scene called \"${p.optString("name")}\" — list_scenes shows the names that exist.",
        )

    private fun todo(p: JSONObject, untrusted: Boolean): JSONObject {
        val what = req(p, "what", "note.todo")
        val atRaw = p.optString("at").trim()
        val cronRaw = p.optString("cron").trim()
        require(atRaw.isNotEmpty() != cronRaw.isNotEmpty()) {
            "at (one-shot) and cron (recurring) are an either/or: fill exactly one, not both and not neither"
        }
        val now = System.currentTimeMillis()
        val t = if (cronRaw.isNotEmpty()) {
            val parsed = CronExpr.parse(cronRaw)
            val gap = parsed.minGapMinutes()
            require(gap >= CronExpr.MIN_INTERVAL_MINUTES) {
                "That interval is too tight (about once every $gap minutes). The tightest the system allows " +
                    "is once every ${CronExpr.MIN_INTERVAL_MINUTES} minutes — tell the user plainly that it " +
                    "cannot be done, and do not agree to it in different words."
            }
            val next = CronExpr.nextAfter(parsed, now) ?: throw IllegalArgumentException(
                "That cron expression can never match (check the day / month / weekday fields): $cronRaw",
            )
            Todo(NotebookBook.newTodoId(), what, "cron", 0L, cronRaw, next, 0L, "", "", "active", now, untrusted)
        } else {
            val at = LocalTime.parse(atRaw) ?: throw IllegalArgumentException(
                "at must be written as \"YYYY-MM-DD HH:mm\" (local time); received: $atRaw",
            )
            require(at > now) {
                "That moment has already passed ($atRaw). It is already ${LocalTime.format(now)}; give a time in the future."
            }
            Todo(NotebookBook.newTodoId(), what, "once", at, "", at, 0L, "", "", "active", now, untrusted)
        }
        book.addTodo(t)
        DesktopScheduler.arm(t)
        return JSONObject().put("id", t.id).put("mode", t.mode).put("next_run_local", LocalTime.format(t.nextRunAt))
            .put("scheduled", "exact")
            .put(
                "note",
                "Set. When it comes due and is dealt with, close it with todos(action=done, id=…) — otherwise it " +
                    "stays in the \"promised but not done\" list forever. This only fires while Andee is running; " +
                    "if the Mac is off or Andee is closed, it is caught up the next time Andee starts.",
            )
    }

    private fun todos(p: JSONObject): JSONObject {
        val action = p.optString("action").trim().lowercase()
        require(action in listOf("list", "done", "drop", "pause", "resume")) {
            "note.todos requires action=list|done|drop|pause|resume"
        }
        if (action == "list") {
            val arr = JSONArray()
            for (t in book.todos(p.optString("filter").ifEmpty { "pending" })
                .sortedBy { if (it.nextRunAt > 0) it.nextRunAt else Long.MAX_VALUE }) {
                arr.put(
                    JSONObject().put("id", t.id).put("what", t.what).put("mode", t.mode)
                        .put("when", if (t.mode == "cron") t.cronExpr else LocalTime.format(t.at))
                        .put("next_run_local", if (t.nextRunAt > 0) LocalTime.format(t.nextRunAt) else "")
                        .put("status", t.status).put("last_status", t.lastStatus),
                )
            }
            return JSONObject().put("items", arr).put("count", arr.length())
        }
        val id = p.optString("id").trim().ifEmpty {
            throw IllegalArgumentException(
                "note.todos action=$action requires an id (take it from the list result; do not make one up)",
            )
        }
        val existing = book.todoById(id) ?: throw IllegalArgumentException("No such id: $id")
        return when (action) {
            "pause" -> {
                book.patchTodo(id, status = "paused")
                DesktopScheduler.cancel(id)
                JSONObject().put("id", id).put("status", "paused")
            }
            "resume" -> {
                val now = System.currentTimeMillis()
                val next = when {
                    existing.mode == "cron" && existing.cronExpr.isNotBlank() -> CronExpr.nextAfter(existing.cronExpr, now)
                    existing.at > now -> existing.at
                    else -> null
                } ?: throw IllegalArgumentException(
                    "The time this one was due has already passed (${LocalTime.format(existing.at)}); it cannot " +
                        "be resumed — use todo to set a new one.",
                )
                DesktopScheduler.arm(book.patchTodo(id, status = "active", nextRunAt = next)!!)
                JSONObject().put("id", id).put("status", "active").put("next_run_local", LocalTime.format(next))
            }
            else -> {
                val status = if (action == "done") "done" else "dropped"
                book.patchTodo(id, status = status, nextRunAt = 0L)
                DesktopScheduler.cancel(id)
                JSONObject().put("id", id).put("status", status)
            }
        }
    }

    private fun req(p: JSONObject, key: String, method: String): String =
        p.optString(key).trim().ifEmpty { throw IllegalArgumentException("$method requires $key") }
}
