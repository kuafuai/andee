package net.kuafuai.andee.device

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
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
 * The phone's side of "one soul on two bodies": who it is, who it has paired with, who is reachable now.
 * The same link as the Mac's (see the desktop's `Peers`) — pairing, an authenticated encrypted channel, a
 * list — and nothing on top of it yet. Off until the user switches it on; nothing listens or is announced
 * before that.
 */
class AndroidPeers(context: Context) {

    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val store: PeerStore by lazy {
        PeerStore(PeerKeystore(app)) { android.os.Build.MODEL.ifBlank { "Android" } }
    }

    @Volatile var enabled: Boolean = prefs().getBoolean("on", false)
        private set

    private val seen = ConcurrentHashMap<String, PeerDiscovery.Found>()
    private val links = ConcurrentHashMap<String, PeerChannel>()
    @Volatile private var server: PeerServer? = null
    @Volatile var lastError: String = ""
        private set
    @Volatile var onChange: () -> Unit = {}

    /** How many friends there are, whenever the list may have changed. For the 朋友 key's badge; separate from
     *  [onChange], which belongs to whichever card is open. */
    @Volatile var onCount: (Int) -> Unit = {}

    private fun changed() { onChange(); onCount(store.all().size) }

    // Android filters multicast packets to save power; without this lock mDNS replies never arrive.
    private var lock: WifiManager.MulticastLock? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private fun prefs() = app.getSharedPreferences("peer_prefs", Context.MODE_PRIVATE)

    /** Handing work across. The service sets [PeerTasks.brain] while a local brain exists. */
    val tasks = PeerTasks(object : PeerTasks.Net {
        override val platform = "phone"
        override val can = PeerTasks.canFor("phone")
        override fun trust(id: String) = store.trust(id)
        override fun enabled() = enabled
        override fun paired() = store.all()
        // Found by mDNS, or reached before: worth dialling. The handshake decides whether it is really them.
        override fun reachable(id: String) = seen.containsKey(id) || store.address(id) != null
        override fun channel(id: String) = links[id]
        override fun connect(id: String) = this@AndroidPeers.connect(id)
    })

    /** The paired computer's agent, used as if it were this phone's own ([PeerRemote]). */
    val remote = PeerRemote.Client { id -> links[id] }

    /** A running worker's progress changed (or it ended): the 在办 card and key re-read. Set by the UI. */
    @Volatile var onProgressChanged: () -> Unit = {}

    /**
     * A worker on the computer ended: its report, as it came. Set by the service, which turns it into a turn of the
     * brain's own (fenced: the worker may have read anything). [device] is the computer it came from.
     */
    @Volatile var onRemoteReport: (device: String, worker: String, text: String, title: String) -> Unit = { _, _, _, _ -> }

