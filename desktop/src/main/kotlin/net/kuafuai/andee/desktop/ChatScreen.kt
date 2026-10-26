package net.kuafuai.andee.desktop



import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.ui.draw.drawWithContent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The phone's `Glass` palette, both of them — day (dark ink on a white card) and night — so the two
 * apps read as one product. Every name is a getter over [night], which is Compose state: whatever
 * reads a colour is subscribed, so flipping the theme redraws the whole window at once with no call
 * site told.
 */
object Palette {
    private val nightState = androidx.compose.runtime.mutableStateOf(Settings.get("theme") == "night")

    /** Night is the default, as it was before the light theme existed; only an explicit `day` is light. */
    val night: Boolean get() = nightState.value
    fun setNight(on: Boolean) {
        // The cached backdrop carries the old veil colour; drop it before anything redraws from it.
        Settings.save(mapOf("theme" to if (on) "night" else "day"))
        Backdrop.reset()
        nightState.value = on
    }

    private fun pick(day: Long, nightV: Long) = Color(if (night) nightV else day)

    val base get() = pick(0xFFFFFFFF, 0xFF000000)
    val cardSolid get() = pick(0xFFFFFFFF, 0xF2060608)
    val cardFrosted get() = pick(0xF2FFFFFF, 0x99000000)
    val panel get() = pick(0x0A000000, 0x0FFFFFFF)
    val panelEdge get() = pick(0x14000000, 0x17FFFFFF)
    val glass get() = pick(0x0F000000, 0x1FFFFFFF)
    val glassEdge get() = pick(0x24000000, 0x38FFFFFF)
    val well get() = pick(0x0D000000, 0x59000000)
    val wellEdge get() = pick(0x1A000000, 0x1AFFFFFF)
    val thumb get() = pick(0xFFFFFFFF, 0x2EFFFFFF)
    val thumbEdge get() = pick(0x2E000000, 0x40FFFFFF)
    val hairline get() = pick(0x24000000, 0x2BFFFFFF)
    val title get() = pick(0xFF0E1116, 0xFFFFFFFF)
    val label get() = pick(0xFF2B313B, 0xFFDCE1EA)
    val secondary get() = pick(0xFF5A6270, 0xFF8F97A6)
    val muted get() = pick(0xFF7D8592, 0xFF5F6775)
    /** Text sitting directly on the photograph. [muted] is chosen against a card and has no ground to stand on here. */
    val photoInk get() = pick(0xFF252A33, 0xFF98A0AE)
    val accent get() = pick(0xFF3B62E0, 0xFF7FA5FF)
    val tint get() = pick(0xFF3B62E0, 0xFF4C6FFF)
    val danger get() = pick(0xFFDC2626, 0xFFFF6B6B)
    val ok get() = pick(0xFF16A34A, 0xFF4ADE80)
    val warn get() = pick(0xFFD97706, 0xFFFBBF24)

    // kept for existing call sites
    val text get() = title
    val accentSoft get() = pick(0x263B62E0, 0x267FA5FF)
    val user get() = accent
    val error get() = danger
    val card get() = cardSolid
}

/** The project's public home, opened from the gesture line under the composer. */
private const val GITHUB_URL = "https://github.com/kuafuai/andee"
private const val DISCORD_URL = "https://discord.gg/PYujCQyqu"

