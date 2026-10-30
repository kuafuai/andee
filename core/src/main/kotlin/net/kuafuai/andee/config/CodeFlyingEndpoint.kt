package net.kuafuai.andee.config

/**
 * Where CodeFlying lives, derived from one string the user (or the build) gives.
 *
 * The three URL shapes below are the only place the paths `/voice/llm`, `/voice/asr/...` and
 * `/baas-api` are written, so the phone and the Mac cannot drift apart on them.
 *
 * [parse] accepts a bare host (`baas.kuafuai.net`, `host:8080`) or one with a scheme
 * (`https://host`). A scheme the user typed wins over [sslDefault]; a bare host takes the default,
 * which on the phone is the build's `CODEFLYING_SSL_ENABLED` and on the Mac is off.
 */
class CodeFlyingEndpoint private constructor(val host: String, val ssl: Boolean) {
    private val http get() = if (ssl) "https" else "http"
    private val ws get() = if (ssl) "wss" else "ws"

    /** Bare origin, e.g. `https://baas.kuafuai.net`. */
    val baseUrl: String get() = "$http://$host"

    /** Origin of the market tools; the bare `/api/` on that host is the front end's static directory. */
    val marketBaseUrl: String get() = "$baseUrl/baas-api"
    val llmBaseUrl: String get() = "$baseUrl/voice/llm"
    val asrEndpoint: String get() = "$ws://$host/voice/asr/bigmodel_async"
    val ttsEndpoint: String get() = "$ws://$host/voice/tts/bidirection"

    companion object {
        fun parse(input: String?, sslDefault: Boolean): CodeFlyingEndpoint? {
            var s = input.orEmpty().trim()
            if (s.isEmpty()) return null
            var ssl = sslDefault
            val scheme = Regex("^([a-zA-Z]+)://").find(s)
            if (scheme != null) {
                ssl = scheme.groupValues[1].lowercase() in setOf("https", "wss")
                s = s.substring(scheme.range.last + 1)
            }
            s = s.trimEnd('/')
            return if (s.isEmpty() || s.any { it.isWhitespace() || it == '/' }) null else CodeFlyingEndpoint(s, ssl)
        }
    }
}