    private val events = object : PeerEvents {
    override fun onMessage(channel: PeerChannel, msg: JSONObject) {
    if (tasks.onMessage(channel, msg)) return
    if (remote.onMessage(msg)) return
    when (msg.optString("type")) {
        "remote.progress" -> { net.kuafuai.andee.peer.RemoteProgress.update(net.kuafuai.andee.peer.RemoteProgress.parse(msg)); onProgressChanged(); return }
        "remote.progress.end" -> { net.kuafuai.andee.peer.RemoteProgress.end(msg.optString("worker")); onProgressChanged(); return }
    }
    if (msg.optString("type") == "remote.event") {
        onRemoteReport(channel.peer.id, msg.optString("worker"), msg.optString("text"), msg.optString("detail"))
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
        }
        override fun onDisconnected(channel: PeerChannel) { links.remove(channel.peer.id); changed() }
        override fun onRejected(remote: String, why: String) { Log.w(TAG, "refused $remote: $why") }
    }

    fun setEnabled(on: Boolean) {
        prefs().edit().putBoolean("on", on).apply()
        enabled = on
        if (on) start() else stop()
        changed()
    }

    fun startIfEnabled() { if (enabled) start() }

    @Synchronized private fun start() {
        stop()
        val me = store.identity()
        lastError = ""
        runCatching {
            lock = wifi.createMulticastLock("andee-peers").apply { setReferenceCounted(false); acquire() }
            val s = PeerServer(PeerDiscovery.DEFAULT_PORT, me, store, events) { peer ->
                // What the user said they were adding when they showed the code. Decided here and only here: the
                // other side cannot claim to be "own".
                store.setTrust(peer.id, inviteTrust)
                Log.i(TAG, "paired with ${peer.name} (${inviteTrust.key})"); code = null; changed()
            }
            s.isReuseAddr = true
            Thread({ runCatching { s.run() } }, "peer-server").apply { isDaemon = true }.start()
            server = s
            announce(me)
            discover(me)
        }.onFailure { lastError = it.message.orEmpty(); Log.w(TAG, "peers could not start", it) }
    }

    @Synchronized private fun stop() {
        runCatching { registration?.let { nsd.unregisterService(it) } }
        runCatching { discoveryListener?.let { nsd.stopServiceDiscovery(it) } }
        registration = null; discoveryListener = null
        runCatching { server?.stop(200) }; server = null
        runCatching { lock?.release() }; lock = null
        links.values.forEach { runCatching { it.close() } }
        links.clear(); seen.clear(); code = null
    }

    private fun announce(me: PeerProtocol.Identity) {
        val info = NsdServiceInfo().apply {
            serviceName = "andee-${me.id.take(8)}"
            serviceType = PeerDiscovery.SERVICE_TYPE
            port = PeerDiscovery.DEFAULT_PORT
            setAttribute(PeerDiscovery.TXT_ID, me.id)
            setAttribute(PeerDiscovery.TXT_NAME, me.name)
            setAttribute(PeerDiscovery.TXT_FP, me.fingerprint)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "announcing as ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { lastError = "announce failed ($code)" }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        registration = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    private fun discover(me: PeerProtocol.Identity) {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { lastError = "discovery failed ($code)" }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
            override fun onServiceLost(i: NsdServiceInfo) {
                seen.values.firstOrNull { "andee-${it.id.take(8)}" == i.serviceName }?.let { seen.remove(it.id); changed() }
            }
            override fun onServiceFound(i: NsdServiceInfo) {
                if (i.serviceName == "andee-${me.id.take(8)}") return   // that is us
                resolveLater(i, 0)
            }
        }
        discoveryListener = l
        nsd.discoverServices(PeerDiscovery.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
    }

    /**
     * One resolve at a time, retried. Before Android 14 `NsdManager` resolves a single service at a time and
     * answers a second request with `FAILURE_ALREADY_ACTIVE` — and `onServiceFound` fires once per service, so a
     * resolve that lost that race was never tried again: the Mac stayed "not reachable" until the next restart,
     * while sitting on the same Wi-Fi (measured on the LIO-TL00, Android 12, with the phone and the Mac both
     * announcing). Hence a queue, and a retry for any failure.
     */
    private val resolveQueue = ArrayDeque<Pair<NsdServiceInfo, Int>>()
    private var resolving = false
    private val resolveHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun resolveLater(i: NsdServiceInfo, attempt: Int) {
        synchronized(resolveQueue) { resolveQueue.addLast(i to attempt) }
        pumpResolve()
    }

    private fun pumpResolve() {
        val (next, attempt) = synchronized(resolveQueue) {
            if (resolving) return
            val n = resolveQueue.removeFirstOrNull() ?: return
            resolving = true
            n
        }
        val done = {
            synchronized(resolveQueue) { resolving = false }
            pumpResolve()
        }
        runCatching {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "resolve ${s.serviceName} failed ($code), attempt $attempt")
                    if (attempt < RESOLVE_TRIES) resolveHandler.postDelayed({ resolveLater(next, attempt + 1) }, RESOLVE_RETRY_MS * (attempt + 1))
                    done()
                }
                override fun onServiceResolved(s: NsdServiceInfo) {
                    val attrs = s.attributes
                    val id = attrs[PeerDiscovery.TXT_ID]?.let { String(it) }
                    val host = s.host?.hostAddress
                    if (id != null && host != null) {
                        seen[id] = PeerDiscovery.Found(
                            id, attrs[PeerDiscovery.TXT_NAME]?.let { String(it) }.orEmpty(), host, s.port,
                            attrs[PeerDiscovery.TXT_FP]?.let { String(it) }.orEmpty(),
                        )
                        Log.i(TAG, "found ${seen[id]?.name} at $host")
                        // A friend just appeared: link up now — see the desktop's twin of this line.
                        if (store.byId(id) != null && links[id]?.isOpen != true) {
                            Thread { connect(id) }.apply { isDaemon = true; name = "peer-dial" }.start()
                        }
                        changed()
                    }
                    done()
                }
            })
        }.onFailure { Log.w(TAG, "resolve could not start", it); done() }
    }

    // ---- pairing ----

    /** The code on screen while this phone shows a QR, or null. */
    @Volatile var code: PairingCode? = null
        private set

    /** Who the QR on screen is for. Set by [invite]; read when somebody pairs with it. */
    @Volatile private var inviteTrust = PeerStore.Trust.FRIEND

    /**
     * Show this phone as a QR code another phone (or a Mac's user, with their phone) can scan. Switches linking
     * on first, since a code nobody can dial is the one thing this must not show. Null when there is no Wi-Fi
     * address to put in it, or the server would not start ([lastError]).
     */
    fun invite(trust: PeerStore.Trust): String? {
        if (!enabled) setEnabled(true)
        val s = server ?: return null
        val host = lanAddress() ?: run { lastError = "no Wi-Fi address"; return null }
        inviteTrust = trust
        val c = PairingCode(PeerProtocol.newCode())
        s.openPairing(c); code = c; changed()
        val me = store.identity()
        return PeerQr.encode(PeerQr.Invite(host, PeerDiscovery.DEFAULT_PORT, me.id, me.name, me.fingerprint, c.code))
    }

    fun cancelCode() { server?.closePairing(); code = null; changed() }

    /** This phone's IPv4 on the network it is on now — the one a friend on the same Wi-Fi can dial. */
    private fun lanAddress(): String? {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    fun setTrust(id: String, t: PeerStore.Trust) { store.setTrust(id, t); changed() }
    fun setAutoAccept(id: String, on: Boolean) { store.setAutoAccept(id, on); changed() }

    /**
     * Pair with the Mac whose QR code was just scanned. Blocks; run off the main thread. Throws
     * [PeerClient.Failed] (or [IllegalArgumentException] for a QR that is not ours) with a sentence a person
     * can read. Only the key the QR names is accepted — see [PeerQr].
     *
     * The address in the QR is tried first; if the Mac has another one this phone already found by mDNS, that
     * is the fallback (a Mac on Wi-Fi and Ethernet at once advertises the one the QR did not pick). Linking is
     * switched on afterwards: the user just paired two devices, and a pair that cannot find each other is not
     * what they asked for.
     */
    fun pairFromQr(raw: String): PeerProtocol.Peer {
        val inv = PeerQr.decode(raw) ?: throw IllegalArgumentException("not an Andee pairing code")
        val me = store.identity()
        val hosts = listOfNotNull(inv.host, seen[inv.id]?.host).distinct()
        var last: Exception? = null
        for (h in hosts) {
            try {
                val peer = PeerClient.pair(h, inv.port, me, inv.code, store, expectFingerprint = inv.fingerprint)
                store.setAddress(peer.id, h, inv.port)
                if (!enabled) setEnabled(true) else changed()
                return peer
            } catch (e: PeerClient.Failed) { last = e }
        }
        throw last ?: PeerClient.Failed("could not reach ${inv.name}")
    }

    data class Row(
        val id: String, val name: String, val fingerprint: String, val paired: Boolean, val reachable: Boolean, val linked: Boolean,
        val found: PeerDiscovery.Found?, val trust: PeerStore.Trust, val auto: Boolean,
    )

    fun rows(): List<Row> {
        val paired = store.all().associateBy { it.id }
        val ids = (paired.keys + seen.keys).toSortedSet()
        return ids.map { id ->
            val p = paired[id]; val f = seen[id]
            Row(id, p?.name ?: f?.name.orEmpty(), p?.fingerprint ?: f?.fingerprint.orEmpty(), p != null, f != null, links[id]?.isOpen == true, f,
                store.trust(id), store.autoAccept(id))
        }.sortedWith(compareByDescending<Row> { it.paired }.thenBy { it.name })
    }

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

    fun unpair(id: String) { links.remove(id)?.close(); store.remove(id); changed() }

    fun ping(id: String): Boolean = links[id]?.send(JSONObject().put("type", "ping").put("n", (System.nanoTime() % 1000).toInt())) == true

    private companion object {
        const val TAG = "Peers"
        const val RESOLVE_TRIES = 5
        const val RESOLVE_RETRY_MS = 800L
    }
}
