package net.kuafuai.andee.brain

/**
 * Which messages the hard backstop drops when the history is past its limit.
 *
 * Pure, so the shape that once emptied a conversation is pinned in a test: a long task is ONE plain user
 * message followed by a hundred assistant and tool messages. Cutting "to the next clean boundary" from the
 * front ran off the end when no boundary followed, and threw away everything but the system prompt — so the
 * next sentence arrived with nothing before it and the model had to ask what was being built.
 */
object HistoryTrim {

    /**
     * @param boundary one flag per message, true for a plain `user` message (the only place a conversation
     *   may be cut). Index 0 is the system message and is never dropped.
     * @param keepFrom nothing at or after this index is dropped either — the start of a task that stopped at
     *   the step limit, which another turn may have pushed out of "newest" before the user says 继续.
     * @return indexes to remove. Whole turns from the front, oldest first, and never the newest turn: it
     *   stays intact however long it has grown, because it is the one the conversation is still in.
     */
    fun dropIndexes(boundary: List<Boolean>, max: Int, keepFrom: Int = Int.MAX_VALUE): List<Int> {
        if (boundary.size <= max) return emptyList()
        val last = (boundary.lastIndex downTo 1).firstOrNull { boundary[it] } ?: return emptyList()
        val newest = minOf(last, keepFrom)
        val drop = ArrayList<Int>()
        var i = 1
        var size = boundary.size
        while (size > max && i < newest) {
            // One whole turn: its user message and everything up to the next user message.
            drop += i; size--; i++
            while (i < newest && !boundary[i]) { drop += i; size--; i++ }
        }
        return drop
    }
}
