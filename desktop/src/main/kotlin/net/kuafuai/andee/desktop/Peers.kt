package net.kuafuai.andee.desktop

import net.kuafuai.andee.peer.PairingCode
import net.kuafuai.andee.peer.PeerChannel
import net.kuafuai.andee.peer.PeerClient
import net.kuafuai.andee.peer.PeerDiscovery
import net.kuafuai.andee.peer.PeerEvents
import net.kuafuai.andee.peer.PeerProtocol
import net.kuafuai.andee.peer.PeerQr
import net.kuafuai.andee.peer.PeerRemote
import net.kuafuai.andee.peer.PeerServer
import net.kuafuai.andee.peer.PeerStore
import net.kuafuai.andee.peer.PeerTasks
import net.kuafuai.andee.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The Mac's side of "one soul on two bodies": who it is, who it has paired with, who is reachable right now.
 *
 * This is the **link**, nothing more: pairing, an authenticated encrypted channel, and a list. What travels
 * over it (delegated work, shared memory) is built on top and arrives later; until then the only message is
 * a ping. Off by default and silent: nothing listens and nothing is announced until the user turns it on.
 */
object Peers {

    private const val TAG = "Peers"

    /** For the screenshot harness only: it has no Keychain. */
    @Volatile var storeOverride: net.kuafuai.andee.config.NotebookStore? = null

    val store: PeerStore by lazy {
        PeerStore(storeOverride ?: EncryptedFileStore(AppDirs.file("peers.enc"), "peers-key")) {
            // A name a person would recognise in a list on the other device.
            runCatching { java.net.InetAddress.getLocalHost().hostName.substringBefore('.') }.getOrDefault("Mac")
        }
    }

    /** Is this device listening and announcing? Persisted: a user who turned it on expects it on after a restart. */
    @Volatile var enabled: Boolean = Settings.get("peers") == "on"
        private set

    /** Peers that are reachable on the LAN right now (from mDNS), by device id. */
    private val seen = ConcurrentHashMap<String, PeerDiscovery.Found>()

    /** Authenticated links that are up, by device id. */
    private val links = ConcurrentHashMap<String, PeerChannel>()

    @Volatile private var server: PeerServer? = null
    @Volatile private var discovery: PeerDiscoveryMac? = null

    /** Something about the list changed (found, lost, connected, paired, unpaired). The UI re-reads. */
    @Volatile var onChange: () -> Unit = {}

    /** How many friends there are, as Compose state: the sidebar's badge reads it and redraws by itself. */
    // Filled at launch ([startIfEnabled]), not here: touching the store while this object initialises would open
    // the Keychain before the screenshot harness has had a chance to swap it out.
    val count = androidx.compose.runtime.mutableStateOf(0)

    private fun changed() { onChange(); count.value = store.all().size }

    /** The code showing right now, if a pairing window is open. */
    @Volatile var code: PairingCode? = null
        private set

    @Volatile var lastError: String = ""
        private set

    /** Handing work across. The assistant sets [PeerTasks.brain]; see `Assistant.start`. */
    val tasks = PeerTasks(object : PeerTasks.Net {
        override val platform = "mac"
        override val can = PeerTasks.canFor("mac")
        override fun trust(id: String) = store.trust(id)
        override fun enabled() = enabled
        override fun offersAgentTo(id: String) = store.dshAllowed(id)
        override fun paired() = store.all()
        // Found by mDNS, or reached before: worth dialling. The handshake decides whether it is really them.
        override fun reachable(id: String) = seen.containsKey(id) || store.address(id) != null
        override fun channel(id: String) = links[id]
        override fun connect(id: String) = this@Peers.connect(id)
    })

