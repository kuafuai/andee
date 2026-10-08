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
 * Vault results are never written: `get_vault` returns phone numbers and
 * emails in the clear, and a debug file is the last place they should land.
 */
object BrainTrace {

    private const val TAG = "Body"
    private const val FILE = "brain_trace.jsonl"
    private const val MAX_BYTES = 1_000_000L
    private const val MAX_FIELD = 4_000

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "brain-trace").apply { isDaemon = true }
    }

    @Volatile private var dir: File? = null

    fun init(context: Context) {
        dir = context.applicationContext.filesDir
    }

    fun turn(gen: Int, kind: String, text: String) =
        append(JSONObject().put("ev", "turn").put("gen", gen).put("kind", kind).put("text", clip(text)))

    fun step(gen: Int, step: Int, content: String, reasoning: String, calls: Int, promptTokens: Int) =
        append(
            JSONObject().put("ev", "step").put("gen", gen).put("step", step)
                .put("content", clip(content)).put("reasoning", clip(reasoning))
                .put("calls", calls).put("prompt_tokens", promptTokens),
        )

    fun tool(gen: Int, name: String, method: String?, args: String, result: String, ms: Long) {
        val secret = method?.startsWith("device.vault.") == true
        append(
            JSONObject().put("ev", "tool").put("gen", gen).put("name", name)
                .put("args", clip(args)).put("ms", ms)
                .put("result", if (secret) "<vault result not traced>" else clip(result)),
        )
    }

    fun end(gen: Int, why: String) = append(JSONObject().put("ev", "end").put("gen", gen).put("why", why))

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
