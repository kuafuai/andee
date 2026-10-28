package net.kuafuai.andee.desktop.shell

import net.kuafuai.andee.desktop.AppDirs
import net.kuafuai.andee.desktop.DesktopBrainHost
import net.kuafuai.andee.desktop.Settings
import net.kuafuai.andee.desktop.shell.RiskClassifier.Tier
import net.kuafuai.andee.desktop.shell.ShellPolicy.Decision
import net.kuafuai.andee.desktop.shell.ShellPolicy.Mode
import net.kuafuai.andee.desktop.shell.ShellPolicy.Turn
import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * `run_command`, `command_output`, `stop_command`: the terminal as one of
 * Andee's hands on the Mac. The design is docs/design/desktop-shell-tool.zh-CN.md;
 * this is its implementation.
 */
class ShellTools(
    /** Ask the user; returns the button they pressed. Blocks the tool thread, never the UI. */
    private val ask: (Approval) -> Answer,
    /** Secrets to scrub from output and refuse in commands: (label, value). */
    secrets: () -> List<Pair<String, String>>,
    /** A line in the conversation the user can see, for each command that ran. */
    private val record: (String) -> Unit,
) : DesktopBrainHost.ToolProvider {

    data class Approval(
        val command: String,
        val why: String,
        val cwd: String,
        val tier: Tier,
        val reason: String,
        val allowAlways: Boolean,
        val second: Boolean,
        /** Non-null when the question is "may this command reach that domain". */
        val domain: String? = null,
    )

    enum class Answer { ONCE, ALWAYS, DENY }

    private val jobs = JobTable(File(AppDirs.root, "shell"))
    private val redactor = Redactor(secrets)
    private val proxy: DomainProxy by lazy {
        DomainProxy { host ->
            ask(Approval("", "", "", Tier.MEDIUM, "network", allowAlways = true, second = false, domain = host)) != Answer.DENY
        }
    }
    private val trashTool: File by lazy { locateTrashTool() }
    private val trash: TrashBroker by lazy {
        TrashBroker({ ShellPolicy.workDirs() }, { trashTool }).start()
            .also { b -> b.onTrashed = { n -> record("moved $n item(s) to the Trash") } }
    }
    /**
     * The shims and the helper sockets live outside Andee's data folder: the
     * sandbox denies reading that folder (it holds the notebook and the vault),
     * and a shim the command cannot read is a shim that silently falls back to
     * /bin/rm.
     */
    private val shimDir: File by lazy { TrashShims.install(File(ShellRuntime.dir, "bin"), trash.path) }

    @Volatile private var turn: Turn = Turn.USER

    /** Set by the host from the brain's turn kind. */
    override fun turnStarted(kind: String) {
        turn = when (kind) {
            "USER" -> Turn.USER
            // A task the user handed over from their other device: their own request, but nobody may be at
            // the Mac to approve a command — exactly a todo they set themselves.
            "WAKE", "PEER" -> Turn.WAKE_OWN
            "NOTIFY" -> Turn.NOTIFY
            else -> Turn.SILENT
        }
    }

    init {
        // Keys are a capability like the vault: a turn a stranger started cannot use them.
        AndeeAgent.allowSigning = { turn == Turn.USER || turn == Turn.WAKE_OWN }
        AndeeAgent.onSign = { key, host -> record("key $key" + (host?.let { " → $it" } ?: "")) }
    }

    /** Triple-click / stop: end every running command and say the user did it. */
    fun stopAll() = jobs.stopAll("user")

    override fun handles(method: String) = method.startsWith("shell.")

    override fun extraTools(): JSONArray = JSONArray().put(RUN).put(OUTPUT).put(STOP)

    override fun methodOfExtra(toolName: String): String? = when (toolName) {
        "run_command" -> "shell.run"
        "command_output" -> "shell.output"
        "stop_command" -> "shell.stop"
        else -> null
    }

    override fun dispatch(method: String, p: JSONObject, callId: String, silent: Boolean, untrusted: Boolean): Any {
        // The turn decides first, whatever the tool thinks the turn is.
        val t = when {
            silent -> Turn.SILENT
            untrusted -> Turn.NOTIFY
            else -> turn
        }
        return when (method) {
            "shell.run" -> run(p, t)
            "shell.output" -> output(p)
            "shell.stop" -> stop(p)
            else -> throw IllegalArgumentException("unknown method $method")
        }
    }

    private fun run(p: JSONObject, turn: Turn): JSONObject {
        val command = p.optString("command").trim().ifEmpty { throw IllegalArgumentException("run_command needs command") }
        val why = p.optString("why").trim().ifEmpty {
            throw IllegalArgumentException("run_command needs why: one plain sentence the user will read before it runs")
        }
        redactor.containsKnown(command)?.let { label ->
            throw IllegalArgumentException(
                "Refused: this command contains the value of $label from the vault. Secrets never go into a " +
                    "command line — they end up in process lists and logs.",
            )
        }
        val mode = ShellPolicy.mode()
        val work = ShellPolicy.workDirs()
        val cwd = p.optString("cwd").trim().takeIf { it.isNotEmpty() }
            ?.let { File(it.replaceFirst("~", System.getProperty("user.home"))) }
            ?: work.first()
        if (!cwd.isDirectory) throw IllegalArgumentException("cwd does not exist: ${cwd.path}")
        val sandboxed = mode != Mode.FULL
        if (sandboxed && work.none { cwd.canonicalPath.startsWith(it.canonicalPath) }) {
            throw IllegalArgumentException(
                "cwd ${cwd.path} is outside the work folders (${work.joinToString { it.path }}). Run it from one " +
                    "of those, or ask the user to add this folder in Settings → Commands.",
            )
        }

        val verdict = RiskClassifier.classify(command)
        val always = ShellPolicy.alwaysAllowed(command, cwd.canonicalPath)
        val confirmCritical = Settings.get("shell_critical_confirm") != "off"
        when (val d = ShellPolicy.decide(verdict.tier, turn, mode, always, confirmCritical)) {
            is Decision.Refuse -> throw IllegalStateException(d.why)
            is Decision.Ask -> {
                val a = ask(Approval(command, why, cwd.path, verdict.tier, verdict.reason, d.allowAlways, d.second))
                when (a) {
                    Answer.DENY -> return JSONObject().put("ran", false).put("approved", false)
                        .put("note", "The user declined. Do not run it another way; ask what they would rather do.")
                    Answer.ALWAYS -> ShellPolicy.rememberAllow(command, cwd.canonicalPath)
                    Answer.ONCE -> Unit
                }
            }
            Decision.Run -> Unit
        }

        val env = environment(sandboxed)
        val profile = if (sandboxed) {
            SandboxProfile.build(work.map { it.path }, emptyList(), ShellRuntime.tmp.path)
        } else null
        AndeeAgent.HostHint.current = AndeeAgent.HostHint.fromCommand(command)
        val job = jobs.start(command, cwd, env, profile)
        val approvedBy = when {
            verdict.tier == Tier.SAFE -> "read_only"
            mode == Mode.FULL -> "full_access"
            mode == Mode.AUTO && verdict.tier <= Tier.MEDIUM -> "auto"
            always -> "always_allow"
            else -> "user"
        }
        record("⌘ $command")
        Log.i(TAG, "${job.id} [$approvedBy, ${verdict.tier}] ${command.take(160)}")

        val budget = (p.optInt("timeout_s", 30).coerceIn(1, 60)) * 1000L
        val finished = waitOrStall(job, budget)
        val out = JSONObject().put("job", job.id).put("risk", verdict.tier.name.lowercase())
            .put("approved_by", approvedBy).put("sandboxed", sandboxed)
        return if (finished) {
            job.markAllRead()
            out.put("exit_code", job.exitCode).put("ms", System.currentTimeMillis() - job.startedAt)
                .putOutput("output", job.output(), job, sandboxed)
        } else if (job.running && stalled(job)) {
            out.put("running", true).put("waiting_for_input", true)
                .putOutput("output_so_far", job.takeNew(), job, sandboxed)
                .put("note", "It stopped producing output and looks like it is waiting for input. You cannot type into " +
                    "it: stop it with stop_command and run a non-interactive form instead (a --yes flag, input from a file).")
        } else {
            out.put("running", true).putOutput("output_so_far", job.takeNew(), job, sandboxed)
                .put("note", "Still running in the background. Check it later with command_output(job=\"${job.id}\"); " +
                    "do not start it again.")
        }
    }

    private fun output(p: JSONObject): JSONObject {
        val job = jobs[p.optString("job")] ?: throw IllegalArgumentException("No such job: ${p.optString("job")}")
        val o = JSONObject().put("job", job.id).put("running", job.running)
            .putOutput("output", job.takeNew(), job, true)
        if (!job.running) o.put("exit_code", job.exitCode)
        job.stoppedBy?.let { o.put("stopped_by", it) }
        return o
    }

    private fun stop(p: JSONObject): JSONObject {
        val job = jobs[p.optString("job")] ?: throw IllegalArgumentException("No such job: ${p.optString("job")}")
        job.stop("brain")
        return JSONObject().put("job", job.id).put("stopped", true).putOutput("output", job.takeNew(), job, true)
    }

    // ---- helpers -------------------------------------------------------

    /** Wait for the job; give up early if it has gone quiet waiting for input. */
    private fun waitOrStall(job: ShellJob, budget: Long): Boolean {
        val deadline = System.currentTimeMillis() + budget
        while (System.currentTimeMillis() < deadline) {
            if (job.waitFor(250)) return true
            if (stalled(job)) return false
        }
        return !job.running
    }

    /** No output for a while and nothing below it using the CPU: it is reading a terminal that has nobody. */
    private fun stalled(job: ShellJob): Boolean = job.running && System.currentTimeMillis() - job.lastOutputAt > STALL_MS

    private fun JSONObject.putOutput(key: String, raw: String, job: ShellJob, sandboxed: Boolean): JSONObject {
        val clean = redactor.scrub(TerminalText.clean(raw))
        val (text, cut) = TerminalText.truncate(clean)
        put(key, text)
        if (cut) put("truncated", true).put("full_log", job.log.path)
        if (sandboxed && Regex("Operation not permitted|Permission denied|Andee blocked").containsMatchIn(clean)) {
            put(
                "sandbox_hint",
                "Something was blocked by the sandbox (writes outside the work folders, deletes outside rebuildable " +
                    "folders, credentials, or a domain the user has not allowed). Tell the user which path or domain " +
                    "it needed; do not try to get around it.",
            )
        }
        return this
    }

    private fun environment(sandboxed: Boolean): Map<String, String> {
        val home = System.getProperty("user.home")
        val path = listOfNotNull(
            if (sandboxed) shimDir.path else null,
            loginPath(),
        ).joinToString(":")
        val env = linkedMapOf(
            "HOME" to home, "USER" to System.getProperty("user.name"), "PATH" to path,
            "LANG" to "en_US.UTF-8", "TERM" to "xterm-256color", "TMPDIR" to ShellRuntime.tmp.path,
            "GIT_PAGER" to "cat", "PAGER" to "cat", "NO_COLOR" to "", "CI" to "",
        )
        if (sandboxed) {
            val proxyUrl = "http://127.0.0.1:${proxy.port}"
            for (k in listOf("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy")) env[k] = proxyUrl
            env["NO_PROXY"] = "localhost,127.0.0.1"
            env["no_proxy"] = "localhost,127.0.0.1"
        }
        AndeeAgent.socketPath()?.let { env["SSH_AUTH_SOCK"] = it }
        return env.filterValues { it.isNotEmpty() || true }
    }

    private val loginPathCache: String by lazy {
        runCatching {
            val p = ProcessBuilder("/bin/zsh", "-l", "-c", "printf %s \"\$PATH\"").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim().ifEmpty { null }
        }.getOrNull() ?: "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
    }

    private fun loginPath() = loginPathCache

    private fun locateTrashTool(): File {
        val bundled = System.getProperty("compose.application.resources.dir")?.let { File(it, "andee-trash") }
        if (bundled != null && bundled.canExecute()) return bundled
        val dev = File(ShellRuntime.dir, "andee-trash")
        if (!dev.canExecute()) {
            javaClass.getResourceAsStream("/andee-trash")?.use { input ->
                dev.parentFile.mkdirs(); dev.outputStream().use { input.copyTo(it) }; dev.setExecutable(true)
            }
        }
        return dev
    }

    /**
     * A folder a sandboxed command can read but not write: the shims and the
     * helper sockets. Not under Andee's data (unreadable to commands, it holds
     * the notebook) and not under a cache folder (writable to commands, which
     * could then swap the `rm` shim for the real one). Denied for writing in
     * [SandboxProfile.PROTECTED_WRITE]; the job temp folder is its own place.
     */
    object ShellRuntime {
        val dir: File by lazy {
            File(System.getProperty("user.home"), "Library/Andee-shell").apply { mkdirs() }
        }
        val tmp: File by lazy {
            File(System.getProperty("user.home"), "Library/Caches/net.kuafuai.andee.mac/tmp").apply { mkdirs() }
        }
    }

    companion object {
        private const val TAG = "Shell"
        private const val STALL_MS = 4_000L

        private fun tool(name: String, description: String, props: JSONObject, required: List<String>) = JSONObject()
            .put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))

        private fun prop(type: String, d: String) = JSONObject().put("type", type).put("description", d)

        val RUN: JSONObject = tool(
            "run_command",
            """
Run one command line on this Mac, in zsh, with a real terminal. Use it for what a terminal does better than clicking: finding and reading files, git, builds and tests, package managers, `open -a App` / `open URL` to open things, `mdfind` to search.

How it is decided — the device decides, and you cannot change it:
- Read-only commands (ls, cat, git status/log/diff, rg, find …) run at once.
- Anything that writes, deletes, installs or reaches another machine is shown to the user first, unless they set commands to automatic. Destructive or irreversible ones (rm -r, force push, reset --hard …) are always shown. If they decline, do not try another way to do the same thing.
- It runs in a sandbox unless the user turned on full access: it can read everything except credentials, write only inside the work folders (default ~/Andee) and temp, delete only by moving to the Trash (rm here moves to the Trash; more than 50 files at once is refused), and reach the network only through Andee's proxy, which asks the user about domains it does not know. ~/.ssh, keychains, browser data and Andee's own data cannot be read at all.
- A command started from a notification somebody else sent is refused, always.

Practicalities:
- `why` is shown to the user next to the command: one plain sentence in their language saying what it is for.
- The terminal has no keyboard: anything that waits for input (a password, y/n, an editor) is reported as waiting — use a non-interactive form instead. No sudo.
- Output is cleaned and cut to the first 2 KB and last 6 KB; the full log path comes back when it was cut. Secrets in output are masked.
- If it is still running after timeout_s you get a job id: check it with command_output, end it with stop_command, never start it twice.
- git push over SSH works when the user has given Andee a key; do not read or copy key files.
""".trim(),
            JSONObject()
                .put("command", prop("string", "The command line."))
                .put("why", prop("string", "One sentence for the user, in their language: what this is for."))
                .put("cwd", prop("string", "Directory to run in; defaults to the first work folder. Must be inside a work folder unless full access is on."))
                .put("timeout_s", prop("integer", "Seconds to wait before handing back a job id, 1-60. Default 30.")),
            listOf("command", "why"),
        )
        val OUTPUT: JSONObject = tool(
            "command_output",
            "Read what a background command (from run_command) has printed since you last looked, and whether it has finished.",
            JSONObject().put("job", prop("string", "The job id run_command gave you.")),
            listOf("job"),
        )
        val STOP: JSONObject = tool(
            "stop_command",
            "End a running command and everything it started. Use it on one that is waiting for input or no longer needed.",
            JSONObject().put("job", prop("string", "The job id.")),
            listOf("job"),
        )
    }
}
