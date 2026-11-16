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
import net.kuafuai.andee.config.ScenePresets
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.i18n.AppLocale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 情景 + 脚本: what the assistant has learned with this person — ways of
 * working (scenes) and ways of moving (flows). One card, two tabs: the head
 * and the hands, side by side on purpose, because a flow is what a scene's
 * routine looks like once it can move on its own.
 *
 * **There is no text editing here, on purpose.** A scene's prompt is prose the
 * model wrote for itself, and the person changes it the way they created it —
 * by saying what should be different. A flow's steps can be *looked at* — the
 * person must be able to check what it will do before it runs — but changing
 * them is the model's job too (re-record, or fix a failed run). A form would
 * invite hand edits that break the structure §11 of the prompt asks for, and
 * would be a second way to write the one thing that already has a good one.
 *
 * Entering and leaving scenes go through [VoiceConfig.setActiveScene], the
 * same call the `scene.*` tools make, so the card's chip and the scrollback
 * row are drawn by the service's listener whichever of them did it. Neither
 * starts a model call: the scene rides in the system prompt from the next
 * thing the person says. Running a flow is different — it *is* the act, so
 * the 运行 button hands the flow to the service ([onRunFlow]) and the card
 * folds out of the way.
 *
 * Same window shape as [ArtifactsUi]: focusable, hidden from accessibility,
 * counted by [OwnCard].
 */
