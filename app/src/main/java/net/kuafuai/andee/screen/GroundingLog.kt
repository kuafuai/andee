package net.kuafuai.andee.screen

import android.content.Context
import android.util.Log
import net.kuafuai.andee.config.VoiceConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Append-only record of what the brain saw and where it then touched, kept so
 * the mis-taps can be mined later.
 *
 * ## Why there is anything to mine
 *
 * The creed is that the model never does coordinate arithmetic: with an
 * element list it says `tap_screen_element {"eid":"e7"}` and the device
 * resolves the pixels. Coordinates only come out when there is no list —
 * self-drawn panes — and that is precisely the ground where a general-purpose
 * model is weakest (AndroidLab: GPT-4o 31.2 vs GUI-tuned 49.5). What happens
 * there is a mis-tap, then a retry a bit to the side, then a hit. That
 * sequence is a labelled example — image, wrong point, right point — produced
 * for free by ordinary operation and, until now, thrown away.
 *
 * Note what is NOT recoverable: tap accuracy itself. [net.kuafuai.andee.ui.TapMarkerUi]'s
 * ring is drawn at the same x,y handed to the `StrokeDescription`, so there is
 * no second measurement anywhere in the loop and therefore no error term to
 * calibrate. The only error this can see is the brain's — which point it
 * chose — and that is learned from, not corrected for.
 *
 * ## Why the device does no pairing
 *
 * Everything here is a dumb append. Deciding which tap was a miss is a
 * heuristic, heuristics are wrong at first, and one baked into the device can
 * only be fixed by shipping an APK and re-collecting. `tools/grounding_pair.py`
 * makes those calls offline against the raw log, so a better rule can be
 * re-run over data already on disk.
 *
 * The strongest label is already computed on-device for another purpose and
 * simply not written down: `ScreenController.guardRepetition` refuses the 4th
 * gesture in a 120px bucket. Its scope is exactly this data set — it covers
 * `tapNorm`/`longPressNorm` and exempts e-taps — so a bucket that tripped it
 * means every coordinate tap in that bucket missed. Hence `refused`.
 *
 * ## Rules it lives under
 *
 * - **Never block a tool call.** A slow handler reads to the brain as "still
 *   working", so the calling thread only builds a JSONObject in memory; the
 *   append and the ~400KB PNG write go to [exec].
 * - **Off by default**, behind `grounding` in [VoiceConfig].
 * - **Bounded.** [MAX_BYTES] of PNGs, oldest deleted; JSONL rotates at
 *   [MAX_JSONL_BYTES] with [JSONL_KEEP] generations.
 * - **Its own directory.** `grounding/`, never `captures/` — that one holds
 *   the user's manual top-bar saves and has no cleanup, so rotating into it
 *   would delete their files.
 */
object GroundingLog {

    private const val TAG = "Body"

    /** Bump when a field changes meaning; the offline script keys off it. */
    private const val SCHEMA = 1

    private const val MAX_BYTES = 512L * 1024 * 1024
    private const val MAX_JSONL_BYTES = 16L * 1024 * 1024
    private const val JSONL_KEEP = 4

    /**
     * `listFiles()` over a few thousand PNGs is slow enough to notice, so the
     * byte count is kept in memory and only re-derived from disk this often.
     */
    private const val SWEEP_EVERY = 50

    @Volatile private var appCtx: Context? = null
    @Volatile private var dir: File? = null
    @Volatile private var shotsDir: File? = null
    @Volatile private var on = false

    private val seq = AtomicLong(0)
    private val shotSeq = AtomicLong(0)
    private var writesSinceSweep = 0
    private var bytesOnDisk = -1L

    /**
     * Its own single daemon thread — deliberately not the service's
     * "ScreenTools" executor, which serialises the user's top-bar buttons: a
     * 400KB write queued ahead of a button press would be visible as lag. It
     * is also a private field of the service, which [ScreenController] has no
     * handle on.
     *
     * Nullable, and rebuilt by [init], because this `object` outlives the
     * service that started it. [shutdown] runs from `onDestroy`, and on a
     * HONOR/MagicOS device switching the accessibility service off and back
     * on destroys and reconnects it **inside one process** — so the second
     * `init` used to find a terminated pool and `execute` on it. That throws
     * `RejectedExecutionException` *on the main thread*, from
     * `onServiceConnected`: the service could not come back at all, which is
     * the worst possible failure for the one repair path the self-check
     * itself recommends ("turn it off and on again"). A writer that can be
     * rebuilt turns that crash into a `Log.w`.
     */
    @Volatile private var exec: ExecutorService? = null
    private val execLock = Any()

