package net.kuafuai.andee.config

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * The device's own record of what Andee did without being asked (`docs/design/inner-drive.zh-CN.md` §7, §11):
 * how many heartbeats and work turns today, which tool calls those work turns made, the thoughts a heartbeat
 * left without starting anything, and how the last work turn went.
 *
 * Every number here is counted by the device and read by the device. The model can see some of it, but never
 * writes it — a budget the model reported against itself would be a budget it could talk its way past.
 *
 * Stored in the notebook under `drive`, like [Selfhood] under `self`, so wipe and export cover it. One day's
 * counters at a time: a new date starts from zero.
 */
class Drive(
    private val book: NotebookBook,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    data class Thought(val at: Long, val text: String)

    /** The last work turn, for the next heartbeat to read and for the 日记 tab to show. */
    data class LastWork(val at: Long, val careId: String, val what: String, val effect: String, val summary: String)

    private val lock = Any()

    private fun load(): JSONObject = book.section(SECTION)
    private fun save(o: JSONObject) = book.putSection(SECTION, o)

    fun today(): String = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate().toString()

    /** The `day` object for today, reset when the date has moved on. */
    private fun day(root: JSONObject): JSONObject {
        val d = root.optJSONObject("day")
        if (d != null && d.optString("date") == today()) return d
        return JSONObject().put("date", today()).put("beats", 0).put("works", 0).put("calls", JSONArray()).put("thoughts", JSONArray())
            .also { root.put("day", it) }
    }

    fun lastBeatAt(): Long = synchronized(lock) { load().optLong("last_beat") }
    fun beatsToday(): Int = synchronized(lock) { day(load()).optInt("beats") }
    fun worksToday(): Int = synchronized(lock) { day(load()).optInt("works") }

    /** Count a heartbeat that reached the model. Free gates that stopped it before the call are not counted. */
    fun markBeat() = synchronized(lock) {
        val root = load()
        val d = day(root)
        d.put("beats", d.optInt("beats") + 1)
        root.put("last_beat", clock())
        save(root)
    }

    /** Remember that a heartbeat looked, without counting it: the free gates use this to keep their own pace. */
    fun touchBeat() = synchronized(lock) { val root = load(); root.put("last_beat", clock()); save(root) }

    /** Count a work turn as started. Counted at the start, so a turn that dies half-way still used its slot. */
    fun markWork() = synchronized(lock) {
        val root = load()
        val d = day(root)
        d.put("works", d.optInt("works") + 1)
        save(root)
    }

    /** Book a finished work turn: what it was about, what the device judged it did, and its call ids. */
    fun recordWork(careId: String, what: String, effect: String, summary: String, calls: List<String>) = synchronized(lock) {
        val root = load()
        val d = day(root)
        val a = d.optJSONArray("calls") ?: JSONArray()
        calls.forEach { a.put(it) }
        while (a.length() > MAX_CALLS) a.remove(0)
        d.put("calls", a)
        root.put("last_work", JSONObject().put("at", clock()).put("care", careId).put("what", what).put("effect", effect).put("summary", summary.take(MAX_SUMMARY)))
        save(root)
    }

    /** The tool calls today's work turns made — what a diary page may point at ([LifeTools.todaysCalls]). */
    fun todaysCalls(): List<String> = synchronized(lock) {
        val a = day(load()).optJSONArray("calls") ?: return emptyList()
        (0 until a.length()).map { a.optString(it) }
    }

    fun lastWork(): LastWork? = synchronized(lock) {
        val o = load().optJSONObject("last_work") ?: return null
        LastWork(o.optLong("at"), o.optString("care"), o.optString("what"), o.optString("effect"), o.optString("summary"))
    }

    /** A thought a heartbeat had and did not act on. Kept for today only; the night's diary is where it can go. */
    fun addThought(text: String) = synchronized(lock) {
        val t = text.trim().take(MAX_THOUGHT)
        if (t.isEmpty()) return
        val root = load()
        val d = day(root)
        val a = d.optJSONArray("thoughts") ?: JSONArray()
        a.put(JSONObject().put("at", clock()).put("text", t))
        while (a.length() > MAX_THOUGHTS) a.remove(0)
        d.put("thoughts", a)
        save(root)
    }

    fun thoughtsToday(): List<Thought> = synchronized(lock) {
        val a = day(load()).optJSONArray("thoughts") ?: return emptyList()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Thought(it.optLong("at"), it.optString("text")) }
    }

    companion object {
        const val SECTION = "drive"
        const val MAX_CALLS = 200
        const val MAX_THOUGHTS = 12
        const val MAX_THOUGHT = 200
        const val MAX_SUMMARY = 400
    }
}
