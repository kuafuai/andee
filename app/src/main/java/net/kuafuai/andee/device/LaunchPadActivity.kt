package net.kuafuai.andee.device

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * The stage an app launch from the background must pass through.
 *
 * Android 12+ and HyperOS in particular abort `startActivity` from a Service
 * to another app ("Abort background activity starts from …") even when
 * SYSTEM_ALERT_WINDOW is granted. Starting our OWN activity is not aborted —
 * an activity that is actually in the foreground may start other apps the
 * normal way. So a launch becomes two hops: the Service opens this invisible
 * pad (with NEW_TASK, the one flag the Service context needs), and the pad
 * hands the errand on to the destination *without* NEW_TASK, so the
 * destination lands in the same task and `back` from it goes straight to
 * whatever the user had before — the pad leaves no trace behind.
 *
 * Two rules are paid-for lessons, mirrored from [net.kuafuai.andee.ui.SelfCheckActivity]:
 *  * The errand must NOT carry NEW_TASK — with it, the destination lands in a
 *    task of its own and the way back is lost, which is the bug this path
 *    exists to fix.
 *  * A re-creation must not re-run the errand: the ROM may rebuild this pad
 *    while the user is already off in the destination, and re-opening it would
 *    throw them back into the screen they are leaving. Only a fresh creation
 *    launches the errand; the extra is dropped immediately after.
 */
class LaunchPadActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            finish()
            return
        }
        @Suppress("DEPRECATION")
        val errand = intent.getParcelableExtra(EXTRA_ERRAND) as? Intent
        if (errand != null) {
            // No NEW_TASK here: same task as the pad, which is finishing
            // right now, so the destination becomes the task's root.
            runCatching { startActivity(errand) }
        }
        intent.removeExtra(EXTRA_ERRAND)
        finish()
    }

    companion object {
        private const val EXTRA_ERRAND = "errand"

        /** Open [errand] through the pad, from a Service or any background context. */
        fun show(context: Context, errand: Intent) {
            context.startActivity(
                Intent(context, LaunchPadActivity::class.java)
                    .putExtra(EXTRA_ERRAND, errand)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
