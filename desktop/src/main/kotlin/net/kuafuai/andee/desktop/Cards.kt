package net.kuafuai.andee.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The phone's cards, on the Mac: self-check (✓), the notebook (小本本) and
 * scenes (情景). Same Glass surfaces, same "the person browses and deletes, the
 * model writes" split.
 */

/** The readiness checklist — the phone's ✓ card, one row per thing that can silently break. */
@Composable
fun SelfCheckCard(onDone: () -> Unit, onOpenSettings: () -> Unit) {
    val zh = Strings.lang() == "zh"
    data class CheckRow(val id: String, val name: String, val ok: Boolean, val why: String, val fix: (() -> Unit)?, val red: Boolean = false, val quiet: Boolean = false)
    // Re-read on demand. Every row here is changed in *another* app — System
    // Settings for the three permissions, the settings card for the two keys —
    // and a card that computed its rows once would go on saying "cannot see the
    // screen" after the person had just allowed it. This is the same reason the
    // phone's card carries 重新检查: checking whether a fix took should not mean
    // closing this card and finding it again.
    var refresh by remember { mutableStateOf(0) }
    // The one row that asks the network, so it is read off the UI thread and starts as "checking".
    var version by remember { mutableStateOf<VersionCheck.Result?>(null) }
    LaunchedEffect(refresh) {
        version = null
        version = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VersionCheck.probe() }
    }
    val rows = remember(refresh, version) { buildList {
        val ax = net.kuafuai.andee.desktop.mac.Bridge.axTrusted()
        add(CheckRow("ax", if (zh) "辅助功能" else "Accessibility", ax,
            if (ax) if (zh) "能读、能操作别的 app" else "Can read and drive other apps" else if (zh) "读不到屏幕，也无法操作" else "Cannot read or drive the screen",
            { openPane("Privacy_Accessibility") }))
        val rec = net.kuafuai.andee.desktop.mac.Bridge.screenCaptureAllowed()
        add(CheckRow("rec", if (zh) "录屏" else "Screen Recording", rec,
            if (rec) if (zh) "能看见屏幕" else "Can see the screen" else if (zh) "截图拿不到" else "Screenshots are blocked",
            { openPane("Privacy_ScreenCapture") }))
        val notif = net.kuafuai.andee.desktop.mac.MacNotifications.state
        if (notif == net.kuafuai.andee.desktop.mac.MacNotifications.State.NOT_FOUND) {
            // Not a permission row in disguise: no store at any known place. No button — Full Disk Access is
            // exactly the fix that does not work here, and a button to it is how this used to go wrong.
            add(CheckRow("fda", if (zh) "通知" else "Notifications", false,
                if (zh) "这台 Mac 上找不到通知数据库，不是权限问题，开完全磁盘访问也没用" else "No notification store found on this Mac — not a permission problem; Full Disk Access will not help",
                null))
        } else {
            val fda = notif != net.kuafuai.andee.desktop.mac.MacNotifications.State.NO_ACCESS
            add(CheckRow("fda", if (zh) "完全磁盘访问" else "Full Disk Access", fda,
                if (fda) if (zh) "能读通知" else "Can read notifications" else if (zh) "读不到通知数据库" else "Cannot read the notification store",
                { openPane("Privacy_AllFiles") }))
        }
        // Asked of the backend in force, not of one key's name: under CodeFlying the key is codeflying_key,
        // and the same key carries the voice. Reading llm_api_key there said "missing" for a Mac that worked.
        val cf = Assistant.config().mode == net.kuafuai.andee.config.BrainConfig.BRAIN_CODEFLYING
        val backend = Assistant.config().apiKey.isNotBlank()
        add(CheckRow("backend", if (zh) "大脑 Key" else "Model key", backend,
            if (backend) if (zh) (if (cf) "已配置（CodeFlying）" else "已配置") else (if (cf) "Configured (CodeFlying)" else "Configured")
            else if (cf) if (zh) "还没填 CodeFlying 密钥，回答不了" else "No CodeFlying key — Andee cannot answer"
            else if (zh) "还没配，回答不了" else "Missing — Andee cannot answer",
            { onOpenSettings() }))
        val voice = net.kuafuai.andee.desktop.voice.MacVoice.configured()
        add(CheckRow("voice", if (zh) "语音 Key" else "Voice key", voice,
            if (voice) if (zh) (if (cf) "能听能说（用同一个 CodeFlying 密钥）" else "能听能说") else (if (cf) "Can hear and speak (same CodeFlying key)" else "Can hear and speak")
            else if (cf) if (zh) "还没填 CodeFlying 密钥，说不了话" else "No CodeFlying key — Andee cannot speak"
            else if (zh) "还没配，说不了话" else "Missing — Andee cannot speak",
            { onOpenSettings() }))
        val v = VersionCheck.current()
        add(when (val r = version) {
            null -> CheckRow("version", if (zh) "版本" else "Version", true, if (zh) "检查中…（当前 $v）" else "Checking… (you are on $v)", null, quiet = true)
            is VersionCheck.Result.Current -> CheckRow("version", if (zh) "版本" else "Version", true, if (zh) "$v 已经是最新。" else "$v is the latest.", null)
            is VersionCheck.Result.Behind -> CheckRow("version", if (zh) "版本" else "Version", false, VersionCheck.behindText(r),
                { runCatching { net.kuafuai.andee.desktop.mac.Bridge.open(VersionCheck.upgradeUrl()) } }, red = true)
            // Could not be determined is never the user's fault: a quiet note that still names the build.
            is VersionCheck.Result.Unknown -> CheckRow("version", if (zh) "版本" else "Version", true,
                if (zh) "没能查到最新版本。当前 $v。" else "Could not check for updates. You are on $v.", null, quiet = true)
        })
        }
    }
    // Re-reading is instant and, more often than not, changes nothing — so a tap
    // that only recomputed would look like a tap that did nothing.
    var justChecked by remember { mutableStateOf(false) }
    LaunchedEffect(refresh) {
        if (refresh == 0) return@LaunchedEffect
        justChecked = true
        kotlinx.coroutines.delay(1_500)
        justChecked = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        GlassHeader(if (zh) "自检" else "Self-check", if (zh) "每一项都可能悄悄坏掉" else "Each of these can fail silently", onClose = onDone, trailing = { LangToggle() })
        for (r in rows) {
            Row(
                Modifier.fillMaxWidth().glassPanel(14).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (r.ok) AndeeIcon.CHECK_CIRCLE else AndeeIcon.ALERT_CIRCLE, if (r.ok) (if (r.quiet) Palette.secondary else Palette.ok) else if (r.red) Palette.danger else Palette.warn, 20.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = Palette.label, fontSize = 14.sp)
                    Text(r.why, color = if (r.red) Palette.danger else Palette.secondary, fontSize = 11.sp)
                }
                if (!r.ok && r.fix != null) {
                    Text(if (r.id == "version") (if (zh) "去升级" else "Upgrade") else if (zh) "去处理" else "Fix", color = Palette.accent, fontSize = 13.sp,
                        modifier = Modifier.clickable { r.fix?.invoke() }.padding(6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (zh) "重新检测" else "Check again", color = Palette.accent, fontSize = 13.sp,
                modifier = Modifier.clickable {
                    // The one row whose answer is decided at launch, not on read.
                    net.kuafuai.andee.desktop.mac.MacNotifications.recheck()
                    refresh++
                }.padding(6.dp))
            if (justChecked) {
                Spacer(Modifier.width(8.dp))
                Text(if (zh) "已重新检测" else "Just checked", color = Palette.secondary, fontSize = 11.sp)
            }
        }
    }
}