private fun openLink(url: String) {
    runCatching { net.kuafuai.andee.desktop.mac.Bridge.open(url) }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ChatScreen(onOpenArtifacts: () -> Unit, onOpenTopic: (Long) -> Unit = {}, onContinueTopic: (Scrollback.Topic) -> Unit = {}, onOpenSettings: () -> Unit = {}) {
    var rows by remember { mutableStateOf(Scrollback.snapshot()) }
    var progress by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf(net.kuafuai.andee.desktop.voice.MacVoice.State.IDLE) }
    var heard by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf(Assistant.pending) }
    val attached = remember { androidx.compose.runtime.mutableStateListOf<Attachment>().apply { addAll(ChatPreview.attachments) } }
    var dropping by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(Assistant.muted) }
    var recording by remember { mutableStateOf(Meeting.isActive()) }
    // A folded stretch is one card standing where its messages stood — the phone's rule, so the
    // conversation still reads top to bottom. The sidebar's topic list is only another way in.
    val items = remember(rows) { chatItems(rows) }
    // The list is created already standing on the newest message. Opening the page used to start at the top
    // and then animate down through the whole conversation, which read as the chat scrolling itself on every
    // visit. (An item that is taller than the viewport is shown from its start, so a long last reply is read
    // from its first line.)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (items.size - 1).coerceAtLeast(0))

    // Registered on enter, removed on leave: the sidebar remounts this screen on every
    // navigation, and a listener left behind per visit is a leak that grows all day.
    androidx.compose.runtime.DisposableEffect(Unit) {
        val l: () -> Unit = { rows = Scrollback.snapshot() }
        Scrollback.addListener(l)
        onDispose { Scrollback.removeListener(l) }
    }
    LaunchedEffect(Unit) {
        rows = Scrollback.snapshot()
        Meeting.onRecordingChanged = { recording = it }
        Assistant.onProgress = { progress = it }
        Assistant.onFinal = { progress = "" }
        Assistant.onBusy = { b -> busy = b; if (!b) progress = "" }
        Assistant.onPendingChanged = { pending = Assistant.pending }
        Assistant.onMuteChanged = { muted = Assistant.muted }
        net.kuafuai.andee.desktop.voice.MacVoice.onState = { voice = it }
        net.kuafuai.andee.desktop.voice.MacVoice.onPartial = { heard = it }
        net.kuafuai.andee.desktop.voice.MacVoice.onFinal = { said ->
            heard = ""
            Thread { Assistant.say(said) }.start()
        }
    }
    LaunchedEffect(Unit) { pending = Assistant.pending }
    // A message that arrives while the page is open scrolls into view smoothly. The first run is skipped: the
    // list already stands on the newest one (above), and animating there would replay the slide.
    var shown by remember { mutableStateOf(items.size) }
    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && items.size != shown) list.animateScrollToItem(items.size - 1)
        shown = items.size
    }

    // The microphone does exactly what a tap on the floating ball does (tapToTalk): it stops whatever was
    // running, listens, and a second tap says "done".
    fun toggleMic() = tapToTalk { askToOpenSettings(onOpenSettings) }

    /** Pictures and files from any door — the clip button, a paste, a drop — end up on the same strip. */
    fun attach(items: List<Attachment>) {
        for (a in items) if (attached.size < Attachments.MAX) attached += a
    }
    fun attachFiles(files: List<java.io.File>) {
        Thread {
            val got = files.mapNotNull { Attachments.fromFile(it) }
            javax.swing.SwingUtilities.invokeLater { attach(got) }
        }.start()
    }
    fun choose() {
        Thread {
            val files = Attachments.choose()
            if (files.isNotEmpty()) attachFiles(files)
        }.start()
    }
    /** ⌘V: a picture or copied files are taken; plain text is left for the field to paste. True if handled. */
    fun pasteAttachment(): Boolean {
        val files = Attachments.filesFromClipboard()
        if (files.isNotEmpty()) { attachFiles(files); return true }
        val pic = Attachments.fromClipboard() ?: return false
        attach(listOf(pic)); return true
    }

    fun send() {
        val t = input.trim()
        // Busy blocks a new message, but not the answer to a question the brain is waiting on.
        val answering = AskGate.isWaiting
        if ((t.isEmpty() && attached.isEmpty()) || (busy && !answering)) return
        val sending = attached.toList()
        input = ""
        attached.clear()
        Thread { Assistant.say(t, attachments = sending) }.start()
    }

    // Compose moved drag-and-drop into androidx.compose.ui.draganddrop (1.7+). The
    // files themselves come from the AWT transferable, whose javaFileListFlavor is
    // the one source of truth on the desktop — no need for Compose's own drag data.
    Box(
        Modifier.fillMaxSize().dragAndDropTarget(
            shouldStartDragAndDrop = { true },
            target = remember {
                object : DragAndDropTarget {
                    override fun onStarted(event: DragAndDropEvent) { dropping = true }
                    override fun onExited(event: DragAndDropEvent) { dropping = false }
                    override fun onEnded(event: DragAndDropEvent) { dropping = false }
                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        dropping = false
                        val drop = event.nativeEvent as? java.awt.dnd.DropTargetDropEvent ?: return false
                        val flavor = java.awt.datatransfer.DataFlavor.javaFileListFlavor
                        if (!drop.transferable.isDataFlavorSupported(flavor)) return false
                        @Suppress("UNCHECKED_CAST")
                        val files = runCatching { drop.transferable.getTransferData(flavor) as List<java.io.File> }
                            .getOrNull() ?: return false
                        attachFiles(files)
                        return true
                    }
                }
            },
        ),
    ) {
    Column(Modifier.fillMaxSize()) {
        Header(
            muted = muted, onToggleMute = { Assistant.setMuted(!muted) },
            live = recording || voice == net.kuafuai.andee.desktop.voice.MacVoice.State.LISTENING,
            status = when {
                recording -> if (Strings.lang() == "zh") "正在录音" else "Recording"
                voice == net.kuafuai.andee.desktop.voice.MacVoice.State.LISTENING -> if (Strings.lang() == "zh") "正在听" else "Listening"
                busy -> if (Strings.lang() == "zh") "思考中…" else "Thinking…"
                voice == net.kuafuai.andee.desktop.voice.MacVoice.State.SPEAKING -> if (Strings.lang() == "zh") "正在说" else "Speaking"
                else -> null
            },
        )

        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            LazyColumn(
                state = list, modifier = Modifier.weight(1f).fillMaxWidth().fadeTop(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 4.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (items.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                            SceneOffer { p ->
                                ScenePresets.adoptIfMissing(p.name)
                                Thread { Assistant.say("进入「${p.title}」情景。${p.example}") }.start()
                            }
                        }
                    }
                }
                items(items, key = { it.key }) { it ->
                    when (it) {
                        is ChatItem.Message -> Bubble(it.row)
                        is ChatItem.Folded -> TopicCard(it.topic, it.count, onOpen = { onOpenTopic(it.topic.id) }, onContinue = { onContinueTopic(it.topic) })
                    }
                }
            }
        }

        if (heard.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AndeeIcon.MIC, Palette.accent, 14.dp)
                Spacer(Modifier.width(6.dp))
                Text(heard, color = Palette.text, fontSize = 13.sp)
            }
        }
        if (progress.isNotEmpty()) {
            Text(progress, color = Palette.secondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp))
        }

        pending?.let { p ->
            val zh = Strings.lang() == "zh"
            Row(
                Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp).fillMaxWidth().glassSurface(12).padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AndeeIcon.DOCUMENT, Palette.accent, 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (p) {
                        is Assistant.Pending.Edit -> if (zh) "修改「${p.page.title}」：说说想怎么改" else "Revising “${p.page.title}”: say what to change"
                        is Assistant.Pending.Topic -> if (zh) "继续「${p.topic.title}」：想从哪接着聊" else "Continuing “${p.topic.title}”: where to take it"
                    },
                    color = Palette.label, fontSize = 13.sp, modifier = Modifier.weight(1f),
                )
                Box(Modifier.clickable { Assistant.cancelPending() }.padding(4.dp)) { Icon(AndeeIcon.CLOSE, Palette.secondary, 14.dp) }
            }
        }
        InputBar(
            input = input,
            onInput = { input = it },
            listening = voice == net.kuafuai.andee.desktop.voice.MacVoice.State.LISTENING,
            canSend = (input.isNotBlank() || attached.isNotEmpty()) && (!busy || AskGate.isWaiting),
            attached = attached,
            onRemove = { attached.remove(it) },
            onAttach = { choose() },
            onPaste = { pasteAttachment() },
            onMic = { toggleMic() },
            onSend = { send() },
        )
        // The ball's whole vocabulary, pinned where the eye ends up — the phone's line under its card.
        // GitHub and Discord sit after it, the same place the phone puts them: the project's home.
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (Strings.lang() == "zh") "点小球说话 · 双击打字 · 设置里切换性格"
                else "Tap the ball to talk · double-tap to type · switch character in Settings",
                color = Palette.photoInk, fontSize = 11.sp,
            )
            Box(Modifier.clickable { openLink(GITHUB_URL) }.padding(horizontal = 5.dp, vertical = 2.dp)) { Icon(AndeeIcon.GITHUB, Palette.photoInk, 13.dp) }
            Box(Modifier.clickable { openLink(DISCORD_URL) }.padding(horizontal = 5.dp, vertical = 2.dp)) { Icon(AndeeIcon.DISCORD, Palette.photoInk, 13.dp) }
        }
    }
    if (dropping) {
        Box(
            Modifier.fillMaxSize().padding(10.dp)
                .background(Palette.accent.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                .border(2.dp, Palette.accent, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (Strings.lang() == "zh") "松开，加到这条消息里" else "Drop to add to this message", color = Palette.title, fontSize = 16.sp)
        }
    }
    }
}

