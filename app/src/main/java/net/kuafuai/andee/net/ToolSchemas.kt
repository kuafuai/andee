package net.kuafuai.andee.net

import net.kuafuai.andee.screen.ScreenController
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON schemas for every screen tool this body exposes. The body sends this
 * list in its `register` message so the hub can turn each entry into a
 * PluginTool the LLM sees. Keep the descriptions in sync with the equivalent
 * body_tools/ Python files — the schema wording is what the LLM reads.
 *
 * Each entry maps to a `screen.*` method in [CommandDispatcher].
 *
 * **Not every entry leaves the device.** The five `note.*` tools
 * (remember / recall / forget / todo / todos) are the brain's private
 * notebook and are marked `localOnly`; [forHub] drops them from the register
 * payload. Their text used to be Chinese because they are behavioural rules
 * ("what is worth remembering") that lived in the same language as the system
 * prompt governing them. The whole model-facing set is English now, so they
 * are too; the header of `brain/LocalPrompt.kt` is where that decision and its
 * one risk (an English prompt pulling the answer language over) are written
 * down.
 *
 * The description set follows the generic_bridge (phone.sh) philosophy:
 * the model NEVER computes coordinates when an e-number exists — it reads
 * them off the element list; coordinates (0-1000 relative) are the
 * self-drawn-screen fallback only.
 */
object ToolSchemas {

    /**
     * Whether `get_screen_element` ships a screenshot alongside the element
     * list. False: when the list has content it already says what is tappable
     * and where, and the picture is pure token cost. The device still attaches
     * one on its own when the list comes back empty — see
     * `ScreenController.dumpUiTree`, the only case where the picture is the
     * only thing the brain has.
     *
     * Lives here because the schema, the dispatcher and the Kotlin signature
     * each used to carry their own copy, and they had already drifted apart.
     */
    const val UI_TREE_WITH_SHOT_DEFAULT = false

    fun all(): JSONArray {
        val arr = JSONArray()
        for (t in TOOLS) arr.put(t)
        return arr
    }

    /**
     * The subset that may be advertised to the hub — everything except the
     * `localOnly` notebook tools.
     *
     * This is the whole of decision C ("the notebook is not visible to the
     * cloud"), and it is a one-line filter on purpose: the register payload is
     * built in exactly one place ([net.kuafuai.andee.net.BodyWsClient.onConnected]),
     * so there is no second path to keep in sync. If you are here because a
     * tool "went missing" from the hub, that is this function working.
     */
    fun forHub(): JSONArray {
        val arr = JSONArray()
        for (t in TOOLS) if (!t.optBoolean("localOnly", false)) arr.put(t)
        return arr
    }

