package net.kuafuai.andee.stage

/**
 * The named parts of a stage fragment: an element carrying `data-slot="name"`.
 *
 * `update_stage` replaces one slot's inside instead of the whole stage, which is
 * what makes a changing number cost a few dozen tokens instead of a page. Done on
 * the text, not in a WebView, so it works while the stage is not on screen. The
 * scan is deliberately small: find the opening tag that carries the attribute, then
 * walk forward counting opens and closes of the same tag name until they balance.
 * Void elements cannot be slots (they have no inside), and that is reported.
 */
object StageSlots {

    private val VOID = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr",
    )

    /** `data-slot` with its value double-, single- or un-quoted — all three are HTML, and models write all three. */
    private val ANY = Regex("""data-slot\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>/]+))""")

    private fun valueOf(m: MatchResult) = m.groupValues.drop(1).first { it.isNotEmpty() }

    /** Slot names in document order. */
    fun names(fragment: String): List<String> =
        ANY.findAll(fragment).map(::valueOf).filter { it.isNotEmpty() }.distinct().toList()

    sealed class Result {
        data class Ok(val fragment: String) : Result()
        data class Error(val reason: String) : Result()
    }

    /** [fragment] with the inside of the element whose `data-slot` is [slot] replaced by [inner]. */
    fun replace(fragment: String, slot: String, inner: String): Result {
        val attr = ANY.findAll(fragment).firstOrNull { valueOf(it) == slot }
            ?: return Result.Error("no slot named \"$slot\"; the slots on the stage are: ${names(fragment).ifEmpty { listOf("(none)") }.joinToString()}")
        val open = fragment.lastIndexOf('<', attr.range.first)
        if (open < 0) return Result.Error("slot \"$slot\" is not inside a tag")
        val tag = Regex("""<([a-zA-Z][a-zA-Z0-9-]*)""").find(fragment, open)?.groupValues?.get(1)?.lowercase()
            ?: return Result.Error("slot \"$slot\" is not inside a tag")
        val openEnd = fragment.indexOf('>', attr.range.last)
        if (openEnd < 0) return Result.Error("slot \"$slot\": the opening tag is not closed")
        if (tag in VOID || fragment[openEnd - 1] == '/') {
            return Result.Error("slot \"$slot\" is on a <$tag>, which has no inside; put data-slot on a container")
        }
        val tokens = Regex("""<(/?)$tag(?=[\s>/])[^>]*>""", RegexOption.IGNORE_CASE)
        var depth = 1
        var pos = openEnd + 1
        while (true) {
            val m = tokens.find(fragment, pos)
                ?: return Result.Error("slot \"$slot\": its <$tag> is never closed")
            if (m.groupValues[1] == "/") depth-- else if (!m.value.endsWith("/>")) depth++
            if (depth == 0) {
                return Result.Ok(fragment.substring(0, openEnd + 1) + inner + fragment.substring(m.range.first))
            }
            pos = m.range.last + 1
        }
    }
}
