package net.kuafuai.andee.desktop

import java.util.Properties

/**
 * What the build was packaged with for CodeFlying, from `local.properties` at build time
 * (see `generateCodeFlyingConfig` in desktop/build.gradle) — the phone's `BuildConfig.CODEFLYING_*`.
 * Nothing configured reads as "not available", and the 本机 backend carries on.
 */
object CodeFlyingBuild {
    private val p: Properties by lazy {
        Properties().apply {
            runCatching {
                CodeFlyingBuild::class.java.getResourceAsStream("/codeflying.properties")?.use { load(it.reader(Charsets.UTF_8)) }
            }
        }
    }

    private fun flag(key: String) = p.getProperty(key, "").trim().lowercase().let { it == "true" || it == "1" || it == "yes" }

    val domain: String get() = p.getProperty("CODEFLYING_DOMAIN", "").trim()
    val ssl: Boolean get() = flag("CODEFLYING_SSL_ENABLED")
    val key: String get() = p.getProperty("CODEFLYING_KEY", "").trim()

    /**
     * The language a fresh install comes up in, baked in at package time — the Mac's counterpart
     * of the phone's `BuildConfig.DEFAULT_LANG`.
     *
     * Without it the Mac read only the saved preference and then the system locale, so a package
     * built as "Chinese" opened on an English machine came up English. That made the package not
     * be the thing its name said it was, which is the one job a per-language build has.
     *
     * Ranks below a language the user has actually picked, and above the system locale. Anything
     * but `zh`/`en` reads as unset, the same rule the phone uses.
     */
    val defaultLang: String get() = p.getProperty("DEFAULT_LANG", "").trim().lowercase().let {
        if (it == "zh" || it == "en") it else ""
    }

    /** The product's version, the phone's `versionName`: one number for both bodies. */
    val productVersion: String get() = p.getProperty("PRODUCT_VERSION", "").trim().ifEmpty { "0.0.0" }
}