/** Title, or a one-line status when something is live. Stop is with the input box, where the hand already is. */
@Composable
private fun Header(status: String?, live: Boolean, muted: Boolean, onToggleMute: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) {
            Box(Modifier.size(8.dp).background(Palette.accent, CircleShape))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            status ?: (if (Strings.lang() == "zh") "对话" else "Chat"),
            color = if (live) Palette.accent else Palette.title,
            fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        val zh = Strings.lang() == "zh"
        Row(
            Modifier.clip(RoundedCornerShape(16.dp)).clickable { onToggleMute() }.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (muted) AndeeIcon.VOLUME_OFF else AndeeIcon.VOLUME, if (muted) Palette.secondary else Palette.label, 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                if (muted) (if (zh) "静音" else "Muted") else (if (zh) "朗读" else "Voice on"),
                color = if (muted) Palette.secondary else Palette.label, fontSize = 12.sp,
            )
        }
    }
}

/** The mic and send live with the text field, as a chat app's composer does. */
@Composable
private fun InputBar(
    input: String, onInput: (String) -> Unit, listening: Boolean, canSend: Boolean,
    attached: List<Attachment>, onRemove: (Attachment) -> Unit, onAttach: () -> Unit, onPaste: () -> Boolean,
    onMic: () -> Unit, onSend: () -> Unit,
) {
    Column {
    if (attached.isNotEmpty()) AttachmentStrip(attached, onRemove)
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(ComposerFocus.pending) {
        if (ComposerFocus.pending) {
            kotlinx.coroutines.delay(120)   // the window has to be up before it can take focus
            runCatching { focus.requestFocus() }
            ComposerFocus.pending = false
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RoundButton(false.not(), onAttach, AndeeIcon.PAPERCLIP, quiet = true)
        RoundButton(listening, onMic, if (listening) AndeeIcon.STOP else AndeeIcon.MIC, alwaysClickable = true)
        Box(
            Modifier.weight(1f).background(Palette.well, RoundedCornerShape(18.dp))
                .border(1.dp, Palette.hairline, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = input,
                onValueChange = onInput,
                textStyle = TextStyle(color = Palette.text, fontSize = 15.sp),
                cursorBrush = SolidColor(Palette.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                        if (canSend) onSend(); true
                    } else if (e.type == KeyEventType.KeyDown && e.key == Key.V && (e.isMetaPressed || e.isCtrlPressed)) {
                        // A copied picture or file is ours; copied text falls through to the field.
                        onPaste()
                    } else false
                },
            )
            if (input.isEmpty()) {
                Text(
                    if (Strings.lang() == "zh") "说点什么，或输入后回车发送" else "Say something, or type and press Return",
                    color = Palette.secondary, fontSize = 14.sp,
                )
            }
        }
        RoundButton(canSend, onSend, AndeeIcon.SEND)
    }
    }
}

