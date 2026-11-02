package net.kuafuai.andee.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageSlotsTest {

    private fun ok(r: StageSlots.Result) = (r as StageSlots.Result.Ok).fragment
    private fun err(r: StageSlots.Result) = (r as StageSlots.Result.Error).reason

    @Test fun replacesOnlyTheInsideOfTheSlot() {
        val f = """<h1>Hi</h1><div class="card" data-slot="price"><b>¥71</b></div><p>tail</p>"""
        assertEquals(
            """<h1>Hi</h1><div class="card" data-slot="price"><b>¥53</b></div><p>tail</p>""",
            ok(StageSlots.replace(f, "price", "<b>¥53</b>")),
        )
    }

    @Test fun nestedSameTagsBalance() {
        val f = """<div data-slot='a'><div>x<div>y</div></div></div><div>after</div>"""
        assertEquals("""<div data-slot='a'>NEW</div><div>after</div>""", ok(StageSlots.replace(f, "a", "NEW")))
    }

    @Test fun anUnknownSlotNamesTheOnesThatExist() {
        val f = """<section data-slot="weather"></section><section data-slot="plan"></section>"""
        val r = err(StageSlots.replace(f, "news", "x"))
        assertTrue(r, r.contains("weather") && r.contains("plan"))
        assertEquals(listOf("weather", "plan"), StageSlots.names(f))
    }

    @Test fun aVoidElementCannotBeASlot() {
        val r = err(StageSlots.replace("""<img data-slot="pic" src="a.png">""", "pic", "x"))
        assertTrue(r, r.contains("no inside"))
    }

    @Test fun anUnclosedSlotIsReported() {
        val r = err(StageSlots.replace("""<ul data-slot="l"><li>a</li>""", "l", "x"))
        assertTrue(r, r.contains("never closed"))
    }

    @Test fun unquotedAndSingleQuotedSlotsAreSlotsToo() {
        val f = """<div class=card data-slot=task><b>old</b></div><p data-slot='note'>n</p>"""
        assertEquals(listOf("task", "note"), StageSlots.names(f))
        assertEquals("""<div class=card data-slot=task>new</div><p data-slot='note'>n</p>""", ok(StageSlots.replace(f, "task", "new")))
    }
}
