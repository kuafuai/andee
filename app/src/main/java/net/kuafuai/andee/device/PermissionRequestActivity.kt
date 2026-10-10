package net.kuafuai.andee.device

import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Translucent trampoline that hosts the runtime-permission dialogs. The app
 * is service-first (no main activity), but requestPermissions() needs a
 * live Activity window. Shows nothing, asks, dies.
 *
 * It is also the only place the answer ever exists. The verdict arrives in
 * [onRequestPermissionsResult], and this activity finishes the moment it has
 * it — there is no second chance to ask a dialog that has already gone. So
 * the verdict is handed back to whoever fired the request through [await],
 * rather than being dropped on the floor as it used to be.
 *
 * 🔴 The distinction that pays for all of this is "was a sheet drawn at all".
 * A refusal and a silent block both give [PackageManager.PERMISSION_DENIED],
 * and they demand opposite responses: one is worth explaining, the other is
 * pointless to repeat. The signal that separates them is
 * [shouldShowRequestPermissionRationale], which is an Activity method — so
 * this is the one place that can read it.
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
        settle(verdict(permissions, grantResults))
        finish()
    }

    private fun verdict(permissions: Array<out String>, grantResults: IntArray): String {
        if (grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) return GRANTED

        // Only the refused ones are asked about: a granted permission has no
        // rationale either, and counting it would call every partial grant a
        // silent block.
        val silent = permissions.indices.any { i ->
            grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED &&
                !shouldShowRequestPermissionRationale(permissions[i])
        }
        return if (silent) NO_DIALOG else DENIED
    }

    companion object {
        /**
         * The words the brain branches on. Same four [PermissionsController]
         * reports, because they travel together.
         */
        internal const val GRANTED = "granted"
        internal const val DENIED = "denied"
        internal const val NO_DIALOG = "no_dialog"

        @Volatile private var latch: CountDownLatch? = null
        @Volatile private var outcome: String? = null

        /**
         * Clear the previous answer and open a fresh waiting point. Call this
         * BEFORE startActivity: the dialog can decide inside a millisecond,
         * and an answer that lands before the latch exists is lost.
         */
        internal fun arm() {
            outcome = null
            latch = CountDownLatch(1)
        }

        /**
         * The verdict, or null if none came within [ms] — meaning the dialog
         * is still up waiting for a human. Null is a fact, not a failure: it
         * is what tells the caller nobody has answered yet.
         */
        internal fun await(ms: Long): String? {
            latch?.await(ms, TimeUnit.MILLISECONDS)
            return outcome
        }

        private fun settle(verdict: String) {
            outcome = verdict
            latch?.countDown()
        }
    }
}
