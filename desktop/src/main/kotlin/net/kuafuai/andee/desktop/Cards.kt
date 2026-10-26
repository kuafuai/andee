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
        val fda = net.kuafuai.andee.desktop.mac.MacNotifications.state != net.kuafuai.andee.desktop.mac.MacNotifications.State.NO_ACCESS
        add(CheckRow("fda", if (zh) "完全磁盘访问" else "Full Disk Access", fda,
            if (fda) if (zh) "能读通知" else "Can read notifications" else if (zh) "读不到通知数据库" else "Cannot read the notification store",
            { openPane("Privacy_AllFiles") }))
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
            is VersionCheck.Result.Behind -> CheckRow("version", if (zh) "版本" else "Version", false,
                if (r.forced) { if (zh) "请升级到 ${r.latest} —— 你现在这个（$v）已经不再支持了。到 GitHub 下载。" else "Please upgrade to ${r.latest} — this build ($v) is no longer supported. Get it from GitHub." }
                else { if (zh) "版本落后了：最新是 ${r.latest}，你现在是 $v。请到 GitHub 下载升级。" else "Out of date: the latest is ${r.latest}, you are on $v. Please upgrade from GitHub." },
                { runCatching { net.kuafuai.andee.desktop.mac.Bridge.open(r.url) } }, red = true)
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
                    Text(if (r.id == "version") (if (zh) "去 GitHub" else "GitHub") else if (zh) "去处理" else "Fix", color = Palette.accent, fontSize = 13.sp,
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
                                        Scrollback.add(Scrollback.Role.ASSISTANT, "已把「${v.title}」回退到 v${v.version}")
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
fun NotebookCard(onDone: () -> Unit) {
    val zh = Strings.lang() == "zh"
    var refresh by remember { mutableStateOf(0) }
    val memories = DesktopNotebook.book.memories().sortedByDescending { it.importance }
    val todos = DesktopNotebook.book.todos("pending").filter { it.status in listOf("active", "paused") }
        .sortedBy { if (it.nextRunAt > 0) it.nextRunAt else Long.MAX_VALUE }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        GlassHeader(if (zh) "小本本" else "Notebook", if (zh) "它记得的事" else "What it remembers", onClose = onDone,
            trailing = {
                Text(if (zh) "复制全部" else "Copy all", color = Palette.accent, fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        val dump = memories.joinToString("\n\n") { "【${it.name}】${it.type}\n${it.description}" }
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(java.awt.datatransfer.StringSelection(dump), null)
                    }.padding(6.dp))
            })

        SectionTitle(if (zh) "记忆" else "Memories")
        if (memories.isEmpty()) {
            Text(if (zh) "还没有记住什么。" else "Nothing remembered yet.", color = Palette.photoInk, fontSize = 13.sp)
        }
        for (m in memories) {
            Column(Modifier.fillMaxWidth().glassPanel(14).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, color = Palette.label, fontSize = 14.sp)
                        Text("${m.type} · ${m.description.take(60)}", color = Palette.secondary, fontSize = 11.sp)
                    }
                    Text(if (zh) "删除" else "Delete", color = Palette.danger, fontSize = 12.sp,
                        modifier = Modifier.clickable { DesktopNotebook.book.forget(m.name); refresh++ }.padding(6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        SectionTitle(if (zh) "待办" else "Todos")
        if (todos.isEmpty()) {
            Text(if (zh) "没有待办。" else "Nothing pending.", color = Palette.photoInk, fontSize = 13.sp)
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
