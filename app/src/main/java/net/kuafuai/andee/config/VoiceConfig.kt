package net.kuafuai.andee.config

import android.content.Context
import net.kuafuai.andee.BuildConfig
import java.util.UUID

/**
 * Reads and writes the phone's settings (`voice_prefs`). Everything here used
 * to be the companion of the settings data class, which now lives in the shared
 * module as [VoiceSettings].
 */
object VoiceConfig {
        /**
         * The 火山 (Volcengine Speech) account the voice pipeline runs on when
         * the user is on the **本机** backend. On [BRAIN_CODEFLYING], the key
         * is derived from [BuildConfig.CODEFLYING_KEY] and this field is not
         * read.
         *
         * **Empty on purpose, and it must stay empty in the repository.** A key
         * in public source is a key in a search index, and a bare UUID is not a
         * shape GitHub's secret scanning recognises. Whoever ships a private
         * build either puts the key behind CodeFlying or asks the user to type
         * it in ⚙ (`api_key`).
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
        internal val OVERRIDE_KEYS = setOf(
            "api_key", "asr_endpoint", "tts_endpoint",
            // Blank goes back to the key the build carries.
            "codeflying_key",
            // The Google rows all have a real value to fall back to — the
            // build's, or the constant — so an empty box means "use that",
            // which is the only way back to a baked-in key.
            "google_api_key", "google_project", "google_base_url", "google_location",
            "google_asr_model", "google_tts_model", "google_language",
        )

        const val DEFAULT_ASR_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
        const val DEFAULT_ASR_RESOURCE = "volc.bigasr.sauc.duration"
        const val DEFAULT_TTS_ENDPOINT = "wss://openspeech.bytedance.com/api/v3/tts/bidirection"
        const val DEFAULT_TTS_RESOURCE = "seed-tts-2.0"
        const val DEFAULT_TTS_SAMPLE_RATE = 24_000
        private const val PREFS = "voice_prefs"

        /**
         * The two cloud providers, valid on both the hearing and speaking
         * sides. See [voiceProvider] and [speakProvider].
         *
         * A string and not a boolean because the list is not closed: the
         * speaking side already adds [VOICE_SYSTEM], and the picker, the
         * self-check rows and the engines all branch on the string, so a new
         * provider is a value rather than a second flag.
         */
        const val VOICE_HUOSHAN = VoiceSettings.VOICE_HUOSHAN
        const val VOICE_GOOGLE = VoiceSettings.VOICE_GOOGLE

        /**
         * The device's own text-to-speech engine. A speak-side only option: a
         * system engine can read aloud but has no recogniser, so it never
         * appears on the listening side.
         */
        const val VOICE_SYSTEM = "system"

        /**
         * The Vertex endpoint, its Express-keyed surface.
         *
         * Not a region-scoped host: Express mode takes an API key and no
         * project or location, which is what makes the Google path a single
         * settings row instead of the five the reference gateway needs.
         */
        const val DEFAULT_GOOGLE_BASE_URL = "https://aiplatform.googleapis.com/v1"

        /**
         * The region the Live API session is opened in.
         *
         * The reference gateway measured both this and `global` handshaking
         * successfully; a real region is the default because it is the one that
         * cannot silently route a device somewhere it has no business being.
         */
        const val DEFAULT_GOOGLE_LOCATION = "asia-northeast1"

        /**
         * The transcription model, and **the `-live` in its name is the whole
         * reason the Google listening path is a WebSocket**.
         *
         * Google's dedicated transcription models are served only by the Live
         * API; the same id on `generateContent` is a 400 "is not supported in
         * the generateContent API". So "no WebSocket" and "a real transcription
         * model" are not both available, and this build takes the model — a
         * general Gemini would have to be *told* to transcribe verbatim and
         * could decide otherwise, on a transcript the meeting recorder writes
         * to disk as the record of what was said.
         *
         * The `-preview` suffix is not optional either: without it the name is
         * a 404. Both facts are the reference gateway's, measured.
         */
        const val DEFAULT_GOOGLE_ASR_MODEL = "gemini-3.5-transcribe-live-preview"

        /**
         * The speaking model.
         *
         * The reference gateway records why this exact id and not the newer
         * one: `3.8-flash-tts` exists on Gemini's own developer API but not on
         * Vertex, where the speech models are 3.1 Flash TTS and below, and
         * asking for 3.8 is a plain "model not found".
         */
        const val DEFAULT_GOOGLE_TTS_MODEL = "gemini-3.1-flash-tts-preview"

