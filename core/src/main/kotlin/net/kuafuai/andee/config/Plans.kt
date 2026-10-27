package net.kuafuai.andee.config

import net.kuafuai.andee.brain.Nudge
import net.kuafuai.andee.brain.NudgeBook
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * What it is trying to do with this person, and what it means to do today — so that speaking first is
 * the next step of a plan rather than a guess. See `docs/design/proactive-nudge.zh-CN.md` §5–§6.
 *
 * Two layers, the upper one guiding the lower:
 *
 *  * **Directions** — what it is helping them get done these weeks. Only from what they said or did, each
 *    with its `source`; at most [MAX_DIRECTIONS] active. A direction the user removed is kept as a tombstone
 *    so the model cannot quietly write it back.
 *  * **Day plans** — one per date: an intent in its own voice, items that each point back at a direction
 *    or a todo (`because`), and the things it will *not* bother them about today.
 *
 * Items with a clock time that interrupt (`remind` / `ask`) become **bubble todos** ([reconcile]): the
 * scheduler raises the bubble at that minute with no model call, and the outcome is written back onto the
 * item ([recordOutcome]). That is what makes most of the speaking-first predictable and cheap.
 *
 * Stored in the notebook under `plan`, through the book itself (like [Gear]), so wipe, factory reset and
 * export cover it. Shared by both bodies; each one only arms and cancels the alarms [reconcile] returns.
 */
