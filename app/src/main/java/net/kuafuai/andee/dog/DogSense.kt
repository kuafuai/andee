package net.kuafuai.andee.dog

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The tablet's own IMU, read as if it were the dog's.
 *
 * The tablet is bolted to the dog, so every rotation of the dog is a rotation
 * of this device — which makes the accelerometer and gyroscope already in the
 * tablet the only sensors in this system whose readings reach the brain. The
 * dog's own ultrasonic and battery ADC are computed in its firmware and thrown
 * away (it only ever *receives* over the 2.4G link), so until that link learns
 * to talk back, this file is the whole of the dog's proprioception.
 *
 * What it answers, and why each one is worth the wire:
 *
 * - **How far have we actually turned.** [turnedDeg] is what makes a closed-loop
 *   `dog_turn` possible. Open-loop turning divides a measured "8.7s per
 *   revolution" by the angle asked for, and that constant drifts with battery
 *   level, floor surface and which way the servos happened to be mid-stride.
 * - **Are we still upright.** [tiltDeg] against a reference taken while the dog
 *   was standing. A dog on its side that is still being told to drive forward is
 *   the one failure the tablet can see and the brain cannot.
 * - **Is anything moving at all.** [vibration] is the residual accelerometer
 *   energy. It does not prove the dog is going anywhere, but a dead-flat reading
 *   while we believe a motion is running is real evidence of a command that
 *   never landed — or of the firmware's own obstacle refusal, which is otherwise
 *   completely invisible from here.
 *
 * ## Why GAME_ROTATION_VECTOR and not ROTATION_VECTOR
 *
 * [Sensor.TYPE_ROTATION_VECTOR] fuses the magnetometer in, and the magnetometer
 * is useless here: the tablet is sitting inches above four servos and a motor
 * driver. [Sensor.TYPE_GAME_ROTATION_VECTOR] is gyro+accelerometer only. It has
 * no idea where north is, which costs us nothing — every question this class is
 * asked is *relative* ("how much further to turn"), measured over the ten
 * seconds of one motion, which is far inside the gyro's drift.
 *
 * ## Why the yaw is not read out of getOrientation()
 *
 * [SensorManager.getOrientation]'s azimuth goes singular as the device
 * approaches vertical — and a tablet mounted upright on a dog's back sits at
 * exactly that pitch. So yaw is instead taken as the compass bearing of one of
 * the device's own axes projected onto the world's horizontal plane, and the
 * axis is chosen at [markTurn] as whichever is furthest from vertical. That is
 * both gimbal-free and mounting-agnostic: however the tablet is strapped on,
 * turning the dog about the floor's normal turns that axis by the same angle.
 * Picking the axis once per turn (rather than per sample) means a dog that
 * tips mid-turn cannot make the reading jump.
 *
 * ## Threading
 *
 * Samples are delivered to a private [HandlerThread], never the main looper:
 * the UI thread in this app is already carrying an OpenGL ball and a gesture
 * pipeline, and a turn loop that stops sampling because a screenshot is being
 * encoded would overshoot. Readers are plain volatile field reads and can be
 * called from any thread, which is what [DogMotion]'s motion threads do.
 */
class DogSense(context: Context) {

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val rotationSensor: Sensor? =
        sensors?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val accelSensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** Which sensor we ended up on, for [status] — the two behave differently. */
    private val rotationName: String = when (rotationSensor?.type) {
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "game_rotation_vector"
        Sensor.TYPE_ROTATION_VECTOR -> "rotation_vector"
        else -> "none"
    }

    /** True when this tablet can answer anything at all about its attitude. */
    val available: Boolean get() = rotationSensor != null

