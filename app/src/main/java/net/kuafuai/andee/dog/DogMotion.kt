package net.kuafuai.andee.dog

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Turns "move forward for two seconds" into a command, a timer, and a stop.
 *
 * [DogLink] is the cable; this is the part that knows what a *move* is. The
 * dog holds its last command until it is given another one, so every motion
 * here has an end: a single move schedules its own "J" for N seconds later, and
 * a sequence sends "J" after its last step. Nothing in this file can leave the
 * dog running.
 *
 * ## Why nothing here blocks
 *
 * This is the load-bearing decision in the whole feature. The brain's requests
 * arrive on `BodyWsClient`'s **single**-threaded worker, so a `dog_move` that
 * blocked for its own duration would hold that one thread for the whole move —
 * and a `dog_stop` the user asked for in the meantime would sit in the queue
 * behind it, unable to stop anything. The dog would then run for exactly as
 * long as the brain originally asked, no matter who shouted at it.
 *
 * So `move` returns the moment the command is out, and the stop is a timer's
 * job. The consequence is that two moves sent back to back do not queue — the
 * second supersedes the first — which is why multi-step motion needs
 * [sequence] rather than the brain calling `move` twice.
 *
 * ## Why a superseded timer cannot stop the wrong dog
 *
 * Timers and cancels race: the auto-stop for move #1 can be inside its "write
 * J" while move #2's "write K" is on its way out, and if the J lands second
 * the dog stops the instant it starts. Every scheduled stop therefore carries
 * the [generation] it was armed under, and the check plus the write happen
 * inside one [motionLock] critical section — so a stop either wins outright or
 * aborts, and never lands after the move that replaced it.
 *
 * ## The one thing here that is closed-loop
 *
 * [move] and [sequence] are open-loop by necessity: the 2.4G link only ever
 * sends, so the dog cannot report where it got to. [turn] is the exception,
 * because the tablet is bolted to the dog and therefore turns with it —
 * [DogSense] reads the tablet's own gyroscope and that is the dog's. So a turn
 * runs until the *measured* angle is reached rather than for a duration divided
 * out of a "8.7 s per revolution" constant that drifts with battery and floor.
 *
 * The same sensor pays for one reflex the brain is too far away to have: a
 * motion whose tilt crosses [FALL_TILT_DEG] is stopped here, on the tablet,
 * within [GUARD_MS] — a round trip to the brain and back is seconds, and a dog
 * that has gone over does not have seconds.
 */