class Plans(
    private val book: NotebookBook,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    data class Direction(
        val id: String,
        val title: String,
        val why: String,
        val howIHelp: String,
        val goodWhen: String,
        val source: String,
        /** `active` / `paused` / `done` / `dropped`. */
        val status: String,
        /** The user paused or removed it themselves; the model may not undo that. */
        val byUser: Boolean,
        val updatedAt: Long,
    )

    data class Item(
        val id: String,
        /** `HH:mm`, or blank when the item has only a [slot]. */
        val time: String,
        /** A loose time ("午饭前", "when they first talk to me"), for items without a clock time. */
        val slot: String,
        /** `remind` / `ask` / `prepare` / `follow_up`. */
        val kind: String,
        /** What to do — for `remind` / `ask`, exactly the words of the bubble. */
        val what: String,
        /** Which direction or todo this serves. Never blank. */
        val because: String,
        val buttons: List<String>,
        val dismiss: String,
        /** A stable short tag for the bubble (water, meal…); blank = one of a kind. */
        val tag: String,
        /** `planned` / `done` / `declined` / `ignored` / `missed` / `skipped` / `moved` / `dropped`. */
        val status: String,
        /** Why it ended up in [status], when the device knows. */
        val note: String,
    )

    data class Day(
        val date: String,
        val intent: String,
        val items: List<Item>,
        val quiet: List<String>,
        val updatedAt: Long,
        /** `what` of items the user removed today; the model may not put them back. */
        val userDropped: List<String>,
    )

    /** What the body has to do to its alarms after a plan changed. */
    data class Sync(val arm: List<Todo>, val cancel: List<String>)

    private val lock = Any()

    // ---- storage -------------------------------------------------------------

    private fun load(): JSONObject = book.section(SECTION)
    private fun save(o: JSONObject) = book.putSection(SECTION, o)

    fun today(): String = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate().toString()
    private fun tomorrow(): String = LocalDate.parse(today()).plusDays(1).toString()

    fun directions(): List<Direction> = synchronized(lock) { dirs(load()) }

    fun day(date: String): Day? = synchronized(lock) { load().optJSONObject("days")?.optJSONObject(date)?.let(::toDay) }

    private fun dirs(root: JSONObject): List<Direction> {
        val a = root.optJSONArray("directions") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::toDirection) }
    }

    private fun putDirs(root: JSONObject, list: List<Direction>) {
        root.put("directions", JSONArray().apply { list.forEach { put(toJson(it)) } })
    }

    private fun putDay(root: JSONObject, d: Day) {
        val days = root.optJSONObject("days") ?: JSONObject()
        days.put(d.date, toJson(d))
        // Keep a week behind today and anything ahead; older days are only history nobody reads.
        val cutoff = LocalDate.parse(today()).minusDays(KEEP_DAYS).toString()
        days.keys().asSequence().toList().filter { it < cutoff }.forEach { days.remove(it) }
        root.put("days", days)
    }

    // ---- the two tools ---------------------------------------------------------

    /**
     * `note.directions` and `note.day_plan`. Throws [IllegalArgumentException] with a sentence for the model
     * on a malformed call; refusals that are the user's limits come back as results that say why.
     *
     * @param bubblesPerDay how many bubbles the user's 主动招呼 level allows a day, so a plan can be told it
     *   asked for more than will ever be shown.
     */
    fun handle(method: String, p: JSONObject?, bubblesPerDay: Int): JSONObject = when (method) {
        "note.directions" -> directionsTool(p ?: JSONObject())
        "note.day_plan" -> dayPlanTool(p ?: JSONObject(), bubblesPerDay)
        else -> throw IllegalArgumentException("unknown method $method")
    }

    private fun directionsTool(p: JSONObject): JSONObject = synchronized(lock) {
        val root = load()
        val list = dirs(root).toMutableList()
        val now = clock()
        when (val action = p.optString("action", "list")) {
            "list" -> {
                // Looking at them is the weekly review the plan line asks for; stamp it so the ask goes away.
                root.put("meta", meta(root).put("directions_reviewed_at", now)); save(root)
                return listDirections(list)
            }
            "add" -> {
                val title = req(p, "title")
                val source = req(p, "source")
                if (list.any { it.title == title && it.byUser && it.status == "dropped" }) {
                    return JSONObject().put("added", false).put(
                        "reason", "The user removed \"$title\" themselves. Do not put it back unless they ask for it again in so many words.",
                    )
                }
                if (list.any { it.title == title && it.status == "active" }) {
                    return JSONObject().put("added", false).put("reason", "\"$title\" is already a direction; use update.")
                }
                if (list.count { it.status == "active" } >= MAX_DIRECTIONS) {
                    return JSONObject().put("added", false).put(
                        "reason", "Already $MAX_DIRECTIONS active directions. More than that is no focus at all: finish, pause or drop one first.",
                    )
                }
                val d = Direction(
                    "d" + now.toString(36), title, p.optString("why"), p.optString("how_i_help"),
                    p.optString("good_when"), source, "active", false, now,
                )
                list += d
                putDirs(root, list); save(root)
                return JSONObject().put("added", true).put("id", d.id)
            }
            "update", "pause", "resume", "done", "drop" -> {
                val id = req(p, "id")
                val i = list.indexOfFirst { it.id == id }
                require(i >= 0) { "No direction with id $id; list first." }
                val old = list[i]
                if (old.byUser && action != "update") {
                    return JSONObject().put("changed", false).put(
                        "reason", "The user set this one to ${old.status} themselves. Leave it; ask them if you think it should change.",
                    )
                }
                list[i] = when (action) {
                    "update" -> old.copy(
                        title = p.optString("title").ifEmpty { old.title },
                        why = p.optString("why").ifEmpty { old.why },
                        howIHelp = p.optString("how_i_help").ifEmpty { old.howIHelp },
                        goodWhen = p.optString("good_when").ifEmpty { old.goodWhen },
                        source = p.optString("source").ifEmpty { old.source },
                        updatedAt = now,
                    )
                    "pause" -> old.copy(status = "paused", updatedAt = now)
                    "resume" -> old.copy(status = "active", updatedAt = now)
                    "done" -> old.copy(status = "done", updatedAt = now)
                    else -> old.copy(status = "dropped", updatedAt = now)
                }
                putDirs(root, list); save(root)
                return JSONObject().put("changed", true).put("id", id).put("status", list[i].status)
            }
            else -> throw IllegalArgumentException("action must be list, add, update, pause, resume, done or drop; got $action")
        }
    }

    private fun listDirections(list: List<Direction>): JSONObject = JSONObject().put(
        "directions",
        JSONArray().apply {
            list.filter { it.status != "dropped" || it.byUser }.forEach {
                put(
                    JSONObject().put("id", it.id).put("title", it.title).put("why", it.why).put("how_i_help", it.howIHelp)
                        .put("good_when", it.goodWhen).put("source", it.source).put("status", it.status)
                        .put("set_by_user", it.byUser),
                )
            }
        },
    )

    private fun dayPlanTool(p: JSONObject, bubblesPerDay: Int): JSONObject {
        val action = p.optString("action", "get")
        val date = when (val d = p.optString("date", "today").trim()) {
            "", "today" -> today()
            "tomorrow" -> tomorrow()
            else -> runCatching { LocalDate.parse(d).toString() }.getOrNull()
                ?: throw IllegalArgumentException("date is today, tomorrow or YYYY-MM-DD; got $d")
        }
        return when (action) {
            "get" -> day(date)?.let { dayJson(it) } ?: JSONObject().put("date", date).put("exists", false)
            "write" -> write(date, p, bubblesPerDay)
            "mark" -> synchronized(lock) {
                val id = req(p, "id")
                val status = req(p, "status")
                require(status in setOf("done", "dropped", "moved")) { "status for mark is done, dropped or moved" }
                val root = load()
                val d = root.optJSONObject("days")?.optJSONObject(date)?.let(::toDay)
                    ?: throw IllegalArgumentException("No plan for $date")
                require(d.items.any { it.id == id }) { "No item $id in the plan for $date" }
                putDay(root, d.copy(items = d.items.map { if (it.id == id) it.copy(status = status, note = p.optString("note")) else it }, updatedAt = clock()))
                save(root)
                JSONObject().put("marked", true).put("id", id).put("status", status)
            }
            else -> throw IllegalArgumentException("action must be get, write or mark; got $action")
        }
    }

    /**
     * Replace the still-planned part of [date]'s plan. Items that already happened (done, ignored, …) stay as
     * the day's history; what the user removed today cannot come back.
     */
    private fun write(date: String, p: JSONObject, bubblesPerDay: Int): JSONObject = synchronized(lock) {
        val intent = req(p, "intent")
        val arr = p.optJSONArray("items") ?: JSONArray()
        require(arr.length() <= MAX_ITEMS) { "At most $MAX_ITEMS items in a day." }
        val root = load()
        val old = root.optJSONObject("days")?.optJSONObject(date)?.let(::toDay)
        val kept = old?.items?.filter { it.status != "planned" }.orEmpty()
        val dropped = old?.userDropped.orEmpty()
        var n = (kept.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0)
        val refused = JSONArray()
        val fresh = mutableListOf<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val what = o.optString("what").trim()
            require(what.isNotEmpty()) { "Every item needs `what`." }
            val kind = o.optString("kind", "remind").trim()
            require(kind in KINDS) { "kind is one of $KINDS; got $kind" }
            val because = o.optString("because").trim()
            require(because.isNotEmpty()) {
                "Item \"$what\" has no `because`. Every item points at a direction or a todo; one that cannot is not part of the plan."
            }
            val time = o.optString("time").trim()
            require(time.isEmpty() || runCatching { LocalTime.parse(time) }.isSuccess) { "time is HH:mm or empty; got $time" }
            if (what in dropped) {
                refused.put(JSONObject().put("what", what).put("reason", "The user removed this today."))
                continue
            }
            val buttons = o.optJSONArray("buttons")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
            require(buttons.size <= NudgeBook.MAX_BUTTONS) { "At most ${NudgeBook.MAX_BUTTONS} buttons." }
            if (kind in INTERRUPTING) require(what.length <= NudgeBook.MAX_TEXT) {
                "\"$what\" is the bubble's words and a bubble holds ≤ ${NudgeBook.MAX_TEXT} characters."
            }
            val dismiss = o.optString("dismiss").trim()
            require(dismiss.isEmpty() || dismiss in buttons) { "dismiss must be one of the buttons." }
            fresh += Item(
                "p${++n}", time, o.optString("slot").trim(), kind, what, because, buttons, dismiss,
                o.optString("tag").trim().lowercase().take(NudgeBook.MAX_KIND), "planned", "",
            )
        }
        val interrupting = fresh.count { it.kind in INTERRUPTING }
        require(interrupting <= MAX_INTERRUPTIONS) {
            "$interrupting items would interrupt them (remind/ask); a day has at most $MAX_INTERRUPTIONS. Keep the ones that matter."
        }
        val quietArr = p.optJSONArray("quiet")
        val quiet = if (quietArr == null) old?.quiet.orEmpty() else (0 until quietArr.length()).map { quietArr.optString(it).trim() }.filter { it.isNotEmpty() }
        val d = Day(date, intent, kept + fresh, quiet, clock(), dropped)
        putDay(root, d)
        save(root)
        JSONObject().put("written", true).put("date", date).put("items", fresh.size).put("refused", refused)
            .put(
                "note",
                (if (interrupting > bubblesPerDay) "The user's setting allows $bubblesPerDay bubbles a day; the rest will be skipped. " else "") +
                    "Items with a clock time that remind/ask are now scheduled as bubbles; the outcome is written back onto each item.",
            )
    }

    private fun dayJson(d: Day): JSONObject = JSONObject().put("date", d.date).put("exists", true).put("intent", d.intent)
        .put("quiet", JSONArray(d.quiet))
        .put(
            "items",
            JSONArray().apply {
                d.items.forEach {
                    put(
                        JSONObject().put("id", it.id).put("time", it.time).put("slot", it.slot).put("kind", it.kind)
                            .put("what", it.what).put("because", it.because).put("status", it.status).put("note", it.note),
                    )
                }
            },
        )
        .put("removed_by_user", JSONArray(d.userDropped))

    // ---- what the user does in settings ---------------------------------------------

    /** The user removed an item. Done by the device, not asked of the model. */
    fun dropItemByUser(date: String, id: String) = synchronized(lock) {
        val root = load()
        val d = root.optJSONObject("days")?.optJSONObject(date)?.let(::toDay) ?: return@synchronized
        val it = d.items.firstOrNull { it.id == id } ?: return@synchronized
        putDay(
            root,
            d.copy(
                items = d.items.map { x -> if (x.id == id) x.copy(status = "dropped", note = "removed by the user") else x },
                userDropped = (d.userDropped + it.what).distinct(), updatedAt = clock(),
            ),
        )
        save(root)
    }

    /** The user paused, resumed or removed a direction. Only they can undo it. */
    fun setDirectionByUser(id: String, status: String) = synchronized(lock) {
        val root = load()
        val list = dirs(root).map { if (it.id == id) it.copy(status = status, byUser = status != "active", updatedAt = clock()) else it }
        putDirs(root, list)
        save(root)
    }

    // ---- bubbles ----------------------------------------------------------------

    /**
     * Bring the bubble todos in line with today's and tomorrow's plans: a todo for every planned remind/ask
     * item with a clock time still ahead, none for anything else. Writes the todos into the book and returns
     * what the body has to arm and cancel.
     */
    fun reconcile(): Sync = synchronized(lock) {
        val now = clock()
        val want = HashMap<String, JSONObject>()
        val at = HashMap<String, Long>()
        for (date in listOf(today(), tomorrow())) {
            val d = day(date) ?: continue
            for (it in d.items) {
                if (it.status != "planned" || it.kind !in INTERRUPTING || it.time.isEmpty()) continue
                val t = LocalDate.parse(date).atTime(LocalTime.parse(it.time)).atZone(zone).toInstant().toEpochMilli()
                if (t <= now) continue
                val ref = "$date#${it.id}"
                want[ref] = JSONObject().put("text", it.what).put("buttons", JSONArray(it.buttons))
                    .put("dismiss", it.dismiss).put("kind", it.tag.ifEmpty { "plan-${it.id}" })
                    .put("ttl_minutes", ITEM_TTL_MIN).put("plan_ref", ref)
                at[ref] = t
            }
        }
        val arm = mutableListOf<Todo>()
        val cancel = mutableListOf<String>()
        val have = book.todos("pending").filter { it.nudge.isNotEmpty() }
        val haveRefs = HashSet<String>()
        for (t in have) {
            val spec = runCatching { JSONObject(t.nudge) }.getOrNull()
            val ref = spec?.optString("plan_ref").orEmpty()
            val w = want[ref]
            if (w != null && t.at == at[ref] && spec.toString() == w.toString()) {
                haveRefs += ref
                continue
            }
            book.patchTodo(t.id, status = "dropped", nextRunAt = 0L)
            cancel += t.id
        }
        for ((ref, spec) in want) {
            if (ref in haveRefs) continue
            val t = Todo(
                id = NotebookBook.newTodoId() + ref.filter { it.isLetterOrDigit() },
                what = "Plan bubble: ${spec.optString("text")}",
                mode = "once", at = at[ref]!!, cronExpr = "", nextRunAt = at[ref]!!,
                lastRunAt = 0L, lastStatus = "", lastError = "", status = "active", createdAt = now,
                nudge = spec.toString(),
            )
            book.addTodo(t)
            arm += t
        }
        Sync(arm, cancel)
    }

    /**
     * A bubble todo came due: turn it into a bubble, or say why not. Never wakes the model.
     *
     * @return the bubble to post, or null when it is stale or over the user's limits — in which case the
     *   plan item already carries the reason.
     */
    fun bubbleFor(todo: Todo, nudges: NudgeBook, level: NudgeBook.Level, muted: Set<String>): Nudge? {
        book.patchTodo(todo.id, status = "done")
        val spec = runCatching { JSONObject(todo.nudge) }.getOrNull() ?: return null
        val ref = spec.optString("plan_ref")
        val n = nudges.parse(spec, untrusted = todo.untrusted)
        val base = if (todo.at > 0) todo.at else clock()
        val bubble = n.copy(expiresAt = base + spec.optLong("ttl_minutes", ITEM_TTL_MIN) * 60_000L, planRef = ref)
        if (clock() >= bubble.expiresAt) {
            mark(ref, "missed", "the device was not running when it was due")
            return null
        }
        nudges.admit(bubble, level, muted)?.let { why ->
            mark(ref, "skipped", why)
            return null
        }
        return bubble
    }

    /** A bubble todo came due with nothing running to show it. Closed, and the item says so — no model, no notification. */
    fun missed(todo: Todo) {
        book.patchTodo(todo.id, status = "done")
        runCatching { JSONObject(todo.nudge).optString("plan_ref") }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { mark(it, "missed", "nothing was running when it was due") }
    }

    /** Write what happened to a bubble back onto its plan item. See [statusFor]. */
    fun recordOutcome(n: Nudge, how: String, button: String?) {
        if (n.planRef.isEmpty()) return
        statusFor(n, how, button)?.let { mark(n.planRef, it, "") }
    }

    private fun mark(ref: String, status: String, note: String) = synchronized(lock) {
        val date = ref.substringBefore('#')
        val id = ref.substringAfter('#')
        val root = load()
        val d = root.optJSONObject("days")?.optJSONObject(date)?.let(::toDay) ?: return@synchronized
        putDay(root, d.copy(items = d.items.map { if (it.id == id) it.copy(status = status, note = note) else it }))
        save(root)
    }

    // ---- when to think (PlanMind) ---------------------------------------------------------

    private fun meta(root: JSONObject): JSONObject = root.optJSONObject("meta") ?: JSONObject()

    private fun minuteOfDay(): Int = Instant.ofEpochMilli(clock()).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

    /** Today's plan has not been looked at in the light of the morning yet, and it is morning or later. */
    fun morningDue(): Boolean = synchronized(lock) {
        meta(load()).optString("morning_date") != today() && minuteOfDay() >= MORNING_FROM_MIN
    }

    fun markMorning() = synchronized(lock) {
        val root = load()
        root.put("meta", meta(root).put("morning_date", today())); save(root)
    }

    /**
     * Whether to ask "is it the moment for one of today's loose-time items?" — only when there is such an item
     * to decide about, outside the night, not more often than [MUSE_GAP_MS] and not more than [MUSE_PER_DAY]
     * a day. Everything else in the plan has a clock time and needs no thought to come due.
     */
    fun museDue(): Boolean = synchronized(lock) {
        if (NudgeBook.isQuiet(minuteOfDay())) return false
        val root = load()
        val m = meta(root)
        if (clock() - m.optLong("muse_at") < MUSE_GAP_MS) return false
        if (m.optString("muse_date") == today() && m.optInt("muse_count") >= MUSE_PER_DAY) return false
        val d = root.optJSONObject("days")?.optJSONObject(today())?.let(::toDay) ?: return false
        d.items.any { it.status == "planned" && it.kind in INTERRUPTING && it.time.isEmpty() }
    }

    fun markMuse() = synchronized(lock) {
        val root = load()
        val m = meta(root)
        val count = if (m.optString("muse_date") == today()) m.optInt("muse_count") + 1 else 1
        root.put("meta", m.put("muse_at", clock()).put("muse_date", today()).put("muse_count", count)); save(root)
    }

    /** Everything the morning look needs, as text: who, what for, what happened yesterday, what is on today. */
    fun morningContext(): String = synchronized(lock) {
        val root = load()
        buildString {
            append("Now: ").append(Instant.ofEpochMilli(clock()).atZone(zone).toLocalDateTime().toString().take(16).replace('T', ' '))
            append(" (").append(LocalDate.parse(today()).dayOfWeek).append(")\n\n")
            val dirs = dirs(root).filter { it.status == "active" }
            append("Long-term directions:\n")
            if (dirs.isEmpty()) append("(none yet)\n")
            dirs.forEach { append("- ${it.title}: ${it.howIHelp} (because: ${it.why}; from: ${it.source})\n") }
            val y = root.optJSONObject("days")?.optJSONObject(LocalDate.parse(today()).minusDays(1).toString())?.let(::toDay)
            append("\nYesterday's plan and what happened:\n")
            if (y == null) append("(none)\n") else y.items.forEach { append("- ${it.time.ifEmpty { it.slot }} ${it.kind} \"${it.what}\" → ${it.status}${if (it.note.isNotEmpty()) " (${it.note})" else ""}\n") }
            val t = root.optJSONObject("days")?.optJSONObject(today())?.let(::toDay)
            append("\nToday's draft:\n")
            if (t == null) {
                append("(none)\n")
            } else {
                append("intent: ${t.intent}\n")
                t.items.forEach { append("- ${it.time.ifEmpty { it.slot }} ${it.kind} \"${it.what}\" because ${it.because} → ${it.status}\n") }
                if (t.quiet.isNotEmpty()) append("quiet: ${t.quiet.joinToString("; ")}\n")
                if (t.userDropped.isNotEmpty()) append("removed by the user today (never put back): ${t.userDropped.joinToString("; ")}\n")
            }
            val end = LocalDate.parse(today()).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val due = book.todos("pending").filter { it.nudge.isEmpty() && it.status == "active" && it.nextRunAt in 1 until end }
            append("\nTodos due today:\n")
            if (due.isEmpty()) append("(none)\n")
            due.forEach { append("- ${Instant.ofEpochMilli(it.nextRunAt).atZone(zone).toLocalTime().toString().take(5)} ${it.what}\n") }
            val notes = book.indexForPrompt()
            if (notes.isNotEmpty()) append("\nWhat your notebook holds about them:\n").append(notes).append('\n')
        }
    }

    /** Today's loose-time interrupting items, for the muse to choose from (or not). Null when there are none. */
    fun museContext(): String? = synchronized(lock) {
        val d = day(today()) ?: return null
        val open = d.items.filter { it.status == "planned" && it.kind in INTERRUPTING && it.time.isEmpty() }
        if (open.isEmpty()) return null
        buildString {
            append("Now: ").append(Instant.ofEpochMilli(clock()).atZone(zone).toLocalTime().toString().take(5)).append('\n')
            append("Today's intent: ${d.intent}\n")
            if (d.quiet.isNotEmpty()) append("Not today: ${d.quiet.joinToString("; ")}\n")
            append("\nWhat already happened today:\n")
            d.items.filter { it.status != "planned" }.forEach { append("- ${it.time.ifEmpty { it.slot }} \"${it.what}\" → ${it.status}\n") }
            append("\nWaiting for the right moment (choose at most one, by id):\n")
            open.forEach { append("- ${it.id}: when \"${it.slot}\", ${it.kind} \"${it.what}\"\n") }
        }
    }

    /** The muse chose an item: its bubble, admitted against the user's limits, or null with the reason recorded. */
    fun bubbleForItem(id: String, nudges: NudgeBook, level: NudgeBook.Level, muted: Set<String>): Nudge? {
        val date = today()
        val it = day(date)?.items?.firstOrNull { x -> x.id == id && x.status == "planned" && x.kind in INTERRUPTING } ?: return null
        val spec = JSONObject().put("text", it.what).put("buttons", JSONArray(it.buttons)).put("dismiss", it.dismiss)
            .put("kind", it.tag.ifEmpty { "plan-${it.id}" }).put("ttl_minutes", MUSE_TTL_MIN)
        val n = nudges.parse(spec, untrusted = false).copy(planRef = "$date#$id")
        nudges.admit(n, level, muted)?.let { why ->
            mark(n.planRef, "skipped", why)
            return null
        }
        return n
    }

    /** A week since the directions were last looked at, and there are some to look at. */
    private fun directionsReviewDue(root: JSONObject): Boolean =
        dirs(root).any { it.status == "active" } && clock() - meta(root).optLong("directions_reviewed_at") >= WEEK_MS

    // ---- what rides on every user message ---------------------------------------------

    fun indexForPrompt(): String = synchronized(lock) {
        val root = load()
        val active = dirs(root).filter { it.status == "active" }
        val d = root.optJSONObject("days")?.optJSONObject(today())?.let(::toDay)
        if (d == null && active.isEmpty()) {
            return "No plan yet: no long-term directions, nothing planned today (plan_directions / day_plan)."
        }
        buildString {
            if (d == null) {
                append("No plan for today yet.")
            } else {
                append("Today's plan: \"${d.intent}\"")
                val next = d.items.filter { it.status == "planned" }.sortedBy { it.time.ifEmpty { "99" } }.take(2)
                if (next.isNotEmpty()) append(" — next: ").append(next.joinToString("; ") { "${it.time.ifEmpty { it.slot }} ${it.kind} \"${it.what}\"" })
                val counts = d.items.groupingBy { it.status }.eachCount().entries.joinToString(", ") { "${it.key} ${it.value}" }
                if (counts.isNotEmpty()) append(" ($counts)")
                if (d.quiet.isNotEmpty()) append(". Not today: ").append(d.quiet.joinToString("; "))
                append('.')
            }
            if (active.isNotEmpty()) append(" Long-term: ").append(active.joinToString("; ") { it.title }).append('.')
            if (directionsReviewDue(root)) {
                append(" A week since you last looked over the directions: in your next quiet review, plan_directions list — done, stalled, or something new they said?")
            }
        }
    }

    // ---- json ----------------------------------------------------------------------

    private fun toDirection(o: JSONObject) = Direction(
        o.optString("id"), o.optString("title"), o.optString("why"), o.optString("how_i_help"),
        o.optString("good_when"), o.optString("source"), o.optString("status", "active"),
        o.optBoolean("by_user"), o.optLong("updated_at"),
    )

    private fun toJson(d: Direction) = JSONObject().put("id", d.id).put("title", d.title).put("why", d.why)
        .put("how_i_help", d.howIHelp).put("good_when", d.goodWhen).put("source", d.source)
        .put("status", d.status).put("by_user", d.byUser).put("updated_at", d.updatedAt)

    private fun toDay(o: JSONObject): Day {
        val items = o.optJSONArray("items") ?: JSONArray()
        return Day(
            o.optString("date"), o.optString("intent"),
            (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map { i ->
                Item(
                    i.optString("id"), i.optString("time"), i.optString("slot"), i.optString("kind"), i.optString("what"),
                    i.optString("because"),
                    i.optJSONArray("buttons")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                    i.optString("dismiss"), i.optString("tag"), i.optString("status", "planned"), i.optString("note"),
                )
            },
            strings(o.optJSONArray("quiet")), o.optLong("updated_at"), strings(o.optJSONArray("user_dropped")),
        )
    }

    private fun toJson(d: Day) = JSONObject().put("date", d.date).put("intent", d.intent)
        .put(
            "items",
            JSONArray().apply {
                d.items.forEach {
                    put(
                        JSONObject().put("id", it.id).put("time", it.time).put("slot", it.slot).put("kind", it.kind)
                            .put("what", it.what).put("because", it.because).put("buttons", JSONArray(it.buttons))
                            .put("dismiss", it.dismiss).put("tag", it.tag).put("status", it.status).put("note", it.note),
                    )
                }
            },
        )
        .put("quiet", JSONArray(d.quiet)).put("updated_at", d.updatedAt).put("user_dropped", JSONArray(d.userDropped))

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    private fun req(p: JSONObject, key: String): String =
        p.optString(key).trim().ifEmpty { throw IllegalArgumentException("$key is required") }

    companion object {
        const val SECTION = "plan"
        const val MAX_DIRECTIONS = 5
        const val MAX_ITEMS = 12
        const val MAX_INTERRUPTIONS = 5
        const val KEEP_DAYS = 7L
        /** A planned bubble is worth showing for an hour after its time, not longer. */
        const val ITEM_TTL_MIN = 60L
        const val MORNING_FROM_MIN = 5 * 60
        const val MUSE_GAP_MS = 40 * 60_000L
        const val MUSE_PER_DAY = 12
        const val MUSE_TTL_MIN = 30L
        const val WEEK_MS = 7 * 24 * 60 * 60_000L
        val KINDS = setOf("remind", "ask", "prepare", "follow_up")
        val INTERRUPTING = setOf("remind", "ask")

        /**
         * What a bubble's end means for its plan item. A bubble with only words that faded on screen was
         * delivered — there was nothing to answer; one with buttons that faded was ignored. Null = no change
         * (it was displaced and will come back).
         */
        fun statusFor(n: Nudge, how: String, button: String?): String? = when (how) {
            "button" -> if (button != null && button == n.dismiss) "declined" else "done"
            "body", "logged" -> "done"
            "timeout" -> if (n.buttons.isEmpty()) "done" else "ignored"
            "mute" -> "declined"
            "expired" -> "missed"
            else -> null
        }
    }
}
