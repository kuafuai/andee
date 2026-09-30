package net.kuafuai.andee.dog

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.json.JSONObject

/**
 * USB-serial transport to the robot dog's remote dongle.
 *
 * The dongle is an Arduino Nano + NRF24L01 that plugs into the tablet's OTG
 * port. It speaks the simplest possible protocol, one ASCII character per
 * command, and answers with one line:
 *
 *     in   'K'          out  "K OK"   — the dog's radio layer acknowledged
 *     in   'K'          out  "K NO-ACK" — nothing answered (out of range, dog
 *                                         off, or the receive window was missed)
 *
 * The dog holds whatever it was last told to do until it is told something
 * else, so "J" (stand/stop) is not a nicety — it is how a move ends. That
 * asymmetry is why [DogMotion] exists and why nothing here ever leaves a
 * motion running: see that class for the scheduling half.
 *
 * ## Why the port is opened lazily
 *
 * Nothing connects at startup. The dongle may not be plugged in, the user may
 * be using the tablet for something else entirely, and a serial port held open
 * for no reason is a battery cost and one more thing to go wrong. The first
 * command opens it; every later command reuses it.
 *
 * ## Why opening takes seconds
 *
 * Asserting DTR on open resets the Nano — the classic Arduino auto-reset — so
 * the dongle reboots, re-initialises its radio and prints a banner. Commands
 * sent before that lands are read by nobody. So [ensureOpen] waits for the
 * banner (or [READY_WAIT_MS], whichever comes first) and drains the buffer.
 * The banner doubles as a diagnosis: `READY` means the dongle and its radio
 * are both up, `ERR: NRF24L01 not responding` means the dongle is alive but
 * its radio module is not — a wiring or 3.3V problem on the dongle, and
 * emphatically not "the dog is broken".
 *
 * ## Threading
 *
 * All port access is serialised on [lock]. Callers block: a single command
 * costs one write plus a bounded ACK read ([ACK_TIMEOUT_MS]), and the first
 * command of a session also pays the permission dialog and [READY_WAIT_MS].
 * That first-command cost is the only long one and it happens once.
 */
class DogLink(private val context: Context) {

    /** One command's outcome, as far as the radio layer can report it. */
    data class Ack(val cmd: Char, val ok: Boolean, val reply: String)

    private val lock = Any()
    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var device: UsbDevice? = null

    /** Where the dongle was found, for status: "1a86:7523". */
    @Volatile private var deviceTag: String? = null

    /** The dongle's own banner text, or null if it never said anything. */
    @Volatile private var banner: String? = null

    @Volatile private var lastCmd: Char? = null
    @Volatile private var lastReply: String? = null

    /**
     * The last telemetry the dog sent back, and when.
     *
     * Kept as the raw last reading rather than smoothed or defaulted: an
     * ultrasonic sensor that sees nothing and a sensor that is unplugged both
     * need to read as "unknown", and a number invented to fill the gap would
     * reach the user as the brain saying how far away the wall is.
     */
    @Volatile private var lastDistanceCm: Int? = null
    @Volatile private var lastBatteryPct: Int? = null
    @Volatile private var lastTelemAt: Long = 0L

    /** Why the last attempt failed — surfaced by `dog_status`, not swallowed. */
    @Volatile private var lastError: String? = null

    private var receiver: BroadcastReceiver? = null
    private var permissionLatch: java.util.concurrent.CountDownLatch? = null

    /**
     * Called when the dongle goes away underneath us (unplugged, or the USB
     * stack dropped it). The motion scheduler uses this to cancel its timers —
     * it cannot send "J" down a cable that is no longer there, but it must at
     * least stop believing it will.
     */
    @Volatile var onLost: (() -> Unit)? = null

    // ---------------------------------------------------------------- opening

