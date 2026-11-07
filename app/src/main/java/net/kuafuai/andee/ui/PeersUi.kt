package net.kuafuai.andee.ui
import net.kuafuai.andee.compat.WindowMetricsCompat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import net.kuafuai.andee.R
import android.content.Intent
import net.kuafuai.andee.device.AndroidPeers
import net.kuafuai.andee.device.ScanActivity
import net.kuafuai.andee.peer.PeerStore
import net.kuafuai.andee.i18n.AppLocale

/**
 * 朋友: the other Andees this one knows, and the way to add one. The phone's twin of the Mac's Friends page.
 * Adding is a QR code either way round — this phone scans one, or shows its own for somebody else's phone to
 * scan (see [net.kuafuai.andee.peer.PeerQr]). Each friend is either **my device** (trusted like the user) or a
 * **friend** (asked about every time unless "always", and run out of sight of the user's memory). There is nothing
 * to type, so unlike [VaultUi] the window never takes focus. Hidden from accessibility and counted by [OwnCard].
 */
class PeersUi(
    private val context: Context,
    private val peers: AndroidPeers,
    private val onDismiss: () -> Unit = {},
) {
    private val wm: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var root: View? = null
    private var frosted = false
    private var lctx: Context = context

    private var error = ""
    private var pairedWith = ""
    @Volatile private var busy = false
    private var arming: String? = null
    private var aside: Any? = null
    private var invite: String? = null
    private var inviteOwn = false

    private val refresh = Runnable { rebuild() }

    fun show() {
        if (root != null) return
        val p = WindowManager.LayoutParams(
            cardWidth(), cardHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        frosted = Glass.frost(context, wm, p)
        val v = build()
        runCatching {
            v.hideFromAccessibility()
            wm.addView(v, p)
            root = v
            OwnCard.shown()
            Glass.enter(v)
        }
        aside = StandAside.register(wm) { root }
        // Found / lost / paired events arrive on other threads; redraw on the main one, coalesced.
        peers.onChange = { main.removeCallbacks(refresh); main.postDelayed(refresh, 150) }
    }

    fun hide() {
        val v = root ?: return
        root = null
        aside?.let { StandAside.unregister(it) }; aside = null
        peers.onChange = {}
        main.removeCallbacks(refresh)
        Glass.exit(v) {
            runCatching { wm.removeView(v) }
            OwnCard.hidden()
            onDismiss()
        }
    }

    private fun rebuild() {
        val old = root ?: return
        val v = build()
        runCatching {
            v.hideFromAccessibility()
            val p = old.layoutParams as WindowManager.LayoutParams
            wm.removeView(old); wm.addView(v, p); root = v
        }
    }

    private fun cardWidth(): Int = minOf(dp(560), (WindowMetricsCompat.bounds(wm).width() * 0.94f).toInt())
    private fun cardHeight(): Int = minOf(dp(720), (WindowMetricsCompat.bounds(wm).height() * 0.88f).toInt())

    @SuppressLint("SetTextI18n")
    private fun build(): View {
        lctx = AppLocale.wrap(context)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.card(context, dp(28), frosted)
            clipToOutline = true
        }
        card.addView(header())
        card.addView(View(context).apply { setBackgroundColor(Color.parseColor(Glass.PANEL_EDGE)) }, LinearLayout.LayoutParams(MATCH, dp(1)))
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(16)) }

        body.addView(title(lctx.getString(R.string.peers_add_title)))
        val code = peers.code?.takeIf { it.alive() }
        val qr = invite
        if (code != null && qr != null) {
            val box = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(14), dp(14), dp(14), dp(12)); background = Glass.panel(context, dp(14))
            }
            // Dark modules on white whatever the theme: a scanner reads contrast.
            box.addView(android.widget.ImageView(context).apply {
                setImageBitmap(qrBitmap(qr))
                setBackgroundColor(Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10))
            }, LinearLayout.LayoutParams(dp(220), dp(220)))
            box.addView(note(lctx.getString(if (inviteOwn) R.string.peers_show_note_own else R.string.peers_show_note_friend)).apply { gravity = Gravity.CENTER })
            box.addView(action(lctx.getString(R.string.common_cancel), Glass.LABEL) { peers.cancelCode(); invite = null; rebuild() },
                LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
            body.addView(box, matchWrap())
            // Redraw when the code dies, so a dead code is never left on screen to be scanned in vain.
            main.removeCallbacks(refresh); main.postDelayed(refresh, CODE_MS)
        } else {
            body.addView(action(lctx.getString(if (busy) R.string.peers_pairing else R.string.peers_scan), Glass.ACCENT) { scan() })
            body.addView(note(lctx.getString(R.string.peers_scan_note)))
            val showRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            showRow.addView(action(lctx.getString(R.string.peers_show_friend), Glass.ACCENT) { show(PeerStore.Trust.FRIEND) })
            showRow.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
            showRow.addView(action(lctx.getString(R.string.peers_show_own), Glass.LABEL) { show(PeerStore.Trust.OWN) })
            body.addView(showRow, matchWrap().apply { topMargin = dp(8) })
            body.addView(note(lctx.getString(R.string.peers_kinds_note)))
        }
        if (pairedWith.isNotEmpty()) body.addView(text(lctx.getString(R.string.peers_paired_ok, pairedWith), Glass.Type.CAPTION, Glass.ACCENT))
        if (error.isNotEmpty()) body.addView(text(error, Glass.Type.CAPTION, Glass.DANGER))

        val rows = peers.rows()
        body.addView(title(lctx.getString(R.string.peers_paired, rows.count { it.paired })))
        if (rows.none { it.paired }) body.addView(note(lctx.getString(R.string.peers_none)))
        for (r in rows.filter { it.paired }) body.addView(pairedRow(r), matchWrap().apply { bottomMargin = dp(10) })

        body.addView(title(lctx.getString(R.string.peers_visible_title)))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(pill(lctx.getString(R.string.common_off), !peers.enabled) { peers.setEnabled(false); rebuild() })
        row.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        row.addView(pill(lctx.getString(R.string.common_on), peers.enabled) { peers.setEnabled(true); rebuild() })
        body.addView(row, matchWrap())
        body.addView(note(lctx.getString(R.string.peers_visible_note)))
        if (peers.lastError.isNotEmpty()) body.addView(text(peers.lastError, Glass.Type.CAPTION, Glass.DANGER))

        val me = peers.store.identity()
        body.addView(title(lctx.getString(R.string.peers_this)))
        body.addView(text(me.name, Glass.Type.BODY, Glass.LABEL))
        body.addView(note(lctx.getString(R.string.peers_fingerprint, me.fingerprint)))

        val scroll = ScrollView(context)
        scroll.addView(body, matchWrap())
        card.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return card
    }

    private fun pairedRow(r: AndroidPeers.Row): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = Glass.panel(context, dp(14)) }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(r.name, Glass.Type.BODY, Glass.TITLE), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(text(
            lctx.getString(when { r.linked -> R.string.peers_linked; r.reachable -> R.string.peers_nearby_state; else -> R.string.peers_away }),
            Glass.Type.MICRO, if (r.linked) Glass.ACCENT else Glass.MUTED,
        ))
        box.addView(head, matchWrap())
        box.addView(note(r.fingerprint))
        val own = r.trust == PeerStore.Trust.OWN
        val kind = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        kind.addView(pill(lctx.getString(R.string.peers_kind_friend), !own) { peers.setTrust(r.id, PeerStore.Trust.FRIEND); rebuild() })
        kind.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
        kind.addView(pill(lctx.getString(R.string.peers_kind_own), own) { peers.setTrust(r.id, PeerStore.Trust.OWN); rebuild() })
        box.addView(kind, matchWrap().apply { topMargin = dp(6) })
        if (!own) {
            val ask = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            ask.addView(pill(lctx.getString(R.string.peers_ask_each), !r.auto) { peers.setAutoAccept(r.id, false); rebuild() })
            ask.addView(View(context), LinearLayout.LayoutParams(dp(8), 1))
            ask.addView(pill(lctx.getString(R.string.peers_always), r.auto) { peers.setAutoAccept(r.id, true); rebuild() })
            box.addView(ask, matchWrap().apply { topMargin = dp(6) })
        }
        val acts = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (!r.linked) acts.addView(action(lctx.getString(R.string.peers_connect), Glass.ACCENT) {
            Thread { peers.connect(r.id) }.apply { isDaemon = true }.start()
        })
        if (r.linked) acts.addView(action(lctx.getString(R.string.peers_ping), Glass.ACCENT) { peers.ping(r.id) })
        acts.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        val armed = arming == r.id
        acts.addView(action(lctx.getString(if (armed) R.string.peers_unpair_confirm else R.string.peers_unpair), Glass.DANGER) {
            if (armed) { peers.unpair(r.id); arming = null } else arming = r.id
            rebuild()
        })
        box.addView(acts, matchWrap().apply { topMargin = dp(8) })
        return box
    }

    private fun show(trust: PeerStore.Trust) {
        error = ""; pairedWith = ""
        inviteOwn = trust == PeerStore.Trust.OWN
        // Opening the server and finding the address are quick but not main-thread work.
        Thread {
            val qr = runCatching { peers.invite(trust) }.getOrNull()
            main.post {
                invite = qr
                if (qr == null) error = lctx.getString(R.string.peers_show_failed, peers.lastError)
                rebuild()
            }
        }.apply { isDaemon = true }.start()
    }

    /** [text] as a QR code, one pixel per module; the [android.widget.ImageView] scales it up without smoothing. */
    private fun qrBitmap(text: String): android.graphics.Bitmap {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(
            text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 0),
        )
        val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) Color.BLACK else Color.WHITE }
        val b = android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
        return android.graphics.Bitmap.createScaledBitmap(b, m.width * 8, m.height * 8, false)
    }

    /**
     * Scan the other side's QR code and pair. The cards step aside while the camera is up ([StandAside]) — they are
     * overlays and would otherwise sit on top of the preview they opened. The wait is on a worker: the scanner
     * reports through [ScanActivity.LastResult], which it always writes (a hit, a cancel, the back key, a
     * timeout), so the poll ends with the activity rather than at its deadline.
     */
    private fun scan() {
        if (busy) return
        error = ""; pairedWith = ""
        ScanActivity.LastResult = null
        StandAside.set(true)
        val started = runCatching {
            context.startActivity(
                Intent(context, ScanActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(ScanActivity.EXTRA_AUTO_CLOSE_MS, SCAN_MS),
            )
        }
        if (started.isFailure) { StandAside.set(false); error = started.exceptionOrNull()?.message.orEmpty(); rebuild(); return }
        busy = true; rebuild()
        Thread {
            val until = System.currentTimeMillis() + SCAN_MS + 3_000
            var r: android.os.Bundle? = null
            while (System.currentTimeMillis() < until) {
                r = ScanActivity.LastResult
                if (r != null) break
                try { Thread.sleep(200) } catch (_: InterruptedException) { break }
            }
            main.post { StandAside.set(false) }
            val value = r?.getString("value")
            if (value != null) {
                runCatching { peers.pairFromQr(value) }
                    .onSuccess { pairedWith = it.name }
                    .onFailure {
                        error = lctx.getString(
                            if (it is IllegalArgumentException) R.string.peers_scan_not_ours else R.string.peers_scan_failed,
                            it.message.orEmpty(),
                        )
                    }
            } else if (r?.getString("error").let { it != null && it != "cancelled by user" }) {
                error = lctx.getString(R.string.peers_scan_failed, r?.getString("error").orEmpty())
            }
            busy = false
            main.post { rebuild() }
        }.apply { isDaemon = true; name = "peer-scan" }.start()
    }

    private fun header(): LinearLayout {
        val h = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), dp(18), dp(14), dp(12)) }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(context).apply { text = lctx.getString(R.string.peers_title); textSize = Glass.Type.HEADLINE; setTextColor(Color.parseColor(Glass.TITLE)) })
        titles.addView(TextView(context).apply { text = lctx.getString(R.string.peers_subtitle); textSize = Glass.Type.CAPTION; setTextColor(Color.parseColor(Glass.SECONDARY)); setPadding(0, dp(2), 0, 0) })
        h.addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
        h.addView(TextView(context).apply {
            text = "✕"; textSize = Glass.Type.CAPTION; setTextColor(Color.parseColor(Glass.SECONDARY)); gravity = Gravity.CENTER
            background = Glass.panel(context, dp(16)); isClickable = true; Glass.pressable(this); setOnClickListener { hide() }
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        })
        return h
    }

    // ---- small pieces ----
    private fun title(s: String) = TextView(context).apply { text = s; textSize = Glass.Type.CAPTION; setTextColor(Color.parseColor(Glass.SECONDARY)); setPadding(dp(2), dp(14), 0, dp(4)) }
    private fun note(s: String) = TextView(context).apply { text = s; textSize = Glass.Type.MICRO; setTextColor(Color.parseColor(Glass.MUTED)); setPadding(dp(2), dp(2), dp(2), dp(4)) }
    private fun text(s: String, size: Float, color: String) = TextView(context).apply { text = s; textSize = size; setTextColor(Color.parseColor(color)) }

    private fun pill(label: String, on: Boolean, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = Glass.Type.CAPTION
        setTextColor(Color.parseColor(if (on) Glass.ON_TINT else Glass.LABEL)); gravity = Gravity.CENTER
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = if (on) Glass.tinted(context, dp(14)) else Glass.well(context, dp(14))
        isClickable = true; Glass.pressable(this); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
    }

    private fun action(label: String, color: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label; textSize = Glass.Type.CAPTION; setTextColor(Color.parseColor(color)); gravity = Gravity.CENTER
        setPadding(dp(14), dp(7), dp(14), dp(7)); background = Glass.well(context, dp(12))
        isClickable = true; Glass.pressable(this); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(MATCH, WRAP)

    private companion object {
        const val SCAN_MS = 60_000L
        const val CODE_MS = 120_500L
        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
