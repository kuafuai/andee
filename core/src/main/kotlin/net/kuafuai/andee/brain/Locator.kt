package net.kuafuai.andee.brain

import net.kuafuai.andee.config.BrainConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns "the 发送 button under the comment box" into a point on a screenshot — the grounding half of a
 * planner/grounder split. The brain says *what* to tap; this says *where*.
 *
 * Why it exists: the brain is a general model, and general models place points badly. Measured on the Mac
 * traces (Oct 2026): e-number taps worked ~80% of the time, zoom-aimed coordinate taps ~43%, unaimed ones
 * under 10%, and at least five misses were the model doing pixel→0-1000 arithmetic wrong. The literature says
 * the same (GPT-4o 0.8 on ScreenSpot-Pro, specialists 50–74). A model trained to point, asked one question
 * per tap, was 1–8 px off on every one of seven targets in our own phone screenshots, toolbar icons included.
 *
 * One call, no tools, thinking off: a grounding question is not a reasoning question.
 *
 * Which model: [modelFor]. The default is the Doubao Seed vision model on Volcengine Ark, because that is the
 * platform the brain already uses on these devices — same key, no second account. A backend that cannot
 * serve it (a gateway that does not forward it, a key not activated for it) is remembered as [unavailable]
 * for the rest of the process, so the tool disappears instead of failing on every tap.
 */
class Locator(cfg: BrainConfig, private val model: String, private val log: (warn: Boolean, msg: String) -> Unit = { _, _ -> }) {

    sealed class Result {
        /** The target's centre, 0-1000 of the image, and what the model says that element shows. */
        data class Found(val x: Int, val y: Int, val seen: String = "") : Result()
        /** The model looked and says it is not on this screen. */
        object Absent : Result()
        /** No answer: the call failed or the reply was unreadable. */
        data class Failed(val why: String) : Result()
    }

    private val key = cfg.baseUrl
    private val llm = LlmClient(baseUrl = cfg.baseUrl, apiKey = cfg.apiKey, model = model, thinking = false, reasoningEffort = "low")