class ScenesUi(
    private val context: Context,
    private val onRunFlow: (net.kuafuai.andee.config.Flow) -> Unit = {},
) {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null
    private var frosted = false
    private var lctx: Context = context

    /** 0 = scenes, 1 = flows. */
    private var tab = 0

    /** The scene or flow whose 删除 has been tapped once and now reads 确认删除. */
    private var arming: String? = null
    private var armingFlow: String? = null

    /** Scenes whose rules are unfolded, and flows whose steps are. */
    private val open = mutableSetOf<String>()
    private val openFlow = mutableSetOf<String>()

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
        card.addView(tabBar(), matchWrap())

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        if (tab == 0) buildScenesBody(body) else buildFlowsBody(body)

        val scroll = ScrollView(context)
        scroll.addView(body, matchWrap())
        card.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return card
    }

    private fun buildScenesBody(body: LinearLayout) {
        val active = VoiceConfig.activeScene(context)
        // The one in use first, then most recently used — the order the
        // prompt index uses, so the card and the model see the same list.
        val all = Notebook.scenes(context)
            .sortedWith(compareByDescending<net.kuafuai.andee.config.Scene> { it.name == active }.thenByDescending { it.lastUsedAt })
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
        addPresets(body)
    }

    private fun buildFlowsBody(body: LinearLayout) {
        val all = Notebook.flows(context).sortedByDescending { it.lastRunAt }
        if (all.isEmpty()) {
            body.addView(emptyState())
            return
        }
        for (f in all) body.addView(flowRow(f), matchWrap().apply { bottomMargin = dp(10) })
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

    private fun emptyState(): TextView = TextView(context).apply {
        text = lctx.getString(if (tab == 0) R.string.scenes_empty else R.string.flows_empty)
        textSize = Glass.Type.BODY
        setTextColor(Color.parseColor(Glass.SECONDARY))
        gravity = Gravity.CENTER
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(20), dp(48), dp(20), dp(48))
        layoutParams = matchWrap()
    }

    /**
     * The ready-made scenes this notebook does not hold yet.
     *
     * Offered below the user's own and only while some are left, so a card
     * that has adopted everything is exactly the card it was before. Adopting
     * writes in the interface language at that moment; see [ScenePresets].
     */
    private fun addPresets(body: LinearLayout) {
        val left = ScenePresets.notAdopted(context)
        if (left.isEmpty()) return
        val english = VoiceConfig.uiLanguage(context) == VoiceConfig.LANG_EN
        body.addView(
            TextView(context).apply {
                text = lctx.getString(R.string.scenes_presets_title)
                textSize = Glass.Type.CAPTION
                setTextColor(Color.parseColor(Glass.SECONDARY))
                setPadding(dp(4), dp(18), 0, dp(2))
            },
            matchWrap(),
        )
        body.addView(
            TextView(context).apply {
                text = lctx.getString(R.string.scenes_presets_note)
                textSize = Glass.Type.MICRO
                setTextColor(Color.parseColor(Glass.MUTED))
                setPadding(dp(4), 0, dp(4), dp(10))
            },
            matchWrap(),
        )
        for (p in left) {
            val t = p.text(english)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Glass.panel(context, dp(14))
            }
            row.addView(TextView(context).apply {
                text = t.title
                textSize = Glass.Type.BODY
                setTextColor(Color.parseColor(Glass.TITLE))
            }, matchWrap())
            row.addView(TextView(context).apply {
                text = t.summary
                textSize = Glass.Type.CAPTION
                setTextColor(Color.parseColor(Glass.SECONDARY))
                setPadding(0, dp(4), 0, dp(10))
            }, matchWrap())
            row.addView(
                action(lctx.getString(R.string.scenes_preset_adopt), Glass.ACCENT) {
                    ScenePresets.adopt(context, p, english)
                    rebuild()
                },
            )
            body.addView(row, matchWrap().apply { bottomMargin = dp(10) })
        }
    }

    private fun tabBar(): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(10), dp(20), dp(6))
        }
        bar.addView(tabChip(0, lctx.getString(R.string.scenes_title)))
        bar.addView(View(context), LinearLayout.LayoutParams(dp(10), 1))
        bar.addView(tabChip(1, lctx.getString(R.string.flows_tab)))
        return bar
    }

    private fun tabChip(index: Int, label: String): TextView = TextView(context).apply {
        text = label
        textSize = Glass.Type.BODY
        val on = tab == index
        setTextColor(Color.parseColor(if (on) Glass.ACCENT else Glass.SECONDARY))
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = if (on) Glass.well(context, dp(14)) else Glass.panel(context, dp(14))
        isClickable = true
        Glass.pressable(this)
        setOnClickListener {
            if (tab != index) {
                tab = index
                arming = null
                armingFlow = null
                rebuild()
            }
        }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
    }

    private fun header(): LinearLayout {
        val h = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(14), dp(12))
        }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(context).apply {
            text = lctx.getString(if (tab == 0) R.string.scenes_title else R.string.flows_tab)
            textSize = Glass.Type.HEADLINE
            setTextColor(Color.parseColor(Glass.TITLE))
        })
        titles.addView(TextView(context).apply {
            text = lctx.getString(if (tab == 0) R.string.scenes_subtitle else R.string.flows_subtitle)
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
    private fun row(s: net.kuafuai.andee.config.Scene, active: Boolean): View {
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

    @SuppressLint("SetTextI18n")
    private fun flowRow(f: net.kuafuai.andee.config.Flow): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Glass.panel(context, dp(14))
        }
        row.addView(TextView(context).apply {
            text = f.title
            textSize = Glass.Type.BODY
            setTextColor(Color.parseColor(Glass.TITLE))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, matchWrap())

        if (f.summary.isNotBlank()) {
            row.addView(TextView(context).apply {
                text = f.summary
                textSize = Glass.Type.CAPTION
                setTextColor(Color.parseColor(Glass.SECONDARY))
                setPadding(0, dp(4), 0, 0)
            }, matchWrap())
        }

        row.addView(TextView(context).apply {
            text = flowMeta(f)
            textSize = Glass.Type.MICRO
            setTextColor(Color.parseColor(Glass.MUTED))
            setPadding(0, dp(4), 0, dp(10))
        }, matchWrap())

        val expanded = f.name in openFlow
        if (expanded) {
            val stepsBox = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = Glass.well(context, dp(10))
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            for ((i, step) in f.steps.withIndex()) {
                stepsBox.addView(stepLine(i + 1, step), matchWrap().apply { if (i > 0) topMargin = dp(4) })
            }
            row.addView(stepsBox, matchWrap().apply { bottomMargin = dp(10) })
        }

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(action(lctx.getString(R.string.flows_run), Glass.ACCENT) {
            hide()
            onRunFlow(f)
        })
        actions.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        actions.addView(
            action(
                lctx.getString(if (expanded) R.string.flows_steps_hide else R.string.flows_steps_show),
                Glass.LABEL,
            ) {
                if (!openFlow.remove(f.name)) openFlow.add(f.name)
                rebuild()
            },
        )
        actions.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val armed = armingFlow == f.name
        actions.addView(
            action(
                lctx.getString(if (armed) R.string.artifacts_delete_confirm else R.string.common_delete),
                Glass.DANGER,
            ) {
                if (armingFlow == f.name) {
                    armingFlow = null
                    Notebook.deleteFlow(context, f.name)
                } else {
                    armingFlow = f.name
                }
                rebuild()
            },
        )
        row.addView(actions, matchWrap())
        return row
    }

    private fun flowMeta(f: net.kuafuai.andee.config.Flow): String {
        if (f.runs == 0) return lctx.getString(R.string.flows_meta_never, f.steps.size)
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        return lctx.getString(R.string.flows_meta, f.steps.size, f.okRuns, f.runs, fmt.format(Date(f.lastRunAt)))
    }

    /** One step, one line: what it does, and what it aims at. */
    private fun stepLine(n: Int, s: net.kuafuai.andee.config.FlowStep): TextView {
        val act = when (s.kind) {
            "tap" -> lctx.getString(R.string.flows_step_tap)
            "long_press" -> lctx.getString(R.string.flows_step_long_press)
            "swipe" -> lctx.getString(R.string.flows_step_swipe)
            "type" -> lctx.getString(R.string.flows_step_type, s.note.take(16))
            "human" -> lctx.getString(R.string.flows_step_human, s.note.take(20))
            "ai" -> lctx.getString(R.string.flows_step_ai, s.note.take(20))
            "wait" -> lctx.getString(R.string.flows_step_wait, s.durationMs / 1000)
            "launch" -> lctx.getString(R.string.flows_step_launch)
            else -> s.kind
        }
        val aim = when {
            s.kind == "launch" -> s.note.ifEmpty { appLabel(s.pkg) }
            s.kind in setOf("human", "ai", "wait") -> s.pkg
            s.text.isNotEmpty() -> s.text
            !s.viewId.isNullOrEmpty() -> "#" + s.viewId!!.substringAfterLast('/')
            else -> "(${s.relX},${s.relY})"
        }
        return TextView(context).apply {
            text = lctx.getString(R.string.flows_step_anchor, "$n. $act", aim)
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(if (s.kind in setOf("human", "ai")) Glass.ACCENT else Glass.LABEL))
        }
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

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        const val GUTTER_DP = 20
    }
}
