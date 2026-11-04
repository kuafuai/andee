package net.kuafuai.andee.brain

/**
 * What a turn is doing, as it happens — for the body to show, so a turn that is waiting on the model or working
 * through an app does not look stuck. Not conversation and not an event: nothing here is kept by the brain or
 * read back into the model. The body decides what to keep (a collapsed "做了 6 步 · 38 秒" row when the turn ends).
 *
 * Emitted on the loop thread, for every turn that answers someone (not the silent reviews, not a work turn,
 * which has its own timeline rows). Each body must hand it off quickly; nothing here may block.
 */
sealed class WorkStep {
    /** A turn began. */
    object Start : WorkStep()

    /** Waiting on the model for step [step] (1-based). The longest silence there is; the timer is what fills it. */
    data class Waiting(val step: Int) : WorkStep()

    /** The model's reasoning for [step], whole; the body cuts it into lines. Only when thinking is on. */
    data class Thought(val step: Int, val text: String) : WorkStep()

    /** What the model wrote before calling its tools ("我看一下屏幕"), markers already stripped. */
    data class Said(val step: Int, val text: String) : WorkStep()

    /** One tool call, as a line a person can read. */
    data class Action(val step: Int, val line: String) : WorkStep()

    /**
     * The turn is over, just before its answer is shown (spoken or quiet) — so a body that leaves a row for the
     * work can put it above the answer. Also sent for a turn that ended any other way (stopped, failed).
     */
    data class Done(val steps: Int, val actions: Int, val ms: Long) : WorkStep()
}