    /**
     * The dongle, if it is plugged in — as a driver we can actually use.
     *
     * Two passes on purpose. The library's own table covers the parts it has
     * heard of (CH340, CH341A, CP210x, FTDI, PL2303, and CDC-ACM devices it
     * lists). Arduino clones carry bridges it has *not* heard of — CH9102
     * (0x1a86:0x55d4) is absent from its `UsbId` table and it is exactly what
     * the newer Type-C Nano boards ship — so a second pass probes by
     * construction. That pass is deliberately narrow: a `Ch34xSerialDriver`
     * can be constructed around *any* device and will hand back a port, so
     * trusting it blindly would let us seize the tablet's own hub or a USB
     * stick. It only claims a device that is a QinHeng part presenting a single
     * vendor-specific (class 0xFF) interface, which is what a CH34x bridge
     * looks like and what almost nothing else does.
     */
    private fun findDongle(): Pair<UsbSerialDriver, UsbDevice>? {
        val manager = usbManager ?: return null

        val fromTable = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        // A known bridge vendor wins when the tablet exposes more than one
        // serial-shaped device. The Pad enumerates an internal device of its
        // own, and taking `firstOrNull()` blindly would eventually open that
        // one and fail with a confusing "this USB device has no serial port".
        fromTable.firstOrNull { it.device.vendorId in BRIDGE_VENDORS }?.let {
            return it to it.device
        }
        fromTable.firstOrNull()?.let { return it to it.device }

        for (d in manager.deviceList.values) {
            // Safe on anything: the library's own check looks for a real CDC
            // interface pair before it says yes.
            if (CdcAcmSerialDriver.probe(d)) return CdcAcmSerialDriver(d) to d
            // 0x1a86 = QinHeng. Unknown PID, so it is a bridge the table does
            // not carry; only take it if it is shaped like one.
            if (d.vendorId == 0x1A86 && d.interfaceCount == 1 &&
                d.getInterface(0).interfaceClass == 0xFF
            ) {
                return Ch34xSerialDriver(d) to d
            }
        }
        return null
    }