    private val TOOLS: List<JSONObject> = listOf(
        tool(
            name = "get_screen_element",
            method = "screen.ui_tree",
            description = """
        [DEFAULT TOOL · FIRST CHOICE] Read the current screen as an OUTLINE of
        screen elements — one line per element, indented to show what contains
        what:
            e17|LinearLayout||[500,570]
              e18*|ImageView|#avatar|[62,523]
              e19*|TextView|好友3|[181,509]
              e20|TextView|这是第3条朋友圈的正文|[493,535]
              e24*|ImageView|#comment_btn|[950,621]
        `e17` is the element's number, `*` marks clickable, then class, label,
        and the element's center in 0-1000 RELATIVE coordinates (computed by
        the system, not by you).

        INDENTATION IS MEANING, and it is the answer to "which one". The lines
        indented under e17 are one post: that avatar, that name, that body and
        that comment button belong to 好友3 and to nobody else. Whenever a
        screen repeats a shape — a feed, a chat list, search results, a
        settings page — the control you want is NOT identified by its own line
        alone. Find the labeled line that names the row you want, then take
        the control from that line's OWN indent group. Never pair a name in
        one group with a button in another.

        The label column is the text, or the description, or — when an element
        has neither — the tail of its resource id with `#` in front, like
        `#comment_btn` above. A `#` value is an internal name, not words
        printed on the screen: use it to tell controls apart, never read it
        back to the user as if it were visible text.

        Whenever the goal is "find a control and tap / type / scroll / read
        on-screen text", call this tool first, then act on what it returned.

        HARD RULES:
        1) To tap an element from this list, use tap_screen_element with its
           e-number. NEVER re-compute coordinates from the bracket values or
           from a screenshot when an e-number exists.
        2) An icon button with no text and no description (Moments' comment
           bubble, an avatar, a photo) is identified by WHERE IT SITS, not by
           what it says. Read its indent group: the `#comment_btn` sitting
           under the line that says 好友3 IS 好友3's comment button, and
           tapping it by e-number is right even though you cannot name the
           icon. Only when two candidates inside the SAME group are
           indistinguishable do you need to see them — then call this tool
           again with with_shot=true: each line's [x,y] is a 0-1000 relative
           center, the same relative space as the screenshot, so the icon at
           image position (x,y) is the line with the nearest bracket
           coordinates. Then still tap by e-number, never by the coordinates
           you read off. A missing `*` does not mean "not a button": ad
           popups draw their ✕ as a bare `ImageView` line with no label and
           no `*`. If such a line sits where the screenshot shows the ✕, tap
           it by e-number — that beats aiming at it by grid cell.
        3) Chinese input: tap the input field FIRST (by e-number), then the
           VERY NEXT action must be type_text. Never re-tap the same input box
           before typing; the keyboard opens by itself.
        4) After an action, the response already tells you what the screen
           became: tap responses carry `after_shot` (image), `screen_changed`,
           and — when the tree is readable — `elements` (the POST-action
           element list with fresh e-numbers, plus `elements_hint`). Chain
           your next action straight from that; do NOT call this tool again
           just to "see the result" when the action response already carried
           it. Re-dump only when you need to scroll deeper or the response
           had no elements (self-drawn pane).
        5) Popups first: close splash ads / membership dialogs / update
           prompts / permission requests before continuing the task.

        [EMPTY LIST] nodes_useful = 0 means nothing on this pane carries a
        name. Read `nodes_total` to tell the two very different cases apart:

        - nodes_total = 0 as well: the pane draws its own pixels (mini-program,
          WebView article, game canvas) and there is no outline at all. The
          device has already attached a screenshot to THIS response
          (shot_auto): read that one, do not call take_screenshot for the same
          screen. Prefer back / home to exit and retry via a different route
          over blind coordinate taps. Blame the pane, not the app: the same
          app's native screens (chat list, tabs, settings) dump fine.

          The exception is worth watching for, and `hint` will tell you when
          it applies: if this SAME app gave you a real element list earlier in
          the session and is now returning zero nodes, that is not a self-drawn
          pane — a self-drawn pane still reports its containers. It means our
          accessibility connection went stale, which happens to WeChat in
          particular. You cannot fix it with a tool call. Ask the user, in
          plain words, to open 设置 → 无障碍 and switch Andee (this device's
          assistant, the floating ball) off and back on; it takes seconds and
          the element list comes back. Judge whether it is worth interrupting
          them — you can still tap by coordinate meanwhile — but do not silently
          keep dumping an app that has stopped answering.
        - nodes_total > 0: the elements are there, they are merely nameless.
          The outline still groups them and still gives every centre, so use
          it — match what you see in the attached screenshot against the
          indentation and tap the line sitting at that spot by e-number.
          A coordinate tap here is a downgrade, not a shortcut.

        `elements_dropped: N` means the screen carried more elements than fit
        and N of the least useful lines were cut, bottom-first. What you were
        given is complete and correctly grouped; scroll if what you want is
        not in it.

        e-numbers are only valid until the page changes — if
        tap_screen_element reports an unknown element, call this tool again
        for a fresh list.
    """.trimIndent(),
            required = emptyList(),
            props = mapOf(
                "verbose" to prop(
                    "boolean",
                    "Return the full nested JSON tree (debug only). The default outline carries the same structure for a third of the tokens — this is for a human reading a dump, not for you.",
                    default = false,
                ),
                "with_shot" to prop(
                    "boolean",
                    "Include a screenshot alongside the element outline. Default false: when the outline has content it already tells you what is tappable, where, and what belongs with what, so the picture costs tokens and adds nothing. Set it true only when you must judge the screen visually — two icons in the SAME indent group that you cannot tell apart by class or id, or a layout / color / render question. When the outline comes back EMPTY the device attaches a screenshot by itself (shot_auto), so never set this to work around an empty list.",
                    default = UI_TREE_WITH_SHOT_DEFAULT,
                ),
            ),
        ),
        tool(
            name = "tap_screen_element",
            method = "screen.tap_id",
            description = "[PREFERRED] Tap a screen element by its e-number from get_screen_element's element list — e.g. {\"eid\": \"e7\"}. The device resolves the number to that element's exact pixel center itself: no coordinate math, no scaling errors. Always tap e-numbers from the LATEST get_screen_element (the page may have changed since an older one; stale numbers fail loudly with 'unknown element' — that means re-dump, not guess).",
            required = listOf("eid"),
            props = mapOf(
                "eid" to prop("string", "Element number from the latest get_screen_element list, e.g. \"e7\"."),
            ),
        ),
        tool(
            name = "tap_by_coordinates",
            method = "screen.tap",
            description = """
        [LAST RESORT] Tap a point on the screen. Aim in ONE of two ways:

        1. **By grid cell + part** (preferred whenever you are looking at an
           image): {"cell": "D7", "part": "bottom-right"}. The cell is one of
           the aim grid drawn on the screenshot you were shown, columns A-H
           left to right, rows 1-12 top to bottom, and each label is printed at
           the CENTRE of its cell. Pick the cell the target sits IN — not the
           label nearest to it. Then `part` says which third of that cell the
           target is in, across and down: top-left, top, top-right, left,
           center, right, bottom-left, bottom, bottom-right (omit it for the
           centre). The device converts both to pixels itself; there is no
           arithmetic for you to do. A cell is too coarse to tap with on its
           own — always give the part unless the target fills the cell.
           If the target straddles two cells, pick the one holding most of it.
           For anything not clearly bigger than a cell (an icon, a ✕ close
           button, a small chip), CHECK FIRST: zoom_screen_region with the
           same cell and part shows a red crosshair exactly where this tap
           would land. On target → send this tap with the same arguments.
           Off target → aim off that magnified image: its labels are
           LOWERCASE and you must say so — {"cell": "d7", "part": "left",
           "on": "zoom"}. A lowercase label sent as if it were a full-screen
           cell will be about 120 units off.

        2. **By coordinate** {"x": …, "y": …} — RELATIVE 0-1000 integers
           ((0,0) top-left, (1000,1000) bottom-right; the device converts to real
           pixels for any screen size). With {"on": "zoom"} these numbers are
           0-1000 *within the last magnified image* instead of the whole screen,
           and the device maps them back — so you never add region.left or divide
           by scale_x yourself. Values outside 0-1000 are rejected, not clamped.

        Use this only when the target has no e-number: self-drawn panes
        (mini-program, WebView, game), images, pure-visual content. If a
        get_screen_element list exists for this screen, use tap_screen_element.
    """.trimIndent(),
            required = emptyList(),
            props = mapOf(
                "cell" to prop("string", "Grid cell of the image you aimed from, e.g. \"D7\" on a full screenshot or \"d7\" on a magnified one."),
                "part" to propEnum("Which third of that cell the target is in. Omit for the centre.", ScreenController.PARTS),
                "x" to prop("integer", "X, 0 (left edge) to 1000 (right edge) — of the screen, or of the zoom image when on=\"zoom\"."),
                "y" to prop("integer", "Y, 0 (top edge) to 1000 (bottom edge) — of the screen, or of the zoom image when on=\"zoom\"."),
                "on" to propEnum("Which image x/y (or a lowercase cell) were read off. \"screen\" is the default.", listOf("screen", "zoom")),
            ),
        ),
        tool(
            name = "long_press_screen_element",
            method = "screen.long_press_id",
            description = "Long-press a screen element by its e-number from get_screen_element (context menus, drag handles, multi-select). Same freshness rule as tap_screen_element: latest dump only. Duration is fixed at ~600ms.",
            required = listOf("eid"),
            props = mapOf(
                "eid" to prop("string", "Element number from the latest get_screen_element, e.g. \"e12\"."),
            ),
        ),
        tool(
            name = "long_press_by_coordinates",
            method = "screen.long_press",
            description = "Hold a finger on a point for a set duration (context menus, drag handles). Aim exactly like tap_by_coordinates — by grid cell plus the third of it the target is in, {\"cell\": \"D7\", \"part\": \"bottom-right\"} (add on=\"zoom\" and use a lowercase label for a magnified image), or by RELATIVE 0-1000 coordinates, not pixels. Prefer long_press_screen_element when the target has an e-number.",
            required = emptyList(),
            props = mapOf(
                "cell" to prop("string", "Grid cell of the image you aimed from, e.g. \"D7\" on a full screenshot or \"d7\" on a magnified one."),
                "part" to propEnum("Which third of that cell the target is in. Omit for the centre.", ScreenController.PARTS),
                "x" to prop("integer", "X, 0 (left edge) to 1000 (right edge) — of the screen, or of the zoom image when on=\"zoom\"."),
                "y" to prop("integer", "Y, 0 (top edge) to 1000 (bottom edge) — of the screen, or of the zoom image when on=\"zoom\"."),
                "on" to propEnum("Which image x/y (or a lowercase cell) were read off. \"screen\" is the default.", listOf("screen", "zoom")),
                "duration_ms" to prop("integer", "How long to hold, in milliseconds. Default 600.", default = 600),
            ),
        ),
        tool(
            name = "swipe_by_coordinates",
            method = "screen.swipe",
            description = "Swipe from one point to another: scroll lists, page carousels, drag things. To scroll a list DOWN (see more content below), swipe from a point near the BOTTOM UP to a point near the TOP — fingers move opposite to content. All coordinates are RELATIVE 0-1000 like tap_by_coordinates. Duration controls speed: shorter = faster fling.",
            required = listOf("x1", "y1", "x2", "y2"),
            props = mapOf(
                "x1" to prop("integer", "Start X, 0 (left) to 1000 (right)."),
                "y1" to prop("integer", "Start Y, 0 (top) to 1000 (bottom)."),
                "x2" to prop("integer", "End X, 0 (left) to 1000 (right)."),
                "y2" to prop("integer", "End Y, 0 (top) to 1000 (bottom)."),
                "duration_ms" to prop("integer", "How long the swipe takes, in ms. Default 300.", default = 300),
            ),
        ),
        tool(
            name = "type_text",
            method = "screen.type",
            description = "Type text into the CURRENTLY FOCUSED input field (tap the field first — by e-number — then call this as the very next action; the keyboard opens by itself, do not re-tap the field). Existing content is REPLACED, not appended. Does not press Enter/Send — use submit_input for that.",
            required = listOf("text"),
            props = mapOf(
                "text" to prop("string", "The text to enter."),
            ),
        ),
        tool(
            name = "submit_input",
            method = "screen.submit_input",
            description = "Submit the currently focused input by firing its IME action (Enter / Send / Search / Next / Done — whichever the field was set up for). Typical uses: send a chat message after type_text, run a search. Use this instead of trying to press a keyboard Enter key — Android delivers 'Enter/Send' through this action, not a raw keystroke.",
            required = emptyList(),
            props = emptyMap(),
        ),
        tool(
            name = "wait_for_screen",
            method = "screen.wait",
            description = "Wait for the screen to finish loading, then observe it. Use this after anything that starts a load — tapping a link, submit_input, launch_app — instead of calling get_screen_element straight away and reading a half-drawn page. It returns the SAME element outline get_screen_element gives you, plus a screenshot, captured after the wait: you already have the fresh state, so do NOT follow it with get_screen_element. One call waits at most 20 seconds (the request itself times out at 30). Ask for what the page plausibly needs — 2-3s for an in-app screen, 5-10s for a slow site or a cold app start.\n\nSomething genuinely slow — a cold app start, an upload, a video that has to transcode — can need more than one wait, and that is fine. But each repeat MUST ASK FOR MORE TIME THAN THE LAST ONE: climb 3 → 5 → 10 → 20, and never send the same number twice in a row. Two reasons, and the second one is fatal: what was not ready after 5s deserves better than another 5s, and a run of calls with byte-identical arguments trips the loop guard, which kills the WHOLE TASK — not just the wait. Sending seconds=10 over and over is the most common way to lose a long job.\n\nEvery wait has to earn its place: read what came back before deciding to wait again. If the outline changed at all, the page is alive — carry on, don't wait more. If you have already climbed to 20s and the screen is still identical, it is not loading, it is stuck or blocked on something (a dialog, a login, no network) — report that or change route. Never wait just to pass time.",
            required = listOf("seconds"),
            props = mapOf(
                "seconds" to prop("number", "How long to wait before looking, in seconds. Values above 20 are reduced to 20; the result reports the time actually waited. On a repeat wait for the same screen this number must be larger than the one you just used."),
            ),
        ),
        tool(
            name = "zoom_screen_region",
            method = "screen.look_region",
            description = """
        [PRECISION AIMING · SCREEN, NOT CAMERA] Despite the method name,
        this is a magnified capture of a screen REGION and has nothing to
        do with the physical camera (camera_turn). A magnified look at ONE region of the screen —
        4x the pixel density of a full screenshot around wherever you point.

        Use it to CHECK an aim before tapping anything not clearly bigger
        than a grid cell (an icon, a ✕ close button, a small chip): pass the
        cell and part you were about to tap — {"cell": "D9", "part":
        "bottom-right"} — and you get a magnified view two cells each way,
        centred on that point, with a RED CROSSHAIR exactly where the tap
        would land. Crosshair on the target → tap with the same cell and
        part. Off it → aim off this image instead (below). Targets often sit
        right on a grid line; this view is centred on the aim, so they are
        never cut in half.

        The other form is x/y marking the region's CENTER in the usual
        0-1000 space plus w/h for its size (try 200x300 for one control,
        400x600 for a card) — a plain magnified look, no crosshair.

        This image is a magnified region, and it carries the SAME 8x12 grid in
        LOWERCASE (a1..h12), labels at each cell's centre, so a label read off
        it can never be confused with a cell of the full screen. Aim your tap
        straight from one of those labels: {"cell": "d7", "part": "top",
        "on": "zoom"} on tap_by_coordinates, and the device
        converts it — no arithmetic. (The old route still works and is
        equivalent: the response carries the region's screen bounds and
        scale_x/scale_y, and a point (px,py) read off the image maps to
        screen-relative (region.left + px/scale_x, region.top + py/scale_y).)

        After a tap that reported screen_changed=false, its miss_shot field
        is exactly this — aim your retry from that image.
    """.trimIndent(),
            required = emptyList(),
            props = mapOf(
                "cell" to prop("string", "Grid cell of the full screenshot you are about to tap, e.g. \"D7\". Replaces x/y/w/h."),
                "part" to propEnum("Which third of that cell — the same part you would tap. Omit for the centre.", ScreenController.PARTS),
                "x" to prop("integer", "Region center X, 0 (left) to 1000 (right). Use instead of cell."),
                "y" to prop("integer", "Region center Y, 0 (top) to 1000 (bottom). Use instead of cell."),
                "w" to prop("integer", "Region width in 0-1000 units, e.g. 200."),
                "h" to prop("integer", "Region height in 0-1000 units, e.g. 300."),
            ),
        ),
        tool(
            name = "take_screenshot",
            method = "screen.screenshot",
            description = """
        [FALLBACK · REQUIRES A REASON] Capture the current screen as a PNG
        (downscaled to 1280 on the long side — proportions preserved).

        Every screenshot arrives with an 8x12 AIM GRID drawn on it, labelled
        A1 (top-left) to H12 (bottom-right), each label printed at the CENTRE
        of its cell. This is the device's way of taking the arithmetic out of
        aiming: read the cell the target sits in and which third of it, and
        pass them straight to tap_by_coordinates as {"cell": "D7", "part":
        "bottom-right"} — no pixel measuring, no rescaling, nothing to get
        wrong. For anything not clearly bigger than a cell, first pass the
        same cell and part to zoom_screen_region: it shows a red crosshair
        where the tap would land, so you can confirm or re-aim off the
        magnified image (its grid is lowercase, and a lowercase label means
        "this was read off the zoom").

        Every screenshot now arrives WITH the current element list attached
        (`elements`, same e-numbers as get_screen_element) whenever the tree
        is readable. When it is attached: aim with tap_id(e) / type(id) from
        THAT list — system-computed and exact. The grid is for elements the
        list does not carry.

        Do NOT use this for finding controls, tapping buttons, or reading
        text — get_screen_element is cheaper and more precise. Only call it
        when:
        1) The content is inherently an image (photo/video/chart/CAPTCHA/map)
        2) get_screen_element returned an empty list (self-drawn pane:
           mini-program, WebView article, game canvas, Moments feed) AND you
           need a FRESH look — the empty response already carried a shot of
           that screen (shot_auto), so use this only after something changed.
           Read the shot and tap by grid cell. Prefer back /
           home over blind taps when unsure.
        3) A human-eye-level visual judgement is required
           (color, overlap, render bug).
        4) You must tell two elements apart by how they LOOK, and the element
           list alone cannot settle it (two unlabeled icons of the same
           class). Prefer get_screen_element with with_shot=true for this —
           it keeps the list and the picture in one response, in one screen
           state. Reach for this tool only when you already hold a current
           list and just need the pixels.

        Never use a screenshot as a "let me take a look first" exploration
        step. After tapping from a screenshot, verify the outcome (fresh
        screenshot or get_screen_element) before assuming it worked.

        A cyan ring with a small caption like "tap 500,500" or "hold 240,880"
        is NOT part of the app — it is where YOUR last tap / long-press
        actually landed, drawn by the device and kept on screen for a few
        seconds. Likewise the grid lines and their A1..H12 labels. Read the
        ring as ground truth for where a number you sent turned out to be on
        this display. Most useful after tap_screen_element (where you never knew the
        pixel location) and after a tap that seemed to do nothing — a ring
        sitting beside the button is a different problem from a ring on a
        button that did not respond.
    """.trimIndent(),
            required = emptyList(),
            props = emptyMap(),
        ),
        tool(
            name = "system_action",
            method = "screen.global",
            description = "Trigger an Android system-level navigation action. Use 'back' to dismiss the current screen (also the recommended way to escape a stuck self-drawn page), 'home' to return to the launcher, 'recents' to open the task switcher, 'notifications' to pull down the shade, 'quick_settings' to open toggles. This has nothing to do with keyboard keys — for the on-screen keyboard's Enter / Send / Search use submit_input; to type any character use type_text.",
            required = listOf("action"),
            props = mapOf(
                "action" to propEnum("Which system action to trigger.", listOf("back", "home", "recents", "notifications", "quick_settings")),
            ),
        ),
        tool(
            name = "get_device_status",
            method = "device.connectivity",
            description = "Check whether the device is online and how: returns online (true/false, validated connection), type (wifi / cellular / ethernet / none). Use before anything that needs the internet, or to answer \"are you connected?\".",
            required = emptyList(),
            props = emptyMap(),
        ),
        tool(
            name = "list_installed_apps",
            method = "device.apps",
            description = "List the launchable apps installed on the device (label + package name) — what a person means by \"what apps are on this tablet\", not the hidden system packages. Useful for \"is X installed?\", \"open something that can do Y\" (find the app, then launch it via the launcher).",
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "get_location",
            method = "device.location",
            description = "Get the device's current GPS location: lat, lng, accuracy in meters, and the fix time. Fresh fix takes a few seconds outdoors; indoors it falls back to a cached one (check age_ms). If the app lacks location permission the result says so with a hint — ask the user to grant it once, then retry. Don't call repeatedly in a loop.",
            required = emptyList(),
            props = mapOf(
                "timeout_ms" to prop("integer", "Max wait for a fresh fix. Default 8000.", default = 8000),
            ),
        ),
        tool(
            name = "get_step_count",
            method = "device.steps",
            description = "Read the hardware step counter: steps since last reboot (cumulative, not per-day — say so when reporting). Needs the 'physical activity' permission; if missing the result explains how to get it. Tablets carried in a bag count too.",
            required = emptyList(),
            props = mapOf(
                "timeout_ms" to prop("integer", "Max wait for a sensor reading. Default 3000.", default = 3000),
            ),
        ),
        tool(
            name = "get_battery",
            method = "device.battery",
            description = "Battery level and charging state: percent, charging (bool), plugged (bool). Cheap to call any time.",
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "launch_app",
            method = "device.launch",
            description = "Launch an app by package name (preferred, e.g. 'com.tencent.mm') or by display label (e.g. '微信' — exact match, use list_installed_apps to find it). Direct launch intent, no screen-tapping needed.",
            required = emptyList(),
            props = mapOf(
                "pkg" to prop("string", "Package name, e.g. 'com.tencent.mm'."),
                "label" to prop("string", "Display label, e.g. '微信'. Used only if pkg is omitted."),
            ),
        ),
        tool(
            name = "set_volume",
            method = "device.volume",
            description = "Read or set media volume (0..max). Omit level/mute to just read the current state.",
            required = emptyList(),
            props = mapOf(
                "level" to prop("integer", "Target volume 0..max. Omit to read only."),
                "mute" to prop("boolean", "true=mute, false=unmute. Omit to leave as-is."),
            ),
        ),
        tool(
            name = "set_brightness",
            method = "device.brightness",
            description = "Read or set screen brightness (1..255). Omit the value to read. Writing needs the WRITE_SETTINGS special grant — the result says so if missing.",
            required = emptyList(),
            props = mapOf(
                "brightness" to prop("integer", "1..255. Omit to read only."),
            ),
        ),
        tool(
            name = "open_url",
            method = "device.open_url",
            description = "Open a URL with the right app: http(s)→browser, tel:→dialer, geo:→map, mailto:→email. Ask the user before opening anything they didn't explicitly request.",
            required = listOf("url"),
            props = mapOf(
                "url" to prop("string", "Full URL including scheme, e.g. 'https://example.com' or 'tel:10086'."),
            ),
        ),
        tool(
            name = "set_alarm",
            method = "device.alarm",
            description = "Set a system alarm (hands off to the clock app, skip-UI). Hour/minute in 24h local time.",
            required = listOf("hour", "minute"),
            props = mapOf(
                "hour" to prop("integer", "0-23."),
                "minute" to prop("integer", "0-59."),
                "message" to prop("string", "Alarm label."),
            ),
        ),
        tool(
            name = "device_facts",
            method = "device.facts",
            description = "Hardware facts: model, brand, Android version, RAM total/available, storage total/available.",
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "read_sensors",
            method = "device.sensors",
            description = "One-shot environment read: light_lux (room brightness — 0 dark, >300 bright) and accelerometer (was the tablet just moved/picked up).",
            required = emptyList(),
            props = mapOf(
                "timeout_ms" to prop("integer", "Per-sensor wait. Default 1500.", default = 1500),
            ),
        ),
        tool(
            name = "search_contacts",
            method = "device.contacts",
            description = "Search the contact book by name (fuzzy). Returns name + phone. Omit query to list the first 20. Needs Contacts permission; the result says how to get it if missing.",
            required = emptyList(),
            props = mapOf(
                "query" to prop("string", "Name to search for. Omit to list."),
            ),
        ),
        tool(
            name = "dial",
            method = "device.dial",
            description = "Open the dialer with a number filled in (direct=false, safe default) or place the call directly (direct=true, needs Phone permission AND explicit user confirmation — never dial directly on your own initiative).",
            required = listOf("number"),
            props = mapOf(
                "number" to prop("string", "Phone number."),
                "direct" to prop("boolean", "true = call immediately (requires user's explicit go-ahead); false/omit = open dialer for the user to press call.", default = false),
            ),
        ),
        tool(
            name = "get_call_log",
            method = "device.call_log",
            description = "Recent calls: name/number, incoming/outgoing/missed, time, duration. Needs Call log permission.",
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "read_sms",
            method = "device.sms.read",
            description = "Read SMS: inbox (default) or sent box, newest first. Classic use: fetch the verification code that just arrived — step 4 of the login playbook in list_vault. Needs SMS permission.",
            required = emptyList(),
            props = mapOf(
                "box" to prop("string", "'inbox' (default) or 'sent'.", default = "inbox"),
            ),
        ),
        tool(
            name = "send_sms",
            method = "device.sms.send",
            description = "Send an SMS directly. SENSITIVE: only when the user explicitly asked to send THIS text to THIS number. Say what you're about to send before sending if there is any doubt.",
            required = listOf("number", "body"),
            props = mapOf(
                "number" to prop("string", "Recipient phone number."),
                "body" to prop("string", "Message text."),
            ),
        ),
        tool(
            name = "list_vault",
            method = "device.vault.list",
            description = """
        The user's own saved accounts — what you log in AS when a task needs
        their identity (飞书, 公司邮箱, …), and any bank card they saved for
        paying. Call this first; it is cheap and tells you whether the user has
        saved anything for the app in front of you.

        Identifiers come back MASKED (138****1111) — enough to confirm you
        have the right account, not enough to type. Use get_vault for the real
        value. Each entry lists its `secrets`: fields you can never read, only
        ask the device to type (see fill_secret).

        THE LOGIN PLAYBOOK, when a phone-number + verification code login is
        in front of you:
          1. list_vault → find the entry (match on its label)
          2. get_vault(entry, "phone") → the real number
          3. tap the phone field, type_text it, tap 发送验证码
          4. read_sms → the code that just arrived (inbox, newest first)
          5. tap the code field, type_text the digits
        If the page wants a password instead, tap the password field and call
        fill_secret — you will never hold the password yourself.

        A CARD entry has `card_number`, `card_expiry` and `card_cvv` among its
        secrets, and a `card_last4` you CAN read. Use the last four to tell two
        cards apart and to say out loud which one you are about to use — never
        just "your card" when the user saved more than one. Fill each of the
        three into its own field, one fill_secret per field.

        If the entry the task needs is missing, say so and ask the user to add
        it in 设置 → 保险箱. Do not guess an account, and never invent one.
            """.trimIndent(),
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "get_vault",
            method = "device.vault.get",
            description = """
        One identifier out of one vault entry, unmasked, so you can type it.
        Readable fields: phone, email, username, note, card_holder, card_last4.
        `note` is the user's own hint about this account ("用手机验证码登录")
        — worth reading before you decide how to log in.

        Secrets are NOT available here and asking for one is an error. That is
        deliberate: the device types secrets so they never enter your context.
        That covers the password AND the card number, expiry and CVV. Use
        fill_secret.
            """.trimIndent(),
            required = listOf("entry"),
            props = mapOf(
                "entry" to prop("string", "Entry id or label from list_vault, e.g. '飞书'."),
                "field" to prop(
                    "string",
                    "phone | email | username | note | card_holder | card_last4. Default phone.",
                    default = "phone",
                ),
            ),
        ),
        tool(
            name = "fill_secret",
            method = "device.vault.fill",
            description = """
        Type one saved secret into the input that ALREADY HAS FOCUS. Tap the
        box first (tap_element / tap_screen) — exactly like type_text, which
        this is a wrapper around.

        `field` picks which secret: password (default), card_number,
        card_expiry, card_cvv. One call fills one field, so a checkout form
        takes three — tap the number box, fill card_number, tap the expiry box,
        fill card_expiry, and so on. list_vault tells you which of these the
        entry actually has.

        You never see the value. The result is {performed, len} and nothing
        more, even when it fails. If it reports performed:false, take a
        screenshot rather than retrying blindly; re-sending is how a field
        ends up with the value twice.

        THIS DOES NOT WORK ON A PAYMENT SHEET. 支付宝 / 微信支付 / a banking
        app's PIN pad draw their own keypad, which is not a text field, so
        there is nothing to type into — and the payment itself is the user's to
        authorise, not yours. What this reaches is an ordinary checkout form or
        the one-time page that binds a card to an app. When you hit a payment
        sheet, stop and hand it to the user.

        The ball flashes and the transcript records 「正在填入…」, so the user
        can always tell their secret was used. Say which card you are using (by
        its last four) before you use it. Only call it when the task the user
        actually asked for needs it.
            """.trimIndent(),
            required = listOf("entry"),
            props = mapOf(
                "entry" to prop("string", "Entry id or label from list_vault, e.g. '飞书'."),
                "field" to prop(
                    "string",
                    "password | card_number | card_expiry | card_cvv. Default password.",
                    default = "password",
                ),
            ),
        ),
        tool(
            name = "get_calendar",
            method = "device.calendar",
            description = "Calendar events from now up to days_ahead (default 3). Needs Calendar permission.",
            required = emptyList(),
            props = mapOf(
                "days_ahead" to prop("integer", "Look-ahead window in days. Default 3.", default = 3),
            ),
        ),
        tool(
            name = "add_calendar_event",
            method = "device.calendar.add",
            description = "Add an event to the primary calendar. begin_ms/end_ms are Unix milliseconds. End defaults to begin+1h.",
            required = listOf("title", "begin_ms"),
            props = mapOf(
                "title" to prop("string", "Event title."),
                "begin_ms" to prop("integer", "Start time, Unix ms."),
                "end_ms" to prop("integer", "End time, Unix ms. Default begin+1h."),
                "location" to prop("string", "Where."),
                "description" to prop("string", "Notes."),
            ),
        ),
        tool(
            name = "get_notifications",
            method = "device.notifications",
            description = "Read the notification buffer: what arrived on the device recently (pkg, title, text, when) — WeChat messages, system alerts, everything the user sees in the shade. Use since_ms to fetch only new ones. If listener_enabled is false, the result includes how to turn it on.",
            required = emptyList(),
            props = mapOf(
                "since_ms" to prop("integer", "Only notifications at/after this Unix ms. Default 0 (all buffered).", default = 0),
                "limit" to prop("integer", "Max items. Default 20.", default = 20),
            ),
        ),
        tool(
            name = "get_permissions",
            method = "device.permissions",
            description = "Which of the ball's powers are granted vs missing (location, steps, contacts, sms, calendar, notifications, ...). Call this FIRST when a tool reports 'permission denied', or when you want to offer the user a one-shot enable.",
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "request_permissions",
            method = "device.permissions.request",
            description = "Guide the user through enabling permissions. Two-step by design: first call WITHOUT confirm → you get a 'say' line (read it aloud / show it) → user agrees → call again WITH confirm=true to fire the dialogs/deep-links. Only call with confirm=true after the user said yes.",
            required = listOf("ids"),
            props = mapOf(
                "ids" to prop("array", "Permission ids from get_permissions.missing[].id, e.g. [\"location\",\"sms\"]."),
                "confirm" to prop("boolean", "false (default) = get the script; true = actually fire the request.", default = false),
            ),
        ),
        tool(
            name = "scan_code",
            method = "device.scan",
            description = "Open the camera and scan a QR/barcode: returns {value, format}. The user aims the device at the code; blocks until scanned or timed out (default 30s). Camera permission is requested on the spot if missing.",
            required = emptyList(),
            props = mapOf(
                "timeout_ms" to prop("integer", "Max wait for the user to aim & the scanner to lock on. Default 30000.", default = 30000),
            ),
        ),
        tool(
            name = "camera_turn",
            method = "device.look",
            description = """
        SEE the physical world through the device camera: returns ONE frame
        of what the lens currently sees (an image — you actually see it, same
        as a screenshot). The session STAYS OPEN between calls.

        WHEN TO USE — you decide the cadence, fit it to the ask:
        - "这是什么/看看这个" → look once, answer. If the frame is blurry or
          the target isn't in view, TELL the user to aim ("对准一点") and
          look again.
        - "帮我盯着水桶满了没" / monitoring tasks → loop: look → judge →
          wait an interval YOU choose (30s? 2min? longer for slow changes) →
          look again. Report progress, stop when done or when the user asks.
        - Front camera (selfie cam) with front=true — check on the user,
          the room from the device's stand, etc.

        Each look returns the CURRENT frame only. While the session is open
        the user sees a viewfinder and a 正在看 line, and the corners flash
        every time you actually take a frame — they can tell when you looked.

        They can also close it themselves, with the ✕完成 pill. If a look
        comes back saying the user just closed the camera, that is a real
        answer, not a glitch: do NOT call this again to retry. Ask them first
        and wait for a yes.

        Close it with look_stop when done — don't leave the camera on
        unnoticed.
    """.trimIndent(),
            required = emptyList(),
            props = mapOf(
                "front" to prop("boolean", "true = front/selfie camera. Default back.", default = false),
            ),
        ),
        tool(
            name = "camera_stop",
            method = "device.look_stop",
            description = "Close the camera look session. Call when the task is done or the user stops asking — an open camera the user forgot about is creepy.",
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "ask_user",
            method = "ui.ask",
            description = """
        Ask the user a question on a small floating card with tappable
        buttons — blocks until they answer (or timeout). Returns which
        button they tapped. The question is also read aloud (TTS), so
        write it as one speakable sentence, no markdown or lists.

        USE WHEN the next step genuinely needs the user's choice: which of
        several options, yes/no on something irreversible, "要继续吗".
        NOT for status updates (that's alert) and not for questions you can
        answer yourself. Two or three short buttons work best; the FIRST
        button renders as the primary (green) one.
    """.trimIndent(),
            required = listOf("question"),
            props = mapOf(
                "question" to prop("string", "Short question shown on the card and read aloud. Keep under ~2 lines."),
                // No `default` here on purpose: the pair the card falls back on
                // is `cmd_ask_yes`/`cmd_ask_no`, resolved in the interface
                // language. A literal default in this schema would be a second,
                // single-language answer to the same question — and one the
                // model reads, so it could echo it back to an English user.
                "buttons" to prop("array", "Button labels, in the language the user is speaking — e.g. [\"帮我看看\",\"忽略\"]. First = primary. Omit it and the card supplies its own pair in the interface language."),
                "timeout_ms" to prop("integer", "Card auto-dismisses after this. Default 30000.", default = 30000),
            ),
        ),
        tool(
            name = "alert_user",
            method = "ui.alert",
            description = "Show the user a short notice card and read it aloud (single 知道了 control; tapping the card also dismisses). Blocks until read or timeout. For status/heads-up only — anything needing a choice is ask_user.",
            required = listOf("text"),
            props = mapOf(
                "text" to prop("string", "The notice text, short — read aloud, so plain prose."),
                "timeout_ms" to prop("integer", "Auto-dismiss. Default 30000.", default = 30000),
            ),
        ),
        tool(
            name = "show_html",
            method = "ui.show_html",
            description = """
        Render a full-screen UI YOU compose, ON THIS TABLET, right now, in
        front of the user. It is not a file you are handing them and not a
        link — the page opens on the device in their hands the moment you
        call this. Never tell the user to open it on a computer, in a
        browser, or anywhere else; there is nowhere else to open it.

        This is your canvas for rich output — dashboards, comparison
        tables, image grids, small games, interactive widgets the chat
        bubble can't hold. It comes back as soon as the page is up — the
        page stays until the user taps 完成, and you are told when they do.
        Do not wait for it and do not describe the page out loud.

        A wall of prose is NOT rich output, however long it is. The chat
        bubble holds text: a message past six lines is folded, and the user
        double-taps it to read the whole thing fullscreen. So an
        explanation, an introduction or a story belongs in your reply, not
        on a page. Reach for this when the answer has STRUCTURE (rows and
        columns, cards, steps) or is interactive; length alone is not a
        reason.

        ONE PAGE AT A TIME: while a page is up, calling this again is
        refused rather than stacked. Compose everything into the one page.

        Inline CSS and JS ONLY. The device may have no usable internet, so a
        page that fetches a font, a framework or an image over the network
        renders broken.

        HOUSE STYLE — follow it unless the user asked for something else.
        This is what the rest of the device looks like; a page that ignores
        it reads as a stray web page, not as part of Andee.

        <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">

        Colours (use these exact values, no others):
          page background  #0F172A
          card / surface   #1E293B
          hairline border  rgba(148,163,184,.18)
          body text        #F8FAFC
          secondary text   #94A3B8
          accent           #9DB4FF   ← the ball's colour; ONE accent only

        Type: font-family:-apple-system,"PingFang SC","Noto Sans CJK SC",
        system-ui,sans-serif — name the CJK families explicitly or Chinese
        falls back to a default glyph set and the page instantly looks cheap.
        Sizes 28 / 20 / 16 / 13 px, line-height 1.6.

        Layout: portrait, ONE column, 20px page gutters, spacing in
        multiples of 8px, card radius 14px. Use the hairline border to
        separate cards — no heavy box-shadows, no gradients, no glow.
        Large touch targets (44px minimum); type readable without zooming.

        A ✕完成 pill floats over the top-right corner so the user can always
        leave — keep roughly 110×44px clear there.
    """.trimIndent(),
            required = listOf("html"),
            props = mapOf(
                "html" to prop("string", "Complete HTML document."),
                // Same reason as `buttons` above: the fallback is
                // `html_default_title`, resolved in the interface language, and
                // `default = "AI 界面"` used to sit here as a literal.
                "title" to prop("string", "Short page title — carried into the conversation record, and quoted back to you if a page is already open. Omit it and the page is named in the interface language."),
            ),
        ),
        tool(
            name = "start_meeting",
            method = "meeting.start",
            description = """
        Hold the microphone open and transcribe everything said in the room,
        for as long as the meeting lasts. Returns immediately — recording
        continues in the background until stop_meeting or until the user
        taps the ball. The tablet goes full screen and shows the transcript
        scrolling as it is recognised, so the user can see it is working.

        THE MICROPHONE IS YOURS FOR THE WHOLE MEETING. The user cannot talk
        to you while it runs — the wake word is off and a voice turn cannot
        start. Do not expect instructions mid-meeting.

        The device also goes silent: your replies are not read aloud while a
        meeting is recording, because the speaker is an inch from the
        microphone and would record you. Say whatever the user actually
        needs to hear BEFORE you call this, and keep the reply that follows
        it short.

        When the user ends it by tapping the ball, you will simply receive a
        new message from them containing the transcript and asking for the
        minutes. You do not need to poll for it and there is no event to
        subscribe to.
    """.trimIndent(),
            required = emptyList(),
            props = mapOf(
                // The fallback is `meeting_default_title` (see `MeetingRecorder`
                // and `ScreenBodyService.meetingMinutesQuery`), resolved in the
                // interface language. It used to be spelled out here as 会议记录,
                // which is the one string above that reaches the screen.
                "title" to prop("string", "What this meeting is, e.g. '周会'. Becomes the title the minutes page is laid out under. Omit it and the meeting is named in the interface language."),
            ),
        ),
        tool(
            name = "stop_meeting",
            method = "meeting.stop",
            description = """
        End the recording and get back the VERBATIM TRANSCRIPT — raw speech,
        no speaker labels, not minutes.

        Writing the minutes is your job, and showing them is a second step:
        compose the minutes as a full HTML page and call show_html. That is
        the only way they reach the user's eyes — a chat reply is not it.
        The full transcript is always on the device at the returned path
        even when the returned text is truncated.

        `stopped_by` says who ended it: "brain" is this call, "user" is the
        ball, "focus" is something on the device taking the audio floor
        (usually a phone call). Do not tell the user they tapped anything
        unless it says "user".
    """.trimIndent(),
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "meeting_status",
            method = "meeting.status",
            description = "Is a meeting being recorded, and for how long. Cheap; returns elapsed_ms and how many characters have been transcribed so far. No transcript text.",
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "dog_move",
            method = "dog.move",
            description = """
        Drive the little robot dog: send it one motion for a set number of
        seconds. It starts right away and STOPS BY ITSELF when the time is up —
        you do not need to stop it afterwards, and the dog never keeps running
        because a call went missing. The call returns immediately (the dog
        keeps moving in the background), so this response is not the end of the
        movement. To cut it short, call dog_stop.

        The dog is a real machine standing on a real floor. Before the first
        move of a session, say what you are about to do, and stay aware that
        whatever it hits, it hits for as long as you asked.

        MOTION: forward / back / left / right, in 0-1000 units of pure
        direction — there are no distances here, only time. `gait` picks how it
        travels:
        - gait=false (DEFAULT) — wheels. Faster, steadier, use it unless the
          user asks for the legs. Rolling forward works on carpet; it is only
          the on-the-spot *turn* that scrubs sideways and slips, which is why
          dog_turn defaults the other way.
        - gait=true — the legs walk. Slower and more impressive; use it when
          the user says 走/步态/腿/用脚.

        HOW LONG: seconds, up to 30 per call. Straight-line speed is NOT
        calibrated — "往前走" is 2s, "走远一点" 4s, and if the user says it went
        too far or not far enough, adjust and say what you changed. There is no
        distance unit to offer them.

        FOR TURNS, USE dog_turn INSTEAD. It measures the angle with the tablet's
        gyroscope and stops when it gets there, which the seconds here cannot do.
        Only fall back to left/right with seconds if dog_turn says this tablet
        has no sensor — a full turn is then about 8.7s on the wheels, 12s on the
        legs, so half a turn ≈ 4.4 and a quarter ≈ 2.2.

        THE DOG HAS OBSTACLE AVOIDANCE, AND IT WILL REFUSE YOU. Its ultrasonic
        sensor blocks forward motion closer than 20cm and backs it up below
        10cm, on its own, regardless of what you asked for. So "it did not
        move forward" is usually this, not a failure: look at what is in front
        of it (camera_turn shows you) rather than sending the command again.

        IF IT REPLIES NO-ACK, the dog never confirmed receiving the command —
        it may be out of range, switched off, or fighting the original remote.
        Do not retry silently in a loop; say so and let the user look.

        Two things you cannot see from here: battery and whether the dog is
        physically stuck. This link only sends. Ask the user — though
        dog_status.posture.vibration is weak evidence: near zero while a motion
        should be running means nothing is actually moving.
    """.trimIndent(),
            required = listOf("motion", "seconds"),
            props = mapOf(
                "motion" to propEnum(
                    "Which way to move.",
                    listOf("forward", "back", "left", "right"),
                ),
                "seconds" to prop("number", "How long to move, up to 30. The dog stops itself when it elapses. Turn on wheels ≈ 8.7s; on legs ≈ 12s."),
                "gait" to prop(
                    "boolean",
                    "false (default) = wheels, faster. true = walk on the legs, slower. Use true only when the user asks for the legs.",
                    default = false,
                ),
            ),
        ),
        tool(
            name = "dog_sequence",
            method = "dog.sequence",
            description = """
        Run several dog motions back to back — a little routine. Use this
        instead of calling dog_move twice: a second dog_move REPLACES the
        first one rather than queueing behind it, so two moves sent in a row
        is one move, not two.

        Returns immediately; the routine runs in the background and ends with
        the dog stopped. dog_stop interrupts it mid-routine, so a routine is
        also safe for anything long. Progress shows up in dog_status
        (`sequence.step` / `of`), so poll that if you need to know where it is —
        nothing is pushed to you when it finishes.

        Each step is {motion, seconds, gait} with the same meanings as
        dog_move, plus motion="pause": stand still for N seconds. Use a pause
        between direction changes (0.3-0.5s) — the servos settle and the
        movement reads as deliberate rather than as a glitch. Mix gait=false
        and gait=true freely inside one routine for a routine that looks like
        a performance.

        Whole-routine ceiling is 120s, one step is 30s. A routine longer than
        that should be several calls, both so it can be interrupted and so you
        get to look at the result in between.

        Example — turn a half circle, pause, then walk forward and come back:
        [{"motion":"left","seconds":4.4},
         {"motion":"pause","seconds":0.5},
         {"motion":"forward","seconds":2},
         {"motion":"pause","seconds":0.5},
         {"motion":"back","seconds":2}]
    """.trimIndent(),
            required = listOf("steps"),
            props = mapOf(
                "steps" to prop(
                    "array",
                    "Steps in order; each {motion, seconds, gait?} with motion one of forward/back/left/right/pause.",
                ),
            ),
        ),
        tool(
            name = "dog_turn",
            method = "dog.turn",
            description = """
        Turn the dog a MEASURED number of degrees. Prefer this over dog_move for
        every turn — it is the one dog command that knows whether it worked.

        The tablet is bolted to the dog, so the tablet's gyroscope is the dog's:
        this drives until the sensor says the angle has been reached, then stops.
        dog_move with seconds divides a "full circle ≈ 8.7s" constant that drifts
        with battery level, floor surface and where the legs happened to be, so
        it is a guess; this is not.

        Returns IMMEDIATELY, while the dog is still turning — the reply carries
        `expected_seconds`, not a result. The measurement shows up afterwards in
        dog_status.last_turn: `turned_deg` is what it really did, and `reached`
        says whether it got there. Check it when the angle matters (lining up
        with a doorway, facing a person); skip it for a rough spin.

        WHEN IT DOES NOT REACH: `reached: false` with a small `turned_deg` means
        the dog barely rotated — the command did not land, it is out of range,
        switched off, or physically blocked. Do NOT just send it again; say what
        the sensor measured and let the user look.

        Turns are also the safe motion: unlike forward, they are not blocked by
        the dog's obstacle sensor, but they still sweep the dog's body through
        whatever is beside it.

        Turns default to the LEGS. A wheeled turn is a differential spin, so the
        wheels have to scrub sideways across the floor — on carpet they lose and
        the dog barely rotates. Pass `gait: false` only on smooth hard floor, or
        when the user asks for the wheels. Note the legs ignore dog_speed, which
        only changes wheel PWM.

        Use dog_move for forward/back. If this tablet has no gyroscope the call
        fails with an explanation and you should fall back to dog_move.
    """.trimIndent(),
            required = listOf("motion", "degrees"),
            props = mapOf(
                "motion" to propEnum("Which way to turn.", listOf("left", "right")),
                "degrees" to prop("number", "How far to turn, 1-720. 90 = a quarter, 180 = about-face, 360 = a full circle."),
                "gait" to prop(
                    "boolean",
                    "true (default) = turn on the legs. false = spin the wheels, which is faster on hard flat floors but slips on carpet, where the wheels have to scrub sideways. Only pass false if the floor is smooth or the user asks for the wheels.",
                    default = true,
                ),
            ),
        ),
        tool(
            name = "dog_stop",
            method = "dog.stop",
            description = "Stop the dog now — an emergency brake, and the first thing to call whenever the user says 停 / 停下 / 别动了. Interrupts a dog_move that is still running and abandons a dog_sequence part-way. Safe to call when nothing is happening: the reply says honestly whether anything was moving, so do not announce a stop that did not happen. Note the dog would stop on its own at the end of whatever it was told to do; this is for ending it early.",
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "dog_status",
            method = "dog.status",
            description = """
        Is the dog reachable, and what is it doing. Cheap, no side effects —
        call it when something dog-related just failed, or before you tell the
        user anything about the dog's state.

        Connection half: `attached` (is a dongle plugged into this tablet at
        all), `permission` (`no_device` / `needed` / `granted` — `needed` means
        the Android dialog has not been answered yet and the next dog command
        will raise it), `open` (did we get the serial port), `device`, `banner`
        (what the dongle said when it started: "READY" means the dongle and its
        radio are both fine, an "ERR: NRF24L01 not responding" means the dongle
        is alive but its radio module is not — a dongle-side wiring job, do not
        blame the dog), and `error` (why the last attempt failed, in words the
        user can act on).

        Motion half: `moving`, `motion`, `remaining_ms`, `sequence` with the step
        it is on, and `turn` while a dog_turn is under way.

        `last_turn` is how a finished dog_turn reports itself — it appears only
        once the turn is over, and carries `turned_deg` (what the gyroscope
        actually measured), `overshoot_deg`, `reached`, and `stopped_by`. A
        `reached: false` with a tiny `turned_deg` is the strongest evidence this
        system can give that a command never got to the dog.

        `posture` is the tablet's own sensors, read as the dog's — they are
        bolted together. `tilt_deg` is the lean away from standing; anything past
        50° means the dog went over (or was picked up) and the tablet has already
        stopped it, which also shows up as `aborted_by: "fallen"`. It is measured
        against a reference the tablet takes each time a motion starts from rest,
        so if the user says the dog is standing normally and this still reads
        large, the reference is stale — call dog_calibrate, do not tell the user
        their dog has fallen over. `vibration` is
        raw accelerometer energy in m/s²: high while the dog works, near zero
        when nothing is moving. It is NOT calibrated into a "stuck" verdict, so
        read it as evidence and say so — near-zero vibration during a motion you
        believe is running usually means the command did not land, or the dog's
        own obstacle sensor refused it.

        `telemetry` is the dog's own distance and battery, which reach us on the
        acknowledgement of the last command sent. It can be STALE — check
        `age_ms`, and call dog_sense to refresh it before saying anything about
        what is in front of the dog. `supported: false` means no reading has ever
        arrived (older dongle or older dog firmware); say you cannot see it
        rather than reporting a number.

        `speed` and `pose` are what was last successfully set THIS session. Null
        means never set here, which is not the same as knowing the dog is at its
        default — say so instead of guessing.
    """.trimIndent(),
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "dog_sense",
            method = "dog.sense",
            description = """
        Ask the dog what it can see and how much charge it has left, WITHOUT
        telling it to do anything. Fast and safe to call while the dog is
        walking — which is exactly when it is worth calling.

        `telemetry.distance_cm` is the dog's own forward-facing ultrasonic
        sensor. `telemetry.battery_pct` is its battery, 0-100.

        Read `telemetry.age_ms` before you believe either number. The dog only
        speaks when spoken to — the reading rides back on the acknowledgement of
        whatever was last sent — so a value from several seconds ago describes a
        world that has moved. This call refreshes it, so straight after it the
        age is small; a large age in dog_status does not mean the dog is broken.

        `telemetry.supported: false` means no reading has EVER arrived, which is
        what an older dongle or older dog firmware looks like from here. It is
        not the same as "0 cm" and not the same as "flat battery". Say you
        cannot see it rather than reporting a number.

        A null `distance_cm` with `supported: true` means the sensor answered
        nothing — usually nothing within range, sometimes an unplugged sensor.
        The dog's own firmware refuses to walk forward under about 20cm and
        backs up under 10cm on its own, so a forward command that does nothing
        while this reads small is the dog protecting itself, not a lost command.
    """.trimIndent(),
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "dog_calibrate",
            method = "dog.calibrate",
            description = """
        Tell the tablet that the dog is standing normally RIGHT NOW, and take
        that as the reference for "upright". Touches no radio and commands the
        dog to do nothing — it only re-reads the tablet's own sensors.

        The tablet is strapped to the dog's back, so it has no way to know
        which way is "standing" except by being told. Every motion takes this
        reference automatically when it starts from rest, so in normal use you
        never need this tool. Call it when that rule cannot hold:

        - the user re-seated, re-angled or re-mounted the tablet;
        - dog_status shows a `tilt_deg` the user says is wrong — a dog they
          tell you is standing normally, reading 30°;
        - a motion came back `aborted_by: "fallen"` and the dog is upright.

        A stale reference is not cosmetic: past 50° the tablet stops motions on
        its own, so a bad reference makes the dog refuse to walk for a fall
        that is not happening.

        Refuses while the dog is moving (`calibrated: false` with `moving`
        set) — a walking dog pitches with every stride and that would be baked
        into the reference. Stop it, let it settle, then calibrate.

        `was_tilt_deg` is what the tilt read just before this call, against the
        old reference. A large value is the measure of how wrong things were;
        near zero means the reference was already fine.
    """.trimIndent(),
            required = emptyList(),
            props = emptyMap<String, JSONObject>(),
        ),
        tool(
            name = "dog_speed",
            method = "dog.speed",
            description = """
        How fast the dog walks. A setting, not a movement: it does NOT start,
        stop or interrupt anything, and it does not make the dog move on its own.

        It takes effect from the NEXT motion command onwards. A dog already
        walking finishes its current move at the old speed, so to change speed
        mid-task, set it and then issue the next dog_move — do not expect the
        running one to speed up.

        Use slow when the dog is near people, near a desk edge, or being asked to
        stop at a particular spot; the obstacle sensor has less distance to work
        with the faster it goes. normal is the sensible default. turbo is for an
        open corridor with nothing in it.
    """.trimIndent(),
            required = listOf("level"),
            props = mapOf(
                "level" to propEnum(
                    "Walking speed. Prefer slow around people.",
                    listOf("slow", "normal", "fast", "turbo"),
                ),
            ),
        ),
        tool(
            name = "dog_pose",
            method = "dog.pose",
            description = """
        Raise or lower the dog's body. `high` stands it up tall, `normal` puts it
        back down. A setting like dog_speed — it does not move the dog anywhere.

        Raise it to get the tablet's camera up to a person's eye level, or to
        clear a doorsill or cable. Lower it for stability and when carrying the
        tablet at speed.

        One thing to know: dog_stop resets the stance to normal. That is
        deliberate — the raised stance leaves the drive motors live, so a stop
        that kept it would look stopped without being stopped. After any stop,
        set the stance again if you still want it raised.
    """.trimIndent(),
            required = listOf("stance"),
            props = mapOf(
                "stance" to propEnum(
                    "Body height. high raises the tablet to eye level.",
                    listOf("high", "normal"),
                ),
            ),
        ),

        // ------------------------------------------------------------------
        // The notebook — `note.*`, localOnly.
        //
        // These five are the device's own memory, not a capability of the
        // hardware, and they never leave it: `forHub()` strips them from the
        // register payload. Their text is English like every other
        // model-facing string; see the header of LocalPrompt.kt for which
        // Chinese survives inside an English prompt (a label that has to be
        // matched on screen, and a specimen of what a Chinese user says).
        // ------------------------------------------------------------------
        tool(
            localOnly = true,
            name = "remember",
            method = "note.remember",
            description = """
        Put one thing into your notebook. What you write is at hand on every later turn, and the user never has to say it twice.

        ## What belongs in it (about this person, not about this turn)

        - who they are, what they do, how to work with them → type=user
        - they explicitly asked you to change how you do something, or confirmed how you did it (「以后都这样」「别这么干」) → type=feedback
        - you personally got something done for them, walked a route through → type=way
        - an outside entrance (a website / app / account name / address / a shop they go to) → type=reference
        - a route you tried and **confirmed is a failure** → type=failure, with the "never do this again" written into content

        ## What does not belong

        - this turn's transient state (what you are waiting for, where you just tapped)
        - coordinates, pixels, which row — they all change with the interface
        - this time's verification code, order number, amount
        - anything already stated in your system prompt

        ## Fields

        - name: the overwrite key. **Writing the same name again = updating that entry** (the old version is kept), not adding one.
          So when a route improves, write it again under the same name.
        - description: one line, readable on its own — that line alone should say who / what / how to handle it.
          Do not write empty phrases like "the user's preferences"; write "doesn't drink anything cold, winter included".
        - content: the body, omittable (when omitted, description is used). For type=feedback or way, add
          `**Why:**` and `**How to apply:**` lines, so that future you can judge the boundary instead of clinging to one rule.
        - type: see above.
    """.trimIndent(),
            required = listOf("name", "description", "type"),
            props = mapOf(
                "name" to prop("string", "Short identifier, and the overwrite key. e.g. 饮食禁忌 / 咖啡偏好 / 飞书登录"),
                "description" to prop("string", "One self-contained line, ≤120 characters"),
                "content" to prop("string", "The body. When omitted, description is used."),
                "type" to propEnum(
                    "Category of memory",
                    listOf("user", "feedback", "way", "reference", "failure"),
                ),
            ),
        ),
        tool(
            localOnly = true,
            name = "recall",
            method = "note.recall",
            description = """
        Look in the notebook.

        **Look once before you start.** Does this look like something you have done before? What has this person said about this kind of thing?
        When something written or spoken disagrees with the notebook, go by the notebook and put the question to them; do not act on your own impression.

        An empty query = pull a batch of the more important and recently used entries ("what have I written down").
        What comes back is name and summary; to get the body, look that name up again with recall.
    """.trimIndent(),
            required = emptyList(),
            props = mapOf(
                "query" to prop("string", "Keyword. Empty = pull a batch of the important / recently used ones"),
                "type" to propEnum(
                    "Only look in this category",
                    listOf("user", "feedback", "way", "reference", "failure"),
                ),
                "limit" to prop("integer", "How many to return, default 8, max 30"),
                "scope" to propEnum("Where to look. Currently only memory", listOf("memory")),
            ),
        ),
        tool(
            localOnly = true,
            name = "forget",
            method = "note.forget",
            description = """
        Delete one entry from the notebook.

        **The user will not say "delete the memory" — they do not know it exists, so you have to see it yourself.**
        These are the signals that mean delete:

        - they corrected something from before: 「不对，其实是……」
        - they changed a preference or a decision: 「之前说的那个不做了」
        - old and new contradict: the notebook says they do not use 微信, and they now say 「加我微信」
        - that thing no longer exists: 「那个活动下线了」「那个需求不要了」

        `recall` first to check the name before deleting, and **do not delete from impression** — a deletion cannot be undone.
        If only the content changed (rather than the entry being void), overwrite it with remember under the same name; do not delete.
    """.trimIndent(),
            required = listOf("name"),
            props = mapOf(
                "name" to prop("string", "The name of the entry to delete; recall first to confirm"),
            ),
        ),
        tool(
            localOnly = true,
            name = "todo",
            method = "note.todo",
            description = """
        Set a "thing to do at a certain time" for yourself.

        ## The rule, first

        Whenever you intend to tell the user "I'll come and find you then / I'll remind you / I'll handle it for you then", **you must call this tool and see the success result before that sentence leaves your mouth**.
        Say it without having set it, and when that moment arrives you will not appear — that is not "might forget", that is lying.

        ## How to write `what`

        One sentence, written for future you to read: what to **do** or **say** at that time.
        "Remind him to take his medicine" is too vague; "tell him it's time for his medicine, and ask how he slept last night" is something that can be executed.

        ## When it triggers (at and cron — one or the other; not both, not neither)

        - `at`: one-off. Local time `YYYY-MM-DD HH:mm`. **Absolute times only** — for "in ten minutes", work the time out yourself and fill in the result.
        - `cron`: recurring. Standard 5 fields, "minute hour day month weekday". e.g. every day at 8 = `0 8 * * *`;
          every Monday at 9 = `0 9 * * 1`; every half hour = `0,30 * * * *`.

        🔴 **The densest is once every 15 minutes.** Anything denser is refused — that is a system limit, not your choice.
        When it is refused, tell them honestly "the fastest is once every 15 minutes" — **do not paper over it in different words, and do not say "ok, it's set"**.

        The result carries `next_run_local` and `scheduled`: `inexact` means the time may be off by a few minutes
        (the norm when there is no exact-alarm permission). When you see `inexact`, say only "I'll speak up around that time" —
        **do not say "on the dot"**.
    """.trimIndent(),
            required = listOf("what"),
            props = mapOf(
                "what" to prop("string", "One sentence: what to do / say at that time"),
                "at" to prop("string", "One-off, local time YYYY-MM-DD HH:mm"),
                "cron" to prop("string", "Recurring, 5 fields \"minute hour day month weekday\". Densest is once every 15 minutes"),
            ),
        ),
        tool(
            localOnly = true,
            name = "todos",
            method = "note.todos",
            description = """
        See and manage the todos you have set.

        - `list`: look. By default only the ones not yet finished (pending); `filter=all` includes finished ones, `filter=cron` shows only recurring ones.
        - `done`: it is handled, close it. **Always close it once handled**, or it hangs in "promised but never done" forever.
        - `drop`: not doing it, cancel it.
        - `pause`: park a recurring todo for now.
        - `resume`: bring back a paused one. A recurring one recounts its next run from now; a one-off whose moment has already passed cannot be resumed,
          so have the user set a new one.

        When the user asks "what have you promised me" or "what is still outstanding", `list`.
        Take `id` from the result of `list` — do not make one up.
    """.trimIndent(),
            required = listOf("action"),
            props = mapOf(
                "action" to propEnum(
                    "What to do",
                    listOf("list", "done", "drop", "pause", "resume"),
                ),
                "id" to prop("string", "Required when action is not list; take it from list"),
                "filter" to propEnum("Used with list", listOf("pending", "all", "cron")),
            ),
        ),
    )

