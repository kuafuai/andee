package net.kuafuai.andee.stage

/**
 * The device's half of a stage page: everything but the fragment Andee wrote. One builder for both bodies, so
 * a stage looks and behaves the same on the phone's WebView and the Mac's WKWebView.
 *
 * Charset, viewport, a Content-Security-Policy with no network for scripts or fetches (https images and media
 * may load), the theme's colours as CSS variables, the house classes in the glass style, `window.andee`, and —
 * only when the fragment uses it — Chart.js, inlined with its defaults set to the theme.
 *
 * `andee.say(text)` calls [sayJs] (the body's own channel); each body accepts it only right after a real click
 * or tap on the stage, checked natively, so a page script that calls the channel directly gets nowhere.
 */
object StageShell {

    /** The colours the shell needs, as CSS colour strings; each body maps its own palette onto these. */
    data class Theme(
        val night: Boolean,
        val ink: String, val ink2: String, val muted: String, val accent: String,
        val ok: String, val warn: String, val danger: String,
        val glass: String, val edge: String, val well: String,
    )

    /** Pages load from this origin, so https images and media are not mixed content. */
    const val BASE_URL = "https://stage.andee.local/"

    /**
     * @param sayJs a JS expression that delivers `t` (a string) to the body, e.g.
     *   `AndeeStage.say(t)` on the phone or `window.webkit.messageHandlers.andee.postMessage(t)` on the Mac.
     */
    fun html(fragment: String, theme: Theme, lang: String, sayJs: String): String = """<!doctype html><html lang="$lang"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: data: blob:; media-src https: data: blob:; style-src 'unsafe-inline'; script-src 'unsafe-inline'; font-src data:; connect-src 'none'">
<style>
:root{--ink:${theme.ink};--ink2:${theme.ink2};--muted:${theme.muted};--accent:${theme.accent};
--ok:${theme.ok};--warn:${theme.warn};--danger:${theme.danger};--glass:${theme.glass};--edge:${theme.edge};--well:${theme.well};color-scheme:${if (theme.night) "dark" else "light"}}
*{box-sizing:border-box}html,body{margin:0;background:transparent;overflow-x:hidden}canvas,svg{max-width:100%}
body{color:var(--ink2);font:15px/1.5 system-ui,-apple-system,"PingFang SC","Noto Sans CJK SC",sans-serif;padding:6px 14px 24px;-webkit-tap-highlight-color:transparent}
h1,h2,h3{color:var(--ink);margin:.2em 0 .35em;line-height:1.25}h1{font-size:24px}h2{font-size:19px}h3{font-size:16px}
p{margin:.35em 0}a{color:var(--accent);text-decoration:none}img,video{max-width:100%;border-radius:14px}
.card{background:var(--glass);border-radius:20px;padding:14px 16px;margin:10px 0;box-shadow:inset 0 1px 0 var(--edge)}
.stat{font-size:34px;font-weight:700;color:var(--ink);line-height:1.1}.label{font-size:12px;color:var(--muted);letter-spacing:.04em}
.muted{color:var(--muted)}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.grid>.card{margin:0;min-width:0}
.row{display:flex;justify-content:space-between;align-items:center;gap:10px;padding:7px 0}
.pill{display:inline-block;padding:3px 10px;border-radius:999px;background:var(--well);font-size:12px;color:var(--ink2)}
.progress{height:6px;border-radius:3px;background:var(--well);overflow:hidden}.progress>i{display:block;height:100%;background:var(--accent);border-radius:3px}
.btn{display:inline-block;border:0;border-radius:999px;padding:9px 16px;background:var(--accent);color:#fff;font:600 14px system-ui,sans-serif;cursor:pointer}
ul.list,ol.list{padding-left:1.1em;margin:.3em 0}
</style>
<script>window.andee={theme:"${if (theme.night) "night" else "day"}",lang:"$lang",say:function(t){try{t=String(t);$sayJs}catch(e){}}};</script>
${chartScript(fragment)}
</head><body>$fragment</body></html>"""

    @Volatile private var chartJs: String? = null

    /**
     * Chart.js 4.4.1 (MIT, `stage/chart.umd.js` on the classpath), inlined only when the fragment uses it, so a
     * stage without a chart does not carry 200 KB. Inline because the page may load no script from the network.
     */
    private fun chartScript(fragment: String): String {
        if (!fragment.contains("Chart")) return ""
        val js = chartJs ?: runCatching {
            StageShell::class.java.getResourceAsStream("/stage/chart.umd.js")!!.bufferedReader().use { it.readText() }
        }.getOrNull()?.also { chartJs = it } ?: return ""
        return "<script>$js</script><script>(function(){var s=getComputedStyle(document.documentElement);" +
            "Chart.defaults.color=s.getPropertyValue('--muted');Chart.defaults.borderColor=s.getPropertyValue('--well');" +
            "Chart.defaults.font.family='system-ui,-apple-system,sans-serif';Chart.defaults.maintainAspectRatio=false;" +
            "Chart.defaults.plugins.legend.labels.boxWidth=12;})();</script>"
    }
}

/** The line under the stage reads the last message a sentence at a time; these are the cuts and the timing. */
object StageLine {
    /** About two seconds a sentence, a little more for a long one; never so long the line looks stuck. */
    fun dwell(s: String): Long = (SENTENCE_MS + (s.length - 20).coerceAtLeast(0) * PER_CHAR_MS).coerceAtMost(MAX_SENTENCE_MS)

    /** A reply cut where a reader would pause: Chinese and English sentence ends, and line breaks. */
    fun sentences(text: String): List<String> =
        Regex("""[^。！？!?；;\n]+[。！？!?；;]*|(?<=[.!?])\s+""").findAll(text.trim())
            .map { it.value.trim() }
            .flatMap { piece -> piece.split(Regex("""(?<=[.!?])\s+(?=[A-Z0-9"“‘(])""")).asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    const val SENTENCE_MS = 2_000L
    const val PER_CHAR_MS = 40L
    const val MAX_SENTENCE_MS = 4_000L

    /** How long the last sentence stays before the line goes. */
    const val LINGER_MS = 4_000L
}
