package net.kuafuai.andee.device

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Translucent trampoline for the system document picker — the only way `read_file`
 * gets at a file (issue #37). Shows nothing, asks, dies.
 *
 * **No storage permission, on purpose.** `ACTION_OPEN_DOCUMENT` hands this app read
 * access to the one document the user picks and to nothing else, so "Andee can read
 * your files" is never true — "Andee can read the file you just gave it" is. That is
 * the whole privacy story, and it is why the manifest still has no `READ_*_STORAGE`.
 *
 * Three things about its lifetime are load-bearing:
 *
 *  - **It outlives the pick until the dispatcher has read the file** ([done]). The grant
 *    that came with the result belongs to this activity and is revoked when it is
 *    destroyed; a provider that will not make it persistable would otherwise leave the
 *    dispatcher reading a file it can no longer open. [HOLD_MS] is the backstop.
 *  - **The dispatcher can take it down** ([close]) when its wait runs out or the user
 *    stops the task. A picker left on screen after the call has given up is a pick that
 *    lands on nobody — the model is told "they didn't choose" while the user believes
 *    they did — and the retry the error suggests would stack a second picker on it.
 *  - **Every pick carries a request id**, so a result can only ever answer the call
 *    that asked for it.
 *
 * The grant is made persistable when the provider allows it, so `read_file {again: true}`
 * can read the next pages without asking twice; every older grant is released when a new
 * file is picked, so at most one file is ever held.
 */
class FilePickActivity : Activity() {

    private var request = 0L
    private var answered = false
    private val main = Handler(Looper.getMainLooper())
    private val backstop = Runnable { finishNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = intent.getLongExtra(EXTRA_REQUEST, 0L)
        live = this
        // Recreated under the picker (rotation, process trim): the picker is still up
        // and its answer comes to onActivityResult. Asking again would stack a second one.
        if (savedInstanceState != null) return
        val mimes = intent.getStringArrayExtra(EXTRA_MIMES)?.takeIf { it.isNotEmpty() } ?: arrayOf("*/*")
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(if (mimes.size == 1) mimes[0] else "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (mimes.size > 1) pick.putExtra(Intent.EXTRA_MIME_TYPES, mimes)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            answer(null, "this device has no file picker")
            finishNow()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK) return
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) {
            hold(uri, data.flags)
            answer(uri, null)
            // Stay until the dispatcher has read it — see the class doc.
            main.postDelayed(backstop, HOLD_MS)
        } else {
            answer(null, "cancelled by user")
            finishNow()
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(backstop)
        if (live === this) live = null
        super.onDestroy()
    }

    private fun answer(uri: Uri?, error: String?) {
        if (answered) return
        answered = true
        LastResult = Result(request, uri, error)
    }

    private fun finishNow() {
        if (isFinishing || isDestroyed) return
        @Suppress("DEPRECATION")
        runCatching { finishActivity(REQ_PICK) }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /**
     * Keep read access to [uri] past this activity, and let go of every file before it —
     * all of them, not only [held], because a grant outlives the process and a restart
     * forgets which one that was. Nothing else in this app persists a grant.
     */
    private fun hold(uri: Uri, flags: Int) {
        val cr = contentResolver
        for (p in cr.persistedUriPermissions) {
            if (p.uri != uri) runCatching { cr.releasePersistableUriPermission(p.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                .onFailure { Log.w(TAG, "could not keep access to the picked file: ${it.message}") }
        }
        held = uri
    }

    data class Result(val request: Long, val uri: Uri?, val error: String?)

    companion object {
        private const val TAG = "FilePick"
        private const val REQ_PICK = 0x0F11
        private const val HOLD_MS = 30_000L
        const val EXTRA_MIMES = "mimes"
        const val EXTRA_REQUEST = "request"

        /** Set once per pick: the file, or why there is none. Match [Result.request] before trusting it. */
        @Volatile
        var LastResult: Result? = null
            private set

        @Volatile
        private var lastRequest = 0L

        @Volatile
        private var live: FilePickActivity? = null

        /** The file picked last, for `read_file {again: true}`. Process-lifetime only. */
        @Volatile
        var held: Uri? = null
            private set

        /** A fresh id for the next pick; clears any answer left over from the last one. */
        @Synchronized
        fun newRequest(): Long {
            LastResult = null
            return ++lastRequest
        }

        /** The answer to [request], or null while the user is still choosing. */
        fun resultFor(request: Long): Result? = LastResult?.takeIf { it.request == request }

        /** The dispatcher has read the file: the trampoline and its grant may go. */
        fun done() {
            val a = live ?: return
            a.runOnUiThread { a.finishNow() }
        }

        /** Take the picker down unanswered — the wait ran out, or the user stopped the task. */
        fun close(reason: String) {
            val a = live ?: return
            a.runOnUiThread {
                a.answer(null, reason)
                a.finishNow()
            }
        }
    }
}
