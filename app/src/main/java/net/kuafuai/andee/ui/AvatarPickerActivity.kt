package net.kuafuai.andee.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import net.kuafuai.andee.R
import net.kuafuai.andee.config.CustomAvatar
import net.kuafuai.andee.i18n.AppLocale
import java.util.concurrent.Executors

/** An Activity retains the picker's URI grant until its local copy has finished. */
class AvatarPickerActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var importing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OwnCard.shown()
        picking = true
        onAway?.invoke(true)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ProgressBar(this@AvatarPickerActivity))
            addView(TextView(this@AvatarPickerActivity).apply {
                text = AppLocale.str(this@AvatarPickerActivity, R.string.expr_custom_loading)
                setTextColor(android.graphics.Color.WHITE)
            })
        }
        setContentView(content)
        content.setBackgroundColor(android.graphics.Color.argb(170, 0, 0, 0))
        window.decorView.hideFromAccessibility()
        if (savedInstanceState?.getBoolean("importing") == true) {
            finish()
        } else if (savedInstanceState == null) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT)
                .setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            val preferred = if (Build.VERSION.SDK_INT >= 33) {
                Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
            } else fallback
            runCatching { startActivityForResult(preferred, PICK) }
                .recoverCatching { startActivityForResult(fallback, PICK) }
                .onFailure { failed(); finish() }
        }
    }

    @Deprecated("Activity has no other way to get a result without androidx.activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            finish()
            return
        }
        importing = true
        val app = applicationContext
        worker.execute {
            val result = runCatching { CustomAvatar.importImage(app, uri) }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    if (result.isFailure) failed()
                    finish()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("importing", importing)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        picking = false
        onAway?.invoke(false)
        OwnCard.hidden()
        if (!isChangingConfigurations) {
            val closed = onClosed
            onClosed = null
            closed?.invoke()
        }
        super.onDestroy()
    }

    private fun failed() {
        Toast.makeText(this, AppLocale.str(this, R.string.expr_custom_error), Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val PICK = 1
        @Volatile var picking = false
            private set
        @Volatile var onAway: ((Boolean) -> Unit)? = null
        private var onClosed: (() -> Unit)? = null

        fun launch(context: Context, closed: () -> Unit) {
            onClosed = closed
            runCatching {
                context.startActivity(Intent(context, AvatarPickerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                onClosed = null
                closed()
                Toast.makeText(context, AppLocale.str(context, R.string.expr_custom_error), Toast.LENGTH_LONG).show()
            }
        }
    }
}
