package net.kuafuai.andee.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import net.kuafuai.andee.device.DeviceState
import net.kuafuai.andee.net.ToolSchemas
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Screen perception + actuation: tap / swipe / long-press / type / screenshot /
 * global-action / ui-tree-dump. Wraps AccessibilityService APIs.
 */
class ScreenController(
    private val service: AccessibilityService,
    /**
     * Where injected gestures get drawn after the fact. Null in any caller that
     * has no overlay to draw on; see [net.kuafuai.andee.ui.TapMarkerUi] for why
     * it is marked *after* the stroke and never before.
     */
    private val marker: net.kuafuai.andee.ui.TapMarkerUi? = null,
) {

    /** Last meaningful accessibility event from a non-overlay app. */
    private val lastContentEventMs = AtomicLong(0L)

    fun noteEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == service.packageName) return
        when (event.eventType) {
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED,
            android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                lastContentEventMs.set(System.currentTimeMillis())
        }
    }

    // ---- normalized coordinate protocol -----------------------------------
    //
    // The brain speaks 0-1000 RELATIVE integers for every gesture, never
    // pixels: screenshots may have been downscaled on their way to the model,
    // so only a relative space survives the trip and stays resolution-
    // independent. Values outside 0-1000 are REJECTED, not clamped — a silent
    // clamp to the edge is a mis-tap the model can't even see happening.

    private val screenW: Int get() = service.resources.displayMetrics.widthPixels
    private val screenH: Int get() = service.resources.displayMetrics.heightPixels

    private fun Int.toPixel(max: Int): Int = this * max / NORM_MAX

    private fun requireNorm(v: Int, name: String): Int {
        if (v !in 0..NORM_MAX) {
            throw IllegalArgumentException(
                "coordinate $name=$v out of range — expected 0..$NORM_MAX (relative, not pixels)"
            )
        }
        return v
    }

    fun tapNorm(nx: Int, ny: Int): JSONObject {
        requireNorm(nx, "x"); requireNorm(ny, "y")
        val w = screenW; val h = screenH
        val px = nx.toPixel(w); val py = ny.toPixel(h)
        // Logged at the four norm/eid entry points rather than in [tap], even
        // though TapMarkerUi is hooked there and treats every path alike. It
        // can afford to — a ring is a ring. Here the distinction is the whole
        // point: only a coordinate the BRAIN chose is a grounding sample; an
        // e-tap's pixels were computed by us and would poison the set.
        guardRepetition(px, py) {
            GroundingLog.noteTapCoord(nx, ny, px, py, w, h, hold = false, refused = true, pkg = lastPkg())
        }
        GroundingLog.noteTapCoord(nx, ny, px, py, w, h, hold = false, refused = false, pkg = lastPkg())
        return tap(px, py).put("norm", "$nx,$ny")
    }

    fun longPressNorm(nx: Int, ny: Int, durationMs: Long): JSONObject {
        requireNorm(nx, "x"); requireNorm(ny, "y")
        val w = screenW; val h = screenH
        val px = nx.toPixel(w); val py = ny.toPixel(h)
        guardRepetition(px, py) {
            GroundingLog.noteTapCoord(nx, ny, px, py, w, h, hold = true, refused = true, pkg = lastPkg())
        }
        GroundingLog.noteTapCoord(nx, ny, px, py, w, h, hold = true, refused = false, pkg = lastPkg())
        return longPress(px, py, durationMs).put("norm", "$nx,$ny")
    }

    fun swipeNorm(nx1: Int, ny1: Int, nx2: Int, ny2: Int, durationMs: Long): JSONObject {
        requireNorm(nx1, "x1"); requireNorm(ny1, "y1")
        requireNorm(nx2, "x2"); requireNorm(ny2, "y2")
        // Not a sample, but it moves everything on screen: without it the
        // offline pairing would read a post-scroll retry as a correction of a
        // pre-scroll miss, at a point that no longer means the same thing.
        GroundingLog.noteSwipe(nx1, ny1, nx2, ny2, durationMs, actor = "brain")
        return swipe(
            nx1.toPixel(screenW), ny1.toPixel(screenH),
            nx2.toPixel(screenW), ny2.toPixel(screenH),
            durationMs,
        )
    }

    // ---- stuck-loop guard (per-location counting) --------------------------
    //
    // A model that cannot tell whether a tap worked re-taps the same spot with
    // slightly different coordinates forever. Counting *consecutive* taps or
    // requiring "screen didn't change" both fail in the field: apps emit
    // content events constantly, and the real dead-loop interleaves menu
    // opens, wrong pages and backs so the screen is always changing. Only
    // PER-LOCATION counting catches it: every gesture within
    // [LOOP_RADIUS_PX] of a remembered spot bumps that spot's counter, no
    // matter what happens in between. Within [LOOP_WINDOW_MS], the 4th attempt
    // on the same spot is refused with an error telling the brain to stop,
    // re-observe, and ask the user for help. Swipes are exempt — scrolling a
    // long list legitimately repeats the same gesture.

    private val loopLock = Any()
    private val loopSpots = HashMap<String, Int>()   // "x,y" bucket -> count
    private var lastLoopSweep = 0L

    /** Set by [dumpUiTree]; read by [lastPkg]. Written and read off-thread. */
    @Volatile private var lastDumpPkg: String? = null

    /**
     * Packages we have actually read a usable element list from, and when.
     *
     * The one fact the device owns that the brain cannot work out for itself:
     * whether *this* app has ever been readable here. WeChat is the app that
     * forced it — its tree is perfectly obtainable, but the accessibility
     * connection sometimes goes stale and the same screen that dumped fine ten
     * minutes ago starts handing back nothing at all. No tool call repairs
     * that; a human toggling Andee off and on in 设置 → 无障碍 does, in about
     * three seconds. The brain cannot ask for that unless it knows the app was
     * supposed to work.
     *
     * Deliberately a record rather than a rule. Nothing here decides anything —
     * [reviveNote] turns it into one sentence of evidence and the brain judges,
     * which is why it is not keyed to `com.tencent.mm`: the same signature from
     * any app means the same thing, and hardcoding the package would have us
     * silent on the second app that does it.
     *
     * Unbounded, and that is fine: one `String`/`Long` per package a human has
     * opened on this tablet, which is tens, not thousands. Never persisted —
     * after a reboot the service is fresh, so old evidence would only argue for
     * a restart that already happened.
     */
    private val goodDumps = ConcurrentHashMap<String, Long>()

    /**
     * @param onRefuse run while still holding [loopLock], just before the
     *   refusal is thrown. The refusal is the strongest mis-tap label this
     *   system produces — the guard already knows a bucket has been hammered,
     *   and its scope (norm taps only, e-taps exempt) is exactly the set
     *   [GroundingLog] collects — so it must be written down rather than left
     *   to ride out on the exception.
     */
    private fun guardRepetition(x: Int, y: Int, onRefuse: () -> Unit = {}) {
        val now = System.currentTimeMillis()
        synchronized(loopLock) {
            if (now - lastLoopSweep > LOOP_WINDOW_MS) {
                loopSpots.clear()
                lastLoopSweep = now
            }
            val key = "${x / LOOP_RADIUS_PX},${y / LOOP_RADIUS_PX}"
            val n = (loopSpots[key] ?: 0) + 1
            loopSpots[key] = n
            if (n > LOOP_MAX_TRIES) {
                onRefuse()
                throw IllegalStateException(LOOP_HELP)
            }
        }
    }

    /**
     * Package behind the last dump — what the brain was actually looking at.
     * Preferred over `DeviceState.foregroundPkg`, which can read
     * `net.kuafuai.andee` whenever one of our own activities (scan, look, html)
     * is up.
     */
    private fun lastPkg(): String? = lastDumpPkg

    // ---- element-index tapping ----------------------------------------------
    //
    // Coordinate math by the model is the top accuracy risk: models
    // mis-convert pixel bounds against screen size exactly on the small,
    // tightly-packed targets where a few percent of error taps the wrong row.
    // The proven design (generic_bridge / phone.sh): the tree itself carries
    // system-computed e-numbers, and the model only ever says "e7". Zero
    // model math, zero scaling errors — coordinates come from the same source
    // of truth (getBoundsInScreen) that the gesture path converts with.
    //
    // STALENESS: the e-number alone is not enough to identify an element
    // after the page shifts (images load in, content settles) — e28 can
    // become a different view between the dump the brain read and the tap.
    // So the index keeps each entry's identity triple (pkg, viewId, bounds)
    // and tapByEid RE-LOCATES live: find a node NOW matching that triple; only
    // if none matches do we fall back to the remembered pixel center, and an
    // empty index still fails loudly. Phone.sh never had this problem because
    // its step loop dumps-and-acts in one breath; we let the brain think
    // between the two, so the tap has to catch up.
    //
    // e-taps are exempt from the stuck-loop guard: re-tapping a real element
    // is legitimate (toggles, list rows), and the guard exists to catch
    // coordinate GUESSING, not acting on confirmed elements.

    private class EidEntry(
        val px: IntArray,        // remembered pixel center — fallback only
        val pkg: String,         // owning app, from the node's root at dump time
        val viewId: String?,     // resource id if any
        val bounds: String,      // "l,t,r,b" in pixels at dump time
    )

    private val eidLock = Any()
    private val eidIndex = HashMap<Int, EidEntry>()

    /** Fresh dump in progress — see [tapByEid] for why taps must wait. */
    private val dumpLock = Object()

    fun tapByEid(eid: String): JSONObject {
        val entry = lookupEid(eid)
        // Re-locate live before touching anything: a match means the element
        // still exists where we found it; no match means either it scrolled
        // away or the pane changed — remembered center, best effort.
        val live = findLiveNode(entry)
        val x: Int; val y: Int
        if (live != null) {
            x = (live[0] + live[2]) / 2
            y = (live[1] + live[3]) / 2
        } else {
            x = entry.px[0]; y = entry.px[1]
        }
        GroundingLog.noteTapEid(eid, x, y, screenW, screenH, hold = false, relocated = live != null, pkg = lastPkg())
        return tap(x, y)
            .put("eid", eid)
            .put("px", "$x,$y")
            .put("relocated", live != null)
    }

    fun longPressByEid(eid: String, durationMs: Long): JSONObject {
        val entry = lookupEid(eid)
        val live = findLiveNode(entry)
        val x: Int; val y: Int
        if (live != null) {
            x = (live[0] + live[2]) / 2
            y = (live[1] + live[3]) / 2
        } else {
            x = entry.px[0]; y = entry.px[1]
        }
        GroundingLog.noteTapEid(eid, x, y, screenW, screenH, hold = true, relocated = live != null, pkg = lastPkg())
        return longPress(x, y, durationMs)
            .put("eid", eid)
            .put("px", "$x,$y")
            .put("relocated", live != null)
    }

    private fun lookupEid(eid: String): EidEntry {
        val key = eid.removePrefix("e").toIntOrNull()
            ?: throw IllegalArgumentException("bad element id '$eid' — expected e<number> from the element list")
        synchronized(eidLock) {
            return eidIndex[key]
                ?: throw IllegalArgumentException(
                    "unknown element '$eid' — the page has changed since your last get_screen_element. " +
                        "Call get_screen_element again and use an eid from the fresh list, do not guess coordinates"
                )
        }
    }

    /**
     * Try to find, RIGHT NOW, the node this entry described at dump time.
     * Matches on viewId when present (strongest), else on bounds-exact within
     * the entry's app, else null. Walking the whole tree per tap costs a few
     * ms on this hardware; correctness of "did I tap what I meant" is
     * worth far more.
     */
    private fun findLiveNode(entry: EidEntry): IntArray? {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return null
        try {
            if (root.packageName?.toString() != entry.pkg) return null
            // Strong path: resource id narrows it down, but WeChat reuses ids
            // across siblings (every bottom tab shares one id), so among the
            // matches pick the one CLOSEST to the remembered center — the
            // entry's own geometry, not list order, decides.
            if (!entry.viewId.isNullOrEmpty()) {
                val matches = runCatching {
                    root.findAccessibilityNodeInfosByViewId(entry.viewId)
                }.getOrNull().orEmpty()
                try {
                    var bestArr: IntArray? = null
                    var bestDist = Long.MAX_VALUE
                    for (m in matches) {
                        val r = Rect()
                        runCatching { m.getBoundsInScreen(r) }
                        if (r.width() > 0 && r.height() > 0) {
                            val cx = (r.left + r.right) / 2
                            val cy = (r.top + r.bottom) / 2
                            val dx = (cx - entry.px[0]).toLong()
                            val dy = (cy - entry.px[1]).toLong()
                            val dist = dx * dx + dy * dy
                            if (dist < bestDist) {
                                bestDist = dist
                                bestArr = intArrayOf(r.left, r.top, r.right, r.bottom)
                            }
                        }
                    }
                    return bestArr
                } finally {
                    matches.forEach { it.recycle() }
                }
            }
            // Weak path: walk for a node with the exact same bounds.
            return findNodeByBounds(root, entry.bounds)
        } finally {
            root.recycle()
        }
    }

    private fun findNodeByBounds(node: AccessibilityNodeInfo, bounds: String): IntArray? {
        val r = Rect()
        runCatching { node.getBoundsInScreen(r) }
        if (r.width() > 0 && r.height() > 0 && "${r.left},${r.top},${r.right},${r.bottom}" == bounds) {
            return intArrayOf(r.left, r.top, r.right, r.bottom)
        }
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                val hit = findNodeByBounds(child, bounds)
                if (hit != null) return hit
            } finally {
                child.recycle()
            }
        }
        return null
    }

    fun tap(x: Int, y: Int): JSONObject {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        // 150ms, not the classic 100ms: WeChat's Moments like/comment buttons
        // (and other touch-filtering surfaces) ignore injections shorter than
        // ~100ms — a 100ms stroke was landing as a no-op while the exact same
        // coordinates at 150ms pop the bubble every time (measured: `input tap`
        // at ~10ms fails, dispatchGesture at 100ms fails, a swipe held at one
        // point for 150ms succeeds). 150ms is still well below any
        // long-press threshold (400ms+), so nothing that was a tap becomes
        // a long-press.
        val stroke = GestureDescription.StrokeDescription(path, 0L, 150L)
        val before = screenFingerprint()
        val result = runGesture(GestureDescription.Builder().addStroke(stroke).build(), 2000L)
        marker?.mark(x, y, net.kuafuai.andee.ui.TapMarkerUi.Kind.TAP)
        result.put("x", x).put("y", y)
        attachEffectSnapshot(result, before)
        return result
    }

    /**
     * Cheap before/after of the screen around a gesture: pkg + window title +
     * a node-tree signature. The point is not what it says, it is that it
     * CHANGES (or doesn't) — a tap that did nothing leaves the fingerprint
     * identical, and the brain reading `screen_changed: false` knows to
     * re-aim instead of assuming success and building on sand.
     *
     * Collected here rather than left to the brain's next dump because the
     * transition window is exactly the moment a dump is least reliable: the
     * animation is still running, elements are mid-flight, and "dump again
     * immediately" reads a screen that does not exist yet. One fingerprint,
     * taken after a fixed beat, answers "did it land" without any of that.
     */
    private fun screenFingerprint(): JSONObject {
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
            ?: return JSONObject().put("pkg", "").put("sig", 0)
        return try {
            val f = FingerprintStats()
            collectFingerprint(root, f, 0)
            // Window COUNT is part of the fingerprint on purpose: WeChat's
            // Moments like/comment bar is a full-screen overlay window that
            // changes nothing in the active window's node tree — measured in
            // the field: the bar appeared, screen_changed said false, and the
            // only honest signal was the window list growing by one.
            val winCount = runCatching { service.windows.size }.getOrDefault(0)
            JSONObject()
                .put("pkg", root.packageName?.toString().orEmpty())
                .put("sig", f.sig() * 31 + winCount)
        } finally {
            root.recycle()
        }
    }

    private class FingerprintStats {
        var count = 0
        var hash = 1
        fun visit(node: AccessibilityNodeInfo) {
            count++
            hash = hash * 31 + node.childCount
            node.viewIdResourceName?.let { hash = hash * 31 + it.hashCode() }
            // TEXT is part of the fingerprint: structure alone cannot see a
            // translation replacing every string on the page, a comment
            // loading in, or a counter ticking — same nodes, same ids, new
            // words. Measured in the field (2026-09-20): dismissing the
            // browser's translate bar changed nothing structural, and
            // screen_changed said false while the screen had visibly changed.
            node.text?.let { hash = hash * 31 + it.hashCode() }
            node.contentDescription?.let { hash = hash * 31 + it.hashCode() }
        }
        fun sig(): Int = count * 31 + hash
    }

    private fun collectFingerprint(node: AccessibilityNodeInfo, f: FingerprintStats, depth: Int) {
        if (depth > 18 || f.count > 900) return  // cap: fingerprint, not a dump
        f.visit(node)
        for (i in 0 until node.childCount) {
            val c = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                collectFingerprint(c, f, depth + 1)
            } finally {
                c.recycle()
            }
        }
    }

    /**
     * The "did my tap land" half of the effect snapshot. Waits one beat for
     * the app to react (a transition that has not started yet reads as no
     * change), then fingerprints again and reports the delta:
     *   - `screen_changed` — the honest one-bit answer
     *   - `now_pkg` — where we landed, if we landed somewhere
     *   - on SUCCESS: `after_shot` — a full screenshot WITH the marker ring
     *     already drawn at this tap's position (the ring is deliberately
     *     never blanked — see TapMarkerUi). The brain sees exactly where its
     *     tap landed relative to everything on screen, which is how a hit
     *     confirms the aim AND how a near-miss ("ring is 40px left of the
     *     button") self-corrects.
     *   - on MISS: `miss_shot` — same idea, zoomed: the ring at the tapped
     *     spot, the neighbourhood magnified, aim the retry from measurement.
     *
     * Both carry `tap_mark: [x,y]` in 0-1000 so the ring's position is
     * machine-readable, not just visible.
     */
    private fun attachEffectSnapshot(result: JSONObject, before: JSONObject) {
        try {
            // 400ms caught the launcher still holding focus mid-launch (tap
            // camera → changed=true but now_pkg=launcher — measured on
            // device). 700ms lets the activity-change settle while staying
            // well under the brain's own next-step latency.
            Thread.sleep(700)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val after = screenFingerprint()
        val changed = before.optString("pkg") != after.optString("pkg") ||
            before.optInt("sig") != after.optInt("sig")
        result.put("screen_changed", changed)
        result.put("now_pkg", after.optString("pkg"))
        val tapX = result.optInt("x")
        val tapY = result.optInt("y")
        // JSONArray, element-wise: a bare IntArray goes through as one opaque
        // object and serialises to "[I@41d5a25" — a machine-readable field
        // that no machine can read.
        result.put("tap_mark", org.json.JSONArray().put(
            (tapX * 1000f / screenW).toInt()
        ).put(
            (tapY * 1000f / screenH).toInt()
        ))
        if (changed) {
            // Success still deserves the evidence: the ring-marked screen is
            // the cheapest possible confirmation, and it doubles as "this is
            // what I was aiming at" context for the next step.
            runCatching {
                if (tapX > 0 || tapY > 0) {
                    // The dispatcher's gesture-passthrough still has the
                    // marker hidden at this point (we run inside it) —
                    // reveal, then give the post a beat to land.
                    marker?.revealNow()
                    Thread.sleep(80)
                    result.put("after_shot", screenshot("tap_after"))
                }
            }
            // The NEW screen's element list, so the brain can chain its next
            // action (tap / type) WITHOUT a get_screen_element round trip it
            // would otherwise burn on "what did the screen become?". The
            // screenshot above already carries the pixels; this carries the
            // e-numbers. One tap response = full next-step context.
            runCatching {
                val tree = dumpOnce(false)
                if (tree.optInt("nodes_useful", 0) > 0) {
                    rebuildIndexAndList(tree)
                    val elements = tree.optString("elements")
                    if (elements.isNotEmpty()) {
                        result.put("elements", elements)
                            .put("elements_pkg", tree.optString("pkg"))
                            .put("elements_hint",
                                "This is the POST-TAP screen. Chain your next tap_screen_element / " +
                                "type_text directly from these e-numbers — do NOT re-dump first.")
                    }
                }
            }
        } else {
            result.put(
                "same_spot_hint",
                "the screen did NOT react to this tap. miss_shot shows where the ring " +
                    "landed — the tapped point, magnified with its surroundings. Aim the " +
                    "retry from that image, not from the previous full screenshot.",
            )
            runCatching {
                if (tapX > 0 || tapY > 0) {
                    // Region around the tap, in 0-1000 space: ~30% of the
                    // shorter axis each way, clamped by lookRegion itself.
                    // revealNow: same reason as the success path — the ring
                    // is the evidence, and passthrough has it hidden.
                    marker?.revealNow()
                    Thread.sleep(80)
                    val rx = (tapX * 1000f / screenW).toInt()
                    val ry = (tapY * 1000f / screenH).toInt()
                    val size = 300
                    val hUnits = (size * screenH.toFloat() / screenW).toInt().coerceAtLeast(1)
                    result.put("miss_shot", lookRegion(rx, ry, size, hUnits))
                }
            }
        }
    }

    fun tapById(id: String): JSONObject {
        val root = service.rootInActiveWindow ?: throw IllegalStateException("no active window")
        try {
            val matches = root.findAccessibilityNodeInfosByViewId(id).orEmpty()
            val target = matches.firstOrNull() ?: throw IllegalArgumentException("no node with id: $id")
            try {
                val performed = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return JSONObject().put("performed", performed).put("id", id).put("matches", matches.size)
            } finally {
                matches.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): JSONObject {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return runGesture(GestureDescription.Builder().addStroke(stroke).build(), durationMs + 2000).put("from", "$x1,$y1").put("to", "$x2,$y2").put("duration_ms", durationMs)
    }

    fun longPress(x: Int, y: Int, durationMs: Long): JSONObject {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val result = runGesture(GestureDescription.Builder().addStroke(stroke).build(), durationMs + 2000)
        marker?.mark(x, y, net.kuafuai.andee.ui.TapMarkerUi.Kind.HOLD)
        return result.put("x", x).put("y", y).put("duration_ms", durationMs)
    }

    fun longPressById(id: String): JSONObject {
        val root = service.rootInActiveWindow ?: throw IllegalStateException("no active window")
        try {
            val matches = root.findAccessibilityNodeInfosByViewId(id).orEmpty()
            val target = matches.firstOrNull() ?: throw IllegalArgumentException("no node with id: $id")
            try {
                val performed = target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                return JSONObject().put("performed", performed).put("id", id)
            } finally {
                matches.forEach { it.recycle() }
            }
        } finally {
            root.recycle()
        }
    }

    fun typeText(text: String, id: String?): JSONObject = typeText(text, id, secret = false)

    /**
     * [secret] = this text is a vault password. Everything works the same
     * except that the value never appears in the result — see
     * [typeViaAdbKeyboard].
     */
    fun typeText(text: String, id: String?, secret: Boolean): JSONObject {
        // ── 输入通道(2026-09-20 定版):ADBKeyboard IME 广播单通道 ──
        //
        // 实测依据(当天 bolt.new 两设备全量对比):
        //   - SET_TEXT/剪贴板PASTE:Radix 表单串字段,CodeMirror 全灭,
        //     MIUI 上 setPrimaryClip 还会静默丢弃(粘进旧内容);
        //   - ADBKeyboard(IME, commitText 正统通道):bolt 注册表单
        //     三字段全对,连 CodeMirror 都进。
        // 设备 ADBKeyboard 为常驻默认 IME。**这句原来写的是「产品决策:平板/
        // 手机是 Andee 本体,无真人键盘场景」—— 那个前提已经不成立**:卡片上
        // 有了 ⌨ 文本输入,真人要打字了。现在由 ui/ImeSwitch 在用户打字期间
        // 临时换成真键盘、发完换回来;这里唯一的要求没变 —— 轮到大脑打字时
        // ADBKeyboard 必须是当前输入法,否则 ADB_INPUT_TEXT 发出去没人接。
        // 通道:检查焦点 → ADB_CLEAR_TEXT 清残留
        // → ADB_INPUT_TEXT → 双路读回验证。失败如实报错,无多通道瀑布。
        val root = service.rootInActiveWindow ?: throw IllegalStateException("no active window")
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } finally {
            root.recycle()
        }
        if (focused == null) {
            throw IllegalStateException("no input focus (tap into an input field first)")
        }
        runCatching { if (focused !== root) focused.recycle() }
        return typeViaAdbKeyboard(text, secret)
    }

    /**
     * 通过 ADBKeyboard 的 IME 广播输入 [text]。
     *
     * ADBKeyboard 是激活的输入法,收到 ADB_INPUT_TEXT 后走
     * InputConnection.commitText()——与真人敲键盘同一条路径,目标
     * 应用无法拒绝(拒绝即拒绝一切键盘输入)。
     *
     * 语义:广播是【追加】,故先发 ADB_CLEAR_TEXT 清残留(之前失败
     * 尝试/自动填充),再输入,最后双路读回(焦点框优先,全树兜底
     * ——WebView 焦点节点藏文本时树里能找到)。自绘编辑器
     * (CodeMirror)两路都可能读不到,那是 a11y 假阴性,错误信息里
     * 提示大脑以截图为准。
     *
     * action 【不带包名前缀】。ADBKeyboard 在 AdbIME.onCreate 里
     * registerReceiver 的 filter 就是裸字符串 ADB_INPUT_TEXT(见设备
     * `dumpsys activity broadcasts`),写成
     * com.android.adbkeyboard.ADB_INPUT_TEXT 匹配不上任何 filter,加上
     * setPackage 钉死收件人,广播就发出去没人接——静默失败,和被系统
     * 拦截长得一模一样。这正是「MIUI 拦 app 跨进程广播」那个结论的
     * 由来:电脑侧 `am broadcast -a ADB_INPUT_TEXT` 用的是对的名字所以
     * 通,设备侧用错名字所以不通,看上去就像身份问题。
     *
     * [secret] 为真时(保险箱密码),读回照常做——内部比对,结果照实
     * 报——但**成功不回声、失败不带片段**。回声本来是给大脑确认输入
     * 成功用的,而密码回声等于把明文顺着 WebSocket 送回远端模型,
     * 「只填不给」这条边界就是在这里成立或失效的。
     */
    private fun typeViaAdbKeyboard(text: String, secret: Boolean = false): JSONObject {
        // ── 键盘是借的,不是占的 ──
        //
        // 设备的输入法平时是**用户自己的**那个(它能在任何 App 里打字),
        // ADBKeyboard 只在这一次调用期间被借过来。所以:先借 → 打字 → 立刻还。
        // 想要这条成立,`text` 的广播必须落在**已经 bind 上**的 ADBKeyboard 上,
        // 而"选中"和"绑定"不是同一件事 —— 这就是 ImeSwitch.lendToBrain 里
        // 那 350ms 的由来(实测 bind 上界 ~400ms,框架不对外暴露这个状态)。
        //
        // 借不到就是**响亮的失败**,不能假装打了字:广播发出去没人接,读回
        // 只会得到"字段还是空的",而那句错误信息会去怪目标 App 藏了文本。
        val giveBackTo = net.kuafuai.andee.ui.ImeSwitch.lendToBrain(service)
        try {
            if (net.kuafuai.andee.ui.ImeSwitch.current(service) != net.kuafuai.andee.ui.ImeSwitch.BRAIN_IME) {
                throw IllegalStateException(
                    "cannot make ADBKeyboard the active IME (it is " +
                        "'${net.kuafuai.andee.ui.ImeSwitch.current(service)}') — " +
                        "the type broadcast would be delivered to nobody. " +
                        "Needs WRITE_SECURE_SETTINGS granted once by adb."
                )
            }
            return typeWithKeyboardUp(text, secret)
        } finally {
            net.kuafuai.andee.ui.ImeSwitch.giveBack(service, giveBackTo)
        }
    }

    // ── On the keyboard the brain's typing raises, and why nothing here puts it down ──
    //
    // Handing the keyboard back to the user makes a real IME active, and a real
    // IME honours the show request that the brain's own tap created: after
    // `screen.type`, the user's keyboard is on screen over the app being driven,
    // and that app's layout is resized under it. Measured, twice:
    //
    //   * `hideSoftInputFromWindow(null, 0)` — the only handle a non-owner of
    //     the field's window has — is **ignored**. `mInputShown` stayed true.
    //   * `performGlobalAction(GLOBAL_ACTION_BACK)` is **not** consumed by the
    //     IME the way an injected key event is: `adb shell input keyevent
    //     KEYCODE_BACK` closed the keyboard and left the page alone, while the
    //     service-level action dismissed *the page* and left the keyboard.
    //
    // So this is left alone deliberately. It is not a defect in the switch: with
    // the person's keyboard as the active IME, a focused field raising it is
    // what that means. It clears itself as soon as the brain touches something
    // that takes focus. Both failed attempts are recorded because the second one
    // is the tempting one, and it navigates somebody else's app backwards.

    /** The broadcast-and-verify half. Only ever called with the brain's IME up. */
    private fun typeWithKeyboardUp(text: String, secret: Boolean): JSONObject {
        runCatching {
            service.sendBroadcast(
                android.content.Intent(ADB_CLEAR_TEXT).setPackage(ADB_KEYBOARD_PKG)
            )
            Thread.sleep(150)
        }
        service.sendBroadcast(
            android.content.Intent(ADB_INPUT_TEXT)
                .putExtra("msg", text)
                .setPackage(ADB_KEYBOARD_PKG)
        )
        // 轮询而不是睡一个固定值:广播是异步的,commitText 之后 WebView
        // 还要把新值回抛给无障碍树。实测 400ms 大多数时候够,偶尔不够,
        // 而读早了报出来的是「没输进去」——一条会让大脑重发、于是真的
        // 输两遍的假错误。
        var afterFocused = ""
        var afterTree = ""
        val deadline = SystemClock.uptimeMillis() + READBACK_BUDGET_MS
        do {
            Thread.sleep(READBACK_POLL_MS)
            afterFocused = readFocusedText()
            afterTree = if (afterFocused == text) "" else readTextFromTree(text)
            if (afterFocused == text || afterTree == text) break
        } while (SystemClock.uptimeMillis() < deadline)
        val ok = afterFocused == text || afterTree == text
        return if (ok) {
            JSONObject()
                .put("performed", true)
                .put("via", "adb_keyboard")
                .put("len", text.length)
                .apply {
                    if (!secret) put("text", if (afterFocused == text) afterFocused else afterTree)
                }
        } else if (secret) {
            JSONObject()
                .put("performed", false)
                .put("via", "adb_keyboard")
                .put("len", text.length)
                .put("error",
                    "broadcast sent but the field does not hold the expected value " +
                    "(read back ${afterFocused.length} chars from the focused node, " +
                    "${afterTree.length} from the tree) — ADBKeyboard may not be the " +
                    "active IME, or the target hides its text; verify with a screenshot")
        } else {
            JSONObject()
                .put("performed", false)
                .put("via", "adb_keyboard")
                .put("len", text.length)
                .put("error",
                    "broadcast sent but field text != expected (focused: '${afterFocused.take(30)}', tree: '${afterTree.take(30)}') — " +
                    "ADBKeyboard may not be the active IME, or the target hides its text; verify with a screenshot")
        }
    }
    /** Focused input's text, or "" — never throws, never leaks nodes. */
    private fun readFocusedText(): String = runCatching {
        val r = service.rootInActiveWindow ?: return@runCatching ""
        val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        try { f?.text?.toString().orEmpty() } finally {
            if (f != null && f !== r) f.recycle()
            r.recycle()
        }
    }.getOrDefault("")
    /**
     * Deep-scan the active window's tree for a node whose text equals
     * [want] — the fallback read path for WebViews that keep the focused
     * node's text hidden from accessibility. Returns "" when absent.
     */
    private fun readTextFromTree(want: String): String {
        if (want.isEmpty()) return ""
        val r = service.rootInActiveWindow ?: return ""
        try {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(r)
            while (stack.isNotEmpty()) {
                val n = stack.removeFirst()
                val t = n.text?.toString().orEmpty()
                if (t == want) return t
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            }
        } finally {
            r.recycle()
        }
        return ""
    }

    /**
     * Fire the current input field's IME action (Enter / Send / Search /
     * Next / Done — whichever the field was set up for). Prefer this over
     * synthesizing a keyboard Enter, because IME action is what the app is
     * actually listening for.
     *
     * When `id` is null we use the currently focused input.
     */
    fun submitInput(id: String?): JSONObject {
        val root = service.rootInActiveWindow ?: throw IllegalStateException("no active window")
        try {
            val target: AccessibilityNodeInfo = if (id != null) {
                root.findAccessibilityNodeInfosByViewId(id).orEmpty().firstOrNull()
                    ?: throw IllegalArgumentException("no node with id: $id")
            } else {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: throw IllegalStateException("no input focus (tap into an input field first)")
            }
            try {
                val performed = target.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                )
                return JSONObject()
                    .put("performed", performed)
                    .put("target_id", target.viewIdResourceName)
            } finally {
                if (target !== root) target.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * @param actor who pressed it. The same method serves the brain's
     *   `system_action` and the user's own top-bar back/home button, and from
     *   in here they are indistinguishable — but a navigation the user chose
     *   invalidates the episode around it, so [GroundingLog] has to be told
     *   which it was. Defaulted to "brain" because the dispatcher, the only
     *   caller that can't be the user, is also the one that never passes it.
     */
    fun globalAction(action: String, actor: String = "brain"): JSONObject {
        val code = when (action.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> throw IllegalArgumentException("unknown global action: $action")
        }
        GroundingLog.noteNav(action.lowercase(), actor)
        val ok = service.performGlobalAction(code)
        return JSONObject().put("performed", ok).put("action", action)
    }

    /**
     * @param reason why this capture happened — "tool" (take_screenshot),
     *   "with_shot", or "auto_empty" (the dump had no list to give). It is the
     *   field the offline pairing filters on: only the self-drawn panes behind
     *   "auto_empty" carry coordinate taps worth learning from.
     */
    fun screenshot(reason: String = "tool"): JSONObject {
        // Settle before capture: images loading and lists settling MOVE the
        // things a screenshot is about to be used to aim at — a shot taken
        // mid-settle gives the brain targets that have already shifted by
        // the time it taps (the "aimed at A, screen became B" miss). Same
        // stability criterion as dumpUiTree: two consecutive tree signatures
        // agree. Cheap when still (one extra fingerprint pass), capped when
        // genuinely animated — the shot is taken anyway and `still_moving`
        // says so, rather than pretending.
        runCatching {
            var prev = screenFingerprint().optInt("sig", -1)
            var tries = 0
            while (tries < STABLE_MAX_TRIES) {
                Thread.sleep(STABLE_POLL_MS)
                val sig = screenFingerprint().optInt("sig", -2)
                if (sig == prev) return@runCatching
                prev = sig
                tries++
            }
        }
        val latch = CountDownLatch(1)
        var pngBytes: ByteArray? = null
        var errCode = 0
        var w = 0;
        var h = 0
        val executor = Executors.newSingleThreadExecutor()
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = screenshot.hardwareBuffer
                            val cs = screenshot.colorSpace
                            val bmp = Bitmap.wrapHardwareBuffer(hb, cs)
                                ?: run { errCode = -1; return }
                            w = bmp.width; h = bmp.height
                            // Downscale to max side 1280 before PNG-encoding
                            // (generic_bridge finding): the raw 1600x2560 shot
                            // is ~765KB base64 and models gain nothing from
                            // the extra pixels, while every vision token is
                            // billed. Safe for coordinates because the whole
                            // protocol is 0-1000 relative — scale-invariant.
                            val scale = SHOT_MAX_SIDE.toFloat() / maxOf(w, h)
                            val outBmp = if (scale < 1f) {
                                Bitmap.createScaledBitmap(
                                    bmp,
                                    (w * scale).toInt().coerceAtLeast(1),
                                    (h * scale).toInt().coerceAtLeast(1),
                                    true,
                                )
                            } else bmp
                            val bos = ByteArrayOutputStream()
                            outBmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                            pngBytes = bos.toByteArray()
                            if (outBmp !== bmp) outBmp.recycle()
                            hb.close()
                        } catch (t: Throwable) {
                            errCode = -2
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        errCode = errorCode
                        latch.countDown()
                    }
                },
            )
            if (!latch.await(5, TimeUnit.SECONDS)) throw IllegalStateException("screenshot timeout")
            val bytes = pngBytes
                ?: throw IllegalStateException("screenshot failed with code $errCode")
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            // Hooked here, not in onSuccess: the PNG is already encoded at this
            // point, so logging costs no second capture and no second compress —
            // and the callback runs on `executor`, which is shutdown two lines
            // below. Same downscale arithmetic as above, since `w`/`h` are
            // deliberately reported as the SOURCE dimensions.
            val scale = SHOT_MAX_SIDE.toFloat() / maxOf(w, h)
            val outW = if (scale < 1f) (w * scale).toInt().coerceAtLeast(1) else w
            val outH = if (scale < 1f) (h * scale).toInt().coerceAtLeast(1) else h
            val shotId = GroundingLog.noteShot(
                png = bytes,
                srcW = w, srcH = h, outW = outW, outH = outH,
                reason = reason,
                pkg = lastPkg(),
                markerPx = marker?.lastMarkPx(),
                markerAgeMs = marker?.lastMarkAgeMs(),
            )
            return JSONObject()
                .put("png_base64", b64)
                .put("width", w)
                .put("height", h)
                .put("bytes", bytes.size)
                // Lets the response the brain saw be matched to the file on
                // disk. Absent when the log is off.
                .also { if (shotId != null) it.put("shot_id", shotId) }
                // Piggyback the element list. The brain's known failure mode
                // (measured, 2026-09-20): it takes a screenshot, measures a
                // target in pixels off the image, and taps blind — even on
                // panes where the tree was available one tool call earlier,
                // because the image arrived WITHOUT the list and "out of
                // sight, out of mind". Attaching the same system-computed
                // 0-1000 centers the tree tool returns makes every screenshot
                // self-sufficient: aim by eid, not by eyeballing pixels.
                // Deliberately not a parameter — there is no case where a
                // screenshot is better WITHOUT the list; on self-drawn panes
                // it comes back empty and costs nothing.
                .also {
                    runCatching {
                        val tree = dumpOnce(false)
                        if (tree.optInt("nodes_useful", 0) > 0) {
                            rebuildIndexAndList(tree)
                            val elements = tree.optString("elements")
                            if (elements.isNotEmpty()) {
                                it.put("elements", elements)
                                    .put("nodes_useful", tree.optInt("nodes_useful"))
                                    .put("aim_hint",
                                        "Prefer tap_id(e) / type(id) with these elements — " +
                                        "system-computed, always accurate. Pixel-aiming " +
                                        "from the image is the fallback, not the default.")
                            }
                        }
                    }
                }
        } finally {
            executor.shutdown()
        }
    }

    /**
     * Zoomed look at a region — the fix for "small target, big guess".
     *
     * A full screenshot compresses a `...` bubble to ~25px, and a vision
     * model's ±10px read on that is ±32px on the real screen — half the
     * button's hit area, so taps land or miss at coin-flip odds and every
     * retry re-guesses from the same un-zoomed picture (measured in the
     * field: three aims at one button, y=700/547/709 — 410px of spread).
     * This returns the region magnified, so the same ±10px read becomes
     * ±3px on screen: the brain sees the button clearly, aims once, done.
     *
     * Coordinates are the same 0-1000 relative space as everything else;
     * `w`/`h` are the region's size in that space. The returned image keeps
     * its true aspect, is scaled so the LONGER side hits
     * [SHOT_MAX_SIDE] (same budget as a full shot), and the response
     * restates the region's screen bounds plus a `scale` factor — the brain
     * reads a point off the zoomed image, divides by scale, adds the
     * region's origin, and has a screen-true coordinate without the device
     * doing math for it.
     */
    fun lookRegion(nx: Int, ny: Int, w: Int, h: Int): JSONObject {
        requireNorm(nx, "x"); requireNorm(ny, "y")
        if (w <= 0 || h <= 0 || w > 1000 || h > 1000) {
            throw IllegalArgumentException("w and h must be 1..1000 (relative), got w=$w h=$h")
        }
        val left = (nx.toPixel(screenW) - w.toPixel(screenW) / 2).coerceIn(0, screenW - 1)
        val top = (ny.toPixel(screenH) - h.toPixel(screenH) / 2).coerceIn(0, screenH - 1)
        val right = (left + w.toPixel(screenW)).coerceAtMost(screenW)
        val bottom = (top + h.toPixel(screenH)).coerceAtMost(screenH)
        if (right - left < 8 || bottom - top < 8) {
            throw IllegalArgumentException("region too small after clamping to screen")
        }
        val full = screenshot("look_region")
        val bytes = Base64.decode(full.getString("png_base64"), Base64.NO_WRAP)
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("could not decode screenshot")
        // The DECODED bitmap's own size, not the response's "width"/"height":
        // those report the SOURCE screen dimensions while the PNG itself is
        // downscaled to max 1280 (first field report bug: x+width overflowed
        // the real bitmap and createBitmap threw).
        val shotW = bmp.width
        val shotH = bmp.height
        val sx = shotW.toFloat() / screenW
        val sy = shotH.toFloat() / screenH
        val rLeft = (left * sx).toInt().coerceIn(0, shotW - 2)
        val rTop = (top * sy).toInt().coerceIn(0, shotH - 2)
        val rRight = (right * sx).toInt().coerceAtLeast(rLeft + 4).coerceAtMost(shotW)
        val rBottom = (bottom * sy).toInt().coerceAtLeast(rTop + 4).coerceAtMost(shotH)
        // Magnify the crop so its longer side hits the same budget a full
        // screenshot gets — the entire point is more pixels-per-target.
        val cw = rRight - rLeft
        val ch = rBottom - rTop
        val mag = (SHOT_MAX_SIDE.toFloat() / maxOf(cw, ch)).coerceAtLeast(1f)
        val outW = (cw * mag).toInt().coerceAtLeast(1)
        val outH = (ch * mag).toInt().coerceAtLeast(1)
        try {
            val region = Bitmap.createBitmap(bmp, rLeft, rTop, cw, ch)
            val scaled = Bitmap.createScaledBitmap(region, outW, outH, true)
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, bos)
            val outBytes = bos.toByteArray()
            region.recycle()
            if (scaled !== region) scaled.recycle()
            // Region bounds in the 0-1000 space the brain speaks.
            val relLeft = (left * 1000f / screenW).toInt()
            val relTop = (top * 1000f / screenH).toInt()
            val relRight = (right * 1000f / screenW).toInt()
            val relBottom = (bottom * 1000f / screenH).toInt()
            return JSONObject()
                .put("png_base64", Base64.encodeToString(outBytes, Base64.NO_WRAP))
                .put("width", outW)
                .put("height", outH)
                .put("bytes", outBytes.size)
                .put(
                    "region",
                    JSONObject()
                        .put("left", relLeft).put("top", relTop)
                        .put("right", relRight).put("bottom", relBottom),
                )
                // shot px per screen-1000 unit: a point read off the image at
                // (px, py) maps to screen-relative
                // (relLeft + px / scale_x, relTop + py / scale_y).
                .put("scale_x", outW.toFloat() / (relRight - relLeft).coerceAtLeast(1))
                .put("scale_y", outH.toFloat() / (relBottom - relTop).coerceAtLeast(1))
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Dump the active window's UI tree, with enough diagnostics that an empty
     * tree can say *why* it is empty.
     *
     * Three different failures look the same from the brain's side ("no nodes"),
     * and only one of them is fixable inside this app:
     *
     *   - `no active window` — the root is null; usually a transition, sometimes
     *     a window the app refuses to hand over.
     *   - `nodes_total` high, `nodes_useful` 0 — we can see the hierarchy but
     *     it carries no id/text/desc. Almost always an app that draws content
     *     itself (WebView page, Flutter/X5 pane, custom-rendered list, game).
     *     No flag fixes this; you have to look at pixels.
     *   - `nodes_total` low — the tree really is thin, or the content lives in
     *     a *different* window, which is why we go looking for one below.
     *
     * Anything an app draws into its own render surface (see [isRenderSurface])
     * is invisible to accessibility by construction — that is a property of the
     * Android view system, not of this service — so the answer there is never
     * "try harder", it's `take_screenshot` and tap by coordinate.
     */
    fun dumpUiTree(
        verbose: Boolean = false,
        withShot: Boolean = ToolSchemas.UI_TREE_WITH_SHOT_DEFAULT,
        capture: (String) -> JSONObject = { screenshot(it) },
    ): JSONObject {
        val ts = System.currentTimeMillis()
        var out = dumpOnce(verbose)
        var waited = 0L
        while (!verbose && out.optInt("nodes_useful", 0) == 0 && waited < SETTLE_BUDGET_MS) {
            val age = System.currentTimeMillis() - lastContentEventMs.get()
            if (lastContentEventMs.get() != 0L && age >= QUIET_WINDOW_MS) break
            try { Thread.sleep(SETTLE_POLL_MS) } catch (_: InterruptedException) { break }
            waited += SETTLE_POLL_MS
            out = dumpOnce(verbose)
        }
        // Stability pass — the half the old loop never covered: "has content"
        // is not "has stopped moving". A screen mid-animation (list settling,
        // pager sliding, splash fading) dumps real elements at positions that
        // are already stale by the time the brain acts on them. Re-dump until
        // two consecutive passes agree on the tree signature, capped tight:
        // this runs on EVERY dump, so the cost when the screen was already
        // still is exactly one extra pass — and that pass is also what
        // guarantees the eid index the brain is about to read was built from
        // the settled geometry, not a mid-flight one.
        if (!verbose && out.optInt("nodes_useful", 0) > 0) {
            var prevSig = treeSignature(out)
            var stableTries = 0
            while (waited < SETTLE_BUDGET_MS && stableTries < STABLE_MAX_TRIES) {
                try { Thread.sleep(STABLE_POLL_MS) } catch (_: InterruptedException) { break }
                waited += STABLE_POLL_MS
                val again = dumpOnce(verbose)
                val sig = treeSignature(again)
                if (sig == prevSig) {
                    out = again
                    break
                }
                prevSig = sig
                out = again
                stableTries++
            }
            if (stableTries >= STABLE_MAX_TRIES) {
                out.put("still_moving", true)
            }
        }
        if (!verbose && out.optInt("nodes_useful", 0) == 0 && waited >= SETTLE_BUDGET_MS) {
            out.put("settling", true).put("settle_waited_ms", waited)
        }
        // Flat element list + eid index — the phone.sh contract: one line per
        // element, system-computed 0-1000 centers, model never does math.
        if (!verbose) rebuildIndexAndList(out)
        // Ahead of the shot block on purpose: `screenshot()` stamps its record
        // with [lastPkg], and the picture belongs to the screen we just read,
        // not to the one before it.
        out.optString("pkg").takeIf { it.isNotEmpty() }?.let { lastDumpPkg = it }
        // Evidence for [reviveNote], recorded only on a dump that actually
        // worked. Our own package is excluded: our Activities read fine and
        // would be recorded as known-good, which is true and useless — nobody
        // is going to ask the user to restart the service so we can read
        // ourselves.
        if (!verbose && out.optInt("nodes_useful", 0) > 0) {
            out.optString("pkg")
                .takeIf { it.isNotEmpty() && it != service.packageName }
                ?.let { goodDumps[it] = ts }
        }
        if (!verbose) {
            GroundingLog.noteTree(
                pkg = out.optString("pkg").takeIf { it.isNotEmpty() },
                total = out.optInt("nodes_total", 0),
                useful = out.optInt("nodes_useful", 0),
                // Lines, not characters: "did the brain have anything to tap"
                // is the only question the offline filter asks of this.
                elements = out.optString("elements").let {
                    if (it.isEmpty()) 0 else it.count { c -> c == '\n' } + 1
                },
                surface = out.optString("surface").takeIf { it.isNotEmpty() },
            )
        }
        // Tell the brain which way the screen is facing: a mid-task rotation
        // (keyboard attached, user flips the tablet) invalidates every
        // coordinate and geometric prior it is holding — the least we owe it
        // is the fact. Coordinates in our 0-1000 space are still correct
        // (they are relative to the CURRENT screen), but "the comment bubble
        // sits left of the button" style priors are portrait-only knowledge.
        runCatching {
            val rot = service.resources.configuration.orientation
            out.put("orientation", if (rot == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait")
        }
        // A picture, but only when it is worth something. With a populated
        // element list it is not: the list already says what is tappable and
        // where, in the same 0-1000 space, for a fraction of the tokens — so
        // `with_shot` defaults to false and the brain has to ask.
        //
        // The exception is the case the list cannot serve. An empty list means
        // this pane draws its own pixels, and there the image is not a bonus
        // next to the list, it IS the observation — the brain would otherwise
        // have to spend a second round trip on take_screenshot to learn
        // anything at all about the screen it is standing on. `shot_auto`
        // marks the ones it did not ask for.
        //
        // Also empty in effect: a dump that came back describing US. Our
        // overlays are hidden from accessibility outright and the window sweep
        // refuses our package, so this should no longer be reachable through
        // those two routes — but `rootInActiveWindow` can still be one of our
        // own Activities (Html/Scan/Look), which are ordinary app windows and
        // legitimately have a tree. It is our tree, not the brain's business,
        // so treat it as no tree and give it the picture instead.
        //
        // Ordered AFTER the index build so the image and the e-numbers
        // describe the same screen state. `capture` rather than `screenshot()`
        // so the caller can blank our overlay first — see CommandDispatcher.
        val treeEmpty = out.optInt("nodes_useful", 0) == 0 ||
            out.optString("pkg") == service.packageName
        if (!verbose && (withShot || treeEmpty)) {
            val reason = if (withShot) "with_shot" else "auto_empty"
            runCatching { out.put("shot", capture(reason)) }
                .onFailure { out.put("shot_error", it.message ?: "capture failed") }
            if (!withShot) out.put("shot_auto", true)
        }
        out.put("ts", ts)
        annotate(out, verbose)
        return out
    }

    /**
     * Signature of a dump's content for the stability pass: which nodes
     * exist, their ids and their bounds. Bounds are the part that matters —
     * a list mid-settle keeps the same nodes while their coordinates slide,
     * and the brain taps coordinates. Text is deliberately included: content
     * streaming in (images loading, counters ticking) is also "not settled".
     */
    private fun treeSignature(out: JSONObject): String {
        val sb = StringBuilder(256)
        val nodes = ArrayList<Pair<Int, JSONObject>>()
        collectNodes(out, nodes, 0)
        for ((_, n) in nodes) {
            sb.append(n.optString("id")).append('|')
                .append(n.optString("bounds")).append('|')
                .append(n.optString("text")).append('\n')
        }
        return sb.toString()
    }

    private fun dumpOnce(verbose: Boolean): JSONObject {
        val active = service.rootInActiveWindow
        val out = if (active != null) {
            try {
                val stats = Stats()
                val body = buildNode(active, verbose, stats) ?: JSONObject().put("empty", true)
                withStats(body, active.packageName?.toString() ?: "", stats)
            } finally {
                active.recycle()
            }
        } else {
            JSONObject().put("empty", true).put("error", "no active window")
        }

        // Nothing usable and we may simply be looking at the wrong window: a
        // lot of Chinese apps host their real content in a second application
        // window (WeChat's mini-program / web-view pannes do exactly this).
        // Also entered when the "active" window turned out to be a status-bar
        // or other system shell during an app transition.
        if (!verbose && out.optInt("nodes_useful", 0) == 0) tryOtherWindows(out)
        // A status bar (SystemUI) can be the active window mid-transition and
        // hand us ~27 "useful" status icons — real nodes, wrong screen. The
        // user-facing app is always a better source when it has ANY content.
        // Only run this correction when we did NOT just come from
        // tryOtherWindows with a genuinely better window.
        if (!verbose && out.optString("pkg") == "com.android.systemui") tryOtherWindows(out)

        return out
    }

    /**
     * Fallback for the case above: walk every window we can see and keep the
     * best one. Only ever entered when the active window gave us nothing, so the
     * extra work is rare.
     *
     * Some apps can expose a transient or empty application window while another
     * window owns the visible content. We therefore inspect all accessible
     * windows before concluding that the current pane has no usable nodes.
     *
     * Whatever we reject, we report: a per-window summary goes out when nothing
     * was found, so the next person debugging this (or the model) can see which
     * window existed and which one had content, instead of a bare `{}`.
     */
    private fun tryOtherWindows(out: JSONObject) {
        val windows = runCatching { service.windows }.getOrNull()
        if (windows == null) {
            out.put("windows_seen", 0)
            return
        }
        // Whether the currently-chosen window belongs to a user app (as
        // opposed to SystemUI). Set BEFORE the loop so the SystemUI-rejection
        // rule below can reference it.
        val outPkgIsApp = out.optString("pkg", "") !in setOf("", "com.android.systemui")
        val detail = JSONArray()
        var best: JSONObject? = null
        var bestUseful = out.optInt("nodes_useful", 0)
        try {
            for (w in windows) {
                val stats = Stats()
                val root = runCatching { w.root }.getOrNull()
                // Read everything we need off the node *before* recycling it.
                val pkg = if (root != null) runCatching { root.packageName?.toString() }.getOrNull().orEmpty() else ""
                val body = if (root != null) {
                    try {
                        buildNode(root, false, stats)
                    } catch (t: Throwable) {
                        null
                    } finally {
                        root.recycle()
                    }
                } else {
                    null
                }
                detail.put(
                    JSONObject()
                        .put("title", w.title?.toString() ?: "")
                        .put("type", windowTypeName(w.type))
                        .put("active", w.isActive)
                        .put("focused", w.isFocused)
                        .put("layer", w.layer)
                        .put("root", root != null)
                        .put("nodes_total", stats.total)
                        .put("nodes_useful", stats.useful),
                )
                // Accept ANY window type that hands us useful nodes, not just
                // TYPE_APPLICATION. WeChat hosts Moments' real content in a
                // system-type window (layer 1) while the "application" window
                // above it is an empty shell — filtering by type meant
                // throwing away the only tree on the screen, which is exactly
                // the "Moments taps are inaccurate" bug: the brain was forced
                // onto screenshot-guessing while a perfectly good element list
                // sat in a window we refused to read.
                //
                // Ranking: a user-app window (application type, or Moments'
                // system shell owned by the same non-SystemUI package) with
                // strictly more useful nodes beats anything SystemUI — the
                // status bar never stops "having content" (clock, icons), so
                // without this rule it wins every app transition.
                val isSystemUi = pkg == "com.android.systemui"
                // Never us. [net.kuafuai.andee.ui.hideFromAccessibility] should
                // already have left our overlays with nothing to find, but this
                // sweep is the one place where being wrong is invisible: it
                // runs only when the real pane has no tree, which is exactly
                // when our own toolbar is the sole thing on screen with
                // `useful > 0`, so it would win and the brain would be handed
                // an element list of our buttons. Two independent guards for
                // the same fault because the failure is silent and the cost is
                // a comparison. Our own Activities (Html/Scan/Look) are real
                // app windows and are NOT overlays, so they are caught here and
                // only here.
                if (pkg == service.packageName) continue
                val beatsBest = stats.useful > bestUseful &&
                    (!isSystemUi || !outPkgIsApp)
                if (body != null && root != null && beatsBest) {
                    bestUseful = stats.useful
                    best = withStats(body, pkg, stats, source = "other_window(${windowTypeName(w.type)})")
                }
            }
        } finally {
            windows.forEach { runCatching { it.recycle() } }
        }
        val b = best
        if (b != null) {
            for (k in ArrayList(out.keys().asSequence().toList())) out.remove(k)
            for (k in b.keys()) out.put(k, b[k])
            // Which window this came from matters: tapping a node found in a
            // background window still works (bounds are screen coordinates),
            // but the brain should know it was not the focused one.
            out.put("windows_seen", windows.size)
            return
        }
        out.put("windows_seen", windows.size)
        out.put("windows_detail", detail)
    }

    private fun windowTypeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "a11y_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_divider"
        else -> "other($type)"
    }

    /**
     * Turn the pruned nested tree into (a) a fresh eid index and (b) the
     * element outline the brain actually reads — one line per element, in
     * document order, indented by depth:
     *
     *   e17|LinearLayout||[500,570]
     *     e18*|ImageView|#avatar|[62,523]
     *     e19*|TextView|好友3|[181,509]
     *     e24*|ImageView|#comment_btn|[950,621]
     *
     * `*` marks clickable. The bracket pair is the element's center in
     * 0-1000 RELATIVE coordinates — same space tap_screen speaks — computed
     * by us from getBoundsInScreen, never by the model. Even when the model
     * ignores e-numbers and taps by coordinate, it copies a system-computed
     * value instead of eyeballing the screenshot.
     *
     * ## Why it is indented, and why that replaced useful-first sorting
     *
     * This list used to be sorted text-first, then clickable, then
     * top-to-bottom, so that a context-limit truncation ate the junk tail.
     * On any feed that ordering is fatal. A 朋友圈 page is five posts of
     * {avatar, name, body, photos, time, ⋯}; sorting scattered each post
     * across the list and stacked twenty label-less `ImageView` lines at the
     * bottom, identical to one another. "Like 好友3's post" was then not
     * merely hard but *unanswerable* from the list — the comment button that
     * belongs to that post is indistinguishable from the other four, so the
     * model can only guess, which is precisely the mis-tap [GroundingLog] was
     * built to collect.
     *
     * Indentation says what the sort threw away, and says it adjacently: the
     * lines of one post are the lines under one container. Measured on a
     * five-post feed it costs about 20% more characters than the sorted flat
     * list; carrying the nested JSON instead — the other way to keep the
     * structure — costs 170% more for the same information, nearly all of it
     * braces and repeated field names. So the tree comes back, rendered
     * cheaply rather than as JSON.
     *
     * Containers earn their line here. An unlabeled `LinearLayout` is worth
     * nothing on its own, but it is the anchor the indentation hangs off, and
     * it is what separates one post from the next — which is why [trim] drops
     * leaves and never containers.
     *
     * The truncation argument that justified sorting is answered by
     * [MAX_ELEMENT_LINES] instead: we do the cutting ourselves, worst-first,
     * and say so in `elements_dropped`, rather than leaving it to whatever
     * the model's context happens to clip.
     */
    private fun rebuildIndexAndList(out: JSONObject) {
        synchronized(eidLock) { eidIndex.clear() }
        val w = screenW
        val h = screenH
        if (w <= 0 || h <= 0) return

        val nodes = ArrayList<Pair<Int, JSONObject>>()
        collectNodes(out, nodes, 0)

        // Pass 1: document order is the final order — no sort. Nodes whose
        // bounds we cannot parse are skipped; their children keep their own
        // depth, so the gap shows up as a missing indent level rather than as
        // a reparented subtree.
        val rows = ArrayList<Row>(nodes.size)
        for ((depth, n) in nodes) {
            val bounds = n.optString("bounds", "")
            if (bounds.isEmpty()) continue
            val parts = bounds.split(",")
            if (parts.size != 4) continue
            val l = parts[0].toIntOrNull() ?: continue
            val t = parts[1].toIntOrNull() ?: continue
            val r = parts[2].toIntOrNull() ?: continue
            val b = parts[3].toIntOrNull() ?: continue
            if (r <= l || b <= t) continue
            rows.add(
                Row(
                    depth = depth,
                    px = intArrayOf((l + r) / 2, (t + b) / 2),
                    node = n,
                    bounds = bounds,
                    label = labelOf(n),
                    clickable = n.optBoolean("clickable", false),
                    container = n.optJSONArray("children") != null,
                )
            )
        }

        val dropped = trim(rows)

        // Pass 2: number in document order, index, render. Numbering comes
        // after the trim so the e-numbers the model reads are contiguous —
        // a gap would look like a stale list and send it back for a re-dump.
        val pkg = out.optString("pkg", "")
        val lines = ArrayList<String>(rows.size)
        for ((eidNum, row) in rows.withIndex()) {
            val eidLabel = "e$eidNum"
            val mark = if (row.clickable) "*" else ""
            val cls = row.node.optString("class", "View")
            val normCx = row.px[0] * NORM_MAX / w
            val normCy = row.px[1] * NORM_MAX / h
            synchronized(eidLock) {
                eidIndex[eidNum] = EidEntry(
                    px = row.px,
                    pkg = pkg,
                    viewId = row.node.optString("id").ifEmpty { null },
                    bounds = row.bounds,
                )
            }
            val indent = "  ".repeat(minOf(row.depth, MAX_INDENT))
            lines.add("$indent$eidLabel$mark|$cls|${row.label}|[$normCx,$normCy]")
        }

        // Replace the nested tree with the outline — the JSON was only ever
        // scaffolding for this. Keep the stats fields, drop the bulk.
        val keep = setOf(
            "ts", "pkg", "nodes_total", "nodes_kept", "nodes_useful", "surface",
            "source", "windows_seen", "windows_detail", "settling", "settle_waited_ms",
            "error", "empty",
        )
        val rootKeys = ArrayList(out.keys().asSequence().toList())
        for (k in rootKeys) if (k !in keep) out.remove(k)
        if (lines.isNotEmpty()) out.put("elements", lines.joinToString("\n"))
        if (dropped > 0) out.put("elements_dropped", dropped)
    }

    /** One candidate line, before numbering. */
    private class Row(
        val depth: Int,
        val px: IntArray,
        val node: JSONObject,
        val bounds: String,
        val label: String,
        val clickable: Boolean,
        val container: Boolean,
    )

    /**
     * Bring [rows] under [MAX_ELEMENT_LINES], worst-first, and report how many
     * went. Only decorative leaves are eligible — no label, nothing to tap,
     * no children to group — because they are the one kind of line whose
     * removal costs the model nothing. Containers are never dropped even
     * though they are usually label-less: the indent level they provide is
     * what keeps two adjacent posts from reading as one.
     *
     * If that is not enough the tail goes, which on a screen read in document
     * order means the bottom of it — recoverable by scrolling, unlike a
     * mis-association, which is not recoverable at all.
     */
    private fun trim(rows: ArrayList<Row>): Int {
        if (rows.size <= MAX_ELEMENT_LINES) return 0
        val before = rows.size
        var over = rows.size - MAX_ELEMENT_LINES
        val it = rows.listIterator(rows.size)
        while (it.hasPrevious() && over > 0) {
            val r = it.previous()
            if (!r.container && r.label.isEmpty() && !r.clickable) {
                it.remove()
                over--
            }
        }
        if (rows.size > MAX_ELEMENT_LINES) {
            rows.subList(MAX_ELEMENT_LINES, rows.size).clear()
        }
        return before - rows.size
    }

    /**
     * What to print in the label column: the text, else the description, else
     * the tail of the view id with a `#` on it.
     *
     * The id fallback is the fix for a whole class of blind spot. 朋友圈's
     * `⋯`, the avatars and the photos are all `ImageView`s with neither text
     * nor description, so the column came out empty and the model was told
     * (by HARD RULE 2, in so many words) to tap them by number without
     * knowing what they were. The node carried
     * `com.tencent.mm:id/comment_btn` the whole time — we were dropping it
     * when building the line. The `#` is not decoration: it marks the value
     * as a resource name rather than something written on the screen, which
     * the model would otherwise be entitled to read back to the user.
     */
    private fun labelOf(n: JSONObject): String {
        n.optString("text").takeIf { it.isNotEmpty() }?.let { return oneLine(it) }
        n.optString("desc").takeIf { it.isNotEmpty() }?.let { return oneLine(it) }
        n.optString("id").takeIf { it.isNotEmpty() }
            ?.let { return "#" + oneLine(it.substringAfterLast('/')) }
        return ""
    }

    /**
     * The outline is one element per line with `|` between fields, so a label
     * containing either character tears the row in half — and a multi-line
     * TextView (a 朋友圈 body, a long notification) contains newlines as a
     * matter of course. Neither was escaped before; the rows it produced were
     * unparseable and the model had to guess where the next element started.
     */
    private fun oneLine(s: String): String =
        s.replace('\n', ' ').replace('\r', ' ').replace('|', '/').trim()

    /** Collect every node in the (pruned) tree, depth-first, with its depth. */
    private fun collectNodes(node: JSONObject, sink: MutableList<Pair<Int, JSONObject>>, depth: Int) {
        sink.add(depth to node)
        val kids = node.optJSONArray("children") ?: return
        for (i in 0 until kids.length()) {
            val kid = kids.optJSONObject(i) ?: continue
            collectNodes(kid, sink, depth + 1)
        }
    }

    /** Main-thread code only; see [Stats] for why these numbers matter. */
    private fun withStats(
        body: JSONObject,
        pkg: String,
        stats: Stats,
        source: String? = null,
    ): JSONObject {
        body.put("pkg", pkg)
        body.put("nodes_total", stats.total)
        body.put("nodes_kept", stats.kept)
        body.put("nodes_useful", stats.useful)
        stats.surface?.let { body.put("surface", shortenClass(it, false)) }
        if (source != null) body.put("source", source)
        return body
    }

    /**
     * What one dump pass saw, so the caller can say why a tree is useless
     * instead of just handing back `{}`:
     *
     *   [total]  — nodes visited, before any pruning
     *   [kept]   — nodes that survived pruning
     *   [useful] — nodes carrying something you can act on (text / desc / id)
     *   [surface]— first self-rendering view found, if any
     */
    private class Stats {
        var total = 0
        var kept = 0
        var useful = 0
        var surface: String? = null
    }

    /**
     * Tell the brain what it is looking at when there is nothing to act on, in
     * its language rather than ours: not "empty tree" but "this is a surface you
     * have to read with your eyes". The empty-tree branch carries the
     * generic_bridge NO_TREE policy verbatim in spirit: prefer back/home over
     * blind coordinate taps — a wrong exit costs one tap, a wrong tap can cost
     * the task.
     *
     * Every branch here fires exactly where [dumpUiTree] attaches a screenshot
     * unasked, so the hints point at the image already in hand rather than
     * sending the brain off to `take_screenshot` for a picture of the same
     * screen it was just given. Called after the shot block for that reason —
     * if the capture failed, say so instead of promising an image that is not
     * there.
     *
     * The branches are not all the same verdict. Only the first three are
     * genuinely empty; the last has nodes that simply have no names, and since
     * [rebuildIndexAndList] started rendering nesting and centres for every
     * node rather than sorting the labelled ones to the top, that case still
     * has a usable outline and must not be told to tap by coordinate.
     *
     * [reviveNote] is appended to the first two only — the two that mean "no
     * nodes AT ALL". That is the whole discrimination: a self-drawn pane still
     * reports its containers, so a mini-program never reaches those branches
     * and never nags the user about restarting anything.
     */
    private fun annotate(out: JSONObject, verbose: Boolean) {
        if (verbose) return
        val total = out.optInt("nodes_total", 0)
        val useful = out.optInt("nodes_useful", 0)
        // "Look at the attached image" vs "go fetch one" — never both.
        val eyes = if (out.has("shot")) {
            "the screenshot attached to this response (do not call take_screenshot " +
                "for the same screen)"
        } else {
            "take_screenshot"
        }
        val revive = reviveNote()
        when {
            // Ours is the window in front, and we hid it from ourselves on
            // purpose — so the zero nodes are expected, not a stale service.
            // First because it otherwise falls into the reviveNote branches and
            // sends the user off to toggle a setting that is working fine.
            total == 0 && net.kuafuai.andee.ui.OwnCard.showing ->
                out.put(
                    "hint",
                    "The user has one of Andee's own cards open (settings or the vault), so " +
                        "there is no app tree to read — the card is deliberately invisible to " +
                        "accessibility, including to us. Nothing is broken. Wait for them to " +
                        "close it, or say in plain words that you need the settings card closed " +
                        "before you can carry on. Do not tap blindly: the taps would land on " +
                        "whatever is behind the card.",
                )

            out.has("error") && total == 0 ->
                out.put(
                    "hint",
                    "No window was handed to accessibility. Either the app is mid-transition " +
                        "(retry once) or this window refuses to expose itself. Judge by $eyes." +
                        revive,
                )

            useful == 0 && total == 0 ->
                // An empty root, not a filtered-away tree — and every other
                // accessible window was empty too (see [tryOtherWindows]).
                // Keep the conclusion scoped to this pane; never write off the
                // whole app because one screen has no semantic tree.
                out.put(
                    "hint",
                    "No accessibility nodes anywhere: the active window's root is empty and " +
                        "none of the ${out.optInt("windows_seen", 0)} other windows held content " +
                        "either. Almost always a pane that draws its own pixels — a " +
                        "mini-program, a WebView article, a game canvas — or, if `settling` is " +
                        "set, a screen still mid-transition. NO_TREE policy: judge by $eyes, " +
                        "and prefer back / home to exit and retry via a " +
                        "different route rather than blind coordinate taps. This is a property " +
                        "of this pane, not of the app: the same app's other screens very likely " +
                        "dump fine, so do not stop calling this tool on them." +
                        revive,
                )

            useful == 0 && out.has("surface") ->
                out.put(
                    "hint",
                    "Everything actionable here is inside ${out.optString("surface")}, which draws " +
                        "its own content and therefore has no accessibility nodes — ever, on any flag. " +
                        "NO_TREE policy: read the screen from $eyes, and prefer " +
                        "back / home to exit and re-enter via a different route over blind " +
                        "coordinate taps.",
                )

            useful == 0 && total > 0 ->
                // Not the same dead end as the branches above: the nodes exist,
                // they are merely nameless, so the outline still carries their
                // nesting and their centres. Aiming at an e-number there is
                // strictly better than a coordinate tap — the device resolves
                // the element's real centre, and the guard exempts e-taps.
                out.put(
                    "hint",
                    "$total nodes but none with text, desc or id: this app draws its own content, " +
                        "so every line in `elements` has an empty label. Read them anyway — the " +
                        "indentation still groups them and each line still carries its centre. " +
                        "Match what you see in $eyes against the outline and tap the `*` line " +
                        "sitting at that spot by e-number; drop to a coordinate tap only if no " +
                        "line covers it.",
                )
        }
    }

    /**
     * The sentence appended when an app that **has worked on this device** is
     * suddenly handing back no nodes at all — see [goodDumps].
     *
     * Deliberately evidence and not an instruction. It states the two facts
     * only the device knows (this package read fine, that long ago) and what
     * the human would have to do; whether that is worth interrupting the user
     * over is the brain's call, because only the brain knows what the user
     * asked for and how far along it is. Hard-coding "if pkg == WeChat and the
     * tree is empty, tell the user to restart" would fire on every mini-program
     * WeChat hosts and train the user to ignore us.
     *
     * Empty string, not null, so the callers can concatenate unconditionally.
     *
     * Identity is best-effort by design: an empty dump usually has no `pkg` of
     * its own, so it falls back to the last package we read and then to the
     * accessibility event stream. Both are "the app we believe is in front" and
     * both can be one screen stale — which is why the note names the package it
     * is talking about instead of saying "this app", so a wrong guess is
     * visible to the brain rather than silently misattributed.
     *
     * Covers one shape of the fault and not the other. A stale connection
     * normally shows up as `rootInActiveWindow == null` — no nodes at all —
     * which is where this fires. If instead it were to hand back a root that
     * prunes down to nothing *useful*, that lands in the nameless-nodes branch
     * and gets no note, because there it is indistinguishable from a
     * mini-program: same package, same known-good history, same counts. Better
     * silent than nagging the user every time WeChat opens a mini-program. If
     * the other shape turns out to happen on a real device, it needs a sharper
     * signal than this one (`windows_seen == 0` is the obvious candidate) —
     * not a looser condition here.
     */
    private fun reviveNote(): String {
        val pkg = lastDumpPkg ?: DeviceState.foregroundPkg ?: return ""
        if (pkg == service.packageName) return ""
        val seen = goodDumps[pkg] ?: return ""
        val mins = (System.currentTimeMillis() - seen) / 60_000
        val ago = if (mins < 1) "less than a minute ago" else "$mins minutes ago"
        return " THIS APP HAS WORKED HERE BEFORE: `$pkg` gave a usable " +
            "element list $ago, and is now returning no accessibility nodes at all. " +
            "A pane that draws its own pixels does not do that — it still reports its " +
            "containers. Zero nodes from an app that was readable is the signature of " +
            "our accessibility connection having gone stale, and no tool call can " +
            "repair it: only the person holding the tablet can, by opening 设置 → " +
            "无障碍 (Settings → Accessibility), switching Andee off and back on. That " +
            "takes about three seconds. Retry this tool once first in case the screen " +
            "was merely mid-transition; if it is still empty, say so to the user in " +
            "plain words and wait for them. Taps and swipes still work and you still " +
            "have the screenshot, so you are not stuck — but grinding on blind " +
            "coordinates is worth less than asking for a three-second toggle."
    }

    private fun runGesture(gesture: GestureDescription, timeoutMs: Long): JSONObject {
        val latch = CountDownLatch(1)
        var completed = false
        val ok = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    completed = true; latch.countDown()
                }

                override fun onCancelled(g: GestureDescription?) {
                    completed = false; latch.countDown()
                }
            },
            null,
        )
        if (!ok) throw IllegalStateException("dispatchGesture returned false")
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return JSONObject().put("completed", completed)
    }

    /**
     * Build one node's JSON. Bottom-up pruning:
     *   1. Recurse into children first (so we know how many survive).
     *   2. `hasReason` = has id/text/desc, or is clickable/scrollable/focused/
     *      checked, or is explicitly disabled, or is a render surface.
     *   3. keep if hasReason, OR if it has ≥ 2 surviving children (it's a
     *      grouping container that tells the LLM "these belong together").
     *   4. hoist if it has exactly 1 surviving child and no reason — return
     *      that child in place of self. Collapses deep layout wrapper chains.
     *   5. drop if it has 0 children and no reason — decorative leaves like
     *      Spacers, dividers, dots.
     *
     * `verbose=true` disables all of this and emits every node with every
     * field, matching pre-pruning behavior.
     *
     * [stats] collects what was seen vs what survived, so the caller can tell
     * "there is no tree" apart from "we threw the tree away".
     */
    private fun buildNode(node: AccessibilityNodeInfo, verbose: Boolean, stats: Stats? = null): JSONObject? {
        if (!verbose && !node.isVisibleToUser) return null
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (!verbose && (bounds.width() <= 0 || bounds.height() <= 0)) return null
        val st = stats
        if (st != null) st.total++

        val kids = mutableListOf<JSONObject>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                buildNode(child, verbose, stats)?.let { kids.add(it) }
            } finally {
                child.recycle()
            }
        }

        val id = node.viewIdResourceName
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val clickable = node.isClickable
        val focused = node.isFocused
        val scrollable = node.isScrollable
        val checked = node.isChecked
        val selected = node.isSelected
        val enabled = node.isEnabled
        val cls = node.className?.toString()
        val surface = if (isRenderSurface(cls)) cls else null

        val hasReason = !id.isNullOrEmpty() || !text.isNullOrEmpty() || !desc.isNullOrEmpty()
                || clickable || focused || scrollable || checked || selected || !enabled
                || surface != null

        if (!verbose) {
            if (!hasReason && kids.isEmpty()) return null           // drop decorative leaf
            if (!hasReason && kids.size == 1) return kids[0]        // hoist single child
        }

        if (st != null) {
            st.kept++
            if (!id.isNullOrEmpty() || !text.isNullOrEmpty() || !desc.isNullOrEmpty()) st.useful++
            if (surface != null && st.surface == null) st.surface = surface
        }

        val obj = JSONObject()
        if (!cls.isNullOrEmpty()) obj.put("class", shortenClass(cls, verbose))
        // Surfaced even though it carries no text: it is the one thing that
        // tells the brain "don't wait for a tree here, you need the pixels".
        if (surface != null) obj.put("opaque", true)
        if (!id.isNullOrEmpty()) obj.put("id", id)
        if (!text.isNullOrEmpty()) obj.put("text", text)
        if (!desc.isNullOrEmpty()) obj.put("desc", desc)
        obj.put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
        if (verbose || clickable) obj.put("clickable", clickable)
        if (verbose || focused) obj.put("focused", focused)
        if (verbose || scrollable) obj.put("scrollable", scrollable)
        if (verbose || checked) obj.put("checked", checked)
        if (verbose || selected) obj.put("selected", selected)
        if (verbose || !enabled) obj.put("enabled", enabled)

        if (kids.isNotEmpty()) {
            val arr = JSONArray()
            for (k in kids) arr.put(k)
            obj.put("children", arr)
        }
        return obj
    }


    /**
     * Views that draw their own content and therefore have no accessibility
     * children: a WebView page, a Flutter/X5 pane (WeChat mini-programs), a
     * game canvas, a video surface, Compose Canvas. Nothing inside them will
     * ever appear in a UI tree — no service flag changes that.
     *
     * We bother because knowing *that* is the difference between the brain
     * retrying get_screen_element forever and switching to pixels.
     */
    private fun isRenderSurface(cls: String?): Boolean {
        if (cls.isNullOrEmpty()) return false
        return cls.contains("WebView", ignoreCase = true)      // incl. X5 / XWeb / WKWebView-ish names
                || cls.contains("SurfaceView", ignoreCase = true)
                || cls.contains("TextureView", ignoreCase = true)
                || cls.contains("GLSurfaceView", ignoreCase = true)
                || cls.contains("Flutter", ignoreCase = true)
                || cls.contains("unity", ignoreCase = true)
                || cls.contains("cocos", ignoreCase = true)
                || cls.contains("CanvasView", ignoreCase = true)
    }

    /**
     * Strip the common android.* / androidx.* / android.view.* prefixes off
     * a class name so `android.widget.FrameLayout` becomes `FrameLayout`.
     * In verbose mode keep the fully-qualified name.
     */
    private fun shortenClass(cls: String?, verbose: Boolean): String? {
        if (cls == null) return null
        if (verbose) return cls
        val prefixes = arrayOf(
            "android.widget.", "android.view.", "android.webkit.",
            "androidx.compose.ui.platform.",
            "androidx.recyclerview.widget.", "androidx.viewpager.widget.",
            "androidx.appcompat.widget.", "androidx.constraintlayout.widget.",
        )
        for (p in prefixes) if (cls.startsWith(p)) return cls.substring(p.length)
        return cls
    }

    companion object {
        /**
         * ADBKeyboard 的广播接口。action 是裸字符串,没有包名前缀 ——
         * 加了前缀就匹配不上它注册的 filter,广播静默消失。
         */
        private const val ADB_KEYBOARD_PKG = "com.android.adbkeyboard"
        private const val ADB_INPUT_TEXT = "ADB_INPUT_TEXT"
        private const val ADB_CLEAR_TEXT = "ADB_CLEAR_TEXT"
        private const val READBACK_POLL_MS = 200L
        private const val READBACK_BUDGET_MS = 1500L

        /**
         * Toolbar labels the IME uses for its clipboard feature.
         *
         * Not translated, and not translatable: these are matched against the
         * labels the *user's* keyboard draws, which come from the keyboard's
         * own language, not from this app's setting. The list carries both
         * scripts for that reason — dropping the Chinese half because the
         * interface is English would break clipboard pasting on every Chinese
         * keyboard. See `NotificationRelayService.CN_CALL_KEYWORDS`.
         */
        private val CLIPBOARD_LABELS = listOf("剪贴板", "Clipboard", "clipboard", "粘贴", "Paste")
        private val PASTE_LABELS = listOf("粘贴", "Paste", "paste", "Paste as plain text")
        private const val SCROLL_TAG = "ScrollDir"
        private const val SETTLE_POLL_MS = 250L
        private const val SETTLE_BUDGET_MS = 1500L

        /**
         * Stability pass knobs — see the second loop in [dumpUiTree].
         * STABLE_POLL_MS is above a frame pair (2×16ms) but below a typical
         * list-settle ripple, so a still screen agrees on the first retry;
         * STABLE_MAX_TRIES caps the worst case (endless animation — carousels
         * never settle) at ~1s extra, after which `still_moving` tells the
         * brain the truth rather than pretending the tree is final.
         */
        private const val STABLE_POLL_MS = 300L
        private const val STABLE_MAX_TRIES = 3
        private const val QUIET_WINDOW_MS = 1200L

        /** The relative coordinate space the brain speaks: 0..1000 both axes. */
        private const val NORM_MAX = 1000

        /** Screenshot long side after downscale (generic_bridge value). */
        private const val SHOT_MAX_SIDE = 1280

        /**
         * Hard ceiling on outline lines. Generous — it is a safety valve for a
         * pathological list view, not a budget. Below it nothing is dropped,
         * because on this screen the model cannot tell "there is no such
         * control" from "we did not print it".
         */
        private const val MAX_ELEMENT_LINES = 300

        /**
         * Deepest indent level rendered; beyond it lines stay at this column.
         * Hoisting already collapses wrapper chains, so real depth is small —
         * this only stops a pathological hierarchy from spending more
         * characters on leading spaces than on content.
         */
        private const val MAX_INDENT = 10

        /** Gestures closer than this count as "the same spot" — catches
         *  re-tap-with-jitter, not just exact duplicates. */
        private const val LOOP_RADIUS_PX = 120

        /** Allowed same-spot attempts before the guard fires. 3 means the
         *  4th is refused. */
        private const val LOOP_MAX_TRIES = 3

        /** Same-spot counter window; older than this is a fresh task. */
        private const val LOOP_WINDOW_MS = 300_000L

        private const val LOOP_HELP =
            "Stuck-loop guard: this same screen area has been touched more than " +
                "3 times within 5 minutes. The gesture was NOT executed. " +
                "Do not retry the same tap. Take a fresh screenshot or get_screen_element, " +
                "pick a genuinely different approach, and if the goal still cannot " +
                "be reached, stop and ask the user for help instead of continuing."
    }
}
