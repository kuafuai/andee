package net.kuafuai.andee.config

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A five-field cron matcher, written here instead of pulled in.
 *
 * Why not a library: this module already carries a dependency pinned backwards
 * because of a kotlin-stdlib clash (`usb-serial-for-android` 3.8.1, see
 * app/build.gradle). A cron library would be a second thing to pin, for one
 * function — "when does this fire next". The grammar below is the whole of
 * what a model can reasonably be asked to produce.
 *
 * Per field: `*`, `n`, `a-b`, a step suffix (`/n`), and comma lists of those.
 * Day-of-week is 0–6 with 0 (and 7) = Sunday. Day matching follows
 * croniter/Vixie: when **both** day-of-month and day-of-week are restricted, a
 * day matches if either one does.
 *
 * All arithmetic is local time via java.time (minSdk is 30, so it is always
 * present) — the user means "8 in the morning here", not UTC.
 */
object CronExpr {

    /**
     * The floor for a repeating todo.
     *
     * Not a product preference: `AlarmManager.setAndAllowWhileIdle` is
     * rate-limited to roughly one wake per 9 minutes under Doze, and Android
     * discourages minute-level polling even with the exact-alarm permission.
     * A schedule finer than this cannot be honoured, so it is refused up front
     * — the model has to tell the user "this can't be done, fifteen minutes is
     * the fastest" instead of promising something the device will silently
     * miss.
     */
    const val MIN_INTERVAL_MINUTES = 15

    /**
     * How far ahead to search for the next match. 1500 days covers the worst
     * legitimate case (a yearly expression, or Feb 29 inside four years) while
     * still terminating quickly for an expression that can never fire, e.g.
     * `0 0 30 2 *`.
     */
    private const val MAX_LOOKAHEAD_DAYS = 1500L

    class Parsed internal constructor(
        internal val minutes: IntArray,
        internal val hours: IntArray,
        internal val doms: IntArray,
        internal val months: IntArray,
        internal val dows: IntArray,
        internal val domRestricted: Boolean,
        internal val dowRestricted: Boolean,
    ) {
        /**
         * Smallest gap between two fires, in minutes.
         *
         * Computed over a single unrestricted day, so a weekly expression
         * reports 1440 rather than 10080 — a **lower** bound, which is the
         * right direction for the guard that reads it: a schedule that is
         * genuinely slow enough is never refused.
         */
        fun minGapMinutes(): Int {
            val marks = ArrayList<Int>(hours.size * minutes.size)
            for (h in hours) for (m in minutes) marks.add(h * 60 + m)
            if (marks.size <= 1) return 1440
            marks.sort()
            var gap = Int.MAX_VALUE
            for (i in 1 until marks.size) gap = minOf(gap, marks[i] - marks[i - 1])
            gap = minOf(gap, 1440 - marks.last() + marks.first())
            return gap
        }
    }

    /**
     * Parse, or throw with a message written for the model to act on.
     *
     * The messages name the offending field and what was wrong with it — a
     * bare "invalid cron" costs a round trip and teaches nothing, and the
     * model is the thing that has to explain the problem to the user.
     */
    fun parse(expr: String): Parsed {
        val fields = expr.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(fields.size == 5) {
            "cron takes 5 fields, \"minute hour day month weekday\"; got ${fields.size}: ${expr.trim()}"
        }
        val minutes = field(fields[0], 0, 59, "minute")
        val hours = field(fields[1], 0, 23, "hour")
        val doms = field(fields[2], 1, 31, "day-of-month")
        val months = field(fields[3], 1, 12, "month")
        val dows = field(fields[4], 0, 7, "weekday").map { if (it == 7) 0 else it }
            .distinct().sorted().toIntArray()
        return Parsed(
            minutes = minutes,
            hours = hours,
            doms = doms,
            months = months,
            dows = dows,
            domRestricted = fields[2].trim() != "*",
            dowRestricted = fields[4].trim() != "*",
        )
    }

    private fun field(spec: String, min: Int, max: Int, label: String): IntArray {
        val out = sortedSetOf<Int>()
        for (raw in spec.split(",")) {
            val part = raw.trim()
            require(part.isNotEmpty()) { "empty item in the $label field: $spec" }
            val rangePart = part.substringBefore("/")
            val step = if (part.contains("/")) {
                part.substringAfter("/").trim().toIntOrNull()?.takeIf { it > 0 }
                    ?: throw IllegalArgumentException("bad step in the $label field: $part")
            } else {
                1
            }
            val lo: Int
            val hi: Int
            when {
                rangePart == "*" -> {
                    lo = min
                    hi = max
                }
                rangePart.contains("-") -> {
                    lo = rangePart.substringBefore("-").trim().toIntOrNull()
                        ?: throw IllegalArgumentException("bad $label field: $part")
                    hi = rangePart.substringAfter("-").trim().toIntOrNull()
                        ?: throw IllegalArgumentException("bad $label field: $part")
                }
                else -> {
                    val v = rangePart.toIntOrNull()
                        ?: throw IllegalArgumentException("bad $label field: $part")
                    lo = v
                    hi = v
                }
            }
            require(lo in min..max && hi in min..max) {
                "$label field out of range ($min-$max): $part"
            }
            require(lo <= hi) { "$label field has its range reversed: $part" }
            var v = lo
            while (v <= hi) {
                out.add(v)
                v += step
            }
        }
        require(out.isNotEmpty()) { "no value matches in the $label field: $spec" }
        return out.toIntArray()
    }

    /**
     * The next fire strictly after [fromMillis], or null when the expression
     * can never fire (e.g. `0 0 30 2 *`).
     *
     * Walks day by day and only descends into hours/minutes once the day
     * matches, so a yearly expression costs 366 iterations of a loop, not half
     * a million.
     */
    fun nextAfter(expr: String, fromMillis: Long): Long? = nextAfter(parse(expr), fromMillis)

    fun nextAfter(p: Parsed, fromMillis: Long): Long? {
        val zone = ZoneId.systemDefault()
        var t = LocalDateTime.ofInstant(Instant.ofEpochMilli(fromMillis), zone)
            .truncatedTo(ChronoUnit.MINUTES)
            .plusMinutes(1)
        val limit = t.plusDays(MAX_LOOKAHEAD_DAYS)
        while (t.isBefore(limit)) {
            if (t.monthValue !in p.months || !dayMatches(p, t)) {
                t = t.toLocalDate().plusDays(1).atStartOfDay()
                continue
            }
            if (t.hour in p.hours) {
                val m = p.minutes.firstOrNull { it >= t.minute }
                if (m != null) {
                    return t.withMinute(m).withSecond(0).withNano(0)
                        .atZone(zone).toInstant().toEpochMilli()
                }
            }
            val nextHour = p.hours.firstOrNull { it > t.hour }
            t = if (nextHour != null) {
                t.withHour(nextHour).withMinute(0).withSecond(0).withNano(0)
            } else {
                t.toLocalDate().plusDays(1).atStartOfDay()
            }
        }
        return null
    }

    private fun dayMatches(p: Parsed, t: LocalDateTime): Boolean {
        val domOk = p.doms.contains(t.dayOfMonth)
        val dowOk = p.dows.contains(dowOf(t))
        return when {
            p.domRestricted && p.dowRestricted -> domOk || dowOk
            p.domRestricted -> domOk
            p.dowRestricted -> dowOk
            else -> true
        }
    }

    /** cron's day-of-week: 0 = Sunday, 6 = Saturday. */
    private fun dowOf(t: LocalDateTime): Int =
        if (t.dayOfWeek == DayOfWeek.SUNDAY) 0 else t.dayOfWeek.value
}
