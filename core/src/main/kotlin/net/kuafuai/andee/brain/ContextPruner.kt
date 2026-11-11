package net.kuafuai.andee.brain

import org.json.JSONObject

/**
 * The free half of keeping the context small: shrink what is old and bulky, call no model.
 *
 * Measured on the Mac (2026-11-11, `andee.log` + `brain_trace.jsonl`): every harvest fired on the character
 * budget, and after it the sixteen messages it kept were *still* 60–75k characters — over budget again. The
 * harvest had turned the talking into a summary and left the weight where it was: old tool results (an element
 * outline, a page's source, a `recall`) and the index block every user message carries (notebook, plan, tasks,
 * people — the newest copy is the only one that is true). So before the harvest is even considered, both are
 * cut back here:
 *
 *  * a `tool` result older than the newest turn and longer than [KEEP_TOOL_CHARS] becomes one line naming the
 *    tool and its size, saying to call it again if it is needed;
 *  * a user message older than the newest turn loses its index block and keeps its clock and its words
 *    (the core recorded when it was built — see `LocalBrain.userCore`).
 *
 * The user's words and the assistant's are never touched; nor is anything at or after [until] (the newest turn,
 * and a task the user may still say 继续 to). Changing a message in the middle costs DeepSeek's prefix cache
 * from that point, so this runs once when the budget is crossed — not a little every turn.
 *
 * The originals are handed to [keep] before they are replaced, so the archive written when this stretch is
 * finally folded away still has them.
 */
object ContextPruner {

    /** A tool result at or under this many characters is left as it is: a stub would not be much shorter. */
    const val KEEP_TOOL_CHARS = 600

    /** How the stand-in for a pruned result begins; a result that already starts with it is not pruned again. */
    const val STUB_PREFIX = "(an older result of "

    /**
     * Prune `history[1 until until]` in place. [core] gives the words-only version of a user message, or null
     * when it has none recorded (a restored or noted message, which carries no index block). [keep] receives
     * each message's original content just before it is replaced. Returns the characters saved.
     */
    fun prune(
        history: List<JSONObject>,
        until: Int,
        core: (JSONObject) -> String?,
        keep: (JSONObject, String) -> Unit,
    ): Int {
        val end = until.coerceAtMost(history.size)
        if (end <= 1) return 0
        // tool_call_id → tool name, from the assistant messages that made the calls.
        val names = HashMap<String, String>()
        for (i in 1 until end) {
            val calls = history[i].optJSONArray("tool_calls") ?: continue
            for (k in 0 until calls.length()) {
                val c = calls.optJSONObject(k) ?: continue
                names[c.optString("id")] = c.optJSONObject("function")?.optString("name").orEmpty()
            }
        }
        var saved = 0
        for (i in 1 until end) {
            val m = history[i]
            val content = m.opt("content") as? String ?: continue
            when (m.optString("role")) {
                "tool" -> {
                    if (content.length <= KEEP_TOOL_CHARS || content.startsWith(STUB_PREFIX)) continue
                    val name = names[m.optString("tool_call_id")].orEmpty().ifEmpty { "a tool" }
                    val stub = stub(name, content.length)
                    keep(m, content)
                    m.put("content", stub)
                    saved += content.length - stub.length
                }
                "user" -> {
                    val words = core(m) ?: continue
                    if (words.length >= content.length) continue
                    keep(m, content)
                    m.put("content", words)
                    saved += content.length - words.length
                }
            }
        }
        return saved
    }

    fun stub(toolName: String, chars: Int): String =
        "$STUB_PREFIX`$toolName` ($chars characters) — pruned from the context to save room, now that the turn it " +
            "belonged to is over. If you need it again, call the tool again rather than guessing what it said.)"
}
