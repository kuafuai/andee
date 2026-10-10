package net.kuafuai.andee.ui

import android.content.Context
import android.util.Log
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * One turn's log, as a full-screen page.
 *
 * The scrollback shows what was *said*; this shows what was *done*. A row is a
 * sentence the user heard, and everything the device did to earn the right to
 * say it — which tools it called, with what arguments, what came back, what it
 * was thinking, and what every request cost in prompt tokens — is in
 * `files/brain_trace.jsonl` and nowhere the user can reach. This is the page
 * that reaches it, opened by long-pressing the row.
 *
 * **Long-press, because the two other taps on a row are taken.** A single tap
 * already means "open that page again" on a page row and nothing at all on a
 * message row (a single tap in a scrollable list is how you stop a fling, see
 * [HistoryListView.attachRowGestures]); a double tap already reads the message
 * fullscreen ([TextPage]). Long-press is the one gesture on a row that was
 * still free, and it is the one that reads as "there is more here".
 *
 * It goes through [HtmlActivity] for the same reason [TextPage] does, and that
 * is mechanics rather than markup: folding the card, hanging the ball off the
 * ledge, floating the 完成 pill and reporting itself closed are all already
 * solved on that path, and none of them is worth solving twice.
 *
 * **Not everything has a log.** A row written by the hub brain, by a scene
 * change, or by a notification being read out has no local turn behind it, and
 * those rows are given `turn = null` and offer no gesture at all — see
 * [ChatHistory.Entry.turn]. A turn that *is* named but has no records left
 * (rotated out of the log by [net.kuafuai.andee.brain.BrainTrace]) gets a page
 * that says so rather than a blank one: the gesture happened, so something has
 * to explain why nothing came of it.
 *
 * Everything read out of the file is escaped. It is data twice over — the
 * model's output, and before that whatever the user or a notification said.
 */
object TracePage {

    private const val TAG = "Body"
    private const val FILE = "brain_trace.jsonl"

    /** Where built pages are staged: the same directory the model's pages use. */
    private fun dir(context: Context) = File(context.filesDir, "pages").apply { mkdirs() }

    /**
     * Write [turn]'s log out as a page and return the file, or null if the disk
     * said no — in which case the caller shows nothing rather than an empty
     * screen.
     *
     * **Blocks: call it off the main thread.** It parses however much of the log
     * belongs to this turn, which is a few kilobytes, but it does so by reading
     * a file that can be a megabyte per generation.
     *
     * One file per turn, named after it, so opening the same row twice
     * overwrites rather than accumulating. The name is safe as a filename
     * because a turn id is `"<processStart>-<n>"` — see
     * [net.kuafuai.andee.brain.BrainTrace] — and so contains no separator.
     * `ChatHistory.wipe` already deletes this whole directory on a factory
     * reset.
     */
    fun write(context: Context, turn: String): File? = runCatching {
        File(dir(context), "trace_$turn.html").also {
            it.writeText(html(AppLocale.wrap(context), read(context, turn)))
        }
    }.getOrElse {
        Log.w(TAG, "trace page: could not write: ${it.message}")
        null
    }

