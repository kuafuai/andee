package net.kuafuai.andee.desktop

import net.kuafuai.andee.brain.BrainEvent

/**
 * Everything that happens that is not conversation, as one line each in the timeline (动态): what woke it, what
 * it wrote down, what it did on its own, what it let pass. The design's rule is that its continuity has to be
 * true; this is the half of that the user can see — nothing it does by itself is invisible.
 *
 * Two sources: the brain ([brain], from [BrainEvent]) and the body ([body], for what only the Mac knows — a
 * heartbeat's thought, a bubble, a notification it let pass, a meeting). Both end in [Scrollback.event], which
 * the brain never reads back.
 *
 * Kept quiet on purpose: a heartbeat that decided to sleep, the usual answer, writes nothing.
 */
object TimelineEvents {

    /** Body-side event kinds, alongside [BrainEvent.Kind]'s names. */
    enum class Body { THOUGHT, WORK, BUBBLE, BUBBLE_MUTED, IGNORED, MEETING, VERSION, LOOK, VAULT, PAGE, MORNING, SHELL }

    private val zh get() = Strings.lang() == "zh"
    private fun t(z: String, e: String) = if (zh) z else e

    fun brain(e: BrainEvent) {
        val s = e.subject
        val text = when (e.kind) {
            BrainEvent.Kind.WOKE -> t("提醒到点：$s", "Reminder due: $s")
            BrainEvent.Kind.NOTIFIED -> t("一条通知值得处理，开始做", "A notification was worth acting on")
            BrainEvent.Kind.FRIEND_TASK -> t("另一台设备的 Andee 交来一件事", "Another Andee handed over a task")
            BrainEvent.Kind.REVIEWED -> if (s == "0" || s.isEmpty()) t("复盘了一遍，没有要记的", "Reviewed the conversation; nothing to keep")
                else t("复盘了一遍，记下 $s 处", "Reviewed the conversation; kept $s things")
            BrainEvent.Kind.FOLDED -> t("收起了一个话题：$s", "Put away a topic: $s")
            BrainEvent.Kind.DIARY -> t("写了今天的日记", "Wrote today's diary")
            BrainEvent.Kind.SELF -> t("改写了自我描述", "Rewrote who it is")
            BrainEvent.Kind.WIKI -> t("写进维基：$s", "Wrote to the wiki: $s")
            BrainEvent.Kind.WIKI_READ -> t("翻出了维基：$s", "Opened a wiki article: $s")
            BrainEvent.Kind.PERSON -> t("记下了一个人：$s", "Noted a person: $s")
            BrainEvent.Kind.CARE -> when (e.detail) {
                "+" -> t("开始在意：$s", "Now cares about: $s")
                "done" -> t("一件在意的事做完了", "A care is done")
                else -> t("放下了一件在意的事", "Let a care go")
            }
            BrainEvent.Kind.MEMORY -> t("记住了：$s", "Remembered: $s")
            BrainEvent.Kind.TODO -> t("排了一件待办：$s", "Set a todo: $s")
            BrainEvent.Kind.PLAN -> t("写了${if (s == "tomorrow") "明天" else "今天"}的打算", "Wrote ${if (s == "tomorrow") "tomorrow's" else "today's"} plan")
        }
        val detail = when (e.kind) {
            BrainEvent.Kind.CARE -> ""
            BrainEvent.Kind.TODO -> e.detail.takeIf { it.isNotBlank() }?.let { t("时间：$it", "At: $it") }.orEmpty()
            else -> e.detail
        }
        Scrollback.event(e.kind.name, text, detail, e.ref)
    }

    fun body(kind: Body, text: String, detail: String = "") = Scrollback.event(kind.name, text, detail)

    // ---- the words for the body's own events -------------------------------------------------

    fun thought(text: String) = body(Body.THOUGHT, t("自己想了想：$text", "Thought: $text"))
    fun workStarted(what: String, why: String) = body(Body.WORK, t("自己动起来：「$what」", "Got up for \"$what\""), why)
    fun workDone(what: String, effect: String, summary: String?) = body(
        Body.WORK,
        when (effect) {
            "ACTED" -> t("做完了「$what」的一部分", "Did some of \"$what\"")
            "LOOKED" -> t("看了看「$what」，没动手", "Looked at \"$what\", did nothing yet")
            else -> t("「$what」这次没做成什么", "Nothing came of \"$what\" this time")
        },
        summary.orEmpty(),
    )
    fun bubble(text: String) = body(Body.BUBBLE, t("冒了个气泡：$text", "Bubble: $text"))
    fun ignored(app: String, why: String) = body(Body.IGNORED, t("没打扰你：$app 的一条通知", "Let a notification from $app pass"), why)
    fun morning(intent: String) = body(Body.MORNING, t("早上想好了今天的打算", "Thought out today's plan this morning"), intent)

    /** The row's mark, from the app's own icon set (an emoji would take whatever font the Mac has). */
    fun mark(kind: String): AndeeIcon = when (kind) {
        BrainEvent.Kind.WOKE.name, BrainEvent.Kind.NOTIFIED.name, Body.IGNORED.name -> AndeeIcon.ALERT_CIRCLE
        BrainEvent.Kind.FRIEND_TASK.name -> AndeeIcon.FRIENDS
        BrainEvent.Kind.REVIEWED.name, BrainEvent.Kind.FOLDED.name -> AndeeIcon.CHECK_CIRCLE
        BrainEvent.Kind.WIKI.name, BrainEvent.Kind.WIKI_READ.name, Body.PAGE.name -> AndeeIcon.DOCUMENT
        BrainEvent.Kind.PERSON.name -> AndeeIcon.FACE
        BrainEvent.Kind.CARE.name, Body.WORK.name, Body.THOUGHT.name -> AndeeIcon.SPARKLE
        Body.BUBBLE.name, Body.BUBBLE_MUTED.name -> AndeeIcon.CHAT
        Body.MEETING.name -> AndeeIcon.MIC
        Body.VERSION.name -> AndeeIcon.LINK
        Body.LOOK.name -> AndeeIcon.IMAGE
        Body.VAULT.name -> AndeeIcon.LOCK
        Body.SHELL.name -> AndeeIcon.GRID
        else -> AndeeIcon.NOTEBOOK
    }
}
