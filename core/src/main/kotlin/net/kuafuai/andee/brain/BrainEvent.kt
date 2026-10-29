package net.kuafuai.andee.brain

import org.json.JSONObject

/**
 * Something that happened that is not a line of conversation: a todo woke it, the nightly review ran, it wrote
 * its diary, it folded a topic, it got up by itself. Each body shows these in its timeline so that nothing it
 * does is invisible — the design's "its continuity must be true" applies to the user's view of it too.
 *
 * Structured, not a sentence: [kind] says what, [subject] names the thing (a title, a name, a count), and the
 * body words it in the interface language. Events are **never** read back into the model's context — they
 * are the device's record of what the brain did, not something anyone said.
 */
data class BrainEvent(
    val kind: Kind,
    /** What it is about: a title, a name, the reminder's text. May be empty. */
    val subject: String = "",
    /** A longer line shown when the row is opened. May be empty. */
    val detail: String = "",
) {
    enum class Kind {
        /** A todo came due and woke it. */
        WOKE,
        /** A notification was judged worth acting on, and a turn started. */
        NOTIFIED,
        /** Another of the user's devices handed it a task. */
        FRIEND_TASK,
        /** The quiet-hour review (or the pre-trim one) finished; [subject] is how many records it touched. */
        REVIEWED,
        /** A finished stretch of conversation was put away as a topic. */
        FOLDED,
        DIARY, SELF, WIKI, PERSON, CARE, MEMORY, TODO, PLAN,
    }

    companion object {
        /**
         * The event for a tool call that landed, or null. Only writes to its own record count; reads are not
         * events. [result] decides whether the write happened (a refused write is not an event).
         */
        fun fromTool(method: String, args: JSONObject, result: Any?): BrainEvent? {
            val r = result as? JSONObject
            fun ok(key: String) = r == null || !r.has(key) || r.optBoolean(key)
            val action = args.optString("action")
            return when (method) {
                "note.diary" -> if (action == "write" && ok("written")) BrainEvent(Kind.DIARY, detail = args.optString("text").take(400)) else null
                "note.self" -> if (action == "write" && ok("written")) BrainEvent(Kind.SELF, detail = args.optString("text").take(400)) else null
                "note.wiki_write" -> if (ok("written")) BrainEvent(Kind.WIKI, args.optString("title"), args.optString("change_note")) else null
                "note.person" -> when (action) {
                    "upsert" -> if (ok("saved")) BrainEvent(Kind.PERSON, args.optString("name"), args.optString("impression")) else null
                    else -> null
                }
                "note.cares" -> when (action) {
                    "add" -> if (ok("added")) BrainEvent(Kind.CARE, args.optString("what"), "+") else null
                    "done", "drop" -> if (ok("changed")) BrainEvent(Kind.CARE, args.optString("id"), action) else null
                    else -> null
                }
                "note.remember" -> BrainEvent(Kind.MEMORY, args.optString("name"), args.optString("description"))
                "note.todo" -> if (r?.has("error") == true) null else BrainEvent(Kind.TODO, args.optString("what").take(120), args.optString("at"))
                "note.day_plan" -> if (action == "write") BrainEvent(Kind.PLAN, args.optString("date", "today"), args.optString("intent")) else null
                else -> null
            }
        }
    }
}
