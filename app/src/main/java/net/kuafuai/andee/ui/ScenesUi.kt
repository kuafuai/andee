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
import net.kuafuai.andee.config.Notebook
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.i18n.AppLocale

/**
 * 情景: the ways of working the assistant has learned with this person — enter
 * one, leave it, read its rules, or delete it.
 *
 * **There is no text editing here, on purpose.** A scene's prompt is prose the
 * model wrote for itself, and the person changes it the way they created it —
 * by saying what should be different. A form would invite hand edits that
 * break the structure §11 of the prompt asks for, and would be a second way to
 * write the one thing that already has a good one.
 *
 * Entering and leaving go through [VoiceConfig.setActiveScene], the same call
 * the `scene.*` tools make, so the card's chip and the scrollback row are drawn
 * by the service's listener whichever of them did it. Neither starts a model
 * call: the scene rides in the system prompt from the next thing the person
 * says.
 *
 * Same window shape as [ArtifactsUi]: focusable, hidden from accessibility,
 * counted by [OwnCard].
 */
class ScenesUi(private val context: Context) {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null
    private var frosted = false
    private var lctx: Context = context

    /** The scene whose 删除 has been tapped once and now reads 确认删除. */
    private var arming: String? = null

    /** Scenes whose rules are unfolded. */
    private val open = mutableSetOf<String>()

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

    /** Redraw in place — after one of its own buttons, or a change made elsewhere. */
    fun rebuild() {
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
        val active = VoiceConfig.activeScene(context)
        // The one in use first, then most recently used — the order the
        // prompt index uses, so the card and the model see the same list.
        val all = Notebook.scenes(context)
            .sortedWith(compareByDescending<Notebook.Scene> { it.name == active }.thenByDescending { it.lastUsedAt })
        if (all.isEmpty()) {
            body.addView(emptyState())
        } else {
            for (s in all) body.addView(row(s, s.name == active), matchWrap().apply { bottomMargin = dp(10) })
            body.addView(
                TextView(context).apply {
                    text = lctx.getString(R.string.scenes_edit_note)
                    textSize = Glass.Type.MICRO
                    setTextColor(Color.parseColor(Glass.MUTED))
                    gravity = Gravity.CENTER
                    setPadding(dp(8), dp(6), dp(8), 0)
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
            text = lctx.getString(R.string.scenes_title)
            textSize = Glass.Type.HEADLINE
            setTextColor(Color.parseColor(Glass.TITLE))
        })
        titles.addView(TextView(context).apply {
            text = lctx.getString(R.string.scenes_subtitle)
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
    private fun row(s: Notebook.Scene, active: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = if (active) Glass.well(context, dp(14)) else Glass.panel(context, dp(14))
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(context).apply {
            text = s.title
            textSize = Glass.Type.BODY
            setTextColor(Color.parseColor(Glass.TITLE))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (active) {
            top.addView(TextView(context).apply {
                text = lctx.getString(R.string.scenes_active)
                textSize = Glass.Type.MICRO
                setTextColor(Color.parseColor(Glass.ACCENT))
                setPadding(dp(8), 0, 0, 0)
            })
        }
        row.addView(top, matchWrap())

        if (s.summary.isNotBlank()) {
            row.addView(TextView(context).apply {
                text = s.summary
                textSize = Glass.Type.CAPTION
                setTextColor(Color.parseColor(Glass.SECONDARY))
                setPadding(0, dp(4), 0, 0)
            }, matchWrap())
        }

        val meta = buildList {
            add(lctx.getString(R.string.scenes_meta_uses, s.uses))
            if (s.triggerApps.isNotEmpty()) {
                add(lctx.getString(R.string.scenes_meta_auto, s.triggerApps.joinToString("、") { appLabel(it) }))
            }
        }.joinToString(" · ")
        row.addView(TextView(context).apply {
            text = meta
            textSize = Glass.Type.MICRO
            setTextColor(Color.parseColor(Glass.MUTED))
            setPadding(0, dp(4), 0, dp(10))
        }, matchWrap())

        val expanded = s.name in open
        if (expanded) {
            row.addView(TextView(context).apply {
                text = s.prompt
                textSize = Glass.Type.CAPTION
                setTextColor(Color.parseColor(Glass.LABEL))
                setLineSpacing(dp(2).toFloat(), 1f)
                setTextIsSelectable(true)
                background = Glass.well(context, dp(10))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }, matchWrap().apply { bottomMargin = dp(10) })
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (active) {
            actions.addView(action(lctx.getString(R.string.scenes_exit), Glass.LABEL) {
                VoiceConfig.setActiveScene(context, null)
                rebuild()
            })
        } else {
            actions.addView(action(lctx.getString(R.string.scenes_enter), Glass.ACCENT) {
                VoiceConfig.setActiveScene(context, s.name)
                Notebook.markSceneUsed(context, s.name)
                rebuild()
            })
        }
        actions.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        actions.addView(
            action(
                lctx.getString(if (expanded) R.string.scenes_hide_prompt else R.string.scenes_show_prompt),
                Glass.LABEL,
            ) {
                if (!open.remove(s.name)) open.add(s.name)
                rebuild()
            },
        )
        actions.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val armed = arming == s.name
        actions.addView(
            action(
                lctx.getString(if (armed) R.string.artifacts_delete_confirm else R.string.common_delete),
                Glass.DANGER,
            ) {
                if (arming == s.name) {
                    arming = null
                    // Leave it first, the same order `scene.delete` uses, so the
                    // listener can still name what was left.
                    if (VoiceConfig.activeScene(context) == s.name) VoiceConfig.setActiveScene(context, null)
                    Notebook.deleteScene(context, s.name)
                } else {
                    // Two taps: a scene is several sessions' worth of learning,
                    // and nothing here can bring it back.
                    arming = s.name
                }
                rebuild()
            },
        )
        row.addView(actions, matchWrap())
        return row
    }

    private fun appLabel(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

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
        text = lctx.getString(R.string.scenes_empty)
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