    private val events = object : PeerEvents {
        override fun onMessage(channel: PeerChannel, msg: JSONObject) {
            if (tasks.onMessage(channel, msg)) return
            // The owner's switch decides, per device, whether this Mac's dsh is used directly, with no Andee in between.
            if (msg.optString("type").startsWith("remote.")) {
                val agent = net.kuafuai.andee.desktop.dsh.DshRemoteAgent(Assistant.dsh, channel.peer.id) { Settings.get("dsh") != "off" }
                PeerRemote.Server({ agent }, { store.dshAllowed(it) }).onMessage(channel, msg)
                return
            }
            if (msg.optString("type") == "ping") channel.send(JSONObject().put("type", "pong").put("n", msg.optInt("n")))
        }
        override fun onConnected(channel: PeerChannel) {
            links[channel.peer.id] = channel; Log.i(TAG, "linked: ${channel.peer.name}")
            // Whoever dialled whom, both sides now know where the other is (our port is theirs too: it is fixed).
            channel.remoteHost?.let { store.setAddress(channel.peer.id, it, PeerDiscovery.DEFAULT_PORT) }
            changed()
            tasks.onConnected(channel)
            flushEvents(channel)
        }
        override fun onDisconnected(channel: PeerChannel) { links.remove(channel.peer.id); changed() }
        override fun onRejected(remote: String, why: String) { Log.w(TAG, "refused $remote: $why") }
    }

    fun setEnabled(on: Boolean) {
        Settings.save(mapOf("peers" to if (on) "on" else "off"))
        enabled = on
        if (on) start() else stop()
        changed()
    }

    /** Call once at launch. Does nothing unless the user turned this on. */
    fun startIfEnabled() {
        count.value = runCatching { store.all().size }.getOrDefault(0)
        if (enabled) start()
    }

    @Synchronized private fun start() {
        stop()
        val me = store.identity()
        lastError = ""
        val s = runCatching {
            PeerServer(PeerDiscovery.DEFAULT_PORT, me, store, events) { peer ->
                // What the user said they were adding when they showed the code: their own device, or a friend.
                // Decided here and only here — the other side's QR or hello cannot claim to be "own".
                store.setTrust(peer.id, inviteTrust)
                Log.i(TAG, "paired with ${peer.name} (${inviteTrust.key})"); code = null; changed()
            }
                .also { it.isReuseAddr = true; Thread({ runCatching { it.run() } }, "peer-server").apply { isDaemon = true }.start() }
        }.getOrElse { lastError = it.message.orEmpty(); null } ?: return
        server = s
        discovery = PeerDiscoveryMac(object : PeerDiscovery.Listener {
            override fun onFound(f: PeerDiscovery.Found) {
                if (f.id == me.id) return
                seen[f.id] = f
                changed()
                // A friend just appeared: link up now, so both sides learn where the other is even on a network
                // whose mDNS only works one way. See [PeerStore.address].
                if (store.byId(f.id) != null && links[f.id]?.isOpen != true) {
                    Thread { connect(f.id) }.apply { isDaemon = true; name = "peer-dial" }.start()
                }
            }
            override fun onLost(id: String) { seen.remove(id); changed() }
        }).also { it.start(me, PeerDiscovery.DEFAULT_PORT) }
    }

    @Synchronized private fun stop() {
        runCatching { server?.stop(200) }
        server = null
        discovery?.stop(); discovery = null
        links.values.forEach { runCatching { it.close() } }
        links.clear(); seen.clear(); code = null
    }

    // ---- pairing ----

    /** Show a code on this device; a device that types it within two minutes (three tries) gets paired. */
    fun showCode(): String? {
        val s = server ?: return null
        val c = PairingCode(PeerProtocol.newCode())
        s.openPairing(c); code = c; changed()
        return c.code
    }

    fun cancelCode() { server?.closePairing(); code = null; changed() }

    /**
     * Open a pairing window and return what the QR code says: this Mac's LAN address, the fresh code and this
     * Mac's fingerprint, for the phone to scan. Switches linking on first — showing a code nobody can dial is
     * the one thing this button must not do. Null when the server could not start ([lastError] says why).
     */
    fun invite(trust: PeerStore.Trust): String? {
        if (!enabled) setEnabled(true)
        inviteTrust = trust
        val c = showCode() ?: return null
        val me = store.identity()
        return PeerQr.encode(PeerQr.Invite(PeerDiscoveryMac.lanAddress().hostAddress, PeerDiscovery.DEFAULT_PORT, me.id, me.name, me.fingerprint, c))
    }

