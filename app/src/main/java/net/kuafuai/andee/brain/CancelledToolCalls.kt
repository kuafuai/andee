package net.kuafuai.andee.brain

import org.json.JSONObject

/** A cancelled batch still needs one tool result per call before the next user message. */
internal fun completeCancelledToolCalls(history: MutableList<JSONObject>) {
    val answered = mutableSetOf<String>()
    for (message in history.asReversed()) {
        if (message.optString("role") == "tool") {
            answered += message.optString("tool_call_id")
            continue
        }
        val calls = message.optJSONArray("tool_calls") ?: return
        for (i in 0 until calls.length()) {
            val id = calls.optJSONObject(i)?.optString("id") ?: continue
            if (id !in answered) {
                history += JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", id)
                    .put("content", "This task was stopped. Do not resume it unless the user asks.")
            }
        }
        return
    }
}
