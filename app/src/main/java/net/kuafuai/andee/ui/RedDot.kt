package net.kuafuai.andee.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/** A small red dot: something here is waiting for the user (守卫 unwritten). Not a count. Ignores touches. */
object RedDot {
    fun view(context: Context): View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(Glass.DANGER))
        }
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}
