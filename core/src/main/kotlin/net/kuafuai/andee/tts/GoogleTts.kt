package net.kuafuai.andee.tts

import net.kuafuai.andee.util.Log
import net.kuafuai.andee.config.VoiceSettings
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Google text-to-speech — Gemini's `streamGenerateContent`, over plain HTTP
 * with an SSE body.
 *
 * **The shape of the protocol is the one thing to understand here: whole text
 * in, audio chunks out.** Gemini TTS does not accept streaming *input*, so
 * "speaking as the model thinks" has to be arranged by the caller — which is
 * why [finish] splits the utterance into sentences and synthesises them one
 * after another. The first sentence's audio is playing while the second is
 * still being made, and [Listener.onAudio] streams each chunk as it lands.
 *
 * Three things here are measured, not chosen:
 *
 *  * **The endpoint is `streamGenerateContent?alt=sse`, not `generateContent`.**
 *    Both are HTTP; the streaming one is what the reference gateway runs in
 *    production against this exact model, and it starts producing sound
 *    before the sentence is finished.
 *  * **`role: "user"` must be written out.** The reference records that
 *    omitting it is accepted on one Google surface and a
 *    `400 Please use a valid role` on another, and that the cost of always
 *    sending it is one field.
 *  * **`/publishers/google` in the path is not optional** — its absence is a
 *   404 with an HTML body, which reads as "wrong host" rather than "wrong
 *    path". Same reason the reference's client logs the first raw response
 *   chunk: the audio container is only ever learned from that field.
 *
 * See `GoogleAsr` for why this device speaks HTTP to Google instead of using
 * the WebSocket the vendor's own live models want.
 */
