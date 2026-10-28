package net.kuafuai.andee.wiki

import java.time.Instant

/**
 * The knowledge base: what this Andee has written and read, kept as articles. Design: `docs/design/inner-drive.zh-CN.md` §9.
 *
 * Three decisions shape it, all the user's:
 *
 *  1. **The store is an interface.** Local folder, Feishu, WeCom, Google Docs are backends; the rules about writing
 *     (search before you write, revise instead of duplicating, states, limits) live once in [Wiki] and the backend
 *     only carries bytes. What environment this Andee lives in is not for Andee to decide.
 *  2. **No mirror.** The body of an article exists in exactly one place. Locally there is a [Meta] for every
 *     document this Andee knows of — wherever its body is — and it is a pointer, not a copy: it answers "I know
 *     there is one, where it is, roughly what it says", never "word for word". Reading goes to the source, now.
 *     That removes "the local one and the online one disagree" and leaves only "my summary is stale", which the
 *     read itself repairs.
 *  3. **Confidentiality is a label, not a lock.** [Level] rides in every [Meta] and is shown to the model and
 *     in settings; the tool layer adds a reminder when something marked [Level.SECRET] goes out. Nothing is
 *     refused for it. (If that turns out to be too soft, the place to harden it is [Wiki.share].)
 *
 * The wiki is **not encrypted** (a working folder: shareable, openable in any editor), which is why anything
 * sensitive about a *person* belongs in [net.kuafuai.andee.config.People], not here.
 */

enum class Level(val key: String, val rank: Int) {
    PUBLIC("public", 0), INTERNAL("internal", 1), SECRET("secret", 2), PRIVATE("private", 3);

    companion object {
        /** An unknown label reads as [INTERNAL], never as public: the cautious way to be wrong. */
        fun of(s: String?): Level = entries.firstOrNull { it.key == s?.trim()?.lowercase() } ?: INTERNAL
    }
}

enum class Status(val key: String) {
    DRAFT("draft"), ACTIVE("active"), STALE("stale"), ARCHIVED("archived"),
    /** The body could not be reached (deleted, moved, no permission). The metadata stays as a lead. */
    LOST("lost");

    companion object {
        fun of(s: String?): Status = entries.firstOrNull { it.key == s?.trim()?.lowercase() } ?: DRAFT
    }
}

val KINDS = setOf("plan", "research", "review", "note", "playbook")

/** What is known locally about one article. Never the body. */
data class Meta(
    val id: String,
    val title: String,
    val kind: String,
    val status: Status,
    val level: Level,
    /** Where the body is: `local`, `feishu`, … — the backend's key. */
    val where: String,
    /** How to reach it on that backend: a relative path, a URL. */
    val ref: String,
    /** "查到的 / 他说的 / 我推断的", and from what. Never blank. */
    val source: String,
    /** One or two lines of what it says, refreshed whenever the body is read or written. */
    val summary: String,
    val version: Int,
    val updated: Long,
    /** `care:<id>` / `person:<id>` / `wiki:<id>` */
    val links: List<String>,
    /** When the body was last actually read from its source. 0 = never (only known of). */
    val verified: Long,
    /** The user edited it; the model must say so before changing it. */
    val byUser: Boolean,
    /** Why `stale`/`lost`, in a sentence. */
    val reason: String,
    /** The tool-call id that produced this version — what a later "I wrote this" has to point at. */
    val madeBy: String,
)

/** A full article: its [meta] plus the body, which is whatever the source says right now. */
data class Article(val meta: Meta, val body: String)

/**
 * What a backend must do. Small on purpose: if it grows, every new backend has to reimplement the thing the
 * layer above is supposed to own once.
 */
interface WikiStore {
    /** `local`, `feishu`, … */
    val key: String
    val capabilities: Capabilities

    /** Read the body from the source *now*. A thrown [WikiUnavailable] means it cannot be reached — not "empty". */
    fun read(ref: String): String

    /** Create (when [ref] is null) or replace. Returns the ref it now has. */
    fun write(ref: String?, title: String, body: String, changeNote: String): String

    /** Share with someone. Backends that cannot return [Share.unsupported]. */
    fun share(ref: String, to: String, permission: String): Share

    /** Move out of the way (archive), never destroy. */
    fun archive(ref: String)

    data class Capabilities(
        val versionHistory: Boolean = false,
        val comments: Boolean = false,
        val sharing: Set<String> = emptySet(),
        /** Operating it means driving a screen, so it only runs while the user is present or has said it may. */
        val needsScreen: Boolean = false,
    )

    data class Share(val ok: Boolean, val note: String) {
        companion object { fun unsupported(why: String) = Share(false, why) }
    }
}

class WikiUnavailable(message: String) : RuntimeException(message)

/** A single-line, tab-free rendering for the prompt index. */
internal fun oneLine(s: String, max: Int): String = s.replace(Regex("\\s+"), " ").trim().take(max)

internal fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString().take(16).replace('T', ' ')
