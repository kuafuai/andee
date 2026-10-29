package net.kuafuai.andee.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import net.kuafuai.andee.brain.Nudge
import net.kuafuai.andee.brain.NudgeBook
import net.kuafuai.andee.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The bubble window's title; the native side finds the window by it. */
const val BUBBLE_TITLE = "Andee Bubble"

/**
 * *When* a bubble goes up beside the ball, and what a tap on it does — the Mac's twin of the phone's
 * `NudgeUi`. See `docs/design/proactive-nudge.zh-CN.md` §4; the budget is the shared [NudgeBook].
 *
 * A bubble is something that is fine to miss, so it waits for the user rather than the other way round:
 * [gate] holds it while a card is up, a turn is running or being read out, a meeting is recording, it is
 * the night, or nobody has touched the Mac for a while (away, or locked — this is the Mac's stand-in for
 * the phone's screen-off check). A held bubble is retried every [RETRY_MS] and dropped silently once its
 * TTL passes. One held at a time, newest wins.
 *
 * Everything here runs on one scheduler thread; the composable only reads [showing].
 */
object Nudges {

    enum class Gate { SHOW, WAIT, LOG }

    private const val RETRY_MS = 5_000L
    private const val STAY_TEXT_MS = 12_000L
    private const val STAY_BUTTONS_MS = 30_000L
    private const val FADE_MS = 260L

    private class Slot(val n: Nudge, var recorded: Boolean = false)

    /**
     * The bubble budget, one per run (a restart starts the count again — see [NudgeBook]). Shared by the
     * `nudge` tool and the plan's timed bubbles, so both draw on the user's one allowance.
     */
    val book = NudgeBook()

    /** What is on screen. Read by [BubbleWindow]. */
    var showing by mutableStateOf<Nudge?>(null)
        private set
    /** True for the fade before [showing] is cleared. */
    var fading by mutableStateOf(false)
        private set

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nudges").apply { isDaemon = true } }
    private var held: Slot? = null
    private var current: Slot? = null
    private var retry: ScheduledFuture<*>? = null
    private var stay: ScheduledFuture<*>? = null
    private var watch: ScheduledFuture<*>? = null

    /** Set by Main: whether the chat window is in front of the user (then the bubble is a scrollback row). */
    @Volatile var chatInFront: () -> Boolean = { false }
    /** Set by Main: whether the ball's window exists to stand a bubble beside. */
    @Volatile var ballUp: () -> Boolean = { false }

    fun post(n: Nudge) = exec.execute {
        held?.let { Log.i("Nudge", "replaced a held bubble: ${it.n.text}") }
        held = Slot(n)
        pump()
    }

    private fun pump() {
        retry?.cancel(false)
        val slot = held ?: return
        if (System.currentTimeMillis() >= slot.n.expiresAt) {
            Log.i("Nudge", "expired before it could be shown: ${slot.n.text}")
            held = null
            outcome(slot.n, "expired", null)
            return
        }
        if (current != null) return
        when (gate()) {
            Gate.WAIT -> retry = exec.schedule(::pump, RETRY_MS, TimeUnit.MILLISECONDS)
            Gate.LOG -> { held = null; record(slot); outcome(slot.n, "logged", null) }
            Gate.SHOW -> {
                held = null
                current = slot
                record(slot)
                fading = false
                showing = slot.n
                MacBall.greet()
                val ms = if (slot.n.buttons.isEmpty()) STAY_TEXT_MS else STAY_BUTTONS_MS
                stay = exec.schedule({ fadeThenEnd("timeout") }, ms, TimeUnit.MILLISECONDS)
                // A card arriving takes the spot beside the ball; the bubble steps back and is held again.
                watch = exec.scheduleWithFixedDelay({
                    if (current === slot && (AskGate.current != null || ApprovalGate.current != null)) end(slot, "superseded", null)
                }, 300, 300, TimeUnit.MILLISECONDS)
            }
        }
    }

    /**
     * Whether a bubble may go up right now. Every answer but SHOW is a reason to hold it, never to drop it.
     * Full-screen video and games are not detected yet, as on the phone.
     */
    private fun gate(): Gate {
        if (!ballUp()) return Gate.WAIT
        if (AskGate.current != null || ApprovalGate.current != null) return Gate.WAIT
        if (Assistant.working() || net.kuafuai.andee.desktop.voice.MacVoice.isListening() || Meeting.isActive()) return Gate.WAIT
        return if (chatInFront()) Gate.LOG else Gate.SHOW
    }

    /** The bubble's words, once, when they first reach the user — on screen or as a row. */
    private fun record(slot: Slot) {
        if (slot.recorded) return
        slot.recorded = true
        TimelineEvents.bubble(slot.n.text)
    }

    // ---- what the user did (called from the UI thread) -----------------------