class GoogleTts(
    private val config: VoiceSettings,
    private val listener: TtsEngine.Listener,
) : TtsEngine {

    private val text = StringBuilder()

    @Volatile
    private var cancelled = false

    /** The in-flight request, so [cancel] can cut a long answer short. */
    @Volatile
    private var call: Call? = null

    /** Cleared per utterance, so the rate check reports once each time. */
    @Volatile
    private var rateChecked = false

    /** Cleared per utterance; see [request]. */
    @Volatile
    private var firstChunkLogged = false

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GoogleTts").apply { isDaemon = true }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * The session is up immediately — there is no handshake to wait for.
     *
     * **This is what makes the sample rate a declared constant rather than
     * something read off the wire**, and it is a direct consequence of
     * [TtsEngine]'s call order: the driver pushes its text from `onOpen`, so
     * `onOpen` has to come before the request that would tell us the real
     * rate. Gemini's speech output is L16 at 24 kHz, which is also what the
     * 火山 speaker reports, so the two backends hand the player the same
     * number. [checkRate] verifies it against the response and says so loudly
     * if a future model disagrees.
     */
    override fun open() {
        io.execute {
            if (cancelled) return@execute
            listener.onOpen(SAMPLE_RATE)
        }
    }

    override fun push(text: String) {
        synchronized(this) { this.text.append(text) }
    }

    override fun finish() {
        val pending = synchronized(this) { this.text.toString() }
        io.execute { runCatching { synth(pending) }.onFailure {
            // A cancel surfaces here as an IOException, and a barge-in is a
            // cancel — so logging it as a failure would put a stack trace in
            // the log every time the user interrupts the assistant, and send
            // the owner looking for a fault in a turn that worked.
            if (cancelled) return@onFailure
            Log.e(TAG, "synth failed", it)
            listener.onError(it.message ?: "tts failed")
        } }
    }

    override fun cancel() {
        cancelled = true
        runCatching { call?.cancel() }
    }

    private fun synth(pending: String) {
        if (cancelled) return
        val sentences = split(pending)
        if (sentences.isEmpty()) {
            // Nothing pronounceable — punctuation, or whitespace. The turn is
            // still over, and saying nothing is the correct reading of it.
            listener.onDone()
            return
        }

        rateChecked = false
        var sawAny = false
        for ((i, sentence) in sentences.withIndex()) {
            if (cancelled) return
            listener.onSentenceStart(sentence)
            val got = request(sentence)
            listener.onSentenceEnd(sentence)
            if (cancelled) return
            sawAny = sawAny || got
            // The first sentence is the test of whether this works at all. A
            // silent *later* sentence is a long pause and not our business; a
            // silent *first* one means the whole utterance is about to vanish
            // into nothing, and reporting it is what gets the words put up on
            // screen to be read instead.
            if (i == 0 && !got) {
                listener.onError("Google TTS returned no audio")
                return
            }
        }
        if (cancelled) return
        listener.onDone()
    }

    /**
     * One sentence, streamed. Returns whether any audio came back.
     *
     * The body is read line by line: the response is SSE (`data: {...}` per
     * line) and chunk boundaries mean nothing, so a fixed buffer size would
     * break on a long sentence.
     */
    private fun request(sentence: String): Boolean {
        val url = config.googleBaseUrl.trimEnd('/') +
            "/publishers/google/models/" + config.googleTtsModel +
            ":streamGenerateContent?alt=sse&key=" + config.googleApiKey

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json; charset=utf-8")
            .post(buildBody(sentence).toString().toRequestBody(JSON_MEDIA))
            .build()

        val c = http.newCall(request)
        call = c
        var sawAudio = false
        try {
            c.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    throw IOException("TTS HTTP ${resp.code}: ${detail(raw)}")
                }
                val body = resp.body ?: throw IOException("TTS returned an empty body")

                BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (cancelled) break
                        // The shape of this stream is only ever learned from the
                        // stream. The documentation says "audio" and does not
                        // say whether that is raw PCM, which field it hides in,
                        // or whether `alt=sse` is honoured at all — and the
                        // reference gateway logs its first chunk for exactly
                        // that reason. Skipping that line here cost a debugging
                        // round: "returned no audio" is what a wrong guess about
                        // the shape looks like from the outside.
                        //
                        // Logged *before* the `data:` test on purpose, so a
                        // response that is not SSE at all — the case where every
                        // later log line would be silent — still says something.
                        if (!firstChunkLogged && line.isNotBlank()) {
                            firstChunkLogged = true
                            Log.i(TAG, "first line of response: ${line.take(800)}")
                        }
                        if (!line.startsWith("data:")) continue
                        val payload = line.substring(5).trim()
                        if (payload.isEmpty() || payload == "[DONE]") continue

                        val chunk = runCatching { JSONObject(payload) }.getOrNull()
                        if (chunk == null) {
                            Log.w(TAG, "unparseable chunk: ${payload.take(200)}")
                            continue
                        }
                        for (pcm in audioOf(chunk)) {
                            sawAudio = true
                            listener.onAudio(pcm)
                        }
                    }
                }
            }
        } finally {
            call = null
        }
        return sawAudio
    }

    /**
     * Every audio part in one SSE chunk, decoded.
     *
     * Defensive at every level on purpose: this is a shape taken from another
     * codebase's working client rather than from a schema, and a `candidates`
     * array that is absent, empty, or carries a text part instead of audio is
     * all "no audio in this chunk", not an exception.
     */
    private fun audioOf(chunk: JSONObject): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val parts = chunk.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts") ?: return out

        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val data = inline.optString("data")
            if (data.isBlank()) continue
            checkRate(inline.optString("mimeType"))
            val pcm = runCatching { java.util.Base64.getMimeDecoder().decode(data) }.getOrNull() ?: continue
            if (pcm.isNotEmpty()) out.add(pcm)
        }
        return out
    }

    /**
     * Compare the wire's sample rate against the one we already told the
     * player, once per utterance.
     *
     * This cannot be fixed from here — `TtsEngine`'s call order means the
     * AudioTrack exists before the first response arrives — so the honest
     * thing is to say so at the exact moment it becomes knowable, with both
     * numbers, rather than let someone debug a chipmunk by ear later.
     */
    private fun checkRate(mime: String) {
        if (rateChecked || mime.isBlank()) return
        rateChecked = true
        val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: return
        if (rate != SAMPLE_RATE) {
            Log.e(TAG, "Gemini TTS returned ${rate}Hz but the player was opened at ${SAMPLE_RATE}Hz ($mime)")
        }
    }

    /**
     * The request body, and every field in it is one the reference gateway
     * arrived at by getting it wrong first: `responseModalities: ["AUDIO"]`
     * (a transcription model wants the opposite), `role` written out, and the
     * voice nested three deep in `prebuiltVoiceConfig`.
     *
     * `languageCode` is omitted rather than blanked when unset. The audio is
     * what decides how to say the words; naming a language is a hint, and an
     * empty string is not the same message as "no hint". This is the same
     * distinction the reference makes for ASR.
     */
    private fun buildBody(sentence: String): JSONObject {
        val prebuilt = JSONObject().put("voiceName", voiceFor(config.ttsSpeaker))
        val speechConfig = JSONObject().put(
            "voiceConfig",
            JSONObject().put("prebuiltVoiceConfig", prebuilt),
        )
        if (config.googleLanguage.isNotBlank()) {
            speechConfig.put("languageCode", config.googleLanguage)
        }

        return JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", sentence))),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", speechConfig),
            )
    }

    /**
     * Split into sentences, keeping the terminator attached to the sentence it
     * ends.
     *
     * The point is time-to-first-sound, not prosody: one request per sentence
     * means the first sentence is being heard while the last is still being
     * synthesised. The cost is that a sentence is synthesised without
     * knowledge of the next, which is exactly the trade the reference gateway
     * makes — a client that pushes text sentence by sentence gets a Gemini
     * call per sentence there too.
     *
     * `. ` rather than `.` in the pattern: a bare period would cut `3.5` in
     * half, and a half-sentence is spoken with a falling contour that sounds
     * like the end of a thought.
     */
    private fun split(pending: String): List<String> =
        pending.split(Regex("(?<=[。！？!?\\n])|(?<=\\. )"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.any { ch -> ch.isLetterOrDigit() } }

    /** The API's own words for what it disliked, else a truncated body. */
    private fun detail(raw: String): String = runCatching {
        JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
    }.getOrDefault("").ifBlank { raw.take(400) }

    companion object {
        private const val TAG = "GoogleTts"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Gemini speech output: signed 16-bit little-endian, mono. */
        private const val SAMPLE_RATE = 24_000

        /** Used when the ball's look names a speaker this build has no mapping for. */
        const val DEFAULT_VOICE = "Kore"

        /**
         * The character's voice, in Google's names.
         *
         * The rule this preserves is the one the whole voice story rests on —
         * **the voice is the character's**, there is no voice row for the user
         * to get wrong — and the mapping is the reference gateway's, chosen by
         * timbre rather than by personality: personality is already the look's
         * `persona` text, and picking a voice to match *that* produces a
         * character whose face and voice disagree.
         *
         * An unknown speaker is not an error. A look added later simply speaks
         * in the default; refusing to talk because a table has no row would be
         * a worse outcome than a different voice.
         */
        private val VOICE_MAP = mapOf(
            "zh_female_vv_uranus_bigtts" to "Leda",
            "zh_male_zhubajie_uranus_bigtts" to "Algenib",
            "zh_male_lubanqihao_uranus_bigtts" to "Fenrir",
            "zh_female_peiqi_uranus_bigtts" to "Zephyr",
            "zh_male_xionger_uranus_bigtts" to "Sulafat",
            "zh_female_roumeinvyou_uranus_bigtts" to "Kore",
        )

        /** [VOICE_MAP] with the default for anything it does not know. */
        fun voiceFor(speaker: String): String = VOICE_MAP[speaker] ?: DEFAULT_VOICE
    }
}
