package net.kuafuai.andee.asr

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
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLContext

/**
 * ByteDance Volcano (火山) streaming ASR client.
 * Direct Kotlin port of wxBot's ui/mic_asr_stream.py binary protocol.
 *
 * Wire format for each message:
 *   header (4 bytes) + seq (int32 BE) + payloadSize (uint32 BE) + gzipped(payload)
 *
 * Header bytes:
 *   [0] (proto_ver<<4) | header_size(=1)  → 0x11
 *   [1] (message_type<<4) | flags
 *   [2] (serialization<<4) | compression  → 0x11 (JSON + gzip)
 *   [3] reserved (0)
 */
class HuoshanAsr(
    private val config: VoiceConfig,
    private val listener: Listener,
) {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(msg: String)
        fun onClosed()
    }

    private var client: WebSocketClient? = null

    // Full client request uses seq=1; audio chunks start at 2 (火山 protocol).
    private val seq = AtomicInteger(2)

    @Volatile
    private var lastPartial: String = ""

    @Volatile
    private var gotAnyText: Boolean = false
    private val chunksSent = AtomicInteger(0)

    // Counted down when server sends an isLast response, or on error/close.
    // waitFinished() blocks on this so we don't close the WS while the final
    // message is still in flight.
    private val finishedLatch = java.util.concurrent.CountDownLatch(1)

    /**
     * Why the handshake failed, when it failed by being *answered* rather than
     * by going unanswered.
     *
     * `connectBlocking` returns false for both, so without this the one error
     * anyone ever sees is "connect timeout" — which sends whoever reads it to
     * look at the network, when the truth is usually a server that replied at
     * once and said no (a blank or revoked `X-Api-Key` answers 403). That has
     * already cost one round of debugging; the self-check's voice row probes a
     * bare TCP connect for the same reason, and a green row beside a red
     * "timeout" is the contradiction this removes.
     *
     * Only written before [connected] is set, so a normal close at the end of
     * a session cannot overwrite it with something irrelevant.
     */
    @Volatile
    private var handshakeRefusal: String? = null

    @Volatile
    private var connected = false

    fun start(sampleRate: Int = 16_000, channels: Int = 1) {
        val requestId = UUID.randomUUID().toString()
        val connectId = UUID.randomUUID().toString()
        val headers = mapOf(
            "X-Api-Key" to config.apiKey,
            "X-Api-Resource-Id" to config.asrResourceId,
            "X-Api-Request-Id" to requestId,
            "X-Api-Sequence" to "-1",
            "X-Api-Connect-Id" to connectId,
        )

        Log.d(TAG, "start: endpoint=${config.asrEndpoint} sr=$sampleRate uid=${config.asrUid}")
        val uri = URI(config.asrEndpoint)
        val c = object : WebSocketClient(uri, headers) {
            override fun onOpen(handshake: ServerHandshake?) {
                connected = true
                Log.d(
                    TAG,
                    "onOpen status=${handshake?.httpStatus} msg=${handshake?.httpStatusMessage}"
                )
                val cfg = JSONObject().apply {
                    put("user", JSONObject().put("uid", config.asrUid))
                    put(
                        "audio",
                        JSONObject()
                            .put("format", "pcm")
                            .put("codec", "raw")
                            .put("rate", sampleRate)
                            .put("bits", 16)
                            .put("channel", channels),
                    )
                    put(
                        "request",
                        JSONObject()
                            .put("model_name", "bigmodel")
                            .put("enable_itn", true)
                            .put("enable_punc", true)
                            .put("enable_ddc", true)
                            .put("show_utterances", true)
                            .put("enable_nonstream", false),
                    )
                }
                send(buildFullClientRequest(1, cfg.toString()))
                Log.d(TAG, "sent full client request payload=$cfg")
            }

            override fun onMessage(message: String?) {
                Log.d(TAG, "onMessage(text): $message")
            }

            override fun onMessage(bytes: ByteBuffer?) {
                bytes ?: return
                val raw = ByteArray(bytes.remaining())
                bytes.get(raw)
                handleResponse(raw)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                if (!connected) handshakeRefusal = reason?.takeIf { it.isNotBlank() } ?: "code $code"
                Log.d(
                    TAG,
                    "onClose code=$code reason=$reason remote=$remote chunksSent=${chunksSent.get()}"
                )
                listener.onClosed()
                finishedLatch.countDown()
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "onError", ex)
                listener.onError("ws error: ${ex?.message ?: ex?.javaClass?.simpleName}")
                finishedLatch.countDown()
            }
        }
        // Force TLS for wss://.
        if (uri.scheme.equals("wss", ignoreCase = true)) {
            c.setSocketFactory(SSLContext.getDefault().socketFactory)
        }
        client = c
        val opened = c.connectBlocking(5, TimeUnit.SECONDS)
        if (!opened) {
            val refusal = handshakeRefusal
            throw IllegalStateException(
                if (refusal == null) {
                    "ASR ws connect timeout"
                } else {
                    // The server answered. Name the key, because a blank or
                    // revoked one is what 403 means here and the endpoint is
                    // the thing everyone checks first.
                    "ASR ws refused: $refusal (check the 火山 API Key in ⚙)"
                },
            )
        }
    }

    fun sendAudio(pcm: ByteArray, isLast: Boolean) {
        val c = client ?: return
        val current = seq.getAndIncrement()
        val frame = buildAudioRequest(current, pcm, isLast)
        c.send(frame)
        val n = chunksSent.incrementAndGet()
        if (isLast || n <= 3 || n % 20 == 0) {
            Log.d(TAG, "sendAudio seq=$current bytes=${pcm.size} isLast=$isLast totalChunks=$n")
        }
    }

    fun close() {
        try {
            client?.close()
        } catch (_: Throwable) {
        }
        client = null
    }

    // ---- Response handling ----

    private fun handleResponse(msg: ByteArray) {
        if (msg.size < 4) return
        val headerSize = (msg[0].toInt() and 0x0f)
        val messageType = ((msg[1].toInt() shr 4) and 0x0f)
        val flags = msg[1].toInt() and 0x0f
        val compression = msg[2].toInt() and 0x0f

        var offset = headerSize * 4
        var isLast = false

        if (flags and 0x01 != 0) offset += 4               // seq present
        if (flags and 0x02 != 0) isLast = true             // last package
        if (flags and 0x04 != 0) offset += 4               // event present

        val payload: ByteArray = when (messageType) {
            MSG_SERVER_FULL_RESPONSE -> {
                if (offset + 4 > msg.size) return
                val size = intAtBE(msg, offset)
                offset += 4
                if (offset + size > msg.size) return
                msg.copyOfRange(offset, offset + size)
            }

            MSG_SERVER_ERROR_RESPONSE -> {
                if (offset + 8 > msg.size) return
                val code = intAtBE(msg, offset)
                val size = intAtBE(msg, offset + 4)
                offset += 8
                val body = if (offset + size <= msg.size) msg.copyOfRange(
                    offset,
                    offset + size
                ) else ByteArray(0)
                val decompressed =
                    if (compression == COMPRESSION_GZIP) runCatching { ungzip(body) }.getOrDefault(
                        body
                    ) else body
                val errMsg = String(decompressed, Charsets.UTF_8)
                Log.e(TAG, "recv ERROR code=$code msg=$errMsg")
                listener.onError("ASR server error code=$code msg=$errMsg")
                finishedLatch.countDown()
                return
            }

            else -> {
                Log.w(TAG, "recv unknown msgType=$messageType flags=$flags")
                return
            }
        }

        val decompressed = if (compression == COMPRESSION_GZIP) {
            runCatching { ungzip(payload) }.getOrElse {
                Log.w(TAG, "gunzip failed on ${payload.size} bytes")
                return
            }
        } else payload

        val bodyStr = String(decompressed, Charsets.UTF_8)
        Log.d(
            TAG,
            "recv msgType=$messageType flags=$flags isLast=$isLast body=${bodyStr.take(300)}"
        )

        val text = try {
            JSONObject(bodyStr).optJSONObject("result")?.optString("text").orEmpty().trim()
        } catch (_: Throwable) {
            Log.w(TAG, "response payload not JSON with result.text")
            return
        }

        if (text.isNotEmpty()) {
            lastPartial = text
            gotAnyText = true
            listener.onPartial(text)
        }
        if (isLast) {
            if (gotAnyText) {
                listener.onFinal(lastPartial)
            } else {
                // A clean end with nothing recognised is an empty turn: the ball
                // got tapped and nobody said anything. It used to be reported as
                // an error, which meant flashing a red server-diagnostic string
                // at a user whose only mistake was silence.
                //
                // The auth / config failures this once tried to catch don't look
                // like this — they arrive as MSG_SERVER_ERROR_RESPONSE above, or
                // as a ws error, never as a well-formed isLast after a handshake
                // and N accepted audio chunks. Kept at info level because the
                // chunk count is the first thing to look at if the mic itself is
                // broken: an empty turn still sends chunks, a dead mic doesn't.
                Log.i(
                    TAG,
                    "isLast with no transcription — empty turn " +
                        "(chunksSent=${chunksSent.get()}, body=${bodyStr.take(200)})",
                )
                listener.onFinal("")
            }
            finishedLatch.countDown()
        }
    }

    /**
     * Block up to [timeoutMs] waiting for the server's isLast response (or an
     * error / socket close). Call this after sending the isLast audio chunk,
     * before [close], so the final transcript has a chance to arrive.
     */
    fun waitFinished(timeoutMs: Long = 3000): Boolean =
        finishedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    // ---- Frame builders ----

    private fun header(msgType: Int, flags: Int): ByteArray = byteArrayOf(
        ((PROTOCOL_VERSION_V1 shl 4) or 1).toByte(),
        ((msgType shl 4) or flags).toByte(),
        ((SERIALIZATION_JSON shl 4) or COMPRESSION_GZIP).toByte(),
        0x00,
    )

    private fun buildFullClientRequest(seqNum: Int, payloadJson: String): ByteArray {
        val body = gzip(payloadJson.toByteArray(Charsets.UTF_8))
        val out = ByteArrayOutputStream()
        out.write(header(MSG_CLIENT_FULL_REQUEST, FLAG_POS_SEQUENCE))
        out.write(ByteBuffer.allocate(4).putInt(seqNum).array())
        out.write(ByteBuffer.allocate(4).putInt(body.size).array())
        out.write(body)
        return out.toByteArray()
    }

    private fun buildAudioRequest(seqNum: Int, pcm: ByteArray, isLast: Boolean): ByteArray {
        val actualSeq = if (isLast) -seqNum else seqNum
        val flags = if (isLast) FLAG_NEG_WITH_SEQUENCE else FLAG_POS_SEQUENCE
        val body = gzip(pcm)
        val out = ByteArrayOutputStream()
        out.write(header(MSG_CLIENT_AUDIO_ONLY_REQUEST, flags))
        out.write(ByteBuffer.allocate(4).putInt(actualSeq).array())
        out.write(ByteBuffer.allocate(4).putInt(body.size).array())
        out.write(body)
        return out.toByteArray()
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun ungzip(data: ByteArray): ByteArray {
        return GZIPInputStream(data.inputStream()).use { it.readBytes() }
    }

    private fun intAtBE(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or
                ((b[o + 1].toInt() and 0xff) shl 16) or
                ((b[o + 2].toInt() and 0xff) shl 8) or
                (b[o + 3].toInt() and 0xff)

    companion object {
        private const val TAG = "HuoshanAsr"
        private const val PROTOCOL_VERSION_V1 = 0b0001
        private const val MSG_CLIENT_FULL_REQUEST = 0b0001
        private const val MSG_CLIENT_AUDIO_ONLY_REQUEST = 0b0010
        private const val MSG_SERVER_FULL_RESPONSE = 0b1001
        private const val MSG_SERVER_ERROR_RESPONSE = 0b1111
        private const val FLAG_POS_SEQUENCE = 0b0001
        private const val FLAG_NEG_WITH_SEQUENCE = 0b0011
        private const val SERIALIZATION_JSON = 0b0001
        private const val COMPRESSION_GZIP = 0b0001
    }
}
