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
