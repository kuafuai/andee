package net.kuafuai.andee.wake

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The enrolled "嘿 Andee" takes, on disk.
 *
 * Three recordings reduced to [Mfcc] frames — a few hundred floats each, no
 * audio kept. That is a deliberate privacy property as much as a size one:
 * nothing recoverable as speech ever lands in storage, and there is no server
 * to send it to in the first place.
 *
 * The stored threshold matters more than the templates. It is derived from how
 * far apart the user's *own* three takes landed ([thresholdFor]), which is the
 * only calibration available: how consistently a person says their own wake
 * phrase, in their room, into this microphone, varies enormously between
 * people, and a constant tuned on one voice is either deaf or trigger-happy on
 * the next.
 */
object WakeTemplates {

    private const val FILE_NAME = "wake_templates.json"
    private const val TAG = "Wake"

    /** How many takes enrollment asks for. Three pairs is the least that says anything. */
    const val ENROLL_COUNT = 3

    /**
     * Slack over the user's own worst pair. Below ~1.1 the phrase has to be
     * said more consistently than anyone says anything; above ~1.4 the door is
     * open wide enough for unrelated speech to walk in.
     */
    private const val SLACK = 1.25f

    /**
     * Hard bounds on the learned threshold, in the units [Dtw] returns after
     * [Mfcc]'s CMVN. A user who enrolls three nearly identical takes would
     * otherwise end up with a threshold so tight only a recording could pass
     * it; one who mumbles differently each time would get one that accepts the
     * television.
     *
     * The band is deliberately biased low. The two failure modes are not
     * symmetric: too tight and the user taps the ball instead — a small
     * annoyance with an obvious workaround. Too loose and a stray sentence
     * opens the microphone and ships audio to 火山 without anyone asking, which
     * is both a surprise and a cost. So when in doubt, be deaf.
     *
     * These numbers are a starting point, not a measurement — a second take of
     * the same phrase lands well under 1.0 on synthetic audio, but real voices
     * spread wider and no real recording has been through this yet. The
     * `no wake: d=… > …` line in [WakeWord] is what a near miss looks like in
     * logcat, and is the evidence to move them on.
     */
    private const val MIN_THRESHOLD = 1.0f
    private const val MAX_THRESHOLD = 2.8f

    class Store(val threshold: Float, val templates: List<Array<FloatArray>>)

    @Volatile
    private var cache: Store? = null

    @Volatile
    private var loaded = false

    fun load(context: Context): Store? {
        cache?.let { return it }
        if (loaded) return null
        loaded = true
        val f = File(context.filesDir, FILE_NAME)
        if (!f.exists()) return null
        val store = runCatching {
            val root = JSONObject(f.readText())
            val arr = root.getJSONArray("templates")
            val templates = ArrayList<Array<FloatArray>>(arr.length())
            for (i in 0 until arr.length()) {
                val rows = arr.getJSONArray(i)
                templates.add(
                    Array(rows.length()) { r ->
                        val row = rows.getJSONArray(r)
                        FloatArray(row.length()) { c -> row.getDouble(c).toFloat() }
                    }
                )
            }
            Store(root.getDouble("threshold").toFloat(), templates)
        }.onFailure { Log.w(TAG, "templates unreadable, ignoring", it) }.getOrNull()
        cache = store
        return store
    }

    fun enrolled(context: Context): Boolean =
        (load(context)?.templates?.size ?: 0) > 0

    /** Persist [templates] with a threshold derived from them. Returns the threshold. */
    fun save(context: Context, templates: List<Array<FloatArray>>): Float {
        val threshold = thresholdFor(templates)
        val arr = JSONArray()
        for (t in templates) {
            val rows = JSONArray()
            for (frame in t) {
                val row = JSONArray()
                for (v in frame) row.put(v.toDouble())
                rows.put(row)
            }
            arr.put(rows)
        }
        val root = JSONObject()
            .put("version", 1)
            .put("threshold", threshold.toDouble())
            .put("templates", arr)
        runCatching { File(context.filesDir, FILE_NAME).writeText(root.toString()) }
            .onFailure { Log.w(TAG, "template save failed", it) }
        cache = Store(threshold, templates)
        loaded = true
        Log.i(TAG, "enrolled ${templates.size} takes, threshold=$threshold")
        return threshold
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
        cache = null
        loaded = true
    }

    /** Drop the cache without touching the file; next [load] re-reads it. */
    fun invalidate() {
        cache = null
        loaded = false
    }

    /** Worst distance between any two takes, plus [SLACK], clamped. */
    private fun thresholdFor(templates: List<Array<FloatArray>>): Float {
        var worst = 0f
        var pairs = 0
        for (i in templates.indices) {
            for (j in i + 1 until templates.size) {
                val d = Dtw.distance(templates[i], templates[j])
                if (d == Dtw.NO_MATCH) continue
                pairs++
                if (d > worst) worst = d
            }
        }
        // No comparable pair at all — the takes differed so much in length that
        // DTW refused them. Nothing was learned, so fall back to the middle of
        // the useful range rather than to a number computed from nothing.
        if (pairs == 0) return (MIN_THRESHOLD + MAX_THRESHOLD) / 2f
        return (worst * SLACK).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
    }

    /**
     * How well [candidate] matches the enrolled phrase: the closest template,
     * or [Dtw.NO_MATCH]. Compared against [Store.threshold] by the caller.
     */
    fun bestDistance(store: Store, candidate: Array<FloatArray>): Float {
        var best = Dtw.NO_MATCH
        for (t in store.templates) {
            val d = Dtw.distance(t, candidate)
            if (d < best) best = d
        }
        return best
    }
}
