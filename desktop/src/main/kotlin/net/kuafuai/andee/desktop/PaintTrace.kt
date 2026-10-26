package net.kuafuai.andee.desktop

/** Dev only (-Dandee.ballTrace): what the ball's per-frame UI-thread work costs, logged every 3 s. */
internal object PaintTrace {
    private var n = 0; private var sum = 0L; private var max = 0L; private var t0 = System.nanoTime()
    @Synchronized fun record(ns: Long) {
        n++; sum += ns; if (ns > max) max = ns
        val now = System.nanoTime()
        if (now - t0 > 3_000_000_000L) {
            net.kuafuai.andee.util.Log.i("BallTrace", "frames=$n avg=${sum / n / 1000}us max=${max / 1000}us fps=${n * 1e9 / (now - t0)}")
            n = 0; sum = 0; max = 0; t0 = now
        }
    }
}

/** Dev only: the render thread's own per-frame cost, split into draw / readback / flip. */
internal object GlTrace {
    private var n = 0; private var d = 0L; private var r = 0L; private var f = 0L; private var rMax = 0L; private var t0 = System.nanoTime()
    @Synchronized fun record(draw: Long, read: Long, flip: Long) {
        n++; d += draw; r += read; f += flip; if (read > rMax) rMax = read
        val now = System.nanoTime()
        if (now - t0 > 3_000_000_000L) {
            net.kuafuai.andee.util.Log.i("BallTrace", "gl frames=$n draw=${d / n / 1000}us readback=${r / n / 1000}us(max ${rMax / 1000}) flip=${f / n / 1000}us")
            n = 0; d = 0; r = 0; f = 0; rMax = 0; t0 = now
        }
    }
}
