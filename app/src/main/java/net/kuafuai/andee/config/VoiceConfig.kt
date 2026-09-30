package net.kuafuai.andee.config

import android.content.Context
import java.util.UUID


data class VoiceConfig(
    val apiKey: String,
    val asrEndpoint: String,
    val asrResourceId: String,
    val asrUid: String,
    val ttsEndpoint: String,
    val ttsResourceId: String,
    val ttsSpeaker: String,
    val ttsSampleRate: Int,
) {
    companion object {
        /**
         * The 火山 (Volcengine Speech) account the voice pipeline runs on.
         *
         * **Empty on purpose, and it must stay empty in the repository.** This
         * used to hold a real key so that a fresh install could hear and speak
         * without setup. That trade is only available to a build nobody else
         * reads: a key in public source is a key in a search index, and a bare
         * UUID is not a shape GitHub's secret scanning recognises, so nothing
         * would warn anyone it had leaked. Whoever ships a private build can
         * put their own key back here; in this tree it is the user's to supply.
         *
         * The user supplies it in ⚙ (`api_key`). Blank in prefs falls back to
         * this constant — see [load] — so with both empty the voice pipeline
         * has no credential and ASR/TTS fail at connect time. That is reported
         * *before* it happens by [net.kuafuai.andee.device.SelfCheck]'s
         * `voice_key` row rather than being left to look like a network fault.
         *
         * On storage: the key lands in `voice_prefs.xml` in plaintext, like
         * `llm_api_key`. Neither leaves the device: `allowBackup` is off and
         * both `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`
         * exclude everything, cloud backup and device transfer alike.
         */
        const val API_KEY = ""

        /**
         * The 火山 speaker the device speaks with when the ball's look has no
         * opinion of its own.
         *
         * Not a settings row anymore: the voice is a property of the character
         * ([net.kuafuai.andee.ui.ball.BallLook.voice]) and this is only the
         * fallback for the looks that shipped without one. See [lookSpeaker].
         */
        const val TTS_SPEAKER = "zh_female_vv_uranus_bigtts"

        /**
         * Keys whose blank value means "go back to the compiled default"
         * rather than "the user didn't touch this row". See [save].
         */
        private val OVERRIDE_KEYS = setOf("api_key")

        const val DEFAULT_ASR_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        const val DEFAULT_ASR_RESOURCE = "volc.bigasr.sauc.duration"
        const val DEFAULT_TTS_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
        const val DEFAULT_TTS_RESOURCE = "seed-tts-2.0"
        const val DEFAULT_TTS_SAMPLE_RATE = 24_000
        private const val PREFS = "voice_prefs"

        /**
         * Where the agent loop runs. Deliberately an explicit setting rather
         * than "hub_url is blank ⇒ go local": [save] drops blank values, so a
         * URL that has ever been saved cannot be cleared from the UI, and
         * inferring the mode from it would leave the user with no way back.
         */
        const val BRAIN_HUB = "hub"
        const val BRAIN_LOCAL = "local"

        const val DEFAULT_LLM_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_LLM_MODEL = "deepseek-flash"
        const val DEFAULT_REASONING_EFFORT = "high"

        /**
         * Hub connection settings. Not part of the voice pipeline but stored
         * in the same SharedPreferences file to keep the settings UI simple.
         * Read via [hubConfig] instead of [load].
         */
        data class HubConfig(
            val url: String,           // empty = don't dial out
            val deviceId: String,      // stable, generated once
            val deviceName: String,    // human-friendly, editable
        )

        fun hubConfig(context: Context): HubConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var deviceId = p.getString("device_id", null)
            if (deviceId.isNullOrEmpty()) {
                deviceId = "body-${UUID.randomUUID().toString().take(8)}"
                p.edit().putString("device_id", deviceId).apply()
            }
            return HubConfig(
                url = p.getString("hub_url", "").orEmpty().trim(),
                deviceId = deviceId,
                deviceName = p.getString("device_name", "").orEmpty().ifEmpty { deviceId },
            )
        }

        /**
         * Which brain answers, and how to reach it if it is the local one.
         *
         * Read via [brainConfig]; like [hubConfig] this never throws and never
         * requires the user to have configured anything — an unconfigured
         * device reads as [BRAIN_LOCAL], which is the out-of-box default.
         */
        data class BrainConfig(
            /** [BRAIN_HUB] or [BRAIN_LOCAL]. */
            val mode: String,
            val apiKey: String,        // empty = local brain cannot run
            val baseUrl: String,
            val model: String,
            val thinking: Boolean,
            val reasoningEffort: String,
        ) {
            val isLocal: Boolean get() = mode == BRAIN_LOCAL
        }

        fun brainConfig(context: Context): BrainConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Anything other than the exact string "hub" is the local brain,
            // so a typo fails closed onto the out-of-box default — same rule
            // as [groundingEnabled]. It used to fail onto the hub, back when
            // the hub was the default; the default moved, so the direction
            // moved with it, and the picker's leftmost option (the fallback
            // when the saved value matches nothing) had to move too.
            val mode = if (p.getString("brain", BRAIN_LOCAL)?.trim()?.lowercase() == BRAIN_HUB) {
                BRAIN_HUB
            } else {
                BRAIN_LOCAL
            }
            return BrainConfig(
                mode = mode,
                apiKey = p.getString("llm_api_key", "").orEmpty().trim(),
                // trimEnd('/') because the client appends "/chat/completions"
                // and a trailing slash from the settings field would produce a
                // double slash, which some gateways 404 on.
                baseUrl = p.getString("llm_base_url", DEFAULT_LLM_BASE_URL)
                    .orEmpty().trim().trimEnd('/').ifEmpty { DEFAULT_LLM_BASE_URL },
                model = p.getString("llm_model", DEFAULT_LLM_MODEL)
                    .orEmpty().trim().ifEmpty { DEFAULT_LLM_MODEL },
                thinking = p.getString("llm_thinking", "enabled")
                    ?.trim()?.lowercase() != "disabled",
                reasoningEffort = p.getString("llm_reasoning_effort", DEFAULT_REASONING_EFFORT)
                    .orEmpty().trim().ifEmpty { DEFAULT_REASONING_EFFORT },
            )
        }

        /**
         * The quiet-hour review: after the user has stopped talking for a
         * while, the local brain reads the conversation back and files what is
         * worth keeping.
         *
         * On by default (it is the feature that makes the device feel like it
         * remembers you) but always switchable, because it spends the user's
         * own API key on a call nobody asked for. [dailyCap] is the other half
         * of that bargain: a conversation that keeps stopping and restarting
         * cannot run up an unbounded bill overnight.
         */
        data class SweepConfig(
            val enabled: Boolean,
            val quietMinutes: Int,
            val dailyCap: Int,
        )

        const val DEFAULT_SWEEP_QUIET_MINUTES = 20
        const val DEFAULT_SWEEP_DAILY_CAP = 12

        fun sweepConfig(context: Context): SweepConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return SweepConfig(
                // Anything other than "off" is on: a typo should leave the
                // feature working, since off is the value a user has to go out
                // of their way to choose.
                enabled = p.getString("sweep", "on")?.trim()?.lowercase() != "off",
                quietMinutes = (p.getString("sweep_quiet_minutes", "")
                    ?.trim()?.toIntOrNull() ?: DEFAULT_SWEEP_QUIET_MINUTES).coerceIn(5, 240),
                dailyCap = (p.getString("sweep_daily_cap", "")
                    ?.trim()?.toIntOrNull() ?: DEFAULT_SWEEP_DAILY_CAP).coerceIn(0, 100),
            )
        }

        /**
         * Is the 火山 account the one saved in settings, or the factory one?
         *
         * Only needed so the answer is *visible*: a setting whose effect cannot
         * be seen is indistinguishable from one that did not take. The settings
         * card prints it, and the service logs it at start.
         */
        fun usingOwnCredentials(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("api_key", "").orEmpty().isNotBlank()

        fun load(context: Context): VoiceConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var uid = p.getString("uid", null)
            if (uid.isNullOrEmpty()) {
                uid = "tablet-body-${UUID.randomUUID().toString().take(8)}"
                p.edit().putString("uid", uid).apply()
            }
            return VoiceConfig(
                // Empty in prefs → [API_KEY], which is itself empty in the
                // public source. Trim before the fallback so a field cleared to
                // spaces behaves like a cleared field.
                apiKey = p.getString("api_key", "").orEmpty().trim().ifEmpty { API_KEY },
                asrEndpoint = p.getString("asr_endpoint", DEFAULT_ASR_ENDPOINT)!!,
                asrResourceId = p.getString("asr_resource_id", DEFAULT_ASR_RESOURCE)!!,
                asrUid = uid,
                ttsEndpoint = p.getString("tts_endpoint", DEFAULT_TTS_ENDPOINT)!!,
                ttsResourceId = p.getString("tts_resource_id", DEFAULT_TTS_RESOURCE)!!,
                // The voice follows the ball's look; [TTS_SPEAKER] is the
                // fallback for looks that don't name one.
                ttsSpeaker = lookSpeaker(p),
                ttsSampleRate = p.getInt("tts_sample_rate", DEFAULT_TTS_SAMPLE_RATE),
            )
        }

        fun save(context: Context, updates: Map<String, String>) {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            for ((k, v) in updates) {
                if (k !in ALLOWED_KEYS) continue
                // Blank normally means "the user left this row alone", and is
                // dropped so it cannot wipe a saved value. The exception is the
                // keys that have a real compiled-in default: there, blank is
                // the only way back to it. Route them before the drop, or an
                // override once saved could never be taken out.
                if (k in OVERRIDE_KEYS) {
                    if (v.isBlank()) p.remove(k) else p.putString(k, v.trim())
                    continue
                }
                if (v.isBlank()) continue
                when (k) {
                    "tts_sample_rate" ->
                        v.trim().toIntOrNull()?.let { p.putInt(k, it) }

                    else -> p.putString(k, v.trim())
                }
            }
            p.apply()
        }

        /** What [SettingsUi] shows. Only the keys it can actually edit. */
        fun current(context: Context): Map<String, String> {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val hc = hubConfig(context)
            val bc = brainConfig(context)
            return mapOf(
                // The overridable credential shows what is *saved*, not what is
                // in effect: an empty box is how the screen says "the factory
                // value is in use", and it is the state that [save] returns to.
                "api_key" to p.getString("api_key", "").orEmpty(),
                "asr_endpoint" to p.getString("asr_endpoint", DEFAULT_ASR_ENDPOINT)!!,
                "asr_resource_id" to p.getString("asr_resource_id", DEFAULT_ASR_RESOURCE)!!,
                "uid" to p.getString("uid", "").orEmpty(),
                "tts_endpoint" to p.getString("tts_endpoint", DEFAULT_TTS_ENDPOINT)!!,
                "tts_resource_id" to p.getString("tts_resource_id", DEFAULT_TTS_RESOURCE)!!,
                "tts_sample_rate" to p.getInt("tts_sample_rate", DEFAULT_TTS_SAMPLE_RATE).toString(),
                "hub_url" to hc.url,
                "device_id" to hc.deviceId,
                "device_name" to hc.deviceName,
                "brain" to bc.mode,
                "llm_api_key" to bc.apiKey,
                "llm_base_url" to bc.baseUrl,
                "llm_model" to bc.model,
                // Round-tripped as the wire words rather than a boolean: this map
                // feeds SettingsUi's pickers and the picker's value is what comes
                // back to [save], which only speaks String.
                "llm_thinking" to if (bc.thinking) "enabled" else "disabled",
                "llm_reasoning_effort" to bc.reasoningEffort,
                "grounding" to p.getString("grounding", "off")!!,
                "lang" to uiLanguage(context),
                "sweep" to p.getString("sweep", "on")!!,
                "sweep_quiet_minutes" to p.getString(
                    "sweep_quiet_minutes", DEFAULT_SWEEP_QUIET_MINUTES.toString(),
                )!!,
                "sweep_daily_cap" to p.getString(
                    "sweep_daily_cap", DEFAULT_SWEEP_DAILY_CAP.toString(),
                )!!,
            )
        }

        /**
         * Whether [net.kuafuai.andee.screen.GroundingLog] records taps and
         * screenshots to disk. Off unless explicitly turned on: it writes
         * pictures of whatever the user is doing.
         *
         * Stored as "on"/"off" rather than a boolean because [save] takes a
         * `Map<String, String>` from [SettingsUi]'s text fields, drops blanks,
         * and only routes `tts_sample_rate` through Int — a real boolean has
         * nowhere to enter the pipeline. Anything other than "on" is off, so a
         * typo fails closed.
         */
        fun groundingEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("grounding", "off")
                ?.trim()?.lowercase() == "on"

        const val LANG_ZH = "zh"
        const val LANG_EN = "en"

        /**
         * Which language the user-facing UI speaks. [LANG_ZH] or [LANG_EN].
         *
         * Unset means *unset*, not Chinese: it falls through to the device's own
         * locale, because a tablet set to English showing a Chinese settings
         * card is the same bug as the reverse. Once the user picks one, the
         * saved value wins over the locale forever — they have said what they
         * want and a system language change should not overrule them.
         *
         * Not part of [current] merely for display: [net.kuafuai.andee.ui.SettingsUi]
         * writes this one the moment it is tapped rather than on 保存, because
         * the card redraws in the new language and that redraw *is* the
         * confirmation. Nothing else on this device is written eagerly.
         */
        fun uiLanguage(context: Context): String {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("lang", "").orEmpty().trim().lowercase()
            if (saved == LANG_EN) return LANG_EN
            if (saved == LANG_ZH) return LANG_ZH
            return if (java.util.Locale.getDefault().language == "zh") LANG_ZH else LANG_EN
        }

        /**
         * Which face the ball is wearing — a [net.kuafuai.andee.ui.ball.BallLook]
         * name, or blank for the first one.
         *
         * Like [uiLanguage] and unlike everything else here, it is written the
         * moment the user swipes rather than on 保存, and for the same reason:
         * the ball changing *is* the confirmation, and there is no card to
         * press a button on. It is also not in [current] — it has no settings
         * row, because the control is the ball itself.
         *
         * It is read from more than the ball now: the look carries the voice and
         * the manner as well as the drawing, so [lookSpeaker] and `LocalBrain`
         * both resolve it through [net.kuafuai.andee.ui.ball.BallLooks.byName].
         */
        fun ballLook(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("ball", "").orEmpty().trim()

        /**
         * The 火山 speaker for the ball's current look, falling back to
         * [TTS_SPEAKER].
         *
         * The whole voice story in one line: **the voice is the character's**.
         * A look either names one or it does not; the user has no voice row to
         * fill in, so there is no precedence to get wrong and no override to
         * silently block a character's voice. [TTS_SPEAKER] survives only as the
         * fallback for looks that shipped without an opinion.
         *
         * Lived at [load]'s own file rather than in `TtsController` for the same
         * reason the settings card used to need a "voice in use" row: [load] is
         * read by the ASR path, the meeting recorder and the start log as well,
         * and a speaker resolved at the call site would have `HuoshanTts`
         * logging one voice while `ScreenBodyService` logged another.
         */
        private fun lookSpeaker(
            p: android.content.SharedPreferences,
        ): String = net.kuafuai.andee.ui.ball.BallLooks
            .byName(p.getString("ball", "").orEmpty().trim()).voice
            .ifEmpty { TTS_SPEAKER }

        // api_key used to be absent here, because it was compiled in and a saved
        // pref would have shadowed the constant with no way to see it from the
        // UI. The constant ships empty in the public source, so the pref is now
        // the only place a key can come from and this row has to be editable.
        // Same class of credential as llm_api_key, which was always editable
        // because a DeepSeek key is the user's own. (tts_speaker was once
        // editable too, and is gone: the voice follows the ball's look, not a
        // row — see [lookSpeaker].)
        private val ALLOWED_KEYS = setOf(
            "uid",
            "api_key",
            "asr_endpoint", "asr_resource_id",
            "tts_endpoint", "tts_resource_id", "tts_sample_rate",
            "hub_url", "device_name",
            "brain",
            "llm_api_key", "llm_base_url", "llm_model",
            "llm_thinking", "llm_reasoning_effort",
            "grounding",
            "lang",
            "ball",
            "sweep", "sweep_quiet_minutes", "sweep_daily_cap",
        )
    }
}
