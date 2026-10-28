package net.kuafuai.andee.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The tablet's own notebook — what it remembers about this person, and what it
 * promised to do later.
 *
 * Two things live here that used to live only in the cloud brain (agentworld's
 * `crm_*` tools): durable facts about the user, and a scheduler that can wake
 * the brain up at a future moment. This device has neither a database nor a
 * cron service, so the notebook is one encrypted JSON blob in SharedPreferences
 * plus [Scheduler] on top of AlarmManager.
 *
 * Storage is a hand-rolled AES/GCM blob whose key lives in the Android
 * Keystore — deliberately the same shape as [Vault], for the same reasons:
 * no dependency, and the key material dies with the app. It is a separate
 * prefs file and a separate key alias on purpose; a corrupted notebook must
 * never be able to take the passwords with it.
 *
 * **This is a private notebook.** Its tools are marked `localOnly` in
 * [net.kuafuai.andee.net.ToolSchemas] and are filtered out of the hub
 * `register` payload — the person's habits and promises do not leave the
 * device. There is no cloud copy and no export path; the only way a human
 * sees this is the read-only viewer in the settings screen.
 *
 * Field names track agentworld's memory model (`name` / `description` /
 * `content` / `type` / `importance` / `source` / `do_not_do` /
 * `failure_signature` / `valid_until` / `layer`) so the two can be compared
 * later. But only four of them are ever typed by the model — see
 * [importanceFor]; everything else is bookkeeping this file fills in itself.
 */
object Notebook {

    private const val TAG = "Body"
    private const val PREFS = "notebook_prefs"
    private const val META_PREFS = "notebook_meta_prefs"
    private const val KEY_BLOB = "book"
    private const val KEY_ALIAS = "body_notebook_key"
    private const val KEY_LAST_ACTIVITY = "last_activity_at"
    private const val KEY_LAST_SWEEP = "last_sweep_at"
    private const val KEY_SWEEP_DAY = "sweep_day"
    private const val KEY_SWEEP_COUNT = "sweep_count"
    private const val KEY_SWEEP_TOKENS = "sweep_tokens"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    const val MAX_MEMORIES = NotebookBook.MAX_MEMORIES
    val TYPES = NotebookBook.TYPES
    const val SOURCE_HUMAN = NotebookBook.SOURCE_HUMAN
    const val SOURCE_INFERRED = NotebookBook.SOURCE_INFERRED
    const val SOURCE_FAILURE_REVIEW = NotebookBook.SOURCE_FAILURE_REVIEW
    const val MAX_SCENES = NotebookBook.MAX_SCENES
    const val MAX_SCENE_PROMPT = NotebookBook.MAX_SCENE_PROMPT

    /**
     * The notebook's logic lives in the shared module ([NotebookBook]); this
     * object keeps the phone's storage (an AES/GCM blob in SharedPreferences,
     * keyed in the Android Keystore) and the `(Context, …)` call shape every
     * existing caller uses. One book per process, created on first use.
     */
    @Volatile
    private var book: NotebookBook? = null

    private fun book(context: Context): NotebookBook {
        book?.let { return it }
        synchronized(this) {
            book?.let { return it }
            val app = context.applicationContext
            return NotebookBook(object : NotebookStore {
                override fun read(): String? =
                    prefs(app).getString(KEY_BLOB, null)?.let { decrypt(it) }

                override fun write(plain: String): Boolean {
                    val blob = encrypt(plain) ?: return false
                    prefs(app).edit().putString(KEY_BLOB, blob).apply()
                    return true
                }

                override fun wipe() {
                    prefs(app).edit().clear().commit()
                }
            }).apply {
                localWikiStore = net.kuafuai.andee.wiki.LocalWikiStore(wikiDir(app))
            }.also { book = it }
        }
    }

    @Volatile
    private var plans: net.kuafuai.andee.config.Plans? = null

    /**
     * Directions and day plans, kept inside the notebook's own file. One instance per process: its lock is
     * what keeps a tool write and a settings tap from interleaving their read-modify-write of the section.
     */
    fun plans(context: Context): net.kuafuai.andee.config.Plans =
        plans ?: synchronized(this) { plans ?: net.kuafuai.andee.config.Plans(book(context)).also { plans = it } }

    /**
     * Where wiki articles live: app-private storage, not shared. Unlike the notebook blob these are plain files, and
     * an article can be marked secret, so they stay where no other app can read them.
     */
    private fun wikiDir(context: Context): java.io.File = java.io.File(context.applicationContext.filesDir, "wiki")

    fun selfhood(context: Context): net.kuafuai.andee.config.Selfhood = book(context).selfhood
    fun people(context: Context): net.kuafuai.andee.config.People = book(context).people
    fun wiki(context: Context): net.kuafuai.andee.wiki.WikiBook = book(context).wiki

