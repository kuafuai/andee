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
import net.kuafuai.andee.text.Md

/**
 * What the model writes in the notebook and the wiki is Markdown, and shown as plain text it reads as a
 * page of asterisks and pipes. The parsing is [Md] in core, shared with the phone's renderer; this only
 * draws its blocks with the card's own palette.
 */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier, color: Color = Palette.label, size: TextUnit = 14.sp) {
    val blocks = remember(text) { Md.blocks(text) }
    SelectionContainer(modifier) {
        Column {
            blocks.forEachIndexed { i, b ->
                if (i > 0) Spacer(Modifier.height(if (b is Md.Block.Heading) 14.dp else 8.dp))
                MdBlockView(b, color, size)
            }
        }
    }
}

/** [Md.runs] drawn as an [AnnotatedString] in the card's palette. */
private fun inline(text: String, accent: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    for (r in Md.runs(text)) {
        val style = SpanStyle(
            fontWeight = if (r.bold) FontWeight.SemiBold else null,
            fontStyle = if (r.italic) FontStyle.Italic else null,
            fontFamily = if (r.code) FontFamily.Monospace else null,
            background = if (r.code) codeBg else Color.Unspecified,
            textDecoration = if (r.strike) TextDecoration.LineThrough else null,
            color = if (r.link) accent else Color.Unspecified,
        )
        val url = r.url
        if (url != null) {
            withLink(LinkAnnotation.Url(url, TextLinkStyles(style.copy(textDecoration = TextDecoration.Underline)))) { append(r.text) }
        } else {
            withStyle(style) { append(r.text) }
        }
    }
}

@Composable
private fun MdBlockView(b: Md.Block, color: Color, size: TextUnit) {
    val accent = Palette.accent
    val codeBg = Palette.glass
    val line = (size.value * 1.6f).sp
    when (b) {
        is Md.Block.Heading -> {
            val s = when (b.level) { 1 -> size.value + 6; 2 -> size.value + 3; 3 -> size.value + 1; else -> size.value }.sp
            Text(inline(b.text, accent, codeBg), color = Palette.title, fontSize = s, lineHeight = (s.value * 1.4f).sp,
                fontWeight = if (b.level <= 2) FontWeight.SemiBold else FontWeight.Medium)
        }
        is Md.Block.Para -> Text(inline(b.text, accent, codeBg), color = color, fontSize = size, lineHeight = line)
        is Md.Block.Item -> Row(Modifier.padding(start = (b.depth * 18).dp)) {
            Text(b.marker, color = Palette.secondary, fontSize = size, lineHeight = line, modifier = Modifier.widthIn(min = 18.dp))
            Text(inline(b.text, accent, codeBg), color = color, fontSize = size, lineHeight = line)
        }
        is Md.Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(Palette.hairline, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(10.dp))
            Column {
                b.inner.forEachIndexed { i, inner ->
                    if (i > 0) Spacer(Modifier.height(6.dp))
                    MdBlockView(inner, Palette.secondary, size)
                }
            }
        }
        is Md.Block.Code -> Box(Modifier.fillMaxWidth().background(codeBg, RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState()).padding(10.dp)) {
            Text(b.text, color = color, fontSize = (size.value - 1).sp, fontFamily = FontFamily.Monospace, lineHeight = line, softWrap = false)
        }
        is Md.Block.Table -> MdTable(b, color, size, accent, codeBg)
        Md.Block.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Palette.hairline))
    }
}

@Composable
private fun MdTable(t: Md.Block.Table, color: Color, size: TextUnit, accent: Color, codeBg: Color) {
    val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
    val s = (size.value - 1).sp
    Column(Modifier.fillMaxWidth().background(codeBg, RoundedCornerShape(8.dp)).padding(vertical = 2.dp)) {
        @Composable
        fun row(cells: List<String>, head: Boolean) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                for (c in 0 until cols) {
                    Text(inline(cells.getOrElse(c) { "" }, accent, codeBg), color = if (head) Palette.title else color,
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
