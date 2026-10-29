package net.kuafuai.andee.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun main() {
    // Intel Macs used to crash inside skiko's Metal backend (SIGILL in
    // MetalRedrawer.createMetalDevice) under macOS 26, so the app had to draw the
    // whole window on the CPU — which is why a full-screen window felt so heavy.
    // skiko 0.9.22 (Compose 1.9) fixed that; verified here with a minimal Metal
    // window, so the GPU is the default again on every machine. Keep the CPU path
    // reachable for comparison or for a machine where Metal misbehaves:
    //   -Dandee.software=true
    if (System.getProperty("andee.software").toBoolean()) {
        System.setProperty("skiko.renderApi", "SOFTWARE")
    }
    installFileLog()
    installUiLagLog()
    net.kuafuai.andee.util.Log.i("Andee", "starting, data in ${AppDirs.root}")
    Assistant.askApproval = { ApprovalGate.request(it) }
    MacBall.restoreLook()
    Assistant.start()
    Peers.startIfEnabled()
    application {
        var pending by remember { mutableStateOf(ApprovalGate.current) }
        // The chat window comes and goes (the ball is always there); the nav says what it shows.
        var chat by remember { mutableStateOf(true) }
        val nav = remember { Nav() }
        ApprovalGate.onChange = { pending = ApprovalGate.current }
        var ballWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        var chatWindow by remember { mutableStateOf<java.awt.Window?>(null) }
        var ask by remember { mutableStateOf(AskGate.current) }
        AskGate.onChange = { ask = AskGate.current }
        // A click aimed at another app must not land on Andee: both windows step aside for the instant of it.
        // (Native, not Window.opacity: AWT refuses that on the decorated chat window.)
        // Like the phone folding its card before it drives another app: the chat window would cover
        // that app, and while it is frontmost the brain's keystrokes would land in Andee's own input.
        // The ball stays; a long-press or a Dock click brings the chat back.
        Assistant.yieldScreen = { if (chat) { chat = false; Thread.sleep(220) } }
        Assistant.hideWindows = { hide -> net.kuafuai.andee.desktop.mac.Bridge.setOwnAlpha(if (hide) 0.0 else 1.0) }

        // Bringing the window up is two things: make it visible, then pull the app forward (an
        // accessory-style ball never owns the foreground, so a visible window can still sit behind).
        var frontTick by remember { mutableStateOf(0) }
        fun openChat(screen: String = "chat", typing: Boolean = false) {
            chat = true
            nav.go(screen)
            if (typing) ComposerFocus.pending = true
            frontTick++
        }
        Assistant.openScreen = { s -> javax.swing.SwingUtilities.invokeLater { openChat(s) } }
        LaunchedEffect(frontTick) {
            if (frontTick == 0) return@LaunchedEffect
            delay(60)
            chatWindow?.let { w ->
                if (w is java.awt.Frame && (w.extendedState and java.awt.Frame.ICONIFIED) != 0) w.extendedState = java.awt.Frame.NORMAL
                w.toFront()
            }
            runCatching {
                val d = java.awt.Desktop.getDesktop()
                if (d.isSupported(java.awt.Desktop.Action.APP_REQUEST_FOREGROUND)) d.requestForeground(true)
            }
        }
        // A click on the Dock icon. With the window closed there is nothing for the system to raise,
        // so without this the icon opened it only when it happened to still be on screen.
        DisposableEffect(Unit) {
            val d = runCatching { java.awt.Desktop.getDesktop() }.getOrNull()
            val l = java.awt.desktop.AppReopenedListener { javax.swing.SwingUtilities.invokeLater { openChat() } }
            runCatching { if (d?.isSupported(java.awt.Desktop.Action.APP_EVENT_REOPENED) == true) d.addAppEventListener(l) }
            onDispose { runCatching { d?.removeAppEventListener(l) } }
        }

        FloatingBall(
            actions = ballActions(onType = { openChat(typing = true) }, onNoVoice = { askToOpenSettings { openChat("settings") } })
                .let { a -> BallActions(a.onTap, a.onDoubleTap, a.onTripleTap) { openChat() } },
            onWindow = { ballWindow = it },
        )
        BallCaptionWindow(ballWindow, suppressed = pending != null || ask != null)
        PromptWindow(ballWindow, pending, ask)
        // Bubbles stand beside the ball; with the chat window in front they are a scrollback row instead.
        Nudges.ballUp = { ballWindow != null }
        Nudges.chatInFront = { chat && chatWindow?.isActive == true }
        BubbleWindow(ballWindow)

        Window(
            onCloseRequest = { chat = false },
            visible = chat,
            title = "Andee",
            // Big enough that the conversation, the ball above it and the sidebar all breathe on a laptop; never
            // past 90% of the screen it opens on.
            state = rememberWindowState(width = defaultChatSize().first, height = defaultChatSize().second),
            resizable = true,
        ) {
            chatWindow = window
            window.minimumSize = java.awt.Dimension(760, 520)
            MenuBar {
                Menu("Andee") {
                    Item(if (Strings.lang() == "zh") "新对话" else "New chat", onClick = { Assistant.newConversation(); chat = true; nav.go("chat") },
                        shortcut = androidx.compose.ui.input.key.KeyShortcut(androidx.compose.ui.input.key.Key.N, meta = true))
                    Item(if (Strings.lang() == "zh") "显示动态" else "Show timeline", onClick = { chat = true; nav.go("chat") })
                    Item(if (Strings.lang() == "zh") "设置" else "Settings", onClick = { chat = true; nav.go("settings") },
                        shortcut = androidx.compose.ui.input.key.KeyShortcut(androidx.compose.ui.input.key.Key.Comma, meta = true))
                    Item(if (Strings.lang() == "zh") "退出 Andee" else "Quit Andee", onClick = ::exitApplication,
                        shortcut = androidx.compose.ui.input.key.KeyShortcut(androidx.compose.ui.input.key.Key.Q, meta = true))
                }
            }
            AppRoot(nav)
        }
    }
}