    /**
     * Call once from `onServiceConnected`, alongside the other
     * `object.init(this)` singletons. Reads the switch once — flipping it in
     * settings takes effect on the next service start, which is the same
     * bargain every other pref here makes.
     *
     * Also the *only* place allowed to bring the writer back after [shutdown]
     * tore it down, because only this call is guaranteed to be the start of a
     * real session. [post] and [noteShot] drop their write instead of
     * resurrecting a thread the service has already said goodbye to.
     */
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        on = VoiceConfig.groundingEnabled(app)
        if (!on) return
        restartWriter()
        submit("grounding init failed") {
            val root = File(app.getExternalFilesDir(null), "grounding")
            val shots = File(root, "shots")
            shots.mkdirs()
            dir = root
            shotsDir = shots
            sweepFromDisk()
            // append, not just note: `note` stamps a record and hands it back,
            // it does not write. This line called it bare, so the session event
            // — the one record every offline pass needs to know what the screen
            // was and which way round — was built and dropped on the floor.
            // Measured on the phone before the fix: 174 events in events.jsonl
            // and not one of them `session`. [noteTapCoord] already assumes it
            // exists ("the session record is written at service start").
            append(
                note(
                    JSONObject()
                        .put("ev", "session")
                        .put("screen_w", app.resources.displayMetrics.widthPixels)
                        .put("screen_h", app.resources.displayMetrics.heightPixels)
                        .put(
                            "orientation",
                            if (app.resources.configuration.orientation ==
                                android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            ) "landscape" else "portrait",
                        )
                )
            )
        }
    }

    /**
     * Record the image the brain is about to be shown, and hand back the id
     * that names it. Called with the PNG already encoded — re-capturing or
     * re-compressing to log would double the cost of every screenshot.
     *
     * Returns synchronously (the id is just a counter) so the live response
     * can carry `shot_id` and be matched to the file offline; the bytes are
     * written on [exec].
     */
    fun noteShot(
        png: ByteArray,
        srcW: Int,
        srcH: Int,
        outW: Int,
        outH: Int,
        reason: String,
        pkg: String?,
        markerPx: IntArray?,
        markerAgeMs: Long?,
    ): String? {
        if (!on) return null
        val id = "s_%d_%04d".format(System.currentTimeMillis(), shotSeq.incrementAndGet())
        val o = JSONObject()
            .put("ev", "shot")
            .put("shot_id", id)
            .put("file", "shots/$id.png")
            .put("src_w", srcW).put("src_h", srcH)
            .put("w", outW).put("h", outH)
            .put("bytes", png.size)
            .put("reason", reason)
        if (pkg != null) o.put("pkg", pkg)
        if (markerPx != null) {
            o.put("marker_px", org.json.JSONArray().put(markerPx[0]).put(markerPx[1]))
            if (markerAgeMs != null) o.put("marker_age_ms", markerAgeMs)
        }
        submit("grounding shot failed") {
            val d = shotsDir ?: return@submit
            File(d, "$id.png").writeBytes(png)
            bytesOnDisk = if (bytesOnDisk < 0) -1L else bytesOnDisk + png.size
            if (++writesSinceSweep >= SWEEP_EVERY || bytesOnDisk > MAX_BYTES) sweep()
            append(o)
        }
        return id
    }

    /**
     * A tap the brain aimed by coordinate — the samples this whole file
     * exists for. `refused` means the stuck-loop guard rejected it, which is
     * the strongest miss label available.
     */
    fun noteTapCoord(
        nx: Int, ny: Int, px: Int, py: Int, screenW: Int, screenH: Int,
        hold: Boolean, refused: Boolean, pkg: String?,
    ) {
        if (!on) return
        post(
            JSONObject()
                .put("ev", "tap").put("mode", "coord")
                .put("nx", nx).put("ny", ny)
                .put("px", px).put("py", py)
                // Per event, not once per session: the session record is written
                // at service start and a rotation afterwards would silently make
                // it a lie, so anything converting px↔norm offline needs the
                // size that was true for THIS tap.
                .put("screen_w", screenW).put("screen_h", screenH)
                .put("hold", hold).put("refused", refused)
                .also { if (pkg != null) it.put("pkg", pkg) }
        )
    }

    /**
     * A tap resolved from an e-number. Not a training sample — the device did
     * the geometry — but it has to be told apart from the coordinate ones, and
     * it marks the screen as changed for the offline pairing.
     */
    fun noteTapEid(
        eid: String, px: Int, py: Int, screenW: Int, screenH: Int,
        hold: Boolean, relocated: Boolean, pkg: String?,
    ) {
        if (!on) return
        post(
            JSONObject()
                .put("ev", "tap").put("mode", "eid")
                .put("eid", eid).put("px", px).put("py", py)
                .put("screen_w", screenW).put("screen_h", screenH)
                .put("hold", hold).put("relocated", relocated)
                .also { if (pkg != null) it.put("pkg", pkg) }
        )
    }

    /** Scrolling moves everything; the offline pairing must not read across one. */
    fun noteSwipe(nx1: Int, ny1: Int, nx2: Int, ny2: Int, durationMs: Long, actor: String) {
        if (!on) return
        post(
            JSONObject()
                .put("ev", "swipe")
                .put("nx1", nx1).put("ny1", ny1).put("nx2", nx2).put("ny2", ny2)
                .put("duration_ms", durationMs)
                .put("actor", actor)
        )
    }

    /**
     * back / home / recents. `actor` matters more than the action: the user's
     * own top-bar presses land here too, and an episode a human steered is not
     * evidence about what the brain was aiming at.
     */
    fun noteNav(action: String, actor: String) {
        if (!on) return
        post(JSONObject().put("ev", "nav").put("action", action).put("actor", actor))
    }

    /** What the element list looked like — i.e. whether the brain had a choice. */
    fun noteTree(pkg: String?, total: Int, useful: Int, elements: Int, surface: String?) {
        if (!on) return
        post(
            JSONObject()
                .put("ev", "tree")
                .put("nodes_total", total)
                .put("nodes_useful", useful)
                .put("elements", elements)
                .also {
                    if (pkg != null) it.put("pkg", pkg)
                    if (surface != null) it.put("surface", surface)
                }
        )
    }

    /**
     * Let the queue drain rather than dropping it — these are small writes.
     *
     * Clears [exec] so the writers after this are dropped quietly instead of
     * throwing into whoever called them. [init] is what starts a new one, and
     * until it does this log is closed on purpose.
     */
    fun shutdown() {
        if (!on) return
        val e = synchronized(execLock) { exec.also { exec = null } } ?: return
        e.shutdown()
        runCatching { e.awaitTermination(2, TimeUnit.SECONDS) }
    }

    // ---- internals ---------------------------------------------------------

    /**
     * The one place a thread is created. A terminated pool is never handed
     * back: the service can be reconnected into the same process, and
     * `execute` on a terminated pool is a `RejectedExecutionException` on the
     * caller's thread — which, from `onServiceConnected`, means no service.
     */
    private fun restartWriter(): ExecutorService = synchronized(execLock) {
        val cur = exec
        if (cur != null && !cur.isShutdown) return cur
        Executors.newSingleThreadExecutor(
            ThreadFactory { r -> Thread(r, "Grounding").apply { isDaemon = true } }
        ).also { exec = it }
    }

    /**
     * Never throws. A write that arrives after [shutdown] has nowhere to go
     * and is dropped with a line in logcat; the alternative is carrying a
     * `RejectedExecutionException` into a `screen.*` tool call.
     */
    private fun submit(what: String, block: () -> Unit) {
        val e = exec ?: return
        runCatching { e.execute { runCatching(block).onFailure { Log.w(TAG, what, it) } } }
            .onFailure { Log.w(TAG, "$what (write dropped)", it) }
    }

    /** Stamp on the calling thread (ordering must reflect when it happened), write on ours. */
    private fun post(o: JSONObject) {
        val stamped = note(o)
        submit("grounding append failed") { append(stamped) }
    }

    private fun note(o: JSONObject): JSONObject = o
        .put("v", SCHEMA)
        .put("ts", System.currentTimeMillis())
        .put("seq", seq.incrementAndGet())

    /** [exec] only. */
    private fun append(o: JSONObject) {
        val d = dir ?: return
        val f = File(d, "events.jsonl")
        if (f.length() > MAX_JSONL_BYTES) rotate(f)
        f.appendBytes((o.toString() + "\n").toByteArray())
    }

    /** [exec] only. events.jsonl → .1 → .2 …, dropping the oldest. */
    private fun rotate(f: File) {
        runCatching {
            File(f.parentFile, "events.jsonl.$JSONL_KEEP").delete()
            for (i in JSONL_KEEP - 1 downTo 1) {
                val src = File(f.parentFile, "events.jsonl.$i")
                if (src.exists()) src.renameTo(File(f.parentFile, "events.jsonl.${i + 1}"))
            }
            f.renameTo(File(f.parentFile, "events.jsonl.1"))
        }
    }

    /** [exec] only. */
    private fun sweepFromDisk() {
        bytesOnDisk = -1L
        writesSinceSweep = SWEEP_EVERY
        sweep()
    }

    /**
     * [exec] only. Delete oldest-first until back under [MAX_BYTES]. The
     * directory listing is the expensive part, which is why this runs every
     * [SWEEP_EVERY] writes rather than on each one.
     */
    private fun sweep() {
        writesSinceSweep = 0
        val d = shotsDir ?: return
        val files = d.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total > MAX_BYTES) {
            files.sortBy { it.lastModified() }
            for (f in files) {
                if (total <= MAX_BYTES) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        }
        bytesOnDisk = total
    }
}
