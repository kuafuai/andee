package net.kuafuai.andee.tts

import android.util.Log
import net.kuafuai.andee.config.VoiceConfig
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/**
 * ByteDance 火山 bidirectional TTS client (seed-tts-2.0).
 * Direct Kotlin port of electron-vite's src/main/ttsBidi.ts.
 *
 * Session lifecycle (event-driven):
 *   client → server: StartConnection (1)
 *   server → client: ConnectionStarted (50)
 *   client → server: StartSession (100)   [carries speaker + audio_params]
 *   server → client: SessionStarted (150)  → we are "ready"
 *   client → server: TaskRequest (200)    [carries a chunk of text; may repeat]
 *   server → client: TTSSentenceStart/End (350/351) + AudioOnlyServer (audio bytes)
 *   client → server: FinishSession (102)  [tell server no more text]
 *   server → client: SessionFinished (152) → we cleanup
 *   client → server: FinishConnection (2)
 *   server → client: ConnectionFinished (52) → ws closes
 *
 * Client frame layout for events:
 *   header(4) + event(int32) + [sessionId size(u32) + bytes]? + payload size(u32) + payload
 *   (session_id is omitted for connection-level events: 1, 2, 50, 51, 52)
 */
class HuoshanTts(
    private val config: VoiceConfig,
    private val listener: Listener,
) {
    interface Listener {
        fun onOpen(sampleRate: Int)
        fun onAudio(pcm: ByteArray)
        fun onSentenceStart(text: String)
        fun onSentenceEnd(text: String)
        fun onDone()
        fun onError(msg: String)
        fun onClose(reason: CloseReason)
    }

    enum class CloseReason { USER, SERVER, ERROR }
    private enum class State { IDLE, CONNECTING, SESSION_OPENING, READY, FINISHING, CLOSED }

    private var ws: WebSocketClient? = null

    /** See [net.kuafuai.andee.asr.HuoshanAsr]'s field of the same name. */
    @Volatile
    private var handshakeRefusal: String? = null

    @Volatile
    private var opened = false

    @Volatile
    private var state: State = State.IDLE
    private var sessionId: String = ""
    private var connectId: String = ""
    private var failReason: CloseReason = CloseReason.SERVER
    private var baseReqParams: JSONObject? = null

    @Volatile
    private var audioFrames: Int = 0

    @Volatile
    private var pendingFinishTrigger: (() -> Unit)? = null

    fun open() {
        check(state == State.IDLE) { "already open (state=$state)" }

        state = State.CONNECTING
        sessionId = UUID.randomUUID().toString()
        connectId = UUID.randomUUID().toString()

        val headers = mapOf(
            "X-Api-Key" to config.apiKey,
            "X-Api-Resource-Id" to config.ttsResourceId,
            "X-Api-Connect-Id" to connectId,
            "X-Control-Require-Usage-Tokens-Return" to "*",
        )

        Log.d(
            TAG,
            "open: ep=${config.ttsEndpoint} speaker=${config.ttsSpeaker} " + "sessionId=$sessionId connectId=$connectId"
        )

        val uri = URI(config.ttsEndpoint)
        val c = object : WebSocketClient(uri, headers) {
            override fun onOpen(handshake: ServerHandshake?) {
                opened = true
                Log.d(TAG, "ws open status=${handshake?.httpStatus}")
                baseReqParams = JSONObject().apply {
                    put("speaker", config.ttsSpeaker)
                    put(
                        "audio_params",
                        JSONObject().put("format", "pcm").put("sample_rate", config.ttsSampleRate)
                    )
                }
                sendEvent(EVT_START_CONNECTION, "{}".toByteArray(Charsets.UTF_8))
                state = State.SESSION_OPENING
            }

            override fun onMessage(message: String?) { /* text frames ignored */
            }

            override fun onMessage(bytes: ByteBuffer?) {
                bytes ?: return
                val raw = ByteArray(bytes.remaining())
                bytes.get(raw)
                handleMessage(raw)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                if (!opened) handshakeRefusal = reason?.takeIf { it.isNotBlank() } ?: "code $code"
                Log.d(TAG, "onClose code=$code reason=$reason remote=$remote frames=$audioFrames")
                state = State.CLOSED
                listener.onClose(failReason)
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "onError", ex)
                failReason = CloseReason.ERROR
                listener.onError("ws error: ${ex?.message ?: ex?.javaClass?.simpleName}")
            }
        }
        if (uri.scheme.equals("wss", ignoreCase = true)) {
            c.setSocketFactory(SSLContext.getDefault().socketFactory)
        }
        ws = c
        val ok = c.connectBlocking(5, TimeUnit.SECONDS)
        if (!ok) {
            val refusal = handshakeRefusal
            // See HuoshanAsr for why a refusal must not be reported as a
            // timeout: both come back from connectBlocking as false, and only
            // one of them is about the network.
            throw IllegalStateException(
                if (refusal == null) {
                    "TTS ws connect timeout"
                } else {
                    "TTS ws refused: $refusal (check the 火山 API Key in ⚙)"
                },
            )
        }
    }

    /** Push a chunk of text to be spoken. Can be called multiple times. */
    fun push(text: String) {
        if (state != State.READY) {
            Log.w(TAG, "push dropped, state=$state")
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // Skip pure punctuation like ttsBidi.ts does — server would emit an empty sentence.
        if (trimmed.matches(Regex("^[\\s\\p{P}\\p{S}]*$"))) {
            Log.d(TAG, "push skipped (no content): $text")
            return
        }
        val body = JSONObject().put("event", EVT_TASK_REQUEST).put(
            "req_params", JSONObject(baseReqParams?.toString() ?: "{}").put("text", text)
        )
        sendEvent(EVT_TASK_REQUEST, body.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * Tell server "no more text incoming, please finalize synthesis".
     *
     * TCP preserves order of push→finish frames on the same connection, so no race.
     * The ttsBidi.ts "wait for first audio" trick is only needed when you want to
     * confirm the server ingested pushes before finalizing — but with 火山's
     * seed-tts-2.0, some speakers don't emit audio until they see FinishSession,
     * causing a mutual wait deadlock. Send immediately.
     */
    fun finish() {
        if (state == State.CLOSED || state == State.IDLE) return
        if (state != State.READY && state != State.SESSION_OPENING) return
        state = State.FINISHING
        Log.d(TAG, "FinishSession (immediate)")
        sendEvent(EVT_FINISH_SESSION, "{}".toByteArray(Charsets.UTF_8))
    }

    fun cancel() {
        if (state == State.CLOSED || state == State.IDLE) return
        failReason = CloseReason.USER
        try {
            if (state == State.READY || state == State.SESSION_OPENING) {
                sendEvent(EVT_CANCEL_SESSION, "{}".toByteArray(Charsets.UTF_8))
            }
        } catch (_: Throwable) {
        }
        try {
            ws?.close()
        } catch (_: Throwable) {
        }
    }

    // ---- Internal ----

    private fun sendEvent(event: Int, payload: ByteArray) {
        val c = ws ?: return
        val frame = buildClientEventFrame(event, sessionId, payload)
        c.send(frame)
    }

    private fun handleMessage(bytes: ByteArray) {
        val msg = try {
            parseMessage(bytes)
        } catch (t: Throwable) {
            listener.onError("parse failed: ${t.message}")
            return
        }
        if (msg.type != MSG_SERVER_AUDIO_ONLY) {
            Log.d(
                TAG,
                "recv type=0x${Integer.toHexString(msg.type)} event=${msg.event} " + "payload=${msg.payload.size}B " + if (msg.type == MSG_SERVER_FULL_RESPONSE && msg.payload.size < 200) {
                    String(msg.payload, Charsets.UTF_8)
                } else ""
            )
        }

        if (msg.type == MSG_SERVER_AUDIO_ONLY && msg.payload.isNotEmpty()) {
            audioFrames += 1
            listener.onAudio(msg.payload)
            // First audio → server has ingested our push → safe to send FinishSession
            val trigger = pendingFinishTrigger
            if (trigger != null) {
                pendingFinishTrigger = null
                trigger()
            }
            return
        }
        if (msg.type == MSG_SERVER_ERROR) {
            failReason = CloseReason.ERROR
            listener.onError(
                "TTS server error code=${msg.errorCode} " + "msg=${
                    String(
                        msg.payload,
                        Charsets.UTF_8
                    )
                }"
            )
            return
        }
        if (msg.type != MSG_SERVER_FULL_RESPONSE) return

        when (msg.event) {
            EVT_CONNECTION_STARTED -> {
                // Connection ready → open session
                val body = JSONObject().put("event", EVT_START_SESSION)
                    .put("req_params", baseReqParams ?: JSONObject())
                sendEvent(EVT_START_SESSION, body.toString().toByteArray(Charsets.UTF_8))
            }

            EVT_CONNECTION_FAILED -> {
                failReason = CloseReason.ERROR
                listener.onError("ConnectionFailed: ${String(msg.payload, Charsets.UTF_8)}")
            }

            EVT_SESSION_STARTED -> {
                state = State.READY
                listener.onOpen(config.ttsSampleRate)
            }

            EVT_TTS_SENTENCE_START -> {
                val text = runCatching {
                    JSONObject(String(msg.payload, Charsets.UTF_8)).optString("text", "")
                }.getOrDefault("")
                listener.onSentenceStart(text)
            }

            EVT_TTS_SENTENCE_END -> {
                val text = runCatching {
                    JSONObject(String(msg.payload, Charsets.UTF_8)).optString("text", "")
                }.getOrDefault("")
                listener.onSentenceEnd(text)
            }

            EVT_TTS_RESPONSE -> { /* server sometimes uses this for audio; unhandled */
            }

            EVT_SESSION_FINISHED -> {
                listener.onDone()
                sendEvent(EVT_FINISH_CONNECTION, "{}".toByteArray(Charsets.UTF_8))
            }

            EVT_SESSION_CANCELED -> {
                sendEvent(EVT_FINISH_CONNECTION, "{}".toByteArray(Charsets.UTF_8))
            }

            EVT_SESSION_FAILED -> {
                failReason = CloseReason.ERROR
                listener.onError("SessionFailed: ${String(msg.payload, Charsets.UTF_8)}")
            }

            EVT_CONNECTION_FINISHED -> {
                try {
                    ws?.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    // ---- Wire format ----

    private data class ParsedMessage(
        val type: Int,
        val flag: Int,
        val event: Int,
        val errorCode: Int,
        val payload: ByteArray,
    )

    private fun buildClientEventFrame(
        event: Int, sessionId: String, payload: ByteArray
    ): ByteArray {
        val header = byteArrayOf(
            ((PROTOCOL_VERSION_V1 shl 4) or HEADER_SIZE_4).toByte(),
            ((MSG_CLIENT_FULL_REQUEST shl 4) or FLAG_WITH_EVENT).toByte(),
            ((SERIALIZATION_JSON shl 4) or COMPRESSION_NONE).toByte(),
            0x00,
        )
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(ByteBuffer.allocate(4).putInt(event).array())
        if (!isConnectionEvent(event)) {
            val sid = sessionId.toByteArray(Charsets.UTF_8)
            out.write(ByteBuffer.allocate(4).putInt(sid.size).array())
            out.write(sid)
        }
        out.write(ByteBuffer.allocate(4).putInt(payload.size).array())
        out.write(payload)
        return out.toByteArray()
    }

    private fun parseMessage(buf: ByteArray): ParsedMessage {
        val headerSize = (buf[0].toInt() and 0x0f) * 4
        val type = (buf[1].toInt() shr 4) and 0x0f
        val flag = buf[1].toInt() and 0x0f
        var offset = headerSize
        var event = 0
        var errorCode = 0

        val isDataMsg =
            type == MSG_CLIENT_FULL_REQUEST || type == MSG_SERVER_FULL_RESPONSE || type == MSG_SERVER_AUDIO_ONLY || type == 0xc
        if (isDataMsg) {
            if (flag == 0x1 || flag == 0x3) offset += 4  // skip sequence
        } else if (type == MSG_SERVER_ERROR) {
            errorCode = intAtBE(buf, offset)
            offset += 4
        }

        if (flag == FLAG_WITH_EVENT) {
            event = intAtBE(buf, offset)
            offset += 4
            // Skip session_id (except connection-level events don't carry it)
            if (!isConnectionEvent(event)) {
                val sidSize = intAtBE(buf, offset)
                offset += 4
                offset += sidSize
            }
            // connect_id present only on 50/51/52
            if (event == EVT_CONNECTION_STARTED || event == EVT_CONNECTION_FAILED || event == EVT_CONNECTION_FINISHED) {
                val cidSize = intAtBE(buf, offset)
                offset += 4
                offset += cidSize
            }
        }

        val payloadSize = intAtBE(buf, offset)
        offset += 4
        val payload = buf.copyOfRange(offset, offset + payloadSize)
        return ParsedMessage(type, flag, event, errorCode, payload)
    }

    private fun intAtBE(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    private fun isConnectionEvent(event: Int): Boolean =
        event == EVT_START_CONNECTION || event == EVT_FINISH_CONNECTION || event == EVT_CONNECTION_STARTED || event == EVT_CONNECTION_FAILED || event == EVT_CONNECTION_FINISHED

    companion object {
        private const val TAG = "HuoshanTts"

        private const val PROTOCOL_VERSION_V1 = 0x1
        private const val HEADER_SIZE_4 = 0x1

        private const val MSG_CLIENT_FULL_REQUEST = 0x1
        private const val MSG_SERVER_FULL_RESPONSE = 0x9
        private const val MSG_SERVER_AUDIO_ONLY = 0xb
        private const val MSG_SERVER_ERROR = 0xf

        private const val FLAG_WITH_EVENT = 0x4
        private const val SERIALIZATION_JSON = 0x1
        private const val COMPRESSION_NONE = 0x0

        private const val EVT_START_CONNECTION = 1
        private const val EVT_FINISH_CONNECTION = 2
        private const val EVT_CONNECTION_STARTED = 50
        private const val EVT_CONNECTION_FAILED = 51
        private const val EVT_CONNECTION_FINISHED = 52
        private const val EVT_START_SESSION = 100
        private const val EVT_CANCEL_SESSION = 101
        private const val EVT_FINISH_SESSION = 102
        private const val EVT_SESSION_STARTED = 150
        private const val EVT_SESSION_CANCELED = 151
        private const val EVT_SESSION_FINISHED = 152
        private const val EVT_SESSION_FAILED = 153
        private const val EVT_TASK_REQUEST = 200
        private const val EVT_TTS_SENTENCE_START = 350
        private const val EVT_TTS_SENTENCE_END = 351
        private const val EVT_TTS_RESPONSE = 352
    }
}
