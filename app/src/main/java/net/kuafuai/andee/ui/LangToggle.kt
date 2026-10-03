package net.kuafuai.andee.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import net.kuafuai.andee.config.VoiceConfig

/**
 * 中 | EN — the two-cell language picker, for surfaces that are not the
 * settings card.
 *
 * ### Why this exists next to `SettingsUi.langPicker`
 *
 * That picker is a `private fun segments(...)` bound to `SettingsUi`'s own
 * field, so it cannot be reused from anywhere else; this is the small
 * standalone version for the self-check page. The two are **not** two
 * implementations of one decision — both call
 * [VoiceConfig.save]`(context, mapOf("lang" to …))`, which is the single write
 * path for the preference. Nothing here reads or writes `lang` any other way.
 *
 * The visual is deliberately the settings card's: a recessed track
 * ([Glass.well]) with the picked cell raised on it ([Glass.thumb]). It keeps
 * the whole `segments` feel without dragging over the parts that are about
 * *animated* selection — this control is two cells in a corner and the pick is
 * confirmed by the page redrawing in the new language, so a sliding thumb
 * would be motion for its own sake.
 *
 * ### The labels are not in resources, and that is the point
 *
 * `中` stays `中` and `EN` stays `EN` whatever the card is speaking. A language
 * picker labels each language *in its own script*; translating these is the one
 * way to make the control useless — the same rule (and the same two strings)
 * as `SettingsUi.langPicker`.
 *
 * ### Persistence
 *
 * [VoiceConfig.save] writes `SharedPreferences("voice_prefs")`, which is a real
 * file (`/data/data/<pkg>/shared_prefs/voice_prefs.xml`) and not an in-memory
 * field, and [VoiceConfig.uiLanguage] prefers the saved value over the device
 * locale forever once it is set. So the choice survives a restart by
 * construction, and this class does not need to cache anything to make that
 * true.
 *
 * @param onPicked fired **after** the write, so a caller that redraws from
 *   [net.kuafuai.andee.i18n.AppLocale] is guaranteed to read the new value.
 */
class LangToggle(
    context: Context,
    private val onPicked: (String) -> Unit,
) : FrameLayout(context) {

    private companion object {
        /** The two tags this app ships, and the only two strings in it that must not be translated. */
        val OPTIONS = listOf(VoiceConfig.LANG_ZH to "中", VoiceConfig.LANG_EN to "EN")

        const val INSET_DP = 3
        const val RADIUS_DP = 16

        /**
         * Keeps the two cells the same width.
         *
         * They are not naturally: `中` is one CJK glyph and `EN` is two Latin
         * ones, so with `WRAP_CONTENT` alone the picked pane visibly changes
         * size when you switch, which reads as the control *resizing* rather
         * than as a selection moving. Weights cannot fix it either — a
         * `layout_weight` needs `width = 0`, and a weighted child in a
         * `WRAP_CONTENT` parent has nothing to distribute. A floor under both
         * cells is the version that works with a wrapping track.
         */
        const val CELL_MIN_W_DP = 46
    }

    private val cells = mutableListOf<TextView>()
    private var current = VoiceConfig.uiLanguage(context)

    init {
        val inset = context.dpI(INSET_DP)
        setPadding(inset, inset, inset, inset)
        background = Glass.well(context, context.dpI(RADIUS_DP))

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val on = Color.parseColor(Glass.TITLE)
        val off = Color.parseColor(Glass.SECONDARY)

        OPTIONS.forEach { (tag, label) ->
            val cell = TextView(context).apply {
                text = label
                textSize = Glass.Type.BODY
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                minWidth = context.dpI(CELL_MIN_W_DP)
                setPadding(context.dpI(14), context.dpI(6), context.dpI(14), context.dpI(6))
                isClickable = true
                setOnClickListener { pick(tag) }
            }
            cells.add(cell)
            row.addView(cell)
        }
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        paint(on, off)
    }

    /**
     * Write the preference, then tell the caller.
     *
     * No-ops on the tag already in effect: the page redraws on every pick, and
     * redrawing for a tap that changed nothing costs a flash of the whole list
     * for no reason. Note this compares against [current], which starts from
     * storage rather than from the label — so a device whose language has never
     * been chosen (falling through to the system locale, see
     * [VoiceConfig.uiLanguage]) still highlights the right cell.
     */
    private fun pick(tag: String) {
        if (tag == current) return
        VoiceConfig.save(context, mapOf("lang" to tag))
        current = tag
        paint(Color.parseColor(Glass.TITLE), Color.parseColor(Glass.SECONDARY))
        onPicked(tag)
    }

    private fun paint(on: Int, off: Int) {
        val thumbRadius = context.dpI(RADIUS_DP - INSET_DP)
        cells.forEachIndexed { i, cell ->
            val on_ = OPTIONS[i].first == current
            cell.setTextColor(if (on_) on else off)
            // The picked cell carries the raised pane rather than a separate
            // sliding layer — same look, and no layout listener to keep in sync
            // with the cells it measures (see SettingsUi.segments for what that
            // costs when it goes wrong).
            cell.background = if (on_) Glass.thumb(context, thumbRadius) else null
        }
    }
}