/** What is about to go: a thumbnail per picture, a name and size per file, each with a way to take it off. */
@Composable
private fun AttachmentStrip(items: List<Attachment>, onRemove: (Attachment) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (a in items) {
            Box {
                when (a) {
                    is Attachment.Photo -> androidx.compose.foundation.Image(
                        a.thumb, a.label,
                        Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Palette.glassEdge, RoundedCornerShape(10.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    is Attachment.FileRef -> Row(
                        Modifier.height(56.dp).widthIn(max = 240.dp).glassSurface(10).padding(start = 12.dp, end = 26.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AndeeIcon.FILE, Palette.accent, 18.dp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(a.label, color = Palette.label, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(Attachments.size(a.bytes), color = Palette.secondary, fontSize = 11.sp)
                        }
                    }
                }
                Box(
                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp)
                        .background(Color(0xCC000000), CircleShape).clickable { onRemove(a) },
                    contentAlignment = Alignment.Center,
                ) { Icon(AndeeIcon.CLOSE, Color.White, 10.dp) }
            }
        }
    }
}

@Composable
private fun RoundButton(
    enabled: Boolean, onClick: () -> Unit, icon: AndeeIcon, danger: Boolean = false, quiet: Boolean = false,
    /** Drawn as active (filled) when [enabled], but clickable either way: the microphone is both a state and a button. */
    alwaysClickable: Boolean = false,
) {
    // A quiet button is always available but never the main action: no fill, the same rim as a disabled one.
    val filled = enabled && !quiet
    Box(
        Modifier.size(40.dp)
            .background(if (!filled) Palette.well else if (danger) Palette.danger else Palette.accent, CircleShape)
            .border(1.dp, if (filled) Color.Transparent else Palette.hairline, CircleShape)
            .clickable(enabled = enabled || alwaysClickable) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, if (filled) Color(0xFF0A2540) else if (quiet) Palette.label else Palette.secondary, 18.dp)
    }
}

/** Preview-only: what the offscreen render starts the strip with. Empty in the app. */
object ChatPreview { var attachments: List<Attachment> = emptyList() }

