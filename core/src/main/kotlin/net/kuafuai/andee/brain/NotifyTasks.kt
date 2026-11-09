package net.kuafuai.andee.brain

import net.kuafuai.andee.config.NotebookBook
import org.json.JSONObject

/**
 * What both bodies do with a notification besides the triage, in one place so the phone and the Mac cannot drift:
 * who wrote it ([Source]), whether they go on record, and whether it answers something a task is waiting for
 * ([net.kuafuai.andee.config.Tasks]).
 */
object NotifyTasks {

    /** Before the triage: the waits this message would answer, one line each for [NotificationTriage.decide]. */
    fun awaited(book: NotebookBook, n: JSONObject): List<String> = runCatching {
        book.tasks.awaiting(Source.fromNotification(n)).map { (t, w) ->
            "${t.id}: ${t.what} — waiting on ${w.short()}" + (if (w.asked.isNotBlank()) ", asked: ${w.asked.take(120)}" else "")
        }
    }.getOrDefault(emptyList())

    /** The triage said `tell`: someone who wrote to the user in private goes on record. Nothing else. */
    fun told(book: NotebookBook, n: JSONObject) {
        val s = Source.fromNotification(n)
        if (Source.worthRecording(s, startsTurn = false)) Source.recordSpeaker(book.people, s)
    }

    /** A turn starting from [n]: its source, and the text to append to the turn — the tasks it answers, if any. */
    class Turn(val source: Source, val extra: String, val answered: List<net.kuafuai.andee.config.Tasks.Task>)

    /**
     * A turn starts from [n] (the triage said `act`, or the user tapped 看看): the sender goes on record, the waits it
     * answers are marked answered with its words, and those tasks' records ride on the turn so it can pick them up
     * and deliver — to the person who asked in the first place, not to whoever is talking now.
     */
    fun turn(book: NotebookBook, n: JSONObject): Turn {
        var s = Source.fromNotification(n)
        if (Source.worthRecording(s, startsTurn = true)) s = Source.recordSpeaker(book.people, s)
        val ready = runCatching { book.tasks.answered(s) }.getOrDefault(emptyList())
        val extra = if (ready.isEmpty()) "" else "\n\nThis message answers something you are in the middle of — the device matched " +
            "who sent it to what you were waiting for. Pick it up: deliver to whoever the task is for, then task done.\n" +
            ready.joinToString("\n\n") { book.tasks.forPrompt(it) }
        return Turn(s, extra, ready)
    }

    /** The timeline row for an answer that arrived: one per task. */
    fun answeredEvents(t: Turn): List<BrainEvent> =
        t.answered.map { BrainEvent(BrainEvent.Kind.TASK_ANSWERED, "${it.id} · ${it.what}", t.source.short() + (t.source.quote.takeIf { q -> q.isNotBlank() }?.let { q -> ": $q" } ?: "")) }
}
