package net.kuafuai.andee.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import net.kuafuai.andee.config.Notebook
import net.kuafuai.andee.config.Tasks
import net.kuafuai.andee.peer.RemoteProgress
import net.kuafuai.andee.peer.RemoteProgressText

/**
 * A job Andee handed to the computer's agent, as a card hanging under the words that announce it — one piece with the reply,
 * not a grey line of its own above it. Live while the worker runs (three lines: where it is, its plan, what it is doing, and the
 * breathing dot), the task's state once it has reported, and the way into its files: the same facts 在办 shows, drawn in the
 * conversation, from the same sources.
 *
 * [refresh] rewrites the lines in place; nothing here is rebuilt for a progress update.
 */
class HandedJobCard(
    private val context: Context,
    private val worker: String,
    private val onFiles: (worker: String, title: String) -> Unit,
    private val onOpenTask: (taskId: String) -> Unit,
) {
    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = Glass.panel(context, dp(14))
        isClickable = true
        Glass.pressable(this)
    }

    private val head = TextView(context).apply {
        textSize = Glass.Type.BODY
        setTextColor(Color.parseColor(Glass.TITLE))
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val dot = View(context).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor(Glass.ACCENT))
        }
        visibility = View.GONE
    }
    private val status = TextView(context).apply {
        textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(Glass.SECONDARY))
        maxLines = 3
        ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(0, dp(4), 0, 0)
    }
    private val files = TextView(context).apply {
        textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(Glass.ACCENT))
        setPadding(0, dp(8), 0, 0)
        isClickable = true
        Glass.pressable(this)
    }
    private var taskId = ""

    init {
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(8) })
        top.addView(head, LinearLayout.LayoutParams(0, WRAP, 1f))
        view.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        view.addView(status, LinearLayout.LayoutParams(MATCH, WRAP))
        view.addView(files, LinearLayout.LayoutParams(WRAP, WRAP))
        view.setOnClickListener { if (taskId.isNotEmpty()) onOpenTask(taskId) }
        val pulse = object : Runnable {
            override fun run() {
                if (!dot.isAttachedToWindow) return
                if (dot.visibility == View.VISIBLE) dot.animate().alpha(if (dot.alpha > 0.5f) 0.12f else 1f).setDuration(600).withEndAction(this).start()
                else dot.postDelayed(this, 600)
            }
        }
        dot.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { dot.post(pulse) }
            override fun onViewDetachedFromWindow(v: View) { dot.animate().cancel() }
        })
        refresh()
    }

    /** Re-read the task and the progress and rewrite the lines. Main thread. */
    fun refresh() {
        val zh = net.kuafuai.andee.config.VoiceConfig.uiLanguage(context) == "zh"
        val book = Notebook.tasks(context)
        val task = book.active().firstOrNull { t -> t.waits.any { it.kind == "worker" && it.name == worker } }
            ?: book.all().lastOrNull { t -> t.waits.any { it.kind == "worker" && it.name == worker } }
        taskId = task?.id.orEmpty()
        val live = RemoteProgress.of(worker)
        head.text = "dsh #$worker · " + (task?.what ?: "")
        dot.visibility = if (live != null) View.VISIBLE else View.GONE
        status.text = when {
            live != null -> RemoteProgressText.of(live, System.currentTimeMillis(), zh).asList().joinToString("\n")
            task != null -> TaskRows.state(task, System.currentTimeMillis(), zh)
            else -> if (zh) "电脑上的 dsh 工作者 #$worker" else "dsh worker #$worker on the computer"
        }
        files.text = (if (zh) "📁 工作文件 ›" else "📁 Work files ›")
        files.setOnClickListener { onFiles(worker, task?.what.orEmpty()) }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