    /** The tool-call id of the [LifeTools] call being answered; set by the brain host just before it dispatches. */
    @Volatile var callBeingRun: String = ""

    @Volatile
    private var life: net.kuafuai.andee.config.LifeTools? = null

    /** `self_note`, `cares`, `diary`, `person`, `people`, `wiki_*` — the same handler the Mac uses. */
    fun life(context: Context): net.kuafuai.andee.config.LifeTools = life ?: synchronized(this) {
        life ?: net.kuafuai.andee.config.LifeTools(selfhood(context), people(context), wiki(context), callId = { callBeingRun })
            .also { life = it }
    }

    /** The gear shelf (what is installed, what it is for), kept inside the notebook's own file. */
    fun gear(context: Context): net.kuafuai.andee.config.Gear = net.kuafuai.andee.config.Gear(book(context))

    fun importanceFor(type: String): Double = NotebookBook.importanceFor(type)
    fun memories(context: Context): List<Memory> = book(context).memories()
    fun remember(
        context: Context,
        name: String,
        description: String,
        content: String,
        type: String,
        source: String = SOURCE_HUMAN,
    ): Pair<Memory, Boolean> = book(context).remember(name, description, content, type, source)
    fun recall(context: Context, query: String, type: String, limit: Int): List<Memory> =
        book(context).recall(query, type, limit)
    fun forget(context: Context, name: String): Boolean = book(context).forget(name)

    /**
     * Erase the book, and forget it was ever read. The meta prefs go too, with
     * `commit()`: the caller is about to report success and the device may lose
     * power a second later.
     */
    fun wipe(context: Context) {
        book(context).wipe()
        metaPrefs(context).edit().clear().commit()
        // The wiki's index lived in the blob just wiped; its articles would be files nothing points at any more.
        wikiDir(context).listFiles()?.forEach { it.deleteRecursively() }
        // The live store writes into .history/ and archive/ without creating them; its constructor is what does.
        net.kuafuai.andee.wiki.LocalWikiStore(wikiDir(context))
    }

    fun indexForPrompt(context: Context, max: Int = 10): String = book(context).indexForPrompt(max)
    fun todos(context: Context, filter: String): List<Todo> = book(context).todos(filter)
    fun todoById(context: Context, id: String): Todo? = book(context).todoById(id)
    internal fun toTodo(o: JSONObject): Todo = NotebookBook.toTodo(o)
    internal fun toJson(t: Todo): JSONObject = NotebookBook.toJson(t)
    fun newTodoId(): String = NotebookBook.newTodoId()
    fun addTodo(context: Context, todo: Todo) = book(context).addTodo(todo)
    fun patchTodo(
        context: Context,
        id: String,
        status: String? = null,
        nextRunAt: Long? = null,
        lastRunAt: Long? = null,
        lastStatus: String? = null,
        lastError: String? = null,
    ): Todo? = book(context).patchTodo(id, status, nextRunAt, lastRunAt, lastStatus, lastError)
    fun scenes(context: Context): List<Scene> = book(context).scenes()
    fun scene(context: Context, name: String): Scene? = book(context).scene(name)
    fun saveScene(
        context: Context,
        name: String,
        title: String,
        summary: String,
        prompt: String,
        triggerApps: List<String>,
    ): Pair<Scene, Boolean> = book(context).saveScene(name, title, summary, prompt, triggerApps)
    fun deleteScene(context: Context, name: String): Boolean = book(context).deleteScene(name)
    fun markSceneUsed(context: Context, name: String) = book(context).markSceneUsed(name)
    fun scenesForPrompt(context: Context): String = book(context).scenesForPrompt()

