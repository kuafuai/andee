package net.kuafuai.andee.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuafuai.andee.config.Tasks

/**
 * 在办 on the Mac: the 「事」 Andee has in hand, in the sidebar above 话题, and one page per 事.
 *
 * Why here and not in 小本本 (where it was a tab): a 事 is state — waiting on someone, a dsh worker on it, an answer
 * that came back — and it changes while the user is not looking; 小本本 is who Andee is. 动态 says what happened,
 * 在办 what is in hand, 话题 what was said and put away: one place each. A 事 is not a conversation of its own —
 * Andee is one individual with one thread — so 接着办 is a turn in that thread with the task named by the device.
 */
object TaskView {
    /** How many the sidebar lists before 全部 N 件 ›. */
    const val SIDEBAR_MAX = 6

    /** Answered first (something to act on), then those a dsh worker is running, then the rest, newest-touched first. */
    fun ordered(tasks: List<Tasks.Task>, running: Set<String>): List<Tasks.Task> = tasks.sortedWith(
        compareBy<Tasks.Task> {
            when {
                it.status == Tasks.DELIVERED || it.status == "ready" -> 0
                worker(it)?.let { w -> w in running } == true -> 1
                else -> 2
            }
        }.thenByDescending { it.touchedAt },
    )

    /** The dsh worker this 事 was handed to last, if any. */
    fun worker(t: Tasks.Task): String? = t.waits.lastOrNull { it.kind == "worker" }?.name

    /** One line under the title: what it is waiting on, in the reader's words. */
    fun state(t: Tasks.Task, now: Long, zh: Boolean): String {
        fun tr(a: String, b: String) = if (zh) a else b
        val w = worker(t)
        val live = w?.let { DshLive.running[it] }
        if (t.status == Tasks.DELIVERED) return tr("交付了，等你确认", "Delivered — confirm?") + t.result.takeIf { it.isNotBlank() }?.let { "：" + it.lineSequence().first().take(50) }.orEmpty()
        if (live != null) return "dsh #$w · " + DshStatus.forTask(live, now, zh)
        val answered = t.waits.lastOrNull { !it.open && !it.seen }
        if (t.status == "ready" && answered != null) return tr("回话到了：", "Answered: ") + answered.answer.lineSequence().first().take(60)
        val open = t.waits.filter { it.open }
        if (open.isNotEmpty()) return tr("在等 ", "Waiting on ") + open.joinToString(", ") { who(it, zh) } + " · " + ago(now - open.maxOf { it.at }, zh)
        if (t.next.isNotBlank()) return tr("下一步：", "Next: ") + t.next
        return tr("进行中", "Under way")
    }

    /** Where a 事 came from or goes to, in the reader's words ([net.kuafuai.andee.brain.Source.short] is the model's English). */
    fun whose(s: net.kuafuai.andee.brain.Source, zh: Boolean): String = when (s.kind) {
        "user" -> if (zh) "你" else "you"
        "self" -> if (zh) "Andee 自己" else "Andee itself"
        else -> s.short()
    }

    fun who(w: Tasks.Wait, zh: Boolean): String = when (w.kind) {
        "user" -> if (zh) "你" else "you"
        "worker" -> if (zh) "dsh 工作者 #${w.name}" else "dsh worker #${w.name}"
        else -> w.short()
    }

    fun ago(ms: Long, zh: Boolean): String {
        val m = ms / 60_000
        return when {
            m < 1 -> if (zh) "刚刚" else "just now"
            m < 60 -> if (zh) "$m 分钟" else "${m}m"
            m < 48 * 60 -> if (zh) "${m / 60} 小时" else "${m / 60}h"
            else -> if (zh) "${m / 1440} 天" else "${m / 1440}d"
        }
    }

    /** The dot's colour: an answer to act on stands out, a running worker is the accent, a wait is quiet. */
    fun dot(t: Tasks.Task): Color = when {
    t.status == Tasks.DELIVERED -> Palette.ok
    t.status == "ready" -> Palette.danger
        worker(t)?.let { DshLive.running.containsKey(it) } == true -> Palette.accent
        else -> Palette.secondary
    }
}

