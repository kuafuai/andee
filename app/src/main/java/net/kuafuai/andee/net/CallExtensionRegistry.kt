package net.kuafuai.andee.net

import org.java_websocket.WebSocket
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single state slot for the "call extension" — a peripheral (e.g. the
 * whatsapp-agent box on another machine) that dials into [BodyWsServer], sends
 * a `register` frame with `role="call_extension"`, and thereby lends its own
 * tools to this body. From brain's point of view those tools show up in the
 * same `register` payload the hub gets from us, next to `screen.*` / `device.*`
 * / `meeting.*` — one device, more arms.
 *
 * Kept as one slot on purpose. The physical topology behind an extension is one
 * phone bridged through USB sound cards; two extensions racing to touch the
 * same phone is not a thing that means anything, and a second connect just
 * replaces the first (see [BodyWsServer.onMessage]).
 *
 * Not thread-safe beyond the volatile writes: [register] / [unregister] are
 * always called from a single [BodyWsServer] callback thread. Readers off other
 * threads see the latest snapshot but may race the transition; the extension's
 * absence is the only failure mode and every reader already handles it.
 */
class CallExtensionRegistry {

    @Volatile
    private var current: WebSocket? = null

    @Volatile
    private var toolsRaw: JSONArray = JSONArray()

    @Volatile
    private var sceneText: String? = null

    @Volatile
    private var deviceName: String? = null

    /**
     * Fires on both register AND unregister, so the service can trigger a
     * single hub re-register whichever way the extension state just changed —
     * the hub's `register` payload contains a snapshot of our tools, and a
     * stale one is a tool call that fails at dispatch time. Runs on the WS
     * callback thread; keep it short.
     */
    @Volatile
    var onChange: (() -> Unit)? = null

    /** The currently registered extension connection, or null. */
    fun connection(): WebSocket? = current

    /** Tool schemas as the extension registered them. Never null; may be empty. */
    fun tools(): JSONArray = toolsRaw

    /** Human-facing description the extension self-supplied ("USB dongle #1"). */
    fun deviceName(): String? = deviceName

    /** Prompt-facing scene description the extension supplied for the brain. */
    fun scene(): String? = sceneText

    /**
     * Whether an extension is currently offering a tool by this name. Iterated
     * off the volatile snapshot; ok to race — a mid-swap "no" is just a race a
     * fresh call would win.
     */
    fun hasMethod(name: String): Boolean {
        val snap = toolsRaw
        for (i in 0 until snap.length()) {
            if (snap.optJSONObject(i)?.optString("name") == name) return true
        }
        return false
    }

    /**
     * Install a new extension. Returns the previous connection when one
     * existed — caller is responsible for closing it, because the answer to
     * "was there one already" is also the answer to "should I hang up on it".
     */
    fun register(
        conn: WebSocket,
        tools: JSONArray,
        deviceName: String?,
        scene: String?,
    ): WebSocket? {
        val previous = current
        current = conn
        toolsRaw = tools
        this.deviceName = deviceName?.ifEmpty { null }
        this.sceneText = scene?.ifEmpty { null }
        onChange?.invoke()
        return previous
    }

    /**
     * Drop [conn] if it is the currently registered one. No-op when the
     * argument is not the live extension — a late `onClose` from a connection
     * that was already replaced must not clobber the replacement's state.
     */
    fun unregister(conn: WebSocket): Boolean {
        if (current !== conn) return false
        current = null
        toolsRaw = JSONArray()
        deviceName = null
        sceneText = null
        onChange?.invoke()
        return true
    }
}
