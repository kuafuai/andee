package net.kuafuai.andee.desktop.dsh

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Installs dsh for the user who pressed 让 Andee 帮我安装 in Settings, and opens its web page so they can enter their key.
 *
 * It is **not** a turn of the brain. The first version sent the request as words and let a turn run it through `run_command`,
 * and that cannot work: that terminal is sandboxed, the sandbox forbids writing `/usr/local/lib/node_modules` and forbids the
 * network (only localhost), so `npm install -g` is refused and the model, reasonably, installed somewhere it could write — a
 * folder the app never looks in. The result was an install that "worked" and a Mac that still said dsh was missing.
 *
 * So this runs one fixed command outside the sandbox, after the user has pressed a button that names it, and then checks the
 * only thing that matters: that [DshWorkers.Bin.find] now finds dsh. Nothing here takes text from anywhere: the package, the
 * prefix and the arguments are constants.
 *
 * The prefix is the user's own folder ([PREFIX]), so it needs no administrator rights and works where `/usr/local` belongs to
 * root; [DshWorkers.Bin.find] looks there.
 */
class DshInstaller(
    /** Where `npm` and `node` are, as a login shell sees them; null when this Mac has no node. */
    private val locateNode: () -> Node? = { Node.find() },
    private val prefix: File = PREFIX,
    private val run: (List<String>, Map<String, String>, Long) -> Result = ::runProcess,
    /** What the install put on disk, asked after it ran (tests replace it). */
    private val found: () -> DshWorkers.Bin? = { DshWorkers.Bin.find() },
) {

    data class Node(val npm: String, val dir: String) {
        companion object {
            /** A GUI app has no shell PATH, so ask a login shell once, as [DshWorkers.Bin.find] does. */
            fun find(): Node? {
                val out = runCatching {
                    val p = ProcessBuilder("/bin/zsh", "-lc", "command -v npm; command -v node").redirectErrorStream(true).start()
                    if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); emptyList() }
                    else p.inputStream.bufferedReader().readLines().map { it.trim() }
                }.getOrNull().orEmpty()
                val npm = out.firstOrNull { it.endsWith("/npm") && File(it).canExecute() } ?: return null
                val node = out.firstOrNull { it.endsWith("/node") && File(it).canExecute() } ?: return null
                return Node(npm, File(node).parent)
            }
        }
    }

    data class Result(val exitCode: Int, val output: String, val timedOut: Boolean = false)

    sealed class Outcome {
        /** dsh is on disk and starts: [version] is what `dsh --version` printed. */
        data class Installed(val bin: DshWorkers.Bin, val version: String, val output: String) : Outcome()

        /** There is no node / npm on this Mac, so there is nothing to run: the user installs Node first. */
        object NoNode : Outcome()

        /** The install ran and failed, or ran and left nothing that starts. [output] is the tail of what npm said. */
        data class Failed(val why: String, val output: String) : Outcome()
    }

    /** The command exactly as it will run, for the confirmation the user reads before anything does. */
    fun commandLine(): String = "npm install -g --prefix ${prefix.path} $PACKAGE"

    /** Runs the install. Blocks (a minute or two on a slow link): call off the UI thread. */
    fun install(): Outcome {
        val node = locateNode() ?: return Outcome.NoNode
        prefix.mkdirs()
        val env = mapOf(
            "PATH" to listOf(node.dir, File(node.npm).parent, "/usr/bin", "/bin", "/usr/sbin", "/sbin").distinct().joinToString(":"),
            "HOME" to System.getProperty("user.home"),
            "NO_COLOR" to "1", "CI" to "1",
            "npm_config_update_notifier" to "false", "npm_config_fund" to "false", "npm_config_audit" to "false",
        )
        val r = run(listOf(node.npm, "install", "-g", "--prefix", prefix.path, PACKAGE), env, INSTALL_MS)
        if (r.timedOut) return Outcome.Failed("npm did not finish in ${INSTALL_MS / 60000} minutes", tail(r.output))
        if (r.exitCode != 0) return Outcome.Failed("npm stopped with exit code ${r.exitCode}", tail(r.output))
        // npm saying it succeeded is not the evidence: the evidence is that the app can now find dsh and that it starts.
        val bin = found() ?: return Outcome.Failed("npm finished but dsh is not where this app looks (${prefix.path}/bin)", tail(r.output))
        val v = run(listOf(bin.dsh, "--version"), mapOf("PATH" to bin.path, "HOME" to System.getProperty("user.home")), VERSION_MS)
        if (v.exitCode != 0 || v.output.isBlank()) return Outcome.Failed("dsh was installed but does not start", tail(v.output.ifBlank { r.output }))
        return Outcome.Installed(bin, v.output.trim().lineSequence().first(), tail(r.output))
    }

    /**
     * Starts dsh's web page (`dsh web`) so the user can enter their DeepSeek key there. It opens the browser itself and keeps
     * running until it is stopped, so the process is handed back for the caller to keep or stop. The page's own port is dsh's.
     */
    fun startWeb(bin: DshWorkers.Bin): Process {
        val pb = ProcessBuilder(bin.dsh, "web").redirectErrorStream(true)
        pb.environment()["PATH"] = bin.path
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        return pb.start()
    }

    private fun tail(s: String) = s.trim().lines().takeLast(12).joinToString("\n").take(1500)

    companion object {
        const val PACKAGE = "@deepseek-ai/dsh"
        const val INSTALL_MS = 5 * 60_000L
        const val VERSION_MS = 20_000L

        /** The user's own folder: no administrator rights, nothing the sandbox or a root-owned `/usr/local` can refuse. */
        val PREFIX: File get() = File(System.getProperty("user.home"), ".npm-global")

        private fun runProcess(cmd: List<String>, env: Map<String, String>, timeoutMs: Long): Result = runCatching {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment().clear(); pb.environment().putAll(env)
            val p = pb.start()
            val out = StringBuilder()
            val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { if (out.length < 20_000) out.appendLine(it) } } }
                .apply { isDaemon = true; start() }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly()
                reader.join(1000)
                return@runCatching Result(-1, out.toString(), timedOut = true)
            }
            reader.join(1000)
            Result(p.exitValue(), out.toString())
        }.getOrElse { Result(-1, it.message.orEmpty()) }
    }
}
