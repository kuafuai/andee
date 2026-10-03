package net.kuafuai.andee.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import java.lang.ref.WeakReference

/**
 * The field a person types into — an **Activity**, not an overlay window.
 *
 * That is not a style choice, it is the only thing on this device that works.
 * Measured on the tablet this was written for (Xiaomi Pad 5, HyperOS):
 *
 *  * a `TYPE_APPLICATION_OVERLAY` window with an `EditText` **never raises the
 *    soft keyboard** — not from `showSoftInput`, not from
 *    `WindowInsetsController.show(ime())`, and not even from a real finger tap
 *    (`mShowRequested` stayed `false` throughout);
 *  * the same input inside an Activity raises it immediately.
 *
 * So a floating input panel is not available here, however much nicer it would
 * look. This is the second overlay-only assumption this feature has had to give
 * up; the first was that the keyboard could be moved on demand
 * (see [ImeSwitch]).
 *
 * Three things it inherits from the overlays it replaced, all learned the hard
 * way elsewhere in this app:
 *
 *  * the card has to get out of the way before it opens — it is a fullscreen
 *    overlay, so it sits above this Activity and eats every touch aimed at the
 *    strip. That is `FloatingWindowUi.setTypingTop`, and this class reports
 *    itself gone so the card can come back exactly as it was.
 *  * `hideFromAccessibility()` — a focused overlay belongs to us, and
 *    `ScreenController`'s node dump would otherwise hand the brain *this* field
 *    as "the app the user is on", so `type_text`'s `findFocus` lands on the
 *    sentence the person is composing. See `OverlayA11y`.
 *  * `OwnCard` — hiding the window from accessibility leaves a dump that looks
 *    like a dead accessibility connection, which `reviveNote` reports to the
 *    user as "toggle Andee off and on". Registering is what keeps that true.
 *  * Nothing here touches the input method. The device's IME is the user's own,
 *    so this types on it like any other app's field would; the keyboard is only
 *    moved inside `ScreenController.typeViaAdbKeyboard`, for the length of one
 *    tool call.
 *
 * Wired like [ChatHistory] and [CardUi]: static hooks the service sets once.
 */
class TextInputActivity : Activity() {

    private lateinit var field: EditText
    private var sent = false

    /** Strings in the user's language; views are still built on `this`. */
    private lateinit var lctx: Context

    /**
     * Whether the soft keyboard has been up at least once.
     *
     * The inset is 0 before the keyboard appears and 0 again once it is gone, so
     * "the keyboard went away" can only be told from "it has not arrived yet" by
     * remembering that it did arrive. Getting this wrong closes the field on the
     * first layout pass, which is the one moment the user is certain to still be
     * typing.
     */
    private var imeShown = false

    /** Pending "the keyboard is really gone, close up" check. See [scheduleCloseWhenGone]. */
    private var closeWhenGone: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lctx = AppLocale.wrap(this)

