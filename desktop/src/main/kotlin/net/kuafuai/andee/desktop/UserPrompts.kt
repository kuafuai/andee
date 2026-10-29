package net.kuafuai.andee.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.kuafuai.andee.brain.SpeechMood
import net.kuafuai.andee.desktop.voice.MacVoice
import net.kuafuai.andee.net.ToolSchemas
import net.kuafuai.andee.util.Log
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * `ui.ask` / `ui.alert` on the Mac: a card with tappable buttons, read aloud.
 *
 * Mirrors the phone's CardUi.ask/alert, with two deliberate differences.
 * The `heard` (voice answer) half is not here yet: the phone reads the question
 * then opens its microphone, which is safe there because the audio path has
 * echo cancellation — the Mac's does not (that is why the desktop is push-to-
 * talk), so an open mic would transcribe Andee's own voice reading the question.
 * And the card sits in the chat window rather than anchored to the ball.
 */
class UiTools : DesktopBrainHost.ToolProvider {

    override fun handles(method: String) = method.startsWith("ui.")



    override fun turnStarted(kind: String) = Pages.beginTurn()

    override fun dispatch(method: String, p: JSONObject, callId: String, silent: Boolean, untrusted: Boolean): Any? = when (method) {
        "ui.show_html" -> showHtml(p)
        "ui.list_pages" -> listPages()
        "ui.get_page" -> getPage(p)
        "ui.list_topics" -> listTopics()
        "ui.get_topic" -> getTopic(p)
        "ui.ask" -> {
            val question = p.optString("question").trim().ifEmpty { throw IllegalArgumentException("ui.ask requires question") }
            // Caller-supplied buttons pass through untouched — those are the model's
            // words. None sent: our own pair in the interface language.
            val buttons = p.optJSONArray("buttons")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: listOf(if (Strings.lang() == "zh") "是" else "Yes", if (Strings.lang() == "zh") "否" else "No")
            val timeoutMs = p.optLong("timeout_ms", 30_000L).coerceIn(5_000, 120_000)
            val r = AskGate.ask(question, buttons, timeoutMs)
            // The question and the answer are part of the conversation, as on the
            // phone — otherwise the card comes and goes and the log shows the
            // brain acting on a choice nobody saw being made.
            Scrollback.add(Scrollback.Role.ASSISTANT, SpeechMood.parse(question).text)
            val reply = r.button ?: r.heard
            if (!reply.isNullOrBlank()) Scrollback.add(Scrollback.Role.USER, reply)
            JSONObject()
                .put("button", r.button)
                .put("how", r.how)
                .put("answered", r.button != null || r.heard != null)
                .apply { r.heard?.let { put("heard", it) } }
        }
        "ui.nudge" -> {
            // Malformed → throws for the model to fix; over budget → a normal result that says why.
            val n = Nudges.book.parse(p, untrusted)
            val refusal = Nudges.book.admit(n, Nudges.level(), Nudges.muted())
            if (refusal != null) {
                JSONObject().put("queued", false).put("reason", refusal)
            } else {
                Nudges.post(n)
                JSONObject().put("queued", true).put(
                    "note",
                    "Queued. It goes up when the user is free and is dropped after its ttl. You will hear back only " +
                        "if they tap a button that is not `dismiss` — do not wait for it, and do not also say it out loud.",
                )
            }
        }
        "ui.alert" -> {
            val text = p.optString("text").trim().ifEmpty { throw IllegalArgumentException("ui.alert requires text") }
            val timeoutMs = p.optLong("timeout_ms", 30_000L).coerceIn(5_000, 120_000)
            val r = AskGate.alert(text, timeoutMs)
            JSONObject().put("how", r.how).put("read", true)
        }
        else -> throw IllegalArgumentException("unknown method $method")
    }

    // ---- pages: the artifacts it made --------------------------------------

    private fun showHtml(p: JSONObject): JSONObject {
        val html = p.optString("html").ifEmpty { throw IllegalArgumentException("ui.show_html requires html") }
        val defaultTitle = if (Strings.lang() == "zh") "AI 界面" else "AI page"
        val title = p.optString("title").ifEmpty { defaultTitle }
        // Ownership is decided here, never by the model: get_page armed a group,
        // an explicit `revise` wins, and either way the target is consumed.
        // `revise` is a page id (what list_pages shows), not a group id: translate it, or an id that
        // happens to equal some other artifact's group files the page under the wrong artifact.
        // An id that names nothing is ignored rather than refused — the page is still worth showing.
        val explicit = p.optString("revise").trim().toLongOrNull()?.let { Pages.pageById(it)?.groupId }
        val group = Pages.armOrResolve(explicit)
        val page = Pages.add(title, html, group) ?: throw IllegalStateException("the page could not be saved")
        // The phone renders in an in-app WebView; the Mac's honest first cut opens
        // the page in the default browser. The shelf (list_pages / get_page / the
        // per-turn index) is what the model reads back, and that works the same.
        runCatching { net.kuafuai.andee.desktop.mac.Bridge.open(page.file!!.absolutePath) }
            .onFailure { Log.w("UiTools", "could not open the page: ${it.message}") }
        Scrollback.add(Scrollback.Role.ASSISTANT, title)
        return JSONObject().put("opened", true).put("id", page.id).put("title", title)
            .put("version", page.version).put("closed", false)
    }