    /**
     * Why not, in words meant for the brain to pass on. Empty when [available].
     */
    fun unavailableReason(): String = when {
        sensors == null -> "this device has no sensor service"
        rotationSensor == null -> "this tablet has no gyroscope / rotation-vector sensor"
        else -> ""
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listening = false
    private val lock = Any()

    // ------------------------------------------------------------------ samples

    /** Device→world rotation, row-major 3x3. Null until the first sample. */
    @Volatile private var rot: FloatArray? = null

    @Volatile private var lastSampleMs = 0L

    /** World-up expressed in device coordinates, captured while standing. */
    @Volatile private var uprightRef: FloatArray? = null

    /** Slow mean of |accel|, the baseline [vibration] is measured against. */
    @Volatile private var accelMean = 0f

    /** Smoothed |·| of the high-passed accelerometer magnitude, in m/s². */
    @Volatile private var vibrationEma = 0f

    // --------------------------------------------------------------- turn state

    /** Which device axis (0=X, 1=Y) the current turn is being measured on. */
    @Volatile private var turnAxis = 0

    /** Bearing of [turnAxis] at the last sample, radians, wrapped. */
    @Volatile private var lastBearing = Double.NaN

    /** Unwrapped degrees since [markTurn]. Positive = counter-clockwise = left. */
    @Volatile private var turnedDeg = 0.0

    /** Degrees per second, signed the same way, smoothed. */
    @Volatile private var turnRate = 0.0

    @Volatile private var marked = false

    // ---------------------------------------------------------------- lifecycle

    /**
     * Start sampling. Idempotent, and cheap enough to leave running: gyro and
     * accelerometer at [SAMPLE_US] are handled by the sensor hub, which is
     * nothing against the GL ball this service already draws continuously.
     * Called lazily from the first dog command rather than at service start, so
     * a tablet that never touches the dog never registers anything.
     */
    fun attach() {
        val sm = sensors ?: return
        synchronized(lock) {
            if (listening) return
            val t = HandlerThread("DogSense").apply { isDaemon = true; start() }
            val h = Handler(t.looper)
            thread = t
            handler = h
            rotationSensor?.let { sm.registerListener(listener, it, SAMPLE_US, h) }
            accelSensor?.let { sm.registerListener(listener, it, SAMPLE_US, h) }
            listening = true
        }
        Log.i(TAG, "sense attached: $rotationName" + if (accelSensor == null) " (no accelerometer)" else "")
    }

    fun shutdown() {
        synchronized(lock) {
            if (!listening) return
            sensors?.unregisterListener(listener)
            thread?.quitSafely()
            thread = null
            handler = null
            listening = false
        }
        Log.i(TAG, "sense detached")
    }

    // ------------------------------------------------------------------ marking

    /**
     * Record the current attitude as "standing upright".
     *
     * There is no way to know how the tablet is strapped to the dog, so tilt has
     * to be measured against a reference rather than against the device's own
     * axes. [DogMotion] calls this at the start of a motion when nothing else
     * was running — at that instant the dog is standing by construction, because
     * every motion in this codebase ends with a "J".
     *
     * A reference taken while the dog is already lying on its side would make
     * [tiltDeg] read zero forever, so this deliberately does nothing when there
     * is no sample yet: a wrong reference is worse than none.
     */
    fun markUpright() {
        val r = rot ?: return
        uprightRef = floatArrayOf(r[6], r[7], r[8])
    }

    /**
     * Zero the turn counter and choose the axis it will be measured on.
     *
     * The axis is whichever of the device's X and Y is furthest from vertical,
     * because a near-vertical axis has no meaningful compass bearing. It is
     * fixed for the whole turn on purpose — see the class note.
     */
    fun markTurn() {
        val r = rot ?: return
        // Column j of a row-major device→world matrix is the world direction of
        // device axis j; its z component is how vertical that axis is.
        val xVertical = abs(r[6])
        val yVertical = abs(r[7])
        turnAxis = if (xVertical <= yVertical) 0 else 1
        lastBearing = bearingOf(r, turnAxis)
        turnedDeg = 0.0
        turnRate = 0.0
        marked = true
    }

    // ------------------------------------------------------------------ readers

    /**
     * Degrees turned since [markTurn], unwrapped — a full circle reads 360, not
     * 0. Positive is counter-clockwise seen from above, i.e. a left turn.
     */
    fun turnedDeg(): Double = if (marked) turnedDeg else 0.0

    /** Signed turn rate in degrees per second, smoothed over a few samples. */
    fun turnRateDegPerSec(): Double = if (marked) turnRate else 0.0

    /**
     * Angle between the current attitude and the [markUpright] reference, in
     * degrees. NaN when there is no reference yet — every caller compares with
     * `>=`, and NaN comparisons are false, so an unknown tilt never trips a
     * guard.
     *
     * Pure yaw does not move this: the reference is the world-up vector in
     * device coordinates, and rotating about world-up leaves it untouched. So
     * this measures exactly the part of the attitude that turning is not.
     */
    fun tiltDeg(): Double {
        val ref = uprightRef ?: return Double.NaN
        val r = rot ?: return Double.NaN
        val dot = (r[6] * ref[0] + r[7] * ref[1] + r[8] * ref[2]).toDouble()
        return Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
    }

    /** Residual accelerometer energy in m/s², gravity removed. */
    fun vibration(): Double = vibrationEma.toDouble()

    /** Has any sample arrived recently enough to trust? */
    fun fresh(): Boolean =
        rot != null && SystemClock.elapsedRealtime() - lastSampleMs < STALE_MS

    /**
     * What the tablet can say about the dog's posture, for `dog_status`.
     *
     * [vibration] is reported as a raw number and never as a "stuck" boolean.
     * The threshold that would separate a stuck dog from a smooth one has never
     * been measured on this hardware, and a wrong boolean would have the brain
     * tell the user something false about a machine they are looking at.
     */
    fun status(): JSONObject {
        val out = JSONObject()
        out.put("sensor", rotationName)
        if (!available) {
            out.put("available", false)
            out.put("why", unavailableReason())
            return out
        }
        out.put("available", true)
        out.put("fresh", fresh())
        val tilt = tiltDeg()
        out.put("tilt_deg", if (tilt.isNaN()) JSONObject.NULL else round1(tilt))
        out.put("upright_ref", uprightRef != null)
        out.put("vibration", round2(vibration()))
        return out
    }

    // ------------------------------------------------------------------ interna

    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> onRotation(e)
                Sensor.TYPE_ACCELEROMETER -> onAccel(e)
            }
        }
    }

    private val matrix = FloatArray(9)

    /** Scratch for the truncated rotation vector — see [onRotation]. */
    private val quat = FloatArray(4)

    private fun onRotation(e: SensorEvent) {
        // Some OEM sensor stacks (Samsung's, historically) deliver five values
        // on a rotation vector, and getRotationMatrixFromVector throws on
        // anything longer than four. Truncating is the standard guard; a crash
        // here would take the accessibility service down with it, which on this
        // device means the ball, the overlay and every tool the brain has.
        val n = minOf(e.values.size, 4)
        if (n < 3) return
        System.arraycopy(e.values, 0, quat, 0, n)
        val vec = if (n == 4) quat else quat.copyOf(3)
        SensorManager.getRotationMatrixFromVector(matrix, vec)
        val snapshot = matrix.copyOf()
        val now = SystemClock.elapsedRealtime()
        val prevMs = lastSampleMs
        rot = snapshot
        lastSampleMs = now
        if (uprightRef == null) {
            // First ever sample: assume the dog is standing. It is, unless
            // somebody powered the tablet on with the dog already tipped over,
            // and markUpright() re-takes this before every motion anyway.
            uprightRef = floatArrayOf(snapshot[6], snapshot[7], snapshot[8])
        }
        if (!marked) return

        val bearing = bearingOf(snapshot, turnAxis)
        val prev = lastBearing
        if (prev.isNaN() || bearing.isNaN()) {
            lastBearing = bearing
            return
        }
        // Unwrap: take the short way round, so crossing ±180° accumulates
        // instead of flipping sign, and 360° of turning reads as 360.
        var delta = bearing - prev
        while (delta > Math.PI) delta -= 2 * Math.PI
        while (delta < -Math.PI) delta += 2 * Math.PI
        val deltaDeg = Math.toDegrees(delta)
        turnedDeg += deltaDeg
        lastBearing = bearing

        val dtMs = (now - prevMs).coerceAtLeast(1L)
        val instantRate = deltaDeg * 1000.0 / dtMs
        // Smoothed, because the stop lead is computed from it and a single
        // noisy sample would have us cut a turn short by several degrees.
        turnRate = turnRate * (1 - RATE_ALPHA) + instantRate * RATE_ALPHA
    }

    private fun onAccel(e: SensorEvent) {
        val mag = sqrt(
            e.values[0] * e.values[0] +
                e.values[1] * e.values[1] +
                e.values[2] * e.values[2]
        )
        if (accelMean == 0f) accelMean = mag
        // Slow mean tracks gravity and any steady tilt; what is left over is
        // the shaking of a machine that is actually doing something.
        accelMean = accelMean * (1 - MEAN_ALPHA) + mag * MEAN_ALPHA
        val residual = abs(mag - accelMean)
        vibrationEma = vibrationEma * (1 - VIB_ALPHA) + residual * VIB_ALPHA
    }

    /**
     * Compass bearing of device axis [axis] projected onto the world's
     * horizontal plane, in radians. NaN when that axis is too close to vertical
     * for the projection to mean anything.
     */
    private fun bearingOf(r: FloatArray, axis: Int): Double {
        val wx = r[axis].toDouble()
        val wy = r[3 + axis].toDouble()
        if (hypot(wx, wy) < MIN_HORIZONTAL) return Double.NaN
        return atan2(wy, wx)
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0
    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    private companion object {
        const val TAG = "Dog"

        /** ~20 ms. At a 9 s revolution that is under a degree per sample. */
        const val SAMPLE_US = 20_000

        /** Older than this and a reading is not evidence of anything. */
        const val STALE_MS = 1_000L

        const val RATE_ALPHA = 0.3
        const val MEAN_ALPHA = 0.05f
        const val VIB_ALPHA = 0.2f

        /**
         * Below this, the chosen axis is so close to vertical that its bearing
         * is noise. [markTurn] picks the better of two axes precisely so this
         * should not trigger; it is the backstop for a dog that tips mid-turn.
         */
        const val MIN_HORIZONTAL = 0.20
    }
}