        /**
         * What the Google rows resolve to on a device where the user has typed
         * nothing: **the build's value if it carries one, otherwise the
         * constant above**.
         *
         * This is [llmModelDefault]'s shape for [llmModelDefault]'s reason. The
         * hint under each row *is* the value a blank resolves to, so the hint
         * and the resolved config have to come from the same call — a card that
         * advertises one model and silently calls another is lying in the one
         * place a user reads to decide what to type.
         *
         * The values come from `local.properties` through `BuildConfig` and
         * never from this file. A key committed to a repository is a key in a
         * search index, and a bare Vertex key is not a shape secret scanning
         * recognises — which is exactly why [API_KEY] ships empty and why this
         * one does too. Whoever builds an APK for Google puts the key in their
         * own `local.properties`, which `.gitignore` excludes; whoever clones
         * the repository gets empty strings here and the constants below.
         *
         * A build with [googleApiKeyDefault] non-empty is also what makes 谷歌
         * the default provider — see [voiceProvider].
         */
        fun googleApiKeyDefault(): String = BuildConfig.GOOGLE_VOICE_KEY.trim()

        /** See [googleApiKeyDefault]. Empty means "no project baked in". */
        fun googleProjectDefault(): String = BuildConfig.GOOGLE_VOICE_PROJECT.trim()

        /** See [googleApiKeyDefault]. */
        fun googleLocationDefault(): String =
            BuildConfig.GOOGLE_VOICE_LOCATION.trim().ifEmpty { DEFAULT_GOOGLE_LOCATION }

        /** See [googleApiKeyDefault]. */
        fun googleBaseUrlDefault(): String =
            BuildConfig.GOOGLE_VOICE_BASE_URL.trim().trimEnd('/').ifEmpty { DEFAULT_GOOGLE_BASE_URL }

        /** See [googleApiKeyDefault]. */
        fun googleAsrModelDefault(): String =
            BuildConfig.GOOGLE_VOICE_ASR_MODEL.trim().ifEmpty { DEFAULT_GOOGLE_ASR_MODEL }

        /** See [googleApiKeyDefault]. */
        fun googleTtsModelDefault(): String =
            BuildConfig.GOOGLE_VOICE_TTS_MODEL.trim().ifEmpty { DEFAULT_GOOGLE_TTS_MODEL }

        /**
         * Where the agent loop runs. Deliberately an explicit setting rather
         * than "hub_url is blank ⇒ go local": [save] drops blank values, so a
         * URL that has ever been saved cannot be cleared from the UI, and
         * inferring the mode from it would leave the user with no way back.
         *
         * [BRAIN_CODEFLYING] is a hosted backend that proxies the LLM, ASR
         * and TTS from one domain. In this mode every endpoint and credential
         * is derived from `BuildConfig.CODEFLYING_*` and the user does not
         * fill in any voice / LLM fields.
         */
        const val BRAIN_HUB = net.kuafuai.andee.config.BrainConfig.BRAIN_HUB
        const val BRAIN_LOCAL = net.kuafuai.andee.config.BrainConfig.BRAIN_LOCAL
        const val BRAIN_CODEFLYING = net.kuafuai.andee.config.BrainConfig.BRAIN_CODEFLYING

        const val DEFAULT_LLM_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_LLM_MODEL = "deepseek-flash"
        const val DEFAULT_REASONING_EFFORT = "high"

        /**
         * The same two defaults for an **English** card, pointing at OpenAI.
         *
         * The 本机 tab's hint *is* the value a blank row resolves to, so the two
         * cannot be allowed to disagree: a card that advertises an OpenAI `Model`
         * / `Base URL` and then, when the user leaves the boxes empty, quietly
         * calls DeepSeek would be lying in the one place a user reads to decide
         * what to type. [llmBaseUrlDefault] / [llmModelDefault] pick by
         * [uiLanguage], and both the hint and the resolved config go through
         * them — that is the whole point of tying these to the language rather
         * than to the build.
         *
         * **CodeFlying is deliberately not switched by language.** Its model is
         * whatever the hosted backend serves; an English build inventing
         * `gpt-4o-mini` at that endpoint would name a model CodeFlying does not
         * have. See [brainConfig].
         */
        const val DEFAULT_LLM_BASE_URL_EN = "https://api.openai.com/v1"
        const val DEFAULT_LLM_MODEL_EN = "gpt-4o-mini"