/**
 * Writes UI stalls to `andee.log`: how long an empty task waited in the event queue
 * before the queue got to it.
 *
 * "It feels laggy" on a Mac that paints in software is invisible from the outside —
 * the process looks idle while the window is frozen, and the only person driving the
 * window is the one who is complaining. A number here is the stall itself, not a
 * proxy for it: if the queue took 200 ms to run an empty task, that click really did
 * wait 200 ms.
 */
private fun installUiLagLog() {
    // java.management is not in the jlink'd runtime, so no ManagementFactory here:
    // the heap delta is enough to tell a garbage collection from a real stall.
    val rt = Runtime.getRuntime()
    fun usedHeap() = rt.totalMemory() - rt.freeMemory()
    kotlin.concurrent.thread(name = "UiLag", isDaemon = true) {
        while (true) {
            val t0 = System.nanoTime()
            val heap0 = usedHeap()
            runCatching { javax.swing.SwingUtilities.invokeAndWait {} }
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (ms >= 60) {
                // A long stall is never the rasteriser — software rendering costs tens of
                // milliseconds, not seconds. Keep the stack: without it a 2 s pause is a
                // number with no owner, and the guess costs another round trip.
                val dHeap = usedHeap() - heap0
                val msg = buildString {
                    append("UI thread stalled ${ms}ms")
                    append(" [heap ${rt.totalMemory() / 1048576}MB, ${dHeap / 1024}KB]")
                    if (ms >= 400) {
                        append('\n')
                        val edt = Thread.getAllStackTraces().entries
                            .firstOrNull { it.key.name.contains("AWT-EventQueue") }
                        if (edt == null) append("  (no EDT stack)")
                        else edt.value.take(16).forEach { append("  at ").append(it).append('\n') }
                    }
                }
                net.kuafuai.andee.util.Log.w("UiLag", msg)
            }
            Thread.sleep(150)
        }
    }
}

