package net.kuafuai.andee.desktop.pet

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import net.kuafuai.andee.util.Log
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/** One drawing of a character. A pose is a file, not a mood: the sprite is art, the mood is the ball. */
enum class PetPose(val file: String) {
    IDLE("idle"),
    HAPPY("happy"),

    /** Thrown or falling: hair and scarf up, knees tucked. Falls back to [IDLE] when a look has no such drawing. */
    AIR("air"),

    // The faces a pat can land on (see PetTalk). Each is a drawing of its own rather than a
    // transform of the idle one: a squashed PNG cannot frown, and the point of a pat is that
    // the pet answers with a face.
    ANGRY("angry"),
    SAD("sad"),
    SLEEPY("sleepy"),
    THINK("think"),

    /** Idle act only: holding a bubble-tea cup. A prop, not a reaction — it has no line of dialogue. */
    TEA("tea"),

    /** Idle act only: eating from a little hotpot. The kind of scene a desktop pet earns its keep with. */
    HOTPOT("hotpot"),
}

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

    fun get(look: PetLook, pose: PetPose): ImageBitmap? {
        // A look without an airborne or happy drawing still gets thrown and patted; it just
        // stands there while it happens, which is better than a hole where the pet was.
        decode(look, pose)?.let { return it }
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
            Log.w(TAG, "missing sprite ${look.id}-${pose.file}.png")
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
     * Width / height of the look's idle drawing: the window is as wide as the character is,
     * so a standing person is narrow and a sitting animal is square.
     */
    fun aspectOf(look: PetLook): Float {
        val art = get(look, PetPose.IDLE) ?: return 1f
        return if (art.height == 0) 1f else art.width.toFloat() / art.height.toFloat()
    }

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
