package net.kuafuai.andee.brain

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the context let go of, kept where it can be read back — and the one note that lets a restarted brain
 * pick the conversation up again. One folder per body (`BrainHost.archiveDir`): `filesDir/context` on the
 * phone, `~/Library/Application Support/Andee/context` on the Mac. Wiped with the scrollback, because it is the
 * same conversation.
 *
 * **Why it exists.** A harvest replaced the old part of the conversation with a 300-word summary, and the
 * hard trim dropped turns with no summary at all; neither left anything behind but the scrollback, which the
 * model has no way to search. A topic the nightly review folds can be read back with `get_topic`; what the
 * context dropped could not. Now every stretch that leaves the context is written here first, the message that
 * stands in for it names its id, and `read_earlier` reads it back.
 *
 * **What it keeps.** The words both ways, which tool was used, and each tool result up to [TOOL_CHARS] — the
 * result is often the thing asked about later (「那个价格是多少」). Not screenshots, and **not the results of the
 * tools [render]'s `sensitive` names** (the vault, text messages): these files are plain text, and a verification
 * code or an account field has no business outliving the turn it was read in.
 *
 * Every method is called on the brain's loop thread; the lock is for the brief overlap of an old and a new brain
 * while one replaces the other.
 */
/** The folder name both bodies use under their own data directory. */
const val CONTEXT_ARCHIVE_DIR = "context"

class ContextArchive(private val dir: File) {

    data class Session(
        /** The harvest summary that stood at the head of the conversation, pointer included. */
        val summary: String,
        /** Scrollback rows from this moment on came after that summary. */
        val since: Long,
    )

    private val lock = Any()
    private val index = File(dir, "index.json")
    private val session = File(dir, "session.json")

    /** Write one stretch. Returns its id, or null when nothing could be written (the caller then keeps quiet about it). */
    fun write(text: String, kind: String, from: Long, to: Long, messages: Int): Long? = synchronized(lock) {
        if (text.isBlank()) return null
        runCatching {
            dir.mkdirs()
            val all = readIndex()
            val id = (all.maxOfOrNull { it.optLong("id") } ?: 0L) + 1
            atomic(File(dir, "ctx_$id.txt"), text.take(MAX_TEXT))
            all += JSONObject().put("id", id).put("kind", kind).put("from", from).put("to", to)
                .put("messages", messages).put("chars", text.length.coerceAtMost(MAX_TEXT))
                .put("first", firstLine(text)).put("at", System.currentTimeMillis())
            while (all.size > MAX_ARCHIVES) {
                val gone = all.removeAt(0)
                File(dir, "ctx_${gone.optLong("id")}.txt").delete()
            }
            atomic(index, JSONArray(all).toString())
            id
        }.getOrNull()
    }

    /**
     * Add [text] to the end of stretch [id] — the trim's, so consecutive cuts are one archive. False when there is no
     * such stretch, it is full, or it could not be written; the caller then writes a new one.
     */
    fun append(id: Long, text: String, to: Long, messages: Int): Boolean = synchronized(lock) {
        if (text.isBlank()) return true
        runCatching {
            val all = readIndex()
            val meta = all.firstOrNull { it.optLong("id") == id } ?: return false
            val f = File(dir, "ctx_$id.txt")
            val now = f.readText()
            if (now.length + text.length >= MAX_TEXT) return false
            atomic(f, now + "\n" + text)
            meta.put("to", if (to > 0) to else meta.optLong("to")).put("messages", meta.optInt("messages") + messages)
                .put("chars", now.length + 1 + text.length).put("at", System.currentTimeMillis())
            // The one written to last is the newest: move it to the end, so eviction and the list agree.
            all.remove(meta)
            all += meta
            atomic(index, JSONArray(all).toString())
            true
        }.getOrDefault(false)
    }

    /** The newest stretches, one line each, for `read_earlier` with no id. */
    fun list(locale: Locale): String = synchronized(lock) {
        val all = readIndex()
        if (all.isEmpty()) return "Nothing has been archived yet: no part of the conversation has left the context."
        val f = SimpleDateFormat("MM-dd HH:mm", locale)
        all.takeLast(LIST_MAX).reversed().joinToString("\n") { o ->
            val span = if (o.optLong("from") > 0) "${f.format(Date(o.optLong("from")))}–${f.format(Date(o.optLong("to")))}"
            else f.format(Date(o.optLong("at")))
            "#${o.optLong("id")} $span · ${o.optInt("messages")} msgs · ${o.optInt("chars")} chars · 「${o.optString("first")}」"
        }
    }