    /**
     * The records belonging to [turn], oldest first.
     *
     * **Both generations of the log are read.** [net.kuafuai.andee.brain.BrainTrace]
     * bounds itself by renaming the live file to `.1` and starting a fresh one,
     * so a turn whose records straddle that moment — or one old enough to have
     * been rotated since — is split across two files, and reading only the live
     * one would show half a turn with nothing to say it was half.
     *
     * Streamed line by line rather than read whole. The whole point of opening
     * one row's log is that it is one turn's worth; nothing here should hold two
     * megabytes of JSON in memory to find eight lines.
     */
    private fun read(context: Context, turn: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        // `.1` first: it holds the older records, so appending in this order
        // gives one chronological list across the rotation boundary.
        for (name in listOf("$FILE.1", FILE)) {
            val f = File(context.filesDir, name)
            if (!f.exists()) continue
            runCatching {
                f.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
                    if (o.optString("turn") == turn) out.add(o)
                }
            }.onFailure { Log.w(TAG, "trace page: read $name: ${it.message}") }
        }
        return out
    }

    /** The whole document. */
    private fun html(lctx: Context, rows: List<JSONObject>): String {
        val head = if (rows.isEmpty()) lctx.getString(R.string.tracepage_gone)
        else SimpleDateFormat("MM-dd HH:mm:ss", AppLocale.localeOf(lctx))
            .format(Date(rows.minOf { it.optLong("ts") }))

        val body = if (rows.isEmpty()) """
      <div class="card">
        <p>${esc(lctx.getString(R.string.tracepage_gone_hint))}</p>
      </div>"""
        else rows.joinToString("\n") { block(lctx, it) }

        return """
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
  .card { background:#1E293B; border:1px solid rgba(148,163,184,.18);
    border-radius:14px; padding:16px 18px; margin:0 0 14px; }
  .kind { margin:0 0 10px; font-size:12px; color:#94A3B8; }
  .kind b { color:#CBD5E1; font-weight:500; }
  /* The rail is what ties a tool to the step that asked for it: indented, and
     coloured, so a turn reads as blocks rather than one grey column. */
  .card.tool { margin-left:16px; border-left:3px solid #378ADD; }
  .card.turn { border-left:3px solid #4ADE80; }
  .card.end { border-left:3px solid #E24B4A; }
  p { margin:0 0 10px; word-break:break-word; }
  p:last-child { margin-bottom:0; }
  .said { font-size:15px; }
  .thought { color:#94A3B8; font-size:14px; }
  .name { font-family:ui-monospace,Menlo,Consolas,monospace; font-size:14px;
    color:#85B7EB; margin:0 0 8px; word-break:break-all; }
  .why { color:#F09595; }
  pre { margin:0 0 10px; padding:10px 12px; background:#0B1220; border-radius:10px;
    font-family:ui-monospace,Menlo,Consolas,monospace; font-size:13px;
    line-height:1.5; white-space:pre-wrap; word-break:break-word; color:#CBD5E1; }
  pre:last-child { margin-bottom:0; }
</style>
</head><body>
  <div class="wrap">
    <p class="who">${esc(head)}</p>
$body
  </div>
</body></html>
""".trimIndent()
    }

    /**
     * One record, as its own card. Unknown event kinds are skipped, not guessed at.
     *
     * The `turn` card's heading is the **reason** the turn happened, not the word
     * "started", and its body carries **no** label. Both come from the same
     * measurement: a `turn` record's `text` is the *prompt*, which for a user turn
     * is the user's own sentence and for the other four is a note the device
     * wrote to itself. Labelling that "Said" — as this page first did, because
     * `step` and `turn` both keep their prose in `text` — puts the user's words
     * in the assistant's mouth on the very first card. The heading is where the
     * origin belongs, and `kind` is the field that knows it.
     */
    private fun block(lctx: Context, r: JSONObject): String {
        val ev = r.optString("ev")
        return when (ev) {
            "turn" -> card(
                "turn",
                kind(kindOf(lctx, r.optString("kind"))),
                para(null, "said", r.optString("text")),
            )

            "step" -> card(
                "step",
                kind(stepHead(lctx, r)),
                para(lctx.getString(R.string.tracepage_said), "said", r.optString("content")) +
                    para(lctx.getString(R.string.tracepage_thought), "thought", r.optString("reasoning")),
            )

            "tool" -> card(
                "tool",
                "<p class=\"name\">${esc(r.optString("name"))}" +
                    " <span class=\"why\">${r.optLong("ms")} ms</span></p>",
                pre(r.optString("args")) + pre(r.optString("result")),
            )

            "end" -> card(
                "end",
                kind(lctx.getString(R.string.tracepage_end)),
                "<p class=\"why\">${esc(r.optString("why"))}</p>",
            )

            else -> ""
        }
    }

    /**
     * [net.kuafuai.andee.brain.LocalBrain.TurnKind] as a sentence.
     *
     * A `when` over the raw name rather than an enum: this reads a file, and a
     * record written by a build that knew a kind this one does not must render as
     * the neutral heading rather than crash the page. New kinds are not
     * hypothetical — `NOTIFY` and `HARVEST` were both added after the first
     * version of this file.
     */
    private fun kindOf(lctx: Context, kind: String): String = lctx.getString(
        when (kind) {
            "USER" -> R.string.tracepage_kind_user
            "WAKE" -> R.string.tracepage_kind_wake
            "NOTIFY" -> R.string.tracepage_kind_notify
            "SWEEP" -> R.string.tracepage_kind_sweep
            "HARVEST" -> R.string.tracepage_kind_harvest
            else -> R.string.tracepage_kind_other
        }
    )

    /**
     * `Step 2 · 44,005 tokens · 1 tool call`.
     *
     * The token count is the point of showing a step at all: it is what every
     * request carries as context, and it is the number that explains why this
     * device triages notifications without one (see
     * [net.kuafuai.andee.brain.NotificationTriage]).
     */
    private fun stepHead(lctx: Context, r: JSONObject): String {
        val parts = ArrayList<String>()
        parts.add(lctx.getString(R.string.tracepage_step, r.optInt("step", 0)))
        val tokens = r.optInt("prompt_tokens", 0)
        if (tokens > 0) {
            parts.add(lctx.getString(R.string.tracepage_tokens, "%,d".format(tokens)))
        }
        parts.add(
            if (r.optInt("calls", 0) > 0) lctx.getString(R.string.tracepage_calls, r.optInt("calls"))
            else lctx.getString(R.string.tracepage_no_calls)
        )
        return parts.joinToString(" · ")
    }

    private fun card(cls: String, head: String, inner: String) = """
      <div class="card $cls">
        $head
$inner
      </div>"""

    private fun kind(text: String) = "<p class=\"kind\"><b>${esc(text)}</b></p>"

    /**
     * A labelled paragraph, skipped entirely when there is nothing to show.
     *
     * A null [label] means the text stands on its own under the card's heading.
     * Used once, on the `turn` card, where a label would have to name whoever
     * spoke — and the heading already does. See [block].
     */
    private fun para(label: String?, cls: String, text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        val head = if (label == null) "" else "<p class=\"kind\">${esc(label)}</p>\n        "
        return "${head}<p class=\"$cls\">${esc(t)}</p>"
    }

    /** Monospaced and pre-wrapped — arguments and results are JSON, not prose. */
    private fun pre(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        return "<pre>${esc(t)}</pre>"
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
