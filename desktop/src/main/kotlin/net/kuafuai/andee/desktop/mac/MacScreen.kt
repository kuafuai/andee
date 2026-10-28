package net.kuafuai.andee.desktop.mac

import net.kuafuai.andee.desktop.DesktopBrainHost
import net.kuafuai.andee.screen.AimParts
import net.kuafuai.andee.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO

/**
 * The `screen.*` tools on a Mac, speaking the phone's protocol exactly: an
 * element outline with e-numbers, 0-1000 coordinates of the main display, an
 * 8x12 aim grid on every screenshot, zoom with a crosshair, and the same
 * guards (values outside 0-1000 are refused, the fourth tap on one spot within
 * five minutes is refused).
 *
 * What a tap does here: an e-number press uses the element's own Press action
 * when it has one (nothing can land on the wrong thing), and otherwise clicks
 * its centre. A coordinate tap is a real mouse click.
 */
class MacScreen(
    /** Hide our own windows for a gesture or a capture, and show them again after. */
    private val hideOwnWindows: (Boolean) -> Unit,
    /**
     * Called before any screen tool runs: gets the chat window off the screen, as the phone folds its
     * card. Otherwise it covers the app being driven and, when it is frontmost, takes the keystrokes.
     */
    private val yieldScreen: () -> Unit = {},
) : DesktopBrainHost.ToolProvider {

    private data class El(val eid: String, val cx: Double, val cy: Double, val press: Boolean, val editable: Boolean, val label: String)

    @Volatile private var index: Map<String, El> = emptyMap()
    @Volatile private var zoomRegion: IntArray? = null
    @Volatile private var zoomAt = 0L
    private val spots = HashMap<String, MutableList<Long>>()

    // tap_target only where a pointing model can answer it; DesktopBrainHost lists exactly what is handled.
    override fun handles(method: String) = method in METHODS && (method != "screen.tap_target" || locatorModel() != null)

    private fun locatorModel(): String? = net.kuafuai.andee.brain.Locator.modelFor(
        net.kuafuai.andee.desktop.Assistant.config(), net.kuafuai.andee.desktop.Settings.get("locator_model"),
    )

    override fun dispatch(method: String, p: JSONObject, silent: Boolean, untrusted: Boolean): Any? {
        if (!Bridge.available) throw IllegalStateException("The Mac screen bridge did not load; screen tools cannot run here.")
        if (!Bridge.axTrusted()) throw IllegalStateException(PermissionHelp.ACCESSIBILITY)
        yieldScreen()
        return when (method) {
            "screen.ui_tree" -> uiTree(p.optBoolean("with_shot", false))
            "screen.tap_id" -> tapId(req(p, "eid"), long = false)
            "screen.long_press_id" -> tapId(req(p, "eid"), long = true)
            "screen.tap_target" -> tapTarget(req(p, "target"), p.optBoolean("long", false))
            "screen.tap" -> tapAim(p, long = false)
            "screen.long_press" -> tapAim(p, long = true)
            "screen.swipe" -> swipe(p)
            "screen.drag" -> drag(p)
            "screen.type" -> type(p.optString("text"))
            "screen.submit_input" -> submit()
            "screen.wait" -> waitFor(p.optDouble("seconds", 2.0))
            "screen.screenshot" -> screenshot(grid = true)
            "screen.look_region" -> lookRegion(p)
            "screen.global" -> global(req(p, "action"))
            else -> throw IllegalArgumentException("unknown method $method")
        }
    }

    // ---- geometry ----------------------------------------------------------

    private val display: JSONObject get() = Bridge.display()
    private fun dw() = display.getDouble("w")
    private fun dh() = display.getDouble("h")
    private fun toPx(nx: Int, ny: Int) = (nx / 1000.0 * dw()) to (ny / 1000.0 * dh())
    private fun toNorm(x: Double, y: Double) = (x / dw() * 1000).toInt().coerceIn(0, 1000) to (y / dh() * 1000).toInt().coerceIn(0, 1000)

    private fun requireNorm(v: Int, name: String) {
        require(v in 0..1000) { "$name=$v is outside 0-1000. Coordinates are relative to the screen, 0 to 1000; read them off the grid." }
    }

    // ---- the outline ---------------------------------------------------------

    private fun uiTree(withShot: Boolean): JSONObject {
        val raw = Bridge.axTree(1500)
        val lines = mutableListOf<String>()
        val idx = LinkedHashMap<String, El>()
        var total = 0
        var n = 0
        var covered = 0
        fun visit(o: JSONObject, depth: Int) {
            total++
            val f = o.optJSONArray("frame")
            val role = o.optString("role").removePrefix("AX")
            val label = listOf("title", "desc", "value", "placeholder", "help").map { o.optString(it) }.firstOrNull { it.isNotBlank() }
                ?: o.optString("id").takeIf { it.isNotBlank() }?.let { "#$it" } ?: ""
            val visible = f != null && f.getDouble(2) > 1 && f.getDouble(3) > 1 &&
                f.getDouble(0) < dw() && f.getDouble(1) < dh() && f.getDouble(0) + f.getDouble(2) > 0 && f.getDouble(1) + f.getDouble(3) > 0
            val press = o.optBoolean("press")
            val editable = o.optBoolean("editable") || role in setOf("TextField", "TextArea", "SearchField", "ComboBox")
            // Something else is what a click at its centre would reach (see `reachable` in AndeeMac.swift): a closed
            // menu's items, a row scrolled out of its panel. Listing it invites a tap that lands on whatever is there.
            val hidden = o.optBoolean("covered")
            if (hidden && visible) covered++
            val useful = visible && !hidden && !o.optBoolean("disabled") &&
                (press || editable || label.isNotBlank() || role in setOf("Button", "Link", "CheckBox", "RadioButton", "PopUpButton", "MenuButton", "Tab", "Cell", "Row"))
            val kids = o.optJSONArray("children")
            val decorative = role in setOf("Group", "ScrollArea", "SplitGroup", "Unknown", "LayoutArea", "") && label.isBlank() && !press
            var d = depth
            if (useful && !(decorative && kids != null)) {
                val eid = "e${n++}"
                val cx = f!!.getDouble(0) + f.getDouble(2) / 2
                val cy = f.getDouble(1) + f.getDouble(3) / 2
                val (nx, ny) = toNorm(cx, cy)
                idx[eid] = El(eid, cx, cy, press, editable, label)
                if (lines.size < MAX_LINES) {
                    lines += "  ".repeat(depth) + "$eid${if (press) "*" else ""}${if (editable) "✎" else ""}|$role|${label.take(80).replace('\n', ' ')}|[$nx,$ny]"
                }
                d = depth + 1
            }
            if (kids != null) for (i in 0 until kids.length()) visit(kids.getJSONObject(i), d)
        }
        raw.optJSONObject("tree")?.let { visit(it, 0) }
        index = idx
        val out = JSONObject().put("app", raw.optString("app")).put("bundle", raw.optString("bundle"))
            .put("window", raw.optString("window_title")).put("nodes_total", total).put("elements_listed", idx.size)
            .put("elements", lines.joinToString("\n"))
        if (idx.size > MAX_LINES) out.put("elements_dropped", idx.size - MAX_LINES)
        if (covered > 0) out.put("covered_not_listed", covered)
        val win = raw.optJSONObject("tree")?.optJSONArray("frame")
        val offMain = win != null && win.getDouble(2) > 1 && run {
            val l = maxOf(0.0, win.getDouble(0)); val t = maxOf(0.0, win.getDouble(1))
            val r = minOf(dw(), win.getDouble(0) + win.getDouble(2)); val b = minOf(dh(), win.getDouble(1) + win.getDouble(3))
            val inside = maxOf(0.0, r - l) * maxOf(0.0, b - t)
            inside < 0.5 * win.getDouble(2) * win.getDouble(3)
        }
        // A window of real size with nothing in it but its own title-bar buttons: the app draws its own UI and
        // tells accessibility nothing (Mac WeChat lists 4 elements — the window and three traffic lights).
        val blind = !offMain && idx.size <= 6 && total <= 16 && win != null && win.getDouble(2) * win.getDouble(3) > 200_000
        if (offMain) {
            out.put("other_display", true).put(
                "hint",
                "${raw.optString("app")}'s window is mostly on another display. Andee can only see and click the main display, " +
                    "so this list and any screenshot do not show it. Ask the user to move the window to the main screen; do not aim at it.",
            )
        } else if (idx.isEmpty() || blind) {
            out.put("empty", true).put("tree_withheld", blind).put(
                "hint",
                "This app gives the accessibility layer almost nothing (common for apps that draw their own UI, like " +
                    "WeChat for Mac). Work from the screenshot attached here: aim with the grid and tap_by_coordinates, " +
                    "checking each result. A keyboard shortcut or the app's menu bar is often more reliable than a click.",
            )
            if (Bridge.screenCaptureAllowed()) out.put("shot_auto", true).put("png_base64", shot(grid = true)) else out.put("screenshot_blocked", SCREEN_RECORDING_NOTE)
        } else if (withShot) {
            if (Bridge.screenCaptureAllowed()) out.put("png_base64", shot(grid = true)) else out.put("screenshot_blocked", SCREEN_RECORDING_NOTE)
        }
        out.put("aim_note", "Coordinates are [x,y] in 0-1000 of the main display. * = has a Press action; ✎ = editable.")
        return out
    }

    // ---- taps ---------------------------------------------------------------

    private fun tapId(eid: String, long: Boolean): JSONObject {
        val e = index[eid] ?: throw IllegalArgumentException(
            "unknown element $eid — the screen has changed or the number is from an older list. Re-dump with get_screen_element; do not guess.",
        )
        val (nx, ny) = toNorm(e.cx, e.cy)
        stillThere(e)
        // A Press action is not a pointer event and the hit test already skips our windows: nothing to step aside for.
        val before = probe()
        val pressed = !long && e.press && Bridge.axPressAt(e.cx, e.cy)
        if (!pressed) click(e.cx, e.cy, long)
        return afterTap(JSONObject().put("completed", true).put("eid", eid).put("label", e.label)
            .put("via", if (pressed) "press_action" else if (long) "right_click" else "click").put("x", nx).put("y", ny), nx, ny, before)
    }

    private fun tapAim(p: JSONObject, long: Boolean): JSONObject {
        val (nx, ny, label) = resolveAim(p)
        guardSpot(nx, ny)
        val (x, y) = toPx(nx, ny)
        val before = probe()
        click(x, y, long)
        return afterTap(JSONObject().put("completed", true).put("x", nx).put("y", ny).put("aimed_at", label)
            .put("via", if (long) "right_click" else "click"), nx, ny, before)
    }

    /**
     * The brain describes, a model trained to point locates, the device clicks — see `Locator`. The picture it
     * locates on is the plain screenshot (no grid: rulers are for an eye that counts cells), at the size the
     * brain's own screenshots have, so "0-1000 of the image" is 0-1000 of the main display.
     */
    private fun tapTarget(target: String, long: Boolean): JSONObject {
        val model = locatorModel() ?: throw IllegalStateException("No locating model is available on this backend. Use tap_screen_element or tap_by_coordinates (zoom first).")
        if (!Bridge.screenCaptureAllowed()) throw IllegalStateException(SCREEN_RECORDING_NOTE)
        val img = scale(capture(SHOT_MAX_SIDE), SHOT_MAX_SIDE)
        val loc = net.kuafuai.andee.brain.Locator(net.kuafuai.andee.desktop.Assistant.config(), model) { warn, msg ->
            if (warn) Log.w("Locator", msg) else Log.i("Locator", msg)
        }.locate(png(img), target)
        return when (loc) {
            is net.kuafuai.andee.brain.Locator.Result.Found -> {
                guardSpot(loc.x, loc.y)
                val (x, y) = toPx(loc.x, loc.y)
                val before = probe()
                click(x, y, long)
                afterTap(
                    JSONObject().put("completed", true).put("located", "${loc.x},${loc.y}").put("target", target).put("saw", loc.seen)
                        .put("via", if (long) "right_click" else "click"),
                    loc.x, loc.y, before,
                )
            }
            net.kuafuai.andee.brain.Locator.Result.Absent -> JSONObject().put("found", false).put("target", target).put(
                "note",
                "The locating model looked at the current screen and does not see this. Nothing was clicked. Look at the screen " +
                    "and describe it differently, or scroll — do not send the same words again.",
            )
            is net.kuafuai.andee.brain.Locator.Result.Failed -> JSONObject().put("found", false).put("target", target)
                .put("error", loc.why).put("note", "Nothing was clicked. Fall back to tap_by_coordinates with a zoom.")
        }
    }

    /**
     * The listed element is no longer what a click at its centre reaches — the list is older than the screen.
     * The phone re-finds the node live before every e-number tap; this is the Mac's version of that check: the
     * hit element or one of its containers must still carry the label the list showed.
     */
    private fun stillThere(e: El) {
        if (e.label.isBlank()) return
        val at = runCatching { Bridge.axAt(e.cx, e.cy) }.getOrNull() ?: return
        if (!at.optBoolean("found")) return
        val chain = at.optJSONArray("chain") ?: return
        val labels = (0 until chain.length()).map { chain.getJSONObject(it).optString("label") }
        val want = e.label.take(40)
        if (labels.none { it.isNotBlank() && (it.contains(want) || want.contains(it.take(40))) }) {
            throw IllegalStateException(
                "${e.eid} (\"${e.label.take(30)}\") is not what is at that spot any more — the screen changed since the list " +
                    "(now there: \"${labels.firstOrNull { it.isNotBlank() }?.take(30) ?: "unlabelled"}\"). Re-dump with get_screen_element and pick again.",
            )
        }
    }

    /** A tiny capture to tell, after a tap, whether anything on the screen moved. Null without the grant. */
    private fun probe(): BufferedImage? =
        if (Bridge.screenCaptureAllowed()) runCatching { capture(PROBE_SIDE) }.getOrNull() else null

    /** Share of probe pixels that differ noticeably — a tap that opened, closed or highlighted something. */
    private fun changed(a: BufferedImage, b: BufferedImage): Double {
        if (a.width != b.width || a.height != b.height) return 1.0
        var diff = 0
        var n = 0
        for (y in 0 until a.height step 2) for (x in 0 until a.width step 2) {
            val p = a.getRGB(x, y); val q = b.getRGB(x, y)
            val d = Math.abs((p shr 16 and 255) - (q shr 16 and 255)) + Math.abs((p shr 8 and 255) - (q shr 8 and 255)) + Math.abs((p and 255) - (q and 255))
            if (d > 48) diff++
            n++
        }
        return diff.toDouble() / n
    }

    /**
     * Taps are followed by a fresh picture, as on the phone, so the next step reads the result — and, as on the
     * phone, a fact about it: whether the screen changed at all, and a ring where the click landed. Without
     * those the model judged every click by eye, and in the traces it repeatedly took a menu for open that was
     * not. The wait is a poll (the page may take a moment to answer), not a fixed sleep.
     */
    private fun afterTap(o: JSONObject, nx: Int = -1, ny: Int = -1, before: BufferedImage? = null): JSONObject {
        var moved: Double? = null
        if (before != null) {
            val until = System.currentTimeMillis() + SETTLE_MAX_MS
            Thread.sleep(150)
            while (true) {
                val now = runCatching { capture(PROBE_SIDE) }.getOrNull() ?: break
                moved = changed(before, now)
                if (moved > CHANGED_SHARE || System.currentTimeMillis() >= until) break
                Thread.sleep(150)
            }
            // Something is moving: give it a beat to settle before the picture the model will read.
            if ((moved ?: 0.0) > CHANGED_SHARE) Thread.sleep(250)
        } else {
            Thread.sleep(400)
        }
        if (moved != null) {
            o.put("screen_changed", moved > CHANGED_SHARE)
            if (moved <= CHANGED_SHARE) o.put(
                "change_note",
                "Nothing on the screen changed after this click. It most likely missed (or hit something inert). Do not assume a menu " +
                    "or page opened — look at after_shot, and zoom in before aiming again.",
            )
        }
        if (Bridge.screenCaptureAllowed()) o.put("after_shot", JSONObject().put("png_base64", shot(grid = true, ringAt = if (nx >= 0) nx to ny else null)))
        o.put("front_app", Bridge.frontmost().optString("name"))
        return o
    }

    private fun resolveAim(p: JSONObject): Triple<Int, Int, String> {
        val on = p.optString("on").lowercase()
        val cell = p.optString("cell").trim()
        if (cell.isNotEmpty()) {
            val zoom = on == "zoom" || (on.isEmpty() && cell.first().isLowerCase())
            val (col, row) = parseCell(cell)
            val part = p.optString("part").trim().lowercase().replace('_', '-').replace(' ', '-')
            val (fx, fy) = if (part.isEmpty()) intArrayOf(1, AimParts.MID_ROW).let { it[0] to it[1] }
            else (AimParts.cellOf(part) ?: throw IllegalArgumentException("unknown part \"$part\" — one of ${AimParts.PARTS.joinToString()}")).let { it[0] to it[1] }
            val lx = ((col * AimParts.COLS + fx) * 2 + 1) * 1000 / (2 * GRID_COLS * AimParts.COLS)
            val ly = ((row * AimParts.ROWS + fy) * 2 + 1) * 1000 / (2 * GRID_ROWS * AimParts.ROWS)
            return if (zoom) zoomToScreen(lx, ly).let { Triple(it.first, it.second, "zoom cell $cell $part") }
            else Triple(lx, ly, "cell $cell $part")
        }
        val x = p.optInt("x", Int.MIN_VALUE)
        val y = p.optInt("y", Int.MIN_VALUE)
        require(x != Int.MIN_VALUE && y != Int.MIN_VALUE) { "give either cell (+ part) or x and y" }
        requireNorm(x, "x"); requireNorm(y, "y")
        return if (on == "zoom") zoomToScreen(x, y).let { Triple(it.first, it.second, "zoom $x,$y") } else Triple(x, y, "$x,$y")
    }

    private fun parseCell(cell: String): Pair<Int, Int> {
        val s = cell.uppercase()
        val col = s[0] - 'A'
        val row = s.substring(1).toIntOrNull()?.minus(1) ?: -1
        require(col in 0 until GRID_COLS && row in 0 until GRID_ROWS) {
            "bad grid cell '$cell' — a column letter A..H and a row number 1..12, exactly as labelled on the image"
        }
        return col to row
    }

    private fun zoomToScreen(lx: Int, ly: Int): Pair<Int, Int> {
        val r = zoomRegion ?: throw IllegalStateException("on=\"zoom\" needs a zoom first — call zoom_screen_region")
        if (System.currentTimeMillis() - zoomAt > 180_000) throw IllegalStateException("that zoom is over three minutes old; take a fresh one")
        return (r[0] + lx * (r[2] - r[0]) / 1000) to (r[1] + ly * (r[3] - r[1]) / 1000)
    }

    /**
     * The phone's stuck-loop guard: a fourth tap within [SPOT_RADIUS_PT] of a spot inside five minutes is refused.
     * The radius was 120 pt — about 240 Retina pixels — which refused aim corrections converging on a small target
     * (9 of 56 coordinate taps in the traces, 4 in one turn). The phone made the same cut for the same reason.
     */
    private fun guardSpot(nx: Int, ny: Int) {
        val (x, y) = toPx(nx, ny)
        val now = System.currentTimeMillis()
        synchronized(spots) {
            spots.values.forEach { it.removeAll { t -> now - t > 300_000 } }
            val key = spots.keys.firstOrNull { k -> k.split(',').let { Math.hypot(it[0].toDouble() - x, it[1].toDouble() - y) < SPOT_RADIUS_PT } }
                ?: "${x.toInt()},${y.toInt()}"
            val list = spots.getOrPut(key) { mutableListOf() }
            if (list.size >= 3) throw IllegalStateException(
                "refused: this is the fourth tap on the same spot in five minutes. Stop, look at the screen again, and " +
                    "if it is not working, tell the user where you are stuck instead of tapping again.",
            )
            list += now
        }
    }

    // ---- swipes, typing, keys ---------------------------------------------

    /**
     * Press at the start, move to the end holding, release: a real drag. [swipe] on a Mac is a scroll — it moves
     * the content under the pointer — so anything that has to be *grabbed* (a slider, a splitter, a window by its
     * title bar, a file onto a folder, a list row, a range selection) had no tool, and the model reached for
     * shell scripts that synthesise the mouse.
     *
     * Both ends are aimed like a tap: 0-1000 numbers, or `from_target`/`to_target` words resolved by the locating
     * model when there is one. The app under the start point is brought forward first, as for a click.
     */
    private fun drag(p: JSONObject): JSONObject {
        val (fx, fy) = dragEnd(p, "from")
        val (tx, ty) = dragEnd(p, "to")
        val ms = p.optInt("duration_ms", 700).coerceIn(200, 5_000)
        val (ax, ay) = toPx(fx, fy); val (bx, by) = toPx(tx, ty)
        require(Math.hypot(bx - ax, by - ay) >= 4) { "the start and the end are the same place — a drag needs somewhere to go" }
        val before = probe()
        gesture(ax, ay) {
            Bridge.activateAt(ax, ay)
            Bridge.drag(ax, ay, bx, by, ms)
        }
        return afterTap(
            JSONObject().put("completed", true).put("from", "$fx,$fy").put("to", "$tx,$ty").put("via", "drag"),
            tx, ty, before,
        )
    }

    private fun dragEnd(p: JSONObject, side: String): Pair<Int, Int> {
        val target = p.optString("${side}_target").trim()
        if (target.isNotEmpty()) {
            val model = locatorModel() ?: throw IllegalStateException("${side}_target needs a locating model, and none is available here. Give ${side}_x and ${side}_y instead.")
            if (!Bridge.screenCaptureAllowed()) throw IllegalStateException(SCREEN_RECORDING_NOTE)
            val loc = net.kuafuai.andee.brain.Locator(net.kuafuai.andee.desktop.Assistant.config(), model) { warn, msg ->
                if (warn) Log.w("Locator", msg) else Log.i("Locator", msg)
            }.locate(png(scale(capture(SHOT_MAX_SIDE), SHOT_MAX_SIDE)), target)
            return when (loc) {
                is net.kuafuai.andee.brain.Locator.Result.Found -> loc.x to loc.y
                net.kuafuai.andee.brain.Locator.Result.Absent -> throw IllegalStateException("the locating model does not see \"$target\" on the screen. Nothing was dragged.")
                is net.kuafuai.andee.brain.Locator.Result.Failed -> throw IllegalStateException("could not locate \"$target\" (${loc.why}). Nothing was dragged; give ${side}_x and ${side}_y instead.")
            }
        }
        val x = p.optInt("${side}_x", Int.MIN_VALUE); val y = p.optInt("${side}_y", Int.MIN_VALUE)
        require(x != Int.MIN_VALUE && y != Int.MIN_VALUE) { "give ${side}_x and ${side}_y (0-1000), or ${side}_target in words" }
        requireNorm(x, "${side}_x"); requireNorm(y, "${side}_y")
        return x to y
    }

    private fun swipe(p: JSONObject): JSONObject {
        val x1 = p.getInt("x1"); val y1 = p.getInt("y1"); val x2 = p.getInt("x2"); val y2 = p.getInt("y2")
        listOf(x1 to "x1", y1 to "y1", x2 to "x2", y2 to "y2").forEach { requireNorm(it.first, it.second) }
        val (ax, ay) = toPx(x1, y1); val (bx, by) = toPx(x2, y2)
        // On a Mac a swipe that scrolls is a scroll: the content moves the way the finger did, by that distance.
        gesture(ax, ay) { Bridge.scroll(ax, ay, (bx - ax).toInt(), (by - ay).toInt()) }
        return JSONObject().put("completed", true).put("from", "$x1,$y1").put("to", "$x2,$y2").put("via", "scroll")
            .put("note", "A swipe scrolls whatever is under the start point by that distance. To drag something, use the app's own controls.")
    }

    /**
     * Put [text] in the focused field **the way a person would: select all, paste. Once.**
     *
     * It used to set the control's AXValue directly. That changes what the field *shows* and tells the page
     * nothing: web and Electron editors (X's composer, WorkBuddy) keep their own state and update it from
     * `input` events, so a field filled that way reads as empty to the app and its send button stays dead.
     * A paste arrives as the event the editor is listening for.
     *
     * 🔴 **One attempt, never a retry on "I cannot read it back".** Those same editors do not expose their
     * text to the accessibility tree, so a successful paste reads back as empty. The first version took
     * that for failure and went on to select-all-delete-retype and then to write AXValue — three edits to
     * one rich-text editor in a second. Draft.js / Lexical own a node tree, and an outside hand rewriting
     * it under them breaks it: `Failed to execute 'removeChild' on 'Node'`. An empty read-back is
     * "unverified", not "failed", and the screenshot is what settles it.
     *
     * Only a read-back that shows *different, non-empty* text counts as a miss worth a second route, and
     * even then it is keystrokes into a cleared field, never AXValue on top of a web editor.
     */
    private fun type(text: String): JSONObject {
        // Keys go to the frontmost app; if that is Andee (the user just typed to it), they would land in Andee.
        Bridge.activateTarget()
        val before = Bridge.focusedValue()
        if (!before.optBoolean("focused")) return typeBlind(text)
        // Keys go to the focused app, not to a point on screen: fading our windows changes nothing for them.
        var via = "paste"
        if (Bridge.setPasteboard(text)) {
            Bridge.key(Bridge.Keys.A, Bridge.Keys.CMD)
            Bridge.key(Bridge.Keys.V, Bridge.Keys.CMD)
        } else {
            via = "keystrokes"
            Bridge.key(Bridge.Keys.A, Bridge.Keys.CMD); Bridge.key(Bridge.Keys.DELETE); Bridge.typeText(text)
        }
        Thread.sleep(350)
        val now = Bridge.focusedValue().optString("value")
        val ok = now == text || now.contains(text)
        return when {
            ok -> JSONObject().put("performed", true).put("via", via).put("len", text.length).put("text", now.take(200))
            // Nothing readable back: a web or Electron editor hiding its text. Not a failure — and above all
            // not a reason to touch the field again.
            now.isEmpty() -> JSONObject().put("performed", true).put("unverified", true).put("via", via).put("len", text.length)
                .put("note", "Typed by $via, once. This field does not expose its text to the accessibility layer, so I cannot read it back. " +
                    "Do NOT type it again — repeating it into a web editor can corrupt it. Look at the screenshot to confirm the text is there, then press send.")
            // Different text is there: the field transformed it or something else wrote into it. Report, do not fight it.
            else -> JSONObject().put("performed", false).put("via", via).put("len", text.length)
                .put("error", "the field reads '${now.take(30)}' instead — look at the screenshot before deciding; do not just type it again")
        }
    }

    /**
     * The app in front reports no focused element to accessibility — Mac WeChat is the case that forced this.
     * Refusing here ("no field has focus") was a dead end the model could only leave through the shell
     * (`osascript` to set the clipboard and press ⌘V), and the shell is closed to a notification's turn, so
     * a message arrived that Andee could read and could not answer. The keystroke never needed the focused
     * element: ⌘V goes to whatever the front app has focused, exactly as a person's would.
     *
     * So: paste, and only paste. No ⌘A — with no idea where focus is, select-all may select the chat history
     * rather than the box. Unverified by construction, and the result says what the screenshot must show.
     */
    private fun typeBlind(text: String): JSONObject {
        // ⌘V lands in the frontmost app, and if that is Andee's own window it lands in Andee's own input.
        if (java.awt.Window.getWindows().any { it.isActive }) {
            throw IllegalStateException("Andee's own window is in front, so a paste would land in Andee. Click into the other app's input box first.")
        }
        val app = Bridge.frontmost().optString("name").ifEmpty { throw IllegalStateException("no app is in front — open the app and click into its input box first") }
        if (!Bridge.setPasteboard(text)) throw IllegalStateException("could not put the text on the pasteboard")
        Bridge.key(Bridge.Keys.V, Bridge.Keys.CMD)
        Thread.sleep(350)
        return JSONObject().put("performed", true).put("unverified", true).put("via", "paste_blind").put("len", text.length)
            .put("app", app)
            .put(
                "note",
                "$app does not tell the accessibility layer which box has focus, so I pasted into whatever it has focused " +
                    "(⌘V, once) and cannot read it back. Look at a screenshot: the text must be in the input box before you " +
                    "submit. If the box already had text, mine was added at the cursor, not in place of it. Do NOT type it " +
                    "again and do NOT use the shell to paste — this is the way to type into $app.",
            )
    }

    private fun submit(): JSONObject {
        Bridge.activateTarget()
        Bridge.key(Bridge.Keys.RETURN)
        return JSONObject().put("performed", true).put("via", "return_key")
    }

    private fun global(action: String): JSONObject {
        Bridge.activateTarget()
        when (action.lowercase()) {
            "back" -> Bridge.key(Bridge.Keys.LEFT_BRACKET, Bridge.Keys.CMD)
            "home" -> Bridge.key(Bridge.Keys.F3, Bridge.Keys.CMD)
            "recents" -> Bridge.key(Bridge.Keys.UP, Bridge.Keys.CONTROL)
            "notifications", "quick_settings" -> Bridge.open("x-apple.systempreferences:com.apple.ControlCenter-Settings.extension")
            else -> throw IllegalArgumentException("unknown action $action — back, home, recents, notifications")
        }
        return JSONObject().put("performed", true).put("action", action)
            .put("note", when (action.lowercase()) {
                "back" -> "Sent ⌘[ — Back in browsers and Finder. Many apps have no Back; prefer a visible button or Esc."
                "home" -> "Sent ⌘F3 (show desktop)."
                "recents" -> "Sent ⌃↑ (Mission Control)."
                else -> "Opened Control Center settings."
            })
    }

    private fun waitFor(seconds: Double): JSONObject {
        val ms = (seconds.coerceIn(0.0, 20.0) * 1000).toLong()
        Thread.sleep(ms)
        return uiTree(withShot = true).put("waited_ms", ms)
    }

    // ---- pictures -----------------------------------------------------------

    private fun screenshot(grid: Boolean): JSONObject {
        if (!Bridge.screenCaptureAllowed()) throw IllegalStateException(SCREEN_RECORDING_NOTE)
        return JSONObject().put("png_base64", shot(grid)).put("aim_grid", AIM_GRID_NOTE)
    }

    /** [maxSide] is the size wanted, so a plain shot is not captured at 2560 to be shrunk to 1280 again. */
    private fun capture(maxSide: Int = 2560): BufferedImage {
        val f = File.createTempFile("andee-shot", ".png")
        try {
            val ok = gestureless { Bridge.screenshot(f.path, maxSide) }
            if (!ok) throw IllegalStateException(SCREEN_RECORDING_NOTE)
            return ImageIO.read(f)
        } finally { f.delete() }
    }

    private fun shot(grid: Boolean, ringAt: Pair<Int, Int>? = null): String {
        var img = capture(SHOT_MAX_SIDE)
        img = scale(img, SHOT_MAX_SIDE)
        if (grid) drawGrid(img, zoom = false)
        ringAt?.let { (nx, ny) -> ring(img, nx * img.width / 1000.0, ny * img.height / 1000.0) }
        return png(img)
    }

    /** The magenta ring where a click landed — the one piece of evidence about aim, as on the phone. */
    private fun ring(img: BufferedImage, mx: Double, my: Double) {
        img.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val r = minOf(img.width, img.height) * 0.018
            color = Color(224, 0, 255); stroke = BasicStroke((r * 0.22).toFloat().coerceAtLeast(2f))
            drawOval((mx - r).toInt(), (my - r).toInt(), (2 * r).toInt(), (2 * r).toInt())
            fillOval((mx - 2).toInt(), (my - 2).toInt(), 4, 4)
            dispose()
        }
    }

    private fun lookRegion(p: JSONObject): JSONObject {
        if (!Bridge.screenCaptureAllowed()) throw IllegalStateException(SCREEN_RECORDING_NOTE)
        val (cx, cy, w, h, mark) = if (p.optString("cell").isNotBlank()) {
            val (nx, ny, _) = resolveAim(p)
            listOf(nx, ny, 2 * 1000 / GRID_COLS, 2 * 1000 / GRID_ROWS, 1)
        } else {
            listOf(p.getInt("x"), p.getInt("y"), p.optInt("w", 200), p.optInt("h", 300), 0)
        }
        requireNorm(cx, "x"); requireNorm(cy, "y")
        val left = (cx - w / 2).coerceIn(0, 999); val top = (cy - h / 2).coerceIn(0, 999)
        val right = (left + w).coerceAtMost(1000); val bottom = (top + h).coerceAtMost(1000)
        // Native resolution: a crop of an already-shrunk picture magnifies blur, not detail.
        val full = capture(0)
        val sx = full.width / 1000.0; val sy = full.height / 1000.0
        val sub = full.getSubimage((left * sx).toInt(), (top * sy).toInt(), ((right - left) * sx).toInt().coerceAtLeast(4), ((bottom - top) * sy).toInt().coerceAtLeast(4))
        val k = SHOT_MAX_SIDE.toDouble() / maxOf(sub.width, sub.height)
        val out = BufferedImage((sub.width * k).toInt(), (sub.height * k).toInt(), BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            drawImage(sub, 0, 0, out.width, out.height, null); dispose()
        }
        drawGrid(out, zoom = true)
        if (mark == 1) {
            val mx = (cx - left) * out.width / (right - left).toDouble(); val my = (cy - top) * out.height / (bottom - top).toDouble()
            out.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val r = minOf(out.width, out.height) * 0.045
                color = Color(224, 0, 255); stroke = BasicStroke((r * 0.14).toFloat())
                drawOval((mx - r).toInt(), (my - r).toInt(), (2 * r).toInt(), (2 * r).toInt())
                drawLine((mx - r * 1.8).toInt(), my.toInt(), (mx - r * 0.4).toInt(), my.toInt())
                drawLine((mx + r * 0.4).toInt(), my.toInt(), (mx + r * 1.8).toInt(), my.toInt())
                drawLine(mx.toInt(), (my - r * 1.8).toInt(), mx.toInt(), (my - r * 0.4).toInt())
                drawLine(mx.toInt(), (my + r * 0.4).toInt(), mx.toInt(), (my + r * 1.8).toInt())
                dispose()
            }
        }
        zoomRegion = intArrayOf(left, top, right, bottom); zoomAt = System.currentTimeMillis()
        return JSONObject().put("png_base64", png(out)).put("region", JSONObject().put("left", left).put("top", top).put("right", right).put("bottom", bottom))
            .put("zoom_hint", "This is a magnified region; its grid is lowercase (a1..h12). Aim off it with {\"cell\": \"d7\", \"part\": \"…\", \"on\": \"zoom\"}.")
    }

    private fun scale(img: BufferedImage, maxSide: Int): BufferedImage {
        val k = maxSide.toDouble() / maxOf(img.width, img.height)
        if (k >= 1) return img
        val out = BufferedImage((img.width * k).toInt(), (img.height * k).toInt(), BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(img, 0, 0, out.width, out.height, null); dispose()
        }
        return out
    }

    private fun drawGrid(img: BufferedImage, zoom: Boolean) {
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val cw = img.width / GRID_COLS.toDouble(); val ch = img.height / GRID_ROWS.toDouble()
        for (i in 0..GRID_COLS) {
            val x = (i * cw).toInt().coerceIn(0, img.width - 1)
            g.color = Color(255, 255, 255, 38); g.stroke = BasicStroke(3f); g.drawLine(x, 0, x, img.height)
            g.color = Color(0, 0, 0, 96); g.stroke = BasicStroke(1f); g.drawLine(x, 0, x, img.height)
        }
        for (i in 0..GRID_ROWS) {
            val y = (i * ch).toInt().coerceIn(0, img.height - 1)
            g.color = Color(255, 255, 255, 38); g.stroke = BasicStroke(3f); g.drawLine(0, y, img.width, y)
            g.color = Color(0, 0, 0, 96); g.stroke = BasicStroke(1f); g.drawLine(0, y, img.width, y)
        }
        // Rulers on the top and left edges only, never inside a cell: a label at a cell's centre sat on
        // whatever that cell held, and the picture the brain aimed from had its target covered.
        val size = (minOf(cw, ch) * 0.2).coerceIn(10.0, 24.0).toFloat()
        g.font = Font(Font.SANS_SERIF, Font.BOLD, size.toInt())
        val fm = g.fontMetrics
        fun mark(label: String, cx: Double, baseline: Double) {
            val w = fm.stringWidth(label)
            val pad = size * 0.25
            g.color = Color(0, 0, 0, 150)
            g.fillRoundRect((cx - w / 2 - pad).toInt(), (baseline - size * 1.02).toInt(), (w + 2 * pad).toInt(), (size * 1.32).toInt(), (size * 0.6).toInt(), (size * 0.6).toInt())
            g.color = Color(255, 255, 255, 235)
            g.drawString(label, (cx - w / 2).toInt(), baseline.toInt())
        }
        val top = size * 1.25
        for (c in 0 until GRID_COLS) { val l = ('A' + c).toString(); mark(if (zoom) l.lowercase() else l, (c + 0.5) * cw, top) }
        for (r in 0 until GRID_ROWS) {
            val l = (r + 1).toString()
            mark(l, fm.stringWidth(l) / 2.0 + size * 0.35, (r + 0.5) * ch + size * 0.35)
        }
        g.dispose()
    }

    private fun png(img: BufferedImage): String {
        val bos = ByteArrayOutputStream()
        ImageIO.write(img, "png", bos)
        return Base64.getEncoder().encodeToString(bos.toByteArray())
    }

    // ---- our own windows -----------------------------------------------------

    /** Gestures hide our windows so a click cannot land on Andee. */
    /**
     * A pointer event at (x, y): step aside only if one of our windows is
     * actually there. Hiding for every click cost ~150 ms a tap and made the
     * ball blink each time the brain touched anything anywhere on the screen.
     */
    private fun <T> gesture(x: Double, y: Double, block: () -> T): T =
        if (Bridge.ownWindowAt(x, y)) stepAside(block) else block()

    /**
     * A click, made to land: the app under the point is brought to the front first. On a Mac a click on an
     * inactive window only activates it — measured in Chrome, the first click on a text box left focus on the
     * page, so the paste that followed went nowhere while type_text reported success, and the first click on a
     * sidebar link did nothing at all. Both were in the traces of one task.
     */
    private fun click(x: Double, y: Double, right: Boolean) {
        gesture(x, y) {
            Bridge.activateAt(x, y)
            Bridge.click(x, y, right = right, holdMs = 40)
        }
    }

    private fun <T> stepAside(block: () -> T): T {
        // The hide is inside the try: if it fails half-way, the windows must still come back.
        try {
            runCatching { hideOwnWindows(true) }.onFailure { net.kuafuai.andee.util.Log.w("MacScreen", "could not step aside", it) }
            Thread.sleep(60)  // the alpha change is applied synchronously; one or two frames for the window server
            return block()
        } finally {
            runCatching { hideOwnWindows(false) }.onFailure { net.kuafuai.andee.util.Log.e("MacScreen", "could not come back", it) }
        }
    }

    /** Captures exclude our process in ScreenCaptureKit itself; nothing to hide. */
    private fun <T> gestureless(block: () -> T): T = block()

    private fun req(p: JSONObject, k: String) = p.optString(k).trim().ifEmpty { throw IllegalArgumentException("$k is required") }

    companion object {
        private const val GRID_COLS = 8
        private const val GRID_ROWS = 12
        private const val SHOT_MAX_SIDE = 1600
        private const val MAX_LINES = 300
        private const val SPOT_RADIUS_PT = 30.0
        /** Long side of the before/after probes; enough to see a menu open, cheap to capture. */
        private const val PROBE_SIDE = 480
        private const val SETTLE_MAX_MS = 1_200L
        /** Share of probe pixels that must change for a click to count as having done something. */
        private const val CHANGED_SHARE = 0.002

        val METHODS = setOf(
            "screen.ui_tree", "screen.tap_id", "screen.tap", "screen.tap_target", "screen.long_press_id", "screen.long_press",
            "screen.swipe", "screen.drag", "screen.type", "screen.submit_input", "screen.wait", "screen.look_region",
            "screen.screenshot", "screen.global",
        )

        val SCREEN_RECORDING_NOTE: String get() = PermissionHelp.SCREEN_RECORDING

        private const val AIM_GRID_NOTE =
            "This image carries an 8x12 aim grid, A1 (top-left) to H12 (bottom-right): letters run along the TOP edge and numbers " +
                "down the LEFT edge like a map's rulers, and nothing is printed inside a cell. " +
                "To click something, name its cell and which part of the cell — {\"cell\": \"D7\", \"part\": \"bottom-right\"} on " +
                "tap_by_coordinates. Andee's own windows are never in this picture. A magenta ring marks where the last click landed."

        init { Log.i("MacScreen", "screen tools registered") }
    }
}
