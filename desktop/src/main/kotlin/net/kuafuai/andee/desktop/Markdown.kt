package net.kuafuai.andee.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What the model writes in the notebook and the wiki is Markdown, and shown as plain text it reads as a
 * page of asterisks and pipes. This is the subset it actually uses — headings, lists, quotes, tables,
 * fences, rules, and bold / italic / code / links inline — drawn with the card's own palette. Anything it
 * does not recognise falls through as an ordinary paragraph, so nothing the model wrote is ever hidden.
 *
 * A leading `---` front-matter block is dropped: it is the wiki file's header, which the card shows as
 * its own line rather than as text.
 */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, color: Color = Palette.label, size: TextUnit = 14.sp) {
    val blocks = remember(text) { MdParse.blocks(text) }
    SelectionContainer(modifier) {
        Column {
            blocks.forEachIndexed { i, b ->
                if (i > 0) Spacer(Modifier.height(if (b is MdBlock.Heading) 14.dp else 8.dp))
                MdBlockView(b, color, size)
            }
        }
    }
}

sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Para(val text: String) : MdBlock()
    data class Item(val marker: String, val depth: Int, val text: String) : MdBlock()
    data class Quote(val inner: List<MdBlock>) : MdBlock()
    data class Code(val text: String) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
    object Rule : MdBlock()
}

object MdParse {
    private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
    private val ITEM = Regex("""^(\s*)([-*+]|\d+[.)])\s+(.*)$""")
    private val RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val TABLE_SEP = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun stripFrontMatter(text: String): String {
        val t = text.trimStart()
        if (!t.startsWith("---\n")) return text
        val end = t.indexOf("\n---", 4)
        return if (end < 0) text else t.substring(end + 4).trimStart('\n')
    }

    fun blocks(raw: String): List<MdBlock> = parse(stripFrontMatter(raw).replace("\r\n", "\n").lines())

    private fun parse(lines: List<String>): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = mutableListOf<String>()
        fun flush() { if (para.isNotEmpty()) { out += MdBlock.Para(joinLines(para)); para.clear() } }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> { flush(); i++ }
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    flush()
                    val fence = trimmed.take(3)
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) { body += lines[i]; i++ }
                    i++ // the closing fence (or the end)
                    out += MdBlock.Code(body.joinToString("\n"))
                }
                HEADING.matches(trimmed) -> {
                    flush()
                    val m = HEADING.find(trimmed)!!
                    out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
                    i++
                }
                RULE.matches(line) -> { flush(); out += MdBlock.Rule; i++ }
                trimmed.startsWith(">") -> {
                    flush()
                    val inner = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        inner += lines[i].trim().removePrefix(">").removePrefix(" ")
                        i++
                    }
                    out += MdBlock.Quote(parse(inner))
                }
                trimmed.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(trimmed)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().contains('|') && lines[i].isNotBlank()) {
                        rows += cells(lines[i].trim()); i++
                    }
                    out += MdBlock.Table(header, rows)
                }
                ITEM.matches(line) -> {
                    flush()
                    val m = ITEM.find(line)!!
                    val depth = m.groupValues[1].replace("\t", "    ").length / 2
                    val text = StringBuilder(m.groupValues[3])
                    i++
                    // A lazy continuation line belongs to the item, not to a new paragraph.
                    while (i < lines.size && lines[i].isNotBlank() && lines[i].startsWith("  ") && !ITEM.matches(lines[i])) {
                        text.append(' ').append(lines[i].trim()); i++
                    }
                    val marker = m.groupValues[2].let { if (it[0].isDigit()) it.trimEnd(')').trimEnd('.') + "." else "•" }
                    out += MdBlock.Item(marker, depth.coerceAtMost(4), text.toString())
                }
                else -> { para += trimmed; i++ }
            }
        }
        flush()
        return out
    }

    private fun cells(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }

    /** Soft line breaks: a space between Latin words, nothing between Chinese characters. */
    private fun joinLines(lines: List<String>): String = buildString {
        for ((k, l) in lines.withIndex()) {
            if (k > 0) {
                val hardBreak = lines[k - 1].endsWith("  ") || lines[k - 1].endsWith("\\")
                if (hardBreak) { if (endsWith("\\")) setLength(length - 1); append('\n') }
                else if (!(isCjk(last()) && isCjk(l.first()))) append(' ')
            }
            append(l)
        }
    }

    private fun isCjk(c: Char): Boolean = c.code in 0x2E80..0x9FFF || c.code in 0xFF00..0xFFEF || c.code in 0x3000..0x303F

    private val INLINE = Regex(
        """`([^`]+)`""" +                          // 1 code
            """|\*\*(.+?)\*\*|__(.+?)__""" +       // 2,3 bold
            """|~~(.+?)~~""" +                       // 4 strike
            """|\[([^\]]+)]\(([^)\s]+)[^)]*\)""" +   // 5,6 link
            """|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\*)|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""" + // 7,8 italic
            """|<(https?://[^>\s]+)>""",              // 9 autolink
    )

    fun inline(text: String, accent: Color, codeBg: Color): AnnotatedString = buildAnnotatedString { appendInline(text, accent, codeBg) }

    private fun AnnotatedString.Builder.appendInline(text: String, accent: Color, codeBg: Color) {
        var at = 0
        for (m in INLINE.findAll(text)) {
            append(text.substring(at, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 0.92.em())) { append(g[1]) }
                g[2].isNotEmpty() || g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInline(g[2].ifEmpty { g[3] }, accent, codeBg) }
                g[4].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(g[4], accent, codeBg) }
                g[5].isNotEmpty() -> link(g[5], g[6], accent)
                g[7].isNotEmpty() || g[8].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(g[7].ifEmpty { g[8] }, accent, codeBg) }
                g[9].isNotEmpty() -> link(g[9], g[9], accent)
            }
            at = m.range.last + 1
        }
        append(text.substring(at))
    }

    private fun AnnotatedString.Builder.link(label: String, url: String, accent: Color) {
        // Only web links open; a relative path from the model is shown, not followed.
        if (url.startsWith("http://") || url.startsWith("https://")) {
            withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)))) { append(label) }
        } else {
            withStyle(SpanStyle(color = accent)) { append(label) }
        }
    }

    private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)
}

