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

    private companion object {
        /**
         * What fits the empty card's two-line summary at the width it is given,
         * with room to spare for a wider glyph set than Latin and for a device
         * with a generous font scale.
         */
        const val SUMMARY_BUDGET = 80
    }

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
                // The empty card draws this under the preset's name, in a row
                // capped at two lines (`HistoryListView.offer`). Both languages
                // are checked here because the two do not wrap alike: the
                // Chinese summaries came in at two lines and their English
                // twins at three, and one extra line per row was enough to push
                // the offers past the scrollback and scroll the hint off the
                // top. A number to fit, rather than a comment asking nicely.
                assertTrue("$tag summary over $SUMMARY_BUDGET", t.summary.length <= SUMMARY_BUDGET)
                assertTrue("$tag prompt blank", t.prompt.isNotBlank())
                assertTrue(
                    "$tag prompt is ${t.prompt.length} chars, limit ${Notebook.MAX_SCENE_PROMPT}",
                    t.prompt.length <= Notebook.MAX_SCENE_PROMPT,
                )
                // The example is not decoration: it is the whole text of the
                // turn a tapped bubble sends (see HistoryListView /
                // onSuggestionClick). A blank one is a bubble that talks to
                // nobody, and it would only be noticed by whoever tapped it.
                assertTrue("$tag example blank", t.example.isNotBlank())
                assertTrue("$tag example over 120", t.example.length <= 120)
            }
        }
    }

    @Test
    fun `preset names are unique`() {
        val names = ScenePresets.all.map { it.name }
        assertTrue(names.size == names.toSet().size)
    }

    @Test
    fun `every name the card offers is one the table can look back up`() {
        // `adoptIfMissing` resolves the name the bubble passed with `byName`, and
        // reports a miss by returning false rather than throwing. A name that
        // came out of `all` but did not survive the round trip would put us back
        // where this started — a bubble that names a scene nobody saves — and
        // the only symptom would be the model saying it has never seen one.
        for (p in ScenePresets.all) {
            assertTrue(p.name, ScenePresets.byName(p.name) === p)
        }
        assertTrue(ScenePresets.byName("no_such_scene") == null)
    }

    @Test
    fun `preset names are used verbatim as the scene's stored name`() {
        // The bubble hands the raw preset name to adoption instead of reading it
        // back from the title (which is translated and can change). That only
        // works while every name is already a legal stored key, so the rule is
        // asserted here rather than trusted.
        for (p in ScenePresets.all) {
            assertTrue(p.name, nameRule.matches(p.name))
        }
    }
}
