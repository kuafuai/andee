package net.kuafuai.andee.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import net.kuafuai.andee.util.Log

/**
 * The top of a page, as a picture, for its card — the phone's `PageThumbs`, on WebKit
 * ([net.kuafuai.andee.desktop.mac.Bridge.htmlSnapshot]). Laid out at a phone's width so the card
 * looks the same on both bodies, kept beside the page as `<page>.thumb.png`; [Pages] deletes it
 * with the page. One at a time: each render is a WKWebView in an off-screen window.
 */
object PageThumbs {
    /** Points wide, and height / width — the phone's numbers. */
    private const val WIDTH = 360
    const val ASPECT = 0.8f

    private val memory = ConcurrentHashMap<String, ImageBitmap>()
    private val renderLock = Any()

    fun fileFor(page: File): File = File(page.parentFile, page.nameWithoutExtension + ".thumb.png")

    /** The picture, made if it does not exist yet. Blocking: call off the UI thread. */
    fun load(page: File): ImageBitmap? {
        memory[page.path]?.let { return it }
        val f = fileFor(page)
        if (!f.exists()) synchronized(renderLock) {
            if (!f.exists() && page.exists()) {
                runCatching {
                    net.kuafuai.andee.desktop.mac.Bridge.htmlSnapshot(page.path, f.path, WIDTH, (WIDTH * ASPECT).toInt())
                }.onFailure { Log.w("PageThumbs", "could not draw ${page.name}: ${it.message}") }
            }
        }
        if (!f.exists()) return null
        return runCatching { org.jetbrains.skia.Image.makeFromEncoded(f.readBytes()).toComposeImageBitmap() }
            .getOrNull()?.also { memory[page.path] = it }
    }
}

/**
 * A page it made, as a share card in the conversation — the phone's page card on the Mac: the same glass as
 * Andee's bubbles, a header (kind, version, time), the title, the top of the page itself, and a footer saying a
 * click opens it. A page whose file is gone shows as the plain title, since there is nothing left to open.
 */
@Composable
fun PageCard(r: Scrollback.Row) {
    val zh = Strings.lang() == "zh"
    val page = remember(r.ref) { r.ref.toLongOrNull()?.let { Pages.pageById(it) } }
    val file = page?.file?.takeIf { it.exists() }
    var shot by remember(r.ref) { mutableStateOf<ImageBitmap?>(null) }
    if (file != null) LaunchedEffect(file.path) { shot = withContext(Dispatchers.IO) { PageThumbs.load(file) } }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier.width(340.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.glass, RoundedCornerShape(16.dp))
                .border(1.dp, Palette.glassEdge, RoundedCornerShape(16.dp))
                .then(if (file != null) Modifier.clickable { runCatching { net.kuafuai.andee.desktop.mac.Bridge.open(file.absolutePath) } } else Modifier)
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AndeeIcon.DOCUMENT, Palette.accent, size = 14.dp)
                Text(
                    if (zh) "页面" else "Page", color = Palette.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                Text(
                    buildString {
                        if ((page?.version ?: 1) > 1) append("v").append(page!!.version).append(" · ")
                        append(java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(r.ts)))
                    },
                    color = Palette.secondary, fontSize = 11.sp,
                )
            }
            Text(
                r.text, color = Palette.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
            )
            if (file != null) {
                Box(
                    Modifier.padding(top = 9.dp).fillMaxWidth().aspectRatio(1f / PageThumbs.ASPECT)
                        .clip(RoundedCornerShape(12.dp)).background(Palette.panel),
                ) {
                    val img = shot
                    if (img != null) {
                        Image(img, contentDescription = r.text, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f / PageThumbs.ASPECT))
                    } else {
                        // The skeleton the picture replaces, in the same frame so nothing jumps.
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 16.dp)) {
                            for ((i, frac) in listOf(0.5f, 0.9f, 0.75f, 0.6f).withIndex()) {
                                if (i > 0) Spacer(Modifier.height(10.dp))
                                Box(
                                    Modifier.fillMaxWidth(frac).height(if (i == 0) 9.dp else 6.dp)
                                        .background(Palette.hairline, RoundedCornerShape(3.dp)),
                                )
                            }
                        }
                    }
                }
                Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(1.dp).background(Palette.hairline))
                Text(
                    if (zh) "Andee 做的页面 · 点击打开" else "A page by Andee · Click to open",
                    color = Palette.secondary, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp),
                )
            } else {
                Text(
                    if (zh) "页面已清理" else "Page cleared", color = Palette.muted, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
