package net.kuafuai.andee.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stage's live line reads a reply a sentence at a time; these are the cuts it makes. */
class StageLineTest {

    @Test fun chineseIsCutAtItsSentenceEnds() {
        assertEquals(
            listOf("三个方案我整理好了。", "我推荐第二个！", "你看屏幕？"),
            StageLine.sentences("三个方案我整理好了。我推荐第二个！你看屏幕？"),
        )
    }

    @Test fun commasAndDashesDoNotCut() {
        val one = "今天没有给你排什么事——日程表我还没拿到权限，看不了你的日历。"
        assertEquals(listOf(one), StageLine.sentences(one))
    }

    @Test fun englishAndLineBreaksCutToo() {
        assertEquals(
            listOf("I found three.", "The second is cheapest.", "Want me to order it?", "第二段"),
            StageLine.sentences("I found three. The second is cheapest. Want me to order it?\n第二段"),
        )
    }

    @Test fun aDecimalIsNotASentenceEnd() {
        assertEquals(listOf("到手 ¥53.80，比上周便宜。"), StageLine.sentences("到手 ¥53.80，比上周便宜。"))
    }

    @Test fun dwellIsAboutTwoSecondsAndCapped() {
        assertEquals(2_000L, StageLine.dwell("短句。"))
        assertTrue(StageLine.dwell("长".repeat(40)) in 2_001L..4_000L)
        assertEquals(4_000L, StageLine.dwell("长".repeat(400)))
    }
}
