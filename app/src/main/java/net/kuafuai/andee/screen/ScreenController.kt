package net.kuafuai.andee.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import net.kuafuai.andee.R
import net.kuafuai.andee.device.DeviceState
import net.kuafuai.andee.i18n.AppLocale
import net.kuafuai.andee.net.ToolSchemas
import net.kuafuai.andee.ui.ChatHistory
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
    /** Where our own (still visible) ball window sits, masked out of pixel comparisons. */
    private val ownArea: () -> Rect? = { null },
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

    // ---- the aim grid, and the two ways to name a point --------------------
    //
    // This exists because a general-purpose vision model *reads* a position off
    // an image far better than it *computes* one, and the arithmetic is where
    // it has been losing: on a self-drawn pane — the exact case where the
    // element tree is empty and pixels are all there is — the old protocol
    // asked the model to measure a point on a 1280-px image and rescale it to
    // 0-1000 by hand. Measured in the field, three attempts at one button
    // spread across 410 px.
    //
    // So every image the brain sees now carries a faint labelled grid, and it
    // may answer with a cell label instead of a number. Two vocabularies, and
    // they are deliberately the same one twice: a cell is always A1..H12 of
    // whatever image the label was read off, and `on` says which image that
    // was.
    //
    // A cell alone is too coarse to tap with, and that is measured, not
    // guessed: a cell is 150x222 px on a 1200x2664 phone, so aiming at its
    // centre hits a 460x140 button 64% of the time and a 110 px icon 38% —
    // *for a model that reads the cell perfectly*. Hence `part`, a third of the
    // cell each way (top-left … bottom-right): the same perfect reader then
    // hits the icon every time and a 66 px one 90% of the time, in one step.
    // Zooming a cell is still there for anything smaller.
    //
    // Nothing here redefines a coordinate. Every path still ends in
    // [tapNorm]'s 0-1000 screen space, so the stuck-loop guard and
    // [GroundingLog] see exactly the same numbers they saw before, and a cell
    // tap is still a coordinate tap as far as the offline pairing is concerned.

    /**
     * Left, top, right, bottom of the last [lookRegion], in screen 0-1000
     * space. This is the whole of what a zoom has to remember: the region's
     * geometry is a fact about the screen, so a point inside it converts the
     * same way whether the content has since changed or not.
     */
    @Volatile
    private var zoomRegion: IntArray? = null

    @Volatile
    private var zoomAtMs = 0L

    private class Aim(val nx: Int, val ny: Int, val label: String)

    private fun cellLabel(col: Int, row: Int): String = "${('A' + col)}${row + 1}"

    /**
     * The centre of one third-by-third of a cell, in the 0-1000 space of the
     * image it was read off. `fx`/`fy` are 0..2; (1, 1) is the cell's centre.
     */
    private fun cellPoint(col: Int, row: Int, fx: Int, fy: Int): IntArray = intArrayOf(
        ((col * 3 + fx) * 2 + 1) * NORM_MAX / (2 * GRID_COLS * 3),
        ((row * 3 + fy) * 2 + 1) * NORM_MAX / (2 * GRID_ROWS * 3),
    )

    /** `part` → which third of the cell, as (fx, fy); null means the centre. */
    private fun parsePart(part: String?): IntArray {
        if (part.isNullOrBlank()) return intArrayOf(1, 1)
        val p = part.trim().lowercase().replace('_', '-').replace(' ', '-')
        return PART_THIRDS[p] ?: throw IllegalArgumentException(
            "unknown part=\"$part\" — expected one of ${PARTS.joinToString()} " +
                "(which third of the cell the target is in; omit it for the centre)"
        )
    }

    private fun badCell(cell: String) =
        "bad grid cell '$cell' — expected a column letter A..${'A' + GRID_COLS - 1} " +
            "and a row number 1..$GRID_ROWS, exactly as labelled on the image you were " +
            "shown (e.g. \"D7\")"

    private fun parseCell(cell: String): IntArray {
        val s = cell.trim().uppercase()
        val row = if (s.length >= 2) s.substring(1).toIntOrNull()?.minus(1) else null
        val col = if (s.isNotEmpty()) s[0] - 'A' else -1
        if (row == null || col !in 0 until GRID_COLS || row !in 0 until GRID_ROWS) {
            throw IllegalArgumentException(badCell(cell))
        }
        return intArrayOf(col, row)
    }

    /** A point in the last zoom image's own 0-1000 space → screen 0-1000. */
    private fun zoomToScreen(lx: Int, ly: Int): IntArray {
        val r = zoomRegion
            ?: throw IllegalStateException(
                "on=\"zoom\" needs a zoom to have been taken first — call " +
                    "zoom_screen_region and read the aim off the image it returns"
            )
        val age = SystemClock.uptimeMillis() - zoomAtMs
        if (age > ZOOM_STALE_MS) {
            throw IllegalStateException(
                "the last zoom image is ${age / 1000}s old — the screen has almost " +
                    "certainly moved since. Take a fresh zoom_screen_region (or a " +
                    "screenshot) and aim from that image instead of this one"
            )
        }
        requireNorm(lx, "x"); requireNorm(ly, "y")
        return intArrayOf(
            r[0] + lx * (r[2] - r[0]) / NORM_MAX,
            r[1] + ly * (r[3] - r[1]) / NORM_MAX,
        )
    }

    /**
     * Turn whatever the brain said into screen coordinates.
     *
     * @param on which image the aim was read off — "screen" (the default) or
     *   "zoom" (the last [lookRegion] image, whose own 0-1000 space maps onto
     *   the region it showed).
     * @param cell a grid label from that image, e.g. "D7".
     * @param part which third of that cell, e.g. "bottom-right"; null = centre.
     */
    private fun resolveAim(on: String?, x: Int?, y: Int?, cell: String?, part: String?): Aim {
        var zoom = when (on?.trim()?.lowercase()) {
            null, "", "screen" -> false
            "zoom" -> true
            else -> throw IllegalArgumentException(
                "unknown on=\"$on\" — expected \"screen\" (a full screenshot) or \"zoom\" " +
                    "(the image zoom_screen_region returned)"
            )
        }
        if (cell != null && cell.isNotBlank()) {
            // The grid labels a zoom in lowercase and a full screen in
            // uppercase, so the label alone says where it was read — and it has
            // to be honoured, because "d7 from the magnified picture" and "D7
            // from the screen" are 120 units apart and both plausible. Only
            // consulted when `on` was not given: an explicit on= wins, so a
            // model that says on="screen" with a lowercase label is corrected
            // by the parser rather than silently followed off the zoom.
            if (on.isNullOrBlank() && cell.trim().all { it.isLowerCase() || !it.isLetter() } &&
                cell.trim().any { it.isLetter() }
            ) {
                zoom = true
            }
            val c = parseCell(cell)
            val f = parsePart(part)
            val local = cellPoint(c[0], c[1], f[0], f[1])
            val where = if (part.isNullOrBlank()) "cell $cell" else "cell $cell ${part.trim()}"
            if (!zoom) return Aim(local[0], local[1], where)
            val s = zoomToScreen(local[0], local[1])
            return Aim(s[0], s[1], "$where of the zoom image")
        }
        if (x == null || y == null) {
            throw IllegalArgumentException(
                "aim needs either {\"cell\": \"D7\"} or {\"x\": …, \"y\": …} — see " +
                    "the grid drawn on the last image you were shown"
            )
        }
        if (!zoom) return Aim(requireNorm(x, "x"), requireNorm(y, "y"), "screen $x,$y")
        val s = zoomToScreen(x, y)
        return Aim(s[0], s[1], "zoom $x,$y")
    }

    fun tapAim(on: String?, x: Int?, y: Int?, cell: String?, part: String?): JSONObject {
        val a = resolveAim(on, x, y, cell, part)
        return tapNorm(a.nx, a.ny).put("aimed_at", a.label)
    }

    fun longPressAim(
        on: String?, x: Int?, y: Int?, cell: String?, part: String?, durationMs: Long,
    ): JSONObject {
        val a = resolveAim(on, x, y, cell, part)
        return longPressNorm(a.nx, a.ny, durationMs).put("aimed_at", a.label)
    }

    /**
     * Zoom on the point a cell (+ part) would tap, with a crosshair on it —
     * look before you fire.
     *
     * Centred on the aim point and two cells each way, not on the cell
     * itself: targets sit on grid lines as often as not (a dialog's ✕ is
     * centred on the screen, and the screen's centre *is* a grid line), and
     * a zoom of exactly one cell cut such a target into quarters. The
     * crosshair turns "where is the target" — the question vision models get
     * wrong — into "is the mark on it", which they get right.
     */
    fun zoomAim(cell: String, part: String?): JSONObject {
        val a = resolveAim("screen", null, null, cell, part)
        val out = lookRegion(
            a.nx, a.ny, 2 * NORM_MAX / GRID_COLS, 2 * NORM_MAX / GRID_ROWS,
            mark = intArrayOf(a.nx, a.ny),
        )
        val args = if (part.isNullOrBlank()) "{\"cell\": \"$cell\"}"
        else "{\"cell\": \"$cell\", \"part\": \"${part.trim()}\"}"
        return out.put("aimed_at", a.label).put(
            "aim_check",
            "The RED CROSSHAIR is exactly where tap_by_coordinates $args would land. " +
                "If it sits on the target, tap with those same arguments. If it does " +
                "not, do NOT nudge the screen cell: name the lowercase cell (+ part) " +
                "of THIS image where the target is, with on=\"zoom\".",
        )
    }

    /**
     * Draw the aim grid onto an image the brain is about to be shown, in
     * place-of-copy (the source is left alone; the caller recycles the copy).
     *
     * The lines are deliberately **faint and two-tone** — a dark hairline with
     * a white halo — because this lands on top of content nobody chose for it
     * to sit on: a white document, a night-mode chat, a photo. One colour
     * alone disappears on half the things this device looks at, and a grid you
     * cannot see is worse than no grid, because the model will still aim by it.
     * The **labels are not faint**: they are the thing being read, so they get
     * a dark halo of their own and enough alpha to survive a white page. They
     * sit at the cell's centre, which is also the `part` = center point.
     *
     * Same grid, same size, on every image — and on a zoom it is *lowercase*,
     * which is the whole disambiguation: a label read off a magnified region
     * must never be mistakable for a cell of the full screen, and the case
     * carries that from the image itself rather than from a rule the model has
     * to remember. [resolveAim] honours it in the other direction too — a
     * lowercase cell with no `on` means the zoom.
     *
     * The returned PNG is what the brain sees AND what [GroundingLog] keeps.
     * One encode, deliberately: the pairing's job is to show what the model was
     * actually looking at, and a training set of ungridded images beside
     * gridded inferences would be a set about a screen nobody saw.
     */
    /**
     * A hollow ring with a gap at its centre, so the mark says where without
     * hiding what is under it. Red with a white halo: it has to win against
     * the grid and against whatever the app painted.
     */
    private fun drawCrosshair(bmp: Bitmap, x: Float, y: Float) {
        runCatching {
            val canvas = Canvas(bmp)
            val r = minOf(bmp.width, bmp.height) * 0.045f
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.WHITE
                strokeWidth = r * 0.30f
            }
            val red = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.rgb(255, 30, 60)
                strokeWidth = r * 0.14f
            }
            for (p in listOf(halo, red)) {
                canvas.drawCircle(x, y, r, p)
                canvas.drawLine(x - r * 1.8f, y, x - r * 0.4f, y, p)
                canvas.drawLine(x + r * 0.4f, y, x + r * 1.8f, y, p)
                canvas.drawLine(x, y - r * 1.8f, x, y - r * 0.4f, p)
                canvas.drawLine(x, y + r * 0.4f, x, y + r * 1.8f, p)
            }
            canvas.drawCircle(x, y, r * 0.08f, red.apply { style = Paint.Style.FILL })
        }
    }

    private fun withAimGrid(src: Bitmap, zoom: Boolean): Bitmap {
        val out = runCatching { src.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull() ?: return src
        runCatching {
            val w = out.width
            val h = out.height
            val cw = w.toFloat() / GRID_COLS
            val ch = h.toFloat() / GRID_ROWS
            val canvas = Canvas(out)
            val unit = minOf(cw, ch)
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.WHITE
                alpha = 38
                strokeWidth = (unit * 0.06f).coerceIn(2f, 3f)
            }
            val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.BLACK
                alpha = 96
                strokeWidth = 1f
            }
            // Edges too: the last row and column are aimable, and a cell whose
            // far side is the screen edge reads as open-ended without them.
            for (i in 0..GRID_COLS) {
                val x = (i * cw).coerceIn(0.5f, w - 0.5f)
                canvas.drawLine(x, 0f, x, h.toFloat(), halo)
                canvas.drawLine(x, 0f, x, h.toFloat(), hair)
            }
            for (i in 0..GRID_ROWS) {
                val y = (i * ch).coerceIn(0.5f, h - 0.5f)
                canvas.drawLine(0f, y, w.toFloat(), y, halo)
                canvas.drawLine(0f, y, w.toFloat(), y, hair)
            }
            val textSize = (unit * 0.20f).coerceIn(9f, 24f)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = textSize
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            for (row in 0 until GRID_ROWS) {
                for (col in 0 until GRID_COLS) {
                    val raw = cellLabel(col, row)
                    val label = if (zoom) raw.lowercase() else raw
                    // Dead centre of the cell. In a corner the label sat nearer
                    // to three neighbours than to most of its own cell, and a
                    // model reads "the label closest to the target".
                    val tx = (col + 0.5f) * cw
                    val ty = (row + 0.5f) * ch + textSize * 0.35f
                    text.style = Paint.Style.STROKE
                    text.strokeWidth = textSize * 0.18f
                    text.color = Color.BLACK
                    text.alpha = 165
                    canvas.drawText(label, tx, ty, text)
                    text.style = Paint.Style.FILL
                    text.color = Color.WHITE
                    text.alpha = 175
                    canvas.drawText(label, tx, ty, text)
                }
            }
        }.onFailure { Log.w("Body", "aim grid: could not draw: ${it.message}") }
        return out
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
        val root = runCatching { activeRoot() }.getOrNull() ?: return null
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
            // Weak path: walk for a node with the exact same bounds. Budgeted:
            // a miss falls back to the remembered centre, which is fine.
            return findNodeByBounds(root, entry.bounds, WalkBudget(SEARCH_BUDGET_MS))
        } finally {
            root.recycle()
        }
    }

    private fun findNodeByBounds(node: AccessibilityNodeInfo, bounds: String, budget: WalkBudget): IntArray? {
        val r = Rect()
        runCatching { node.getBoundsInScreen(r) }
        if (r.width() > 0 && r.height() > 0 && "${r.left},${r.top},${r.right},${r.bottom}" == bounds) {
            return intArrayOf(r.left, r.top, r.right, r.bottom)
        }
        for (i in 0 until node.childCount) {
            if (budget.spent()) return null
            val child = runCatching { childOf(node, i) }.getOrNull() ?: continue
            try {
                val hit = findNodeByBounds(child, bounds, budget)
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
        val beforePx = if (before.optBoolean("blind")) pixelSignature() else null
        val result = runGesture(GestureDescription.Builder().addStroke(stroke).build(), 2000L)
        marker?.mark(x, y, net.kuafuai.andee.ui.TapMarkerUi.Kind.TAP)
        result.put("x", x).put("y", y)
        attachEffectSnapshot(result, before, beforePx)
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
        val root = runCatching { activeRoot() }.getOrNull()
            ?: return JSONObject().put("pkg", "").put("sig", 0)
        return try {
            val f = FingerprintStats()
            val budget = WalkBudget(FINGERPRINT_BUDGET_MS)
            collectFingerprint(root, f, 0, budget)
            // Window COUNT is part of the fingerprint on purpose: WeChat's
            // Moments like/comment bar is a full-screen overlay window that
            // changes nothing in the active window's node tree — measured in
            // the field: the bar appeared, screen_changed said false, and the
            // only honest signal was the window list growing by one.
            val winCount = runCatching { service.windows.size }.getOrDefault(0)
            JSONObject()
                .put("pkg", root.packageName?.toString().orEmpty())
                .put("sig", f.sig() * 31 + winCount)
                // Two cut fingerprints stop at different nodes, so their
                // signatures differ whether or not the screen did.
                .apply { if (budget.cut) put("cut", true) }
                // A withheld root (or a bare canvas) gives the same signature
                // before and after any tap, so the tree cannot say whether one
                // landed — see [pixelSignature].
                .apply { if (f.count <= BLIND_TREE_NODES) put("blind", true) }
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

    private fun collectFingerprint(node: AccessibilityNodeInfo, f: FingerprintStats, depth: Int, budget: WalkBudget) {
        if (depth > 18 || f.count > 900) return  // cap: fingerprint, not a dump
        f.visit(node)
        for (i in 0 until node.childCount) {
            if (budget.spent()) return
            val c = runCatching { childOf(node, i) }.getOrNull() ?: continue
            try {
                collectFingerprint(c, f, depth + 1, budget)
            } finally {
                c.recycle()
            }
        }
    }

    /**
     * Mean luma per cell of a [PX_COLS]×[PX_ROWS] grid over the live screen,
     * with the status bar and our own ball window set to NaN so a ticking
     * clock or a blinking ball cannot read as the tap landing. Null if the
     * capture failed — the caller then falls back to the tree's answer.
     */
    private fun pixelSignature(): FloatArray? {
        val mask = ownArea()
        return captureFrame { hw ->
            val sw = PX_COLS * PX_SAMPLE
            val sh = PX_ROWS * PX_SAMPLE
            val scaled = Bitmap.createScaledBitmap(hw, sw, sh, true)
            val soft = if (scaled.config == Bitmap.Config.HARDWARE) {
                scaled.copy(Bitmap.Config.ARGB_8888, false).also { scaled.recycle() }
            } else scaled
            val px = IntArray(sw * sh)
            soft.getPixels(px, 0, sw, 0, 0, sw, sh)
            soft.recycle()
            val cellW = hw.width.toFloat() / PX_COLS
            val cellH = hw.height.toFloat() / PX_ROWS
            val statusBar = (hw.height * STATUS_BAR_FRACTION).toInt()
            FloatArray(PX_COLS * PX_ROWS) { i ->
                val cx = i % PX_COLS
                val cy = i / PX_COLS
                val cell = Rect(
                    (cx * cellW).toInt(), (cy * cellH).toInt(),
                    ((cx + 1) * cellW).toInt(), ((cy + 1) * cellH).toInt(),
                )
                if (cell.top < statusBar || (mask != null && Rect.intersects(mask, cell))) {
                    return@FloatArray Float.NaN
                }
                var sum = 0f
                for (yy in 0 until PX_SAMPLE) for (xx in 0 until PX_SAMPLE) {
                    val c = px[(cy * PX_SAMPLE + yy) * sw + cx * PX_SAMPLE + xx]
                    sum += 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                }
                sum / (PX_SAMPLE * PX_SAMPLE)
            }
        }
    }

    private fun pixelsMoved(a: FloatArray, b: FloatArray): Boolean {
        if (a.size != b.size) return true
        var moved = 0
        for (i in a.indices) {
            if (a[i].isNaN() || b[i].isNaN()) continue
            if (kotlin.math.abs(a[i] - b[i]) > PX_CELL_DELTA) moved++
        }
        return moved >= PX_MIN_CELLS
    }

    /** takeScreenshot refuses calls closer than ~333 ms apart; space them out instead of failing. */
    private val lastCaptureAt = AtomicLong(0L)

    private fun awaitCaptureGap() {
        val wait = lastCaptureAt.get() + CAPTURE_GAP_MS - SystemClock.uptimeMillis()
        if (wait > 0) runCatching { Thread.sleep(wait) }
    }

    private fun <T> captureFrame(transform: (Bitmap) -> T): T? {
        awaitCaptureGap()
        val latch = CountDownLatch(1)
        var out: T? = null
        val executor = Executors.newSingleThreadExecutor()
        try {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val hb = screenshot.hardwareBuffer
                        try {
                            Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)?.let { out = transform(it) }
                        } catch (t: Throwable) {
                            Log.w("Body", "captureFrame: ${t.message}")
                        } finally {
                            hb.close()
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                },
            )
            latch.await(3, TimeUnit.SECONDS)
        } finally {
            lastCaptureAt.set(SystemClock.uptimeMillis())
            executor.shutdown()
        }
        return out
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
    private fun attachEffectSnapshot(result: JSONObject, before: JSONObject, beforePx: FloatArray? = null) {
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
        var changed = before.optString("pkg") != after.optString("pkg") ||
            before.optInt("sig") != after.optInt("sig")
        if (!changed && beforePx != null) {
            // Measured 2026-10-08 on a withheld WeChat: every tap that opened a
            // page came back `false`, and the brain re-aimed at a screen that
            // had already moved on — most of what looked like bad aim.
            val afterPx = pixelSignature()
            if (afterPx != null && pixelsMoved(beforePx, afterPx)) changed = true
            result.put(
                "screen_changed_note",
                "This app hides its contents from accessibility, so screen_changed was judged " +
                    "from the pixels. A playing video or animation also counts as a change — " +
                    "confirm from the attached screenshot.",
            )
        }
        result.put("screen_changed", changed)
        if (before.optBoolean("cut") || after.optBoolean("cut")) {
            result.put(
                "screen_changed_note",
                "This app answers accessibility too slowly to compare the screen before and " +
                    "after, so screen_changed is a guess — judge whether the tap landed from the " +
                    "attached screenshot.",
            )
        }
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
                val tree = dumpOnce(false, WalkBudget(ATTACHED_TREE_MS))
                if (tree.optInt("nodes_useful", 0) > 0) {
                    rebuildIndexAndList(tree)
                    val elements = tree.optString("elements")
                    if (elements.isNotEmpty()) {
                        result.put("elements", elements)
                            .put("elements_pkg", tree.optString("pkg"))
                            .put("elements_hint",
                                "This is the POST-TAP screen. Chain your next tap_screen_element / " +
                                "type_text directly from these e-numbers — do NOT re-dump first." +
                                if (tree.optBoolean("walk_cut")) {
                                    " The list is PARTIAL: this app answered too slowly to read it " +
                                        "all, so what is in the screenshot but not here was not reached."
                                } else "")
                    }
                }
            }
        } else {
            result.put(
                "same_spot_hint",
                "the screen did NOT react to this tap. miss_shot shows where the ring " +
                    "landed — the tapped point, magnified with its surroundings. Its grid " +
                    "is lowercase (a1..h12) because it is a zoom, so a cell read off it is " +
                    "aimed with {\"cell\": \"d7\", \"part\": \"…\", \"on\": \"zoom\"}, which " +
                    "the device converts for you. Aim the retry from that image, not from " +
                    "the previous full screenshot.",
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
        // ── 输入通道:无障碍输入法优先,ADBKeyboard 兜底(2026-09-30) ──
        //
        // 首选 [A11yIme]:API 33 起,声明了 flagInputMethodEditor 的无障碍
        // 服务自己就能 commitText 到焦点编辑器 —— 和键盘同一条 InputConnection,
        // 但**不是**一个输入法:不用装、不用启用、不用切,用户自己的键盘
        // 全程还在。它排第一位是因为下面那条路要求用户有一台电脑(见
        // [typeViaA11yIme])。兜底仍是 ADBKeyboard,给 API 30–32 和
        // 这条路够不着的编辑器 —— 打字静默失效这件事这个项目已经付过
        // 两次代价了。
        //
        // 以下是 ADBKeyboard 那条路的原始记录(2026-09-20 定版),照旧有效:
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
        return typeViaA11yIme(text, secret) ?: typeViaAdbKeyboard(text, secret)
    }

    /**
     * 首选通道:本服务自己的无障碍输入法([A11yIme])。
     *
     * 返回 null = **这条路不通、且一个字都没打**(API < 33、当前没有
     * 焦点编辑器、自绘编辑器拿不到 InputConnection),交给
     * [typeViaAdbKeyboard] 兜底。返回非 null = 已经 commit 过,成功与
     * 否都在这里定论,绝不能再兜底一次 —— 那是真正会输两遍的情况。
     *
     * 它排在 ADBKeyboard 前面的理由不是快(虽然省掉了借还输入法的
     * ~0.75s),是**用户不需要电脑**:ADB 通道要侧载一个第三方 APK,
     * 还要 `adb shell pm grant … WRITE_SECURE_SETTINGS` 才能改默认输入
     * 法。没有电脑的用户在那条路上一个字也打不了。
     */
    private fun typeViaA11yIme(text: String, secret: Boolean): JSONObject? {
        val got = A11yIme.replace(service, text) ?: return null
        val ok = got == text
        return JSONObject()
            .put("performed", ok)
            .put("via", A11yIme.VIA)
            .put("len", text.length)
            .apply {
                if (ok) {
                    if (!secret) put("text", got)
                } else if (secret) {
                    put(
                        "error",
                        "committed through the accessibility IME but the field holds " +
                            "${got.length} chars instead of ${text.length} — " +
                            "verify with a screenshot",
                    )
                } else {
                    put(
                        "error",
                        "committed through the accessibility IME but the field reads " +
                            "'${got.take(30)}' — verify with a screenshot",
                    )
                }
            }
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
        val budget = WalkBudget(SEARCH_BUDGET_MS)
        try {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.add(r)
            while (stack.isNotEmpty() && !budget.spent()) {
                val n = stack.removeFirst()
                val t = n.text?.toString().orEmpty()
                if (t == want) return t
                for (i in 0 until n.childCount) childOf(n, i)?.let { stack.add(it) }
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
                // A WebView's editor often reports the action but does not act on
                // it. [A11yIme.editorAction] asks the editor's own EditorInfo what
                // it is (Send / Search / Next) and fires that, which is what its
                // listener is registered for. Second rather than first because
                // ACTION_IME_ENTER needs no focused *input connection* and covers
                // the `id`-addressed case this method also serves.
                val viaIme = if (!performed && id == null) {
                    A11yIme.editorAction(service)
                } else {
                    false
                }
                return JSONObject()
                    .put("performed", performed || viaIme)
                    .put("target_id", target.viewIdResourceName)
                    .apply { if (viaIme) put("via", A11yIme.VIA) }
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
     * @param grid draw the [withAimGrid] overlay. Off only for a capture that
     *   is not itself shown to the brain — [lookRegion] takes a full shot and
     *   then crops it, and a crop carrying the full screen's grid lines and
     *   cell labels would put two contradictory grids on one image.
     */
    fun screenshot(reason: String = "tool", grid: Boolean = true): JSONObject {
        // Settle before capture: images loading and lists settling MOVE the
        // things a screenshot is about to be used to aim at — a shot taken
        // mid-settle gives the brain targets that have already shifted by
        // the time it taps (the "aimed at A, screen became B" miss). Same
        // stability criterion as dumpUiTree: two consecutive tree signatures
        // agree. Cheap when still (one extra fingerprint pass), capped when
        // genuinely animated — the shot is taken anyway and `still_moving`
        // says so, rather than pretending.
        runCatching {
            val first = screenFingerprint()
            // A slow app cannot be fingerprinted to a stable answer, and each
            // try would cost a full budget — take the picture as it is.
            if (first.optBoolean("cut")) return@runCatching
            var prev = first.optInt("sig", -1)
            var tries = 0
            while (tries < STABLE_MAX_TRIES) {
                Thread.sleep(STABLE_POLL_MS)
                val again = screenFingerprint()
                if (again.optBoolean("cut")) return@runCatching
                val sig = again.optInt("sig", -2)
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
        awaitCaptureGap()
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
                            // The grid goes on the downscaled copy and only
                            // there: this PNG is what the brain reads a cell
                            // off, so the lines must be drawn at the
                            // resolution it will be looking at. `bmp` is left
                            // untouched for the same reason it was never ours
                            // to keep — it belongs to the hardware buffer.
                            val gridded = if (grid) withAimGrid(outBmp, zoom = false) else outBmp
                            val bos = ByteArrayOutputStream()
                            gridded.compress(Bitmap.CompressFormat.PNG, 100, bos)
                            pngBytes = bos.toByteArray()
                            if (gridded !== outBmp) gridded.recycle()
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
                // The grid, named. A model that has to infer the labels' meaning
                // from the picture alone may never use them at all, and the
                // whole point is to take the rescaling arithmetic out of its
                // hands.
                .also {
                    if (grid) it.put(
                        "aim_grid",
                        "This image carries an 8x12 aim grid, labelled A1 (top-left) to " +
                            "${'A' + GRID_COLS - 1}$GRID_ROWS (bottom-right), a letter per " +
                            "column and a number per row; each label is printed at the " +
                            "CENTRE of its cell. To tap something, name the cell it sits in " +
                            "plus which third of that cell it is in — {\"cell\": \"D7\", " +
                            "\"part\": \"bottom-right\"} on tap_by_coordinates / " +
                            "long_press_by_coordinates (part is one of " +
                            "${PARTS.joinToString("/")}; omit it for the centre) — and the " +
                            "device converts it to the right pixels itself. For anything " +
                            "not clearly bigger than a cell (an icon, a ✕ close button), " +
                            "first send the same cell and part to zoom_screen_region: it " +
                            "shows a red crosshair where the tap would land, so you can " +
                            "confirm it or re-aim off the magnified image. The grid lines " +
                            "and labels are drawn by the device and are not part of the app.",
                    )
                }
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
                        val tree = dumpOnce(false, WalkBudget(ATTACHED_TREE_MS))
                        if (tree.optInt("nodes_useful", 0) > 0) {
                            rebuildIndexAndList(tree)
                            val elements = tree.optString("elements")
                            if (elements.isNotEmpty()) {
                                it.put("elements", elements)
                                    .put("nodes_useful", tree.optInt("nodes_useful"))
                                    .put("aim_hint",
                                        "Prefer tap_id(e) / type(id) with these elements — " +
                                        "system-computed, always accurate. Pixel-aiming " +
                                        "from the image is the fallback, not the default." +
                                        if (tree.optBoolean("walk_cut")) {
                                            " The list is PARTIAL: this app answered too slowly " +
                                                "to read it all, so aim at what is missing from the image."
                                        } else "")
                            }
                        }
                    }
                }
        } finally {
            lastCaptureAt.set(SystemClock.uptimeMillis())
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
    fun lookRegion(nx: Int, ny: Int, w: Int, h: Int, mark: IntArray? = null): JSONObject {
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
        val full = screenshot("look_region", grid = false)
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
            // Region bounds in the 0-1000 space the brain speaks — computed
            // before the grid so the recorded zoom describes the screen
            // rectangle that was actually magnified.
            val relLeft = (left * 1000f / screenW).toInt()
            val relTop = (top * 1000f / screenH).toInt()
            val relRight = (right * 1000f / screenW).toInt()
            val relBottom = (bottom * 1000f / screenH).toInt()
            // Same grid, same 8×12, drawn on a picture of a region ~120 units
            // wide: each cell here is ~15 screen units against the ~125 of a
            // full shot, which is what makes two steps enough. Remembered so
            // `on="zoom"` and a lowercase cell can be turned back into screen
            // coordinates without the brain doing the arithmetic itself.
            zoomRegion = intArrayOf(relLeft, relTop, relRight, relBottom)
            zoomAtMs = SystemClock.uptimeMillis()
            val gridded = withAimGrid(scaled, zoom = true)
            if (mark != null) {
                drawCrosshair(
                    gridded,
                    (mark[0] - relLeft) * outW.toFloat() / (relRight - relLeft).coerceAtLeast(1),
                    (mark[1] - relTop) * outH.toFloat() / (relBottom - relTop).coerceAtLeast(1),
                )
            }
            val bos = ByteArrayOutputStream()
            gridded.compress(Bitmap.CompressFormat.PNG, 100, bos)
            val outBytes = bos.toByteArray()
            region.recycle()
            if (gridded !== scaled) gridded.recycle()
            if (scaled !== region) scaled.recycle()
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
                // The arithmetic above still works and is still documented,
                // but it is no longer what the brain is asked to do: this is
                // the magnified picture, so its labels are lowercase and a tap
                // may name one of them with no conversion at all.
                .put(
                    "zoom_hint",
                    "This image is a MAGNIFIED REGION, and its grid is lowercase " +
                        "(a1..h12) so a label can never be confused with a cell of the " +
                        "full screen. Each label is printed at the CENTRE of its cell. " +
                        "Name one of these labels, plus which third of it the target is " +
                        "in — {\"cell\": \"d7\", \"part\": \"top-left\", \"on\": \"zoom\"} " +
                        "on tap_by_coordinates / long_press_by_coordinates — and the " +
                        "device converts it for you; no arithmetic, and none of the " +
                        "scale_x/scale_y math is needed. Pick the cell the target sits " +
                        "IN, not the label nearest it.")
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Whether the window in front reports itself as visible to us **right
     * now** — the one question [net.kuafuai.andee.net.CommandDispatcher]'s fold
     * has to be able to wait on.
     *
     * A fullscreen overlay of ours occludes the app, and every node of an
     * occluded window answers `isVisibleToUser == false`; [buildNode] drops
     * those before it counts them, so the whole tree reads as empty. Folding
     * the card fixes it, but `updateViewLayout` only *asks* — the system
     * recomputes occlusion a frame or two later, measured here at ~140 ms after
     * the fold call has already returned. A dump that beats that recomputation
     * gets zero nodes from a healthy screen.
     *
     * False is deliberately also the answer when there is no root at all: the
     * caller polls to a deadline, and a screen mid-transition is worth the same
     * short wait as one still being uncovered.
     */
    fun activeWindowVisible(): Boolean {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return false
        return try {
            runCatching { root.isVisibleToUser }.getOrDefault(false)
        } finally {
            runCatching { root.recycle() }
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
        val budget = WalkBudget(TREE_BUDGET_MS)
        val firstStart = SystemClock.uptimeMillis()
        var out = dumpOnce(verbose, budget)
        val firstMs = SystemClock.uptimeMillis() - firstStart
        var waited = 0L
        while (!verbose && out.optInt("nodes_useful", 0) == 0 && waited < SETTLE_BUDGET_MS && !budget.spent()) {
            val age = System.currentTimeMillis() - lastContentEventMs.get()
            if (lastContentEventMs.get() != 0L && age >= QUIET_WINDOW_MS) break
            try { Thread.sleep(SETTLE_POLL_MS) } catch (_: InterruptedException) { break }
            waited += SETTLE_POLL_MS
            out = dumpOnce(verbose, budget)
        }
        val helped = !verbose && out.optBoolean("withheld") && TreeHelper.wake(service)
        if (helped) out = awaitHelper(out)
        // Stability pass — the half the old loop never covered: "has content"
        // is not "has stopped moving". A screen mid-animation (list settling,
        // pager sliding, splash fading) dumps real elements at positions that
        // are already stale by the time the brain acts on them. Re-dump until
        // two consecutive passes agree on the tree signature, capped tight:
        // this runs on EVERY dump, so the cost when the screen was already
        // still is exactly one extra pass — and that pass is also what
        // guarantees the eid index the brain is about to read was built from
        // the settled geometry, not a mid-flight one.
        //
        // Skipped for a slow app: each re-dump is another full walk, and a
        // re-dump cut short by the budget is kept out of `out` — a partial
        // tree must never replace a whole one just because it came second.
        if (!verbose && out.optInt("nodes_useful", 0) > 0 &&
            !out.optBoolean("walk_cut") && firstMs < STABLE_SKIP_MS
        ) {
            var prevSig = treeSignature(out)
            var stableTries = 0
            while (waited < SETTLE_BUDGET_MS && stableTries < STABLE_MAX_TRIES) {
                try { Thread.sleep(STABLE_POLL_MS) } catch (_: InterruptedException) { break }
                waited += STABLE_POLL_MS
                val again = dumpOnce(verbose, budget)
                if (again.optBoolean("walk_cut")) break
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
        if (helped) out.put("tree_helper_enabled", true)
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
        // A walk the budget cut short is the same case in part: whatever it
        // did not reach exists only in the pixels.
        val walkCut = out.optBoolean("walk_cut")
        if (!verbose && (withShot || treeEmpty || walkCut)) {
            val reason = when {
                withShot -> "with_shot"
                treeEmpty -> "auto_empty"
                else -> "auto_partial"
            }
            runCatching { out.put("shot", capture(reason)) }
                .onFailure { out.put("shot_error", it.message ?: "capture failed") }
            if (!withShot) out.put("shot_auto", true)
        }
        out.put("ts", ts)
        annotate(out, verbose)
        return out
    }

    /**
     * Re-dump after [TreeHelper] switched the system's helper on. The setting
     * is written at once but the service binds asynchronously, and the app
     * only hands over its tree once it has — so this polls rather than sleeps.
     * Its own budget: the wait is not walking, and the first dump may already
     * have spent most of [TREE_BUDGET_MS].
     */
    private fun awaitHelper(first: JSONObject): JSONObject {
        runCatching {
            ChatHistory.addAssistant(AppLocale.str(service, R.string.tree_helper_enabled_row))
        }
        var out = first
        val until = SystemClock.uptimeMillis() + HELPER_WAIT_MS
        while (SystemClock.uptimeMillis() < until) {
            try { Thread.sleep(HELPER_POLL_MS) } catch (_: InterruptedException) { break }
            out = dumpOnce(false, WalkBudget(TREE_BUDGET_MS))
            if (!out.optBoolean("withheld")) break
        }
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

    private fun dumpOnce(verbose: Boolean, budget: WalkBudget): JSONObject {
        val active = activeRoot()
        val out = if (active != null) {
            try {
                val stats = Stats(budget)
                val body = buildNode(active, verbose, stats) ?: JSONObject().put("empty", true)
                withStats(body, active.packageName?.toString() ?: "", stats).also {
                    if (isWithheld(active)) it.put("withheld", true)
                }
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
        if (!verbose && out.optInt("nodes_useful", 0) == 0 && !budget.spent()) tryOtherWindows(out, budget)
        // A status bar (SystemUI) can be the active window mid-transition and
        // hand us ~27 "useful" status icons — real nodes, wrong screen. The
        // user-facing app is always a better source when it has ANY content.
        // Only run this correction when we did NOT just come from
        // tryOtherWindows with a genuinely better window.
        if (!verbose && out.optString("pkg") == "com.android.systemui" && !budget.spent()) tryOtherWindows(out, budget)

        return out
    }

    /**
     * The app handed accessibility a root with no class, no size and no
     * children — a placeholder, not a pane.
     *
     * Measured on WeChat 8.0.78 (Xiaomi Pad 5, 2026-10-08): the system's own
     * `uiautomator dump` got the same single blank node with **no**
     * accessibility service enabled and a freshly started WeChat, while
     * Settings dumped 89 nodes. With MIUI's `MiuiEnhanceTBService` enabled
     * alongside us the same screen dumped 219 nodes, and removing it blanked the
     * root again — reproducible both ways, and the real "有时候可以有时候不行".
     * So it is the app refusing every client, not our
     * connection going stale — and the two must not share a hint, because the
     * stale one sends the user to toggle Andee, which cannot help here.
     * Occlusion (our card) and self-drawn panes are both distinguishable: they
     * keep real bounds and their containers.
     */
    private fun isWithheld(root: AccessibilityNodeInfo): Boolean = runCatching {
        if (root.childCount != 0 || root.packageName?.toString() == service.packageName) return@runCatching false
        val r = android.graphics.Rect()
        root.getBoundsInScreen(r)
        r.isEmpty && root.className.isNullOrEmpty()
    }.getOrDefault(false)

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
    private fun tryOtherWindows(out: JSONObject, budget: WalkBudget) {
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
                if (budget.spent()) break
                val stats = Stats(budget)
                val root = runCatching { rootOf(w) }.getOrNull()
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
            "error", "empty", "withheld", "tree_helper_enabled",
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
        if (stats.budget.cut) body.put("walk_cut", true)
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
    private class Stats(val budget: WalkBudget = WalkBudget.UNLIMITED) {
        var total = 0
        var kept = 0
        var useful = 0
        var surface: String? = null
    }

    /**
     * Wall-clock cap on one tree walk. Every `getChild` is a binder round trip
     * answered on the *target app's* UI thread, so an app busy animating
     * answers each one late — 红果's 福利 page measured 551 nodes in 135 s,
     * ~245 ms a node — and a walk with no cap outlives the brain's tool deadline while
     * still holding the app's attention. A cut walk reports itself
     * (`walk_cut`) so the caller can say the list is partial, not absent.
     */
    private class WalkBudget(ms: Long) {
        private val until = if (ms == Long.MAX_VALUE) Long.MAX_VALUE else SystemClock.uptimeMillis() + ms
        var cut = false
            private set

        fun spent(): Boolean {
            if (!cut && SystemClock.uptimeMillis() >= until) cut = true
            return cut
        }

        companion object {
            val UNLIMITED get() = WalkBudget(Long.MAX_VALUE)
        }
    }

    /**
     * API 33+ lets one round trip carry up to 50 descendants back with the
     * node asked for. `UNINTERRUPTIBLE` is the half that matters for a walk:
     * by default the app abandons the prefetch the moment our next request
     * arrives, and a depth-first walk sends the next request immediately —
     * so most nodes came back one IPC each. Older versions keep the plain call.
     */
    private fun childOf(node: AccessibilityNodeInfo, i: Int): AccessibilityNodeInfo? =
        if (android.os.Build.VERSION.SDK_INT >= 33) node.getChild(i, PREFETCH_WALK) else node.getChild(i)

    private fun activeRoot(): AccessibilityNodeInfo? =
        if (android.os.Build.VERSION.SDK_INT >= 33) service.getRootInActiveWindow(PREFETCH_WALK)
        else service.rootInActiveWindow

    private fun rootOf(w: AccessibilityWindowInfo): AccessibilityNodeInfo? =
        if (android.os.Build.VERSION.SDK_INT >= 33) w.getRoot(PREFETCH_WALK) else w.root

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
     * The branches are not all the same verdict. Only the first four are
     * genuinely empty; the last has nodes that simply have no names, and since
     * [rebuildIndexAndList] started rendering nesting and centres for every
     * node rather than sorting the labelled ones to the top, that case still
     * has a usable outline and must not be told to tap by coordinate.
     *
     * [reviveNote] is appended to the two genuinely-empty branches only — the
     * two that mean "no nodes AT ALL" and have no other explanation. That is
     * the whole discrimination: a self-drawn pane still reports its containers,
     * so a mini-program never reaches those branches and never nags the user
     * about restarting anything. The two branches ahead of them are the cases
     * where a window of *ours* explains the zero, and they must not carry it —
     * that note asks for a permission toggle, and asking for one when nothing
     * is wrong is how this last went wrong.
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

            // Same zero, a different window of ours, and a different answer:
            // the big card is not focusable, so the app below is still active
            // and still has a root — it is merely *covered*, and an occluded
            // window reports every node invisible. Nothing about accessibility
            // is wrong here, so this has to come ahead of [reviveNote] too.
            total == 0 && net.kuafuai.andee.ui.FullscreenCard.covering ->
                out.put(
                    "hint",
                    "Andee's own card is unfolded over the whole screen, so the app behind it " +
                        "is covered and reports every node as not visible — that is why this is " +
                        "empty, and nothing is broken. Folding the card is automatic before any " +
                        "screen tool, so retry this call once and it should read normally. Do " +
                        "not tell the user to restart anything, and do not tap blindly.",
                )

            // Ahead of the two [reviveNote] branches: the app worked before,
            // so they would send the user off to toggle Andee — which is
            // exactly what they did, to no effect. See [isWithheld].
            useful == 0 && out.optBoolean("withheld") -> {
                val app = out.optString("pkg").ifEmpty { "This app" }
                val helper = if (out.optBoolean("tree_helper_enabled")) {
                    "Andee has just switched that helper on itself and the app has not picked " +
                        "it up yet — the next dump may read normally, so retry once before " +
                        "giving up on the list; if it is still blank, the user may need to " +
                        "reopen the app. "
                } else {
                    "Andee could not switch it on here, and the user cannot either: it is a " +
                        "hidden companion MIUI only runs alongside TalkBack, and TalkBack " +
                        "would read the whole screen aloud and change how every tap works. So " +
                        "do not send the user to look for any setting — if it matters, say " +
                        "once that in this app you are working from the picture, and carry on. "
                }
                out.put(
                    "hint",
                    "`$app` is withholding its UI from accessibility: its window hands every " +
                        "accessibility client — the system's own uiautomator included — a blank " +
                        "placeholder with no size and no children. That is the app's choice, not a " +
                        "fault on this device; switching Andee off and on does not change it, so " +
                        "do not ask the user to. What it does depend on is which *other* " +
                        "accessibility services are on: WeChat was measured exposing its full " +
                        "tree only while MIUI's TalkBack helper (MiuiEnhanceTalkback) was also " +
                        "enabled. " + helper + "It covers " +
                        "this whole app, not just this screen — so backing out to find a readable " +
                        "page will not help either. Work from $eyes: find the target in the image " +
                        "and use tap_by_coordinates / swipe_by_coordinates (0-1000), checking the " +
                        "result in the screenshot each tap returns. type_text and submit_input " +
                        "still work on a focused field.",
                )
            }

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
        // Independent of the verdict above: a cut walk can still have produced
        // a perfectly good partial list, and the model needs both facts.
        if (out.optBoolean("walk_cut")) {
            out.put(
                "walk_note",
                "This app answered accessibility too slowly (it is busy animating), so reading " +
                    "stopped after ${TREE_BUDGET_MS / 1000}s with only part of the screen listed. " +
                    "The e-numbers above are real and tappable; anything you see in $eyes but not " +
                    "in the list was simply not reached — aim at it with tap_by_coordinates. " +
                    "Calling this tool again will be just as slow. Nothing is broken; do not tell " +
                    "the user you cannot see the screen.",
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
     *      Spacers, dividers, dots — unless it [looksLikeIcon].
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
            if (st != null && st.budget.spent()) break
            val child = childOf(node, i) ?: continue
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
            // Lynx parks hidden panels at x≈4000 and still calls them visible;
            // an e-number there would send a tap past the screen edge.
            if (kids.isEmpty() && !Rect.intersects(bounds, Rect(0, 0, screenW, screenH))) return null
            if (!hasReason && kids.isEmpty() && !looksLikeIcon(bounds)) return null   // drop decorative leaf
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
     * A nameless, non-clickable leaf that is still worth an e-number: ad SDKs
     * draw their ✕ as a bare ImageView with no desc and the click handled by
     * a parent or a touch listener, so step 5 used to delete the one control
     * the brain was looking for and leave it aiming from a screenshot.
     * Icon-sized and roughly square keeps dots, dividers and banners out;
     * fully on-screen keeps out nodes caught mid-animation with bounds that
     * would hand the brain an e-number it cannot hit.
     */
    private fun looksLikeIcon(b: Rect): Boolean {
        if (b.left < 0 || b.top < 0 || b.right > screenW || b.bottom > screenH) return false
        val d = service.resources.displayMetrics.density
        val w = b.width() / d
        val h = b.height() / d
        if (minOf(w, h) < ICON_MIN_DP || maxOf(w, h) > ICON_MAX_DP) return false
        return maxOf(w, h) <= ICON_MAX_ASPECT * minOf(w, h)
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

        /** See [childOf]. Compile-time ints, only ever passed on API 33+. */
        @android.annotation.SuppressLint("InlinedApi")
        private const val PREFETCH_WALK = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or
            AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE

        /**
         * Walk caps — see [WalkBudget]. A whole `get_screen_element`, re-dumps
         * included, fits in [TREE_BUDGET_MS]; a first pass slower than
         * [STABLE_SKIP_MS] skips the stability re-dumps, which on a slow app
         * would cost a full walk each to learn what the screenshot shows anyway.
         */
        private const val TREE_BUDGET_MS = 8_000L
        private const val STABLE_SKIP_MS = 2_000L
        private const val ATTACHED_TREE_MS = 4_000L
        private const val FINGERPRINT_BUDGET_MS = 1_500L
        private const val SEARCH_BUDGET_MS = 1_500L

        /** A withheld root is 1 node; a bare canvas a handful. */
        private const val BLIND_TREE_NODES = 3
        private const val PX_COLS = 20
        private const val PX_ROWS = 32
        private const val PX_SAMPLE = 6
        /** Mean-luma delta (0-255) for a cell to count; a blinking text cursor stays under it. */
        private const val PX_CELL_DELTA = 10f
        private const val PX_MIN_CELLS = 2
        private const val STATUS_BAR_FRACTION = 0.035f
        private const val CAPTURE_GAP_MS = 350L

        /** See [awaitHelper]. Measured: the helper binds in about a second. */
        private const val HELPER_WAIT_MS = 3_000L
        private const val HELPER_POLL_MS = 300L

        /** The relative coordinate space the brain speaks: 0..1000 both axes. */
        private const val NORM_MAX = 1000

        /** Screenshot long side after downscale (generic_bridge value). */
        private const val SHOT_MAX_SIDE = 1280

        /** Aim-grid columns. Letters run A.. — keep it ≤ 26. */
        private const val GRID_COLS = 8

        /** Aim-grid rows; rows are numbered from 1, so the last is [GRID_ROWS]. */
        private const val GRID_ROWS = 12

        /** How long a zoom image stays usable as an aiming reference. */
        private const val ZOOM_STALE_MS = 180_000L

        /** The nine thirds of a cell a tap may name, in reading order. */
        val PARTS = listOf(
            "top-left", "top", "top-right",
            "left", "center", "right",
            "bottom-left", "bottom", "bottom-right",
        )

        // Aliases are the names a model reaches for when it is not looking at
        // the schema — a Chinese answer says 右下, not bottom-right.
        private val PART_THIRDS: Map<String, IntArray> = buildMap {
            PARTS.forEachIndexed { i, p -> put(p, intArrayOf(i % 3, i / 3)) }
            listOf("左上", "上", "右上", "左", "中", "右", "左下", "下", "右下")
                .forEachIndexed { i, p -> put(p, intArrayOf(i % 3, i / 3)) }
            put("centre", intArrayOf(1, 1)); put("middle", intArrayOf(1, 1))
            put("中间", intArrayOf(1, 1)); put("中心", intArrayOf(1, 1))
            put("top-center", intArrayOf(1, 0)); put("bottom-center", intArrayOf(1, 2))
            put("center-left", intArrayOf(0, 1)); put("center-right", intArrayOf(2, 1))
            put("left-top", intArrayOf(0, 0)); put("right-top", intArrayOf(2, 0))
            put("left-bottom", intArrayOf(0, 2)); put("right-bottom", intArrayOf(2, 2))
        }

        /**
         * Hard ceiling on outline lines. Generous — it is a safety valve for a
         * pathological list view, not a budget. Below it nothing is dropped,
         * because on this screen the model cannot tell "there is no such
         * control" from "we did not print it".
         */
        private const val MAX_ELEMENT_LINES = 300
        private const val ICON_MIN_DP = 16f
        private const val ICON_MAX_DP = 120f
        private const val ICON_MAX_ASPECT = 2f

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
