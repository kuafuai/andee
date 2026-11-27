package net.kuafuai.andee.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale

/**
 * The input pill, drawn one way in two places: resting at the foot of the card and inside
 * [TextInputActivity]. One builder, so the two cannot drift.
 *
 * Voice is the default on both. In keyboard mode the microphone sits immediately left of send;
 * in voice mode that same slot is a keyboard and there is no empty send button. The [middle]
 * view owns its own behaviour: a real field, or the hold-to-talk surface supplied by the host.
 */
object InputPill {

    fun build(
        context: Context,
        middle: View,
        voiceMode: Boolean,
        onCamera: () -> Unit,
        onAlbum: () -> Unit,
        onSend: () -> Unit,
        onToggleMode: () -> Unit,
    ): LinearLayout {
        fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Glass.well(context, dp(24))
            setPadding(dp(6), dp(4), dp(5), dp(4))
            minimumHeight = dp(48)
        }
        fun icon(res: Int, desc: String, onClick: () -> Unit) = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, res)?.mutate())
            setColorFilter(Color.parseColor(Glass.SECONDARY))
            contentDescription = desc
            setPadding(dp(7), dp(7), dp(7), dp(7))
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { onClick() }
        }
        pill.addView(
            icon(R.drawable.ic_camera, AppLocale.str(context, R.string.input_take_photo), onCamera),
            LinearLayout.LayoutParams(dp(36), dp(36)),
        )
        pill.addView(
            icon(R.drawable.ic_image, AppLocale.str(context, R.string.input_pick_photos), onAlbum),
            LinearLayout.LayoutParams(dp(36), dp(36)),
        )
        pill.addView(middle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        pill.addView(
            icon(
                if (voiceMode) R.drawable.ic_keyboard else R.drawable.ic_mic,
                AppLocale.str(context, if (voiceMode) R.string.input_switch_keyboard else R.string.input_switch_voice),
                onToggleMode,
            ),
            LinearLayout.LayoutParams(dp(36), dp(36)),
        )
        if (!voiceMode) {
            val send = ImageView(context).apply {
                setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_send)?.mutate())
                setColorFilter(Color.WHITE)
                contentDescription = AppLocale.str(context, R.string.input_send)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(Glass.ACCENT))
                }
                isClickable = true
                Glass.pressable(this)
                setOnClickListener { onSend() }
            }
            pill.addView(send, LinearLayout.LayoutParams(dp(36), dp(36)))
        }
        return pill
    }
}
