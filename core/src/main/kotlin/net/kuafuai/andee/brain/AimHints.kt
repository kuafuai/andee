package net.kuafuai.andee.brain

import org.json.JSONObject

/**
 * Pointing the model at `tap_target` when its own aim keeps missing. Measured on the Mac (10-29, a reply on X): a
 * thin editor box under grid taps that landed ~3% below it, six misses, a stuck-loop refusal that said only
 * "tell the user" — and the model concluded the box could not be clicked and asked the user to do it. One
 * `tap_target` call, once the user suggested it, found the box at once. Nothing in what the device said had
 * named it.
 *
 * The device knows what the model does not: that the screen did not change, how many times in a row, and that a
 * vision locator is on offer. So the hint is the device's, one implementation in [LocalBrain] for both bodies,
 * and only when `tap_target` is in the tool list.
 */
object AimHints {

    /** The start of both bodies' same-spot refusal, so the loop can add the way out to it. */
    const val REPEAT_REFUSED = "refused: this spot has been tapped too many times"

    private val COORD_TAPS = setOf("screen.tap", "screen.long_press")
    private val ID_TAPS = setOf("screen.tap_id", "screen.long_press_id")
    private const val TARGET = "screen.tap_target"

    fun isTap(method: String) = method in COORD_TAPS || method in ID_TAPS || method == TARGET

    /**
     * The hint for a tap result, or null. [missesInARow] counts coordinate taps whose screen did not change,
     * including this one. Only for a result that actually says `screen_changed: false`.
     */
    fun afterTap(method: String, result: JSONObject, missesInARow: Int): String? {
        if (!result.has("screen_changed") || result.optBoolean("screen_changed", true)) return null
        return when {
            method == TARGET ->
                "tap_target found and tapped \"${result.optString("saw").ifEmpty { "it" }}\" but nothing changed. Check you named the " +
                    "thing that takes the click: a send/Reply button is greyed out and inert until its box has text, so to type, " +
                    "name the input box itself (its placeholder text). Describe it differently rather than giving up on tap_target."
            method in ID_TAPS ->
                "The element list has this element but tapping it changed nothing — on web pages the real control is often drawn " +
                    "inside or on top of what accessibility lists (rich-text editors especially). Next, use tap_target and describe " +
                    "the thing you see (its text, its placeholder), not the grid."
            method in COORD_TAPS && missesInARow >= 2 ->
                "$missesInARow coordinate taps in a row changed nothing. The target is probably thinner than the cell part you are " +
                    "naming (a one-line input box, a small icon), so the grid keeps landing beside it. Use tap_target: describe the " +
                    "element in words and a vision model aims at it far more finely than a grid cell."
            else -> null
        }
    }

    /** Added to a same-spot refusal when `tap_target` is on offer. */
    const val REFUSED_WAY_OUT =
        " You have not run out of ways: use tap_target and describe the element (its text, placeholder or icon, and where it " +
            "is) — a vision model aims more finely than the grid. Ask the user only if that fails too."
}