    /**
     * The user tapped the ball to stop everything: the bubble on screen goes, and the one waiting for its moment is
     * dropped — recorded as dismissed, as if the user had waved it away.
     */
    fun dismiss() = exec.execute {
        held?.let { outcome(it.n, "dismissed", null) }
        held = null
        current?.let { end(it, "dismissed", null) }
    }

    fun tapButton(label: String) = exec.execute { current?.let { end(it, "button", label) } }
    fun tapBody() = exec.execute { current?.let { end(it, "body", null) } }
    fun longPress() = exec.execute { current?.let { end(it, "mute", null) } }

    private fun fadeThenEnd(how: String) {
        val slot = current ?: return
        fading = true
        exec.schedule({ if (current === slot) end(slot, how, null) }, FADE_MS, TimeUnit.MILLISECONDS)
    }

    private fun end(slot: Slot, how: String, button: String?) {
        if (current !== slot) return
        current = null
        stay?.cancel(false)
        watch?.cancel(false)
        showing = null
        fading = false
        val n = slot.n
        Log.i("Nudge", "$how${button?.let { " [$it]" } ?: ""}: ${n.text}")
        outcome(n, how, button)
        when (how) {
            "button" -> if (button != null && button != n.dismiss) {
                Scrollback.add(Scrollback.Role.USER, button)
                Assistant.answerNudge(n, button)
            }
            // A bubble that only says something: a tap is "seen", not "let's talk".
            "body" -> if (n.buttons.isNotEmpty()) Assistant.answerNudge(n, null)
            "mute" -> mute(n)
            "superseded" -> if (held == null) held = slot
        }
        // Displaced: wait a beat rather than spin against the card that took the spot.
        retry = exec.schedule(::pump, if (how == "superseded") RETRY_MS else 0L, TimeUnit.MILLISECONDS)
    }

    /** A planned bubble's ending goes back onto its plan item, for tonight's review and the settings page. */
    private fun outcome(n: Nudge, how: String, button: String?) =
        runCatching { DesktopNotebook.plans.recordOutcome(n, how, button) }
            .onFailure { Log.w("Nudge", "could not record the outcome: ${it.message}") }

    /** A planned bubble came due: the plan decides whether it is still worth showing and allowed. */
    fun postPlanned(todo: net.kuafuai.andee.config.Todo) {
        DesktopNotebook.plans.bubbleFor(todo, book, level(), muted())?.let(::post)
    }

    // ---- the user's settings -------------------------------------------------

    fun level(): NudgeBook.Level = NudgeBook.Level.of(Settings.get("nudge").ifEmpty { null })

    fun muted(): Set<String> = Settings.get("nudge_muted").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun unmuteAll() = Settings.save(mapOf("nudge_muted" to ""))

    private fun mute(n: Nudge) {
        Settings.save(mapOf("nudge_muted" to (muted() + n.kind).joinToString(",")))
        // A setting changed by a gesture with no confirmation; the user is owed the news and the way back.
        TimelineEvents.body(TimelineEvents.Body.BUBBLE_MUTED, String.format(Strings.locale(), Strings.get("s_nudge_muted_row"), n.text))
    }

}

/**
 * The plan, thought about without being asked ([net.kuafuai.andee.brain.PlanMind]) — the Mac's twin of the
 * phone's `planTick`. Every [TICK_MS], whether or not anyone is at the Mac — the device is Andee's own:
 * the morning look once a day, then the muse while they are free. On its own thread, because both block on a
 * model call and the bubble timers must not wait behind one.
 */
