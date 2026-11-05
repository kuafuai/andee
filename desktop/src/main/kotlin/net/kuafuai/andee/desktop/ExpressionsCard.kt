package net.kuafuai.andee.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuafuai.andee.ui.ball.BallLooks
import net.kuafuai.andee.ui.ball.Mood
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** 形象与表情: every look and every expression, to look at and to choose. */
@Composable
fun ExpressionsCard(onDone: () -> Unit) {
    val zh = Strings.lang() == "zh"
    var pickedMood by remember { mutableStateOf(Mood.CALM) }
    val currentLook = Settings.get("ball").ifEmpty { BallLooks.active.name }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        GlassHeader(if (zh) "形象与表情" else "Look & expressions", if (zh) "选一个形象，表情跟着它变" else "Pick a look; the face follows", onClose = onDone)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BallPreview(pickedMood, 168.dp)
        }
        Spacer(Modifier.height(14.dp))

        SectionTitle(if (zh) "形象" else "Look")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (look in BallLooks.ALL) {
                val on = look.name == currentLook
                Text(
                    Strings.get("ball_look_${look.name}"), color = if (on) Palette.accent else Palette.label, fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().glassPanel(12)
                        .border(1.dp, if (on) Palette.accent.copy(alpha = 0.5f) else Palette.panelEdge, RoundedCornerShape(12.dp))
                        .clickable { MacBall.pickLook(look.name) }.padding(12.dp),
                )
            }
        }

        SectionTitle(if (zh) "表情" else "Expressions")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val feelings = listOf(
                Mood.CALM, Mood.HAPPY, Mood.CURIOUS, Mood.TENSE, Mood.ANXIOUS, Mood.CONCERNED,
                Mood.WINK, Mood.WAVE, Mood.PROTECT, Mood.CELEBRATE, Mood.REST, Mood.SUCCESS, Mood.FAILED,
            )
            feelings.chunked(4).forEach { rowMoods ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    for (m in rowMoods) {
                        val on = m == pickedMood
                        Text(
                            moodLabel(m, zh), color = if (on) Palette.title else Palette.secondary, fontSize = 13.sp,
                            modifier = Modifier
                                .background(if (on) Palette.thumb else Color.Transparent, RoundedCornerShape(10.dp))
                                .border(1.dp, if (on) Palette.thumbEdge else Palette.panelEdge, RoundedCornerShape(10.dp))
                                .clickable { pickedMood = m }.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun moodLabel(m: Mood, zh: Boolean): String = if (zh) when (m) {
    Mood.CALM -> "平静"; Mood.HAPPY -> "开心"; Mood.CURIOUS -> "好奇"; Mood.TENSE -> "紧张"
    Mood.ANXIOUS -> "焦虑"; Mood.CONCERNED -> "担心"; Mood.WINK -> "眨眼"; Mood.WAVE -> "挥手"
    Mood.PROTECT -> "护着"; Mood.CELEBRATE -> "庆祝"; Mood.REST -> "歇着"; Mood.SUCCESS -> "成功"; Mood.FAILED -> "失败"
    else -> m.name.lowercase()
} else m.name.lowercase()

/** A second ball, decoupled from the corner one so poking an expression touches only this card. */
@Composable
internal fun BallPreview(mood: Mood, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val renderer = remember(px) { BallRenderer(px, px) }
    val bitmap = remember(px) { Bitmap().apply { allocPixels(ImageInfo(px, px, ColorType.BGRA_8888, ColorAlphaType.PREMUL)) } }
    val image = remember(bitmap) { bitmap.asComposeImageBitmap() }
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(renderer) {
        val queued = java.util.concurrent.atomic.AtomicBoolean(false)
        renderer.onFrame = {
            if (queued.compareAndSet(false, true)) javax.swing.SwingUtilities.invokeLater {
                queued.set(false)
                renderer.frame?.let { f -> bitmap.installPixels(f); bitmap.notifyPixelsChanged(); tick++ }
            }
        }
        onDispose { renderer.onFrame = {}; renderer.stop() }
    }
    LaunchedEffect(renderer, mood) { renderer.setState(mood, listening = false, emotion = null) }
    Canvas(Modifier.size(size)) {
        if (tick > 0) drawImage(image, dstOffset = IntOffset.Zero, dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()))
    }
}
