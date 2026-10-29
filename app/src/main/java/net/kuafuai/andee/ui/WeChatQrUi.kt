package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale

/**
 * The WeChat icon at the foot of the card opens this: Andee's WeChat QR code, a line saying to scan it with
 * WeChat to add Andee as a friend, and 保存到相册 — the code is no use on the screen that is showing it, so the
 * way to scan it from this same phone is WeChat's "scan from album".
 *
 * Saved through [MediaStore] into Pictures/Andee, which needs no storage permission on API 29+ (this app's
 * minimum is 30). Same window shape as [NotebookUi]: hidden from accessibility, counted by [OwnCard].
 */
class WeChatQrUi(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null

    fun show() {
        if (root != null) return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT,
        )
        val frosted = Glass.frost(context, wm, p)
        val v = build(frosted)
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
        }
    }

    @SuppressLint("SetTextI18n")
    private fun build(frosted: Boolean): View {
        val lctx = AppLocale.wrap(context)
        val width = minOf(dp(360), (wm.currentWindowMetrics.bounds.width() * 0.86f).toInt())
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Glass.card(context, dp(28), frosted)
            setPadding(dp(22), dp(18), dp(22), dp(22))
            isClickable = true // a tap on the card itself must not reach the scrim behind it
        }
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(context).apply {
            text = lctx.getString(R.string.wechat_title)
            textSize = Glass.Type.TITLE
            setTextColor(Color.parseColor(Glass.TITLE))
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(TextView(context).apply {
            text = "✕"
            textSize = Glass.Type.CAPTION
            setTextColor(Color.parseColor(Glass.SECONDARY))
            gravity = Gravity.CENTER
            background = Glass.panel(context, dp(16))
            isClickable = true
            Glass.pressable(this)
            setOnClickListener { hide() }
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        card.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        // On white whatever the theme: a QR code needs its quiet zone, and the image already carries its own.
        card.addView(ImageView(context).apply {
            setImageResource(R.drawable.wechat_qr)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() }
            clipToOutline = true
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        card.addView(TextView(context).apply {
            text = lctx.getString(R.string.wechat_hint)
            textSize = Glass.Type.BODY
            setTextColor(Color.parseColor(Glass.LABEL))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        val save = TextView(context).apply {
            text = lctx.getString(R.string.wechat_save)
            textSize = Glass.Type.BODY
            setTextColor(Color.parseColor(Glass.ON_TINT))
            gravity = Gravity.CENTER
            background = Glass.tinted(context, dp(24))
            setPadding(dp(26), dp(13), dp(26), dp(13))
            isClickable = true
            Glass.pressable(this)
        }
        save.setOnClickListener {
            val ok = saveToGallery()
            save.text = lctx.getString(if (ok) R.string.wechat_saved else R.string.wechat_save_failed)
            if (ok) save.isClickable = false
        }
        card.addView(save, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })

        // A dimmed full-screen scrim: tapping outside the card closes it.
        return android.widget.FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { hide() }
            addView(card, android.widget.FrameLayout.LayoutParams(width, WRAP, Gravity.CENTER))
        }
    }

    /** Into Pictures/Andee through MediaStore — no permission needed. True when the file was written. */
    private fun saveToGallery(): Boolean = runCatching {
        val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.wechat_qr) ?: return false
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "andee-wechat-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Andee")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val written = resolver.openOutputStream(uri)?.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } == true
        if (!written) { resolver.delete(uri, null, null); return false }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
