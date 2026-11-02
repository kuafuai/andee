package net.kuafuai.andee.config

import android.content.Context
import android.util.Log
import net.kuafuai.andee.stage.StageStore
import java.io.File

/**
 * The phone's handle on the stage ([StageStore], shared with the Mac): one store in `filesDir/stage`, and the
 * one thing the phone adds — after any write, redraw the card's stage and mark the stage's care looked after.
 */
object Stage {
    private const val TAG = "Stage"

    @Volatile private var store: StageStore? = null
    @Volatile private var listener: (() -> Unit)? = null

    /** The one view that draws it. Called after every write, on the writer's thread. */
    fun setListener(l: (() -> Unit)?) { listener = l }

    fun store(ctx: Context): StageStore = store ?: synchronized(this) {
        store ?: StageStore(File(ctx.applicationContext.filesDir, "stage"), log = { m, t -> Log.w(TAG, m, t) }).also { s ->
            val app = ctx.applicationContext
            s.onChanged = {
                listener?.invoke()
                runCatching { Notebook.selfhood(app).stageTouched() }.onFailure { Log.w(TAG, "could not touch the stage's care", it) }
            }
            store = s
        }
    }

    fun current(ctx: Context) = store(ctx).current()
    fun indexForPrompt(ctx: Context): String = store(ctx).indexForPrompt()
    fun wipe(ctx: Context) = store(ctx).wipe()
}
