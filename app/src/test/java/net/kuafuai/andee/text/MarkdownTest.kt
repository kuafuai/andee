package net.kuafuai.andee.text

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {

    @Test
    fun stripsEmphasis() {
        assertEquals("已经打开微信了", stripMarkdown("已经**打开微信**了"))
        assertEquals("重点", stripMarkdown("***重点***"))
    }

    @Test
    fun leavesSnakeCaseAlone() {
        assertEquals("调用 tap_screen 和 get_ui_tree", stripMarkdown("调用 tap_screen 和 get_ui_tree"))
        assertEquals("submit_input 完成", stripMarkdown("submit_input 完成"))
    }

    @Test
    fun stripsHeadingsAndLists() {
        assertEquals("结果\n第一步\n第二步", stripMarkdown("## 结果\n- 第一步\n- 第二步"))
        assertEquals("甲\n乙", stripMarkdown("1. 甲\n2. 乙"))
    }

    @Test
    fun keepsLinkTextDropsUrl() {
        assertEquals("看这里", stripMarkdown("[看这里](https://example.com/a_b_c)"))
        // 图片连 alt 一起丢。整条只有一张图时会命中兜底(见
        // fallsBackWhenStrippingEmptiesEverything),所以这里带上下文。
        assertEquals("看图 就懂了", stripMarkdown("看图 ![截图](https://example.com/x.png) 就懂了"))
    }

    @Test
    fun stripsCodeAndFences() {
        assertEquals("ls -la", stripMarkdown("`ls -la`"))
        assertEquals("echo hi", stripMarkdown("```bash\necho hi\n```"))
    }

    @Test
    fun stripsRulesQuotesTables() {
        assertEquals("前\n后", stripMarkdown("前\n---\n后"))
        assertEquals("引用的话", stripMarkdown("> 引用的话"))
        assertEquals("姓名 年龄\n张三 30", stripMarkdown("| 姓名 | 年龄 |\n|---|---|\n| 张三 | 30 |"))
    }

    @Test
    fun fallsBackWhenStrippingEmptiesEverything() {
        assertEquals("---", stripMarkdown("---"))
        assertEquals("", stripMarkdown(""))
    }
}