        /** [DEFAULT_LLM_BASE_URL] for a Chinese card, [DEFAULT_LLM_BASE_URL_EN] for an English one. */
        fun llmBaseUrlDefault(context: Context): String =
            if (uiLanguage(context) == LANG_EN) DEFAULT_LLM_BASE_URL_EN else DEFAULT_LLM_BASE_URL

        /** [DEFAULT_LLM_MODEL] for a Chinese card, [DEFAULT_LLM_MODEL_EN] for an English one. */
        fun llmModelDefault(context: Context): String =
            if (uiLanguage(context) == LANG_EN) DEFAULT_LLM_MODEL_EN else DEFAULT_LLM_MODEL

        /**
         * The CodeFlying server, from the build's `CODEFLYING_DOMAIN`. There is no
         * "available" switch: CodeFlying is always a choice and the default one, and
         * a build packaged without a domain simply has nowhere to send it yet.
         */
        fun codeFlyingEndpoint(@Suppress("UNUSED_PARAMETER") context: Context): net.kuafuai.andee.config.CodeFlyingEndpoint? =
            net.kuafuai.andee.config.CodeFlyingEndpoint.parse(
                BuildConfig.CODEFLYING_DOMAIN, BuildConfig.CODEFLYING_SSL_ENABLED,
            )

