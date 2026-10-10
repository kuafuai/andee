package net.kuafuai.andee.brain

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Every step of the local brain, appended to `files/brain_trace.jsonl`.
 *
 * logcat is not enough: some ROMs (Honor's MagicOS, measured) drop every
 * `Log.*` line a third-party app writes, so a 25-step task that failed left no
 * trace anywhere. This file is app-private, read back with `run-as`, bounded
 * at two generations of [MAX_BYTES], and wiped with the chat history.
 *
 * **Every record says which turn it belongs to** — see [idOf], and do not match
 * on `gen` instead. That is what lets the scrollback open one row onto the work
 * behind it (see [net.kuafuai.andee.ui.TracePage]). One turn is a handful of
 * records, and they are four kinds: a `turn` (what was asked), a `step` per
 * model reply (what it said, what it reasoned, and what the request cost in
 * prompt tokens), a `tool` per call (name, arguments, duration, result), and an
 * `end` saying why it stopped. Measured on the test device: ~8 records and
 * 7.3 KB for an ordinary turn.
 *
 * Vault results are never written: `get_vault` returns phone numbers and
 * emails in the clear, and a debug file is the last place they should land.
 */
object BrainTrace {

    private const val TAG = "Body"
    private const val FILE = "brain_trace.jsonl"
    private const val MAX_BYTES = 1_000_000L
    private const val MAX_FIELD = 4_000

    /**
     * Wall clock at class load, which for this file is process start.
     *
     * It exists because [idOf]'s other half is not unique on its own. See there.
     */
    private val run = System.currentTimeMillis()

    /**
     * The turn being recorded right now, or null between turns.
     *
     * This is how the scrollback finds a row's log: the value is copied onto
     * [net.kuafuai.andee.ui.ChatHistory.Entry.turn] when the answer is written,
     * and the reader matches it back here. See [idOf] for why it is not just
     * `gen`.
     *
     * Cleared by [net.kuafuai.andee.brain.LocalBrain] when a turn **ends**, not
     * when it is read. That direction matters for one path: `submit` returns
     * early when the API key is missing, and the row it writes is a device
     * complaint with no log behind it. Reading-and-clearing would still leave
     * *that* row claiming whatever the previous turn recorded, whereas ending
     * clears it before the next turn can inherit it.
     */
    @Volatile
    private var current: String? = null

    /**
     * The identity of turn [gen], and the only thing a caller should match a
     * log against.
     *
     * **`gen` alone is not enough, and this is measured rather than feared.**
     * It is [net.kuafuai.andee.brain.LocalBrain]'s in-memory `AtomicInteger`, so
     * it restarts at 1 with every process while this file is persistent and
     * append-only. On the test device `"gen":1` accounted for **176 of 278**
     * records — "the first turn since the last restart", 176 times over. A
     * scrollback that matched on `gen` would open a reinstall's first turn onto
     * yesterday's first turn, and there would be no visible sign it had.
     *
     * So the file records `run-gen`, `run` being the process's own start. A
     * timestamp rather than a random id because it also *sorts*: two runs order
     * the same way their turns do, which keeps the file readable end to end.
     */
    private fun idOf(gen: Int) = turnId(run, gen)

    /**
     * [idOf] with `run` passed in, so a test can stand in for a restart by
     * handing it two different stamps. See [idOf] for what the pair means.
     */
    internal fun turnId(run: Long, gen: Int) = "$run-$gen"

    /** The turn in flight, or null. See [current]. */
    fun currentTurn(): String? = current

    /** A turn ended, however it ended. See [current] for why this is not [currentTurn]. */
    fun clearTurn() {
        current = null
    }

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "brain-trace").apply { isDaemon = true }
    }

    @Volatile private var dir: File? = null

    fun init(context: Context) {
        dir = context.applicationContext.filesDir
    }

    /**
     * A turn began — the user's words, a todo coming due, a sweep.
     *
     * Sets [current] as a side effect, so this must be called by whatever starts
     * the turn and before any [step] or [tool] of it.
     *
     * `gen` stays in the record beside `turn` even though it is redundant once
     * you have the latter: it is what makes a line legible to a person reading
     * the file, and records written before `turn` existed still have it. It is
     * **not** what a reader should match on — see [idOf].
     */
    fun turn(gen: Int, kind: String, text: String) {
        current = idOf(gen)
        append(
            JSONObject().put("ev", "turn").put("turn", idOf(gen)).put("gen", gen)
                .put("kind", kind).put("text", clip(text)),
        )
    }

    fun step(gen: Int, step: Int, content: String, reasoning: String, calls: Int, promptTokens: Int) =
        append(
            JSONObject().put("ev", "step").put("turn", idOf(gen)).put("gen", gen).put("step", step)
                .put("content", clip(content)).put("reasoning", clip(reasoning))
                .put("calls", calls).put("prompt_tokens", promptTokens),
        )

    fun tool(gen: Int, name: String, method: String?, args: String, result: String, ms: Long) {
        val secret = method?.startsWith("device.vault.") == true
        append(
            JSONObject().put("ev", "tool").put("turn", idOf(gen)).put("gen", gen).put("name", name)
                .put("args", clip(args)).put("ms", ms)
                .put("result", if (secret) "<vault result not traced>" else clip(result)),
        )
    }

    fun end(gen: Int, why: String) =
        append(JSONObject().put("ev", "end").put("turn", idOf(gen)).put("gen", gen).put("why", why))

    fun wipe(context: Context) {
        val d = context.applicationContext.filesDir
        File(d, FILE).delete()
        File(d, "$FILE.1").delete()
    }

    private fun clip(s: String) = if (s.length <= MAX_FIELD) s else s.take(MAX_FIELD) + "…(${s.length})"

    private fun append(o: JSONObject) {
        val d = dir ?: return
        o.put("ts", System.currentTimeMillis())
        exec.execute {
            runCatching {
                val f = File(d, FILE)
                if (f.length() > MAX_BYTES) f.renameTo(File(d, "$FILE.1"))
                f.appendText(o.toString() + "\n")
            }.onFailure { Log.w(TAG, "brain trace: ${it.message}") }
        }
    }
}
