package net.kuafuai.andee.brain

import org.json.JSONObject

/**
 * One bubble the model asked to hold up over the ball: the weakest way this device has of speaking first.
 *
 * Unlike a card it is silent, blocks nothing and is allowed to be ignored — not answering is an answer.
 * See `docs/design/proactive-nudge.zh-CN.md` §4.
 */
data class Nudge(
    val text: String,
    /** 0–3 labels. Empty = a bubble that only says something. */
    val buttons: List<String>,
    /** The label that means "no thanks": tapping it ends the bubble without starting a turn. */
    val dismiss: String?,
    val kind: String,
    /** Absolute millis after which the bubble is stale and must not be shown at all. */
    val expiresAt: Long,
    /**
     * The turn that wrote it was [LocalBrain.TurnKind.NOTIFY]. The turn a tap on it starts runs as one too —
     * otherwise a stranger's message could put a 「好」 button in front of the user and get a trusted turn back
     * for it, the same laundering `Todo.untrusted` closes for the alarm clock.
     */
    val untrusted: Boolean,
    /** `date#item` when this bubble is a planned item ([net.kuafuai.andee.config.Plans]); blank otherwise. */
    val planRef: String = "",
)

/**
 * The device's half of `nudge`: what a bubble may say, and how often the model may raise one.
 *
 * **The limits are here and not in the prompt**, for the reason every rule in [net.kuafuai.andee.net.TurnGuards]
 * is: a prompt is a request. Being spoken to first is only welcome when it is rare, and a stream of small
 * interruptions is how a user learns to mute the ball, after which even the right ones are lost. So the
 * budget is mechanical and a refusal comes back to the model as a sentence saying why.
 *
 * Pure apart from the clock, so it can be tested. In memory only: a service restart resets the counts,
 * which errs toward one extra bubble and is not worth a file.
 */
class NudgeBook(private val clock: () -> Long = System::currentTimeMillis) {

    /** How chatty the user lets it be. [OFF] is also the fallback for a value nobody recognises. */
    enum class Level(val key: String, val perHour: Int, val perDay: Int) {
        OFF("off", 0, 0),
        FEW("few", 1, 4),
        NORMAL("normal", 2, 8),
        ;

        companion object {
            /** Unset means [FEW], unrecognised means [OFF] — the same split `VoiceConfig.notifyScope` draws. */
            fun of(saved: String?): Level = when (saved?.trim()?.lowercase()) {
                null, "" -> FEW
                FEW.key -> FEW
                NORMAL.key -> NORMAL
                else -> OFF
            }
        }
    }

    private val admitted = ArrayDeque<Pair<Long, String>>()

    /**
     * Read a `ui.nudge` call. Throws [IllegalArgumentException] with a sentence for the model on a malformed
     * one — those are the model's to fix, unlike [admit]'s refusals, which are not.
     */
    fun parse(params: JSONObject?, untrusted: Boolean): Nudge {
        val text = params?.optString("text")?.trim().orEmpty()
        require(text.isNotEmpty()) { "nudge requires text" }
        require(text.length <= MAX_TEXT) {
            "A bubble holds one short sentence (≤ $MAX_TEXT characters); this was ${text.length}. " +
                "If it needs more than that, it is a conversation, not a bubble — use ask_user, or say it next time they talk to you."
        }
        val arr = params?.optJSONArray("buttons")
        val buttons = if (arr == null) emptyList() else List(arr.length()) { arr.optString(it).trim() }.filter { it.isNotEmpty() }
        require(buttons.size <= MAX_BUTTONS) { "A bubble has at most $MAX_BUTTONS buttons; got ${buttons.size}." }
        require(buttons.toSet().size == buttons.size) { "Two buttons have the same label." }
        buttons.firstOrNull { it.length > MAX_LABEL }?.let {
            throw IllegalArgumentException("Button labels are a word or two (≤ $MAX_LABEL characters): \"$it\"")
        }
        val dismiss = params?.optString("dismiss")?.trim()?.ifEmpty { null }
        require(dismiss == null || dismiss in buttons) {
            "dismiss must be one of the buttons, letter for letter: \"$dismiss\" is not in $buttons"
        }
        val ttlMin = params?.optLong("ttl_minutes", DEFAULT_TTL_MIN) ?: DEFAULT_TTL_MIN
        require(ttlMin in 1..MAX_TTL_MIN) { "ttl_minutes must be 1–$MAX_TTL_MIN; got $ttlMin" }
        val kind = params?.optString("kind")?.trim()?.lowercase()?.take(MAX_KIND)?.ifEmpty { null } ?: "other"
        return Nudge(text, buttons, dismiss, kind, clock() + ttlMin * 60_000L, untrusted)
    }

    /**
     * Admit [n] against the budget, or say why not. Null = admitted, and counted from now: a bubble that is
     * held and then expires has still spent its slot, which errs toward fewer.
     */
    fun admit(n: Nudge, level: Level, muted: Set<String>): String? {
        val now = clock()
        if (level == Level.OFF) {
            return "The user has turned bubbles off in settings. Do not raise one; if it matters, say it the next time they talk to you."
        }
        if (n.kind in muted) {
            return "The user asked not to be reminded of \"${n.kind}\" any more. Respect that; do not raise it under another kind."
        }
        while (admitted.isNotEmpty() && now - admitted.first().first >= DAY_MS) admitted.removeFirst()
        val lastHour = admitted.count { now - it.first < HOUR_MS }
        if (lastHour >= level.perHour) {
            return "Bubble budget used: at most ${level.perHour} per hour at the user's setting. Not raised. " +
                "Do not retry; if it still matters later, it can wait."
        }
        if (admitted.size >= level.perDay) {
            return "Bubble budget used: at most ${level.perDay} per day at the user's setting. Not raised. Do not retry today."
        }
        admitted.lastOrNull { it.second == n.kind }?.let { (at, _) ->
            if (now - at < KIND_COOLDOWN_MS) {
                val mins = (KIND_COOLDOWN_MS - (now - at) + 59_999) / 60_000
                return "A \"${n.kind}\" bubble went up ${(now - at) / 60_000} min ago; the same kind waits " +
                    "${KIND_COOLDOWN_MS / 60_000} min between bubbles ($mins min to go). Not raised."
            }
        }
        admitted.addLast(now to n.kind)
        return null
    }

    companion object {
        const val MAX_TEXT = 60
        const val MAX_BUTTONS = 3
        const val MAX_LABEL = 12
        const val MAX_KIND = 24
        const val DEFAULT_TTL_MIN = 30L
        const val MAX_TTL_MIN = 720L
        const val HOUR_MS = 60 * 60_000L
        const val DAY_MS = 24 * HOUR_MS
        const val KIND_COOLDOWN_MS = 45 * 60_000L

        /** 22:30–08:00. A bubble arriving inside it waits for morning, or expires first. */
        const val QUIET_FROM_MIN = 22 * 60 + 30
        const val QUIET_UNTIL_MIN = 8 * 60

        fun isQuiet(minuteOfDay: Int): Boolean = minuteOfDay >= QUIET_FROM_MIN || minuteOfDay < QUIET_UNTIL_MIN

        /**
         * What the brain is told when the user answers a bubble. English and bracketed: it is the device
         * talking, and `LocalPrompt` §9 tells the model not to take a language from it.
         */
        fun answerText(n: Nudge, button: String?): String =
            if (button != null) {
                "[You offered in a bubble: \"${n.text}\" — the user tapped \"$button\"]"
            } else {
                "[You offered in a bubble: \"${n.text}\" — the user tapped the bubble itself rather than a " +
                    "button: they want to talk about it]"
            }
    }
}
