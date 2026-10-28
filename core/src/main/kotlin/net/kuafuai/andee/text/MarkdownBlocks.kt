package net.kuafuai.andee.text

/**
 * Markdown the model writes — diary pages, its self-description, wiki articles — parsed into blocks and styled runs
 * that each body draws with its own widgets (Compose on the Mac, Views on the phone). One parser, so a page cannot
 * render as a table on one body and a wall of pipes on the other.
 *
 * It is the subset the model actually writes: headings, lists, quotes, tables, fences, rules, and bold / italic /
 * code / strike / links inline. Anything it does not recognise falls through as an ordinary paragraph, so nothing
 * the model wrote is ever hidden. A leading `---` front-matter block (the wiki file's header) is dropped.
 *
 * `_italic_` only matches with no word character on either side, for the reason [stripMarkdown] gives: snake_case
 * tool names are everywhere in what this device writes, and `tap_screen_element` must not lose its underscores.
 */
object Md {
    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Para(val text: String) : Block()
        /** [marker] is `•` or `3.`; [depth] counts nesting levels from 0. */
        data class Item(val marker: String, val depth: Int, val text: String) : Block()
        data class Quote(val inner: List<Block>) : Block()
        data class Code(val text: String) : Block()
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block()
        object Rule : Block() { override fun toString() = "Rule" }
    }

    /** A stretch of inline text with one style. [link] marks any link; [url] is set only when it may be opened. */
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
        val link: Boolean = false,
        val url: String? = null,
    )

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

    fun blocks(raw: String): List<Block> = parse(stripFrontMatter(raw).replace("\r\n", "\n").lines())

    private fun parse(lines: List<String>): List<Block> {
        val out = mutableListOf<Block>()
        val para = mutableListOf<String>()
        fun flush() { if (para.isNotEmpty()) { out += Block.Para(joinLines(para)); para.clear() } }
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
                    out += Block.Code(body.joinToString("\n"))
                }
                HEADING.matches(trimmed) -> {
                    flush()
                    val m = HEADING.find(trimmed)!!
                    out += Block.Heading(m.groupValues[1].length, m.groupValues[2])
                    i++
                }
                RULE.matches(line) -> { flush(); out += Block.Rule; i++ }
                trimmed.startsWith(">") -> {
                    flush()
                    val inner = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        inner += lines[i].trim().removePrefix(">").removePrefix(" ")
                        i++
                    }
                    out += Block.Quote(parse(inner))
                }
                trimmed.contains('|') && i + 1 < lines.size && TABLE_SEP.matches(lines[i + 1]) -> {
                    flush()
                    val header = cells(trimmed)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) { rows += cells(lines[i].trim()); i++ }
                    out += Block.Table(header, rows)
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
                    out += Block.Item(marker, depth.coerceAtMost(4), text.toString())
                }
                else -> { para += trimmed; i++ }
            }
        }
        flush()
        return out
    }

    private fun cells(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }

    /**
     * A single newline inside a paragraph stays a line break — GitHub-comment style, not strict Markdown. What the
     * model writes is mostly one point per line (「我是 Andee。\n最近在忙：…」), and joining those lines, as strict
     * Markdown does, ran its self-description together into one sentence.
     */
    private fun joinLines(lines: List<String>): String = lines.joinToString("\n") { it.removeSuffix("\\") }

    private val INLINE = Regex(
        """`([^`]+)`""" +                                   // 1 code
            """|\*\*(.+?)\*\*|__(.+?)__""" +                // 2,3 bold
            """|~~(.+?)~~""" +                                // 4 strike
            """|\[([^\]]+)]\(([^)\s]+)[^)]*\)""" +            // 5,6 link
            """|(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\*)""" +   // 7 italic
            """|(?<!\w)_(?!\s)(.+?)(?<!\s)_(?!\w)""" +        // 8 italic
            """|<(https?://[^>\s]+)>""",                       // 9 autolink
    )

    /** Inline runs of [text], styles nesting (bold inside a link, italic inside bold). Only http(s) links carry a [Run.url]. */
    fun runs(text: String): List<Run> = mutableListOf<Run>().also { runs(text, Run(""), it) }.filter { it.text.isNotEmpty() }

    private fun runs(text: String, style: Run, out: MutableList<Run>) {
        var at = 0
        for (m in INLINE.findAll(text)) {
            out += style.copy(text = text.substring(at, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> out += style.copy(text = g[1], code = true)
                g[2].isNotEmpty() || g[3].isNotEmpty() -> runs(g[2].ifEmpty { g[3] }, style.copy(bold = true), out)
                g[4].isNotEmpty() -> runs(g[4], style.copy(strike = true), out)
                g[5].isNotEmpty() -> runs(g[5], style.copy(link = true, url = g[6].takeIf(::web) ?: style.url), out)
                g[7].isNotEmpty() || g[8].isNotEmpty() -> runs(g[7].ifEmpty { g[8] }, style.copy(italic = true), out)
                g[9].isNotEmpty() -> out += style.copy(text = g[9], link = true, url = g[9])
            }
            at = m.range.last + 1
        }
        out += style.copy(text = text.substring(at))
    }

    /**
     * The prose of [text] as one plain line, for a two-line preview: paragraphs, quotes and list items, without
     * their markup; tables, code, rules and headings are skipped — flattened, a table is a row of pipes and dashes.
     */
    fun preview(text: String, max: Int = 240): String {
        fun prose(b: Block): List<String> = when (b) {
            is Block.Para -> listOf(b.text)
            is Block.Item -> listOf(b.text)
            is Block.Quote -> b.inner.flatMap(::prose)
            else -> emptyList()
        }
        return blocks(text).flatMap(::prose).joinToString(" ") { p -> runs(p).joinToString("") { it.text }.replace('\n', ' ') }
            .trim().take(max)
    }

    /** A relative path from the model is shown, never followed. */
    private fun web(url: String) = url.startsWith("http://") || url.startsWith("https://")
}
