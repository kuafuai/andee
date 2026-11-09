package net.kuafuai.andee.wake

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The enrolled wake-word takes, on disk.
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
     * Slack over the user's own *closest* pair. See [thresholdFor] for why it is
     * the closest and not the worst, and for the measurements that decided it.
     *
     * Below ~1.1 the phrase has to be said more consistently than anyone says
     * anything; above ~1.4 the door is open wide enough for unrelated speech to
     * walk in.
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
     * **The upper bound is no longer a guess.** It was 2.8, which was picked as
     * "the widest thing that could still be called a threshold" and never
     * measured — and the first sixty judgements this device ever logged put
     * every non-wake utterance it heard at **2.21–2.59**, i.e. entirely *below*
     * it. 2.8 was not a bound at all, it was a door left open. 1.8 comes from
     * the same sixty: the widest value that would still have rejected all
     * fifty-seven of them while accepting all three real attempts (1.16–1.46).
     *
     * One voice, one room, one phrase — thin evidence, and the honest reading of
     * it is "better founded than the number it replaces", not "correct". The
     * `no wake: d=… > …` line in [WakeWord] is what a near miss looks like in
     * logcat, and is still the evidence to move these on.
     */
    private const val MIN_THRESHOLD = 1.0f
    private const val MAX_THRESHOLD = 1.8f

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

    /**
     * The distance between the two most alike takes, times [SLACK], clamped.
     *
     * **The closest pair, not the worst** — a correction the device paid for.
     * The worst pair was the obvious choice: it is the honest answer to "how
     * much does this user vary?". It is also the wrong question, because it is
     * not what matching does. [bestDistance] returns the *nearest* template, so
     * an utterance is accepted when it lands close to some take. The width the
     * door needs is therefore "how far can a real attempt be from the nearest
     * take", and the only evidence in the file for that is the takes that did
     * land close.
     *
     * Two enrolments measured on the tablet — same voice, same room, twenty
     * minutes apart, and the same logs:
     *
     * ```
     * worst pair  1.82 / 1.69   →  thresholds 2.28 / 2.11   (what shipped)
     * best  pair  1.40 / 1.30   →  thresholds 1.75 / 1.62   (what this computes)
     * ```
     *
     * The same voice's *actual* attempts, from logcat, landed at **1.16, 1.38,
     * 1.46** — nearer even than the tightest enrolment pair. A wake attempt is
     * made deliberately; an enrolment take is made while reading a card and
     * reaching for a button. Anchoring on the worst take was anchoring on the
     * one sample nobody wants to recognise.
     *
     * Where the impostors were is the other half: **2.21–2.59**, every one of
     * the fifty-seven. So 2.28 was not a threshold standing between two
     * distributions, it was a line through the middle of one of them — sixteen
     * of the sixty judgements sat within 0.06 of it.
     *
     * The residual risk is the documented one, and it is why the enrolment card
     * tells the user to say the phrase the way they will actually say it: three
     * takes that come out *unusually* alike give a tight door and a phrase that
     * has to be repeated carefully forever. [MIN_THRESHOLD] bounds that; the
     * alternative it replaces — a door so wide the room walks in — costs the
     * user an upload to 火山 they never asked for, which is the worse side of
     * the asymmetry the bounds are built on.
     */
    private fun thresholdFor(templates: List<Array<FloatArray>>): Float {
        var best = Float.MAX_VALUE
        var pairs = 0
        for (i in templates.indices) {
            for (j in i + 1 until templates.size) {
                val d = Dtw.distance(templates[i], templates[j])
                if (d == Dtw.NO_MATCH) continue
                pairs++
                if (d < best) best = d
            }
        }
        // No comparable pair at all — the takes differed so much in length that
        // DTW refused them. Nothing was learned, so fall back to the middle of
        // the useful range rather than to a number computed from nothing.
        if (pairs == 0) return (MIN_THRESHOLD + MAX_THRESHOLD) / 2f
        return (best * SLACK).coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)
    }

    /**
     * The user's sensitivity setting applied to the learned threshold.
     *
     * The learned value stays the anchor (see [thresholdFor]) and the setting
     * only scales it, so `normal` is byte-for-byte the old behaviour. The
     * factors are read against the one set of measurements there is: real
     * attempts at 1.16–1.46, unrelated speech at 2.21–2.59. `loose` on a
     * threshold already at [MAX_THRESHOLD] reaches 2.16, still short of every
     * impostor measured; `looser` reaches into them, which is the trade the
     * user is choosing and the settings note says so. [EFFECTIVE_MAX] keeps
     * even that below the far end of the impostors, and [EFFECTIVE_MIN] keeps
     * `strict` from asking for a recording.
     */
    fun effectiveThreshold(store: Store, level: String): Float {
        val factor = when (level) {
            "strict" -> 0.85f
            "loose" -> 1.2f
            "looser" -> 1.4f
            else -> 1.0f
        }
        if (factor == 1.0f) return store.threshold
        return (store.threshold * factor).coerceIn(EFFECTIVE_MIN, EFFECTIVE_MAX)
    }

    private const val EFFECTIVE_MIN = 0.85f
    private const val EFFECTIVE_MAX = 2.4f

    /**
     * How well [candidate] matches the enrolled phrase: the closest template,
     * or [Dtw.NO_MATCH]. Compared against [effectiveThreshold] by the caller.
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
