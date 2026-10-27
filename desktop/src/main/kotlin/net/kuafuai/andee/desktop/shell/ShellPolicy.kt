package net.kuafuai.andee.desktop.shell

import net.kuafuai.andee.desktop.AppDirs
import net.kuafuai.andee.desktop.Settings
import net.kuafuai.andee.desktop.shell.RiskClassifier.Tier
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Who decides whether a command runs, and how far it can reach once it does.
 * See docs/design/desktop-shell-tool.zh-CN.md §4 and §7.
 */
object ShellPolicy {

    enum class Mode(val key: String) { ASK("ask"), AUTO("auto"), READ_ONLY("read_only"), FULL("full") }

    fun mode(): Mode = Mode.values().firstOrNull { it.key == Settings.get("shell_mode") } ?: Mode.ASK

    /** Work folders: writable inside the sandbox. Defaults to ~/Andee, created on first use. */
    fun workDirs(): List<File> {
        val home = System.getProperty("user.home")
        val saved = Settings.get("shell_workdirs").split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { File(it.replaceFirst(Regex("^~"), home)) }.filter { it.isDirectory && !SandboxProfile.isDangerousWorkDir(it.path) }
        if (saved.isNotEmpty()) return saved
        return listOf(File(System.getProperty("user.home"), "Andee").apply { mkdirs() })
    }

    // ---- "always allow" -------------------------------------------------

    private val allowFile get() = AppDirs.file("shell_always_allow.json")

    /** The command name plus its first non-flag word: `git status`, `npm test`. */
    fun prefixOf(command: String): String {
        val words = RiskClassifier.segments(command).firstOrNull().orEmpty().trim().split(Regex("\\s+"))
        val prog = words.firstOrNull().orEmpty().substringAfterLast('/')
        val sub = words.drop(1).firstOrNull { !it.startsWith("-") }.orEmpty()
        return if (sub.isEmpty() || sub.contains('/') || sub.contains('.')) prog else "$prog $sub"
    }

    fun alwaysAllowed(command: String, workDir: String): Boolean {
        if (RiskClassifier.segments(command).size > 1) return false
        if (RiskClassifier.DANGEROUS_FLAGS.any { it.containsMatchIn(command) }) return false
        val o = runCatching { JSONObject(allowFile.readText()) }.getOrNull() ?: return false
        val arr = o.optJSONArray(workDir) ?: return false
        val p = prefixOf(command)
        return (0 until arr.length()).any { arr.optString(it) == p }
    }

    fun rememberAllow(command: String, workDir: String) {
        val o = runCatching { JSONObject(allowFile.readText()) }.getOrDefault(JSONObject())
        val arr = o.optJSONArray(workDir) ?: JSONArray().also { o.put(workDir, it) }
        val p = prefixOf(command)
        if ((0 until arr.length()).none { arr.optString(it) == p }) arr.put(p)
        allowFile.writeText(o.toString(2))
    }

    // ---- the decision ---------------------------------------------------

    sealed class Decision {
        object Run : Decision()
        /** Ask the user. [second] = the critical tier's extra confirmation. */
        data class Ask(val allowAlways: Boolean, val second: Boolean) : Decision()
        data class Refuse(val why: String) : Decision()
    }

    enum class Turn { USER, WAKE_OWN, WAKE_OTHER, NOTIFY, SILENT }

    /**
     * Mode, tier and where the turn came from, in that order of who can say no.
     * The turn-source refusals come first and full-open does not lift them:
     * full-open is the user trusting Andee, not trusting whoever sent a message.
     */
    fun decide(tier: Tier, turn: Turn, mode: Mode, alwaysAllowed: Boolean, criticalConfirm: Boolean): Decision {
        when (turn) {
            Turn.NOTIFY -> return Decision.Refuse(
                "This turn started from a notification somebody else sent, so no command can run from it — not in this turn " +
                    "and not in a todo you set from it. To type into an app (a reply), use type_text, which needs no command. " +
                    "Otherwise tell the user what you were about to run and let them ask for it themselves; do not promise a " +
                    "later turn will run it.",
            )
            Turn.SILENT -> return Decision.Refuse("A silent review may only use the notebook; it must not run commands.")
            Turn.WAKE_OTHER -> if (tier != Tier.SAFE) return Decision.Refuse(
                "This todo was not set by the user, and nobody is here to approve a command — only read-only " +
                    "commands may run now. To type into an app, use type_text, which needs no command. Tell the user what " +
                    "you wanted to run when they are back; do not promise another automatic turn will run it.",
            )
            Turn.WAKE_OWN -> if (mode != Mode.FULL && tier != Tier.SAFE) return Decision.Refuse(
                "A todo is running and the user may not be at the Mac to approve anything, so only read-only " +
                    "commands may run now (full access lifts this). To type into an app, use type_text, which needs no " +
                    "command. Tell the user what you wanted to run when they are back; do not promise another automatic " +
                    "turn will run it.",
            )
            Turn.USER -> Unit
        }
        if (mode == Mode.READ_ONLY && tier != Tier.SAFE) {
            return Decision.Refuse(
                "The user has set commands to read-only, so this one (${tier.name.lowercase()} risk) cannot run. " +
                    "Say what you would have run; they can change the mode in Settings.",
            )
        }
        if (tier == Tier.SAFE) return Decision.Run
        if (mode == Mode.FULL) {
            return if (tier == Tier.CRITICAL && criticalConfirm) Decision.Ask(allowAlways = false, second = true) else Decision.Run
        }
        if (tier == Tier.CRITICAL) return Decision.Ask(allowAlways = false, second = true)
        if (tier == Tier.HIGH) return Decision.Ask(allowAlways = false, second = false)
        if (mode == Mode.AUTO) return Decision.Run
        if (alwaysAllowed) return Decision.Run
        return Decision.Ask(allowAlways = true, second = false)
    }
}
