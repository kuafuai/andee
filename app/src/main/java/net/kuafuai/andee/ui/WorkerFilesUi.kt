package net.kuafuai.andee.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.kuafuai.andee.compat.WindowMetricsCompat
import net.kuafuai.andee.peer.RemoteAgentHost
import net.kuafuai.andee.peer.PeerRemote
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * What a worker made, on the computer, as a drive: the folder's listing is asked for when the card opens, a file is fetched
 * only when it is tapped, in pieces ([RemoteAgentHost.fetch]), and then opened — an HTML page in the page viewer, anything
 * else in whatever the system opens it with. Nothing is copied before it is asked for, and a file of any size takes only
 * one piece of memory.
 *
 * The same files are what `list_worker_files` and `fetch_worker_file` give Andee; this is the user's way in to them.
 */
class WorkerFilesUi(
    private val context: Context,
    private val worker: String,
    private val title: String,
    private val client: PeerRemote.Client,
    /** The paired computer whose agent this is; null when none offers it now. */
    private val device: () -> String?,
    private val inbox: File,
    /** An HTML page the user tapped: open it in the page viewer (the service folds the ball away first). */
    private val openPage: (title: String, file: File) -> Unit,
    private val onDismiss: () -> Unit = {},
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var frosted = false

    /** The folder shown, relative to the worker's folder; empty is the top. */
    private var path = ""
    private var entries: List<Entry>? = null
    private var error: String? = null
    private var fetching: String? = null

    /** A text file being read in the card: its name and what is in it. Null shows the folder. */
    private var reading: Pair<String, String>? = null

    private data class Entry(val name: String, val dir: Boolean, val size: Long)

    private fun zh() = net.kuafuai.andee.config.VoiceConfig.uiLanguage(context) == "zh"
    private fun t(z: String, e: String) = if (zh()) z else e
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    fun show() {
        if (root != null) return
        val p = WindowManager.LayoutParams(
            minOf(dp(560), (WindowMetricsCompat.bounds(wm).width() * 0.96f).toInt()),
            minOf(dp(720), (WindowMetricsCompat.bounds(wm).height() * 0.90f).toInt()),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 0, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        frosted = Glass.frost(context, wm, p)
        val v = build()
        runCatching {
            v.hideFromAccessibility()
            wm.addView(v, p)
            root = v
            OwnCard.shown()
            Glass.enter(v)
        }
        load()
    }

    fun hide() {
        val v = root ?: return
        root = null
        Glass.exit(v) {
            runCatching { wm.removeView(v) }
            OwnCard.hidden()
            onDismiss()
        }
    }

    private fun rebuild() {
        val old = root ?: return
        val v = build()
        runCatching {
            v.hideFromAccessibility()
            val p = old.layoutParams as WindowManager.LayoutParams
            wm.removeView(old)
            wm.addView(v, p)
            root = v
        }
    }

    /** Ask the computer for the folder's entries, off the main thread. Nothing is downloaded. */
    private fun load() {
        entries = null
        error = null
        rebuild()
        val dev = device()
        if (dev == null) {
            error = t("现在够不着电脑（它没开，或不在同一个 Wi-Fi）。", "The computer cannot be reached now (it is off, or not on this Wi-Fi).")
            rebuild(); return
        }
        Thread {
            val r = runCatching { client.list(dev, worker, path) }
            ui.post {
                r.onSuccess { j ->
                    val a = j.optJSONArray("entries")
                    entries = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
                        .map { Entry(it.optString("name"), it.optBoolean("dir"), it.optLong("size")) }
                }.onFailure { error = it.message ?: it.javaClass.simpleName }
                rebuild()
            }
        }.apply { isDaemon = true; name = "worker-files-list" }.start()
    }

    /** Fetch one file now, in pieces, and open it. */
    private fun open(e: Entry) {
        val dev = device() ?: return
        fetching = e.name
        rebuild()
        val rel = if (path.isEmpty()) e.name else "$path/${e.name}"
        Thread {
            val r = runCatching { RemoteAgentHost(client, null, { dev }, inbox).fetch(dev, worker, rel) }
            ui.post {
                fetching = null
                r.onSuccess { j -> view(File(j.getString("path")), e.name) }
                    .onFailure { error = it.message ?: it.javaClass.simpleName }
                rebuild()
            }
        }.apply { isDaemon = true; name = "worker-files-fetch" }.start()
    }

    private fun view(file: File, name: String) {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext == "html" || ext == "htm") {
            hide()
            openPage(name.removeSuffix(".html").removeSuffix(".htm").take(24), file)
            return
        }
        // Text is read here, in the card. Handing it to the system looked fine and was not: a phone with no app that
        // claims .md still resolved ACTION_VIEW text/markdown to whatever declared a wildcard (a music app, measured on
        // the HONOR), which opened and closed at once — the "flashes and is gone" the user saw.
        if (ext in TEXT_EXTS) {
            val text = runCatching { readText(file) }.getOrElse {
                error = t("读不了这个文件：${it.message}", "Could not read this file: ${it.message}")
                return
            }
            reading = name to text
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // A chooser, so the user picks: a remembered default that cannot open the file is what made this silent.
        val chooser = Intent.createChooser(intent, name).apply {
            clipData = android.content.ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val hasViewer = context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
        if (!hasViewer) {
            error = t("手机上没有能打开 .$ext 的应用。文件在：${file.path}", "No app on this phone opens .$ext. The file is at ${file.path}")
            return
        }
        // The card is an overlay and sits above the chooser it would raise.
        hide()
        runCatching { context.startActivity(chooser) }
    }

    /** At most [MAX_TEXT_BYTES] of the file, with a line saying so when there is more. */
    private fun readText(file: File): String {
        val buf = ByteArray(MAX_TEXT_BYTES)
        var n = 0
        file.inputStream().use { i ->
            while (n < buf.size) {
                val r = i.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
        }
        val head = String(buf, 0, n, Charsets.UTF_8)
        return if (file.length() > n) head + "\n\n" + t("（文件太长，只显示前 ${n / 1024} KB）", "(The file is long; only the first ${n / 1024} KB is shown.)") else head
    }

    // ---- drawing ----

    @SuppressLint("SetTextI18n")
    private fun build(): View {
        reading?.let { return buildReader(it.first, it.second) }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.card(context, dp(28), frosted)
            clipToOutline = true
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(14), dp(12))
        }
        if (path.isNotEmpty()) head.addView(chip("‹") { path = path.substringBeforeLast('/', ""); load() })
        head.addView(
            label("dsh #$worker · $title", Glass.Type.HEADLINE, Glass.TITLE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        head.addView(chip("✕") { hide() })
        card.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        card.addView(label(
            t("在电脑上", "On the computer") + (if (path.isEmpty()) "" else " › $path") + " · " + t("点开才下载", "downloaded when you open one"),
            Glass.Type.CAPTION, Glass.SECONDARY,
        ).apply { setPadding(dp(22), 0, dp(22), dp(8)) }, LinearLayout.LayoutParams(MATCH, WRAP))
        card.addView(View(context).apply { setBackgroundColor(Color.parseColor(Glass.PANEL_EDGE)) }, LinearLayout.LayoutParams(MATCH, dp(1)))

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(16)) }
        when {
            error != null -> body.addView(label(error!!, Glass.Type.CAPTION, Glass.DANGER), matchWrap())
            entries == null -> body.addView(label(t("正在向电脑要目录…", "Asking the computer for the folder…"), Glass.Type.CAPTION, Glass.SECONDARY), matchWrap())
            entries!!.isEmpty() -> body.addView(label(t("这个文件夹是空的。", "This folder is empty."), Glass.Type.CAPTION, Glass.SECONDARY), matchWrap())
        }
        if (fetching != null) body.addView(label(t("正在从电脑取 $fetching …", "Fetching $fetching from the computer…"), Glass.Type.CAPTION, Glass.ACCENT),
            matchWrap().apply { bottomMargin = dp(10) })
        for (e in entries.orEmpty()) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = Glass.panel(context, dp(14))
                isClickable = true
                Glass.pressable(this)
                setOnClickListener {
                    if (fetching != null) return@setOnClickListener
                    if (e.dir) { path = if (path.isEmpty()) e.name else "$path/${e.name}"; load() } else open(e)
                }
            }
            row.addView(label(if (e.dir) "📁" else icon(e.name), Glass.Type.BODY, Glass.TITLE).apply { setPadding(0, 0, dp(10), 0) })
            row.addView(label(e.name, Glass.Type.BODY, Glass.TITLE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(label(if (e.dir) "›" else size(e.size), Glass.Type.CAPTION, Glass.SECONDARY).apply { setPadding(dp(10), 0, 0, 0) })
            body.addView(row, matchWrap().apply { bottomMargin = dp(8) })
        }
        card.addView(ScrollView(context).apply { addView(body, matchWrap()) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return card
    }

    /** One text file, read in the card: Markdown drawn as Markdown, anything else as plain monospace text. */
    private fun buildReader(name: String, text: String): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.card(context, dp(28), frosted)
            clipToOutline = true
        }
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(18), dp(14), dp(12))
        }
        head.addView(chip("‹") { reading = null; rebuild() })
        head.addView(
            label(name, Glass.Type.HEADLINE, Glass.TITLE).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        head.addView(chip("✕") { hide() })
        card.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        card.addView(View(context).apply { setBackgroundColor(Color.parseColor(Glass.PANEL_EDGE)) }, LinearLayout.LayoutParams(MATCH, dp(1)))
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(16), dp(22), dp(20)) }
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext == "md" || ext == "markdown") {
            body.addView(MarkdownView.build(context, text, onLink = { url ->
                // The card is an overlay and sits above the browser it opens.
                hide()
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }), matchWrap())
        } else {
            body.addView(label(text, Glass.Type.CAPTION, Glass.LABEL).apply { typeface = android.graphics.Typeface.MONOSPACE }, matchWrap())
        }
        card.addView(ScrollView(context).apply { addView(body, matchWrap()) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return card
    }

    private fun icon(name: String) = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "html", "htm" -> "🌐"; "md", "txt" -> "📝"; "pdf" -> "📕"; "png", "jpg", "jpeg", "gif", "webp" -> "🖼"
        "csv", "xlsx", "xls" -> "📊"; "zip", "gz", "tar" -> "🗜"; else -> "📄"
    }

    private fun size(n: Long) = when {
        n >= 1 shl 20 -> "%.1f MB".format(n / 1048576.0)
        n >= 1 shl 10 -> "%.0f KB".format(n / 1024.0)
        else -> "$n B"
    }

    private fun chip(text: String, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(Glass.SECONDARY))
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        background = Glass.panel(context, dp(16))
        isClickable = true
        Glass.pressable(this)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, dp(32)).apply { if (text == "‹") rightMargin = dp(8) }
    }

    private fun label(text: String, size: Float, color: String): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(Color.parseColor(color))
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)

    private companion object {
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

        /** Read in the card; 400 KB is far more than a person reads on a phone and keeps one TextView sane. */
        const val MAX_TEXT_BYTES = 400 * 1024
        val TEXT_EXTS = setOf(
            "md", "markdown", "txt", "text", "log", "csv", "tsv", "json", "jsonl", "yaml", "yml", "toml", "ini", "xml",
            "kt", "java", "py", "js", "ts", "sh", "sql", "rs", "go", "c", "h", "cpp", "swift", "rb", "css", "conf",
        )
    }
}
