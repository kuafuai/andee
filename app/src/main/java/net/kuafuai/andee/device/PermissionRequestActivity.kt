package net.kuafuai.andee.device

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

/**
 * Translucent trampoline that hosts the runtime-permission dialogs. The app
 * is service-first (no main activity), but requestPermissions() needs a
 * live Activity window. Shows nothing, asks, dies.
 */
class PermissionRequestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val perms = intent.getStringArrayExtra("permissions") ?: run { finish(); return }
        requestPermissions(perms, 4242)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        finish()
    }
}