        field = EditText(this).apply {
            hint = lctx.getString(R.string.input_hint)
            textSize = Glass.Type.BODY
            setTextColor(Color.parseColor(Glass.TITLE))
            // SECONDARY, not MUTED. The hint is the only thing in here until
            // somebody types, so it is the one string that has to survive being
            // read at a glance: #5F6775 on the strip's near-black is ~3.6:1,
            // which is the faint grey this whole change was about.
            setHintTextColor(Color.parseColor(Glass.SECONDARY))
            // Recessed, so the strip reads as "type here" rather than as a
            // toolbar with two buttons and some floating text.
            background = Glass.well(this@TextInputActivity, dp(14))
            // Single line with the enter key turned into send. Deliberately not
            // a multi-line box: with one, Enter inserts a newline and the only
            // way to send is the button — a coin flip the user has to learn.
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send()
                    true
                } else {
                    false
                }
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The *card* fill, not `Glass.panel`.
            //
            // `Glass` is a two-layer stack: an almost-opaque black base, then
            // white at low alpha on top of it. `panel` is the second layer —
            // 6% white — and it is only ever correct *on top of a card*.
            // Standing alone over somebody else's app it draws nothing at all,
            // and the field's own background came out as whatever was behind
            // it: measured over a white page, mean RGB 255,255,255 with
            // `Glass.TITLE` white text on it. Contrast 0. That is the "输入框
            // 是透明的" this replaces — not a matter of taste, arithmetic.
            //
            // So this strip gets the base. It has to stay readable over *any*
            // backdrop, a white document and a night-mode terminal alike, and
            // there is no blur to lean on: `Glass.frost` blurs behind a window,
            // and this window is the whole screen (only this strip is painted),
            // so asking for it would defocus everything the user was looking
            // at. A dark bar over the bottom edge is the honest answer.
            background = Glass.card(this@TextInputActivity, dp(20), frosted = false)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        row.addView(field, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(glyph(lctx.getString(R.string.input_send), Glass.ACCENT) { send() })
        row.addView(glyph("✕", Glass.LABEL) { finish() })

        // The strip sits on **the card's own backdrop**, not on the user's app.
        //
        // The card above ends at this strip's top edge (it has to: overlays sort
        // above the keyboard, so a card that reached lower would hide both this
        // strip and the keys). That leaves the band this window owns, and with a
        // translucent window the band showed whatever app was underneath — a
        // bright seam of somebody's home screen between a dark card and a dark
        // strip, which reads as the card having gone see-through. Measured on
        // the tablet: mean RGB 60–85 in that band against 8–35 in the card
        // directly above it.
        //
        // Same [Backdrop] object, same scrim, same `CENTER_CROP`, so the two
        // windows line up by construction rather than by eye. The fill behind
        // the image is the same near-opaque slate the strip is made of, so a
        // field opened before the backdrop has finished warming still gets a
        // continuous surface rather than the desktop.
        val backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        Backdrop.warm(this) { bmp ->
            backdrop.setImageBitmap(bmp)
            backdrop.foreground = ColorDrawable(Backdrop.scrim())
        }

        // One row, anchored at the bottom. See the backdrop above for why the
        // window is no longer see-through.
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(4), dp(12), dp(12))
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor(Glass.CARD_SOLID))
            addView(
                backdrop,
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
            )
            addView(
                column,
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
            )
        }
        setContentView(root)

        // The keyboard is most of a phone screen, and it arrives **over** this
        // window. A translucent window is never resized for the IME — measured:
        // with `adjustResize` set the row still sat at the window's bottom edge
        // with 搜狗's keys drawn on top of it — so the inset has to be applied by
        // hand. Which needs the decor to stop fitting system windows first: with
        // it fitting, the IME inset is consumed before any listener sees it
        // (measured: it arrived as 0).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // On the column, not the root: the backdrop is full bleed and must
            // slide under the status bar and the keys, while the strip itself
            // steps around them.
            column.setPadding(dp(12), dp(4) + bars.top, dp(12), dp(12) + ime)
            if (ime > 0) {
                imeShown = true
                cancelCloseWhenGone()
            } else if (imeShown) {
                // The keyboard is on its way out, and so is this field:收起键盘
                // means the user is done typing, and a strip left behind on an
                // otherwise empty screen is a thing to dismiss twice.
                //
                // The band is deliberately *not* re-reported here (see the post
                // below): with the keys gone the strip would jump to the bottom
                // of the screen, the card would grow to meet it, and the whole
                // thing would close a moment later — three moves for one
                // gesture. Left alone, the card simply grows back once.
                scheduleCloseWhenGone()
                return@setOnApplyWindowInsetsListener insets
            }
            // Tell the card where our top edge is, in screen pixels, so it can
            // end there instead of covering us. Posted, because the row has no
            // position until this pass has laid it out — and sent again on every
            // inset change, which is what keeps the two in step as the keyboard
            // slides up and as 搜狗's toolbar row comes and goes.
            root.post {
                if (isFinishing) return@post
                val at = IntArray(2)
                row.getLocationOnScreen(at)
                onTop?.invoke(at[1])
            }
            insets
        }
        window.decorView.hideFromAccessibility()
        OwnCard.shown()
        live = WeakReference(this)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // Here rather than in onCreate: the window has focus by now, and an IME
        // request from a window that does not is dropped without a word.
        field.requestFocus()
        showKeyboard()
        field.postDelayed({ if (!isFinishing) showKeyboard() }, IME_RETRY_MS)
    }

    override fun onPause() {
        // Paired with [resumed] in the companion, which is what makes
        // `isShowing` mean "on screen" rather than "alive". Measured: HOME (or
        // anything else that backgrounds this without finishing it) leaves the
        // instance — and its window — in place, and a service that believed the
        // field was still showing made the next ⌨ tap a *close*: the user taps
        // the button, and nothing at all happens.
        resumed = false
        super.onPause()
    }

    override fun onDestroy() {
        cancelCloseWhenGone()
        if (live?.get() === this) live = null
        resumed = false
        OwnCard.hidden()
        // The card was taken off the screen for the length of this field (see
        // [FloatingWindowUi.setTypingTop]) and is waiting to be put back.
        // This is the one place every way out of the field passes through —
        // `✕`, BACK, send, or the service dismissing it — and hanging the
        // restore off the send button instead would strand the device with an
        // invisible, untouchable card: no ball, and no way to ask for one.
        onClosed?.invoke()
        super.onDestroy()
    }

    /**
     * Hand the text to the service and get out of the way.
     *
     * Clears the field before handing it over, so a send that turns out to be a
     * no-op (nothing listening) is visible as an empty box rather than as text
     * that silently went nowhere.
     */
    private fun send() {
        val text = field.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        field.setText("")
        sent = true
        onSubmit?.invoke(text)
        finish()
    }

    /**
     * Close up once the keyboard is really gone, rather than on the first frame
     * it reports 0.
     *
     * An IME does not only go away when the user is finished: it also retracts
     * for a moment when the input method is swapped out underneath us, which is
     * exactly what happens when the brain types into another app while this
     * field is open ([ImeSwitch] moves the device to ADBKeyboard for one tool
     * call). Closing on that would be a field that vanishes for no reason the
     * user can see — the failure this whole class has been trying not to be. A
     * quarter of a second is longer than the animation and shorter than a
     * person noticing the delay, and anything that brings the keyboard back
     * cancels it.
     */
    private fun scheduleCloseWhenGone() {
        if (closeWhenGone != null || isFinishing) return
        val r = Runnable {
            closeWhenGone = null
            if (!isFinishing) finish()
        }
        closeWhenGone = r
        field.postDelayed(r, IME_GONE_MS)
    }

    private fun cancelCloseWhenGone() {
        val r = closeWhenGone ?: return
        closeWhenGone = null
        field.removeCallbacks(r)
    }

    private fun showKeyboard() {
        runCatching { field.windowInsetsController?.show(android.view.WindowInsets.Type.ime()) }
        runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(field, 0)
        }
    }

    private fun glyph(label: String, color: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = Glass.Type.BODY
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            isFocusable = true
            Glass.pressable(this)
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val IME_RETRY_MS = 250L

        /**
         * How long the keyboard has to stay gone before the field follows it.
         * Long enough to ride out an input-method swap, short enough to read as
         * "the field closed with the keyboard".
         */
        private const val IME_GONE_MS = 250L
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val MATCH_PARENT = LinearLayout.LayoutParams.MATCH_PARENT

        /** Set once by the service, like [ChatHistory.init]. */
        @Volatile
        var onSubmit: ((String) -> Unit)? = null

        /**
         * Fires from [onDestroy] — the field is gone, whatever took it away.
         *
         * The service uses it to put the card back; see the call there for why
         * it is this hook and not the send button.
         */
        @Volatile
        var onClosed: (() -> Unit)? = null

        /**
         * The screen-y of this strip's top edge, whenever it moves.
         *
         * The card above is a fullscreen overlay and overlays sort above the
         * keyboard on this platform, so it has to *end* here for the field and
         * the keyboard to be visible at all. Reported rather than computed on
         * the other side because only this window knows the keyboard's real
         * height.
         */
        @Volatile
        var onTop: ((Int) -> Unit)? = null

        private var live: WeakReference<TextInputActivity>? = null

        /** Set between [onResume] and [onPause]; see [isShowing]. */
        @Volatile
        private var resumed = false

        /**
         * Whether the field is **on screen**, which is not the same as "there is
         * an instance". The service uses this to decide whether a ⌨ tap opens
         * or closes the field, and an alive-but-backgrounded activity would
         * otherwise make the tap do neither.
         */
        val isShowing: Boolean get() = resumed && live?.get() != null

        /**
         * Open it. `NEW_TASK` because this comes from a service, and `SINGLE_TOP`
         * so a second tap on ⌨ raises the field already up instead of stacking a
         * second one behind it.
         */
        fun start(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, TextInputActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }

        /** Close it if it is up. Safe to call from anywhere, including never-opened. */
        fun dismiss() {
            live?.get()?.finish()
        }
    }
}
