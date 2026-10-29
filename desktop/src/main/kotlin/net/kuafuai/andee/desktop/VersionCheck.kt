package net.kuafuai.andee.desktop

import net.kuafuai.andee.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The self-check's version row on the Mac: the phone's `SelfCheck.probeVersion`, against the same service.
 *
 * What it sends, and nothing else: this build's version, `platform=mac`, the Mac's model and macOS release, the
 * interface language, this install's own random id, and whether CodeFlying is in use (the backend in force AND a key set: 1 / 0 —
 * never the key or the address). No conversation, no screen content, no file names. It is the one call the app
 * makes to a host the maintainers run, and docs/permissions.md says so.
 *
 * Behind — a release strictly newer than this build ([net.kuafuai.andee.config.Upgrade.newer]; a build ahead of
 * the release is current) — is red, says to upgrade from GitHub or CodeFlying, and its button opens CodeFlying's
 * site in the interface language. Checked once at launch ([remindAtLaunch]) and again whenever the self-check card
 * opens. Anything that could not be determined — no network, the day's allowance spent, a server error — is a
 * quiet note, never a fault: a statistics endpoint being down is not the user's problem.
 */
object VersionCheck {

    private const val BASE = "https://api.heyandee.com"
    private const val CALL_MS = 4_000L

    sealed interface Result {
        /** Reached, and this is the current release. */
        object Current : Result
        /** Reached, and there is a strictly newer release. */
        data class Behind(val latest: String, val forced: Boolean) : Result
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
            // "1" only if CodeFlying is the backend in force AND a key is configured for it, else "0".
            ?.addQueryParameter("codeflying", if (usingCodeFlyingWithKey()) "1" else "0")
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
                // Ahead of (or level with) the release is not behind, whatever the service says.
                if (!net.kuafuai.andee.config.Upgrade.newer(data.optString("latest_version"), current())) return Result.Current
                Result.Behind(
                    latest = data.optString("latest_version").ifEmpty { "?" },
                    forced = data.optBoolean("force_update", false),
                )
            }
        } catch (e: Exception) {
            Result.Unknown(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Where the upgrade button goes, in the interface language. */
    fun upgradeUrl(): String = net.kuafuai.andee.config.Upgrade.url(Strings.lang())

    /** The row's and the reminder's sentence. */
    fun behindText(r: Result.Behind): String {
        val zh = Strings.lang() == "zh"
        val v = current()
        return if (r.forced) {
            if (zh) "请升级到 ${r.latest} —— 你现在这个（$v）已经不再支持了。到 GitHub 或 CodeFlying 升级。"
            else "Please upgrade to ${r.latest} — this build ($v) is no longer supported. Upgrade from GitHub or CodeFlying."
        } else {
            if (zh) "版本落后了：最新是 ${r.latest}，你现在是 $v。请到 GitHub 或 CodeFlying 升级。"
            else "Out of date: the latest is ${r.latest}, you are on $v. Upgrade from GitHub or CodeFlying."
        }
    }

    /**
     * The phone's startup card, on the Mac: check once at launch and, only if behind, put up one card beside the
     * ball with 去升级 / 以后再说. Silent (not read aloud — nobody asked a question), and only when no other card is
     * up. Nothing at all when current, ahead, or undeterminable.
     */
    fun remindAtLaunch() {
        Thread({
            Thread.sleep(LAUNCH_DELAY_MS)
            val p = probe()
            Log.i("Version", "launch check: ${current()} → $p")
            val r = p as? Result.Behind ?: return@Thread
            if (AskGate.current != null) return@Thread.also { Log.i("Version", "a card is up; not reminding this launch") }
            val zh = Strings.lang() == "zh"
            val go = if (zh) "去升级" else "Upgrade"
            val a = AskGate.ask(behindText(r), listOf(go, if (zh) "以后再说" else "Later"), REMIND_MS, speak = false)
            Log.i("Version", "reminder answered: ${a.button ?: a.how}")
            if (a.button == go) runCatching { net.kuafuai.andee.desktop.mac.Bridge.open(upgradeUrl()) }
        }, "version-remind").apply { isDaemon = true }.start()
    }

    /** Long enough for the ball to be up before the card is placed beside it. */
    private const val LAUNCH_DELAY_MS = 8_000L
    private const val REMIND_MS = 120_000L

    private fun usingCodeFlyingWithKey(): Boolean = Assistant.config().let {
        it.mode == net.kuafuai.andee.config.BrainConfig.BRAIN_CODEFLYING && it.apiKey.isNotBlank()
    }

    /** "Mac14,5"-style model identifier; empty when sysctl is not answering. */
    private fun macModel(): String = runCatching {
        val p = ProcessBuilder("/usr/sbin/sysctl", "-n", "hw.model").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText().trim().also { p.waitFor(2, TimeUnit.SECONDS) }
    }.getOrDefault("")
}
