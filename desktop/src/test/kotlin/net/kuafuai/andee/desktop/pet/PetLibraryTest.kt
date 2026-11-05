package net.kuafuai.andee.desktop.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The custom-pet path: whatever the user drops in has to become something the window can
 * draw — trimmed, the right height, still transparent — or the pet is a giant photo with
 * a rectangle of desktop swallowed around it.
 */
class PetLibraryTest {

    private fun tempDir(): File = kotlin.io.path.createTempDirectory("andee-pet").toFile()

    /** 400×600, a body in the middle and wide empty margins — the shape a real picture arrives in. */
    private fun picture(): BufferedImage {
        val im = BufferedImage(400, 600, BufferedImage.TYPE_INT_ARGB)
        im.createGraphics().apply {
            color = Color(0xF7C6C6)
            fillRoundRect(120, 150, 160, 300, 60, 60)
            dispose()
        }
        return im
    }

    @Test
    fun aPictureBecomesACustomPet() {
        val dir = tempDir()
        val source = File(dir, "我的伙伴.png")
        ImageIO.write(picture(), "png", source)

        val look = PetLibrary.addCustom(source, dir) ?: error("a readable PNG was refused")
        assertTrue("must be marked custom", look.custom)
        assertEquals("我的伙伴", look.zh)

        val installed = File(dir, "${look.id}-idle.png")
        assertTrue("written next to the original", installed.isFile)
        val png = ImageIO.read(installed)
        assertEquals("scaled to the app's own height", PetLibrary.CUSTOM_HEIGHT, png.height)
        assertTrue("empty margins trimmed (was ${png.width}x${png.height})", png.width < 200)
        assertEquals("must stay transparent", 0, png.getRGB(0, 0) ushr 24)
    }

    @Test
    fun aFileThatIsNotAnImageIsRefused() {
        val dir = tempDir()
        val source = File(dir, "notes.png").apply { writeText("not an image") }
        assertNull(PetLibrary.addCustom(source, dir))
        assertTrue("nothing installed", dir.listFiles().orEmpty().none { it.name.endsWith("-idle.png") })
    }

    @Test
    fun removingACustomPetDeletesItsFile() {
        val dir = tempDir()
        val source = File(dir, "buddy.png")
        ImageIO.write(picture(), "png", source)
        val look = PetLibrary.addCustom(source, dir) ?: error("a readable PNG was refused")

        PetLibrary.removeCustom(look)
        assertFalse(File(dir, "${look.id}-idle.png").exists())
        // The original picture is the user's, not ours: removing the pet must not touch it.
        assertTrue(source.isFile)
    }

    @Test
    fun anUnknownIdReadsAsTheFirstPreset() {
        assertEquals(PetLibrary.PRESETS.first().id, PetLibrary.of("no-such-pet").id)
        assertEquals(PetLibrary.PRESETS.first().id, PetLibrary.of("").id)
    }
}
