package net.kuafuai.andee.ui

import android.content.Context
import android.util.Log
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * A chat message, as a full-screen page.
 *
 * A row shows six lines and then an ellipsis, which is the right shape for a
 * transcript — a chat where every answer unfolds to forty lines is a chat nobody
 * can scroll. What was missing is a way to read the rest when the rest is what
 * you wanted. Double-tapping a row opens this.
 *
 * It goes through [HtmlActivity] rather than growing a reader of its own, and
 * that is about mechanics rather than markup: the page path already knows how to
 * fold the card first, hang the ball off the ledge, float a 完成 pill, and report
 * itself closed — none of which is worth implementing twice. The stylesheet below
 * is the house style the `show_html` tool description gives the model, so a
 * message opened this way looks like a page the model composed, not like a
 * second kind of screen.
 *
 * The text is escaped. A message is data — it can contain angle brackets, a
 * stray tag, someone's HTML homework — and handing that to a WebView unescaped
 * would render it as markup. Paragraphs are built here rather than by CSS
 * `white-space:pre` so the escaping stays obvious in one place.
 */
object TextPage {

    private const val TAG = "Body"

    /** Where the built page is staged: the same directory the model's pages use. */
    private fun dir(context: Context) = File(context.filesDir, "pages").apply { mkdirs() }

    /**
     * Write [entry] out as a page and return the file, or null if the disk said
     * no — in which case the caller shows nothing rather than an empty screen.
     *
     * One file per entry, named after its id: opening the same message twice
     * overwrites rather than accumulating, and `ChatHistory.wipe` already deletes
     * this directory when the user asks for a factory reset.
     */
    fun write(context: Context, entry: ChatHistory.Entry): File? = runCatching {
        File(dir(context), "text_${entry.id}.html").also {
            it.writeText(html(head(context, entry), entry.text))
        }
    }.getOrElse {
        Log.w(TAG, "text page: could not write: ${it.message}")
        null
    }

    /**
     * Who said it and when, as the row's own header reads. Dated rather than
     * clock-only: a row is read in a list you can see the top of, a page is read
     * on its own with nothing around it to say whether it was this morning.
     */
    private fun head(context: Context, e: ChatHistory.Entry): String {
        val lctx = AppLocale.wrap(context)
        val who = when (e.role) {
            ChatHistory.Role.USER -> lctx.getString(R.string.textpage_role_you)
            ChatHistory.Role.ASSISTANT -> lctx.getString(R.string.textpage_role_assistant)
            ChatHistory.Role.ERROR -> lctx.getString(R.string.textpage_role_error)
            ChatHistory.Role.PAGE -> lctx.getString(R.string.textpage_role_page)
            ChatHistory.Role.EVENT -> "·"
        }
        val when_ = SimpleDateFormat("MM-dd HH:mm", AppLocale.localeOf(context)).format(Date(e.ts))
        return "$who · $when_"
    }

    /**
     * A folded topic's conversation as one page — 对话记录 on the topic card.
     *
     * Built from the device's own rows, which are what the user saw and said,
     * rather than from the brain's archived text, which carries tool chatter and
     * reads like a log. Rows age out under the history cap, though, so when none
     * are left the archived text is shown instead; it is still the conversation.
     */
    fun writeTopic(context: Context, topic: ChatHistory.Topic): File? = runCatching {
        val lctx = AppLocale.wrap(context)
        val fmt = SimpleDateFormat("MM-dd HH:mm", AppLocale.localeOf(context))
        val rows = ChatHistory.rowsOf(topic.id)
        val cards = if (rows.isNotEmpty()) {
            rows.joinToString("\n") { e ->
                val who = when (e.role) {
                    ChatHistory.Role.USER -> lctx.getString(R.string.textpage_role_you)
                    ChatHistory.Role.ASSISTANT -> lctx.getString(R.string.textpage_role_assistant)
                    ChatHistory.Role.ERROR -> lctx.getString(R.string.textpage_role_error)
                    ChatHistory.Role.PAGE -> lctx.getString(R.string.textpage_role_page)
                    ChatHistory.Role.EVENT -> "·"
                }
                val cls = if (e.role == ChatHistory.Role.USER) "card mine" else "card"
                "    <p class=\"who\">${esc(who)} · ${esc(fmt.format(Date(e.ts)))}</p>\n" +
                    "    <div class=\"$cls\">\n${body(e.text)}\n    </div>"
            }
        } else {
            val text = ChatHistory.topicText(topic.id) ?: lctx.getString(R.string.textpage_topic_gone)
            "    <div class=\"card\">\n${body(text)}\n    </div>"
        }
        val head = lctx.getString(R.string.textpage_topic_head, topic.title)
        File(dir(context), "topic_${topic.id}.html").also { it.writeText(page(head, cards)) }
    }.getOrElse {
        Log.w(TAG, "topic page: could not write: ${it.message}")
        null
    }

    /** The whole document. Exposed for the tests that read it back. */
    fun html(who: String, text: String): String =
        page(who, "    <div class=\"card\">\n${body(text)}\n    </div>")

    private fun page(head: String, content: String): String = """
<!doctype html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<style>
  html,body { margin:0; padding:0; background:#0F172A; color:#F8FAFC;
    font-family:-apple-system,"PingFang SC","Noto Sans CJK SC",system-ui,sans-serif;
    font-size:16px; line-height:1.6; -webkit-text-size-adjust:100%; }
  /* 88px of top padding clears the ✕完成 pill the page floats there. */
  .wrap { padding:88px 20px 40px; }
  .who { margin:0 0 16px; font-size:13px; color:#94A3B8; }
  .card + .who { margin-top:20px; }
  .card { background:#1E293B; border:1px solid rgba(148,163,184,.18);
    border-radius:14px; padding:20px; }
  .card.mine { background:#1E2B45; }
  p { margin:0 0 16px; word-break:break-word; }
  p:last-child { margin-bottom:0; }
  p.gap { margin:0; height:8px; }
</style>
</head><body>
  <div class="wrap">
    <p class="who">${esc(head)}</p>
$content
  </div>
</body></html>
""".trimIndent()

    /** Blank lines become spacing rather than empty paragraphs. */
    private fun body(text: String): String = text
        .split('\n')
        .joinToString("\n") { line ->
            if (line.isBlank()) "      <p class=\"gap\"></p>"
            else "      <p>${esc(line)}</p>"
        }

    /**
     * The three characters that would otherwise turn text into markup. Quotes
     * are left alone: nothing here is interpolated into an attribute.
     */
    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