/** A small accent dot that fades in and out: the one mark on 在办 that says something is still working. */
@Composable
private fun RunningDot(modifier: Modifier = Modifier) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "running")
    val a by transition.animateFloat(
        initialValue = 0.12f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(600, easing = androidx.compose.animation.core.LinearEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "running-alpha",
    )
    Box(modifier.size(6.dp).background(Palette.accent.copy(alpha = a), CircleShape))
}

/** A clock the 在办 rows tick on, once a second, for the live dsh lines. */
@Composable
private fun ticking(): Long {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    return now
}

/** The tasks as they are now, read again every two seconds (an answer or a worker can change them at any time). */
@Composable
private fun activeTasks(): List<Tasks.Task> {
    var tasks by remember { mutableStateOf(DesktopNotebook.book.tasks.active()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            tasks = runCatching { DesktopNotebook.book.tasks.active() }.getOrDefault(tasks)
        }
    }
    return TaskView.ordered(tasks, DshLive.running.keys)
}

/** The sidebar's 在办: a header with 定时 ›, up to [TaskView.SIDEBAR_MAX] rows, and 全部 N 件 › past that. */
@Composable
fun SidebarTasks(nav: Nav) {
    val zh = Strings.lang() == "zh"
    fun t(a: String, b: String) = if (zh) a else b
    val tasks = activeTasks()
    val now = ticking()
    val here = nav.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t("在办", "In hand") + if (tasks.isNotEmpty()) "  ${tasks.size}" else "", color = Palette.secondary, fontSize = 12.sp,
        modifier = Modifier.clickable { nav.go("tasks") }.padding(2.dp))
        if (tasks.any { it.status == "ready" || it.status == Tasks.DELIVERED }) RedDot(Modifier.padding(start = 3.dp).align(Alignment.Top))
        // Something is running in dsh now: a quiet accent dot that breathes, apart from the red one that asks something of you.
        if (tasks.any { TaskView.worker(it)?.let { w -> w in DshLive.running } == true }) RunningDot(Modifier.padding(start = 5.dp).align(Alignment.CenterVertically))
        Spacer(Modifier.weight(1f))
        Text(t("定时 ›", "Timed ›"), color = Palette.accent, fontSize = 12.sp, modifier = Modifier.clickable { nav.go("timed") }.padding(2.dp))
    }
    Spacer(Modifier.height(4.dp))
    if (tasks.isEmpty()) {
        Text(t("Andee 接下的、一时办不完的事会列在这里，办完为止。", "What Andee has taken on and not finished shows here until it is done."),
        color = Palette.photoInk, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        return
    }
    for (task in tasks.take(TaskView.SIDEBAR_MAX)) {
        val on = here == "task:${task.id}"
        Row(
            Modifier.fillMaxWidth()
            .background(if (on) Palette.glass else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable { nav.go("task:${task.id}") }
            .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.padding(top = 6.dp).size(6.dp).background(TaskView.dot(task), CircleShape))
            Spacer(Modifier.width(7.dp))
            Column {
                Text(task.what, color = if (on) Palette.title else Palette.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(TaskView.state(task, now, zh), color = if (task.status == "ready") Palette.label else Palette.photoInk,
                fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (tasks.size > TaskView.SIDEBAR_MAX) {
        Text(t("全部 ${tasks.size} 件 ›", "All ${tasks.size} ›"), color = Palette.accent, fontSize = 12.sp,
        modifier = Modifier.clickable { nav.go("tasks") }.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** 全部: every open 事, the same rows at full width. */
@Composable
fun AllTasksScreen(nav: Nav) {
    val zh = Strings.lang() == "zh"
    val tasks = activeTasks()
    val now = ticking()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        GlassHeader(if (zh) "在办" else "In hand", if (zh) "${tasks.size} 件" else "${tasks.size}", onClose = { nav.back() })
        if (tasks.isEmpty()) Text(if (zh) "现在没有在办的事。" else "Nothing in hand.", color = Palette.photoInk, fontSize = 13.sp)
        // Finished in the last seven days, below: a closed 事 does not just vanish; it can be looked at and reopened.
        val week = 7L * 24 * 3_600_000
        val closed = remember(tasks) { DesktopNotebook.book.tasks.all().filter { !it.active && System.currentTimeMillis() - it.closedAt < week }.sortedByDescending { it.closedAt } }
        for (task in tasks) {
            Row(Modifier.fillMaxWidth().glassPanel(14).clickable { nav.push("task:${task.id}") }.padding(12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(7.dp).background(TaskView.dot(task), CircleShape))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.what, color = Palette.label, fontSize = 14.sp)
                    Text("${task.id} · " + TaskView.state(task, now, zh), color = Palette.secondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (closed.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(if (zh) "最近办完" else "Recently finished", color = Palette.muted, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            for (task in closed) {
                Row(Modifier.fillMaxWidth().glassWell(12).clickable { nav.push("task:${task.id}") }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (task.status == "done") "✓" else "—", color = Palette.secondary, fontSize = 13.sp)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(task.what, color = Palette.secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(task.closedAt)) + " · " +
                            (if (task.status == "done") (if (zh) "办完了" else "done") else (if (zh) "放下了" else "let go")) +
                            task.result.takeIf { it.isNotBlank() }?.let { " · " + it.take(60) }.orEmpty(),
                            color = Palette.photoInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

/**
 * One 事: what it is waiting on, a dsh worker's live progress, its record, and the three things to do with it —
 * 接着办 (a turn now), 说点要求 (the next words are about it), 放下.
 */
@Composable
fun TaskScreen(id: String, nav: Nav, onTalk: () -> Unit) {
    val zh = Strings.lang() == "zh"
    fun t(a: String, b: String) = if (zh) a else b
    val now = ticking()
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(id) { while (true) { kotlinx.coroutines.delay(2000); version++ } }
    val task = remember(id, version) { DesktopNotebook.book.tasks.get(id) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        // With a worker on it, the live card below says the state; the subtitle would only say it twice.
        val running = task?.let { TaskView.worker(it) }?.let { DshLive.running.containsKey(it) } == true
        GlassHeader(task?.what ?: id, task?.let { if (running) it.id else "${it.id} · " + TaskView.state(it, now, zh) }, onClose = { nav.back() })
        if (task == null) {
            Text(t("这件事已经不在了。", "This is gone."), color = Palette.photoInk, fontSize = 13.sp)
            return@Column
        }
        val worker = TaskView.worker(task)
        val live = worker?.let { DshLive.running[it] }
        if (live != null) {
            DshCard(live, Modifier.padding(bottom = 10.dp))
        }
        if (task.status == Tasks.DELIVERED) {
            // Andee says it delivered. Whether it is done is the user's to say.
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp).glassPanel(14).padding(12.dp)) {
                Text(t("Andee 说交付了：", "Andee says it delivered: ") + task.result.ifBlank { t("（没写交付了什么）", "(it did not say what)") },
                    color = Palette.label, fontSize = 13.sp, lineHeight = 19.sp)
                Text(t("办没办完，你说了算。", "Whether it is done is yours to say."), color = Palette.secondary, fontSize = 12.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(t("✓ 办完了", "✓ Done"), Modifier.weight(1f)) {
                    DesktopNotebook.book.tasks.confirm(task.id).onSuccess { c -> c.cancel.forEach(DesktopScheduler::cancel) }
                    Scrollback.event(net.kuafuai.andee.brain.BrainEvent.Kind.TASK_DONE.name, t("你确认办完了：${task.what}", "You confirmed done: ${task.what}"))
                    nav.back()
                }
                SecondaryButton(t("还没完", "Not yet"), Modifier.weight(1f)) {
                    // Back to open, and the next words are about what is missing.
                    DesktopNotebook.book.tasks.reopen(task.id, t("用户说还没完", "the user said it is not done"))
                    Assistant.beginTask(task)
                    onTalk()
                }
            }
            Spacer(Modifier.height(12.dp))
        } else if (task.active) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(t("接着办", "Carry on"), Modifier.weight(1f)) {
                    Assistant.continueTask(task)
                    nav.go("chat")
                }
                SecondaryButton(t("说点要求再办", "Say how first"), Modifier.weight(1f)) {
                    Assistant.beginTask(task)
                    onTalk()
                }
                SecondaryButton(t("办完了", "Done"), Modifier.weight(0.6f)) {
                    DesktopNotebook.book.tasks.confirm(task.id).onSuccess { c -> c.cancel.forEach(DesktopScheduler::cancel) }
                    nav.back()
                }
                SecondaryButton(t("放下", "Let it go"), Modifier.weight(0.6f)) {
                    DesktopNotebook.book.tasks.close(task.id, "dropped", t("用户在在办里放下了", "let go by the user in 在办"))
                        .onSuccess { c -> c.cancel.forEach(DesktopScheduler::cancel) }
                    nav.back()
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        // Where it came from and who gets it.
        Column(Modifier.fillMaxWidth().glassPanel(14).padding(12.dp)) {
            Text(t("从哪来：", "From: ") + TaskView.whose(task.from, zh) + task.from.quote.takeIf { it.isNotBlank() }?.let { " — 「${it.take(120)}」" }.orEmpty(),
                color = Palette.label, fontSize = 13.sp)
            if (task.deliverTo.kind != "user") Text(t("办完交给：", "Deliver to: ") + TaskView.whose(task.deliverTo, zh), color = Palette.label, fontSize = 13.sp)
            if (task.next.isNotBlank()) Text(t("下一步：", "Next: ") + task.next, color = Palette.label, fontSize = 13.sp)
            if (task.due > 0) Text(t("期限：", "Due: ") + LocalTime.format(task.due), color = Palette.label, fontSize = 13.sp)
            if (task.untrusted) Text(t("起因是别人的话，按不受信任的方式办。", "Started from someone else's words; handled as untrusted."), color = Palette.secondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(10.dp))
        // The waits, newest last: what was asked of whom, and what came back.
        if (task.waits.isNotEmpty()) {
            Text(t("等过 / 在等", "Waits"), color = Palette.muted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            for (w in task.waits) {
                Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).glassWell(12).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text((if (w.open) "○ " else "● ") + TaskView.who(w, zh) + (if (w.asked.isNotBlank()) " · " + w.asked else ""),
                        color = Palette.label, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
                        if (w.kind == "worker") DshLinks(w.name)
                    }
                    if (!w.open) Text("「" + w.answer + "」", color = Palette.secondary, fontSize = 12.sp, lineHeight = 17.sp)
                    else if (w.kind != "time") Text(t("已等 ", "Waiting ") + TaskView.ago(now - w.at, zh), color = Palette.photoInk, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (task.steps.isNotEmpty()) {
            Text(t("经过", "Steps"), color = Palette.muted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            for (s in task.steps) {
                Text(java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(s.at)) + "  " + s.text,
                color = Palette.label, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
        if (!task.active) {
            Spacer(Modifier.height(10.dp))
            SecondaryButton(t("重新打开", "Reopen")) {
                DesktopNotebook.book.tasks.reopen(task.id, t("用户重新打开", "reopened by the user"))
                version++
            }
            Spacer(Modifier.height(10.dp))
            Text((if (task.status == "done") t("办完了：", "Done: ") else t("放下了：", "Let go: ")) + task.result,
            color = Palette.secondary, fontSize = 13.sp, fontStyle = FontStyle.Italic)
        }
    }
}

/** The notebook tab that lists 定时 (only the alarms; the 事 are in the sidebar). */
const val NOTEBOOK_TAB_TODOS = 8

/** Opens a 事 from places that hold no [Nav] (a timeline row). Set by [AppRoot]. */
object TaskNav {
    @Volatile var open: (String) -> Unit = {}
}