    /** Server-side lookup: given a hub-facing tool name, return the method
     *  string CommandDispatcher expects. */
    fun methodOf(toolName: String): String? =
        TOOLS.firstOrNull { it.getString("name") == toolName }?.optString("method")?.ifEmpty { null }

    // ---- builders ----

    /**
     * @param localOnly true for the brain's private notebook tools: kept in
     *   [all] (the on-device brain sees them) but stripped by [forHub]. Set
     *   explicitly on each of the five rather than inferred from the `note.`
     *   prefix — a tool silently escaping to the hub is not something that
     *   should happen because someone renamed something.
     */
    private fun tool(
        name: String, method: String, description: String,
        required: List<String>, props: Map<String, JSONObject>,
        localOnly: Boolean = false,
    ): JSONObject {
        val propsObj = JSONObject()
        for ((k, v) in props) propsObj.put(k, v)
        val reqArr = JSONArray()
        for (r in required) reqArr.put(r)
        val o = JSONObject()
            .put("name", name)
            .put("method", method)
            .put("description", description)
            .put(
                "parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", propsObj)
                    .put("required", reqArr)
            )
        if (localOnly) o.put("localOnly", true)
        return o
    }

    private fun prop(type: String, description: String, default: Any? = null): JSONObject {
        val o = JSONObject().put("type", type).put("description", description)
        if (default != null) o.put("default", default)
        return o
    }

    private fun propEnum(description: String, values: List<String>): JSONObject {
        val arr = JSONArray()
        for (v in values) arr.put(v)
        return JSONObject()
            .put("type", "string")
            .put("description", description)
            .put("enum", arr)
    }
}
