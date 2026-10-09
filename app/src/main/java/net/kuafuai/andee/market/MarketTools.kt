package net.kuafuai.andee.market

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The CodeFlying app market, lent to the brain as two tools.
 *
 * **Only exists in CodeFlying mode.** [enabled] is wired from `BrainConfig.mode`;
 * when it is false [tools] returns an empty array and [hasMethod] says no, so a
 * local or hub brain is byte-for-byte what it was before this class existed.
 * That is deliberate: the market is a CodeFlying capability, and the other two
 * backends answer to a different server (or none).
 *
 * **Two tools, not the whole market.** The market holds hundreds of HTTP APIs and
 * grows. Handing the model every one of them is the context-blowout that tool
 * search exists to avoid — Claude Code measured 93 tools at ~46K tokens before
 * it started deferring them. Two specs cost nothing per turn, and the round-trip
 * is only paid on the turns that actually reach outside the device:
 *
 * ```
 * market_search {query, limit}    → the handful of tools that match
 * market_call   {name, arguments} → run one of them
 * ```
 *
 * This is the same shape as Claude Code's `tool_search` → specific call, and the
 * server side of it is `POST /api/tools/search` + `POST /api/tools/call` in baas.
 *
 * **Why [call] is blocking.** The brain dispatches external tools on its timeout
 * pool ([net.kuafuai.andee.brain.LocalBrain.dispatchWithTimeout]), so a call that
 * takes seconds is exactly what that pool is for. Returning a future would buy
 * nothing and cost the deadline.
 */
