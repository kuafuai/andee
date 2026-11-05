package net.kuafuai.andee.desktop.pet

import net.kuafuai.andee.desktop.AppDirs
import net.kuafuai.andee.util.Log
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * One selectable appearance.
 *
 * Two kinds, deliberately the same type: the **presets** ship inside the app as resources,
 * a **custom pet** is a file under the app's own `pets/` directory. Everything above this
 * (config, window, settings, the sprites) asks for a [PetLook] and never learns which it got.
 */
data class PetLook(
    val id: String,
    val zh: String,
    val en: String,
    /** Null for a preset (art lives in the jar); the pet's own folder for a custom one. */
    val dir: File? = null,
    /** How tall it stands at scale 100, in dp. A cat-shaped picture and a person need different heights to look the same size. */
    val baseHeight: Int = 200,
    /**
     * Who she is, not only how she looks — like a ball look (`BallLook.voice` / `persona`). While the pet is the
     * body these replace the ball look's ([Pet.voice], [Pet.persona]). Blank = Andee's own: the house voice and
     * the plain prompt, which is what a picture the user added gets.
     */
    val voice: String = "",
    val persona: String = "",
) {
    val custom: Boolean get() = dir != null
}

/**
 * The two prefab pets, plus whatever the user added.
 *
 * The girl and the boy are one character with two looks — same horns, same coral
 * neckerchief, same glowing core — because a pet is *Andee on the desktop*, not a set of
 * unrelated mascots. They are deliberately not blue: the ball already owns cobalt, and a
 * blue-haired girl in a white dress is somebody else's mascot.
 */
object PetLibrary {

    /**
     * Four characters, each a look, a voice and a manner (2026-11-05; the ball's looks work the same way). The
     * cream girl is plain Andee. The cobalt pair that used to be third and fourth were the same two drawn in blue;
     * they were replaced by the quiet girl (高冷文静) and the boy in the cream sweater (温柔男友), and a saved
     * `blue-girl` / `blue-boy` reads as those ([of]).
     */
    /** The shared presets (`PetCharacters`, core), with their pictures in this jar. */
    val PRESETS = net.kuafuai.andee.pet.PetCharacters.PRESETS.map {
        PetLook(it.id, it.zh, it.en, baseHeight = it.baseHeight, voice = it.voice, persona = it.persona)
    }

    /** Height a picture the user dropped in is rendered at: the girl's, so the first sight is not a giant or a speck. */
    const val CUSTOM_HEIGHT = 200

    /** Every look, presets first. */
    fun all(): List<PetLook> = PRESETS + custom()

    /** An id that no longer exists (a removed custom pet, a typo) reads as the first preset. */
    fun of(id: String): PetLook = net.kuafuai.andee.pet.PetCharacters.canonical(id).let { want -> all().firstOrNull { it.id == want } } ?: PRESETS.first()

    fun custom(): List<PetLook> = runCatching {
        petsDir().listFiles { f -> f.isFile && f.name.endsWith("-idle.png") }
            ?.sortedBy { it.name }
            ?.map { f ->
                val id = f.name.removeSuffix("-idle.png")
                val label = id.removePrefix(CUSTOM_PREFIX)
                PetLook(id, label, label, f.parentFile, CUSTOM_HEIGHT)
            }.orEmpty()
    }.getOrElse { emptyList() }

    /**
     * Install a picture as a custom pet.
     *
     * Decoded, flattened to an image this app can definitely draw, trimmed of transparent
     * margins, scaled to [CUSTOM_HEIGHT] and re-encoded as PNG. Doing it once here is what
     * keeps a 40 MP phone photo from becoming the pet, and keeps a decoder surprise out of
     * every launch.
     *
     * [into] is where it lands; tests pass a throwaway directory, the app passes [petsDir].
     *
     * @returns the installed look, or null when the file is not a readable image.
     */
    fun addCustom(source: File, into: File = petsDir()): PetLook? {
        val image = runCatching { ImageIO.read(source) }.getOrNull()
        if (image == null) {
            Log.w(TAG, "not an image: ${source.path}")
            return null
        }
        val trimmed = trim(image)
        val scaled = scaleToHeight(trimmed, CUSTOM_HEIGHT)
        val id = CUSTOM_PREFIX + source.nameWithoutExtension.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5_-]"), "").ifBlank { "pet" }
        val dir = into
        val file = File(dir, "$id-idle.png")
        runCatching {
            dir.mkdirs()
            ImageIO.write(scaled, "png", file)
        }.onFailure {
            Log.w(TAG, "cannot write ${file.path}: ${it.message}")
            return null
        }
        Log.i(TAG, "custom pet installed: ${file.path} (${scaled.width}x${scaled.height})")
        return PetLook(id, source.nameWithoutExtension, source.nameWithoutExtension, dir, CUSTOM_HEIGHT)
    }

    fun removeCustom(look: PetLook) {
        if (!look.custom) return
        runCatching { File(look.dir, "${look.id}-idle.png").delete() }
    }

    /**
     * Where custom pets live: beside the settings, inside the one folder "back up this app" means.
     *
     * Deliberately does not create it: this is a *reader* (the list, the sprite loader, the
     * settings preview all call it), and a reader that mkdirs the user's data folder leaves an
     * empty `pets/` behind on machines that never had a pet. [addCustom] creates it when it
     * actually has something to write.
     */
    fun petsDir(): File = File(AppDirs.root, "pets")

    /** Flatten to ARGB and cut the transparent margins, so the window hugs the body (see PetSprites). */
    private fun trim(image: BufferedImage): BufferedImage {
        val argb = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        argb.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        var top = argb.height
        var left = argb.width
        var right = -1
        var bottom = -1
        for (y in 0 until argb.height) {
            for (x in 0 until argb.width) {
                if ((argb.getRGB(x, y) ushr 24) != 0) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < 0) return argb                       // fully transparent: leave it, the window draws a disc
        val pad = (maxOf(argb.width, argb.height) * 0.008f).toInt()
        val x0 = (left - pad).coerceAtLeast(0)
        val y0 = (top - pad).coerceAtLeast(0)
        val x1 = (right + pad).coerceAtMost(argb.width - 1)
        val y1 = (bottom + pad).coerceAtMost(argb.height - 1)
        return argb.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    private fun scaleToHeight(image: BufferedImage, height: Int): BufferedImage {
        if (image.height == height) return image
        val width = (image.width.toFloat() * height / image.height).toInt().coerceAtLeast(1)
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        out.createGraphics().apply {
            setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            drawImage(image, 0, 0, width, height, null)
            dispose()
        }
        return out
    }

    private const val CUSTOM_PREFIX = "my-"
    private const val TAG = "Pet"
}

/**
 * Ask the user for a picture and install it as a custom pet.
 *
 * The AWT dialog blocks its thread, so it gets one of its own rather than the EDT; the
 * callback lands on that thread, which is fine for Compose state and keeps the settings
 * card responsive for as long as the dialog is open (macOS sheet dialogs can wait).
 */
fun pickCustomPet(onPicked: (PetLook?) -> Unit) {
    kotlin.concurrent.thread(name = "pet-picker") {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "选一张图片做桌宠", java.awt.FileDialog.LOAD)
        dialog.setFilenameFilter { _, name ->
            val n = name.lowercase()
            n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
        }
        dialog.isVisible = true
        val file = dialog.file?.let { File(dialog.directory, it) }
        onPicked(file?.let { PetLibrary.addCustom(it) })
    }
}
