package net.kuafuai.andee.desktop.dsh

import java.io.File
import java.util.Locale

/**
 * What a worker made, listed and read on this Mac: the same drive the phone browses over the link ([WorkerFilesUi] there), except
 * that here the folder is on the disk, so nothing is fetched. One level at a time, inside the worker's folder only, and text is
 * read in the window instead of being handed to whatever the system picks for the extension.
 *
 * Plain Kotlin on purpose: the walls (nothing outside the folder, links that leave it count as outside) are tested without a
 * window.
 */
class WorkerFiles(private val base: File) {

    data class Entry(val name: String, val dir: Boolean, val size: Long)

    /** What opening a file means. */
    sealed class Opened {
        /** Text, read here. [more] is true when the file was longer than [MAX_TEXT_BYTES]. */
        data class Text(val name: String, val text: String, val markdown: Boolean, val more: Boolean) : Opened()

        /** An HTML page: shown the way the app shows any page. */
        data class Page(val name: String, val file: File) : Opened()

        /** Anything else: handed to the system's own viewer. */
        data class Other(val name: String, val file: File) : Opened()
    }

    private val root: File = base.canonicalFile

    /** The entries of [path] (relative to the worker's folder; empty is the top), folders first, hidden files left out. */
    fun list(path: String): List<Entry> {
        val d = inside(path)
        require(d.isDirectory) { "$path is not a folder." }
        return (d.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
            .map { Entry(it.name, it.isDirectory, if (it.isFile) it.length() else 0L) }
    }

    /** The file at [path], read or described according to its kind. */
    fun open(path: String): Opened {
        val f = inside(path)
        require(f.isFile) { "$path is not a file." }
        val ext = f.extension.lowercase(Locale.ROOT)
        return when {
            ext == "html" || ext == "htm" -> Opened.Page(f.name, f)
            ext in TEXT_EXTS -> {
                val buf = ByteArray(MAX_TEXT_BYTES)
                var n = 0
                f.inputStream().use { i ->
                    while (n < buf.size) {
                        val r = i.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                }
                // A cut can land inside a multi-byte character; decoding replaces the broken tail rather than failing.
                Opened.Text(f.name, String(buf, 0, n, Charsets.UTF_8), ext == "md" || ext == "markdown", f.length() > n)
            }
            else -> Opened.Other(f.name, f)
        }
    }

    /** [path] inside the worker's folder, resolved through links: a link that leaves the folder is outside it. */
    internal fun inside(path: String): File {
        val f = File(root, path.trimStart('/')).canonicalFile
        require(f == root || f.path.startsWith(root.path + File.separator)) { "$path is not inside the worker's folder." }
        return f
    }

    companion object {
        /** Far more than a person reads in a window, and keeps one text view sane. Same figure as the phone's. */
        const val MAX_TEXT_BYTES = 400 * 1024

        val TEXT_EXTS = setOf(
            "md", "markdown", "txt", "text", "log", "csv", "tsv", "json", "jsonl", "yaml", "yml", "toml", "ini", "xml",
            "kt", "java", "py", "js", "ts", "sh", "sql", "rs", "go", "c", "h", "cpp", "swift", "rb", "css", "conf",
        )

        fun size(n: Long) = when {
            n >= 1 shl 20 -> "%.1f MB".format(Locale.ROOT, n / 1048576.0)
            n >= 1 shl 10 -> "%.0f KB".format(Locale.ROOT, n / 1024.0)
            else -> "$n B"
        }

        fun icon(name: String) = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "html", "htm" -> "🌐"; "md", "txt" -> "📝"; "pdf" -> "📕"; "png", "jpg", "jpeg", "gif", "webp" -> "🖼"
            "csv", "xlsx", "xls" -> "📊"; "zip", "gz", "tar" -> "🗜"; else -> "📄"
        }
    }
}
