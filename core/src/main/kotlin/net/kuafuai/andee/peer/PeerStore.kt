package net.kuafuai.andee.peer

import net.kuafuai.andee.config.NotebookStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

/**
 * This device's identity and the devices it has paired with, persisted through a [NotebookStore] — the same
 * encrypted-file / Keystore-backed storage the notebook and the vault already use, so the private key never
 * sits on disk in the clear on either platform, and "wipe" means one thing everywhere.
 *
 * The private key is the whole of this device's identity: whoever holds it can be this device to every peer
 * that pinned it. It is therefore stored only in the encrypted blob, never logged, never sent, and a factory
 * reset takes it — after which this device is a stranger to everything it had paired with, and they to it.
 *
 * Safe to call from any thread.
 */
class PeerStore(private val store: NotebookStore, private val defaultName: () -> String) : PeerPins {

    private val lock = Any()

    @Volatile private var identityCache: PeerProtocol.Identity? = null

    /** Created on first use and kept for the life of the install. The name is the user's to change. */
    fun identity(): PeerProtocol.Identity = identityCache ?: synchronized(lock) {
        identityCache?.let { return it }
        val root = load()
        val o = root.optJSONObject("identity")
        val ident = if (o != null) {
            runCatching { fromJson(o) }.getOrNull()
        } else null
        val made = ident ?: PeerProtocol.Identity(UUID.randomUUID().toString(), defaultName(), PeerCrypto.newKeyPair()).also {
            root.put("identity", toJson(it))
            save(root)
        }
        identityCache = made
        made
    }

    fun rename(name: String) = synchronized(lock) {
        val cur = identity()
        val renamed = PeerProtocol.Identity(cur.id, name.trim().ifEmpty { cur.name }.take(40), cur.keys)
        val root = load()
        root.put("identity", toJson(renamed))
        save(root)
        identityCache = renamed
    }

    // ---- PeerPins ----

    override fun byKey(publicKey: ByteArray): PeerProtocol.Peer? = all().firstOrNull { it.publicKey.contentEquals(publicKey) }

    override fun byId(id: String): PeerProtocol.Peer? = all().firstOrNull { it.id == id }

