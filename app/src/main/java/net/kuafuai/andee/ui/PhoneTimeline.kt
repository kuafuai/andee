package net.kuafuai.andee.ui

import android.content.Context
import net.kuafuai.andee.R
import net.kuafuai.andee.brain.BrainEvent
import net.kuafuai.andee.i18n.AppLocale

/**
 * The phone's twin of the Mac's `TimelineEvents`: everything that happens that is not conversation, as one quiet
 * row in the scrollback — what woke it, what it wrote down, what it did on its own, what it let pass. Nothing it
 * does by itself is invisible.
 *
 * Two sources: the brain ([brain], from [BrainEvent], through `AndroidBrainHost.event`) and the body ([body], for
 * what only the phone knows — a heartbeat's thought, a bubble, a notification it let pass). Both end in
 * [ChatHistory.addEvent], which the brain never reads back. A heartbeat that decided to sleep writes nothing.
 */
object PhoneTimeline {

    /** Body-side event kinds, alongside [BrainEvent.Kind]'s names. */
    enum class Body { THOUGHT, WORK, BUBBLE, BUBBLE_MUTED, IGNORED, MORNING, SCENE, VAULT }

    private fun s(c: Context, id: Int, vararg a: Any) = AppLocale.str(c, id, *a)

    fun brain(c: Context, e: BrainEvent) {
        val detail = when (e.kind) {
            // A review is one row; what it wrote is the detail, one line each.
            BrainEvent.Kind.REVIEWED -> e.items.joinToString("\n") { "- " + line(c, it) }
            BrainEvent.Kind.CARE -> ""
            BrainEvent.Kind.TODO -> e.detail.takeIf { it.isNotBlank() }?.let { s(c, R.string.tl_todo_at, it) }.orEmpty()
            else -> e.detail
        }
        ChatHistory.addEvent(e.kind.name, line(c, e), detail, e.ref)
    }

    /** One event in words. An [BrainEvent.updated] write says so, so a rewrite does not read as something new. */
    private fun line(c: Context, e: BrainEvent): String {
        val x = e.subject
        val u = e.updated
        return when (e.kind) {
            BrainEvent.Kind.WOKE -> s(c, R.string.tl_woke, x)
            BrainEvent.Kind.NOTIFIED -> s(c, R.string.tl_notified)
            BrainEvent.Kind.FRIEND_TASK -> s(c, R.string.tl_friend_task)
            BrainEvent.Kind.REVIEWED -> s(c, R.string.tl_reviewed, x)
            BrainEvent.Kind.FOLDED -> s(c, R.string.tl_folded, x)
            BrainEvent.Kind.DIARY -> s(c, if (u) R.string.tl_diary_updated else R.string.tl_diary)
            BrainEvent.Kind.SELF -> s(c, R.string.tl_self)
            BrainEvent.Kind.WIKI -> s(c, R.string.tl_wiki, x)
            BrainEvent.Kind.WIKI_READ -> s(c, R.string.tl_wiki_read, x)
            BrainEvent.Kind.WIKI_SHOWN -> s(c, R.string.tl_wiki_shown, x)
            BrainEvent.Kind.PERSON -> s(c, R.string.tl_person, x)
            BrainEvent.Kind.CARE -> when (e.detail) {
                "+" -> s(c, R.string.tl_care_add, x)
                "done" -> s(c, R.string.tl_care_done)
                else -> s(c, R.string.tl_care_drop)
            }
            BrainEvent.Kind.MEMORY -> s(c, if (u) R.string.tl_memory_updated else R.string.tl_memory, x)
            BrainEvent.Kind.TODO -> s(c, R.string.tl_todo, x)
            BrainEvent.Kind.PLAN -> s(
                c,
                when {
                    x == "tomorrow" && u -> R.string.tl_plan_tomorrow_updated
                    x == "tomorrow" -> R.string.tl_plan_tomorrow
                    u -> R.string.tl_plan_today_updated
                    else -> R.string.tl_plan_today
                },
            )
        }
    }

    fun body(kind: Body, text: String, detail: String = "") = ChatHistory.addEvent(kind.name, text, detail)

    fun thought(c: Context, text: String) = body(Body.THOUGHT, s(c, R.string.tl_thought, text))
    fun workStarted(c: Context, what: String, why: String) = body(Body.WORK, s(c, R.string.tl_work_started, what), why)
    fun workDone(c: Context, what: String, effect: String, summary: String?) = body(
        Body.WORK,
        s(c, when (effect) { "ACTED" -> R.string.tl_work_acted; "LOOKED" -> R.string.tl_work_looked; else -> R.string.tl_work_nothing }, what),
        summary.orEmpty(),
    )
    fun bubble(c: Context, text: String) = body(Body.BUBBLE, s(c, R.string.tl_bubble, text))
    fun ignored(c: Context, app: String, why: String) = body(Body.IGNORED, s(c, R.string.tl_ignored, app), why)
    fun held(c: Context, app: String, text: String) = body(Body.IGNORED, s(c, R.string.tl_held, app), text)
    fun capped(c: Context, app: String, text: String) = body(Body.IGNORED, s(c, R.string.tl_capped, app), text)
    fun morning(c: Context, intent: String) = body(Body.MORNING, s(c, R.string.tl_morning), intent)
}
