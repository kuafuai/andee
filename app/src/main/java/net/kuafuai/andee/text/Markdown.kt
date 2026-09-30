package net.kuafuai.andee.text

/**
 * Flatten the brain's markdown into something worth speaking aloud.
 *
 * The brain answers in markdown (`**加粗**`, `## 标题`, `- 列表`, links), which
 * TTS either reads out as punctuation or turns into strange pauses, and which
 * clutters the one-line subtitle band.
 *
 * Deliberately leaves `_` alone. Underscore-italic is vanishingly rare in the
 * Chinese answers this thing actually produces, while snake_case is everywhere
 * in the progress subtitles — every body tool is named `tap_screen`,
 * `get_screen_element`, `submit_input`. Treating `_` as emphasis would silently read
 * those as "tapscreen".
 */

// 整行构造连行尾换行一起吃掉(末尾的 `\n?`),否则删掉的行会留下一个空行,
// 字幕多一截空白、TTS 多一次停顿。
// 分隔线必须在强调之前处理:`***` 整行既是分隔线也能被 *强调* 正则吃掉。
private val RULE = Regex("""^[ \t]*([-*])([ \t]*\1){2,}[ \t]*$\n?""", RegexOption.MULTILINE)
private val CODE_FENCE = Regex("""^[ \t]*`{3,}.*$\n?""", RegexOption.MULTILINE)
// 图片在链接之前:![alt](url) 是 [text](url) 的超集,反了会剩一个孤零零的 `!`。
private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
private val HEADING = Regex("""^[ \t]{0,3}#{1,6}[ \t]+""", RegexOption.MULTILINE)
private val QUOTE = Regex("""^[ \t]{0,3}>+[ \t]?""", RegexOption.MULTILINE)
private val BULLET = Regex("""^[ \t]*[-*+][ \t]+""", RegexOption.MULTILINE)
private val ORDERED = Regex("""^[ \t]*\d+[.)][ \t]+""", RegexOption.MULTILINE)
// 表格分隔行(|---|:--:|)整行去掉;正文行的竖线换空格,免得念成一串停顿。
private val TABLE_RULE = Regex("""^[ \t]*\|?[ \t:|-]*\|[ \t:|-]*$\n?""", RegexOption.MULTILINE)
private val EMPHASIS = Regex("""\*{1,3}([^*\n]+)\*{1,3}""")
private val INLINE_CODE = Regex("""`+([^`\n]+)`+""")
private val BLANK_RUN = Regex("""\n{3,}""")
// 删掉标记会留下成片空格(表格的竖线尤其明显),压成一个,再清掉每行两端。
private val SPACE_RUN = Regex("""[ \t]{2,}""")
private val LINE_EDGE = Regex("""^[ \t]+|[ \t]+$""", RegexOption.MULTILINE)

/**
 * Returns [text] with markdown markup removed. Falls back to the original when
 * stripping would leave nothing — an answer made entirely of markup is more
 * likely a bug in this function than an empty answer from the brain.
 */
fun stripMarkdown(text: String): String {
    if (text.isEmpty()) return text
    var s = text
    s = CODE_FENCE.replace(s, "")
    s = RULE.replace(s, "")
    s = TABLE_RULE.replace(s, "")
    s = IMAGE.replace(s, "")
    s = LINK.replace(s, "$1")
    s = HEADING.replace(s, "")
    s = QUOTE.replace(s, "")
    s = BULLET.replace(s, "")
    s = ORDERED.replace(s, "")
    s = EMPHASIS.replace(s, "$1")
    s = INLINE_CODE.replace(s, "$1")
    s = s.replace('|', ' ')
    s = SPACE_RUN.replace(s, " ")
    s = LINE_EDGE.replace(s, "")
    s = BLANK_RUN.replace(s, "\n\n").trim()
    return s.ifEmpty { text }
}