class MarketTools(
    private val baseUrl: String,
    private val apiKey: String,
    private val enabled: Boolean,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // A market call is a third party's API on the far side of baas — image
        // generation and the like are slow by nature, and the model may declare a
        // budget of its own via `timeout_ms`. This has to sit **above** the
        // largest budget the model is told to use, or the socket would give up
        // before the brain does and the model would see a transport error instead
        // of the answer. The brain abandons the *wait* independently; a call that
        // outlives it keeps running on the dispatch pool.
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Built once — the specs are static, unlike the market itself. */
    private val specs: JSONArray = if (enabled) buildSpecs() else JSONArray()

    fun tools(): JSONArray = specs

    /**
     * Whether this source owns the tool called [name]. Consulted by the brain
     * before dispatch.
     *
     * [name] is the model-facing tool name (`market_search`), **not** a
     * `ToolSchemas` internal method (`screen.tap_id`). The two look alike enough
     * to be worth saying out loud: these tools have no separate internal name —
     * the brain hands the tool name straight to the dispatcher, the same way it
     * does for the call extension, and this is where that string is claimed.
     */
    fun hasMethod(name: String): Boolean = name == NAME_SEARCH || name == NAME_CALL

    /**
     * Run one of the two tools. Never throws on a tool-level failure — a bad
     * payload comes back as a readable string, because "the model gets told what
     * went wrong" beats "the turn dies".
     */
    fun call(tool: String, params: JSONObject?): Any = when (tool) {
        NAME_SEARCH -> search(params)
        NAME_CALL -> execute(params)
        else -> throw IllegalArgumentException("unknown tool: $tool")
    }

    private fun search(params: JSONObject?): JSONObject {
        val query = params?.optString("query").orEmpty().trim()
        if (query.isEmpty()) return failure("market_search needs a 'query'.")

        val limit = (params?.optInt("limit", DEFAULT_LIMIT) ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val body = JSONObject().put("query", query).put("limit", limit)
        val envelope = post("/api/tools/search", body)
            ?: return failure("market_search could not reach the market.")
        refusal(envelope)?.let { return it }

        val tools = envelope.optJSONArray("data") ?: JSONArray()
        if (tools.length() == 0) {
            return JSONObject()
                .put("tools", tools)
                .put("note", "Nothing in the market matches '$query'. Tell the user that " +
                    "rather than inventing a tool or a tool name.")
        }
        return JSONObject().put("tools", tools)
    }

    private fun execute(params: JSONObject?): JSONObject {
        val name = params?.optString("name").orEmpty().trim()
        if (name.isEmpty()) return failure("market_call needs the 'name' from a market_search result.")
        val arguments = params?.optJSONObject("arguments") ?: JSONObject()

        val body = JSONObject().put("name", name).put("arguments", arguments)
        val envelope = post("/api/tools/call", body)
            ?: return failure("market_call '$name' could not reach the market.")
        refusal(envelope)?.let { return it }

        return JSONObject().put("result", envelope.opt("data") ?: JSONObject.NULL)
    }

    /**
     * A `code != 0` envelope, turned into the reply the model should see — or
     * null when the call was fine.
     *
     * **The server's own message is the payload here.** baas validates arguments
     * against the market tool's schema before spending the upstream call, and
     * says so in the same words a person could act on ("参数 speaker 取值不合法:
     * zh_female_9，可选值: [...]"). Swallowing that into a generic "the call
     * failed" would throw away the one sentence that lets the model retry
     * correctly — and a market call that reaches the upstream and fails is
     * charged for anyway.
     */
    private fun refusal(envelope: JSONObject): JSONObject? {
        if (envelope.optInt("code", -1) == 0) return null
        val message = envelope.optString("message").ifBlank { "the market refused the call" }
        Log.w(TAG, "refused: ${message.take(200)}")
        return failure(message)
    }

    /**
     * POST [body] to a baas path and hand back the parsed envelope, or null when
     * the request never produced one. A non-2xx or unparseable body is a
     * transport failure here; a well-formed envelope carrying `code != 0` is not
     * — that is the server answering, and [refusal] decides what to do with it.
     */
    private fun post(path: String, body: JSONObject): JSONObject? {
        val req = Request.Builder()
            .url("$baseUrl$path")
            // Same credential and same shape as the LLM calls: the kft_ token in
            // Authorization. baas resolves the appId from it, which is also what
            // decides whose balance a billed tool is charged against.
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val envelope = runCatching { JSONObject(text) }.getOrNull()
                if (envelope == null) {
                    Log.w(TAG, "$path http ${resp.code}, unparseable: ${text.take(200)}")
                }
                envelope
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$path failed: ${t.message}")
            null
        }
    }

    private fun failure(text: String): JSONObject = JSONObject().put("error", text)

    private fun buildSpecs(): JSONArray = JSONArray().apply {
        put(
            JSONObject()
                .put("name", NAME_SEARCH)
                .put("description", DESC_SEARCH)
                .put(
                    "parameters", JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", JSONObject()
                                .put("query", prop("string", "What the user wants, in their own words — Chinese, English, keywords, all fine. e.g. \"查快递\", \"generate an image of a cat\", \"translate to Japanese\"."))
                                .put("limit", prop("integer", "How many tools to return. Default $DEFAULT_LIMIT."))
                        )
                        .put("required", JSONArray().put("query"))
                )
        )
        put(
            JSONObject()
                .put("name", NAME_CALL)
                .put("description", DESC_CALL)
                .put(
                    "parameters", JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", JSONObject()
                                .put("name", prop("string", "The exact 'name' from a market_search result."))
                                .put("arguments", prop("object", ARG_DESC))
                                .put("timeout_ms", prop("integer", TIMEOUT_DESC))
                        )
                        .put("required", JSONArray().put("name").put("arguments"))
                )
        )
    }

    private fun prop(type: String, description: String): JSONObject =
        JSONObject().put("type", type).put("description", description)

    private companion object {
        const val TAG = "BodyMarket"

        const val NAME_SEARCH = "market_search"
        const val NAME_CALL = "market_call"

        const val DEFAULT_LIMIT = 8
        const val MAX_LIMIT = 30

        val JSON = "application/json; charset=utf-8".toMediaType()

        val DESC_SEARCH =
            "Search the app market for a third-party capability this device does not have — " +
                "express delivery tracking, weather, news, translation, image and music generation, " +
                "SMS, stock quotes, and several hundred more. Call this FIRST whenever the user asks " +
                "for something none of the device.* or screen.* tools cover; do not tell them the " +
                "phone cannot do it before you have looked here. Returns each match's name, what it " +
                "does, and the parameters it takes — read those before calling market_call."

        val DESC_CALL =
            "Run a market tool. Pass the exact 'name' and the 'arguments' object from a " +
                "market_search result — the names are per-tool and cannot be guessed. Some tools " +
                "cost the user money and some take a while; search first. If the tool needs a " +
                "task id or a follow-up query (video and music generation do), it says so in its " +
                "result and there is a second tool in the market for that."

        val ARG_DESC =
            "The parameters object, built from the 'parameters' schema the search result showed " +
                "for this tool."

        val TIMEOUT_DESC =
            "How long to wait for this call before giving up on it. Default 60000. Most market " +
                "tools answer in a second or two, but image and video generation do not — pass " +
                "180000 for those rather than assuming the call failed. Giving up early only " +
                "abandons the wait: the tool may still be running, and it has been paid for " +
                "either way."
    }
}
