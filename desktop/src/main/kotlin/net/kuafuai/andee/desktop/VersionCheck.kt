package net.kuafuai.andee.desktop

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The self-check's version row on the Mac: the phone's `SelfCheck.probeVersion`, against the same service.
 *
 * What it sends, and nothing else: this build's version, `platform=mac`, the Mac's model and macOS release, the
 * interface language, this install's own random id, and whether CodeFlying is the backend in force (on / off —
 * never the key or the address). No conversation, no screen content, no file names. It is the one call the app
 * makes to a host the maintainers run, and docs/permissions.md says so.
 *
 * Behind is red and says where to upgrade (GitHub). Anything that could not be determined — no network, the
 * day's allowance spent, a server error — is a quiet note, never a fault: a statistics endpoint being down is
 * not the user's problem.
 */
object VersionCheck {

    /** Where a newer build is published, and the fallback when the service names no download address. */
    const val RELEASES_URL = "https://github.com/kuafuai/andee/releases"

    private const val BASE = "https://api.heyandee.com"
    private const val CALL_MS = 4_000L

    sealed interface Result {
        /** Reached, and this is the current release. */
        object Current : Result
        /** Reached, and there is a newer one. [url] is where to get it. */
        data class Behind(val latest: String, val forced: Boolean, val url: String) : Result
        /** Could not be determined; [why] is for the row's text. */
        data class Unknown(val why: String) : Result
    }

    fun current(): String = CodeFlyingBuild.productVersion

    /** One GET. Blocking: call it off the UI thread. */
    fun probe(): Result {
        val url = BASE.toHttpUrlOrNull()?.newBuilder()
            ?.addPathSegments("api/version")
            ?.addQueryParameter("current", current())
            ?.addQueryParameter("platform", "mac")
            ?.addQueryParameter("model", macModel())
            ?.addQueryParameter("brand", "Apple")
            ?.addQueryParameter("os_version", "macOS ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
            ?.addQueryParameter("device_id", Settings.uid())
            ?.addQueryParameter("app_package", "net.kuafuai.andee.mac")
            ?.addQueryParameter("codeflying", if (Assistant.config().mode == net.kuafuai.andee.config.BrainConfig.BRAIN_CODEFLYING) "1" else "0")
            ?.addQueryParameter("lang", Strings.locale().toLanguageTag())
            ?.build() ?: return Result.Unknown("bad address")
        val http = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(CALL_MS, TimeUnit.MILLISECONDS).build()
        return try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                // Ten a day per address, and 重新检测 spends one per tap: the service says "enough today" with 429.
                if (resp.code == 429) return Result.Unknown("quota")
                val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
                    ?: return Result.Unknown("HTTP ${resp.code}")
                if (json.optInt("code", -1) != 0) return Result.Unknown("HTTP ${resp.code} code=${json.optInt("code")}")
                val data = json.optJSONObject("data") ?: return Result.Unknown("no data")
                if (!data.optBoolean("has_update", false)) return Result.Current
                // org.json hands back the four-letter string "null" for a JSON null: check the scheme, not emptiness.
                val raw = data.optString("download_url").orEmpty()
                Result.Behind(
                    latest = data.optString("latest_version").ifEmpty { "?" },
                    forced = data.optBoolean("force_update", false),
                    url = raw.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: RELEASES_URL,
                )
            }
        } catch (e: Exception) {
            Result.Unknown(e.message ?: e.javaClass.simpleName)
        }
    }

    /** "Mac14,5"-style model identifier; empty when sysctl is not answering. */
    private fun macModel(): String = runCatching {
        val p = ProcessBuilder("/usr/sbin/sysctl", "-n", "hw.model").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor(2, TimeUnit.SECONDS) }
    }.getOrDefault("")
}