object PlanTicks {
    private const val TICK_MS = 5 * 60_000L
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "plan-mind").apply { isDaemon = true } }

    fun start() {
        exec.scheduleWithFixedDelay({ runCatching(::tick).onFailure { Log.w("Plan", "tick failed: ${it.message}") } }, 60_000L, TICK_MS, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        val cfg = Assistant.config()
        if (cfg.apiKey.isEmpty()) return
        // Not whether anyone is at the Mac, or the hour: the device is Andee's own (see the phone's planTick).
        val plans = DesktopNotebook.plans
        val mind = net.kuafuai.andee.brain.PlanMind(cfg, plans) { warn, msg -> if (warn) Log.w("Plan", msg) else Log.i("Plan", msg) }
        val level = Nudges.level()
        if (mind.morning(Strings.lang(), level.perDay)) {
            DesktopScheduler.syncPlan()
            TimelineEvents.morning(plans.day(plans.today())?.intent.orEmpty())
        }
        val free = !Assistant.working() && !Meeting.isActive() && AskGate.current == null && ApprovalGate.current == null
        if (free && level != NudgeBook.Level.OFF) {
            mind.muse(Strings.lang())?.let { id -> plans.bubbleForItem(id, Nudges.book, level, Nudges.muted())?.let(Nudges::post) }
        }
        val brain = Assistant.built()
        if (free && DesktopNotebook.driveEnabled() && brain != null && !brain.isBusy()) heartbeat(cfg, brain)
    }

    /**
     * Is anything on its mind worth getting up for ([net.kuafuai.andee.brain.Heartbeat])? The phone's twin, on the
     * same tick and presence check; the beat keeps its own pace and budget. A `work` verdict starts a quiet turn
     * ([net.kuafuai.andee.brain.SelfWork]) and leaves one scrollback row when it is done.
     */
    private fun heartbeat(cfg: net.kuafuai.andee.config.BrainConfig, brain: net.kuafuai.andee.brain.LocalBrain) {
        val self = DesktopNotebook.book.selfhood
        val drive = DesktopNotebook.drive
        val log: (Boolean, String) -> Unit = { warn, msg -> if (warn) Log.w("Drive", msg) else Log.i("Drive", msg) }
        val v = net.kuafuai.andee.brain.Heartbeat(cfg, self, drive, { DesktopNotebook.plans.indexForPrompt() }, log).beat(Strings.lang())
        if (v is net.kuafuai.andee.brain.Heartbeat.Verdict.Note) TimelineEvents.thought(v.text)
        if (v !is net.kuafuai.andee.brain.Heartbeat.Verdict.Work) return
        log(false, "work on ${v.care.id} (${v.care.what}): ${v.why}")
        TimelineEvents.workStarted(v.care.what, v.why)
        net.kuafuai.andee.brain.SelfWork.start(brain, self, drive, v.care, v.ratio, v.why) { effect, summary ->
            log(false, "work on ${v.care.id} booked as $effect")
            val text = summary?.let { net.kuafuai.andee.brain.SpeechMood.parse(it).text.trim() }
            TimelineEvents.workDone(v.care.what, effect.name, text)
        }
    }
}

/**
 * The bubble beside the ball: a small card, its own window like the prompt card and the caption (the
 * ball's window is exactly the ball's size). Never takes focus — a bubble that pulled the keyboard away
 * from whatever the user was typing in would be the opposite of quiet. Sized to its content, because a
 * transparent window larger than the card would swallow clicks around it.
 */
@Composable
fun ApplicationScope.BubbleWindow(ball: java.awt.Window?) {
    val n = Nudges.showing
    val up = ball != null && n != null
    // Keep the last words while it fades.
    var shown by remember { mutableStateOf<Nudge?>(null) }
    if (n != null) shown = n
    val alpha by animateFloatAsState(if (n != null && !Nudges.fading) 1f else 0f, tween(if (Nudges.fading) 260 else 220))
    val density = LocalDensity.current
    var size by remember { mutableStateOf(DpSize(240.dp, 80.dp)) }
    val state = rememberWindowState(size = size, position = WindowPosition(0.dp, 0.dp))
    state.size = size
    Window(
        onCloseRequest = {},
        state = state,
        title = BUBBLE_TITLE,
        visible = up,
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        focusable = false,
    ) {
        val w = window
        LaunchedEffect(up) {
            if (!up) return@LaunchedEffect
            var floated = false
            while (true) {
                ball?.let { place(w, it, gap = 6) }
                if (!floated && w.isShowing) floated = net.kuafuai.andee.desktop.mac.Bridge.floatWindow(BUBBLE_TITLE)
                delay(80)
            }
        }
        val b = shown ?: return@Window
        Box(
            Modifier.wrapContentSize(align = Alignment.TopStart, unbounded = true)
                .onSizeChanged { with(density) { size = DpSize(it.width.toDp(), it.height.toDp()) } },
        ) {
            BubbleCard(b, Modifier.padding(4.dp).alpha(alpha))
        }
    }
}

/** The card itself, apart from its window so `uishot bubble` can draw it. */
@Composable
fun BubbleCard(b: Nudge, modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(min = 120.dp, max = 300.dp)
            .background(Palette.cardSolid, RoundedCornerShape(16.dp))
            .border(1.dp, Palette.hairline, RoundedCornerShape(16.dp))
            .pointerInput(b) { detectTapGestures(onTap = { Nudges.tapBody() }, onLongPress = { Nudges.longPress() }) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(b.text, color = Palette.text, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 3)
        if (b.buttons.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                b.buttons.forEachIndexed { i, label ->
                    val primary = i == 0
                    Text(
                        label, color = if (primary) Color.White else Palette.label, fontSize = 13.sp,
                        modifier = Modifier
                            .background(if (primary) Palette.tint else Palette.panel, RoundedCornerShape(12.dp))
                            .border(1.dp, if (primary) Color.Transparent else Palette.hairline, RoundedCornerShape(12.dp))
                            .clickable { Nudges.tapButton(label) }
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}
