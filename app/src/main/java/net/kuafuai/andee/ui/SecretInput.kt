package net.kuafuai.andee.ui

import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.EditText

/**
 * The dots on the secret fields of our own cards — drawn by us, and never by
 * telling the IME that the field is a password.
 *
 * ### Why a password `inputType` cannot be used here
 *
 * Measured on Honor MagicOS, and the symptom is that the field simply cannot
 * be typed into: tap the DeepSeek Key row in [SettingsUi], or a password or
 * card number in [VaultUi], and the keyboard appears for one frame and
 * retracts. It is not a focus bug — `dumpsys input_method` shows `startInput`
 * arriving with the right `inputType` and the input connection bound — and it
 * is not ours to fix from inside the card.
 *
 * The chain is: a password `inputType` makes the ROM hand the field to its own
 * secure keyboard (`com.hihonor.secime`) instead of the user's; that window
 * carries `PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS`, which hides **every**
 * `TYPE_APPLICATION_OVERLAY` on screen for as long as it is up. Our cards are
 * overlays, so the window it hides includes the one holding the focused field.
 * The IME loses its target the instant it arrives and is dismissed again.
 *
 * That flag is an anti-phishing measure and it is working exactly as intended
 * — a password prompt should not be typed into with unknown windows floating
 * over it. There is nothing to negotiate with: the flag belongs to their
 * window, and the only move available to us is to not ask for the secure
 * keyboard in the first place.
 *
 * ### What we do instead
 *
 * The `inputType` stays ordinary and the masking becomes a
 * [PasswordTransformationMethod], which is a *display* concern and invisible
 * to the IME. [TYPE_TEXT_FLAG_NO_SUGGESTIONS][InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS]
 * is what replaces the part of the password variation that was worth having:
 * it is the standing request that the keyboard neither predict from nor learn
 * what is typed here. Autofill is not in play on these cards, so nothing else
 * was riding on the variation.
 *
 * One thing improves on the way past. The 显示/隐藏 toggle used to swap the
 * `inputType` between a hidden and a shown variant, which on the numeric fields
 * meant swapping the keyboard under the user's thumb mid-entry. Only the
 * transformation moves now, so the keyboard the user started typing on is the
 * one they finish on.
 */
internal fun secretType(numeric: Boolean): Int = if (numeric) {
    InputType.TYPE_CLASS_NUMBER
} else {
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
}

/**
 * Show or hide the dots.
 *
 * Call it **after** `isSingleLine`, which installs a transformation of its own
 * and would otherwise strip this one straight back off. Losing that one costs
 * nothing: neither type from [secretType] carries `TYPE_TEXT_FLAG_MULTI_LINE`,
 * so there is no newline for it to fold away.
 */
internal fun EditText.setMasked(masked: Boolean) {
    transformationMethod =
        if (masked) PasswordTransformationMethod.getInstance() else null
}