@Composable
private fun MdBlockView(b: MdBlock, color: Color, size: TextUnit) {
    val accent = Palette.accent
    val codeBg = Palette.glass
    val line = (size.value * 1.6f).sp
    when (b) {
        is MdBlock.Heading -> {
            val s = when (b.level) { 1 -> size.value + 6; 2 -> size.value + 3; 3 -> size.value + 1; else -> size.value }.sp
            Text(MdParse.inline(b.text, accent, codeBg), color = Palette.title, fontSize = s, lineHeight = (s.value * 1.4f).sp,
                fontWeight = if (b.level <= 2) FontWeight.SemiBold else FontWeight.Medium)
        }
        is MdBlock.Para -> Text(MdParse.inline(b.text, accent, codeBg), color = color, fontSize = size, lineHeight = line)
        is MdBlock.Item -> Row(Modifier.padding(start = (b.depth * 18).dp)) {
            Text(b.marker, color = Palette.secondary, fontSize = size, lineHeight = line, modifier = Modifier.widthIn(min = 18.dp))
            Text(MdParse.inline(b.text, accent, codeBg), color = color, fontSize = size, lineHeight = line)
        }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Palette.hairline, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Column {
                b.inner.forEachIndexed { i, inner ->
                    if (i > 0) Spacer(Modifier.height(6.dp))
                    MdBlockView(inner, Palette.secondary, size)
                }
            }
        }
        is MdBlock.Code -> Box(Modifier.fillMaxWidth().background(codeBg, RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState()).padding(10.dp)) {
            Text(b.text, color = color, fontSize = (size.value - 1).sp, fontFamily = FontFamily.Monospace, lineHeight = line, softWrap = false)
        }
        is MdBlock.Table -> MdTable(b, color, size, accent, codeBg)
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Palette.hairline))
    }
}

@Composable
private fun MdTable(t: MdBlock.Table, color: Color, size: TextUnit, accent: Color, codeBg: Color) {
    val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
    val s = (size.value - 1).sp
    Column(Modifier.fillMaxWidth().background(codeBg, RoundedCornerShape(8.dp)).padding(vertical = 2.dp)) {
        @Composable
        fun row(cells: List<String>, head: Boolean) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                for (c in 0 until cols) {
                    Text(MdParse.inline(cells.getOrElse(c) { "" }, accent, codeBg), color = if (head) Palette.title else color,
                        fontSize = s, lineHeight = (s.value * 1.5f).sp, fontWeight = if (head) FontWeight.Medium else null,
                        modifier = Modifier.weight(1f).padding(end = 10.dp))
                }
            }
        }
        row(t.header, true)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
        t.rows.forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(1.dp).background(Palette.hairline.copy(alpha = 0.5f)))
            row(r, false)
        }
    }
}
