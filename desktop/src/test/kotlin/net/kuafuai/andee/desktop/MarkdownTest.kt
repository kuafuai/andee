package net.kuafuai.andee.desktop

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
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
        val b = MdParse.blocks(page)
        assertEquals(MdBlock.Heading(1, "运营规划（草稿 v1）"), b[0])
        assertTrue(b[1] is MdBlock.Quote)
        assertEquals(MdBlock.Rule, b[2])
        assertEquals(MdBlock.Heading(2, "一、现状"), b[3])
        val t = b[4] as MdBlock.Table
        assertEquals(listOf("项", "值", "来源"), t.header)
        assertEquals(2, t.rows.size)
        assertEquals(MdBlock.Item("•", 0, "留言区打开后， 拟了两条"), b[5])
        assertEquals(MdBlock.Item("1.", 0, "第一"), b[6])
        assertEquals(MdBlock.Para("中文换行接着写"), b[7])
    }

    @Test fun inlineMarkersAreRemovedFromTheText() {
        val s = MdParse.inline("先**粗**后`码`再[链接](https://x.y)和*斜*", Color.Blue, Color.Gray)
        assertEquals("先粗后码再链接和斜", s.text)
    }

    @Test fun noFrontMatterIsLeftAlone() {
        assertEquals(MdBlock.Para("hello"), MdParse.blocks("hello").single())
    }
}