        /**
         * The CodeFlying API key in effect for this install.
         *
         * A key the user typed (`codeflying_key`) wins. Otherwise
         * `assets/andee_key.txt`: production APKs are built once from shared
         * source (empty key) and the per-user `kft_` token is written into that
         * asset at package time. Dev builds fall back to
         * [BuildConfig.CODEFLYING_KEY] from local.properties.
         */
        fun codeFlyingKey(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("codeflying_key", "").orEmpty().trim()
                .ifEmpty { codeFlyingBuiltInKey(context) }

        /** The key that came with the APK, ignoring anything typed. */
        fun codeFlyingBuiltInKey(context: Context): String =
            runCatching {
                context.assets.open("andee_key.txt").bufferedReader().use { it.readText().trim() }
            }.getOrDefault("").ifEmpty { BuildConfig.CODEFLYING_KEY }

        /** Origin for the market tools; see [net.kuafuai.andee.config.CodeFlyingEndpoint.marketBaseUrl]. */
        fun codeFlyingMarketBaseUrl(context: Context): String =
            codeFlyingEndpoint(context)?.marketBaseUrl.orEmpty()

        fun codeFlyingLlmBaseUrl(context: Context): String =
            codeFlyingEndpoint(context)?.llmBaseUrl.orEmpty()

        fun codeFlyingAsrEndpoint(context: Context): String =
            codeFlyingEndpoint(context)?.asrEndpoint.orEmpty()

        fun codeFlyingTtsEndpoint(context: Context): String =
            codeFlyingEndpoint(context)?.ttsEndpoint.orEmpty()

        /**
         * Which palette the UI draws with. `true` = night, and false = day.
         *
         * **Day (light) is the default.** A fresh install comes up light, and so does
         * a device that has never touched the setting — which is why the test is
         * "is night" rather than "is not day". The dark palette is one tap away in
         * 设置 → 外观 and is remembered from then on.
         *
         * The inversion is on purpose and it is the whole content of this function.
         * The test is written against the *non-default* value rather than the default
         * one so that an explicit choice always wins and nothing else has to: only
         * the string the picker actually wrote can turn night on, so no rewrite of
         * the default can silently overrule a user who picked dark.
         *
         * Read straight off disk rather than out of [current], and that matters here:
         * [net.kuafuai.andee.ui.Theme] reads this once at process start and again on
         * every change, so the answer has to be the file's, not a snapshot of the
         * other rows that happens to be in memory.
         *
         * Only the two strings are meaningful; the "night" one is written by the
         * picker as [net.kuafuai.andee.ui.Theme.NIGHT].
         */
        fun nightTheme(context: Context): Boolean {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("theme", "").orEmpty()
            return nightFromSaved(saved)
        }

        /**
         * The rule [nightTheme] implements, separated so it can be tested without a
         * device — there is no Robolectric here, and the default this decides is not
         * observable until something is drawn.
         *
         * `internal` rather than private for that reason alone. A theme default is
         * exactly the kind of value that gets inverted by a later "make it dark again"
         * request, and the two things that must move together (this and `Theme.night`'s
         * initial value) are in different files.
         */
        internal fun nightFromSaved(saved: String): Boolean =
            // Only an explicit "night" turns night on; absent or unrecognised reads as
            // day, so the default cannot be lost by a build that never wrote it, and an
            // explicit choice can never be overruled by a change to the default.
            saved.trim().lowercase() == "night"

        /** Whether to show the 云端 (hub) tab in the backend picker. Default off. */
        fun showHub(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("show_hub", "")?.trim()?.lowercase() == "on"

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

        /** Moved to the shared module; see [net.kuafuai.andee.config.BrainConfig]. */
        // Nested name kept so existing callers and the settings card read unchanged.

        /**
         * The brain mode in effect, with CodeFlying availability already
         * reconciled. Reads the saved `brain` pref; falls back to CodeFlying
         * if the build enables it and otherwise to local. Unknown saved values
         * fail closed to local — same rule as [groundingEnabled].
         */
        fun brainMode(context: Context): String {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return resolveBrainMode(context, p.getString("brain", ""))
        }

        /**
         * [raw] — a saved value, or one the user has just picked and **not yet
         * saved** — put through the rule above.
         *
         * Split out because the settings card has to ask about the second
         * kind. The brain picker does not write on tap, so when the user
         * selects 本机 out of CodeFlying the card is holding a value no pref
         * reflects yet, and asking [brainMode] there answers about the mode
         * being left — which is how the voice block came to stay hidden until
         * 保存 rebuilt the card.
         *
         * **One rule, one place.** A second copy of "is CodeFlying really in
         * play" is how a build that does not enable it ends up hiding the very
         * voice settings it is reading from.
         */
        fun resolveBrainMode(context: Context, raw: String?): String {
            val saved = raw?.trim()?.lowercase().orEmpty()
            return when (saved) {
                BRAIN_HUB -> BRAIN_HUB
                BRAIN_LOCAL -> BRAIN_LOCAL
                BRAIN_CODEFLYING -> BRAIN_CODEFLYING
                "" -> BRAIN_CODEFLYING
                else -> BRAIN_LOCAL
            }
        }

        fun brainConfig(context: Context): BrainConfig {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val mode = brainMode(context)
            // CodeFlying derives everything from the build — the user does not
            // see or edit the endpoints. llm_model / thinking / effort are
            // deliberately *still* read from prefs so the same picker in
            // [SettingsUi] applies to both local and CodeFlying.
            if (mode == BRAIN_CODEFLYING) {
                return BrainConfig(
                    mode = BRAIN_CODEFLYING,
                    apiKey = codeFlyingKey(context),
                    baseUrl = codeFlyingLlmBaseUrl(context),
                    model = p.getString("llm_model", DEFAULT_LLM_MODEL)
                        .orEmpty().trim().ifEmpty { DEFAULT_LLM_MODEL },
                    thinking = p.getString("llm_thinking", defaultThinking())
                        ?.trim()?.lowercase() != "disabled",
                    reasoningEffort = p.getString("llm_reasoning_effort", DEFAULT_REASONING_EFFORT)
                        .orEmpty().trim().ifEmpty { DEFAULT_REASONING_EFFORT },
                )
            }
            // Language-dependent defaults: the blank-row fallback must be the
            // value the 本机 tab's hint advertised, or an English card would
            // promise OpenAI in the placeholder and then call DeepSeek.
            val defaultBaseUrl = llmBaseUrlDefault(context)
            val defaultModel = llmModelDefault(context)
            return BrainConfig(
                mode = mode,
                apiKey = p.getString("llm_api_key", "").orEmpty().trim(),
                // trimEnd('/') because the client appends "/chat/completions"
                // and a trailing slash from the settings field would produce a
                // double slash, which some gateways 404 on.
                baseUrl = p.getString("llm_base_url", "")
                    .orEmpty().trim().trimEnd('/').ifEmpty { defaultBaseUrl },
                model = p.getString("llm_model", defaultModel)
                    .orEmpty().trim().ifEmpty { defaultModel },
                thinking = p.getString("llm_thinking", defaultThinking())
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

        /** Nothing pushes. Notifications still land in the ring buffer. */
        const val NOTIFY_OFF = "off"

        /** The chat/SMS/mail apps — the set that already interrupted the user. */
        const val NOTIFY_CHAT = "chat"

        /** Every app, minus the structural noise — see the relay's own filter. */
        const val NOTIFY_ALL = "all"

        /**
         * Which notifications the device is allowed to think about by itself.
         *
         * **The default is [NOTIFY_CHAT] and the fallback is [NOTIFY_OFF], and
         * those are deliberately different values.** An unset preference is a
         * device nobody has configured, and on it the honest scope is the one
         * that already interrupted the user before triage existed — so turning
         * the feature on changed *how* a chat message is handled, not *which*
         * apps are watched. An unrecognised string is a different thing
         * entirely: a typo, a downgrade, a setting written by a version that
         * knew a value this one does not. Nothing is known about what it was
         * meant to permit, so it permits nothing. Same fail-closed direction as
         * [groundingEnabled], and the picker lists 关 leftmost to match — see
         * [net.kuafuai.andee.ui.SettingsUi.segmented], whose leftmost option is
         * the fallback for exactly this case.
         *
         * Scope is all this decides. What *happens* to an in-scope notification
         * is [net.kuafuai.andee.brain.NotificationTriage]'s call, and when that
         * cannot run the signboard comes back — the user is the filter again,
         * which is what this device did before any of it existed.
         */
        fun notifyScope(context: Context): String {
            val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("notify", NOTIFY_CHAT)?.trim()?.lowercase()
            return when (v) {
                NOTIFY_ALL -> NOTIFY_ALL
                NOTIFY_CHAT -> NOTIFY_CHAT
                else -> NOTIFY_OFF
            }
        }

        /**
         * Which service hears: [VOICE_HUOSHAN] or [VOICE_GOOGLE]. The speaking
         * side is a separate choice — see [speakProvider] — because a system
         * engine can read aloud but has no recogniser.
         *
         * **Anything that is not exactly `google` reads as 火山**, which is the
         * same fail-closed direction [groundingEnabled] and [notifyScope] take,
         * and here it is also the backward-compatible one: every device that
         * predates this setting has no value at all, and 火山 is what they have
         * been running. A device that upgrades must not change voice because a
         * key it never had is now missing.
         *
         * **CodeFlying is pinned to 火山, and that is the point of it.**
         * CodeFlying is the one-tap mode: the APK is packaged by an operator and
         * handed to someone who configures nothing, so [load] takes every
         * endpoint *and* credential from the build and the whole errand is the
         * gateway's. Which means the gateway is also where the choice of
         * upstream lives — its 火山 support and its Google support are two
         * handlers behind one URL, and a client that second-guessed that by
         * dialling Google itself with a key baked into the APK would be
         * choosing a different vendor and a different bill than the operator
         * did. So the saved value is ignored here, not merely outranked.
         *
         * On any other backend the picker is the user's: their key, their
         * choice, and a saved value outranks the build's default.
         */
        fun voiceProvider(context: Context): String {
            if (brainMode(context) == BRAIN_CODEFLYING) return VOICE_HUOSHAN
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("voice_provider", "").orEmpty().trim().lowercase()
            return when (saved) {
                VOICE_GOOGLE -> VOICE_GOOGLE
                VOICE_HUOSHAN -> VOICE_HUOSHAN
                // Never chosen. A build packaged with a Google key is a build
                // packaged *for* Google, so that is what it reads as; a build
                // without one reads as 火山, which is what every build did
                // before this setting existed. Either way the picker is one tap
                // away, and a user's own choice always outranks this.
                "" -> if (googleApiKeyDefault().isNotEmpty()) VOICE_GOOGLE else VOICE_HUOSHAN
                else -> VOICE_HUOSHAN
            }
        }

        /**
         * Which service speaks: [VOICE_HUOSHAN], [VOICE_GOOGLE] or
         * [VOICE_SYSTEM]. The speaking and hearing sides are now two separate
         * choices, because a system engine reads aloud but cannot hear — so
         * [voiceProvider] (the hearing side) keeps only 火山 / Google, while
         * this one may add the device's own engine.
         *
         * An unset value falls back to [voiceProvider], so a device that
         * predates this row speaks exactly as it did before; a saved choice
         * always wins. CodeFlying is pinned to 火山 for the same reason as
         * [voiceProvider] — the hosted gateway owns the upstream choice.
         */
        fun speakProvider(context: Context): String {
            if (brainMode(context) == BRAIN_CODEFLYING) return VOICE_HUOSHAN
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("speak_provider", "").orEmpty().trim().lowercase()
            return when (saved) {
                VOICE_GOOGLE -> VOICE_GOOGLE
                VOICE_SYSTEM -> VOICE_SYSTEM
                VOICE_HUOSHAN -> VOICE_HUOSHAN
                "" -> voiceProvider(context)
                else -> VOICE_HUOSHAN
            }
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

        fun load(context: Context): VoiceSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var uid = p.getString("uid", null)
            if (uid.isNullOrEmpty()) {
                uid = "tablet-body-${UUID.randomUUID().toString().take(8)}"
                p.edit().putString("uid", uid).apply()
            }
            // On CodeFlying every endpoint and credential is derived from the
            // build — the user never sees these fields. On 本机 the prefs rule
            // (user's own 火山 account); 云端 shares the same prefs because
            // ASR/TTS still run on this device whichever brain answers.
            val codeFlying = brainMode(context) == BRAIN_CODEFLYING
            return VoiceSettings(
                apiKey = if (codeFlying) codeFlyingKey(context)
                else p.getString("api_key", "").orEmpty().trim().ifEmpty { API_KEY },
                asrEndpoint = if (codeFlying) codeFlyingAsrEndpoint(context)
                else p.getString("asr_endpoint", null)
                    ?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_ASR_ENDPOINT,
                asrResourceId = p.getString("asr_resource_id", DEFAULT_ASR_RESOURCE)!!,
                asrUid = uid,
                ttsEndpoint = if (codeFlying) codeFlyingTtsEndpoint(context)
                else p.getString("tts_endpoint", null)
                    ?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_TTS_ENDPOINT,
                ttsResourceId = p.getString("tts_resource_id", DEFAULT_TTS_RESOURCE)!!,
                // The voice follows the ball's look; [TTS_SPEAKER] is the
                // fallback for looks that don't name one. The same speaker
                // string is what the Google path maps to a Gemini voice, so
                // this stays the one place a voice is decided.
                ttsSpeaker = lookSpeaker(p),
                ttsSampleRate = p.getInt("tts_sample_rate", DEFAULT_TTS_SAMPLE_RATE),
                voiceProvider = voiceProvider(context),
                // Every one of these is saved-value-else-build-default, and the
                // card's hint under each row is the same call — see
                // [googleApiKeyDefault]. On a build with nothing baked in that
                // reduces to exactly what it was before: the constants.
                googleApiKey = p.getString("google_api_key", "").orEmpty().trim()
                    .ifEmpty { googleApiKeyDefault() },
                googleBaseUrl = p.getString("google_base_url", "").orEmpty().trim()
                    .trimEnd('/').ifEmpty { googleBaseUrlDefault() },
                // The project number has no constant to fall back to — there is
                // no sensible one to invent, and the failure a missing one
                // produces is a 1008 naming the resource, which says what is
                // wrong better than an invented value could.
                googleProject = p.getString("google_project", "").orEmpty().trim()
                    .ifEmpty { googleProjectDefault() },
                googleLocation = p.getString("google_location", "").orEmpty().trim()
                    .ifEmpty { googleLocationDefault() },
                googleAsrModel = p.getString("google_asr_model", "").orEmpty().trim()
                    .ifEmpty { googleAsrModelDefault() },
                googleTtsModel = p.getString("google_tts_model", "").orEmpty().trim()
                    .ifEmpty { googleTtsModelDefault() },
                // Blank is a real value here, not a missing one — see the
                // field's own note. It must not fall through to a default.
                googleLanguage = p.getString("google_language", "").orEmpty().trim(),
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
                // The card shows the key in effect, so saving it back unchanged must not
                // pin the build's key as a user override: store only a different one.
                if (k == "codeflying_key" && v.trim() == codeFlyingBuiltInKey(context)) {
                    p.remove(k)
                    continue
                }
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
                // asr_endpoint/tts_endpoint joined api_key here: they are also
                // overridable from local.properties now, so the settings card
                // must show saved-or-empty rather than saved-or-default.
                "api_key" to p.getString("api_key", "").orEmpty(),
                "codeflying_key" to codeFlyingKey(context),
                "asr_endpoint" to p.getString("asr_endpoint", "").orEmpty(),
                "asr_resource_id" to p.getString("asr_resource_id", DEFAULT_ASR_RESOURCE)!!,
                "uid" to p.getString("uid", "").orEmpty(),
                "tts_endpoint" to p.getString("tts_endpoint", "").orEmpty(),
                "tts_resource_id" to p.getString("tts_resource_id", DEFAULT_TTS_RESOURCE)!!,
                "tts_sample_rate" to p.getInt("tts_sample_rate", DEFAULT_TTS_SAMPLE_RATE).toString(),
                // Which service hears and speaks, plus its rows. The Google
                // endpoint/model rows show saved-or-empty for the same reason
                // the 火山 ones do: an empty box is the only state in which
                // the hint — which is the value a blank resolves to — can be
                // seen at all.
                "voice_provider" to voiceProvider(context),
                "speak_provider" to speakProvider(context),
                "google_api_key" to p.getString("google_api_key", "").orEmpty(),
                "google_base_url" to p.getString("google_base_url", "").orEmpty(),
                "google_project" to p.getString("google_project", "").orEmpty(),
                "google_location" to p.getString("google_location", "").orEmpty(),
                "google_asr_model" to p.getString("google_asr_model", "").orEmpty(),
                "google_tts_model" to p.getString("google_tts_model", "").orEmpty(),
                "google_language" to p.getString("google_language", "").orEmpty(),
                "hub_url" to hc.url,
                "device_id" to hc.deviceId,
                "device_name" to hc.deviceName,
                // What the card selects: nothing saved is CodeFlying (the default tab for a
                // new install), whether or not this build can run it — the tab then says so.
                // Not bc.mode, which has CodeFlying already resolved away to local.
                "brain" to p.getString("brain", "").orEmpty().trim().lowercase().ifEmpty { BRAIN_CODEFLYING },
                "show_hub" to if (showHub(context)) "on" else "off",
                // llm_api_key / llm_base_url / llm_model are the 本机 tab's rows —
                // the user's own credentials and the endpoint they point at.
                // They stay visible no matter which backend is active, because
                // switching to CodeFlying and back must not look like the
                // local-tab fields were wiped. The compiled CodeFlying key never
                // flows through these prefs; it comes from BuildConfig at
                // [brainConfig] time, so showing the saved values here cannot
                // leak it.
                //
                // All three show saved-or-empty, never the resolved value. The
                // empty box is how the row says "the default is in use", and it
                // is the only state in which the hint is visible at all —
                // printing the resolved default into the box would be text, not
                // a placeholder, and the row's language-aware hint
                // ([llmModelDefault]: DeepSeek for a Chinese card, OpenAI for an
                // English one) could never be seen.
                "llm_api_key" to p.getString("llm_api_key", "").orEmpty(),
                "llm_base_url" to p.getString("llm_base_url", "").orEmpty(),
                "llm_model" to p.getString("llm_model", "").orEmpty(),
                // Round-tripped as the wire words rather than a boolean: this map
                // feeds SettingsUi's pickers and the picker's value is what comes
                // back to [save], which only speaks String.
                "llm_thinking" to if (bc.thinking) "enabled" else "disabled",
                "llm_reasoning_effort" to bc.reasoningEffort,
                "grounding" to p.getString("grounding", "off")!!,
                "notify" to notifyScope(context),
                "lang" to uiLanguage(context),
                "sweep" to p.getString("sweep", "on")!!,
                "sweep_quiet_minutes" to p.getString(
                    "sweep_quiet_minutes", DEFAULT_SWEEP_QUIET_MINUTES.toString(),
                )!!,
                "sweep_daily_cap" to p.getString(
                    "sweep_daily_cap", DEFAULT_SWEEP_DAILY_CAP.toString(),
                )!!,
                "max_steps" to maxSteps(context).toString(),
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

        /**
         * Thinking for a device that has never saved the setting: the build's
         * `DEFAULT_THINKING` (local.properties, `on` / `off`), else on. Only the
         * unset case — a saved choice always wins, same as [uiLanguage].
         */
        private fun defaultThinking(): String =
            if (BuildConfig.DEFAULT_THINKING.trim().lowercase() == "off") "disabled" else "enabled"

        /**
         * Hard cap on tool-call steps per task. A real task is a dozen steps;
         * this is the runaway guard, and hitting it produces a final answer
         * rather than silence. Editable from the settings card so a user who
         * routinely runs longer tasks can raise it; coerced to a sane range
         * (1..200) so a stray zero doesn't make the brain refuse everything
         * and a stray thousand doesn't burn an unbounded bill.
         */
        const val DEFAULT_MAX_STEPS = 25

        fun maxSteps(context: Context): Int =
            (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("max_steps", "")?.trim()?.toIntOrNull()
                ?: DEFAULT_MAX_STEPS).coerceIn(1, 200)

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
        /** The user turned reading answers aloud off (the card's speaker key). Off by default, as on the Mac. */
        fun muted(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mute", "") == "1"

        fun setMuted(context: Context, on: Boolean) = save(context, mapOf("mute" to if (on) "1" else "0"))

        fun uiLanguage(context: Context): String {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("lang", "").orEmpty().trim().lowercase()
            if (saved == LANG_EN) return LANG_EN
            if (saved == LANG_ZH) return LANG_ZH
            // The build's `DEFAULT_LANG` (local.properties) is the language of a
            // device nobody has picked for yet. It sits below the saved choice
            // and above the system locale; anything but zh/en reads as unset.
            when (BuildConfig.DEFAULT_LANG.trim().lowercase()) {
                LANG_EN -> return LANG_EN
                LANG_ZH -> return LANG_ZH
            }
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
         * Whether the wake phrase is still listened for with the screen off.
         *
         * **On unless the user turns it off.** That default is the whole point
         * of the switch: the screen being off is the normal state of a phone in
         * a pocket, so the older rule — hold the microphone only while the
         * screen was on — meant the assistant could not be called from exactly
         * the place a person keeps their phone. The gate in
         * [net.kuafuai.andee.wake.WakeWord] used to spell that rule out
         * unconditionally; this is the user's override of it.
         *
         * What it costs, and why the settings row that flips it says so out
         * loud: with the screen off the microphone is held continuously, so the
         * platform's recording indicator stays lit and the segmenter runs for as
         * long as the device is awake. Nothing recoverable as speech leaves the
         * phone either way — see [net.kuafuai.andee.wake.WakeWord] — but
         * "listening all the time" is a claim the user is entitled to see stated
         * before they agree to it rather than discover on a status bar.
         *
         * Read from the wake supervisor's gate, which reconsiders ten times a
         * second. Cheap (SharedPreferences answers from an already-loaded map)
         * and deliberately not cached anywhere: the settings card writes it and
         * the very next poll has to see it.
         */
        fun wakeWhileLocked(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean("wake_locked", true)

        fun setWakeWhileLocked(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("wake_locked", on).apply()
        }

        /**
         * The scene the assistant is in, by [net.kuafuai.andee.config.Scene.name], or null.
         *
         * Persisted for the same reason as [ballLook]: a service restart is not
         * the user leaving the scene. Written directly rather than through
         * [save], because [save] skips blank values and leaving a scene *is*
         * writing a blank.
         */
        fun activeScene(context: Context): String? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("scene", "").orEmpty().trim().ifEmpty { null }

        /**
         * [byBrain] is true only for the `scene.*` tools. It exists for one
         * reader: the scene-ended event, which must not tell the brain what the
         * brain itself just did (a second turn to say "you left the scene").
         */
        fun setActiveScene(context: Context, name: String?, byBrain: Boolean = false) {
            val next = name?.trim().orEmpty()
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val prev = p.getString("scene", "").orEmpty()
            if (prev == next) return
            p.edit().putString("scene", next).apply()
            onSceneChanged?.invoke(prev.ifEmpty { null }, next.ifEmpty { null }, byBrain)
        }

        /**
         * `(left, entered, byBrain)`, either name null. Set by the service so the
         * card's chip and the scrollback follow whoever changed it — the brain's
         * tool, the scenes card, the chip's ✕ or an app trigger. Called on the
         * writer's thread.
         */
        @Volatile
        var onSceneChanged: ((String?, String?, Boolean) -> Unit)? = null

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
         * logging one voice while `BodyCoreService` logged another.
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
            // Answers read aloud or not — the card's 朗读 / 静音 key, the Mac header's twin.
            "mute",
            // Which palette the UI draws with — see [net.kuafuai.andee.ui.Theme].
            // In this list because [save] drops anything that is not: a key missing
            // here is a write that silently does nothing, which on a theme means the
            // picker moves, the card redraws and the setting is gone next launch.
            "theme",
            "uid",
            "api_key",
            "codeflying_key",
            "asr_endpoint", "asr_resource_id",
            "tts_endpoint", "tts_resource_id", "tts_sample_rate",
            "voice_provider", "speak_provider",
            "google_api_key", "google_base_url",
            "google_project", "google_location",
            "google_asr_model", "google_tts_model", "google_language",
            "hub_url", "device_name",
            "brain",
            "show_hub",
            "llm_api_key", "llm_base_url", "llm_model",
            "llm_thinking", "llm_reasoning_effort",
            "grounding",
            "notify",
            "lang",
            "ball",
            "sweep", "sweep_quiet_minutes", "sweep_daily_cap",
            "max_steps",
        )
}