    /**
     * Plain-text view for the settings screen. Read-only, and the only way out.
     *
     * **Labels are translated; the entries are not.** What is appended from
     * [Memory] / [Todo] — a name, a description, a cron expression, a
     * timestamp — is stored data and is printed exactly as written. Only the
     * scaffolding around it (headings, "expired", "source", separators) comes
     * from resources, which is why this builds the string in pieces instead of
     * one big template: the data would be un-escapable inside a resource.
     *
     * The signature stays `(Context)` on purpose. The caller passes whatever
     * Context it has and the wrap happens here, so no call site can forget it
     * and there is one place to look when a label comes out in the wrong
     * language. See [AppLocale] for why a Service's Context cannot simply be
     * used for the strings.
     */
    fun dumpText(context: Context): String {
        val ctx = AppLocale.wrap(context)
        val now = System.currentTimeMillis()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder()
        val mem = memories(context)
        sb.append(ctx.getString(R.string.notebook_dump_header, mem.size))
        sb.append(ctx.getString(R.string.notebook_dump_sort_note))
        if (mem.isEmpty()) {
            sb.append(ctx.getString(R.string.notebook_dump_empty_memories))
        }
        for (m in mem.sortedWith(compareByDescending<Memory> { it.updatedAt })) {
            sb.append("· ").append(m.name).append("  [").append(m.type).append("]")
            if (m.expired(now)) sb.append(ctx.getString(R.string.notebook_dump_expired))
            sb.append('\n')
            sb.append("   ").append(m.description).append('\n')
            if (m.content.isNotBlank() && m.content != m.description) {
                sb.append("   ").append(m.content.replace("\n", "\n   ")).append('\n')
            }
            sb.append("   ").append(fmt.format(Date(m.updatedAt)))
                .append(ctx.getString(R.string.notebook_dump_source)).append(m.source)
                .append(ctx.getString(R.string.notebook_dump_hits, m.hits))
        }
        val todos = todos(context, "all")
        sb.append(ctx.getString(R.string.notebook_dump_todos_header, todos.size))
        if (todos.isEmpty()) sb.append(ctx.getString(R.string.notebook_dump_empty_todos))
        for (t in todos.sortedBy { it.nextRunAt }) {
            sb.append("· ").append(t.what).append("  [").append(t.status).append("]\n")
            sb.append("   ")
                .append(
                    if (t.mode == "cron") {
                        ctx.getString(R.string.notebook_dump_recurring, t.cronExpr)
                    } else {
                        fmt.format(Date(t.at))
                    }
                )
                .append(ctx.getString(R.string.notebook_dump_next))
                .append(if (t.nextRunAt > 0) fmt.format(Date(t.nextRunAt)) else "—")
                .append('\n')
            if (t.lastError.isNotBlank()) {
                sb.append(ctx.getString(R.string.notebook_dump_last_error))
                    .append(t.lastError).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // meta: activity stamp + sweep budget
    //
    // Plain prefs, deliberately NOT in the encrypted blob. These are touched
    // on every turn (the activity stamp) and are not secret — none of them
    // says anything about the user. Routing them through the notebook would
    // mean re-encrypting the whole book on every single message.
    // ------------------------------------------------------------------

    /** When the user last said something. */
    fun touchActivity(context: Context) {
        metaPrefs(context).edit()
            .putLong(KEY_LAST_ACTIVITY, System.currentTimeMillis())
            .apply()
    }

    fun lastActivityAt(context: Context): Long = metaPrefs(context).getLong(KEY_LAST_ACTIVITY, 0L)

    /**
     * When the quiet-hour sweep last actually ran. Written by [noteSweep], so it
     * is the one fact that separates "the user spoke and the sweep already
     * looked back at it" from "the user spoke and the process died before the
     * sweep could". See `BodyCoreService.catchUpSweep`.
     */
    fun lastSweepAt(context: Context): Long = metaPrefs(context).getLong(KEY_LAST_SWEEP, 0L)

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** True when today's sweep count is still under [cap]. */
    fun sweepAllowed(context: Context, cap: Int): Boolean = sweepCountToday(context) < cap

    fun sweepCountToday(context: Context): Int {
        val p = metaPrefs(context)
        return if (p.getString(KEY_SWEEP_DAY, "") == today()) p.getInt(KEY_SWEEP_COUNT, 0) else 0
    }

    fun sweepTokensToday(context: Context): Int {
        val p = metaPrefs(context)
        return if (p.getString(KEY_SWEEP_DAY, "") == today()) p.getInt(KEY_SWEEP_TOKENS, 0) else 0
    }

    fun noteSweep(context: Context, tokens: Int) {
        val day = today()
        val count = sweepCountToday(context)
        val toks = sweepTokensToday(context)
        metaPrefs(context).edit()
            .putString(KEY_SWEEP_DAY, day)
            .putInt(KEY_SWEEP_COUNT, count + 1)
            .putInt(KEY_SWEEP_TOKENS, toks + tokens)
            .putLong(KEY_LAST_SWEEP, System.currentTimeMillis())
            .apply()
    }

    private fun metaPrefs(context: Context) =
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)

    // ---- crypto ----------------------------------------------------
    // Same construction as Vault: base64(iv || ciphertext), key in the
    // Android Keystore, no user-authentication requirement (a notebook write
    // happens mid-conversation, not at the lock screen).

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(c.iv + body, Base64.NO_WRAP)
    }.getOrNull()

    private fun decrypt(blob: String): String? = runCatching {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, raw, 0, IV_BYTES),
        )
        String(c.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    }.getOrElse {
        // A restored backup or a wiped Keystore leaves an undecryptable blob.
        // Same trap as Vault: from the outside it looks like an empty notebook.
        Log.e(TAG, "notebook: decrypt failed — the notebook is unreadable on this install", it)
        null
    }
}
