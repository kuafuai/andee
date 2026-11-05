package net.kuafuai.andee.desktop.pet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The speech bubble's box on the desk, in dp. */
internal const val BUBBLE_W = 250
internal const val BUBBLE_H = 84

/** The pet's body, drawn at [height] tall — shared by the canvas, the settings preview and the shot harness. */
@Composable
fun PetFace(
    height: Dp,
    look: PetLook,
    pose: PetPose,
    tilt: Float = 0f,
    squash: Float = 0f,
    lift: Float = 0f,
    breath: Float = 1f,
    sway: Float = 0f,
    bob: Float = 0f,
    frame: ImageBitmap? = null,
    alpha: Float = 1f,
    drawShadow: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val art = remember(look, pose) { PetSprites.get(look, pose) }
    val aspect = PetSprites.aspectOf(look)
    // A frame already carries the motion (a mesh warp of the same master, see tools/pet_anim.py), so
    // the procedural breath is off while one is showing.
    val still = if (frame != null) 1f else breath
    val lean = if (frame != null) 0f else sway
    val rise = if (frame != null) 0f else bob
    Box(modifier.size(width = height * aspect, height = height), contentAlignment = Alignment.BottomCenter) {
        // A contact shadow, so it stands on the desktop instead of hovering over wallpaper —
        // and it pulls in as the pet leaves the ground, which is most of what sells a fall.
        if (drawShadow) Canvas(Modifier.fillMaxWidth().height(height * 0.075f)) {
            val shrink = 1f - 0.45f * lift
            val alpha = 0.22f * (1f - 0.6f * lift)
            drawOval(
                brush = Brush.radialGradient(
                    listOf(Color.Black.copy(alpha = alpha), Color.Transparent),
                    center = Offset(this.size.width / 2f, this.size.height / 2f),
                    radius = this.size.width / 2f,
                ),
                topLeft = Offset(this.size.width * (0.5f - 0.32f * shrink), 0f),
                size = Size(this.size.width * 0.64f * shrink, this.size.height),
            )
        }
        if (art != null) {
            Image(
                bitmap = frame ?: art,
                contentDescription = null,
                // Its own shape at its own height (PetSprites.heightShare), feet on the floor, centred — never
                // fitted into a box shaped for another pose. An idle frame is the idle drawing, sized as it.
                modifier = run {
                    val h = height * PetSprites.heightShare(look, pose)
                    Modifier.size(width = h * (art.width.toFloat() / art.height), height = h)
                }.graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    rotationZ = tilt + lean
                    scaleY = still * (1f - 0.16f * squash)
                    scaleX = 1f + 0.12f * squash
                    translationY = rise
                    this.alpha = alpha
                },
                contentScale = ContentScale.Fit,
            )
        } else {
            // Missing art must look wrong, not vanish: a faint disc says "this is the pet, its file is not here".
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color(0xFFB9C0C8).copy(alpha = 0.35f),
                    radius = this.size.minDimension * 0.3f,
                )
            }
        }
    }
}

@Composable
internal fun SpeechBubble(text: String, tailAt: Float) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val tail = 9.dp.toPx()
            val radius = 14.dp.toPx()
            val body = size.height - tail
            drawRoundRect(
                color = Color(0xF7FFFFFF),
                topLeft = Offset(0f, 0f),
                size = Size(size.width, body),
                cornerRadius = CornerRadius(radius, radius),
            )
            val tailX = tailAt.dp.toPx().coerceIn(tail, size.width - tail)
            drawPath(
                Path().apply {
                    moveTo(tailX - tail, body)
                    lineTo(tailX, size.height)
                    lineTo(tailX + tail, body)
                    close()
                },
                Color(0xF7FFFFFF),
            )
        }
        Column(
            Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text,
                color = Color(0xFF23262B),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Three quiet minutes before it lies down, then it says so once. */
private const val SLEEP_AFTER_MS = 180_000L