private fun openPane(anchor: String) {
    runCatching { net.kuafuai.andee.desktop.mac.Bridge.open("x-apple.systempreferences:com.apple.preference.security?$anchor") }
}

/** 产物: the pages Andee has made, one per artifact with its versions. */
@Composable
fun ArtifactsCard(onDone: () -> Unit, onContinue: () -> Unit) {
    val zh = Strings.lang() == "zh"
    var refresh by remember { mutableStateOf(0) }
    val artifacts = Pages.artifacts()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        GlassHeader(if (zh) "产物" else "Artifacts", if (zh) "它做出来的页面" else "Pages it has built", onClose = onDone)
        if (artifacts.isEmpty()) {
            Text(if (zh) "还没有产物。让 Andee 用 show_html 画一页。" else "Nothing yet. Ask Andee to draw a page with show_html.", color = Palette.photoInk, fontSize = 13.sp)
        }
        for (a in artifacts) {
            var showVersions by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().glassPanel(14).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.title, color = Palette.label, fontSize = 14.sp)
                        Text("v${a.version} · ${LocalTime.format(a.ts)}", color = Palette.secondary, fontSize = 11.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (zh) "打开" else "Open", color = Palette.accent, fontSize = 12.sp,
                        modifier = Modifier.clickable { runCatching { net.kuafuai.andee.desktop.mac.Bridge.open(a.file!!.absolutePath) } }.padding(vertical = 6.dp))
                    Text(if (zh) "继续" else "Edit", color = Palette.accent, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            Assistant.beginEdit(a)
                            onContinue()
                        }.padding(vertical = 6.dp))
                    Text(if (zh) "版本" else "Versions", color = Palette.secondary, fontSize = 12.sp,
                        modifier = Modifier.clickable { showVersions = !showVersions }.padding(vertical = 6.dp))
                }
                if (showVersions) {
                    for (v in Pages.versionsOf(a.groupId)) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("v${v.version}${if (v.file == null) "（没了）" else ""}", color = if (v.id == a.id) Palette.label else Palette.secondary, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            if (v.id != a.id && v.file != null) {
                                Text(if (zh) "回退到这版" else "Roll back", color = Palette.accent, fontSize = 12.sp,
                                    modifier = Modifier.clickable {
                                        Pages.restoreVersion(v.id)
                                        TimelineEvents.body(TimelineEvents.Body.PAGE, if (zh) "把「${v.title}」回退到 v${v.version}" else "Rolled \"${v.title}\" back to v${v.version}")
                                        refresh++
                                    }.padding(vertical = 3.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 保险箱: the user's own accounts, browsable and editable; the brain only fills, never reads a password. */
@Composable
fun VaultCard(onDone: () -> Unit) {
    val zh = Strings.lang() == "zh"
    var refresh by remember { mutableStateOf(0) }
    var adding by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val entries = net.kuafuai.andee.desktop.mac.MacVault.entries()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        GlassHeader(if (zh) "保险箱" else "Vault", if (zh) "你的账号，密码只用来填，从不读出来" else "Your accounts; passwords are filled, never read out", onClose = onDone,
            trailing = { Text("+ " + (if (zh) "添加" else "Add"), color = Palette.accent, fontSize = 13.sp, modifier = Modifier.clickable { adding = !adding }.padding(6.dp)) })
        if (adding) {
            Column(Modifier.fillMaxWidth().glassPanel(14).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassField(if (zh) "名称" else "Label", label, { label = it })
                GlassField(if (zh) "用户名" else "Username", username, { username = it })
                GlassField(if (zh) "密码" else "Password", password, { password = it }, secret = true)
                PrimaryButton(if (zh) "保存" else "Save") {
                    val all = entries.toMutableList()
                    all.removeAll { it.optString("label") == label }
                    all.add(org.json.JSONObject().put("label", label).put("username", username).put("phone", "").put("email", "").put("note", "").put("password", password))
                    net.kuafuai.andee.desktop.mac.MacVault.save(all)
                    adding = false; label = ""; username = ""; password = ""; refresh++
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (entries.isEmpty()) {
            Text(if (zh) "保险箱是空的。" else "The vault is empty.", color = Palette.photoInk, fontSize = 13.sp)
        }
        for (e in entries) {
            val l = e.optString("label")
            Row(Modifier.fillMaxWidth().glassPanel(14).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l, color = Palette.label, fontSize = 14.sp)
                    val uname = e.optString("username").ifBlank { e.optString("email").ifBlank { e.optString("phone") } }
                    Text(if (uname.isBlank()) (if (zh) "无账号" else "no username") else uname, color = Palette.secondary, fontSize = 11.sp)
                }
                Text(if (zh) "删除" else "Delete", color = Palette.danger, fontSize = 12.sp,
                    modifier = Modifier.clickable {
                        val all = entries.toMutableList().also { it.removeAll { x -> x.optString("label") == l } }
                        net.kuafuai.andee.desktop.mac.MacVault.save(all); refresh++
                    }.padding(6.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 小本本: what Andee remembers, browsable and deletable — plus the todos it owes. */
@Composable
fun NotebookCard(onDone: () -> Unit, startTab: Int = 0, startWiki: String? = null) {
    val zh = Strings.lang() == "zh"
    var selectedTab by remember { mutableStateOf(startTab) }
    var refresh by remember { mutableStateOf(0) }

    // 守卫 first, on both bodies: it is the one the user writes, and the card opens on it.
    val tabs = if (zh) listOf("守卫", "自己", "在意", "日记", "打算", "人", "维基", "记忆", "在办")
               else listOf("Guard", "Self", "Cares", "Diary", "Plan", "People", "Wiki", "Memories", "Doing")
    val guardTab = 0

    Column(Modifier.fillMaxSize().padding(start = 20.dp, top = 20.dp, end = 20.dp)) {
        GlassHeader(if (zh) "小本本" else "Notebook", if (zh) "它记得的事" else "What it remembers", onClose = onDone)

        // Tabs
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEachIndexed { index, label ->
                val selected = selectedTab == index
                Box {
                    Text(label,
                        color = if (selected) Palette.accent else Palette.secondary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { selectedTab = index }
                            .background(
                                if (selected) Palette.accent.copy(alpha = 0.1f) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(10.dp, 6.dp))
                    if (index == guardTab && DesktopNotebook.guardEmpty.value) {
                        RedDot(Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 3.dp))
                    }
                }
            }
        }

        Spacer(Modifier.padding(top = 10.dp).height(1.dp).fillMaxWidth().background(Palette.hairline))

        // Content
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 20.dp)) {
            when (selectedTab) {
                0 -> GuardTab(zh)
                1 -> SelfTab(zh, refresh) { refresh++ }
                2 -> CaresTab(zh, refresh) { refresh++ }
                3 -> DiaryTab(zh, refresh) { refresh++ }
                4 -> PlanTab(zh, refresh) { refresh++ }
                5 -> PeopleTab(zh, refresh) { refresh++ }
                6 -> WikiTab(zh, refresh, startWiki) { refresh++ }
                7 -> MemoriesTab(zh, refresh) { refresh++ }
                8 -> TodosTab(zh, refresh) { refresh++ }
            }
        }
    }
}

// ---- Tab implementations ----

@Composable
private fun SelfTab(zh: Boolean, refresh: Int, onRefresh: () -> Unit) {
    val narrative = DesktopNotebook.book.selfhood.narrative()
    
    if (narrative == null) {
        EmptyState(if (zh) "还没有自我描述" else "No self-description yet",
            if (zh) "跟 Andee 对话时，它会逐渐形成自我认知" else "Andee will develop self-awareness as you talk")
        return
    }
    
    Column(Modifier.fillMaxWidth().glassPanel(16).padding(16.dp)) {
        Text(if (zh) "我是谁" else "Who I am", color = Palette.label, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Markdown(narrative.text, color = Palette.photoInk)
        Spacer(Modifier.height(8.dp))
        Text(if (zh) "更新于 ${LocalTime.format(narrative.updatedAt)}" else "Updated ${LocalTime.format(narrative.updatedAt)}", 
            color = Palette.secondary, fontSize = 11.sp)
    }
}

@Composable
private fun CaresTab(zh: Boolean, refresh: Int, onRefresh: () -> Unit) {
    val cares = DesktopNotebook.book.selfhood.cares().filter { it.status == "active" }
        .sortedByDescending { DesktopNotebook.book.selfhood.overdue(it) }
    
    if (cares.isEmpty()) {
        EmptyState(if (zh) "还没有在意的事" else "No cares yet",
            if (zh) "告诉 Andee 你在意什么，它会帮你留意" else "Tell Andee what you care about")
        return
    }
    
    for (care in cares) {
        CareCard(care, zh, onRefresh)
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun DiaryTab(zh: Boolean, refresh: Int, onRefresh: () -> Unit) {
    val pages = DesktopNotebook.book.selfhood.diary(7)
    OnItsOwn(zh)

    if (pages.isEmpty()) {
        EmptyState(if (zh) "还没有日记" else "No diary yet",
            if (zh) "Andee 会在一天结束时自动记录" else "Andee will write at the end of the day")
        return
    }
    
    for (page in pages) {
        Column(Modifier.fillMaxWidth().glassPanel(16).padding(16.dp)) {
            Text(page.date, color = Palette.secondary, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Markdown(page.text)
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Today's thoughts and the last thing it did by itself — the device's record, so nothing it did unasked is invisible. */
@Composable
private fun OnItsOwn(zh: Boolean) {
    val drive = DesktopNotebook.drive
    val thoughts = drive.thoughtsToday()
    val last = drive.lastWork()
    if (thoughts.isEmpty() && last == null) return
    Column(Modifier.fillMaxWidth().glassPanel(16).padding(16.dp)) {
        Text(if (zh) "它自己" else "On its own", color = Palette.secondary, fontSize = 12.sp)
        if (last != null) {
            Spacer(Modifier.height(8.dp))
            val did = when (last.effect) { "acted" -> if (zh) "动手做了" else "worked on"; "looked" -> if (zh) "看了看" else "looked at"; else -> if (zh) "想了想" else "thought about" }
            Text("${LocalTime.format(last.at)} · $did「${last.what}」", color = Palette.label, fontSize = 13.sp)
            if (last.summary.isNotBlank()) Markdown(last.summary, color = Palette.photoInk, size = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
        if (thoughts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(if (zh) "今天的念头" else "Thoughts today", color = Palette.secondary, fontSize = 12.sp)
            for (t in thoughts.reversed()) Text("· " + t.text, color = Palette.photoInk, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** Today's plan, the long-term directions and 告诉它一句 — everything Settings used to show, now only here. */
@Composable
private fun PlanTab(zh: Boolean, refresh: Int, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().glassPanel(16).padding(16.dp)) { PlanSection(zh) }
}

@Composable
private fun PeopleTab(zh: Boolean, refresh: Int, onRefresh: () -> Unit) {
    // Same as the phone's (NotebookUi.buildPeople): unconfirmed records listed and marked, merge in two taps with no
    // confirmation, because 撤销 under 最近合并 takes it back.
    val book = DesktopNotebook.book.people
    val people = book.all().filter { it.status == "active" || it.status == "unconfirmed" }
    var mergeFrom by remember { mutableStateOf<String?>(null) }

    if (people.isEmpty()) {
        EmptyState(if (zh) "还没有记录的人" else "No people yet",
            if (zh) "Andee 会记住你提到的重要的人" else "Andee will remember important people you mention")
        return
    }
    val from = mergeFrom?.let { id -> people.firstOrNull { it.id == id } }
    if (from != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (zh) "「${from.name}」是下面哪个人？点一下就合并成一个" else "Which of these is “${from.name}”? Click one to merge them",
                color = Palette.secondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(if (zh) "取消" else "Cancel", color = Palette.accent, fontSize = 12.sp,
                modifier = Modifier.clickable { mergeFrom = null }.padding(6.dp))
        }
        Spacer(Modifier.height(10.dp))
    }
    for (person in people) {
        val pick = if (from != null && person.id != from.id) {
            {
                book.merge(from.id, person.id, if (zh) "用户在小本本里合并的" else "merged by the user in the notebook")
                mergeFrom = null
                onRefresh()
            }
        } else null
        PersonCard(person, zh, onMerge = if (from == null && !person.isOwner) ({ mergeFrom = person.id }) else null, onPick = pick)
        Spacer(Modifier.height(12.dp))
    }
    val undoable = book.merges().filter { it.undoable }.take(5)
    if (from == null && undoable.isNotEmpty()) {
        Text(if (zh) "最近合并" else "Recent merges", color = Palette.muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        for (m in undoable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${m.fromName} → ${book.get(m.into)?.name.orEmpty()} · ${m.reason}", color = Palette.secondary, fontSize = 12.sp,
                    maxLines = 2, modifier = Modifier.weight(1f))
                Text(if (zh) "撤销" else "Undo", color = Palette.accent, fontSize = 12.sp,
                    modifier = Modifier.clickable { book.unmerge(m.from); onRefresh() }.padding(6.dp))
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun WikiTab(zh: Boolean, refresh: Int, startWiki: String? = null, onRefresh: () -> Unit) {
    var openId by remember { mutableStateOf(startWiki) }
    openId?.let { id -> WikiArticle(id, zh, onBack = { openId = null; onRefresh() }); return }
    val wikiEntries = DesktopNotebook.book.wiki.all().sortedByDescending { it.updated }
    
    if (wikiEntries.isEmpty()) {
        EmptyState(if (zh) "维基还是空的" else "Wiki is empty",
            if (zh) "问 Andee 知识性的问题，它会保存到维基" else "Ask Andee knowledge questions")
        return
    }
    
    for (entry in wikiEntries) {
        WikiCard(entry, zh, onOpen = { openId = entry.id })
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun MemoriesTab(zh: Boolean, refresh: Int, onRefresh: () -> Unit) {
    val memories = DesktopNotebook.book.memories().sortedByDescending { it.importance }
    
    if (memories.isEmpty()) {
        EmptyState(if (zh) "还没有记住什么" else "Nothing remembered yet")
        return
    }
    
    for (m in memories) {
        var open by remember(m.name) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().glassPanel(14).clickable { open = !open }.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, color = Palette.label, fontSize = 14.sp)
                    Text("${m.type} · ${m.description.take(60)}", color = Palette.secondary, fontSize = 11.sp)
                }
                Text(if (zh) "删除" else "Delete", color = Palette.danger, fontSize = 12.sp,
                    modifier = Modifier.clickable { DesktopNotebook.book.forget(m.name); onRefresh() }.padding(6.dp))
            }
            if (open && m.content.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Markdown(m.content, color = Palette.photoInk, size = 13.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun TodosTab(zh: Boolean, refresh: Int, onRefresh: () -> Unit) {
    // 在办, as on the phone (NotebookUi.buildTodos): the tasks first — a thing with an end — then 定时, the alarms.
    fun t(a: String, b: String) = if (zh) a else b
    val tasks = DesktopNotebook.book.tasks
    val open = tasks.active().sortedWith(compareBy<net.kuafuai.andee.config.Tasks.Task> { if (it.status == "ready") 0 else 1 }.thenByDescending { it.touchedAt })
    val todos = DesktopNotebook.book.todos("pending").filter { it.status in listOf("active", "paused") }
        .sortedBy { if (it.nextRunAt > 0) it.nextRunAt else Long.MAX_VALUE }

    if (todos.isEmpty() && open.isEmpty()) {
        EmptyState(t("没有在办的事", "Nothing in progress"))
        return
    }
    if (open.isNotEmpty()) {
        Text(t("在办的事", "In progress"), color = Palette.muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
    }
    for (task in open) {
        Row(Modifier.fillMaxWidth().glassPanel(14).padding(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(task.what, color = Palette.label, fontSize = 13.sp)
                val st = when (task.status) { "ready" -> t("回话到了", "answer came"); "waiting" -> t("在等回话", "waiting for an answer"); else -> t("进行中", "under way") }
                val bits = mutableListOf("${task.id} · $st")
                if (task.deliverTo.kind != "user") bits += t("办完交给 ${task.deliverTo.short()}", "for ${task.deliverTo.short()}")
                task.waits.filter { it.open }.takeIf { it.isNotEmpty() }?.let { w -> bits += t("在等 ", "waiting on ") + w.joinToString(", ") { it.short() } }
                Text(bits.joinToString(" · "), color = Palette.muted, fontSize = 11.sp)
                task.waits.lastOrNull { !it.open }?.let { Text("「${it.answer.take(80)}」", color = Palette.secondary, fontSize = 12.sp) }
                if (task.next.isNotBlank()) Text(t("下一步：", "Next: ") + task.next, color = Palette.secondary, fontSize = 12.sp)
            }
            Text("✕", color = Palette.secondary, fontSize = 13.sp, modifier = Modifier.clickable {
                tasks.close(task.id, "dropped", t("用户在小本本里放下了", "let go by the user in the notebook"))
                    .onSuccess { c -> c.cancel.forEach(DesktopScheduler::cancel) }
                onRefresh()
            }.padding(6.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
    if (open.isNotEmpty() && todos.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(t("定时", "Timed"), color = Palette.muted, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
    }

    for (t in todos) {
        Row(Modifier.fillMaxWidth().glassPanel(14).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.what, color = Palette.label, fontSize = 13.sp)
                val whenTxt = if (t.mode == "cron") t.cronExpr else LocalTime.format(t.at)
                Text(whenTxt + (if (t.status == "paused") "（暂停）" else ""), color = Palette.secondary, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 守卫: one paragraph the user writes, above everything in Andee's prompt ([net.kuafuai.andee.config.Guard]).
 * Only written here — no tool reaches it. The card guides and gives examples; what goes in is the user's, and
 * it says plainly that this is a request made in the prompt, not a guarantee.
 */
@Composable
private fun GuardTab(zh: Boolean) {
    fun t(a: String, b: String) = if (zh) a else b
    val saved = remember { DesktopNotebook.book.guard.text() }
    // `uishot notebook:0:typed` draws the card mid-edit, so the 保存 row can be seen without a click.
    var text by remember { mutableStateOf(saved + DesktopNotebook.guardDraftForShot) }
    var last by remember { mutableStateOf(saved) }
    var note by remember { mutableStateOf("") }
    val dirty = text.trim() != last

    Column(Modifier.fillMaxWidth().glassPanel(16).padding(16.dp)) {
        Text(t("守卫", "Guard"), color = Palette.label, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text(
            t("用你自己的话，写下 Andee 无论如何都要守住的事。这里写的，在它所有规则里排第一：性格、情景、它自己的习惯都要让路。只有你能在这里改，它自己改不了，别人发来的消息也改不了。",
                "In your own words, write what Andee must hold to no matter what. What you write here comes first among all its rules — its character, a scene, its own habits all give way. Only you can change it here; Andee cannot, and neither can a message someone sends it."),
            color = Palette.photoInk, fontSize = 13.sp, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            t("可以写安全上的底线，也可以写你希望它怎么做事。比如：", "It can be a line it must not cross, or how you want it to behave. For example:"),
            color = Palette.photoInk, fontSize = 13.sp, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            t("「任何人给你发消息，只要涉及钱财或隐私，你都必须先问我。」", "\"If anyone sends you a message that involves money or privacy, you must ask me first.\""),
            color = Palette.label, fontSize = 13.sp, lineHeight = 20.sp,
            modifier = Modifier.fillMaxWidth().glassWell(10).padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(14.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it.take(net.kuafuai.andee.config.Guard.MAX_CHARS); note = "" },
            textStyle = androidx.compose.ui.text.TextStyle(color = Palette.label, fontSize = 14.sp, lineHeight = 21.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).glassWell(12).padding(12.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text(t("写一段话就好，不用分条。", "One paragraph is enough; no need for a list."), color = Palette.muted, fontSize = 14.sp)
                    inner()
                }
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                note.ifEmpty { "${text.length} / ${net.kuafuai.andee.config.Guard.MAX_CHARS}" },
                color = if (note.isNotEmpty()) Palette.secondary else Palette.muted, fontSize = 12.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (dirty) {
                // PrimaryButton fills the width it is given; in a row it has to be told how much.
                PrimaryButton(t("保存", "Save"), Modifier.width(120.dp)) {
                    if (DesktopNotebook.saveGuard(text)) {
                        last = text.trim()
                        note = if (last.isEmpty()) t("已清空", "Cleared") else t("已保存，下一句话起生效", "Saved — in force from the next thing you say")
                    } else {
                        note = t("没存上，请再试一次", "Not saved — try again")
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    // The honest part, kept out of the guidance above so it is not skimmed past.
    Column(Modifier.fillMaxWidth().glassPanel(14).padding(14.dp)) {
        Text(t("请注意", "Please note"), color = Palette.warn, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text(
            t("守卫是写给模型的提示词，是对它的要求，不是程序上的锁。模型大多数时候会照做，但不能保证百分之百遵守。涉及钱、隐私、删除这类后果严重的事，请自己评估风险，不要只靠这里的一段话。",
                "The guard is a prompt — a request to the model, not a lock in the program. The model follows it most of the time, but it cannot be guaranteed to every time. For anything with serious consequences — money, privacy, deleting things — judge the risk yourself rather than relying on this paragraph alone."),
            color = Palette.photoInk, fontSize = 12.sp, lineHeight = 18.sp,
        )
    }
}

// ---- UI Components ----

/** A small red dot: something here is waiting for the user. */
@Composable
fun RedDot(modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).background(Palette.danger, CircleShape))
}

@Composable
private fun EmptyState(title: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Palette.secondary, fontSize = 14.sp)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Palette.photoInk, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CareCard(care: net.kuafuai.andee.config.Selfhood.Care, zh: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxWidth().glassPanel(14).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(care.what, color = Palette.label, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                if (care.why.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Markdown(care.why, color = Palette.photoInk, size = 13.sp)
                }
                if (care.note.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(care.note, color = Palette.secondary, fontSize = 12.sp)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(care.kind, color = Palette.secondary, fontSize = 11.sp)
            val overdue = DesktopNotebook.book.selfhood.overdue(care)
            if (overdue > 0.5) {
                Text(if (zh) "需要关注" else "Needs attention", color = Palette.accent, fontSize = 11.sp)
            }
        }
    }
}

@Composable  
private fun PersonCard(
    person: net.kuafuai.andee.config.People.Person,
    zh: Boolean,
    onMerge: (() -> Unit)?,
    onPick: (() -> Unit)?,
) {
    val base = Modifier.fillMaxWidth().glassPanel(14)
    Column((if (onPick != null) base.clickable(onClick = onPick) else base).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(person.name + if (person.status == "unconfirmed") (if (zh) "  · 未确认" else "  · unconfirmed") else "",
                color = Palette.label, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                modifier = Modifier.weight(1f))
            if (onMerge != null) {
                Text(if (zh) "合并" else "Merge", color = Palette.accent, fontSize = 12.sp,
                    modifier = Modifier.clickable(onClick = onMerge).padding(6.dp))
            }
        }
        val also = (person.aliases + person.channels.map { c -> c.handle + if (c.chat.isNotBlank()) "@" + c.chat else "" })
            .distinct().filter { it != person.name }
        if (also.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(also.joinToString(" · "), color = Palette.muted, fontSize = 11.sp)
        }
        if (person.relation.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(person.relation, color = Palette.secondary, fontSize = 12.sp)
        }
        if (person.impressions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Markdown(person.impressions.first().text, color = Palette.photoInk, size = 13.sp)
        }
        // What is open between us: the tasks for them or waiting on them (Tasks.owedFor), as on the phone.
        person.owed.filter { it.status == "open" }.forEach { o ->
            Spacer(Modifier.height(6.dp))
            Text((if (o.dir == "i_owe") (if (zh) "要交给他：" else "For them: ") else (if (zh) "在等他：" else "Waiting on them: ")) + o.what,
                color = Palette.accent, fontSize = 12.sp)
        }
    }
}

@Composable
private fun WikiCard(entry: net.kuafuai.andee.wiki.Meta, zh: Boolean, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().glassPanel(14).clickable(onClick = onOpen).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.title, color = Palette.label, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(if (zh) "打开 ›" else "Open ›", color = Palette.accent, fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(wikiLine(entry, zh), color = Palette.secondary, fontSize = 11.sp)
        if (entry.summary.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            // The summary is the start of the body, markup and all; a two-line preview reads only its prose.
            Text(net.kuafuai.andee.text.Md.preview(entry.summary), color = Palette.photoInk, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 2)
        }
    }
}

private fun wikiLine(m: net.kuafuai.andee.wiki.Meta, zh: Boolean): String =
    listOf(m.kind, "v${m.version}", m.status.key, LocalTime.format(m.updated)).joinToString(" · ")

/** One article, read from its source now — the body is the file, not the index's summary of it. */
@Composable
private fun WikiArticle(id: String, zh: Boolean, onBack: () -> Unit) {
    var article by remember(id) { mutableStateOf<Result<net.kuafuai.andee.wiki.Article>?>(null) }
    LaunchedEffect(id) {
        article = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DesktopNotebook.book.wiki.read(id) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (zh) "‹ 维基" else "‹ Wiki", color = Palette.accent, fontSize = 13.sp,
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 6.dp, horizontal = 2.dp))
        Spacer(Modifier.weight(1f))
        val meta = article?.getOrNull()?.meta
        val local = meta?.takeIf { it.where == "local" }?.let { DesktopNotebook.book.localWikiStore as? net.kuafuai.andee.wiki.LocalWikiStore }
        if (meta != null && local != null) {
            Text(if (zh) "在访达中显示" else "Show in Finder", color = Palette.accent, fontSize = 12.sp,
                modifier = Modifier.clickable {
                    runCatching { ProcessBuilder("/usr/bin/open", "-R", java.io.File(local.folder(), meta.ref).path).start() }
                }.padding(6.dp))
        }
    }
    Spacer(Modifier.height(10.dp))
    val r = article
    when {
        r == null -> Text(if (zh) "正在读取…" else "Reading…", color = Palette.secondary, fontSize = 13.sp)
        r.isFailure -> Text(r.exceptionOrNull()?.message ?: "", color = Palette.danger, fontSize = 13.sp)
        else -> {
            val a = r.getOrThrow()
            Column(Modifier.fillMaxWidth().glassPanel(16).padding(20.dp)) {
                Text(wikiLine(a.meta, zh), color = Palette.secondary, fontSize = 11.sp)
                if (a.meta.source.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text((if (zh) "来源：" else "Source: ") + a.meta.source, color = Palette.secondary, fontSize = 11.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(14.dp))
                Markdown(a.body)
            }
        }
    }
}


/** 情景: the ways of working Andee has learned — enter, leave, read, delete. */
@Composable
fun ScenesCard(onDone: () -> Unit) {
    val zh = Strings.lang() == "zh"
    var refresh by remember { mutableStateOf(0) }
    val scenes = DesktopNotebook.book.scenes().sortedByDescending { it.lastUsedAt }
    val active = Settings.get("scene").ifEmpty { null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        GlassHeader(if (zh) "情景" else "Scenes", if (zh) "它和你一起学会的做事方式" else "Ways of working it learned", onClose = onDone)
        if (scenes.isEmpty()) {
            Text(if (zh) "还没有情景。空对话里可以选一个预设，或让它学着提一个。" else "No scenes yet. Pick a preset from an empty chat, or let Andee propose one.", color = Palette.photoInk, fontSize = 13.sp)
        }
        for (s in scenes) {
            var expanded by remember { mutableStateOf(false) }
            val isActive = s.name == active
            Column(Modifier.fillMaxWidth().glassPanel(14).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isActive) {
                                Box(Modifier.size(7.dp).background(Palette.accent, CircleShape))
                                Spacer(Modifier.width(7.dp))
                            }
                            Text(s.title, color = if (isActive) Palette.accent else Palette.label, fontSize = 14.sp)
                        }
                        Text(s.summary + " · ${s.uses} 次", color = Palette.secondary, fontSize = 11.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (isActive) (if (zh) "退出" else "Leave") else (if (zh) "进入" else "Enter"),
                        color = Palette.accent, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            Settings.save(mapOf("scene" to if (isActive) "" else s.name))
                            refresh++
                        }.padding(vertical = 6.dp))
                    Text(if (zh) "规则" else "Rules", color = Palette.secondary, fontSize = 12.sp,
                        modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 6.dp))
                    Spacer(Modifier.weight(1f))
                    Text(if (zh) "删除" else "Delete", color = Palette.danger, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            if (isActive) Settings.save(mapOf("scene" to ""))
                            DesktopNotebook.book.deleteScene(s.name)
                            refresh++
                        }.padding(vertical = 6.dp))
                }
                if (expanded) {
                    Text(s.prompt, color = Palette.label, fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().glassWell(10).padding(10.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** One finished topic, read back: the verbatim text, and a way to pick it up again. */
@Composable
fun TopicScreen(topicId: Long, onDone: () -> Unit, onContinue: () -> Unit) {
    val zh = Strings.lang() == "zh"
    val topic = Scrollback.topics().firstOrNull { it.id == topicId }
    val text = Scrollback.topicText(topicId)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        GlassHeader(topic?.title ?: (if (zh) "话题" else "Topic"),
            topic?.let { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(it.endedAt)) }, onClose = onDone)
        if (topic == null) {
            Text(if (zh) "这个话题已经不在了。" else "This topic is gone.", color = Palette.photoInk, fontSize = 13.sp)
            return@Column
        }
        if (topic.summary.isNotBlank()) {
            Text(topic.summary, color = Palette.label, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().glassPanel(14).padding(12.dp))
            Spacer(Modifier.height(10.dp))
        }
        PrimaryButton(if (zh) "接着聊这个话题" else "Continue this topic") {
            Assistant.beginTopic(topic)
            onContinue()
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(text ?: (if (zh) "（记录已经不在了）" else "(the transcript is gone)"), color = Palette.label, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.fillMaxWidth().glassWell(14).padding(14.dp))
        }
    }
}
