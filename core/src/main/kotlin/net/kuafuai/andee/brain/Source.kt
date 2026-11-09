package net.kuafuai.andee.brain

import net.kuafuai.andee.config.People
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where a turn came from: which app, which conversation, who said it and what they said. Written by the device when
 * the turn starts and shown to the model on every step of it, so "who asked" is a record rather than a memory.
 *
 * The failure it exists for: A asked something in B群, Andee went to C群 to ask its leader, and came back to B群 and
 * @-ed somebody else — it was looking for A by a name it half remembered, among nicknames that do not match the
 * contact book. A record of the exact words A wrote lets it find A's message and quote-reply it instead.
 *
 * Only facts the notification carried. A field the notification did not have stays blank and the prompt says so; a
 * guessed sender is worse than none, because it would be trusted.
 */
data class Source(
    /** `notify` today; `voice`, `typed`, `friend`, `self` arrive with tasks. */
    val kind: String,
    val app: String = "",
    val pkg: String = "",
    /** The [People.Channel.kind] for this app: `wechat`, `qq`, or the package. */
    val channel: String = "",
    /** The conversation's name: the group's, or for a private chat the other person's. Blank when it is not a chat. */
    val conversation: String = "",
    /** true group, false private chat, null not known to be a chat at all. */
    val group: Boolean? = null,
    /** Who wrote it, as shown in that conversation. Blank when the notification did not say. */
    val sender: String = "",
    /** What they wrote, as much as the notification carried. */
    val quote: String = "",
    val at: Long = 0L,
    /** Set by [recordSpeaker]: the person on record, and what the device knows about them. */
    val personId: String? = null,
    val personNote: String = "",
) {

    /** The [People.Channel] the sender is reached on: a group nickname belongs to its group, a private name does not. */
    fun speakerChannel(): People.Channel? {
        if (sender.isBlank() || group == null) return null
        return People.Channel(channel, sender, if (group) conversation else "")
    }

    /** The line the turn carries. English like every other bracket line; the names stay as the app printed them. */
    fun forPrompt(): String = buildString {
        append("(where this turn came from — the device's record, not your memory)\n")
        append("app ").append(app.ifBlank { pkg })
        when (group) {
            true -> append(" · group 「").append(conversation).append("」")
            false -> append(" · private chat 「").append(conversation).append("」")
            null -> {}
        }
        if (sender.isNotBlank()) append(" · from 「").append(sender).append("」")
        else if (group == true) append(" · sender not shown in the notification")
        if (personNote.isNotBlank()) append(" ").append(personNote)
        if (at > 0) append(" · ").append(SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(at)))
        if (quote.isNotBlank()) append("\nthey wrote: 「").append(quote.take(QUOTE_MAX)).append("」")
        if (group == true && sender.isNotBlank()) {
            append(
                "\nIf you answer them in that group, find this message by its words and quote-reply it; if you @ " +
                    "anyone, it is 「$sender」 — not someone with a similar name.",
            )
        }
        append(")")
    }

    companion object {
        const val QUOTE_MAX = 120

        /** `张三: 在吗` / `张三：在吗` — how a chat app writes the sender into a group notification's text. */
        private val TAGS = Regex("""^(?:\[[^\]]{1,8}]\s*)+""")
        private val SENDER = Regex("""^([^:：\n]{1,24})[:：]\s*(.+)$""", RegexOption.DOT_MATCHES_ALL)

        /**
         * Read a notification record (the shape both bodies hand [NotificationTriage.decide]) into a source.
         *
         * Only a record with `chat` is a conversation. Android sets it from the platform's conversation extras, the
         * Mac from WeChat's request id; anything else (a like, a delivery update) has no one to answer.
         */
        fun fromNotification(n: JSONObject): Source {
            val pkg = n.optString("pkg")
            val title = n.optString("title").substringBefore(" · ").trim()
            // WeChat puts 「[2条]」 and 「[有人@我]」 in front of the sender; they are the app's, not anyone's words.
            val said = n.optString("latest").ifBlank { n.optString("text") }.trim().replace(TAGS, "")
            val group = when (n.optString("chat")) {
                "group" -> true
                "private" -> false
                else -> null
            }
            val m = SENDER.find(said)
            val (sender, quote) = when (group) {
                // The group's notification is titled with the group; the sender is the text's prefix, or unknown.
                true -> if (m != null) m.groupValues[1].trim() to m.groupValues[2].trim() else "" to said
                // A private chat is titled with the other person. Some apps repeat the name in the text; drop it.
                false -> title to (if (m != null && m.groupValues[1].trim() == title) m.groupValues[2].trim() else said)
                null -> "" to said
            }
            return Source(
                kind = "notify",
                app = n.optString("app"),
                pkg = pkg,
                channel = channelOf(pkg),
                conversation = if (group == null) "" else title,
                group = group,
                sender = sender,
                quote = quote,
                at = n.optLong("when"),
            )
        }

        fun channelOf(pkg: String): String {
            val p = pkg.lowercase()
            return when {
                p == "com.tencent.mm" || p.contains("wechat") || p.contains("xinwechat") -> "wechat"
                p == "com.tencent.mobileqq" || p.endsWith(".qq") -> "qq"
                p.contains("wework") || p.contains("wxwork") -> "wecom"
                p.contains("dingtalk") || p.contains("rimet") -> "dingtalk"
                p.contains("lark") || p.contains("feishu") -> "feishu"
                else -> pkg
            }
        }

        /**
         * Should the sender of [s] go on record? Only someone who spoke to Andee or to its user: a private chat the
         * triage thought worth telling or acting on, or any conversation a turn starts from. Not everyone who spoke in
         * a group — that would be a file on every member — and not a private message the triage ignored (adverts,
         * strangers), or 「人」 fills up with them.
         */
        fun worthRecording(s: Source, startsTurn: Boolean): Boolean =
            s.sender.isNotBlank() && s.group != null && (startsTurn || s.group == false)

        /**
         * Put the sender on record — the record they already have on this channel, else an unconfirmed one — and log
         * what they wrote. Returns [s] with the person attached for the prompt line, including anyone else on record
         * who may be the same person, so the model can merge in the same turn rather than at night.
         *
         * Never throws: a full people list or a refused write leaves the source as it was.
         */
        fun recordSpeaker(people: People, s: Source): Source = runCatching {
            val ch = s.speakerChannel() ?: return s
            val p = people.unconfirmed(s.sender, ch, "first wrote in ${if (s.group == true) s.conversation else s.app}")
            if (people.get(p.id) == null) return s // removed by the user: not written back, not shown
            val gist = s.quote.take(People.MAX_TEXT).ifBlank { "wrote" }
            if (people.log(p.id, "in", s.channel, gist) != null) people.log(p.id, "in", s.channel, "wrote (not kept: personal)")
            val like = people.lookalikes(s.sender, emptyList(), null, except = p.id).take(3)
            val note = buildString {
                append("[on record: ").append(p.id)
                if (p.name != s.sender) append(" ").append(p.name)
                if (p.status == "unconfirmed") append(", unconfirmed")
                append("]")
                if (like.isNotEmpty()) {
                    append(" [may also be: ")
                    append(like.joinToString("; ") { "${it.person.id} ${it.person.name}" })
                    append(" — person merge if you are sure]")
                }
            }
            s.copy(personId = p.id, personNote = note)
        }.getOrDefault(s)
    }
}