    override fun all(): List<PeerProtocol.Peer> = synchronized(lock) {
        val arr = load().optJSONArray("peers") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i -> runCatching { peerFrom(arr.getJSONObject(i)) }.getOrNull() }.sortedBy { it.name }
    }

    override fun add(peer: PeerProtocol.Peer) = synchronized(lock) {
        val root = load()
        val arr = root.optJSONArray("peers") ?: JSONArray()
        val kept = JSONArray()
        var before: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") != peer.id) kept.put(o) else before = o   // pairing again replaces: a new key under the same id is the point
        }
        // A new pairing is a friend until the user says it is one of their own. Pairing an existing one again
        // keeps what the user had decided about it.
        kept.put(
            JSONObject().put("id", peer.id).put("name", peer.name).put("key", b64(peer.publicKey))
                .put("paired_at", System.currentTimeMillis())
                .put("trust", before?.optString("trust")?.ifEmpty { null } ?: Trust.FRIEND.key)
                .put("auto", before?.optBoolean("auto") ?: false),
        )
        save(root.put("peers", kept))
    }

    override fun remove(id: String): Boolean = synchronized(lock) {
        val root = load()
        val arr = root.optJSONArray("peers") ?: return false
        val kept = JSONArray()
        var hit = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id) hit = true else kept.put(o)
        }
        if (hit) save(root.put("peers", kept))
        hit
    }

    fun pairedAt(id: String): Long = synchronized(lock) {
        val arr = load().optJSONArray("peers") ?: return 0L
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") == id) return arr.getJSONObject(i).optLong("paired_at")
        0L
    }

    // ---- how far each one is trusted ----

    /**
     * Two kinds of paired device, and the difference is the whole safety story of working together.
     *
     * - [OWN]: another of the user's own devices. Its requests are the user's own, run in the same conversation
     *   with the same memory, and its reports are trusted.
     * - [FRIEND]: somebody else's Andee. Its requests are a stranger's words: the user is asked before each one
     *   (unless they chose "always" for that friend), they run in a conversation of their own that cannot see
     *   this user's memory or history, and with no vault, no commands, no personal data.
     */
    enum class Trust(val key: String) {
        OWN("own"), FRIEND("friend");
        companion object { fun of(s: String?) = values().firstOrNull { it.key == s } }
    }

    /**
     * Unknown id → [Trust.FRIEND]. A record with no `trust` at all was paired before friends existed, when every
     * pairing meant "my other device" — so it reads as [Trust.OWN], which is what the user agreed to then.
     */
    fun trust(id: String): Trust = synchronized(lock) {
        val o = record(id) ?: return Trust.FRIEND
        if (!o.has("trust")) Trust.OWN else Trust.of(o.optString("trust")) ?: Trust.FRIEND
    }

    fun setTrust(id: String, t: Trust) = update(id) {
        it.put("trust", t.key)
        // A device filed as a friend loses what only an own device may have.
        if (t != Trust.OWN) it.put("dsh_ok", false)
    }

    /** For a friend: run its requests without asking each time. Meaningless for [Trust.OWN], which never asks. */
    fun autoAccept(id: String): Boolean = synchronized(lock) { record(id)?.optBoolean("auto") ?: false }

    fun setAutoAccept(id: String, on: Boolean) = update(id) { it.put("auto", on) }

    /**
     * Whether [id], one of the user's own devices, may use this device's dsh directly: its `delegate_task` and friends
     * are carried over the link to the local [DshWorkers] with no Andee in between (see [PeerRemote]). The owner's switch,
     * per device, off until they turn it on, and only ever honoured for [Trust.OWN]: a friend's Andee never gets it.
     */
    fun dshAllowed(id: String): Boolean = synchronized(lock) {
        val o = record(id) ?: return false
        o.optBoolean("dsh_ok") && (if (!o.has("trust")) Trust.OWN else Trust.of(o.optString("trust"))) == Trust.OWN
    }

    fun setDshAllowed(id: String, on: Boolean) = update(id) { it.put("dsh_ok", on) }

    /**
     * Where [id] was last reached (`host:port`), kept across restarts. mDNS is the way to find a friend, and on some
     * routers and phones it simply does not deliver (measured: an Android 12 phone that never heard the Mac's
     * announcements while both sat on the same /24 and could dial each other directly). The last address that
     * worked is the fallback, and any connection still has to pass the pinned-key handshake, so a stale address
     * that now belongs to somebody else just fails to connect.
     */
    fun address(id: String): Pair<String, Int>? = synchronized(lock) {
        val a = record(id)?.optString("addr").orEmpty()
        val i = a.lastIndexOf(':')
        if (i <= 0) null else a.substring(0, i) to (a.substring(i + 1).toIntOrNull() ?: return null)
    }

    fun setAddress(id: String, host: String, port: Int) {
        if (address(id) == host to port) return
        update(id) { it.put("addr", "$host:$port") }
    }

    private fun record(id: String): JSONObject? {
        val arr = load().optJSONArray("peers") ?: return null
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") == id) return arr.getJSONObject(i)
        return null
    }

    private fun update(id: String, f: (JSONObject) -> Unit): Unit = synchronized(lock) {
        val root = load()
        val arr = root.optJSONArray("peers") ?: return
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") == id) { f(arr.getJSONObject(i)); save(root); return }
    }

    /** Forget everything, identity included. */
    fun wipe() = synchronized(lock) { store.wipe(); identityCache = null }

    // ---- storage ----

    private fun load(): JSONObject = runCatching { JSONObject(store.read() ?: "{}") }.getOrDefault(JSONObject())

    private fun save(o: JSONObject) {
        check(store.write(o.toString())) { "the pairing store could not be written" }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)

    private fun toJson(i: PeerProtocol.Identity) = JSONObject()
        .put("id", i.id).put("name", i.name)
        .put("public", b64(i.publicBytes)).put("private", b64(i.keys.private.encoded))

    private fun fromJson(o: JSONObject): PeerProtocol.Identity {
        val pub = PeerCrypto.publicFrom(unb64(o.getString("public")))
        val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unb64(o.getString("private"))))
        return PeerProtocol.Identity(o.getString("id"), o.getString("name"), KeyPair(pub, priv))
    }

    private fun peerFrom(o: JSONObject) = PeerProtocol.Peer(o.getString("id"), o.getString("name"), unb64(o.getString("key")))
}
