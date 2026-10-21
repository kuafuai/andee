package net.kuafuai.andee.config

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** One local image shared by every ball surface; decoding never runs on the UI thread. */
object CustomAvatar {
    private val lock = Any()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val ui by lazy { Handler(Looper.getMainLooper()) }
    private val loader = Executors.newSingleThreadExecutor()
    private var preferences: SharedPreferences? = null
    private var loaded = false
    private var generation = 0
    @Volatile private var image: Bitmap? = null

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "custom_avatar") notifyChanged()
    }

    fun hasImage(context: Context): Boolean = file(context).baseFile.isFile

    fun bitmap(context: Context): Bitmap? =
        if (VoiceConfig.customAvatarEnabled(context)) image else null

    fun observe(context: Context, listener: () -> Unit) {
        val app = context.applicationContext
        if (preferences == null) {
            preferences = app.getSharedPreferences("voice_prefs", Context.MODE_PRIVATE).also {
                it.registerOnSharedPreferenceChangeListener(preferenceListener)
            }
        }
        listeners.add(listener)
        loader.execute {
            synchronized(lock) {
                if (!loaded) {
                    image = runCatching {
                        file(app).openRead().use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                    loaded = true
                    if (image == null && VoiceConfig.customAvatarEnabled(app)) {
                        VoiceConfig.save(app, mapOf("custom_avatar" to "off"))
                    }
                }
            }
            notifyChanged()
        }
    }

    fun unobserve(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Called by the picker's worker. Failure leaves the previous image and selection intact. */
    fun importImage(context: Context, uri: Uri) {
        val before = synchronized(lock) { generation }
        val bytes = context.contentResolver.openInputStream(uri)?.use { AvatarImageRules.read(it) }
            ?: throw IOException("Cannot open avatar image")
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
            val (width, height) = AvatarImageRules.targetSize(info.size.width, info.size.height)
            d.setTargetSize(width, height)
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        synchronized(lock) {
            // A reset or a cancelled picker must not be undone by a decode already in flight.
            if (before != generation || Thread.currentThread().isInterrupted) {
                decoded.recycle()
                throw IOException("Avatar import cancelled")
            }
            val target = file(context)
            val stream = target.startWrite()
            try {
                check(decoded.compress(Bitmap.CompressFormat.PNG, 100, stream))
                target.finishWrite(stream)
            } catch (e: Exception) {
                target.failWrite(stream)
                decoded.recycle()
                throw e
            }
            image = decoded
            loaded = true
            VoiceConfig.save(context, mapOf("custom_avatar" to "on"))
        }
        notifyChanged()
    }

    fun clear(context: Context) {
        synchronized(lock) {
            generation++
            file(context).delete()
            image = null
            loaded = true
            VoiceConfig.save(context, mapOf("custom_avatar" to "off"))
        }
        notifyChanged()
    }

    private fun file(context: Context) = AtomicFile(File(context.filesDir, "custom-avatar.png"))

    private fun notifyChanged() {
        ui.post { listeners.forEach { it() } }
    }
}