/**
 * Shared-module logging goes to `andee.log` next to the data, rotated at
 * 2 MB. A packaged app has no terminal, so this file is the only place a
 * failure leaves a trace.
 */
private fun installFileLog() {
    val f = AppDirs.file("andee.log")
    if (f.length() > 2_000_000) f.renameTo(File(f.parentFile, "andee.log.1"))
    val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    net.kuafuai.andee.util.Log.sink = object : net.kuafuai.andee.util.Log.Sink {
        override fun log(level: Char, tag: String, msg: String, t: Throwable?) {
            val line = "${fmt.format(Date())} $level/$tag: $msg" + (t?.let { "\n" + it.stackTraceToString() } ?: "")
            synchronized(this) { runCatching { f.appendText(line + "\n") } }
            if (level == 'W' || level == 'E') System.err.println(line)
        }
    }
}

/** The chat window's first size: 1280×960 points, shrunk to 90% of the main screen when that is smaller. */
private fun defaultChatSize(): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    val screen = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
    // On a machine that paints in software every pixel of this window is filled by
    // the CPU, so the window's pixel count is its frame cost. Rendering at 1x cuts
    // that count too but blurs the text, because the display then stretches a
    // half-resolution image over the same area. Shrinking the window cuts the count
    // *without* that trade: HiDPI is pixels per point, so a 2x window at 220k points
    // is as sharp as a 2x window at 470k points — it just costs less to paint.
    val software = System.getProperty("skiko.renderApi") == "SOFTWARE"
    val w = minOf(if (software) 1000 else 1280, ((screen?.width ?: 1280) * if (software) 0.72f else 0.9f).toInt())
    val h = minOf(if (software) 720 else 960, ((screen?.height ?: 960) * if (software) 0.72f else 0.9f).toInt())
    return w.dp to h.dp
}

/**
 * The ball's taps, shared by the floating ball and the one above the conversation. [onType] brings the chat up
 * with the cursor in the box; a long press is the caller's (the floating ball opens the window, the chat's own
 * ball has nothing to open).
 */
/**
 * The phone's rule for a ball that cannot listen (BodyCoreService.reportVoiceFailure): say what is missing on
 * a card, and put the way out on the same card — the settings door is a button, not something the tap does
 * to you. A tap that silently throws a window open reads as the app misbehaving.
 */
fun askToOpenSettings(open: () -> Unit) {
    val zh = Strings.lang() == "zh"
    val go = if (zh) "打开设置" else "Open settings"
    val ack = if (zh) "知道了" else "Got it"
    Thread {
        val r = AskGate.ask(noVoiceKey(), listOf(go, ack), 60_000)
        if (r.button == go) javax.swing.SwingUtilities.invokeLater(open)
    }.start()
}

/**
 * One tap on the ball, or on the chat window's microphone: "listen to me now". A meeting being recorded is
 * ended instead. Whatever was running or being read out stops first, and the mic opens in the same tap rather
 * than needing a second one. One function, so the two doors cannot drift apart.
 */
fun tapToTalk(onNoVoice: () -> Unit) {
    when {
        Meeting.isActive() -> Meeting.stopFromUser()
        else -> {
            // Always: "working" misses what is queued or waiting to start, and that is what carried on after a tap.
            Assistant.stop()
            if (!net.kuafuai.andee.desktop.voice.MacVoice.toggle()) onNoVoice()
        }
    }
}

fun ballActions(onType: () -> Unit, onNoVoice: () -> Unit) = BallActions(
    onTap = { tapToTalk(onNoVoice) },
    // Typing is a new request too: whatever was running or being read out stops first, as the tap does.
    onDoubleTap = { Assistant.stop(); onType() },
    onTripleTap = { if (Meeting.isActive()) Meeting.stopFromUser() else Assistant.stop() },
    onLongPress = {},
)
