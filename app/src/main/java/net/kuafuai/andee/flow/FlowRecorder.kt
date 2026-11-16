package net.kuafuai.andee.flow

import net.kuafuai.andee.config.FlowStep
import org.json.JSONObject

/**
 * The recording half of flows: while armed, every gesture the brain performs
 * through the device tools is noted as a [FlowStep], and the whole run is
 * saved as a [net.kuafuai.andee.config.Flow] when the user says to.
 *
 * Recording is explicit and short-lived — armed by `flow.record_start`,
 * disarmed by `flow.record_stop` or by saving. Nothing is stored until
 * `flow.save`, which the model only calls after the user said yes (the same
 * consent rule `save_scene` has). The recorder itself is pure data: it never
 * touches the screen, the notebook or the window, so the dispatcher hooks can
 * note a step from a tool result without ordering concerns.
 */
class FlowRecorder {

    private val lock = Any()
    private val steps = mutableListOf<FlowStep>()

    @Volatile
    var active = false
        private set

    /** Told the new step count after every noted step — the recording pill. */
    @Volatile
    var onStep: ((Int) -> Unit)? = null

    fun start() {
        synchronized(lock) {
            steps.clear()
            active = true
        }
    }

    fun stop() {
        synchronized(lock) { active = false }
    }

    fun isActive(): Boolean = active

    fun count(): Int = synchronized(lock) { steps.size }

    fun snapshot(): List<FlowStep> = synchronized(lock) { steps.toList() }

    fun note(step: FlowStep) {
        if (!active) return
        synchronized(lock) { steps.add(step) }
        onStep?.invoke(count())
    }

    /**
     * A `device.launch` tool result → step. Without this, a recording that
     * opened its app through the model would begin with a tap that only works
     * when that app is already open — the single most common reason a saved
     * flow dies at step 1 from the home screen. The label is kept so a flow
     * recorded by name still reads sensibly in the steps list.
     */
    fun noteLaunch(pkg: String, label: String) {
        if (!active) return
        note(FlowStep(kind = "launch", pkg = pkg, note = label))
    }

    /**
     * A tap / long_press tool result → step. eid taps carry the anchor fields
     * (view_id / text / bounds / pkg) that [ScreenController.tapEntry] put on
     * the result; coordinate taps carry only x/y, and replay will gate them
     * on the package alone.
     */
    fun noteTap(res: JSONObject, hold: Boolean, w: Int, h: Int) {
        if (!active) return
        val x = res.optInt("x")
        val y = res.optInt("y")
        note(
            FlowStep(
                kind = if (hold) "long_press" else "tap",
                viewId = res.optString("view_id").ifEmpty { null },
                text = res.optString("text"),
                bounds = res.optString("bounds"),
                pkg = res.optString("pkg"),
                x = x,
                y = y,
                relX = if (w > 0) x * NORM_MAX / w else 0,
                relY = if (h > 0) y * NORM_MAX / h else 0,
                changedAfter = if (res.has("screen_changed")) res.optBoolean("screen_changed") else null,
            ),
        )
    }

    /** A swipe tool call → step. Coordinates arrive as 0-1000. */
    fun noteSwipe(nx1: Int, ny1: Int, nx2: Int, ny2: Int, durationMs: Long, res: JSONObject, w: Int, h: Int) {
        if (!active) return
        note(
            FlowStep(
                kind = "swipe",
                relX = nx1,
                relY = ny1,
                relX2 = nx2,
                relY2 = ny2,
                x = if (w > 0) nx1 * w / NORM_MAX else 0,
                y = if (h > 0) ny1 * h / NORM_MAX else 0,
                x2 = if (w > 0) nx2 * w / NORM_MAX else 0,
                y2 = if (h > 0) ny2 * h / NORM_MAX else 0,
                durationMs = durationMs,
                changedAfter = if (res.has("screen_changed")) res.optBoolean("screen_changed") else null,
            ),
        )
    }

    /** Typed text → step. The text is the user's own data, notebook-private. */
    fun noteType(text: String, pkg: String) {
        if (!active) return
        note(FlowStep(kind = "type", note = text, pkg = pkg))
    }

    /** `screen.wait` → step. */
    fun noteWait(seconds: Double, pkg: String) {
        if (!active) return
        note(FlowStep(kind = "wait", durationMs = (seconds * 1000).toLong().coerceAtMost(WAIT_MAX_MS), pkg = pkg))
    }

    /** A step the user performed with their own hand, on the model's request. */
    fun noteHuman(noteText: String, pkg: String, timeoutSec: Int = DEFAULT_HUMAN_TIMEOUT_SEC) {
        if (!active) return
        note(FlowStep(kind = "human", note = noteText, pkg = pkg, humanTimeoutSec = timeoutSec, changedAfter = true))
    }

    /**
     * A step the USER cannot record because it needs judgement — a CAPTCHA
     * to read, a dialog that may or may not appear, a choice between two
     * similar buttons. Unlike [noteHuman], this one is handed to the MODEL
     * on replay: the flow pauses there and waits for `resume_flow`.
     */
    fun noteHandoff(noteText: String, pkg: String) {
        if (!active) return
        note(FlowStep(kind = "ai", note = noteText, pkg = pkg))
    }

    companion object {
        const val NORM_MAX = 1000
        const val WAIT_MAX_MS = 30_000L
        const val DEFAULT_HUMAN_TIMEOUT_SEC = 60
    }
}