    /** Blocks for one model call (~2 s). [pngBase64] is the screenshot exactly as captured, no grid on it. */
    fun locate(pngBase64: String, target: String): Result {
        val messages = listOf(
            JSONObject().put("role", "user").put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,$pngBase64")))
                    .put(JSONObject().put("type", "text").put("text", prompt(target))),
            ),
        )
        val reply = try {
            llm.complete(messages, JSONArray(), noThinking = true)
        } catch (e: LlmHttpException) {
            // A model this backend does not serve, or one that cannot take an image: not going to change this run.
            if (e.code in setOf(400, 401, 403, 404, 415, 422)) withdraw("HTTP ${e.code}")
            else failed("HTTP ${e.code}")
            return Result.Failed("the locating model answered HTTP ${e.code}")
        } catch (t: Throwable) {
            failed(t.message ?: "no answer")
            return Result.Failed(t.message ?: "the locating call failed")
        }
        val raw = reply.content.trim()
        log(false, "locate \"${target.take(60)}\" → ${raw.take(80)} (tokens ${reply.promptTokens}/${reply.completionTokens})")
        val r = parse(raw)
        // A readable answer — a point or "not there" — is a working locator. Only unreadable ones count against it.
        if (r is Result.Failed) failed(r.why) else misses.remove(slot)
        return r
    }

    private val slot get() = key + "|" + model

    /** Take the tool away for the rest of this run; the brain falls back to zoom-and-aim. */
    private fun withdraw(why: String) {
        unavailable[slot] = true
        log(true, "locator unavailable with $model ($why); tap_target is withdrawn for this run and the brain aims by grid again")
    }

    /** Three unreadable or failed answers in a row withdraw it too: a slow tool that never works is worse than none. */
    private fun failed(why: String) {
        log(true, "locate failed: $why")
        if (misses.merge(slot, 1, Int::plus)!! >= MAX_MISSES) withdraw("$MAX_MISSES failures in a row")
    }

    companion object {
        /** What `locator_model` can be set to for the fastest and most precise answers, on Ark or the CodeFlying gateway. */
        const val DOUBAO = "doubao-seed-2-1-lite-260915"

        private val unavailable = ConcurrentHashMap<String, Boolean>()
        private val misses = ConcurrentHashMap<String, Int>()
        private const val MAX_MISSES = 3

        /**
         * The model to locate with: [override] (the `locator_model` setting) if there is one, otherwise **the
         * model the user configured for the brain**, on every backend alike. Null only without a key, or once
         * this model has been withdrawn for the run (see [withdraw]) — and then the brain aims by grid as before.
         *
         * One rule instead of "Doubao on Ark, nothing elsewhere": the first version picked a model by address and
         * hid the tool on any backend it did not recognise, on the assumption that only Doubao could see. Current
         * models are multimodal; measured through api.openai-next.com, `gpt-6.1-sol` averaged 12 px off on our
         * screenshots (worst 45) and answered "not there" for things that were not — against Doubao's 6 px, which
         * stays reachable as `locator_model` for anyone who wants the speed (2 s against 5–16 s).
         */
        fun modelFor(cfg: BrainConfig, override: String): String? {
            if (cfg.apiKey.isEmpty()) return null
            val m = override.trim().ifEmpty { cfg.model }
            return m.takeIf { it.isNotBlank() && unavailable[cfg.baseUrl + "|" + it] != true }
        }

        /**
         * Two things in it were measured, on our own phone screenshots with the default model:
         *
         *  * **Chinese, whatever language the target is in.** The same twelve targets averaged 6.4 px off (worst
         *    24) in Chinese; the English wording missed by 25–37 px on three of them.
         *  * **Say what you see before you point.** Asked bare, "红色的删除按钮" on a screen that had none came
         *    back as the ✕ close button — a confident point at something that does a similar job, which in a
         *    real app is the dangerous kind of miss. Made to name the element first (`<seen>`) and to check the
         *    description against it, the same question answered <none/>, every positive still landed, and it
         *    costs ~16 output tokens. The `seen` text also goes back to the brain with the tap.
         */
        fun prompt(target: String): String =
            "这是一张屏幕截图。要找的界面元素：「${target.trim()}」。\n" +
                "先用一句话写出截图上最符合的那个元素实际显示的文字或图标（<seen>…</seen>），再判断它是不是要找的元素：" +
                "描述里提到的文字、颜色或图标在它身上都能对上，才算是。是，就输出 <point>x y</point>（中心点，x、y 是相对图片宽高的 0-1000 归一化坐标）；" +
                "不是或截图里没有，就输出 <none/>。不要把只是作用相近的东西当成它（比如不要把「关闭」当成「删除」）。"

        /** `<point>x y</point>`, or a `<bbox>x1 y1 x2 y2</bbox>` (its centre), or `<none/>`; `<seen>` rides along. */
        fun parse(raw: String): Result {
            val seen = Regex("""<seen>(.*?)</seen>""", RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.get(1)?.trim()?.take(60).orEmpty()
            // A point wins over a stray <none/>: the model sometimes writes both while it reasons, and a point is a commitment.
            val hasPoint = raw.contains("<point>") || raw.contains("<bbox>")
            if (!hasPoint && Regex("""<none\s*/?>""", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return Result.Absent
            Regex("""<point>\s*\(?\s*(\d{1,4})\s*[, ]\s*(\d{1,4})\s*\)?\s*</point>""").find(raw)?.let {
                return found(it.groupValues[1].toInt(), it.groupValues[2].toInt(), raw, seen)
            }
            Regex("""<bbox>\s*(\d{1,4})[ ,]+(\d{1,4})[ ,]+(\d{1,4})[ ,]+(\d{1,4})\s*</bbox>""").find(raw)?.let {
                val (a, b, c, d) = it.destructured
                return found((a.toInt() + c.toInt()) / 2, (b.toInt() + d.toInt()) / 2, raw, seen)
            }
            return Result.Failed("the locating model did not answer with a point: ${raw.take(60)}")
        }

        private fun found(x: Int, y: Int, raw: String, seen: String): Result =
            if (x in 0..1000 && y in 0..1000) Result.Found(x, y, seen) else Result.Failed("point outside 0-1000: ${raw.take(40)}")
    }
}
