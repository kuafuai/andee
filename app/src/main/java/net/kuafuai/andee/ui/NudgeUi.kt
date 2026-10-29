package net.kuafuai.andee.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import net.kuafuai.andee.brain.Nudge

/**
 * *When* a bubble goes up; [CardUi.bubble] is *how*. See `docs/design/proactive-nudge.zh-CN.md` §4.
 *
 * A bubble is something that is fine to miss, so this never makes the user's moment fit the bubble — the
 * bubble waits for the moment. [gate] is asked before every attempt and answers [Gate.WAIT] whenever the
 * user is busy with something else; a waiting bubble is retried every [RETRY_MS] until its TTL runs out,
 * and is then dropped without a word, because 「该喝水啦」 three hours late is noise.
 *
 * **One held at a time, and the newest wins.** A queue of bubbles is push spam with extra steps — the same
 * reason [CardUi] shows one card at a time.
 *
 * Main thread for everything; [post] may be called from anywhere.
 */
object NudgeUi {

    enum class Gate {
        /** Nothing in the way: try to show it. */
        SHOW,

        /** The user is busy (a card, speech, a task, a locked screen, quiet hours): hold it. */
        WAIT,

        /**
         * The card is unfolded and the user is reading the scrollback: a bubble hanging off a full-screen
         * card would be odd, so it is written there as a row instead and is done.
         */
        LOG,
    }

    private const val TAG = "Nudge"
    private const val RETRY_MS = 5_000L
    private const val STAY_TEXT_MS = 12_000L
    private const val STAY_BUTTONS_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())

    /** The bubble and whether its words are already in the scrollback — written once, however often it is re-held. */
    private class Slot(val n: Nudge, var recorded: Boolean = false)

    private var held: Slot? = null
    private var showing: Slot? = null

    private var gate: () -> Gate = { Gate.WAIT }
    private var onAnswer: (Nudge, String?) -> Unit = { _, _ -> }
    private var onMute: (Nudge) -> Unit = { }
    private var onOutcome: (Nudge, String, String?) -> Unit = { _, _, _ -> }

    /**
     * @param onAnswer the user tapped a button that is not the `dismiss` one (label), or the body of a bubble
     *   that has buttons (null). Starts a turn.
     * @param onMute the user long-pressed: never this kind again.
     * @param onOutcome how every bubble ended (`button`, `body`, `mute`, `timeout`, `logged`, `expired`), for a
     *   planned one to be written back onto its plan item.
     */
    fun init(
        gate: () -> Gate,
        onAnswer: (Nudge, String?) -> Unit,
        onMute: (Nudge) -> Unit,
        onOutcome: (Nudge, String, String?) -> Unit = { _, _, _ -> },
    ) {
        this.gate = gate
        this.onAnswer = onAnswer
        this.onMute = onMute
        this.onOutcome = onOutcome
    }

    fun post(n: Nudge) {
        main.post {
            held?.let { Log.i(TAG, "replaced a held bubble: ${it.n.text}") }
            held = Slot(n)
            pump()
        }
    }

    private val retry = Runnable { pump() }

    private fun pump() {
        main.removeCallbacks(retry)
        val slot = held ?: return
        if (System.currentTimeMillis() >= slot.n.expiresAt) {
            Log.i(TAG, "expired before it could be shown: ${slot.n.text}")
            held = null
            onOutcome(slot.n, "expired", null)
            return
        }
        if (showing != null) {
            // Its own end calls pump again.
            return
        }
        when (gate()) {
            Gate.WAIT -> main.postDelayed(retry, RETRY_MS)
            Gate.LOG -> {
                held = null
                record(slot)
                onOutcome(slot.n, "logged", null)
            }
            Gate.SHOW -> {
                held = null
                showing = slot
                val stay = if (slot.n.buttons.isEmpty()) STAY_TEXT_MS else STAY_BUTTONS_MS
                CardUi.bubble(slot.n.text, slot.n.buttons, stay) { r ->
                    showing = null
                    if (r.how != "busy") record(slot)
                    finish(slot, r)
                    // A refused or displaced bubble waits a beat: the gate said SHOW a moment ago, so pumping
                    // straight away would just spin against whatever is in the slot.
                    if (r.how == "busy" || r.how == "superseded") {
                        main.postDelayed(retry, RETRY_MS)
                    } else {
                        pump()
                    }
                }
            }
        }
    }

    /**
     * The bubble's words go in the scrollback when they first reach the user: they were said, and a user
     * who looks back for "what was it saying" should find it. Never twice for one bubble.
     */
    private fun record(slot: Slot) {
        if (slot.recorded) return
        slot.recorded = true
        ChatHistory.appContextOrNull()?.let { PhoneTimeline.bubble(it, slot.n.text) }
    }

    private fun finish(slot: Slot, r: CardUi.Result) {
        val n = slot.n
        Log.i(TAG, "${r.how}${r.button?.let { " [$it]" } ?: ""}: ${n.text}")
        onOutcome(n, r.how, r.button)
        when (r.how) {
            "button" -> {
                val b = r.button ?: return
                if (b == n.dismiss) return
                ChatHistory.addUser(b)
                onAnswer(n, b)
            }
            // Tapping a bubble that only says something is "seen", not "let's talk".
            "body" -> if (n.buttons.isNotEmpty()) onAnswer(n, null)
            "mute" -> onMute(n)
            // Never reached the user, or a card took its place: hold it again unless something newer is held.
            "busy", "superseded" -> if (held == null) held = slot
            else -> Unit
        }
    }
}