sealed class ChatItem(val key: String) {
    class Message(val row: Scrollback.Row) : ChatItem("m${row.id}")
    class Folded(val topic: Scrollback.Topic, val count: Int) : ChatItem("t${topic.id}")
}

/**
 * Rows in order, with each folded stretch collapsed to one entry at the place of its first row.
 * A topic whose rows have all aged out of the record is not drawn — it stays reachable from the
 * sidebar and through list_topics.
 */
fun chatItems(rows: List<Scrollback.Row>): List<ChatItem> {
    val topics = Scrollback.topics().associateBy { it.id }
    val counts = rows.mapNotNull { it.topicId }.groupingBy { it }.eachCount()
    val seen = HashSet<Long>()
    val out = ArrayList<ChatItem>()
    for (r in rows) {
        val t = r.topicId
        if (t == null) { out += ChatItem.Message(r); continue }
        val topic = topics[t] ?: continue
        if (seen.add(t)) out += ChatItem.Folded(topic, counts[t] ?: 0)
    }
    return out
}

/** One finished topic, standing where its messages stood: what it was, how long, and the two ways back in. */
@Composable
private fun TopicCard(topic: Scrollback.Topic, count: Int, onOpen: () -> Unit, onContinue: () -> Unit) {
    val zh = Strings.lang() == "zh"
    val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US) }
    Column(
        Modifier.fillMaxWidth().glassPanel(16).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AndeeIcon.CHAT, Palette.secondary, 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(if (zh) "话题" else "Topic", color = Palette.secondary, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Text(
                fmt.format(java.util.Date(topic.endedAt)) + " · " + (if (zh) "$count 条" else "$count msgs"),
                color = Palette.photoInk, fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(topic.title, color = Palette.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (topic.summary.isNotBlank() && topic.summary != topic.title) {
            Spacer(Modifier.height(2.dp))
            Text(topic.summary, color = Palette.label, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (zh) "继续话题" else "Continue", color = Palette.accent, fontSize = 13.sp,
                modifier = Modifier.clickable { onContinue() }.padding(vertical = 4.dp))
            Text(if (zh) "对话记录" else "Transcript", color = Palette.accent, fontSize = 13.sp,
                modifier = Modifier.clickable { onOpen() }.padding(vertical = 4.dp))
        }
    }
}

/** Asked for by the ball's double-tap: the next time the composer is on screen, put the cursor in it. */
object ComposerFocus {
    var pending by androidx.compose.runtime.mutableStateOf(false)
}

fun noVoiceKey() = when {
    // Under CodeFlying the one key covers the voice too, and there is no voice block to point at.
    Assistant.config().mode == net.kuafuai.andee.config.BrainConfig.BRAIN_CODEFLYING ->
        if (Strings.lang() == "zh") "还没有 CodeFlying 密钥：在设置的「后端」里填上。" else "No CodeFlying key yet: add it under Backend in Settings."
    Strings.lang() == "zh" -> "还没配语音 Key：在设置的「语音」里填上。"
    else -> "No voice key yet: add it under Voice in Settings."
}

@Composable
private fun Bubble(r: Scrollback.Row) {
    val mine = r.role == Scrollback.Role.USER
    val error = r.role == Scrollback.Role.ERROR
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        SelectionContainer {
            Text(
                r.text,
                color = when { error -> Palette.error; mine -> Palette.text; else -> Palette.text },
                fontSize = 15.sp,
                lineHeight = 21.sp,
                modifier = Modifier.widthIn(max = 560.dp)
                    .background(
                        when {
                            error -> Palette.error.copy(alpha = 0.12f)
                            mine -> Palette.accentSoft
                            else -> Palette.glass
                        },
                        RoundedCornerShape(16.dp),
                    )
                    .border(1.dp, if (error) Palette.error.copy(alpha = 0.4f) else Palette.glassEdge, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
fun Pill(label: String, onClick: () -> Unit) {
    Text(
        label, color = Palette.text, fontSize = 13.sp,
        modifier = Modifier.background(Palette.panel, RoundedCornerShape(15.dp))
            .border(1.dp, Palette.hairline, RoundedCornerShape(15.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * The top of the conversation dissolves instead of being cut off: the phone's scrollback does the same under its
 * ball. A mask, not a coloured overlay, so it is right on any backdrop and in both themes.
 */
private fun Modifier.fadeTop(height: androidx.compose.ui.unit.Dp): Modifier = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = height.toPx()
        drawRect(
            brush = androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = h),
            size = androidx.compose.ui.geometry.Size(size.width, h),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

