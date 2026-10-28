package net.kuafuai.andee.text

import net.kuafuai.andee.text.Md.Block
import net.kuafuai.andee.text.Md.Run
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownBlocksTest {
    private val page = """
        ---
        title: 运营规划
        kind: plan
        ---

        # 运营规划（草稿 v1）

        > 本页是**基于公开数据**搭的初版。

        ---

        ## 一、现状

        | 项 | 值 | 来源 |
        |---|---|---|
        | 仓库 | `github.com/kuafuai/andee` | GitHub API |
        | 版本 | **v0.7.1** | Releases |

        - 留言区打开后，
          拟了两条
        1. 第一
        中文换行
        接着写
    """.trimIndent()

    @Test fun parsesWhatTheWikiActuallyHolds() {
        val b = Md.blocks(page)
        assertEquals(Block.Heading(1, "运营规划（草稿 v1）"), b[0])
        assertEquals(Block.Quote(listOf(Block.Para("本页是**基于公开数据**搭的初版。"))), b[1])
        assertEquals(Block.Rule, b[2])
        assertEquals(Block.Heading(2, "一、现状"), b[3])
        val t = b[4] as Block.Table
        assertEquals(listOf("项", "值", "来源"), t.header)
        assertEquals(2, t.rows.size)
        assertEquals(Block.Item("•", 0, "留言区打开后， 拟了两条"), b[5])
        assertEquals(Block.Item("1.", 0, "第一"), b[6])
        assertEquals(Block.Para("中文换行接着写"), b[7])
    }

    @Test fun inlineStylesNestAndTheMarkersAreGone() {
        val r = Md.runs("先**粗*斜*粗**后`码`再[链接](https://x.y)和[本地](notes/a.md)")
        assertEquals("先粗斜粗后码再链接和本地", r.joinToString("") { it.text })
        assertEquals(Run("斜", bold = true, italic = true), r[2])
        assertEquals(Run("码", code = true), r[5])
        assertEquals(Run("链接", link = true, url = "https://x.y"), r[7])
        // A relative path is shown as a link, never followed.
        assertEquals(Run("本地", link = true, url = null), r[9])
    }

    @Test fun snakeCaseKeepsItsUnderscores() {
        assertEquals(listOf(Run("call tap_screen_element then get_screen_element")), Md.runs("call tap_screen_element then get_screen_element"))
    }

    @Test fun noFrontMatterIsLeftAlone() {
        assertEquals(Block.Para("hello"), Md.blocks("hello").single())
        assertTrue(Md.blocks("").isEmpty())
    }
}
