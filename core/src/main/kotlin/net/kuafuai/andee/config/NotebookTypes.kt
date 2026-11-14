package net.kuafuai.andee.config

/**
 * A situation the assistant can step into: a goal, a voice, rules and steps
 * that ride in the system prompt while it is active. Learned, not shipped —
 * the model proposes one and the user says yes before it is written.
 */
data class Scene(
    val name: String,
    val title: String,
    val summary: String,
    val prompt: String,
    /** App identifiers whose arrival in the foreground enters this scene. */
    val triggerApps: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long,
    val uses: Int,
)

/**
 * One recorded step of a [Flow]: the act plus everything needed to find the
 * element again. Anchor first (viewId / text / bounds / pkg), coordinates as
 * fallback — absolute pixels for this screen and 0-1000 relative for any
 * other. Learned, not shipped: a flow is recorded from a run that went right.
 */
data class FlowStep(
    /** `tap` | `long_press` | `swipe` | `type` | `human` | `wait` | `launch` | `ai` */
    val kind: String,
    /** human/ai: the instruction; type: the text typed; launch: the app's display label. */
    val note: String = "",
    /** Resource id at record time, when the app exposes one. */
    val viewId: String? = null,
    /** The element's visible label at record time. */
    val text: String = "",
    /** "l,t,r,b" in pixels at record time. */
    val bounds: String = "",
    /** Owning app at record time; for a `launch` step, the package to open. */
    val pkg: String = "",
    /** Absolute pixel centre at record time (swipe: start). */
    val x: Int = 0,
    val y: Int = 0,
    /** 0-1000 of the screen, for other resolutions (swipe: start). */
    val relX: Int = 0,
    val relY: Int = 0,
    /** Swipe end. */
    val x2: Int = 0,
    val y2: Int = 0,
    val relX2: Int = 0,
    val relY2: Int = 0,
    /** Swipe duration / wait time, ms. */
    val durationMs: Long = 0,
    /** Whether the screen changed after this step when it was recorded. Null = not judged. */
    val changedAfter: Boolean? = null,
    /** human steps only: how long to wait for the user, seconds. */
    val humanTimeoutSec: Int = 60,
)

/**
 * A recorded run the assistant can play back instead of re-deciding every
 * tap — the "hands" to a scene's "head". Kept in the notebook like [Scene]:
 * local-only, the user's own, visible and deletable. Runs are counted so a
 * flow that keeps failing shows it.
 */
data class Flow(
    val name: String,
    val title: String,
    val summary: String,
    val steps: List<FlowStep>,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long,
    val runs: Int,
    val okRuns: Int,
)

/** A promise the brain made to act later, kept in the notebook. */
data class Todo(
    val id: String,
    val what: String,
    /** `once` or `cron`. */
    val mode: String,
    /** Absolute millis, one-shot only. 0 for cron rows. */
    val at: Long,
    /** Standard 5-field expression, cron rows only. */
    val cronExpr: String,
    val nextRunAt: Long,
    val lastRunAt: Long,
    val lastStatus: String,
    val lastError: String,
    /** `active` / `paused` / `done` / `dropped`. */
    val status: String,
    val createdAt: Long,
    /**
     * Set on a turn whose subject matter a stranger wrote (a notification).
     * Such a todo runs as that same kind of turn when it comes due, so the
     * vault and scene guards cannot be laundered through the alarm clock.
     * Rows written before this field existed read as false.
     */
    val untrusted: Boolean = false,
    /**
     * A bubble spec (JSON) when this todo raises a bubble at its time instead of waking the model — the
     * plan's timed items ([Plans.reconcile]). Blank for an ordinary todo.
     */
    val nudge: String = "",
)
