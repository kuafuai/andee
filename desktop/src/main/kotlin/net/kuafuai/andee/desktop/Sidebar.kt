package net.kuafuai.andee.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The left rail: the doors the phone hides behind ⚙ and ✓, as one tap each, and
 * under them the conversation's finished topics. A desktop has the room for it
 * and a person at a desk reaches for these all day.
 *
 * It is glass laid on the same blurred photograph as everything else, a shade
 * lighter than the content pane so the two read as two panes of one material.
 */
@Composable
fun Sidebar(nav: Nav, width: androidx.compose.ui.unit.Dp = 228.dp) {
    val zh = Strings.lang() == "zh"
    fun t(a: String, b: String) = if (zh) a else b

    // The topic list re-reads whenever the scrollback changes (a fold is a write to it).
    var version by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { version = version + 1 }
        Scrollback.addListener(l)
        onDispose { Scrollback.removeListener(l) }
    }
    val topics = remember(version) { Scrollback.topics() }
    val artifactCount = remember(version, nav.current) { Pages.artifacts().size }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.US) }
    val here = nav.current
    var sweeping by remember { mutableStateOf(false) }

    Column(
        Modifier.width(width).fillMaxHeight()
            .background(Color(0x14FFFFFF))
            .border(1.dp, Palette.panelEdge)
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        // Brand row. No 新对话 button: the phone has none, and a stretch that is over is put away by the
        // quiet-hour review (or 整理 below); the menu bar's Andee → 新对话 is still there for whoever wants it.
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Andee", color = Palette.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(12.dp))

        NavItem(AndeeIcon.CHAT, t("动态", "Timeline"), here == "chat") { nav.go("chat") }
        NavItem(AndeeIcon.DOCUMENT, t("产物", "Artifacts") + if (artifactCount > 0) "  $artifactCount" else "", here == "artifacts") { nav.go("artifacts") }
        // The same doors, in the same order, as the phone's top bar: 产物 · 小本本 · 情景 · 朋友 · 自检 · 设置.
        // 形象与表情, 装备 and 保险箱 are opened from 设置 on both, so neither body has a door the other lacks.
        NavItem(AndeeIcon.NOTEBOOK, t("小本本", "Notebook"), here == "notebook") { nav.go("notebook") }
        NavItem(AndeeIcon.SPARKLE, t("情景", "Scenes"), here == "scenes") { nav.go("scenes") }
        NavItem(AndeeIcon.FRIENDS, t("朋友", "Friends"), here == "peers", badge = Peers.count.value) { nav.go("peers") }

        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("话题", "Topics"), color = Palette.secondary, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (sweeping) t("整理中…", "Working…") else t("整理", "Tidy up"),
                color = if (sweeping) Palette.photoInk else Palette.accent, fontSize = 12.sp,
                modifier = Modifier.clickable(enabled = !sweeping) {
                    sweeping = true
                    Thread { Sweep.now(); Thread.sleep(4000); sweeping = false }.start()
                }.padding(2.dp),
            )
        }
        Spacer(Modifier.height(4.dp))

        if (topics.isEmpty()) {
            Text(
                t("聊完的一段会收在这里。安静一会儿它会自己整理，也可以点上面的「整理」。",
                    "Finished stretches are put away here. Andee tidies up when it's quiet, or tap Tidy up."),
                color = Palette.photoInk, fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(topics, key = { it.id }) { tp ->
                val on = here == "topic:${tp.id}"
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (on) Palette.glass else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable { nav.go("topic:${tp.id}") }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                ) {
                    Text(tp.title, color = if (on) Palette.title else Palette.label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmt.format(Date(tp.endedAt)), color = Palette.photoInk, fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        NavItem(AndeeIcon.CHECK_CIRCLE, t("自检", "Self-check"), here == "selfcheck") { nav.go("selfcheck") }
        NavItem(AndeeIcon.SLIDERS, t("设置", "Settings"), here == "settings") { nav.go("settings") }
    }
}

@Composable
private fun NavItem(icon: AndeeIcon, label: String, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) Palette.glass else Color.Transparent, RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) Palette.glassEdge else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, if (selected) Palette.title else Palette.secondary, 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(label, color = if (selected) Palette.title else Palette.label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        // A count at the row's end: how many there are, readable without opening the page.
        if (badge > 0) {
            Box(
                Modifier.background(Palette.accent, RoundedCornerShape(9.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (badge > 99) "99+" else badge.toString(), color = Color.White, fontSize = 11.sp)
            }
        }
    }
}
