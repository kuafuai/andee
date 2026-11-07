package net.kuafuai.andee.ui
import net.kuafuai.andee.compat.WindowMetricsCompat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.config.Ages
import net.kuafuai.andee.config.Categories
import net.kuafuai.andee.config.Gear
import net.kuafuai.andee.config.Notebook
import net.kuafuai.andee.device.AndroidGear
import net.kuafuai.andee.i18n.AppLocale

/**
 * 装备: what is installed and what Andee has learned each thing is for — the user's own view of exactly what
 * the model is told, and the place to correct it.
 *
 * The device reads the facts (installed, last used, how often); the brain writes the meaning after really
 * using something. Here the person can read both, rewrite the meaning (and from then on the brain leaves it
 * alone), tell it to stop mentioning one app, make it forget what it learned, rescan, and decide which
 * *kinds* of app it is told about. Everything kept from the model is still listed here, flagged: the person
 * is entitled to see what was kept out and to change it.
 *
 * Same window shape as [VaultUi]: focusable (it has text fields), hidden from accessibility, counted by
 * [OwnCard].
 */
class GearUi(
    private val context: Context,
    private val shelf: AndroidGear,
    private val onDismiss: () -> Unit = {},
) {

    private val wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var frosted = false
    private var lctx: Context = context

    private var query = ""
    private var editing: String? = null
    private var arming: String? = null
    private var scanning = false

    private val gear: Gear get() = shelf.gear

    fun show() {
        if (root != null) return
        val p = WindowManager.LayoutParams(
            cardWidth(), cardHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
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

    fun hide() {
        val v = root ?: return
        root = null
        Glass.exit(v) {
            runCatching { wm.removeView(v) }
            OwnCard.hidden()
            onDismiss()
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

    private fun cardWidth(): Int =
        minOf(dp(560), (WindowMetricsCompat.bounds(wm).width() * 0.94f).toInt())

    private fun cardHeight(): Int =
        minOf(dp(720), (WindowMetricsCompat.bounds(wm).height() * 0.88f).toInt())

    // ---- build ----

    @SuppressLint("SetTextI18n")
    private fun build(): View {
        lctx = AppLocale.wrap(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.card(context, dp(28), frosted)
            clipToOutline = true
        }
        card.addView(header())
        card.addView(
            View(context).apply { setBackgroundColor(Color.parseColor(Glass.PANEL_EDGE)) },
            LinearLayout.LayoutParams(MATCH, dp(1)),
        )
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(16))
        }
        kinds(body)
        list(body)
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
            text = lctx.getString(R.string.gear_title)
            textSize = Glass.Type.HEADLINE
            setTextColor(Color.parseColor(Glass.TITLE))
        })
        titles.addView(TextView(context).apply {
            text = lctx.getString(R.string.gear_subtitle)
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

    /** Which kinds of app the model is told about. */
    private fun kinds(body: LinearLayout) {
        body.addView(sectionTitle(lctx.getString(R.string.gear_kinds_title)))
        body.addView(note(lctx.getString(R.string.gear_kinds_note)))
        val labels = listOf(
            Categories.ADULT to R.string.gear_kind_adult, Categories.FINANCE to R.string.gear_kind_finance,
            Categories.HEALTH to R.string.gear_kind_health, Categories.DATING to R.string.gear_kind_dating,
            Categories.OTHER to R.string.gear_kind_other,
        )
        for ((cat, res) in labels) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(TextView(context).apply {
                text = lctx.getString(res)
                textSize = Glass.Type.BODY
                setTextColor(Color.parseColor(Glass.LABEL))
            }, LinearLayout.LayoutParams(dp(96), WRAP))
            val on = gear.shown(cat)
            row.addView(pill(lctx.getString(R.string.gear_hide), !on) { gear.setShown(cat, false); rebuild() })
            row.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
            row.addView(pill(lctx.getString(R.string.gear_tell), on) { gear.setShown(cat, true); rebuild() })
            body.addView(row, matchWrap())
        }
    }

    private fun list(body: LinearLayout) {
        val all = gear.forCard()
        val rows = all.filter { (i, _) ->
            query.isBlank() || i.label.contains(query, true) || i.id.contains(query, true) ||
                i.does.contains(query, true) || i.canDo.contains(query, true)
        }
        body.addView(sectionTitle(lctx.getString(R.string.gear_installed, all.size)))

        val scanned = gear.scannedAt()
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(context).apply {
            text = if (scanned > 0) {
                lctx.getString(R.string.gear_scanned_ago, Ages.ago(System.currentTimeMillis() - scanned, zh()))
            } else lctx.getString(R.string.gear_never_scanned)
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(Glass.SECONDARY))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(
            action(lctx.getString(if (scanning) R.string.gear_scanning else R.string.gear_rescan), Glass.ACCENT) {
                if (scanning) return@action
                scanning = true; rebuild()
                Thread {
                    shelf.scanNow()
                    scanning = false
                    android.os.Handler(android.os.Looper.getMainLooper()).post { rebuild() }
                }.apply { isDaemon = true; name = "gear-rescan" }.start()
            },
        )
        body.addView(top, matchWrap())
        body.addView(note(lctx.getString(R.string.gear_scan_note)))

        // Without usage access the system will not say how much each app is used; say so, with the way to fix it.
        if (!shelf.hasUsageAccess()) {
            body.addView(note(lctx.getString(R.string.gear_usage_access_note)))
            body.addView(
                action(lctx.getString(R.string.gear_usage_access_open), Glass.ACCENT) {
                    // A Settings page the user flips; the app can only point at it, never grant it.
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) },
            )
        }

        body.addView(
            editText(query, lctx.getString(R.string.gear_search)) { query = it; },
            matchWrap().apply { topMargin = dp(10) },
        )
        body.addView(
            action(lctx.getString(R.string.gear_search), Glass.LABEL) { rebuild() },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6); bottomMargin = dp(8) },
        )

        if (rows.isEmpty()) {
            body.addView(TextView(context).apply {
                text = lctx.getString(R.string.gear_none_match)
                textSize = Glass.Type.BODY
                setTextColor(Color.parseColor(Glass.SECONDARY))
                setPadding(dp(4), dp(16), 0, dp(16))
            })
            return
        }
        for ((item, hidden) in rows) body.addView(row(item, hidden), matchWrap().apply { bottomMargin = dp(10) })
    }

    private fun row(i: Gear.Item, hidden: Boolean): View {
        val now = System.currentTimeMillis()
        val z = zh()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = Glass.panel(context, dp(14))
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(context).apply {
            text = i.label
            textSize = Glass.Type.BODY
            setTextColor(Color.parseColor(if (hidden) Glass.SECONDARY else Glass.TITLE))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (hidden) head.addView(TextView(context).apply {
            text = lctx.getString(R.string.gear_hidden_from_it)
            textSize = Glass.Type.MICRO
            setTextColor(Color.parseColor(Glass.MUTED))
        })
        row.addView(head, matchWrap())

        val meta = buildList {
            if (i.lastUsedAt > 0) add(lctx.getString(R.string.gear_last_used, Ages.ago(now - i.lastUsedAt, z)))
            if (i.uses > 0) add(lctx.getString(R.string.gear_uses, i.uses))
            if (i.signedIn == "yes") add(lctx.getString(R.string.gear_signed_in) + " (" + Ages.ago(now - i.signedInAt, z) + ")")
            if (i.signedIn == "no") add(lctx.getString(R.string.gear_signed_out) + " (" + Ages.ago(now - i.signedInAt, z) + ")")
            if (i.limit.isNotBlank()) add(i.limit)
        }.joinToString(" · ")
        if (meta.isNotEmpty()) row.addView(TextView(context).apply {
            text = meta
            textSize = Glass.Type.MICRO
            setTextColor(Color.parseColor(Glass.MUTED))
            setPadding(0, dp(2), 0, 0)
        })

        val learned = i.does.isNotBlank() || i.canDo.isNotBlank()
        row.addView(TextView(context).apply {
            text = if (learned) {
                listOfNotNull(i.does.takeIf { it.isNotBlank() }, i.canDo.takeIf { it.isNotBlank() }?.let { lctx.getString(R.string.gear_can_do, it) })
                    .joinToString("\n")
            } else lctx.getString(R.string.gear_unlearned)
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(if (learned) Glass.LABEL else Glass.MUTED))
            setPadding(0, dp(6), 0, 0)
        })
        if (learned && i.byUser) row.addView(TextView(context).apply {
            text = lctx.getString(R.string.gear_by_user)
            textSize = Glass.Type.MICRO
            setTextColor(Color.parseColor(Glass.MUTED))
        })

        if (editing == i.id) {
            var does = i.does
            var canDo = i.canDo
            row.addView(label(lctx.getString(R.string.gear_field_does)))
            row.addView(editText(does, "") { does = it })
            row.addView(label(lctx.getString(R.string.gear_field_can_do)))
            row.addView(editText(canDo, "") { canDo = it })
            val bar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            bar.addView(action(lctx.getString(R.string.common_cancel), Glass.LABEL) { editing = null; rebuild() })
            bar.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
            bar.addView(action(lctx.getString(R.string.common_save), Glass.ACCENT) {
                gear.edit(i.id, does, canDo); editing = null; rebuild()
            })
            row.addView(bar, matchWrap().apply { topMargin = dp(10) })
        }

        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(action(lctx.getString(R.string.gear_edit), Glass.ACCENT) {
            editing = if (editing == i.id) null else i.id; rebuild()
        })
        actions.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        actions.addView(action(lctx.getString(if (i.ignored) R.string.gear_unignore else R.string.gear_ignore), Glass.LABEL) {
            gear.setIgnored(i.id, !i.ignored); rebuild()
        })
        if (i.does.isNotBlank() || i.canDo.isNotBlank() || i.signedIn.isNotBlank() || i.limit.isNotBlank()) {
            actions.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            val armed = arming == i.id
            actions.addView(action(lctx.getString(if (armed) R.string.gear_forget_confirm else R.string.gear_forget), Glass.DANGER) {
                if (armed) { gear.forget(i.id); arming = null } else arming = i.id
                rebuild()
            })
        }
        row.addView(actions, matchWrap().apply { topMargin = dp(10) })
        return row
    }

    // ---- small pieces ----

    private fun zh(): Boolean = lctx.resources.configuration.locales[0].language == "zh"

    private fun sectionTitle(text: String) = TextView(context).apply {
        this.text = text
        textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(Glass.SECONDARY))
        setPadding(dp(2), dp(14), 0, dp(4))
    }

    private fun note(text: String) = TextView(context).apply {
        this.text = text
        textSize = Glass.Type.MICRO
        setTextColor(Color.parseColor(Glass.MUTED))
        setPadding(dp(2), dp(2), dp(2), dp(4))
    }

    private fun label(text: String) = TextView(context).apply {
        this.text = text
        textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(Glass.LABEL))
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun editText(value: String, hint: String, onChange: (String) -> Unit) = EditText(context).apply {
        background = Glass.well(context, dp(12))
        setPadding(dp(12), dp(11), dp(12), dp(11))
        setTextColor(Color.parseColor(Glass.TITLE))
        setHintTextColor(Color.parseColor(Glass.MUTED))
        textSize = Glass.Type.BODY
        if (hint.isNotEmpty()) setHint(hint)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT
        setText(value)
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { onChange(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun pill(label: String, on: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(if (on) Glass.ON_TINT else Glass.LABEL))
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = if (on) Glass.tinted(context, dp(14)) else Glass.well(context, dp(14))
        isClickable = true
        Glass.pressable(this)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
    }

    private fun action(label: String, color: String, onClick: () -> Unit): TextView = TextView(context).apply {
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
    }
}
