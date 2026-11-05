package net.kuafuai.andee.desktop.pet

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import net.kuafuai.andee.util.Log
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

typealias PetPose = net.kuafuai.andee.pet.PetPose

/**
 * The sprite sheet, decoded once and kept.
 *
 * A preset's art ships inside the app (`desktop/src/main/resources/pet/<look>-<pose>.png`);
 * a custom pet is a PNG in the app's own `pets/` folder. Both paths end in the same
 * [ImageBitmap], so the window and the settings preview do not care which they got.
 *
 * Transparent, and trimmed to the body by `tools/pet_sprites.py` (presets) or by
 * [PetLibrary.addCustom] (custom): the window *is* the pet's box, so padding would be a
 * rectangle swallowing clicks where nothing is drawn.
 *
 * A missing file is not fatal: the window draws a quiet grey disc and the log names the
 * asset. A pet that cannot find its art should look wrong, not take the app down.
 */
object PetSprites {

    private val cache = ConcurrentHashMap<String, ImageBitmap>()
    private val missingLogged = ConcurrentHashMap.newKeySet<String>()
    private val maskCache = ConcurrentHashMap<String, AlphaMask>()

    /**
     * Which pixels of a drawing are actually her.
     *
     * The window is her bounding box, and a box has empty corners: clicking the air beside her head
     * or between her legs would pat her, which feels like the desktop is answering instead of her.
     * The mask is sampled from the same bitmap that is on screen, at half resolution — the question
     * is "is this pixel her or the wallpaper", and half a pixel of error is not a question.
     */
    class AlphaMask(val w: Int, val h: Int, private val solid: BooleanArray) {
        fun at(px: Float, py: Float): Boolean {
            if (px < 0f || py < 0f || px >= w || py >= h) return false
            return solid[py.toInt() * w + px.toInt()]
        }
    }

    fun mask(look: PetLook, pose: PetPose): AlphaMask? {
        val key = "mask-${look.id}-${pose.file}@${look.dir?.path ?: "bundled"}"
        maskCache[key]?.let { return it }
        val bitmap = get(look, pose) ?: return null
        val w = (bitmap.width / 2).coerceAtLeast(1)
        val h = (bitmap.height / 2).coerceAtLeast(1)
        // `toPixelMap` rather than `readPixels`: the latter is backend-specific and came back with a
        // zeroed buffer here, which silently made every pixel "not her" and swallowed no clicks.
        val pixels = bitmap.toPixelMap()
        val solid = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Anything below a third is the antialiased edge of a hair, not a place to click.
                solid[y * w + x] = pixels[x * 2, y * 2].alpha > 0.31f
            }
        }
        val mask = AlphaMask(w, h, solid)
        maskCache[key] = mask
        return mask
    }

    fun get(look: PetLook, pose: PetPose): ImageBitmap? {
        // A look without an airborne or happy drawing still gets thrown and patted; it just
        // stands there while it happens, which is better than a hole where the pet was.
        decode(look, pose)?.let { return it }
        pose.fallback?.let { f -> decode(look, f)?.let { return it } }
        return if (pose == PetPose.IDLE) null else decode(look, PetPose.IDLE)
    }

    private fun decode(look: PetLook, pose: PetPose): ImageBitmap? {
        val key = "${look.id}-${pose.file}@${look.dir?.path ?: "bundled"}"
        cache[key]?.let { return it }
        val bytes = runCatching {
            if (look.dir == null) {
                PetSprites::class.java.getResourceAsStream("/pet/${look.id}-${pose.file}.webp")?.readBytes()
                    ?: PetSprites::class.java.getResourceAsStream("/pet/${look.id}-${pose.file}.png")?.readBytes()
            } else {
                java.io.File(look.dir, "${look.id}-${pose.file}.webp").takeIf { it.isFile }?.readBytes()
                    ?: java.io.File(look.dir, "${look.id}-${pose.file}.png").takeIf { it.isFile }?.readBytes()
            }
        }.onFailure { Log.w(TAG, "cannot read ${look.id}-${pose.file}.png: ${it.message}") }.getOrNull()
        if (bytes == null) {
            // Expected for every pose a look has no drawing of (see [PetPose.fallback]): say it once, not per frame.
            if (missingLogged.add(key)) Log.i(TAG, "no sprite ${look.id}-${pose.file}; using its fallback")
            return null
        }
        val decoded = decode(bytes)
        if (decoded == null) {
            Log.w(TAG, "not an image: ${look.id}-${pose.file}.png")
            return null
        }
        cache[key] = decoded
        return decoded
    }

    /**
     * The idle loop's frames, if this look has one (`anim/<look>-idle-N.webp`, computed by
     * `tools/pet_anim.py`). Empty when it does not — the window then falls back to the still with its
     * procedural breath.
     */
    fun frames(look: PetLook): List<ImageBitmap> {
        val key = "anim-${look.id}@${look.dir?.path ?: "bundled"}"
        frameCache[key]?.let { return it }
        val list = ArrayList<ImageBitmap>(32)
        for (i in 0 until 32) {
            val bytes = runCatching {
                if (look.dir == null) {
                    PetSprites::class.java.getResourceAsStream("/pet/anim/${look.id}-idle-$i.webp")?.readBytes()
                } else {
                    java.io.File(look.dir, "anim/${look.id}-idle-$i.webp").takeIf { it.isFile }?.readBytes()
                }
            }.getOrNull() ?: break
            list.add(decode(bytes) ?: break)
        }
        frameCache[key] = list
        return list
    }

    private val frameCache = ConcurrentHashMap<String, List<ImageBitmap>>()

    /**
     * How much of the box's height a pose is drawn at. Every drawing stands the full height of the box (each is
     * trimmed to her body, and she is the same height standing whatever her hands do) — except the airborne
     * one, a curled-up body, which keeps its size relative to the idle drawing.
     */
    fun heightShare(look: PetLook, pose: PetPose): Float {
        if (pose != PetPose.AIR) return 1f
        val air = decode(look, PetPose.AIR) ?: return 1f
        val idle = decode(look, PetPose.IDLE) ?: return 1f
        return (air.height.toFloat() / idle.height).coerceIn(0.3f, 1f)
    }

    /**
     * The box's width / height: wide enough for the widest pose at its drawn height. It used to be the idle
     * drawing's own shape, and every pose was fitted into it — so a wider pose shrank: a hand at her ear made
     * the whole of her a fifth smaller while she listened, and asleep on her side a third.
     */
    fun aspectOf(look: PetLook): Float = aspectCache.getOrPut("${look.id}@${look.dir?.path ?: "bundled"}") {
        PetPose.entries.mapNotNull { p ->
            decode(look, p)?.let { if (it.height == 0) null else it.width.toFloat() / it.height * heightShare(look, p) }
        }.maxOrNull() ?: 1f
    }

    private val aspectCache = ConcurrentHashMap<String, Float>()


    /**
     * Skia first: the sheets ship as WebP (a ninth of the PNG's size for this flat art), and Skia
     * decodes it with the same alpha handling the rest of the UI uses. ImageIO stays as the
     * fallback for a picture a user added, which is stored as PNG.
     */
    private fun decode(bytes: ByteArray): ImageBitmap? {
        val skia = runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
        if (skia != null && skia.width > 0) return skia
        return runCatching { ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap() }.getOrNull()
    }

    private const val TAG = "Pet"
}
