package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 产物: the pages the assistant has made, newest first — open, hand back to the
 * assistant for changes, or delete.
 *
 * The list is [ChatHistory.artifacts], not a store of its own: a page is
 * archived once, by `ui.show_html`, and the scrollback row and this row are
 * two views of the same file. Deleting here leaves the scrollback row behind
 * as 已清理, the same end state the 12-page cap produces.
 *
 * What the buttons *do* is the service's business — opening has to fold the
 * card, editing has to reach whichever brain is running — so this class only
 * reports which entry was picked.
 *
 * Same window shape as [NotebookUi]: focusable, hidden from accessibility,
 * counted by [OwnCard].
 */
class ArtifactsUi(
    private val context: Context,
    private val onOpen: (ChatHistory.Entry) -> Unit,
    private val onEdit: (ChatHistory.Entry) -> Unit,
) {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null
    private var frosted = false
    private var lctx: Context = context

    /** The row whose 删除 has been tapped once and now reads 确认删除. */
    private var arming: Long? = null

    fun isShown(): Boolean = root != null

    fun show() {
        if (root != null) return
        val p = WindowManager.LayoutParams(
            cardWidth(), cardHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        frosted = Glass.frost(context, wm, p)
        val v = build()
        runCatching {
            v.hideFromAccessibility()
            wm.addView(v, p)
            root = v
            OwnCard.shown()
            Glass.enter(v)
        }
    }

    fun hide(then: () -> Unit = {}) {
        val v = root ?: return
        root = null
        Glass.exit(v) {
            runCatching { wm.removeView(v) }
            OwnCard.hidden()
            then()
        }
    }

    private fun rebuild() {
        val old = root ?: return
        val v = build()
        runCatching {
            v.hideFromAccessibility()
            val p = old.layoutParams as WindowManager.LayoutParams
            wm.removeView(old)
            wm.addView(v, p)
            root = v
        }
    }

    private fun cardWidth(): Int {
        val screen = Glass.usableSize(context).first
        return minOf(dp(560), screen - dp(2 * GUTTER_DP))
    }

    private fun cardHeight(): Int {
        val screen = Glass.usableSize(context).second
        return minOf(dp(720), screen - dp(2 * GUTTER_DP))
    }

    // ---- build ----

    private fun build(): View {
        lctx = AppLocale.wrap(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.card(context, dp(28), frosted)
            clipToOutline = true
        }
        card.addView(header(), matchWrap())
        card.addView(
            View(context).apply { setBackgroundColor(Color.parseColor(Glass.PANEL_EDGE)) },
            LinearLayout.LayoutParams(MATCH, dp(1)),
        )

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        val all = ChatHistory.artifacts()
        if (all.isEmpty()) {
            body.addView(emptyState())
        } else {
            for (e in all) body.addView(row(e), matchWrap().apply { bottomMargin = dp(10) })
            body.addView(
                TextView(context).apply {
                    text = lctx.getString(R.string.artifacts_cap_note)
                    textSize = Glass.Type.MICRO
                    setTextColor(Color.parseColor(Glass.MUTED))
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                },
                matchWrap(),
            )
        }
        val scroll = ScrollView(context)
        scroll.addView(body, matchWrap())
        card.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return card
    }

    private fun header(): LinearLayout {
        val h = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(14), dp(12))
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(context).apply {
            text = lctx.getString(R.string.artifacts_title)
            textSize = Glass.Type.HEADLINE
            setTextColor(Color.parseColor(Glass.TITLE))
        })
        titles.addView(TextView(context).apply {
            text = lctx.getString(R.string.artifacts_subtitle)
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(Glass.SECONDARY))
            setPadding(0, dp(2), 0, 0)
        })
        h.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        h.addView(TextView(context).apply {
            text = "✕"
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(Glass.SECONDARY))
            gravity = Gravity.CENTER
            background = Glass.panel(context, dp(16))
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { hide() }
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        })
        return h
    }

    @SuppressLint("SetTextI18n")
    private fun row(e: ChatHistory.Entry): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Glass.panel(context, dp(14))
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { hide { onOpen(e) } }
        }
        row.addView(TextView(context).apply {
            text = e.text
            textSize = Glass.Type.BODY
            setTextColor(Color.parseColor(Glass.TITLE))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, matchWrap())

        val meta = buildString {
            append(SimpleDateFormat("yyyy-MM-dd HH:mm", AppLocale.localeOf(context)).format(Date(e.ts)))
            val bytes = e.page?.length() ?: 0L
            if (bytes > 0) append(" · ${(bytes + 1023) / 1024} KB")
        }
        row.addView(TextView(context).apply {
            text = meta
            textSize = Glass.Type.MICRO
            setTextColor(Color.parseColor(Glass.MUTED))
            setPadding(0, dp(4), 0, dp(10))
        }, matchWrap())

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(action(lctx.getString(R.string.artifacts_open), Glass.LABEL) {
            hide { onOpen(e) }
        })
        actions.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        actions.addView(action(lctx.getString(R.string.artifacts_edit), Glass.ACCENT) {
            hide { onEdit(e) }
        })
        actions.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val armed = arming == e.id
        actions.addView(
            action(
                lctx.getString(if (armed) R.string.artifacts_delete_confirm else R.string.common_delete),
                Glass.DANGER,
            ) {
                if (arming == e.id) {
                    arming = null
                    ChatHistory.deletePage(e.id)
                } else {
                    // Two taps, because unlike a memory a page cannot be asked
                    // for again word for word.
                    arming = e.id
                }
                rebuild()
            },
        )
        row.addView(actions, matchWrap())
        return row
    }

    private fun action(label: String, color: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = Glass.well(context, dp(12))
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }

    private fun emptyState(): TextView = TextView(context).apply {
        text = lctx.getString(R.string.artifacts_empty)
        textSize = Glass.Type.BODY
        setTextColor(Color.parseColor(Glass.SECONDARY))
        gravity = Gravity.CENTER
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(20), dp(48), dp(20), dp(48))
        layoutParams = matchWrap()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val GUTTER_DP = 20
    }
}
