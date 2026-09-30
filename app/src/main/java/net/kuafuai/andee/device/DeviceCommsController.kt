package net.kuafuai.andee.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Personal-data queries + actuation: contacts, call log, dial, SMS, calendar.
 * ALL runtime permissions, ALL checked per call with graceful denial —
 * personal data is the one place a crash or a silent overreach is
 * unacceptable. Read paths report exactly what they saw; write paths
 * (sendSms) are deliberately explicit — the brain must confirm before firing.
 */
class DeviceCommsController(private val context: Context) {

    // ---- contacts ----------------------------------------------------

    fun searchContacts(query: String?, limit: Int = 20): JSONObject {
        if (!ensurePermission(Manifest.permission.READ_CONTACTS)) return denied("contacts")
        val q = query?.trim().orEmpty()
        val selection = if (q.isEmpty()) null
            else ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?"
        val args = if (q.isEmpty()) null else arrayOf("%$q%")
        val out = JSONArray()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            selection, args,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )?.use { c: Cursor ->
            var n = 0
            while (c.moveToNext() && n < limit) {
                out.put(JSONObject()
                    .put("name", c.getString(0) ?: "")
                    .put("number", c.getString(1) ?: ""))
                n++
            }
        }
        return JSONObject().put("count", out.length()).put("contacts", out)
    }

    // ---- dial ----------------------------------------------------

    fun dial(number: String, direct: Boolean): JSONObject {
        if (direct) {
            if (!ensurePermission(Manifest.permission.CALL_PHONE)) return denied("call_phone",
                "Direct dialing needs the Phone permission; without it only the dialer can be opened (direct=false).")
            return runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_CALL, Uri.parse("tel:$number"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                JSONObject().put("dialed", number).put("direct", true)
            }.getOrElse { JSONObject().put("error", "call failed: ${it.message}") }
        }
        return runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            JSONObject().put("dialer_opened", number).put("direct", false)
        }.getOrElse { JSONObject().put("error", "dialer failed: ${it.message}") }
    }

    // ---- call log ----------------------------------------------------

    fun callLog(limit: Int = 15): JSONObject {
        if (!ensurePermission(Manifest.permission.READ_CALL_LOG)) return denied("call_log")
        val out = JSONArray()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null,
            CallLog.Calls.DATE + " DESC",
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n < limit) {
                out.put(JSONObject()
                    .put("name", c.getString(0) ?: c.getString(1))
                    .put("number", c.getString(1))
                    .put("type", when (c.getInt(2)) {
                        CallLog.Calls.INCOMING_TYPE -> "incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        else -> "other"
                    })
                    .put("when", SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(c.getLong(3))))
                    .put("duration_s", c.getLong(4)))
                n++
            }
        }
        return JSONObject().put("count", out.length()).put("calls", out)
    }

    // ---- sms ----------------------------------------------------

    fun readSms(box: String, limit: Int = 15): JSONObject {
        if (!ensurePermission(Manifest.permission.READ_SMS)) return denied("sms")
        val uri = if (box == "sent") Telephony.Sms.Sent.CONTENT_URI else Telephony.Sms.Inbox.CONTENT_URI
        val out = JSONArray()
        context.contentResolver.query(
            uri,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null,
            "date DESC",
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n < limit) {
                out.put(JSONObject()
                    .put("address", c.getString(0) ?: "")
                    .put("body", (c.getString(1) ?: "").take(300))
                    .put("when", SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(c.getLong(2)))))
                n++
            }
        }
        return JSONObject().put("count", out.length())
            .put("box", box)
            .put("messages", out)
    }

    fun sendSms(number: String, body: String): JSONObject {
        if (!ensurePermission(Manifest.permission.SEND_SMS)) return denied("send_sms")
        return runCatching {
            SmsManager.getDefault().sendTextMessage(number, null, body, null, null)
            JSONObject().put("sent", true).put("to", number).put("len", body.length)
        }.getOrElse { JSONObject().put("error", "send failed: ${it.message}") }
    }

    // ---- calendar ----------------------------------------------------

    fun calendarEvents(daysAhead: Int = 3): JSONObject {
        if (!ensurePermission(Manifest.permission.READ_CALENDAR)) return denied("calendar")
        val now = System.currentTimeMillis()
        val until = now + daysAhead * 86_400_000L
        val out = JSONArray()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(until.toString())
            .build()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DESCRIPTION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION,
            ),
            null, null,
            CalendarContract.Instances.BEGIN + " ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                out.put(JSONObject()
                    .put("title", c.getString(0) ?: "")
                    .put("desc", (c.getString(1) ?: "").take(120))
                    .put("begin", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(c.getLong(2))))
                    .put("end", SimpleDateFormat("HH:mm", Locale.US).format(Date(c.getLong(3))))
                    .put("location", c.getString(4) ?: ""))
            }
        }
        return JSONObject().put("count", out.length()).put("events", out)
    }

    fun addCalendarEvent(title: String, beginMs: Long, endMs: Long?, location: String?, description: String?): JSONObject {
        if (!ensurePermission(Manifest.permission.WRITE_CALENDAR)) return denied("write_calendar")
        val cv = android.content.ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, primaryCalendarId())
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, beginMs)
            put(CalendarContract.Events.DTEND, endMs ?: (beginMs + 3_600_000L))
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        return runCatching {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv)
            if (uri != null) JSONObject().put("added", title).put("event_uri", uri.toString())
            else JSONObject().put("error", "insert returned null")
        }.getOrElse { JSONObject().put("error", "insert failed: ${it.message}") }
    }

    private fun primaryCalendarId(): Long {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            null, null, null,
        )?.use { c ->
            var first = -1L
            while (c.moveToNext()) {
                if (c.getInt(1) == 1) return c.getLong(0)
                if (first < 0) first = c.getLong(0)
            }
            if (first >= 0) return first
        }
        return 1L
    }

    // ---- permissions ----------------------------------------------------

    private fun ensurePermission(perm: String): Boolean =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun denied(what: String, hint: String = "Ask the user to grant it once (Settings → Apps → Permissions), then retry.") =
        JSONObject().put("error", "permission denied: $what").put("hint", hint)
}
