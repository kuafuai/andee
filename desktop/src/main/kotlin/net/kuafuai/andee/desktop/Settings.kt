package net.kuafuai.andee.desktop

import java.io.File
import java.util.Properties
import java.util.UUID

/**
 * Where the desktop app keeps everything: `~/Library/Application Support/Andee`.
 * One directory, so "reset" and "back up" each mean one folder.
 */
object AppDirs {
    val root: File by lazy {
        // `andee.home` is for the screenshot harness and tests: a throwaway directory, so
        // drawing the UI never reads or writes the user's real conversation.
        System.getProperty("andee.home")?.let { return@lazy File(it).apply { mkdirs() } }
        val home = System.getProperty("user.home")
        File(home, "Library/Application Support/Andee").apply { mkdirs() }
    }

    fun file(name: String): File = File(root, name)
}

/**
 * The desktop's settings, as a properties file. The keys are the same names
 * the phone's `voice_prefs` uses, so a setting means the same thing on both.
 *
 * Writes go through [save], which only accepts [KEYS] — the same rule as the
 * phone's `ALLOWED_KEYS`, so a typo cannot quietly create a setting nothing
 * reads.
 */
object Settings {

    val KEYS = setOf(
        "brain", "llm_api_key", "llm_base_url", "llm_model", "llm_thinking", "llm_reasoning_effort",
        "api_key", "codeflying_key", "asr_endpoint", "tts_endpoint", "asr_resource_id", "tts_resource_id", "tts_speaker",
        "voice_provider", "lang", "max_steps", "scene", "ball", "ball_pos", "notify", "sweep", "sweep_quiet_minutes", "sweep_daily_cap",
        "shell_mode", "shell_workdirs", "shell_critical_confirm", "mute",
        "google_api_key", "google_project", "google_location", "google_asr_model", "google_tts_model", "google_base_url",
        "google_language", "tts_sample_rate", "grounding", "show_hub", "hub_url", "device_name", "theme", "peers",
        "nudge", "nudge_muted",
    )

    /**
     * Which service hears and speaks: what the user picked, else by interface language — Google
     * for English, 火山 for Chinese. Anything saved that is not exactly `google` reads as 火山, the
     * phone's fail-closed rule. Read per use, so the language picker moves it at once.
     */
    fun voiceProvider(): String = when (get("voice_provider").trim().lowercase()) {
        "google" -> "google"
        "huoshan" -> "huoshan"
        "" -> if (Strings.lang() == "en") "google" else "huoshan"
        else -> "huoshan"
    }

    private val file get() = AppDirs.file("settings.properties")
    private val lock = Any()

    private fun load(): Properties = Properties().apply {
        if (file.exists()) file.inputStream().use { load(it.reader(Charsets.UTF_8)) }
    }

    fun get(key: String, default: String = ""): String = synchronized(lock) {
        load().getProperty(key, default)
    }

    fun all(): Map<String, String> = synchronized(lock) {
        load().stringPropertyNames().associateWith { load().getProperty(it) }
    }

    /** Blank values are written as blank, unlike the phone: a desktop field can be cleared. */
    fun save(values: Map<String, String>) = synchronized(lock) {
        val p = load()
        for ((k, v) in values) if (k in KEYS) p.setProperty(k, v.trim())
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.outputStream().use { p.store(it.writer(Charsets.UTF_8), "Andee settings") }
        tmp.renameTo(file)
    }

    /** A stable per-install id, generated once — the phone's `uid`. */
    fun uid(): String = synchronized(lock) {
        val f = AppDirs.file("uid")
        if (f.exists()) return f.readText().trim()
        val id = UUID.randomUUID().toString()
        f.writeText(id)
        id
    }
}