    private fun listPages(): JSONObject {
        val arr = org.json.JSONArray()
        for (e in Pages.pages()) {
            arr.put(
                JSONObject().put("id", e.id).put("title", e.title)
                    .put("created", LocalTime.format(e.ts)).put("bytes", e.file?.length() ?: 0L)
                    .put("available", e.file != null).put("version", e.version)
                    .put("versions", Pages.versionsOf(e.groupId).size),
            )
        }
        return JSONObject().put("items", arr).put("count", arr.length()).put(
            "note",
            when {
                arr.length() == 0 -> "No pages have been made yet. Build one with show_html; do not describe a page that does not exist."
                else -> "Newest first, one row per page — the id is the CURRENT version. available:false means the page's file is gone. Use get_page for the source of one you are going to change."
            },
        )
    }

    private fun getPage(p: JSONObject): JSONObject {
        val id = p.optLong("id", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
            ?: throw IllegalArgumentException("ui.get_page requires integer id, from list_pages")
        val entry = Pages.pageById(id)
        val file = entry?.file
        return when {
            entry == null -> JSONObject().put("found", false)
                .put("note", "No page has that id. list_pages shows what there is — take an id from there, and do not make one up.")
            file == null -> JSONObject().put("found", false).put("id", entry.id).put("title", entry.title)
                .put("note", "That page was made, but its file is gone — there is nothing to read back. Say so; do not reconstruct it from impression.")
            else -> {
                val html = runCatching { file.readText() }.getOrNull()
                if (html == null) {
                    JSONObject().put("found", false).put("id", entry.id)
                        .put("note", "The page is listed but its file could not be read. Do not invent its contents.")
                } else {
                    // Reading back has one purpose: the show_html that follows is the revision.
                    Pages.armGroup(entry.groupId)
                    val cap = net.kuafuai.andee.ui.ScrollbackLimits.ARTIFACT_SOURCE_MAX
                    val cut = html.length > cap
                    JSONObject().put("found", true).put("id", entry.id).put("title", entry.title)
                        .put("created", LocalTime.format(entry.ts)).put("truncated", cut)
                        .put("version", entry.version).put("versions", Pages.versionsOf(entry.groupId).size)
                        .put("html", if (cut) html.take(cap) else html)
                        .put("note", "The page's current source, verbatim. To change it, send the whole revised page with show_html — not a diff. This call showed the user nothing." + if (cut) " Cut at $cap characters; rebuild the missing tail in the same style." else "")
                }
            }
        }
    }

    // ---- topics: the finished conversations, read back ----------------------

    private fun listTopics(): JSONObject {
        val arr = org.json.JSONArray()
        for (t in Scrollback.topics()) {
            arr.put(
                JSONObject().put("id", t.id).put("title", t.title).put("ended", LocalTime.format(t.endedAt))
                    .put("messages", Scrollback.snapshot().count { it.topicId == t.id })
                    .put("available", Scrollback.topicText(t.id) != null),
            )
        }
        return JSONObject().put("items", arr).put("count", arr.length()).put(
            "note",
            when {
                arr.length() == 0 -> "No conversations have been put away yet. Nothing is missing."
                else -> "Newest first. These are FINISHED conversations; the one-line summary in your context is not the whole story. Read one back with get_topic before answering about a detail."
            },
        )
    }

    private fun getTopic(p: JSONObject): JSONObject {
        val id = p.optLong("id", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
            ?: throw IllegalArgumentException("ui.get_topic requires integer id, from list_topics")
        val topic = Scrollback.topics().firstOrNull { it.id == id }
        val text = Scrollback.topicText(id)
        return when {
            topic == null -> JSONObject().put("found", false)
                .put("note", "No topic has that id. list_topics shows what there is — take an id from there, and do not make one up.")
            text == null -> JSONObject().put("found", false).put("id", topic.id).put("title", topic.title)
                .put("note", "That topic exists — 「${topic.title}」 — but its text could not be read, so the details are gone. Say so; do not reconstruct it.")
            else -> {
                val cap = net.kuafuai.andee.ui.ScrollbackLimits.TOPIC_TEXT_MAX
                val cut = text.length > cap
                JSONObject().put("found", true).put("id", topic.id).put("title", topic.title)
                    .put("ended", LocalTime.format(topic.endedAt)).put("truncated", cut)
                    .put("text", if (cut) text.take(cap) else text)
                    .put("note", "The conversation as it happened. This call showed the user nothing." + if (cut) " Cut at $cap characters." else "")
            }
        }
    }
}

/**
 * The card gate. The brain's tool thread blocks in [ask]/[alert] until the user
 * answers or the timeout passes — a timeout is "no answer", never a guess.
 */
object AskGate {

    data class Result(val button: String?, val heard: String?, val how: String)

    class Pending(
        val question: String?,
        val buttons: List<String>,
        val answer: CompletableFuture<Result>,
        val deadline: Long,
    )

    @Volatile var current: Pending? = null
        private set
    @Volatile var onChange: () -> Unit = {}

    /** Compose-readable twin of "a question card is up", so the input box can stay open to answer it. */
    private val waiting = androidx.compose.runtime.mutableStateOf(false)
    val isWaiting: Boolean get() = waiting.value

    /** [speak] = false for a card the device raises itself (the launch-time version reminder): nobody asked. */
    fun ask(question: String, buttons: List<String>, timeoutMs: Long, speak: Boolean = true): Result =
        run(question, buttons, timeoutMs, speak)

    fun alert(text: String, timeoutMs: Long): Result =
        run(text, emptyList(), timeoutMs)

    private fun run(question: String, buttons: List<String>, timeoutMs: Long, speak: Boolean = true): Result {
        val p = Pending(question, buttons, CompletableFuture(), System.currentTimeMillis() + timeoutMs)
        synchronized(this) {
            current?.answer?.complete(Result(null, null, "superseded"))
            current = p
        }
        waiting.value = p.buttons.isNotEmpty()
        onChange()
        // Voice-first: the question is read out, exactly as the phone says it.
        if (speak) MacVoice.speak(question)
        val r = runCatching { p.answer.get(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrDefault(Result(null, null, "timeout"))
        synchronized(this) { if (current === p) current = null }
        waiting.value = current?.buttons?.isNotEmpty() == true
        onChange()
        return r
    }

    fun answer(button: String?) {
        val p = current ?: return
        p.answer.complete(Result(button, null, "button"))
    }

    /**
     * Answer the card that is up with words, typed or spoken. A sentence that is (nearly) just a
     * button label presses it; any other sentence to a question is the answer too, and travels back
     * in the tool result for the model to judge against the options it offered.
     *
     * This is why those words must never start a turn of their own: the brain is blocked inside
     * the very tool call this card belongs to, so a new turn would queue behind it while it waits
     * for this answer — each waiting on the other, until the card times out. Same rule and same
     * matching as the phone's `CardUi.tryAnswerByVoice`.
     *
     * @return true when the card took the words, so the caller must not send them as a message.
     */
    fun tryAnswer(said: String): Boolean {
        val text = said.trim()
        val p = current ?: return false
        if (text.isEmpty() || p.answer.isDone) return false
        // Alerts have no answer to give; a sentence said over one is an ordinary message.
        if (p.buttons.isEmpty()) return false
        val spoken = normalize(text)
        val hit = p.buttons.firstOrNull { label ->
            val l = normalize(label)
            l.isNotEmpty() && (spoken == l || (spoken.contains(l) && l.length * 2 >= spoken.length))
        }
        return if (hit != null) p.answer.complete(Result(hit, text, "voice")) .let { true }
        else p.answer.complete(Result(null, text, "voice_other")).let { true }
    }

    /** Lower-cased, spaces and punctuation dropped, so "好的。" equals "好的". */
    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * The user closed the card instead of answering it. The same outcome as waiting out the timeout —
     * no button, no words — but the result says which of the two happened, so the brain can tell
     * "not now" from "never answered".
     */
    fun dismiss() {
        current?.answer?.complete(Result(null, null, "closed"))
    }
}

/** The card: [Pending.question] null means an alert (one 知道了 control). */
@Composable
fun AskCard(p: AskGate.Pending) {
    val zh = Strings.lang() == "zh"
    fun t(a: String, b: String) = if (zh) a else b
    val isAlert = p.buttons.isEmpty()
    run {
        Box(
            Modifier.fillMaxWidth()
                .background(Palette.card, RoundedCornerShape(18.dp))
                .border(1.dp, Palette.hairline, RoundedCornerShape(18.dp)),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Held clear of the close button in the corner, which sits over the first line's end.
                Text(p.question ?: "", color = Palette.text, fontSize = 16.sp, modifier = Modifier.padding(end = 22.dp))
                if (isAlert) {
                    Row(Modifier.align(Alignment.End)) {
                        Pill(t("知道了", "OK")) { AskGate.answer(null) }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        p.buttons.forEachIndexed { i, b ->
                            val primary = i == 0
                            Text(
                                b, color = if (primary) Color(0xFF0A2540) else Palette.text, fontSize = 15.sp,
                                modifier = Modifier.fillMaxWidth()
                                    .background(if (primary) Palette.accent else Palette.panel, RoundedCornerShape(12.dp))
                                    .border(1.dp, if (primary) Color.Transparent else Palette.hairline, RoundedCornerShape(12.dp))
                                    .clickable { AskGate.answer(b) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
            // The way out, in the corner. A question is read out loud and then waits for its timeout,
            // so a card the user is done with has to be closable by hand — and closing it stops the
            // reading, the same as a tap on the ball does.
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp)
                    .clickable { MacVoice.stopSpeaking(); AskGate.dismiss() }
                    .padding(6.dp),
            ) { Icon(AndeeIcon.CLOSE, Palette.secondary, 14.dp) }
        }
    }
}
