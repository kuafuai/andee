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

    /** The product's version, the phone's `versionName`: one number for both bodies. */
    val productVersion: String get() = p.getProperty("PRODUCT_VERSION", "").trim().ifEmpty { "0.0.0" }
}