    /**
     * Ask the system for access to this USB device, blocking until the user
     * answers the dialog.
     *
     * The dialog is the user's, not ours: the first time anything touches a USB
     * device Android asks "允许 Andee 访问该 USB 设备吗？". We can only ask and
     * wait. It is bounded ([PERMISSION_WAIT_MS]) because an unanswered dialog
     * must not wedge the tool call forever — and the timeout is reported as a
     * timeout, with the thing the user has to do named in plain words, rather
     * than as a generic failure.
     */
    private fun ensurePermission(d: UsbDevice): Boolean {
        val manager = usbManager ?: run {
            lastError = "this device has no USB management service"
            return false
        }
        if (manager.hasPermission(d)) return true

        // The dialog's answer arrives as a broadcast, so the receiver has to be
        // listening before we ask. Registered once and kept: the same receiver
        // also carries attach/detach.
        ensureReceiver()
        val latch = java.util.concurrent.CountDownLatch(1)
        permissionLatch = latch
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        // setPackage makes this an explicit-enough intent for a mutable
        // PendingIntent on Android 14 (targetSdk 34), which refuses implicit
        // ones outright.
        val ask = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags,
        )
        Log.i(TAG, "asking the user to allow USB access to ${describe(d)}")
        manager.requestPermission(d, ask)
        val answered = runCatching { latch.await(PERMISSION_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
        permissionLatch = null
        if (!manager.hasPermission(d)) {
            lastError = if (answered) {
                "the user refused USB permission, so the dog cannot be reached"
            } else {
                "the USB permission dialog went unanswered for ${PERMISSION_WAIT_MS / 1000} s — " +
                    "have the user tap Allow for USB access on the tablet, then retry"
            }
            Log.w(TAG, "USB permission not granted: $lastError")
            return false
        }
        return true
    }

    private fun ensureReceiver() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_USB_PERMISSION ->
                        permissionLatch?.countDown()

                    // Unplugged mid-session. The dog keeps executing whatever it
                    // was last told — see the class note and CLAUDE.md — so the
                    // only honest thing to do is forget the link and say so.
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        @Suppress("DEPRECATION")
                        val gone = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        if (gone == null || gone.deviceId == device?.deviceId) {
                            Log.w(TAG, "dongle detached — dropping the link")
                            synchronized(lock) { turnOffLocked() }
                            lastError = "the dongle was unplugged"
                            // Outside the lock on purpose: onLost reaches back
                            // into DogMotion, which takes its own lock. Calling it
                            // while holding this one would be the other half of a
                            // lock-order cycle.
                            onLost?.invoke()
                        }
                    }
                }
            }
        }
        // NOT_EXPORTED: the permission answer comes from the system (allowed),
        // and nothing else has any business poking us.
        ContextCompat.registerReceiver(
            context, r, IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver = r
    }

    /**
     * Open the port if it is not already open. Returns false with [lastError]
     * set — never throws — because every caller is a tool result that has to
     * explain itself to the brain.
     */
    private fun ensureOpen(): Boolean {
        synchronized(lock) {
            if (port != null) return true
            val manager = usbManager ?: run {
                lastError = "this device has no USB management service"
                return false
            }
            val (driver, d) = findDongle() ?: run {
                lastError = "no dongle found — check that the Arduino Nano is plugged into " +
                    "the tablet over the OTG cable"
                return false
            }
            if (!ensurePermission(d)) return false

            val conn = manager.openDevice(d) ?: run {
                lastError = "the system refused to open the USB device (the permission was " +
                    "granted, but openDevice returned null)"
                return false
            }
            val p = driver.ports.firstOrNull() ?: run {
                conn.close()
                lastError = "this USB device has no serial port"
                return false
            }
            try {
                p.open(conn)
                p.setParameters(BAUD, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                // Asserting DTR is what resets an Arduino; the library does it
                // inside open(), so the wait below is not optional.
                p.setDTR(true)
                p.setRTS(true)
            } catch (e: Exception) {
                runCatching { p.close() }
                runCatching { conn.close() }
                lastError = "failed to open the serial port: ${e.message}"
                Log.w(TAG, "open failed", e)
                return false
            }

            port = p
            connection = conn
            device = d
            deviceTag = describe(d)
            Log.i(TAG, "dongle open: $deviceTag @ $BAUD")
            banner = readBanner(p)
            lastError = null
            return true
        }
    }

    /**
     * Wait out the Nano's reset and collect whatever it says.
     *
     * Returns as soon as a known line lands, otherwise after [READY_WAIT_MS].
     * The remaining bytes are drained either way so the first command's ACK is
     * not read out of a buffer full of banner.
     */
    private fun readBanner(p: UsbSerialPort): String {
        val text = StringBuilder()
        val deadline = System.currentTimeMillis() + READY_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val chunk = runCatching { readSome(p, 250) }.getOrElse { "" }
            if (chunk.isNotEmpty()) {
                text.append(chunk)
                val t = text.toString()
                if (t.contains("READY") || t.contains("ERR")) break
            }
        }
        // Keep only printable ASCII. The CH34x bridge emits one partial byte as
        // DTR toggles it into the reset, so the banner comes back as
        // "\uFFFDREADY" — noise, not signal, and not something to hand to the
        // brain as a device fact.
        val raw = text.toString()
            .filter { it == '\n' || it == '\r' || it in ' '..'~' }

        // The bridge also keeps bytes in its FIFO across a port close/reopen,
        // so the buffer can still hold the tail of the previous session ("J OK")
        // ahead of this boot's banner. Report the line that actually announces
        // the dongle, and fall back to the whole text only when there is none —
        // a diagnostic field that mixes in yesterday's traffic is a diagnostic
        // field nobody can read.
        val announcement = raw.split('\n')
            .map { it.trim() }
            .lastOrNull { it.contains("READY") || it.contains("ERR") }
        val banner = announcement ?: raw.trim()
        Log.i(TAG, "dongle banner: ${banner.ifEmpty { "(silent)" }}")
        if (banner.contains("ERR")) {
            lastError = "the dongle is online but its NRF24L01 module is not responding — " +
                "check the wiring and the 3.3V supply to the radio module on the dongle"
        }
        return banner
    }

    // ----------------------------------------------------------------- sending

    /**
     * Send one command character and wait, briefly, for the dongle's answer.
     *
     * [awaitAck] false is the emergency path: `J` has to leave the tablet as
     * soon as possible, and the acknowledgement tells us nothing we can act on
     * — the dog is either stopping or it is not. Skipping the read takes the
     * stop from ~500ms to ~1ms, and that difference matters when the thing
     * being stopped has motors.
     */
    fun send(cmd: Char, awaitAck: Boolean = true): Ack {
        if (!ensureOpen()) return Ack(cmd, false, lastError ?: "link not open")
        var lost = false
        val ack = synchronized(lock) {
            val p = port ?: return@synchronized Ack(cmd, false, "link not open")
            val bytes = byteArrayOf(cmd.code.toByte())
            fun attempt(): Boolean = try {
                p.write(bytes, WRITE_TIMEOUT_MS)
                true
            } catch (e: Exception) {
                Log.w(TAG, "write failed", e)
                turnOffLocked()
                lastError = "failed to write to the serial port: ${e.message}"
                lost = true
                false
            }

            if (!attempt()) {
                Ack(cmd, false, "write failed")
            } else {
                lastCmd = cmd
                if (!awaitAck) {
                    Ack(cmd, true, "sent (no ack read)")
                } else {
                    var reply = readAck(p, ACK_TIMEOUT_MS)
                    var ok = verdict(reply)
                    // One resend, same rule as the Mac-side dog_run.py: NO-ACK
                    // usually means the dog's receive window was missed rather
                    // than the dog being unreachable, and a second frame
                    // frequently lands.
                    if (!ok && reply.contains("NO-ACK")) {
                        if (attempt()) reply = readAck(p, ACK_TIMEOUT_MS).ifEmpty { reply }
                        ok = verdict(reply)
                    }
                    lastReply = reply.trim().ifEmpty { null }
                    parseTelemetry(reply)
                    lastError = if (ok) null else NO_ACK_HINT.format(cmd)
                    Ack(cmd, ok, reply.trim())
                }
            }
        }
        // Outside [lock] — see the detach branch for why.
        if (lost) onLost?.invoke()
        return ack
    }

    private fun verdict(reply: String): Boolean =
        reply.contains("NO-ACK").not() && reply.contains("OK")

    /**
     * Pull `d=` / `b=` off the dongle's reply line, if it carried them.
     *
     * The dongle appends telemetry *after* the verdict ("K OK d=37 b=82") and
     * prints nothing at all when the dog sent none, so absence is the normal
     * case on a dongle or a dog that has not been reflashed. A field the dog
     * could not measure comes through as `?` and stays null — the readings are
     * deliberately not cleared on a silent reply, because one missed ack
     * payload is not news; [lastTelemAt] is what says how old the number is.
     */
    private fun parseTelemetry(reply: String) {
        val m = TELEM_RE.find(reply) ?: return
        lastDistanceCm = m.groupValues[1].toIntOrNull()
        lastBatteryPct = m.groupValues[2].toIntOrNull()
        lastTelemAt = System.currentTimeMillis()
    }

    /**
     * Ask the dog how it is, without telling it to do anything.
     *
     * '?' resends the dongle's current frame verbatim. The dog re-reads that
     * frame every main loop and only changes behaviour when the bytes change,
     * so this cannot interrupt a motion in progress — which is the whole point:
     * the distance reading is most wanted exactly while the dog is walking.
     */
    fun poll(): Ack = send('?')

    /**
     * The last thing the dog said about itself, with its age.
     *
     * `age_ms` is not decoration. The dog only speaks when spoken to (the
     * reading rides back on the ack frame of a command we sent), so a stale
     * distance is the normal state of a dog standing still, and a brain that
     * treats a five-second-old "30cm ahead" as current would drive into
     * something that moved. Null means never heard.
     */
    fun telemetry(): JSONObject = JSONObject()
        .put("distance_cm", lastDistanceCm ?: JSONObject.NULL)
        .put("battery_pct", lastBatteryPct ?: JSONObject.NULL)
        .put(
            "age_ms",
            if (lastTelemAt == 0L) JSONObject.NULL
            else System.currentTimeMillis() - lastTelemAt
        )
        .put("supported", lastTelemAt != 0L)

    /**
     * Read until the dongle's answer line is complete, or the budget runs out.
     *
     * Not a fixed sleep: the answer normally lands in a few milliseconds and a
     * fixed wait would tax every command for the worst case. A short read
     * timeout keeps it cheap early and still waits when the dog is slow to be
     * heard.
     *
     * Stopping at "OK" is not good enough anymore — telemetry is appended
     * *after* the verdict, so a read that returned on the verdict would parse
     * every reply as having none. It waits for the newline instead, and still
     * settles for a bare verdict if none arrives, so an old dongle (which
     * prints the same line minus the telemetry) costs one extra read, not a
     * timeout.
     */
    private fun readAck(p: UsbSerialPort, budgetMs: Long): String {
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < end) {
            val chunk = runCatching { readSome(p, 120) }.getOrElse { return sb.toString() }
            if (chunk.isEmpty()) continue
            sb.append(chunk)
            val t = sb.toString()
            if (t.contains("NO-ACK") || t.contains("OK")) {
                if (t.contains('\n')) break
            }
        }
        return sb.toString()
    }

    private fun readSome(p: UsbSerialPort, timeoutMs: Int): String {
        val buf = ByteArray(64)
        val n = p.read(buf, timeoutMs)
        return if (n > 0) String(buf, 0, n, Charsets.US_ASCII) else ""
    }

    // ----------------------------------------------------------------- lifecycle

    /**
     * Put the dog down and let go of the cable.
     *
     * Best-effort "J" first: if the dongle is still there it is the difference
     * between a dog standing still and a dog doing whatever it was last told
     * with nobody watching. Then the port closes, and every cached fact about
     * the link goes with it.
     */
    fun close() {
        synchronized(lock) {
            if (port != null) runCatching { sendLocked('J') }
            turnOffLocked()
            receiver?.let { runCatching { context.unregisterReceiver(it) } }
            receiver = null
        }
        Log.i(TAG, "dongle closed")
    }

    /** Best-effort "J", already holding [lock]. */
    private fun sendLocked(cmd: Char) {
        val p = port ?: return
        runCatching { p.write(byteArrayOf(cmd.code.toByte()), WRITE_TIMEOUT_MS) }
        lastCmd = cmd
    }

    /** Tear down port + connection, keeping [lastError] as the caller set it. */
    private fun turnOffLocked() {
        runCatching { port?.close() }
        runCatching { connection?.close() }
        port = null
        connection = null
        device = null
        deviceTag = null
        banner = null
    }

    // ------------------------------------------------------------------- status

    /**
     * Everything the tablet knows about the cable and the dongle.
     *
     * Distance and battery now come back from the dog itself, riding on the ack
     * frame of whatever we last sent (see [telemetry]) — but only when both the
     * dog and the dongle carry the firmware that does it. `telemetry.supported`
     * is false until a reading has actually arrived, because "this dongle was
     * never reflashed" and "the dog is out of range" look identical from here
     * and neither is a reason to make a number up.
     *
     * `attached` means "a dongle is plugged in", NOT "we have it open", and
     * `permission` says whether the system dialog has been answered yet. Both
     * are answered from a fresh enumeration when there is no live port, because
     * the question gets asked precisely when nothing is open: before the first
     * command, and right after something failed.
     */
    fun status(): JSONObject {
        val live = device
        val found: UsbDevice? = live ?: runCatching { findDongle()?.second }.getOrNull()
        val permission = when {
            found == null -> "no_device"
            usbManager?.hasPermission(found) == true -> "granted"
            else -> "needed"
        }
        return JSONObject()
            .put("attached", found != null)
            .put("device", deviceTag ?: found?.let { describe(it) } ?: JSONObject.NULL)
            .put("permission", permission)
            .put("open", port != null)
            .put("baud", BAUD)
            .put("banner", banner ?: JSONObject.NULL)
            .put("last_cmd", lastCmd?.toString() ?: JSONObject.NULL)
            .put("last_reply", lastReply ?: JSONObject.NULL)
            .put("telemetry", telemetry())
            .put("error", lastError ?: JSONObject.NULL)
    }

    private fun describe(d: UsbDevice): String =
        "%04x:%04x".format(d.vendorId, d.productId)

    private companion object {
        const val TAG = "Dog"
        const val BAUD = 115_200
        const val ACTION_USB_PERMISSION = "net.kuafuai.andee.dog.USB_PERMISSION"

        /** How long the Nano may take to reboot and say something. */
        const val READY_WAIT_MS = 3_000L

        /** How long one command's answer may take. */
        const val ACK_TIMEOUT_MS = 500L

        /** Writes are small and the driver already retries internally. */
        const val WRITE_TIMEOUT_MS = 200

        /** A dialog nobody answers must not wedge the tool call. */
        const val PERMISSION_WAIT_MS = 12_000L

        /** "K OK d=37 b=82" — either field may be `?` when the dog can't measure it. */
        val TELEM_RE = Regex("""d=(\d+|\?)\s+b=(\d+|\?)""")

        /**
         * What NO-ACK means, said the same way every time. It is not "the dog
         * is broken": the likeliest causes are mundane and the user can fix
         * them, so the message names them.
         */
        const val NO_ACK_HINT =
            "command %s got no acknowledgement from the machine (NO-ACK) — too far away, " +
                "the dog is switched off, or the original remote is transmitting at the " +
                "same time"

        /**
         * Vendors whose whole business is USB-serial bridges, i.e. what a
         * dongle is made of. Used only to break a tie when the tablet offers
         * more than one serial-shaped device.
         */
        val BRIDGE_VENDORS = setOf(
            0x1A86, // QinHeng — CH340 / CH341 / CH9102, what Arduino clones use
            0x0403, // FTDI
            0x10C4, // Silicon Labs CP210x
            0x067B, // Prolific
            0x2341, // Arduino
        )
    }
}
