package net.kuafuai.andee.desktop

import androidx.compose.foundation.Image
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 朋友: the other Andees this one knows, and the way to add one.
 *
 * Two kinds, chosen when the code is shown and changeable later: **my device** (another of the user's own — trusted
 * like the user) and **friend** (somebody else's Andee — asked about every time unless set to "always", and run
 * out of sight of the user's memory). Adding one is one act with a human in the loop: this Mac shows a QR code,
 * the other side's phone scans it. The code inside dies after two minutes or three wrong tries, and the QR also
 * names this Mac's key, so the scanner pins that key and no other (see [net.kuafuai.andee.peer.PeerQr]).
 * Removing one is one click and takes effect on its next attempt to connect.
 */
@Composable
fun PeersCard(onDone: () -> Unit) {
    val zh = Strings.lang() == "zh"
    fun t(a: String, b: String) = if (zh) a else b
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        Peers.onChange = { tick++ }
        onDispose { Peers.onChange = {} }
    }
    // The code counts down on screen; re-read it each second.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis(); tick++ } }

    val rows = remember(tick) { Peers.rows() }
    val me = remember { Peers.store.identity() }
    val code = Peers.code?.takeIf { it.alive() }
    var invite by remember { mutableStateOf<String?>(null) }
    var inviteOwn by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf<String?>(null) }
    if (code == null) invite = null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        GlassHeader(
            t("朋友", "Friends"),
            t("Andee 可以请朋友帮忙：你自己的其它设备，或者别人的 Andee", "Andee can ask friends for help: your other devices, or other people's Andees"),
            onClose = onDone,
        )

        SectionTitle(t("添加", "Add"))
        val qr = invite
        if (qr != null && code != null) {
            Column(Modifier.fillMaxWidth().glassPanel(14).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val img = remember(qr) { qrImage(qr) }
                // Dark modules on white whatever the theme: a scanner reads contrast, and a light-on-dark code
                // is one plenty of them refuse.
                Box(Modifier.background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Image(img, contentDescription = null, modifier = Modifier.size(220.dp), filterQuality = FilterQuality.None)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (inviteOwn) t("用你自己的手机：Andee 设置 → 朋友 → 扫码添加，扫这个码。", "On your own phone: Andee Settings → Friends → Scan to add, and scan this.")
                    else t("让朋友用手机上的 Andee 扫这个码：设置 → 朋友 → 扫码添加。", "Have your friend scan this with Andee on their phone: Settings → Friends → Scan to add."),
                    color = Palette.label, fontSize = 13.sp,
                )
                Text(t("两分钟内有效。", "Valid for two minutes."), color = Palette.muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Pill(t("取消", "Cancel")) { Peers.cancelCode(); invite = null; tick++ }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(t("添加我的设备", "Add my own device")) { inviteOwn = true; invite = Peers.invite(net.kuafuai.andee.peer.PeerStore.Trust.OWN); tick++ }
                Pill(t("添加朋友", "Add a friend")) { inviteOwn = false; invite = Peers.invite(net.kuafuai.andee.peer.PeerStore.Trust.FRIEND); tick++ }
            }
            Text(
                t("「我的设备」和你一样被信任；「朋友」每次请求都会先问你，也看不到你的记忆和对话。",
                    "“My device” is trusted like you. A “friend” is asked about every time and cannot see your memory or conversations."),
                color = Palette.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (Peers.lastError.isNotEmpty()) Text(Peers.lastError, color = Palette.danger, fontSize = 12.sp)

        SectionTitle(t("朋友", "Friends") + "  ${rows.count { it.paired }}")
        if (rows.none { it.paired }) Text(t("还没有。", "None yet."), color = Palette.photoInk, fontSize = 13.sp)
        for (r in rows.filter { it.paired }) {
            Column(Modifier.fillMaxWidth().glassPanel(14).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if (r.linked) Palette.ok else if (r.reachable) Palette.warn else Palette.muted, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(r.name, color = Palette.label, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(
                        when { r.linked -> t("已连接", "Linked"); r.reachable -> t("在附近", "Nearby"); else -> t("不在附近", "Not nearby") },
                        color = Palette.secondary, fontSize = 11.sp,
                    )
                }
                Text(r.fingerprint, color = Palette.muted, fontSize = 10.sp)
                Spacer(Modifier.height(6.dp))
                val own = r.trust == net.kuafuai.andee.peer.PeerStore.Trust.OWN
                SegmentedPicker(listOf("friend" to t("朋友", "Friend"), "own" to t("我的设备", "My device")), if (own) "own" else "friend") {
                    Peers.setTrust(r.id, if (it == "own") net.kuafuai.andee.peer.PeerStore.Trust.OWN else net.kuafuai.andee.peer.PeerStore.Trust.FRIEND); tick++
                }
                if (!own) {
                Spacer(Modifier.height(6.dp))
                SegmentedPicker(listOf("ask" to t("每次先问我", "Ask me each time"), "auto" to t("总是允许", "Always allow")), if (r.auto) "auto" else "ask") {
                Peers.setAutoAccept(r.id, it == "auto"); tick++
                }
                }
                if (own) {
                    // Only for a device that is the user's own, and off until they turn it on.
                    Spacer(Modifier.height(6.dp))
                    Text(t("让它直接使用这台电脑的 dsh", "Let it use this Mac's dsh directly"), color = Palette.label, fontSize = 12.sp)
                    SegmentedPicker(listOf("off" to t("关", "Off"), "on" to t("开", "On")), if (r.dsh) "on" else "off") {
                        Peers.setDshAllowed(r.id, it == "on"); tick++
                    }
                    Text(
                        t("打开后，它的 Andee 可以直接派活给这台电脑的 dsh，这台 Mac 的 Andee 不参与，也不过问。它产出的文件放在这台 Mac 上，手机点开才下载。",
                            "When on, its Andee can hand work to this Mac's dsh directly; this Mac's Andee takes no part and is not asked. The files it makes stay on this Mac and are fetched only when opened."),
                        color = Palette.secondary, fontSize = 11.sp, lineHeight = 15.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (!r.linked) Text(t("连接", "Connect"), color = Palette.accent, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            Thread { Peers.connect(r.id); tick++ }.apply { isDaemon = true }.start()
                        }.padding(vertical = 6.dp))
                    if (r.linked) Text(t("测试", "Ping"), color = Palette.accent, fontSize = 12.sp,
                        modifier = Modifier.clickable { Peers.ping(r.id) }.padding(vertical = 6.dp))
                    Spacer(Modifier.weight(1f))
                    val armed = confirmUnpair == r.id
                    Text(if (armed) t("确认删除", "Confirm remove") else t("删除", "Remove"), color = Palette.danger, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            if (armed) { Peers.unpair(r.id); confirmUnpair = null; tick++ } else confirmUnpair = r.id
                        }.padding(vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        SectionTitle(t("允许其它设备找到我", "Let other devices find me"))
        SegmentedPicker(listOf("off" to t("关", "Off"), "on" to t("开", "On")), if (Peers.enabled) "on" else "off") {
            Peers.setEnabled(it == "on"); tick++
        }
        Text(
            t("打开后，朋友在同一个 Wi-Fi 里能找到这台 Mac（暂时只支持同一个 Wi-Fi）。不是朋友的设备连不上，也看不到任何内容。显示二维码时会自动打开。",
                "When on, friends on the same Wi-Fi can reach this Mac (same Wi-Fi only, for now). Anyone else cannot connect or see anything. Showing a code turns it on."),
            color = Palette.secondary, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )

        SectionTitle(t("这台设备", "This device"))
        Text(me.name, color = Palette.label, fontSize = 14.sp)
        Text(t("指纹 ", "Fingerprint ") + me.fingerprint, color = Palette.secondary, fontSize = 11.sp)
    }
}

/** [text] as a QR code: one pixel per module, scaled up by the [Image] with no smoothing. */
private fun qrImage(text: String): androidx.compose.ui.graphics.ImageBitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(
        text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 0, com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M),
    )
    val img = java.awt.image.BufferedImage(m.width, m.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
    for (y in 0 until m.height) for (x in 0 until m.width) img.setRGB(x, y, if (m[x, y]) 0x000000 else 0xFFFFFF)
    return img.toComposeImageBitmap()
}
