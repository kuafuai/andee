package net.kuafuai.andee.config

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A preset that breaks `Notebook.saveScene`'s limits would only fail when a
 * user taps 采用, in the language they happen to have set. Checking both
 * languages here moves that to the build.
 */
class ScenePresetsTest {

    private val nameRule = Regex("[a-z0-9_]{1,40}")

    @Test
    fun `every preset fits the limits saveScene enforces, in both languages`() {
        for (p in ScenePresets.all) {
            assertTrue("name ${p.name}", nameRule.matches(p.name))
            for (english in listOf(false, true)) {
                val t = p.text(english)
                val tag = "${p.name}/${if (english) "en" else "zh"}"
                assertTrue("$tag title blank", t.title.isNotBlank())
                assertTrue("$tag title over 40", t.title.length <= 40)
                assertTrue("$tag summary blank", t.summary.isNotBlank())
                assertTrue("$tag summary over 160", t.summary.length <= 160)
                assertTrue("$tag prompt blank", t.prompt.isNotBlank())
                assertTrue(
                    "$tag prompt is ${t.prompt.length} chars, limit ${Notebook.MAX_SCENE_PROMPT}",
                    t.prompt.length <= Notebook.MAX_SCENE_PROMPT,
                )
            }
        }
    }

    @Test
    fun `preset names are unique`() {
        val names = ScenePresets.all.map { it.name }
        assertTrue(names.size == names.toSet().size)
    }
}