    /** Who the QR on screen is for. Set by [invite]; read when somebody pairs with it. */
    @Volatile private var inviteTrust = PeerStore.Trust.FRIEND

    fun setTrust(id: String, t: PeerStore.Trust) { store.setTrust(id, t); changed() }
    fun setAutoAccept(id: String, on: Boolean) { store.setAutoAccept(id, on); changed() }

    /** The owner's switch for [id]: may that own device use this Mac's dsh directly. Tells it on the open link now. */
    fun setDshAllowed(id: String, on: Boolean) { store.setDshAllowed(id, on); tasks.reannounce(id); changed() }

    /** A job a paired device started has ended: its Andee gets the report on the open link, or when it is back (30 min). */
    fun remoteFinished(device: String, worker: String, text: String, detail: String) {
        val msg = JSONObject().put("type", "remote.event").put("worker", worker).put("text", text.take(8_000)).put("detail", detail.take(8_000))
        if (links[device]?.takeIf { it.isOpen }?.send(msg) == true) return
        pendingEvents.add(Triple(device, System.currentTimeMillis(), msg))
    }

    private val pendingEvents = java.util.concurrent.CopyOnWriteArrayList<Triple<String, Long, JSONObject>>()

    /** Reports waiting for a device to come back, sent when its link comes up. Older than 30 minutes are dropped. */
    private fun flushEvents(channel: PeerChannel) {
        val now = System.currentTimeMillis()
        for (e in pendingEvents.toList()) {
            if (now - e.second > 30 * 60_000L) { pendingEvents.remove(e); continue }
            if (e.first == channel.peer.id && channel.send(e.third)) pendingEvents.remove(e)
        }
    }

    // ---- the list ----

    data class Row(
    val id: String, val name: String, val fingerprint: String, val paired: Boolean, val reachable: Boolean, val linked: Boolean,
    val found: PeerDiscovery.Found?, val trust: PeerStore.Trust, val auto: Boolean, val dsh: Boolean = false,
    )

    fun rows(): List<Row> {
        val paired = store.all().associateBy { it.id }
        val ids = (paired.keys + seen.keys).toSortedSet()
        return ids.map { id ->
            val p = paired[id]; val f = seen[id]
            Row(id, p?.name ?: f?.name.orEmpty(), p?.fingerprint ?: f?.fingerprint.orEmpty(), p != null, f != null, links[id]?.isOpen == true, f,
                store.trust(id), store.autoAccept(id), store.dshAllowed(id))
        }.sortedWith(compareByDescending<Row> { it.paired }.thenBy { it.name })
    }

    /** Dial a paired device that is reachable and hold the link open. */
    /** Ids being dialled now. mDNS reports one device several times (per address, per interface); one dial is enough. */
    private val dialing = ConcurrentHashMap.newKeySet<String>()

    fun connect(id: String): Boolean {
        if (links[id]?.isOpen == true) return true
        if (!dialing.add(id)) return false
        try { return dial(id) } finally { dialing.remove(id) }
    }

    private fun dial(id: String): Boolean {
        // Where mDNS says it is now, then where it was last reached — see [PeerStore.address].
        val tries = listOfNotNull(seen[id]?.let { it.host to it.port }, store.address(id)).distinct()
        for ((host, port) in tries) {
            val ok = runCatching { PeerClient.connect(host, port, store.identity(), store, events, timeoutMs = 4_000) }
                .onFailure { lastError = it.message.orEmpty() }.isSuccess
            if (ok) { store.setAddress(id, host, port); changed(); return true }
        }
        changed()
        return false
    }

    /** Unpair: forget the key, drop the link. The other device keeps its pin until the user unpairs there too. */
    fun unpair(id: String) {
        links.remove(id)?.close()
        store.remove(id)
        changed()
    }

    fun ping(id: String): Boolean = links[id]?.send(JSONObject().put("type", "ping").put("n", (System.nanoTime() % 1000).toInt())) == true
}
