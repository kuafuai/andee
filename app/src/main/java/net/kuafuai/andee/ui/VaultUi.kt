package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.config.Vault
import net.kuafuai.andee.i18n.AppLocale

/**
 * The user's credential vault: one entry per account, so the ball can log in
 * as them.
 *
 * Its own overlay rather than more rows in [SettingsUi] — the list grows, and
 * the wake-word row already set the precedent of putting a real screen behind
 * a single button ([WakeEnrollUi]).
 *
 * The window is **focusable** (flags 0, not `FLAG_NOT_FOCUSABLE`) with
 * `SOFT_INPUT_ADJUST_RESIZE`, for the same reason [SettingsUi] is: without
 * that pair the EditTexts here cannot receive the IME at all, and the
 * keyboard covers the field being typed into. [hideFromAccessibility] is not
 * optional either — we are an accessibility service, and an un-hidden form
 * shows up in our own UI dumps with the user's password in it.
 */
class VaultUi(
    private val context: Context,
    private val onDismiss: () -> Unit = {},
) {

    private val wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: View? = null

    /** Null = the list; non-null = editing this entry (blank id = new). */
    private var editing: Vault.Entry? = null

    /** Whether the compositor agreed to blur. See [Glass.frost]. */
    private var frosted = false

    /**
     * Strings in the user's language, refreshed at the top of [build].
     *
     * Refreshed there and not in [show] on purpose: [rebuild] goes straight to
     * [build] without passing through [show], so rewrapping in the one function
     * that consumes it means no future caller has to remember to do it.
     *
     * Views are still built on [context]; only text comes from here. See
     * [AppLocale] for why those two are deliberately different Contexts.
     */
    private var lctx: Context = context

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
            Glass.fadeIn(v)
        }
    }

    private fun cardWidth(): Int =
        minOf(dp(500), (wm.currentWindowMetrics.bounds.width() * 0.94f).toInt())

    private fun cardHeight(): Int =
        minOf(dp(600), (wm.currentWindowMetrics.bounds.height() * 0.86f).toInt())

    // ---- build ----

    @SuppressLint("SetTextI18n")
    private fun build(): View {
        lctx = AppLocale.wrap(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.card(context, dp(28), frosted)
            clipToOutline = true
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(14), dp(12))
        }
        header.addView(
            TextView(context).apply {
                text = if (editing == null) {
                    lctx.getString(R.string.vault_title)
                } else {
                    lctx.getString(R.string.vault_edit_title)
                }
                textSize = Glass.Type.HEADLINE
                setTextColor(Color.parseColor(Glass.TITLE))
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            TextView(context).apply {
                text = "✕"
                textSize = Glass.Type.CAPTION
                setTextColor(Color.parseColor(Glass.SECONDARY))
                gravity = Gravity.CENTER
                background = Glass.panel(context, dp(16))
                isClickable = true
                Glass.pressable(this)
                setOnClickListener {
                    if (editing == null) hide() else { editing = null; rebuild() }
                }
            },
            LinearLayout.LayoutParams(dp(32), dp(32)),
        )
        card.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        divider(card)

        val scroll = ScrollView(context)
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val e = editing
        val footer = if (e == null) buildList(body) else buildForm(body, e)
        scroll.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        divider(card)
        card.addView(
            footer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return card
    }

    private fun footerRow(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        setPadding(dp(16), dp(12), dp(16), dp(16))
    }

    @SuppressLint("SetTextI18n")
    private fun buildList(body: LinearLayout): LinearLayout {
        body.addView(TextView(context).apply {
            text = lctx.getString(R.string.vault_intro)
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(Glass.SECONDARY))
            setPadding(0, 0, 0, dp(10))
        })

        val all = Vault.entries(context)
        if (all.isEmpty()) {
            body.addView(TextView(context).apply {
                text = lctx.getString(R.string.vault_empty)
                textSize = Glass.Type.CAPTION
                setTextColor(Color.parseColor(Glass.MUTED))
                setPadding(0, dp(12), 0, dp(12))
            })
        }
        for (entry in all) {
            body.addView(
                entryRow(entry),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(8) },
            )
        }

        return footerRow().apply {
            addView(button(lctx.getString(R.string.vault_add_account), filled = true) {
                editing = Vault.Entry(id = "", label = "")
                rebuild()
            })
        }
    }

    @SuppressLint("SetTextI18n")
    private fun entryRow(entry: Vault.Entry): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = Glass.panel(context, dp(14))
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { editing = entry; rebuild() }
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
            text = entry.label.ifBlank { entry.id }
            textSize = Glass.Type.BODY
            setTextColor(Color.WHITE)
        })
        // [Vault.Entry.masked] rather than masking here: `card_last4` is four
        // characters and [Vault.mask] would render it `****`, throwing away the
        // one thing that tells two cards apart. That exemption lives in one
        // place so this row and the brain's own list cannot disagree about it.
        val summary = buildList {
            entry.masked().forEach { (k, v) -> add("${fieldLabel(k)} $v") }
            if (entry.password.isNotBlank()) add(lctx.getString(R.string.vault_password_saved))
        }.joinToString(" · ")
        column.addView(TextView(context).apply {
            text = summary.ifBlank { lctx.getString(R.string.vault_summary_empty) }
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(Glass.SECONDARY))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            text = lctx.getString(R.string.vault_delete)
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(Glass.DANGER))
            setPadding(dp(12), dp(6), dp(4), dp(6))
            isClickable = true
            setOnClickListener { Vault.remove(context, entry.id); rebuild() }
        })
        return row
    }

    @SuppressLint("SetTextI18n")
    private fun buildForm(body: LinearLayout, entry: Vault.Entry): LinearLayout {
        val label = field(
            body, lctx.getString(R.string.vault_field_name), entry.label,
            hint = lctx.getString(R.string.vault_field_name_hint),
        )
        val phone = field(body, lctx.getString(R.string.vault_field_phone), entry.phone, numeric = true)
        val email = field(body, lctx.getString(R.string.vault_field_email), entry.email)
        val username = field(body, lctx.getString(R.string.vault_field_username), entry.username)
        val password = field(body, lctx.getString(R.string.vault_field_password), entry.password, password = true)
        body.addView(TextView(context).apply {
            text = lctx.getString(R.string.vault_password_note)
            textSize = Glass.Type.MICRO
            setTextColor(Color.parseColor(Glass.MUTED))
            setPadding(0, dp(4), 0, 0)
        })
        val note = field(
            body, lctx.getString(R.string.vault_field_note), entry.note,
            hint = lctx.getString(R.string.vault_field_note_hint),
        )

        // The card group, below the note rather than beside the password: an
        // entry usually has one or the other, and a form that opens with four
        // blank card rows reads as four things the user forgot to fill in.
        body.addView(TextView(context).apply {
            text = lctx.getString(R.string.vault_section_card)
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(Glass.LABEL))
            setPadding(0, dp(18), 0, dp(2))
        })
        body.addView(TextView(context).apply {
            text = lctx.getString(R.string.vault_card_note)
            textSize = Glass.Type.MICRO
            setTextColor(Color.parseColor(Glass.MUTED))
        })
        val cardHolder = field(
            body, lctx.getString(R.string.vault_field_card_holder), entry.cardHolder,
            hint = lctx.getString(R.string.vault_field_card_holder_hint),
        )
        val cardNumber = field(
            body, lctx.getString(R.string.vault_field_card_number), entry.cardNumber,
            numeric = true, password = true,
        )
        val cardExpiry = field(
            body, lctx.getString(R.string.vault_field_card_expiry), entry.cardExpiry,
            hint = lctx.getString(R.string.vault_field_card_expiry_hint), date = true,
        )
        val cardCvv = field(
            body, lctx.getString(R.string.vault_field_card_cvv), entry.cardCvv,
            numeric = true, password = true,
        )

        return footerRow().apply {
            addView(button(lctx.getString(R.string.common_cancel), filled = false) { editing = null; rebuild() })
            addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
            addView(button(lctx.getString(R.string.common_save), filled = true) {
                val name = label.text.toString().trim()
                if (name.isEmpty()) {
                    label.error = lctx.getString(R.string.vault_error_name_required)
                    return@button
                }
                Vault.put(
                    context,
                    Vault.Entry(
                        id = entry.id.ifBlank { Vault.newId() },
                        label = name,
                        phone = phone.text.toString().trim(),
                        email = email.text.toString().trim(),
                        username = username.text.toString().trim(),
                        note = note.text.toString().trim(),
                        password = password.text.toString(),
                        // Spaces and dashes are how a card is printed and how
                        // a person types it; no checkout field wants them, and
                        // the brain never sees this to clean it up.
                        cardNumber = cardNumber.text.toString().filter(Char::isDigit),
                        cardExpiry = cardExpiry.text.toString().trim(),
                        cardCvv = cardCvv.text.toString().filter(Char::isDigit),
                        cardHolder = cardHolder.text.toString().trim(),
                    ),
                )
                editing = null
                rebuild()
            })
        }
    }

    private fun fieldLabel(key: String): String = when (key) {
        "phone" -> lctx.getString(R.string.vault_field_phone)
        "email" -> lctx.getString(R.string.vault_field_email)
        "username" -> lctx.getString(R.string.vault_field_username)
        "note" -> lctx.getString(R.string.vault_field_note)
        "card_holder" -> lctx.getString(R.string.vault_field_card_holder)
        "card_last4" -> lctx.getString(R.string.vault_label_card_last4)
        else -> key
    }

    // ---- widgets (same palette as SettingsUi) ----

    @SuppressLint("SetTextI18n")
    private fun field(
        parent: LinearLayout,
        label: String,
        value: String,
        hint: String? = null,
        numeric: Boolean = false,
        password: Boolean = false,
        /**
         * A date keypad rather than [numeric]'s phone one, for `MM/YY`.
         *
         * Measured on this tablet: `TYPE_CLASS_PHONE` draws a dialpad whose
         * symbol keys are `+ . * #` — **no slash** — so a hint reading `MM/YY`
         * asks for a character the keyboard it just raised cannot produce.
         * `TYPE_CLASS_DATETIME` puts the separators on it.
         */
        date: Boolean = false,
    ): EditText {
        parent.addView(TextView(context).apply {
            text = label
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(Glass.LABEL))
            setPadding(0, dp(8), 0, dp(4))
        })
        val et = EditText(context).apply {
            background = Glass.well(context, dp(12))
            setPadding(dp(12), dp(11), dp(12), dp(11))
            setTextColor(Color.parseColor(Glass.TITLE))
            setHintTextColor(Color.parseColor(Glass.MUTED))
            textSize = Glass.Type.BODY
            if (hint != null) setHint(hint)
            // Order matters: setSingleLine installs its own transformation and
            // would strip the password dots right back off again.
            isSingleLine = true
            inputType = when {
                password -> secretType(numeric)
                date -> InputType.TYPE_CLASS_DATETIME or
                    InputType.TYPE_DATETIME_VARIATION_DATE
                numeric -> InputType.TYPE_CLASS_PHONE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (password) setMasked(true)
            setText(value)
        }
        if (password) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(
                et,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            var shown = false
            row.addView(TextView(context).apply {
                text = lctx.getString(R.string.common_show)
                textSize = Glass.Type.CAPTION
                setTextColor(Color.parseColor(Glass.ACCENT))
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(4), dp(10))
                isClickable = true
                setOnClickListener {
                    shown = !shown
                    et.setMasked(!shown)
                    text = if (shown) {
                        lctx.getString(R.string.common_hide)
                    } else {
                        lctx.getString(R.string.common_show)
                    }
                    et.setSelection(et.text.length)
                }
            })
            parent.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        } else {
            parent.addView(
                et,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        return et
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = Glass.Type.BODY
            setTextColor(Color.parseColor(if (filled) Glass.TITLE else Glass.LABEL))
            gravity = Gravity.CENTER
            background =
                if (filled) Glass.tinted(context, dp(20)) else Glass.panel(context, dp(20))
            setPadding(dp(22), dp(11), dp(22), dp(11))
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { onClick() }
        }

    private fun divider(parent: LinearLayout) {
        parent.addView(
            View(context).apply { setBackgroundColor(Color.parseColor(Glass.PANEL_EDGE)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)),
        )
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
