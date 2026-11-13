package net.kuafuai.andee.desktop

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuafuai.andee.desktop.dsh.WorkerFiles

/**
 * What worker [worker] made, listed in the window and opened from there: the phone's file card, on the disk instead of over the
 * link. Text is read here (Markdown drawn as Markdown); a page and anything else go to the system's own viewer, since the app
 * has no better one. A row that cannot be read says why on this page, and the folder stays on screen.
 */
@Composable
fun WorkerFilesScreen(worker: String, nav: Nav, startOpen: String? = null) {
    val zh = Strings.lang() == "zh"
    fun t(a: String, b: String) = if (zh) a else b
    val w = remember(worker) { Assistant.dsh.get(worker) }
    if (w == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            GlassHeader("dsh #$worker", null, onClose = { nav.back() })
            Text(t("这个工作者已经不在了。", "This worker is gone."), color = Palette.photoInk, fontSize = 13.sp)
        }
        return
    }
    val files = remember(w.dir) { WorkerFiles(java.io.File(w.dir)) }
    var path by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(startOpen?.let { runCatching { files.open(it) }.getOrNull() as? WorkerFiles.Opened.Text }) }
    var error by remember { mutableStateOf<String?>(null) }
    val entries = remember(path) {
        runCatching { files.list(path) }.onFailure { error = it.message ?: it.javaClass.simpleName }.getOrDefault(emptyList())
    }

    fun openEntry(e: WorkerFiles.Entry) {
        val rel = if (path.isEmpty()) e.name else "$path/${e.name}"
        error = null
        if (e.dir) { path = rel; return }
        runCatching { files.open(rel) }.onSuccess { o ->
            when (o) {
                is WorkerFiles.Opened.Text -> reading = o
                is WorkerFiles.Opened.Page -> runCatching { net.kuafuai.andee.desktop.mac.Bridge.open(o.file.absolutePath) }
                is WorkerFiles.Opened.Other -> runCatching { net.kuafuai.andee.desktop.mac.Bridge.open(o.file.absolutePath) }
                    .onFailure { error = t("打不开这个文件：${o.file.path}", "Could not open this file: ${o.file.path}") }
            }
        }.onFailure { error = it.message ?: it.javaClass.simpleName }
    }

    val open = reading
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        if (open != null) {
            GlassHeader(
                open.name, "dsh #$worker · ${w.title}",
                onClose = { nav.back() },
                trailing = { Chip(t("‹ 文件", "‹ Files")) { reading = null }; Spacer(Modifier.width(8.dp)) },
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (open.markdown) Markdown(open.text)
                else SelectionContainer {
                    Text(open.text, color = Palette.label, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace)
                }
                if (open.more) {
                    Text(
                        t("（文件太长，只显示前 ${WorkerFiles.MAX_TEXT_BYTES / 1024} KB）", "(The file is long; only the first ${WorkerFiles.MAX_TEXT_BYTES / 1024} KB is shown.)"),
                        color = Palette.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            return@Column
        }
        GlassHeader(
            "dsh #$worker · ${w.title}",
            t("在电脑上", "On this computer") + (if (path.isEmpty()) "" else " › $path"),
            onClose = { nav.back() },
            trailing = {
                if (path.isNotEmpty()) Chip("‹") { path = path.substringBeforeLast('/', ""); error = null }
                Spacer(Modifier.width(8.dp))
                Chip(t("在 Finder 中显示", "Show in Finder")) { Assistant.dsh.openFolder(worker) }
                Spacer(Modifier.width(8.dp))
            },
        )
        error?.let { Text(it, color = Palette.danger, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
        if (entries.isEmpty() && error == null) {
            Text(t("这个文件夹是空的。", "This folder is empty."), color = Palette.photoInk, fontSize = 13.sp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (e in entries) {
                Row(
                    Modifier.fillMaxWidth().glassPanel(14).clip(RoundedCornerShape(14.dp)).clickable { openEntry(e) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (e.dir) "📁" else WorkerFiles.icon(e.name), fontSize = 15.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(e.name, color = Palette.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (e.dir) "›" else WorkerFiles.size(e.size), color = Palette.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Text(
        text, color = Palette.secondary, fontSize = 12.sp,
        modifier = Modifier.glassPanel(16).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