    /** One stretch from [offset], at most [READ_CHARS] of it. Null when there is no such id. */
    fun read(id: Long, offset: Int): JSONObject? = synchronized(lock) {
        val meta = readIndex().firstOrNull { it.optLong("id") == id } ?: return null
        val text = runCatching { File(dir, "ctx_$id.txt").readText() }.getOrNull() ?: return null
        val start = offset.coerceIn(0, text.length)
        val end = (start + READ_CHARS).coerceAtMost(text.length)
        JSONObject().put("id", id).put("from", meta.optLong("from")).put("to", meta.optLong("to"))
            .put("text", text.substring(start, end)).put("offset", start)
            .put("next_offset", if (end < text.length) end else JSONObject.NULL)
            .put("total_chars", text.length)
    }

    fun saveSession(s: Session) = synchronized(lock) {
        runCatching {
            dir.mkdirs()
            atomic(session, JSONObject().put("summary", s.summary).put("since", s.since).toString())
        }
        Unit
    }

    fun loadSession(): Session? = synchronized(lock) {
        val o = runCatching { JSONObject(session.readText()) }.getOrNull() ?: return null
        val summary = o.optString("summary")
        if (summary.isBlank()) null else Session(summary, o.optLong("since"))
    }

    private fun readIndex(): MutableList<JSONObject> {
        val arr = runCatching { JSONArray(index.readText()) }.getOrNull() ?: return mutableListOf()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.toMutableList()
    }

    private fun atomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.contains("我：") }?.substringAfter("我：")?.trim()?.take(40)
            ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(40).orEmpty()

    companion object {
        const val MAX_ARCHIVES = 100
        const val MAX_TEXT = 200_000
        const val READ_CHARS = 20_000
        const val LIST_MAX = 12
        /** How much of each tool result is kept. Enough for a price list or a recall; not a page's whole source. */
        const val TOOL_CHARS = 3_000

        /**
         * A stretch of the brain's history as plain text. The user's words lose the device's index block
         * ([TopicSplitter.spokenText]) but keep their clock; [original] gives back what [ContextPruner] cut;
         * [sensitive] names the tools whose results are never written down.
         */
        fun render(
            messages: List<JSONObject>,
            original: (JSONObject) -> String?,
            sensitive: (String) -> Boolean,
        ): String {
            val names = HashMap<String, String>()
            for (m in messages) {
                val calls = m.optJSONArray("tool_calls") ?: continue
                for (k in 0 until calls.length()) {
                    val c = calls.optJSONObject(k) ?: continue
                    names[c.optString("id")] = c.optJSONObject("function")?.optString("name").orEmpty()
                }
            }
            val sb = StringBuilder()
            for (m in messages) {
                val raw = original(m) ?: (m.opt("content") as? String)
                when (m.optString("role")) {
                    "user" -> {
                        if (raw == null) continue // a screenshot or a photo: not text, not kept
                        val stamp = raw.takeIf { it.startsWith("[now ") }?.let { it.substring(0, it.indexOf(']') + 1) }.orEmpty()
                        val words = TopicSplitter.spokenText(raw)
                        if (words.isNotEmpty()) sb.append(stamp).append(if (stamp.isEmpty()) "" else " ").append("我：").append(words).append("\n")
                    }
                    "assistant" -> {
                        if (!raw.isNullOrBlank()) sb.append("他：").append(raw.trim()).append('\n')
                        val calls = m.optJSONArray("tool_calls")
                        if (calls != null) for (k in 0 until calls.length()) {
                            val f = calls.optJSONObject(k)?.optJSONObject("function") ?: continue
                            sb.append("（调用 ").append(f.optString("name")).append(' ')
                                .append(f.optString("arguments").take(300)).append("）\n")
                        }
                    }
                    "tool" -> {
                        val name = names[m.optString("tool_call_id")].orEmpty()
                        val body = raw.orEmpty()
                        when {
                            sensitive(name) -> sb.append("（").append(name).append(" 的结果不留存）\n")
                            body.isBlank() -> {}
                            body.length <= TOOL_CHARS -> sb.append("（").append(name).append(" 返回）").append(body).append('\n')
                            else -> sb.append("（").append(name).append(" 返回，前 ").append(TOOL_CHARS).append(" 字 / 共 ")
                                .append(body.length).append(" 字）").append(body.take(TOOL_CHARS)).append('\n')
                        }
                    }
                }
            }
            return sb.toString().trim()
        }
    }
}
