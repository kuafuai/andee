package net.kuafuai.andee.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import kotlin.math.roundToInt

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

    /** A photo ready to go: what the brain gets, and what the strip shows. */
    private class Photo(val jpegBase64: String, val thumb: Bitmap)

    private val photos = mutableListOf<Photo>()
    private lateinit var strip: LinearLayout
    private lateinit var thumbs: LinearLayout
    private lateinit var thumbScroll: HorizontalScrollView

    /** Whether the card should follow the strip's top edge right now. */
    private var trackTop = false

    /** The keyboard is sliding; [onProgress] owns the strip's position and the card's edge until it stops. */
    private var sliding = false
    private var keyboardRequested = false
    private val keyboardRetry = Runnable {
        val visible = ViewCompat.getRootWindowInsets(field)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (resumed && !isFinishing && !picking && hasWindowFocus() && field.hasFocus() && !visible) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * The user is in the camera, the photo picker or the permission dialog.
     * While it is set, the keyboard going away and this activity stopping are
     * both expected, and neither may close the field.
     */
    private var picking = false

    /** Where the system camera was told to write. */
    private var shotFile: File? = null

    /** Images still being decoded; [send] waits for them rather than dropping them. */
    private var decoding = 0
    private var sendWhenDecoded = false
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No window animation in or out: the strip opens exactly where the card's identical pill already is,
        // so the keyboard sliding is the only motion there should be.
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        // Before the first `Glass.*` read below: [Theme] holds the *default* palette
        // until something loads the saved one, and this page draws immediately.
        Theme.load(this)
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
            // No fill of its own: the pill around it is the field (see [InputPill]), exactly as the card's
            // resting pill draws it, so opening the keyboard changes nothing but the keyboard.
            background = null
            // Single line with the enter key turned into send. Deliberately not
            // a multi-line box: with one, Enter inserts a newline and the only
            // way to send is the button — a coin flip the user has to learn.
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(4), 0, dp(4), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send()
                    true
                } else {
                    false
                }
            }
        }

        // The same pill as the card's resting one — camera, album, words, send — so the field opening is the
        // keyboard arriving and nothing else. Closing is the keyboard going (or back): the pill has no ✕.
        val row = InputPill.build(
            this, field,
            onCamera = { takePhoto() }, onAlbum = { pickPhotos() }, onSend = { send() },
        )

        thumbs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        thumbScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(thumbs)
        }

        strip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // **No fill, and that is the whole point.**
            //
            // This strip used to paint `Glass.card(frosted = false)` — a near-opaque
            // slate — and the argument for it was real at the time. Read it, because
            // the argument still holds and what changed is underneath: the strip stands
            // over whatever app is behind, 6% white over a white document is nothing,
            // and there is no blur to lean on (`Glass.frost` blurs behind a *window*,
            // and this window is the whole screen). So the strip got the base fill and
            // the field's own translucency went to work on an opaque wall.
            //
            // **A translucent field on an opaque bar is a flat grey rectangle.** That
            // is what "the input box has no glass" was, and no alpha on the field could
            // have fixed it. What did change is that this window now paints [Backdrop]
            // itself (see below), so the surface behind this bar is *ours*: a known
            // blue wash rather than somebody's home screen. Nothing needs to be walled
            // off any more, so the strip gets out of the way and the field is left with
            // the thing it can actually be frosted against — the page. `Glass.well`'s
            // 5% / 35% now shows the wash's gradient through the box, which is the
            // effect this was asked for.
            //
            // The pre-warm window is still covered: the fill behind the backdrop image
            // is the same slate this line used to paint, so a field opened in the first
            // few hundred milliseconds gets a continuous surface rather than the
            // desktop. Same arrangement as before, one layer down.
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(thumbScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP))
            addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP))
        }

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
        // Tap the empty space above the strip = done typing. The field keeps
        // focus otherwise — an EditText never lets go on its own — and the pill
        // has no ✕, only camera/album/words/send. Clearing focus hides the
        // keyboard, and the inset listener below already closes the field once
        // the keys are really gone ([scheduleCloseWhenGone]).
        backdrop.setOnClickListener {
            field.clearFocus()
            hideKeyboard()
        }

        // One row, anchored at the bottom. See the backdrop above for why the
        // window is no longer see-through.
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(4), dp(12), dp(12))
            addView(strip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP))
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
        // Opened from the resting pill's camera or album icon: carry on to that.
        when (intent?.getStringExtra(EXTRA_THEN)) {
            "camera" -> root.post { takePhoto() }
            "album" -> root.post { pickPhotos() }
        }

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
            // Lay out at the final inset once. During animation translationY
            // offsets the strip back to the keyboard's current position.
            column.setPadding(dp(12), dp(4) + bars.top, dp(12), dp(12) + ime)
            if (ime > 0) {
                imeShown = true
                cancelCloseWhenGone()
            } else if (picking) {
                // Off in the camera or the picker; the keyboard left with us
                // and comes back in onResume. Leave the card's height alone.
                trackTop = false
                return@setOnApplyWindowInsetsListener insets
            } else if (imeShown && photos.isEmpty()) {
                // The keyboard is on its way out, and so is this field:收起键盘
                // means the user is done typing, and a strip left behind on an
                // otherwise empty screen is a thing to dismiss twice.
                //
                // The band is deliberately *not* re-reported here (see the post
                // below): with the keys gone the strip would jump to the bottom
                // of the screen, the card would grow to meet it, and the whole
                // thing would close a moment later — three moves for one
                // gesture. Left alone, the card simply grows back once.
                //
                // Not with photos on the strip, though: those took a trip to
                // another app to collect, and losing them to a dropped keyboard
                // is worse than a strip that waits at the bottom for 发送.
                trackTop = false
                if (!sliding) scheduleCloseWhenGone()
                return@setOnApplyWindowInsetsListener insets
            }
            // Before the keyboard has ever arrived the strip sits at the very bottom, under the card's own
            // identical pill. Telling the card to end there would only make it jump twice.
            if (!imeShown) return@setOnApplyWindowInsetsListener insets
            if (sliding) {
                // This listener sees where the keyboard will *end* before it starts moving. The card takes that
                // edge once, now — resizing an overlay every frame is a relayout of the whole card per frame,
                // which is what made the slide stutter (measured: 66 slow UI-thread frames in three opens).
                // The gap it leaves above the rising strip is this window's own backdrop: the same picture.
                trackTop = false
                if (ime > 0 && strip.height > 0) {
                    val at = IntArray(2)
                    root.getLocationOnScreen(at)
                    onTop?.invoke(at[1] + root.height - (dp(12) + ime) - strip.height)
                }
                return@setOnApplyWindowInsetsListener insets
            }
            // Tell the card where our top edge is, in screen pixels, so it can
            // end there instead of covering us. Posted, because the row has no
            // position until this pass has laid it out — and sent again on every
            // inset change, which is what keeps the two in step as the keyboard
            // slides up and as 搜狗's toolbar row comes and goes.
            trackTop = true
            root.post { reportTop() }
            insets
        }
        // Follow the IME on the render path, without requesting layout each frame.
        ViewCompat.setWindowInsetsAnimationCallback(root, object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
            private var startBottom = 0

            override fun onPrepare(a: WindowInsetsAnimationCompat) {
                if (a.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                startBottom = column.paddingBottom
                sliding = true
                trackTop = false
            }

            override fun onStart(
                a: WindowInsetsAnimationCompat,
                bounds: WindowInsetsAnimationCompat.BoundsCompat,
            ): WindowInsetsAnimationCompat.BoundsCompat {
                if (a.typeMask and WindowInsetsCompat.Type.ime() != 0 && !picking) {
                    strip.translationY = (column.paddingBottom - startBottom).toFloat()
                }
                return bounds
            }

            override fun onProgress(insets: WindowInsetsCompat, running: MutableList<WindowInsetsAnimationCompat>): WindowInsetsCompat {
                if (!sliding || picking) return insets
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                strip.translationY = (column.paddingBottom - dp(12) - ime).toFloat()
                return insets
            }

            override fun onEnd(a: WindowInsetsAnimationCompat) {
                if (a.typeMask and WindowInsetsCompat.Type.ime() == 0) return
                sliding = false
                strip.translationY = 0f
                val now = ViewCompat.getRootWindowInsets(root)
                val ime = now?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                if (ime > 0) {
                    trackTop = true
                    reportTop()
                    return
                }
                if (imeShown && photos.isEmpty() && !picking) {
                    // The keys are down: hand the card its full height back now — its own pill is the same
                    // picture as this strip, so the hand-over is invisible — and close.
                    onTop?.invoke(0)
                    scheduleCloseWhenGone()
                }
            }
        })
        // The strip also grows by itself, when the first photo arrives.
        strip.addOnLayoutChangeListener { _, _, top, _, _, _, oldTop, _, _ ->
            if (top != oldTop && trackTop) reportTop()
        }
        window.decorView.hideFromAccessibility()
        OwnCard.shown()
        live = WeakReference(this)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        keyboardRequested = false
        field.requestFocus()
        field.post { showKeyboard() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::field.isInitialized) showKeyboard()
    }

    override fun onPause() {
        // Paired with [resumed] in the companion, which is what makes
        // `isShowing` mean "on screen" rather than "alive". Measured: HOME (or
        // anything else that backgrounds this without finishing it) leaves the
        // instance — and its window — in place, and a service that believed the
        // field was still showing made the next ⌨ tap a *close*: the user taps
        // the button, and nothing at all happens.
        resumed = false
        field.removeCallbacks(keyboardRetry)
        super.onPause()
    }

    /** What `noHistory` used to do, minus the trips this field sends the user on. */
    override fun onStop() {
        super.onStop()
        if (!picking && !isFinishing) finish()
    }

    override fun onDestroy() {
        if (::field.isInitialized) field.removeCallbacks(keyboardRetry)
        cancelCloseWhenGone()
        worker.shutdownNow()
        shotFile?.delete()
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
     * Hand the text and the photos to the service and get out of the way.
     *
     * Photos alone are a complete message — "what is this", without the words.
     * Clears the field before handing it over, so a send that turns out to be a
     * no-op (nothing listening) is visible as an empty box rather than as text
     * that silently went nowhere.
     */
    private fun send() {
        if (decoding > 0) {
            sendWhenDecoded = true
            return
        }
        val text = field.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() && photos.isEmpty()) return
        val images = photos.map { it.jpegBase64 }
        field.setText("")
        sent = true
        onSubmit?.invoke(text, images)
        finish()
    }

    private fun takePhoto() {
        if (photos.size + decoding >= MAX_PHOTOS) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            goAway()
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA_PERMISSION)
            return
        }
        val dir = File(cacheDir, "shots").apply { mkdirs() }
        val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        goAway()
        if (runCatching { startActivityForResult(intent, REQ_CAMERA) }.isFailure) {
            Log.w(TAG, "no camera app to take a photo with")
            comeBack()
            return
        }
        shotFile = file
    }

    /**
     * The system photo picker where there is one (no storage permission, and
     * it is the picker people already know); `GET_CONTENT` below API 33.
     */
    private fun pickPhotos() {
        val room = MAX_PHOTOS - photos.size - decoding
        if (room <= 0) return
        val intent = if (Build.VERSION.SDK_INT >= 33) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                // The picker rejects a limit of 1; leaving it out means "one".
                if (room > 1) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, room)
            }
        } else {
            Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, room > 1)
        }
        goAway()
        if (runCatching { startActivityForResult(intent, REQ_PICK) }.isFailure) {
            Log.w(TAG, "no photo picker")
            comeBack()
        }
    }

    private fun goAway() {
        picking = true
        cancelCloseWhenGone()
        onAway?.invoke(true)
    }

    private fun comeBack() {
        picking = false
        onAway?.invoke(false)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != REQ_CAMERA_PERMISSION) return
        comeBack()
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) takePhoto()
    }

    @Deprecated("Activity has no other way to get a result without androidx.activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        comeBack()
        when (requestCode) {
            REQ_CAMERA -> {
                val file = shotFile
                shotFile = null
                if (file == null) return
                if (resultCode == RESULT_OK && file.length() > 0) {
                    load(Uri.fromFile(file), file)
                } else {
                    file.delete()
                }
            }
            REQ_PICK -> {
                if (resultCode != RESULT_OK || data == null) return
                val uris = mutableListOf<Uri>()
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it }
                }
                if (uris.isEmpty()) data.data?.let { uris += it }
                uris.take(MAX_PHOTOS - photos.size - decoding).forEach { load(it, null) }
            }
        }
    }

    /**
     * Decode off the main thread, at screenshot size: 1280 on the long side
     * is what the brain already reads every screen at, and a 12-megapixel
     * original would be a few megabytes of base64 for no more understanding.
     * `ImageDecoder` applies the EXIF rotation, so a portrait photo arrives
     * upright.
     */
    private fun load(uri: Uri, deleteAfter: File?) {
        decoding++
        val thumbPx = dp(THUMB_DP)
        worker.execute {
            val result = runCatching {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    val long = maxOf(w, h)
                    if (long > PHOTO_LONG_SIDE) {
                        val s = PHOTO_LONG_SIDE.toFloat() / long
                        d.setTargetSize((w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1))
                    }
                }
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                val thumb = ThumbnailUtils.extractThumbnail(bmp, thumbPx, thumbPx)
                if (thumb !== bmp) bmp.recycle()
                Photo(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), thumb)
            }
            deleteAfter?.delete()
            runOnUiThread {
                decoding--
                result.onSuccess { addPhoto(it) }
                    .onFailure { Log.w(TAG, "could not read $uri: ${it.message}") }
                if (decoding == 0 && sendWhenDecoded) {
                    sendWhenDecoded = false
                    send()
                }
            }
        }
    }

    private fun addPhoto(p: Photo) {
        if (isFinishing || photos.size >= MAX_PHOTOS) return
        photos += p
        val size = dp(THUMB_DP)
        val cell = FrameLayout(this)
        val image = ImageView(this).apply {
            setImageBitmap(p.thumb)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
            clipToOutline = true
        }
        cell.addView(image, FrameLayout.LayoutParams(size, size))
        val remove = TextView(this).apply {
            text = "✕"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC000000"))
            }
            setOnClickListener {
                photos.remove(p)
                thumbs.removeView(cell)
                syncPhotos()
            }
        }
        cell.addView(
            remove,
            FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(3)
                marginEnd = dp(3)
            },
        )
        thumbs.addView(
            cell,
            LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(8) },
        )
        syncPhotos()
        thumbScroll.post { thumbScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun syncPhotos() {
        thumbScroll.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
        field.hint = lctx.getString(if (photos.isEmpty()) R.string.input_hint else R.string.input_hint_photos)
    }

    private fun reportTop() {
        if (isFinishing) return
        val at = IntArray(2)
        strip.getLocationOnScreen(at)
        onTop?.invoke(at[1])
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
        if (closeWhenGone != null || isFinishing || picking) return
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

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun showKeyboard() {
        if (!resumed || isFinishing || picking || !hasWindowFocus() || keyboardRequested) return
        keyboardRequested = true
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        field.postDelayed(keyboardRetry, IME_RETRY_MS)
    }

    private fun hideKeyboard() {
        runCatching { field.windowInsetsController?.hide(android.view.WindowInsets.Type.ime()) }
        runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(field.windowToken, 0)
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

    private fun iconButton(icon: Int, label: String, onClick: () -> Unit): ImageView =
        ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(Color.parseColor(Glass.LABEL))
            contentDescription = label
            setPadding(dp(8), dp(10), dp(8), dp(10))
            isClickable = true
            isFocusable = true
            Glass.pressable(this)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(44))
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "Body"
        private const val IME_RETRY_MS = 250L
        private const val MAX_PHOTOS = 6
        private const val PHOTO_LONG_SIDE = 1280
        private const val JPEG_QUALITY = 85
        private const val THUMB_DP = 64
        private const val REQ_CAMERA = 1
        private const val REQ_PICK = 2
        private const val REQ_CAMERA_PERMISSION = 3

        /**
         * How long the keyboard has to stay gone before the field follows it.
         * Long enough to ride out an input-method swap, short enough to read as
         * "the field closed with the keyboard".
         */
        private const val IME_GONE_MS = 250L
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val MATCH_PARENT = LinearLayout.LayoutParams.MATCH_PARENT

        /** Set once by the service, like [ChatHistory.init]. Text may be empty when photos are not. */
        @Volatile
        var onSubmit: ((String, List<String>) -> Unit)? = null

        /**
         * The field is sending the user to the camera / photo picker (true) or
         * has them back (false). The card above has to vanish meanwhile: it is
         * an overlay and would sit over the top of whatever app opens.
         */
        @Volatile
        var onAway: ((Boolean) -> Unit)? = null

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
        /** [then] = "camera" / "album" to go straight on to that, as the resting pill's icons ask. */
        fun start(context: Context, then: String? = null) {
            runCatching {
                context.startActivity(
                    Intent(context, TextInputActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(EXTRA_THEN, then),
                )
            }
        }

        const val EXTRA_THEN = "then"

        /** Close it if it is up. Safe to call from anywhere, including never-opened. */
        fun dismiss() {
            live?.get()?.finish()
        }
    }
}