class DogMotion(
    private val link: DogLink,
    private val sense: DogSense,
) {

    /** One leg of a sequence: a motion held for a duration. */
    data class Step(val cmd: Char, val label: String, val ms: Long)

    private val motionLock = Any()

    /**
     * Bumped by every new motion. A scheduled stop whose generation no longer
     * matches has been superseded and must do nothing — see the class note.
     */
    private var generation = 0L

    @Volatile private var currentLabel: String? = null
    @Volatile private var currentCmd: Char? = null
    @Volatile private var movingUntilMs = 0L

    /** Non-null while a sequence is walking its steps. */
    @Volatile private var seq: SeqRun? = null

    /** Non-null while a closed-loop [turn] is running. */
    @Volatile private var turnRun: TurnRun? = null

    /**
     * The last finished turn, kept because [turn] returns before the dog has
     * stopped moving — so the honest answer to "how far did it actually go"
     * does not exist yet when the tool replies. `dog_status` carries it.
     */
    @Volatile private var lastTurn: JSONObject? = null

    /** Set when a guard, not a command, ended the last motion. */
    @Volatile private var lastAbort: String? = null

    /**
     * Speed and stance as last *acknowledged*, not as last asked for.
     *
     * The authoritative copy lives in the dongle's frame; this is only what to
     * tell the brain. Null means never set this session — which is not the same
     * as knowing the dog is at its firmware default, and reads that way in
     * `dog_status` rather than guessing.
     */
    @Volatile private var currentSpeed: String? = null
    @Volatile private var currentPose: String? = null

    private class SeqRun(
        val gen: Long,
        val labels: List<String>,
        val totalMs: Long,
        @Volatile var index: Int = 0,
    )

    private class TurnRun(
        val gen: Long,
        val targetDeg: Double,
        val dir: String,
        val label: String,
        val timeoutMs: Long,
        @Volatile var turned: Double = 0.0,
    )

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "DogMotion-timer").apply { isDaemon = true }
        }
    private val seqExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DogMotion-seq").apply { isDaemon = true }
    }

    init {
        // The cable can vanish mid-move. We cannot send "J" down a cable that
        // is no longer there, so this only forgets what we thought was
        // happening — and deliberately does *not* touch [generation]. Bumping
        // it here would invalidate the stop timer of a move whose link came
        // back, leaving that move with nothing to end it. The scheduled stop
        // stays armed and no-ops on its own instead (see [autoStop]), because a
        // lost link already cleared the state it tests.
        link.onLost = {
            synchronized(motionLock) {
                currentLabel = null
                currentCmd = null
                movingUntilMs = 0
                seq = null
            }
        }
    }

    // ------------------------------------------------------------------ moves

    /**
     * Start one motion and return immediately; it stops itself after [seconds].
     *
     * Returns the radio layer's verdict for the *command* (`ack`: did the dog
     * acknowledge the frame) alongside what the tablet will now do. Those are
     * different facts and both matter: a NO-ACK with a scheduled stop means the
     * tablet believes it is driving a dog that never heard it.
     */
    fun move(motion: String, seconds: Double, gait: Boolean): JSONObject {
        val (cmd, label) = resolve(motion, gait)
            ?: throw IllegalArgumentException(
                "unknown motion '$motion' — use forward, back, left or right"
            )
        val ms = validate(seconds)
        val where = if (gait) "legged" else "wheeled"
        beginSensing()

        synchronized(motionLock) {
            generation++
            val gen = generation
            seq = null
            turnRun = null
            lastAbort = null
            // The dog is standing right now — whatever ran before it ended with
            // a "J" — so this is the moment a tilt reference is true.
            if (currentLabel == null) sense.markUpright()
            val ack = link.send(cmd)
            // Ask the link how it actually is, rather than assuming the write
            // worked: a cable yanked out between open() and write() surfaces
            // here, and reporting a dog that is "moving" on a dead link is the
            // one lie this feature cannot afford.
            val open = link.status().optBoolean("open")
            if (!open) {
                currentCmd = null
                currentLabel = null
                movingUntilMs = 0
                return JSONObject()
                    .put("sent", false)
                    .put("motion", "$where $label")
                    .put("ack", if (ack.ok) "OK" else "NO-ACK")
                    .put("error", link.status().optString("error", "USB link is down"))
                    .put("note", "The command did not get through and the dog will not move — " +
                        "check the dongle is plugged in with its light on, then retry")
            }
            currentCmd = cmd
            currentLabel = "$where $label"
            movingUntilMs = SystemClock.elapsedRealtime() + ms
            scheduler.schedule(Runnable { autoStop(gen) }, ms, TimeUnit.MILLISECONDS)
            armFallGuard(gen)
            return JSONObject()
                .put("sent", true)
                .put("motion", "$where $label")
                .put("seconds", seconds)
                .put("ack", if (ack.ok) "OK" else "NO-ACK")
                .put("stops_by_itself", true)
                .put("note", "Moving; it stops itself after ${seconds} s — call dog_stop to " +
                    "stop it sooner")
        }
    }

    /**
     * Run several legs in order, on its own thread, and return at once.
     *
     * This is the tool for anything with more than one beat — "转一圈再往前走"
     * — because [move] supersedes rather than queues. It is also the only way to
     * run a script longer than the hub's request timeout, since nobody waits for
     * it here. The last step is followed by "J" no matter how the run ends,
     * including on error, which is the rule the Mac-side `dog_run.py` already
     * established.
     *
     * Progress is not reported anywhere except [status]; the brain polls if it
     * cares, and the user can see the dog.
     */
    fun sequence(rawSteps: JSONArray): JSONObject {
        require(rawSteps.length() > 0) { "dog_sequence needs at least one step" }
        val steps = ArrayList<Step>(rawSteps.length())
        var totalMs = 0L
        for (i in 0 until rawSteps.length()) {
            val o = rawSteps.optJSONObject(i)
                ?: throw IllegalArgumentException("step ${i + 1} is not an object")
            val motion = o.optString("motion").lowercase()
            val gait = o.optBoolean("gait", false)
            val ms = validate(o.optDouble("seconds", Double.NaN))
            // "pause" is a beat with the dog standing — useful between
            // direction changes, where the servos need to settle before the
            // next leg, and cheaper to say than a move of zero seconds.
            val step = if (motion == "pause") {
                Step('J', "pause", ms)
            } else {
                val (cmd, label) = resolve(motion, gait)
                    ?: throw IllegalArgumentException(
                        "step ${i + 1}: unknown motion '$motion' — " +
                            "use forward, back, left, right or pause"
                    )
                Step(cmd, (if (gait) "legged " else "wheeled ") + label, ms)
            }
            steps.add(step)
            totalMs += ms
        }
        if (totalMs > MAX_SEQUENCE_MS) {
            throw IllegalArgumentException(
                "The whole sequence is ${totalMs / 1000}s, too long (ceiling " +
                    "${MAX_SEQUENCE_MS / 1000}s) — split it into several calls, so it can " +
                    "be stopped in between"
            )
        }
        beginSensing()

        synchronized(motionLock) {
            generation++
            val gen = generation
            val run = SeqRun(gen, steps.map { it.label }, totalMs)
            seq = run
            turnRun = null
            lastAbort = null
            if (currentLabel == null) sense.markUpright()
            currentLabel = steps.first().label
            currentCmd = steps.first().cmd
            movingUntilMs = SystemClock.elapsedRealtime() + totalMs
            seqExec.execute { runSequence(run, steps) }
            armFallGuard(gen)
            return JSONObject()
                .put("started", true)
                .put("steps", steps.size)
                .put("total_seconds", totalMs / 1000.0)
                .put("plan", JSONArray(steps.map { it.label }))
                .put("note", "The sequence runs in the background and winds itself up to a " +
                    "stop after ${totalMs / 1000.0} s; call dog_stop to stop it sooner")
        }
    }

    private fun runSequence(run: SeqRun, steps: List<Step>) {
        try {
            for ((i, step) in steps.withIndex()) {
                // Cancelled or superseded between steps — leave without sending
                // anything. Whoever superseded us owns the dog now.
                if (!stillCurrent(run.gen)) return
                run.index = i
                synchronized(motionLock) {
                    if (run.gen != generation) return
                    link.send(step.cmd, awaitAck = true)
                }
                Log.i(TAG, "sequence step ${i + 1}/${steps.size}: ${step.label} for ${step.ms}ms")
                // Sliced sleep: `dog_stop` bumps the generation and the next
                // slice notices, so an interrupted sequence releases the dog in
                // ~100ms instead of finishing its step first.
                var slept = 0L
                while (slept < step.ms) {
                    if (!stillCurrent(run.gen)) return
                    val slice = minOf(SLICE_MS, step.ms - slept)
                    Thread.sleep(slice)
                    slept += slice
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "sequence failed", e)
        } finally {
            synchronized(motionLock) {
                if (run.gen != generation) {
                    // Superseded: the thing that replaced us sent its own
                    // command and owns the stop. Writing "J" here would cancel
                    // it.
                    return@synchronized
                }
                link.send('J', awaitAck = false)
                currentLabel = null
                currentCmd = null
                movingUntilMs = 0
                seq = null
            }
            Log.i(TAG, "sequence done, dog stopped")
        }
    }

    /** Does this motion still own the dog? */
    private fun stillCurrent(gen: Long): Boolean = synchronized(motionLock) { gen == generation }

    // ------------------------------------------------------------- closed loop

    /**
     * Turn a measured number of degrees and stop when the gyroscope says so.
     *
     * This is the only motion here that knows whether it worked. The tablet is
     * bolted to the dog, so [DogSense] reading the tablet's rotation *is* the
     * dog's rotation; the loop drives until the angle is reached rather than for
     * a duration computed from a revolution constant that changes with battery
     * level, floor and stride phase.
     *
     * Returns immediately, like everything else in this class — the loop runs on
     * [seqExec] and the reply says how long to expect. The measured result lands
     * in `dog_status.last_turn` a moment after the dog has actually stopped,
     * which is the earliest it is true.
     *
     * Failure is reported, not hidden: a turn that times out having moved four
     * degrees says so, and that is the clearest evidence this system can produce
     * that a command never reached the dog.
     */
    fun turn(motion: String, degrees: Double, gait: Boolean): JSONObject {
        val dir = motion.lowercase()
        if (dir != "left" && dir != "right") {
            throw IllegalArgumentException(
                "dog_turn only accepts left or right (got '$motion') — for forward/back " +
                    "use dog_move"
            )
        }
        require(!degrees.isNaN()) { "degrees is required" }
        require(degrees > 0) { "degrees must be positive, got $degrees" }
        require(degrees <= MAX_TURN_DEG) {
            "at most ${MAX_TURN_DEG.toInt()}° in one turn (got $degrees) — split it into " +
                "several turns and you can look at the dog in between"
        }
        if (!sense.available) {
            throw IllegalStateException(
                "A closed-loop turn needs a rotation sensor: ${sense.unavailableReason()}. " +
                    "Use dog_move and time it in seconds instead — a full circle is about " +
                    "12 s legged and 8.7 s wheeled (wheeled spins slip on carpet; prefer legged)."
            )
        }
        beginSensing()

        val (cmd, name) = resolve(dir, gait)!!
        val where = if (gait) "legged" else "wheeled"
        val label = "$where $name $degrees°"
        // Generous: the point of the ceiling is to end a turn that is not
        // happening, not to cut short a slow one. Scaled off the open-loop
        // estimate so a 20° nudge does not sit here for ten seconds.
        val estimateMs = (degrees / 360.0 * (if (gait) GAIT_360_MS else WHEEL_360_MS)).toLong()
        val timeoutMs = (estimateMs * TIMEOUT_SLACK).toLong()
            .coerceIn(TIMEOUT_FLOOR_MS, (MAX_STEP_S * 1000).toLong())

        synchronized(motionLock) {
            generation++
            val gen = generation
            seq = null
            lastAbort = null
            if (currentLabel == null) sense.markUpright()
            // Zero the counter and get the first command out here, not on the
            // loop thread, for the same reason [move] does: the cable's state is
            // only knowable once something has been written to it. A turn that
            // reported "started" and then spent its whole timeout measuring zero
            // would end by blaming the dog for an unplugged dongle — and the
            // brain would send the user to look at a machine that is fine.
            sense.markTurn()
            val ack = link.send(cmd)
            val open = link.status().optBoolean("open")
            if (!open) {
                currentCmd = null
                currentLabel = null
                movingUntilMs = 0
                turnRun = null
                return JSONObject()
                    .put("started", false)
                    .put("motion", label)
                    .put("ack", if (ack.ok) "OK" else "NO-ACK")
                    .put("error", link.status().optString("error", "USB link is down"))
                    .put("note", "The command did not get through and the dog will not move — " +
                        "check the dongle is plugged in with its light on, then retry")
            }
            val run = TurnRun(gen, degrees, dir, label, timeoutMs)
            turnRun = run
            currentLabel = label
            currentCmd = cmd
            movingUntilMs = SystemClock.elapsedRealtime() + timeoutMs
            seqExec.execute { runTurn(run) }
            return JSONObject()
                .put("started", true)
                .put("motion", label)
                .put("target_deg", degrees)
                .put("ack", if (ack.ok) "OK" else "NO-ACK")
                .put("expected_seconds", round1(estimateMs / 1000.0))
                .put("note", "Turning; it stops itself at ${degrees}° — that angle is " +
                    "measured by the gyroscope, not timed off a stopwatch. Read " +
                    "dog_status.last_turn in about ${round1(estimateMs / 1000.0)} s for the " +
                    "angle actually reached; call dog_stop to stop it sooner")
        }
    }

    /**
     * The turn's polling loop. The first command is already out (see [turn]);
     * this only watches the gyroscope and decides when to write "J".
     *
     * Runs on [seqExec] for the same reason [runSequence] does, and shares it
     * deliberately: only one motion can own the dog at a time, so the two can
     * never need to run together, and a second executor would only add a thread
     * that is idle whenever this one is not.
     */
    private fun runTurn(run: TurnRun) {
        var stoppedBy = "reached"
        try {
            val deadline = SystemClock.elapsedRealtime() + run.timeoutMs
            while (true) {
                if (!stillCurrent(run.gen)) return  // superseded; the new owner drives
                val turned = abs(sense.turnedDeg())
                run.turned = turned
                // Lead the target by however far the current rate carries the
                // dog during the stop latency. This is a control-loop estimate,
                // not a claim about the machine — `overshoot_deg` in the result
                // is the measurement, and it is what to tune STOP_LEAD_MS by.
                val lead = abs(sense.turnRateDegPerSec()) * STOP_LEAD_MS / 1000.0
                if (turned + lead >= run.targetDeg) break
                if (sense.tiltDeg() >= FALL_TILT_DEG) { stoppedBy = "fallen"; break }
                if (SystemClock.elapsedRealtime() > deadline) { stoppedBy = "timeout"; break }
                Thread.sleep(POLL_MS)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            stoppedBy = "interrupted"
        } catch (e: Exception) {
            Log.w(TAG, "turn failed", e)
            stoppedBy = "error"
        } finally {
            val atStop = abs(sense.turnedDeg())
            val stillOpen = link.status().optBoolean("open")
            val owned = synchronized(motionLock) {
                if (run.gen != generation) return@synchronized false
                link.send('J', awaitAck = false)
                currentLabel = null
                currentCmd = null
                movingUntilMs = 0
                turnRun = null
                if (stoppedBy == "fallen") lastAbort = "fallen"
                true
            }
            if (owned) {
                // The dog is still rotating as this runs: servos settle and
                // wheels coast. Waiting it out is the only way the number we
                // report is the angle the dog actually ended up at.
                runCatching { Thread.sleep(COAST_MS) }
                val settled = abs(sense.turnedDeg())
                val secs = round1(run.timeoutMs / 1000.0)
                lastTurn = JSONObject()
                    .put("motion", run.label)
                    .put("target_deg", run.targetDeg)
                    .put("turned_deg", round1(settled))
                    .put("overshoot_deg", round1(settled - run.targetDeg))
                    .put("at_stop_deg", round1(atStop))
                    .put("stopped_by", stoppedBy)
                    .put("reached", stoppedBy == "reached")
                    .put(
                        "note", when {
                            stoppedBy == "reached" -> "Reached the target; measured ${round1(settled)}°"
                            // The cable going away mid-turn is its own diagnosis
                            // and must not be reported as the dog misbehaving.
                            stoppedBy == "timeout" && !stillOpen ->
                                "The USB link dropped mid-turn and only ${round1(settled)}° was " +
                                    "measured — the dongle came out, it is not the dog's fault; " +
                                    "have the user check the OTG cable"
                            stoppedBy == "timeout" -> "Timed out, having measured only " +
                                "${round1(settled)}° in $secs s (target ${run.targetDeg}°) — the " +
                                "dog may be out of range, switched off, or stuck; take a look first"
                            stoppedBy == "fallen" -> "The body tilted past ${FALL_TILT_DEG.toInt()}° " +
                                "so it was stopped in a hurry — the dog has probably fallen over " +
                                "or been picked up; tell the user to go and look"
                            else -> "The turn ended abnormally ($stoppedBy); measured ${round1(settled)}°"
                        }
                    )
                Log.i(TAG, "turn done: ${run.label} → ${round1(settled)}° ($stoppedBy)")
            }
        }
    }

    /**
     * Stop the dog if it goes over, without asking anyone.
     *
     * The brain is at the other end of a WebSocket and a language model; the
     * round trip is seconds even when nothing is wrong. A dog that has tipped
     * while being told to drive is the one case where the tablet both *can* see
     * the problem and cannot afford to relay it, so the reflex lives here.
     *
     * Re-arms itself rather than running on a fixed schedule, so it stops the
     * moment the motion it was armed for stops owning the dog — there is never
     * a guard outliving its own motion.
     */
    private fun armFallGuard(gen: Long) {
        if (!sense.available) return
        scheduler.schedule(object : Runnable {
            override fun run() {
                var tripped = false
                synchronized(motionLock) {
                    if (gen != generation || currentLabel == null) return
                    if (sense.tiltDeg() >= FALL_TILT_DEG) {
                        link.send('J', awaitAck = false)
                        // Supersede, so the move's own auto-stop finds a newer
                        // generation and does not fire into whatever comes next.
                        generation++
                        currentLabel = null
                        currentCmd = null
                        movingUntilMs = 0
                        seq = null
                        turnRun = null
                        lastAbort = "fallen"
                        tripped = true
                    }
                }
                if (tripped) {
                    Log.w(TAG, "fall guard: tilt over $FALL_TILT_DEG°, dog stopped")
                    return
                }
                scheduler.schedule(this, GUARD_MS, TimeUnit.MILLISECONDS)
            }
        }, GUARD_MS, TimeUnit.MILLISECONDS)
    }

    /** Sensors are registered on first use, never at service start. */
    private fun beginSensing() {
        runCatching { sense.attach() }
            .onFailure { Log.w(TAG, "sense attach failed", it) }
    }

    private fun autoStop(gen: Long) {
        synchronized(motionLock) {
            if (gen != generation) {
                // Superseded by a newer motion, which armed its own stop.
                Log.i(TAG, "auto-stop for generation $gen skipped (superseded)")
                return
            }
            if (currentLabel == null && currentCmd == null) {
                // The link was lost and the state was already cleared, so there
                // is nothing running to stop. Sending "J" here would do nothing
                // but make ensureOpen() go looking for a USB device that is not
                // there.
                Log.i(TAG, "auto-stop with nothing running — nothing to send")
                return
            }
            link.send('J', awaitAck = false)
            currentLabel = null
            currentCmd = null
            movingUntilMs = 0
            seq = null
        }
        Log.i(TAG, "auto-stop: dog stopped")
    }

    // ------------------------------------------------------------------- stop

    /**
     * Stop now, whoever asked.
     *
     * Always sends "J", even when nothing looks like it is running — this is
     * the brake, and a brake that declines to press itself because the tablet's
     * bookkeeping says the dog is idle is not a brake. The dog could have been
     * started by the original remote, or left running by an earlier session;
     * "J" is the one command that is safe to send blind (it means *stand*).
     * Which also makes this the cheapest way to prove the whole link end to end
     * without moving anything.
     *
     * The reply is then reported honestly: `was_moving` says whether we knew of
     * a motion, and `ack` says whether the machine confirmed the stop. A note
     * that claims the dog is standing has to be backed by an OK.
     */
    fun stop(): JSONObject {
        val wasMoving: String?
        val ack: DogLink.Ack
        synchronized(motionLock) {
            wasMoving = currentLabel
            generation++
            currentLabel = null
            currentCmd = null
            movingUntilMs = 0
            seq = null
            turnRun = null
            ack = link.send('J', awaitAck = true)
            // The dongle's 'J' clears the stance byte too (a raised stance would
            // otherwise keep the wheel motors running through a "stop"), so the
            // stance we report has to fall back with it.
            if (ack.ok) currentPose = "normal"
        }
        val note = when {
            !ack.ok -> ack.reply.ifEmpty {
                link.status().optString("error", "the stop command did not get through")
            }
            wasMoving == null ->
                "Nothing was running in the first place; the stop command went out and the " +
                    "machine acknowledged it, so the dog is now standing"
            else -> "Stop command sent, acknowledged by the machine"
        }
        return JSONObject()
            .put("stopped", ack.ok)
            .put("was_moving", wasMoving ?: JSONObject.NULL)
            .put("ack", if (ack.ok) "OK" else "NO-ACK")
            .put("note", note)
    }

    // ------------------------------------------------------------- settings

    /**
     * Gait speed. A setting, not a motion — so it deliberately does not touch
     * [generation], arm a stop, or cancel what the dog is doing.
     *
     * The dongle holds the speed byte across frames precisely so this can be
     * changed mid-walk: on the dog side speed is four independent `if`s that
     * sit *beside* the direction chain, so a frame carrying only a speed byte
     * would fall through to the standing branch and stop the dog. That memory
     * lives in the dongle; from here it is one character.
     *
     * It takes effect on the next frame the dongle sends, which for a dog
     * already walking is the next command — not immediately. Say so rather
     * than implying the dog speeds up as the tool returns.
     */
    fun speed(level: String): JSONObject {
        val (ch, label) = when (level.lowercase()) {
            "slow" -> '1' to "slow"
            "normal" -> '2' to "normal"
            "fast" -> '3' to "fast"
            "turbo" -> '4' to "turbo"
            else -> throw IllegalArgumentException(
                "speed must be slow / normal / fast / turbo (got $level)"
            )
        }
        val ack = link.send(ch)
        currentSpeed = if (ack.ok) label else currentSpeed
        return JSONObject()
            .put("speed", label)
            .put("applied", ack.ok)
            .put("ack", if (ack.ok) "OK" else "NO-ACK")
            .put(
                "note",
                if (ack.ok) "Takes effect from the next motion command; a motion already " +
                    "under way waits for its current step to end"
                else link.status().optString("error", "no reply received")
            )
    }

    /**
     * Stance: `high` raises the body (the firmware's `Buf[4]==0x05` branch),
     * `normal` puts it back down.
     *
     * Also a setting, and also held by the dongle — but with one asymmetry
     * worth knowing: a stop (`J`) clears it, because the raised-stance branch
     * on the dog sits *ahead* of the wheel branches and only poses the legs
     * without cutting the wheel motors. A "stop" that left the stance set
     * would look stopped and not be.
     */
    fun pose(stance: String): JSONObject {
        val (ch, label) = when (stance.lowercase()) {
            "high" -> 'P' to "raised"
            "normal" -> 'p' to "normal"
            else -> throw IllegalArgumentException(
                "stance must be high / normal (got $stance)"
            )
        }
        val ack = link.send(ch)
        if (ack.ok) currentPose = label
        return JSONObject()
            .put("pose", label)
            .put("applied", ack.ok)
            .put("ack", if (ack.ok) "OK" else "NO-ACK")
            .put("note", "A stop (dog_stop) resets the stance — the raised stance does not " +
                "cut the wheel motors")
    }

    /**
     * Take the tablet's current attitude as "standing upright".
     *
     * Every motion already re-takes this when it starts from rest, so this
     * exists for the case that rule cannot cover: the tablet was re-seated,
     * re-angled or re-strapped between motions. The old reference then makes a
     * standing dog read as leaning, and past [FALL_TILT_DEG] the fall guard
     * kills motions that were never in trouble.
     *
     * Refuses while something is running. The reference has to be taken with
     * the dog genuinely still — a walking dog is pitching a few degrees with
     * every stride, and baking that into the reference is worse than the stale
     * one it replaces.
     */
    fun calibrate(): JSONObject {
        beginSensing()
        if (!sense.available) {
            return JSONObject()
                .put("calibrated", false)
                .put("why", sense.unavailableReason())
        }
        val running = synchronized(motionLock) { currentLabel }
        if (running != null) {
            return JSONObject()
                .put("calibrated", false)
                .put("moving", running)
                .put("why", "The dog is running \"$running\", and calibrating while it moves " +
                    "gives a wrong reference — dog_stop first, wait for it to stand still, " +
                    "then calibrate")
        }
        // Sensors are registered lazily, so the first call here can arrive
        // before any sample has. Nothing to do but wait a beat for one.
        val deadline = SystemClock.elapsedRealtime() + CALIBRATE_WAIT_MS
        while (!sense.fresh() && SystemClock.elapsedRealtime() < deadline) {
            try {
                Thread.sleep(CALIBRATE_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!sense.fresh()) {
            return JSONObject()
                .put("calibrated", false)
                .put("why", "The sensor has not produced a reading yet — try again; if it " +
                    "keeps doing this, the tablet's rotation sensor is faulty")
        }
        val before = sense.tiltDeg()
        sense.markUpright()
        return JSONObject()
            .put("calibrated", true)
            .put("was_tilt_deg", if (before.isNaN()) JSONObject.NULL else round1(before))
            .put("vibration", round1(sense.vibration()))
            .put(
                "note",
                "The current attitude is now recorded as \"standing\". From here tilt_deg " +
                    "is measured from it, and so is the fall guard — which is why this is " +
                    "only done when the dog is genuinely standing still"
            )
    }

    /**
     * Ask the dog for a fresh distance/battery reading without commanding it.
     *
     * Bounded and quick (one frame, one ack), so it is the one `dog.*` call
     * that may be made while a motion runs — which is when the distance
     * actually matters.
     */
    fun poll(): JSONObject {
        link.poll()
        return JSONObject()
            .put("telemetry", link.telemetry())
            .put("moving", currentLabel != null)
            .put("motion", currentLabel ?: JSONObject.NULL)
    }

    // ----------------------------------------------------------------- status

    /** Link facts plus motion facts, flattened so the brain reads one object. */
    fun status(): JSONObject {
        val out = link.status()
        val label = currentLabel
        val run = seq
        val turning = turnRun
        out.put("moving", label != null)
        out.put("motion", label ?: JSONObject.NULL)
        out.put(
            "remaining_ms",
            if (label != null) (movingUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            else JSONObject.NULL
        )
        out.put(
            "sequence",
            if (run == null) JSONObject.NULL
            else JSONObject()
                .put("running", true)
                .put("step", run.index + 1)
                .put("of", run.labels.size)
                .put("plan", JSONArray(run.labels))
        )
        out.put(
            "turn",
            if (turning == null) JSONObject.NULL
            else JSONObject()
                .put("running", true)
                .put("target_deg", turning.targetDeg)
                .put("turned_deg", round1(turning.turned))
        )
        // Only ever the *last finished* turn: while one is running the honest
        // answer is in `turn` above, and reporting a stale result beside a live
        // motion is how a brain ends up narrating the wrong number.
        out.put("last_turn", if (turning == null) (lastTurn ?: JSONObject.NULL) else JSONObject.NULL)
        out.put("posture", sense.status())
        out.put("speed", currentSpeed ?: JSONObject.NULL)
        out.put("pose", currentPose ?: JSONObject.NULL)
        out.put("aborted_by", lastAbort ?: JSONObject.NULL)
        return out
    }

    /** Service teardown: cancel every timer, put the dog down, release threads. */
    fun shutdown() {
        synchronized(motionLock) {
            generation++
            runCatching { link.send('J', awaitAck = false) }
            currentLabel = null
            currentCmd = null
            movingUntilMs = 0
            seq = null
            turnRun = null
        }
        scheduler.shutdownNow()
        seqExec.shutdownNow()
        runCatching { sense.shutdown() }
    }

    // -------------------------------------------------------------- vocabulary

    /**
     * Motion name (and whether the legs are used) → the dongle's character.
     *
     * The wheel and gait characters are different sets, and the gait *turns*
     * are the trap: the dog's own firmware comments label `E` as 左转 and `D`
     * as 右转, and measured behaviour is the opposite (E turns right, D turns
     * left). The mapping below follows the measurement — the same correction
     * the dog's `NRF24_Dongle/README.md` and its `/dog` skill both carry.
     */
    private fun resolve(motion: String, gait: Boolean): Pair<Char, String>? =
        when (motion.lowercase()) {
            "forward" -> (if (gait) 'B' else 'K') to "forward"
            "back" -> (if (gait) 'C' else 'L') to "back"
            "left" -> (if (gait) 'D' else 'M') to "left"
            "right" -> (if (gait) 'E' else 'N') to "right"
            else -> null
        }

    /**
     * One leg's duration, in range. Rejected rather than clamped, for the same
     * reason the coordinate tools reject out-of-range values: a silently
     * shortened move is a dog that did not go where the brain thinks it went.
     */
    private fun validate(seconds: Double): Long {
        require(!seconds.isNaN()) { "seconds is required" }
        require(seconds > 0) { "seconds must be positive, got $seconds" }
        require(seconds <= MAX_STEP_S) {
            "One step is at most ${MAX_STEP_S.toInt()} s (got $seconds) — split it into " +
                "several steps, or string them together with dog_sequence"
        }
        return (seconds * 1000).toLong()
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    private companion object {
        const val TAG = "Dog"

        /** Matches the Mac-side `dog_run.py` per-step ceiling. */
        const val MAX_STEP_S = 30.0

        /**
         * Whole-sequence ceiling. Longer than one step because a sequence is
         * exactly what you use when you want to string things together, but
         * still bounded: a runaway dog nobody is watching is the failure mode
         * this whole class exists to prevent.
         */
        const val MAX_SEQUENCE_MS = 120_000L

        /** How often a running sequence checks whether it was cancelled. */
        const val SLICE_MS = 100L

        // ------------------------------------------------------------- turning

        /** Two revolutions. Past that, look at the dog between attempts. */
        const val MAX_TURN_DEG = 720.0

        /**
         * Open-loop revolution times, measured 2026-09-04 and kept here only to
         * size a turn's timeout and tell the brain how long to wait. Nothing
         * depends on them being right any more — that is the point of [turn].
         */
        const val WHEEL_360_MS = 8_700.0
        const val GAIT_360_MS = 12_000.0

        /** A turn gets this much more than the open-loop estimate before it is
         *  declared not to be happening. */
        const val TIMEOUT_SLACK = 2.5

        /** …but never less than this, so small turns still get a fair chance. */
        const val TIMEOUT_FLOOR_MS = 4_000L

        /** How often the turn loop reads the gyroscope. */
        const val POLL_MS = 40L

        /**
         * Assumed delay between writing "J" and the dog actually ceasing to
         * rotate; the loop stops this much of a rotation early. An estimate of
         * *our* latency, not a claim about the machine — `overshoot_deg` in
         * `last_turn` is the measurement to tune it against.
         */
        const val STOP_LEAD_MS = 150.0

        /** How long to let the dog coast before reporting the angle it reached. */
        const val COAST_MS = 600L

        // -------------------------------------------------------------- guards

        /**
         * Tilt from standing, in degrees, past which a motion is cut off. A dog
         * on four legs does not lean this far doing anything normal, so the
         * false-positive cost is near zero, while the miss cost is a machine
         * driving its legs against the floor on its side.
         */
        const val FALL_TILT_DEG = 50.0

        /** How often the fall guard looks, while a motion is running. */
        const val GUARD_MS = 200L

        // ----------------------------------------------------------- calibrate

        /**
         * How long [calibrate] waits for a first sensor sample. Sensors are
         * registered lazily, so a calibration issued as the session's first
         * dog command has nothing to reference for a few tens of ms. Short
         * enough that a tablet with a dead sensor answers quickly instead of
         * reading as a hung tool call.
         */
        const val CALIBRATE_WAIT_MS = 800L

        const val CALIBRATE_POLL_MS = 30L
    }
}
