package com.henry.kite.airplay.rtsp

import android.util.Log
import com.henry.kite.airplay.pairing.PairingTransport
import com.henry.kite.airplay.pairing.SessionKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * RTSP client for the AirPlay control connection.
 *
 * The same TCP connection carries pairing, stream setup, and teardown, and it has
 * to stay open for the whole mirroring session: closing it tells the receiver to
 * stop. After [enableEncryption] every byte in both directions is wrapped by
 * [EncryptedStream], which is why reads and writes share one mutex.
 *
 * Receivers with "require password" enabled answer *every* request — pairing
 * included — with an RTSP Digest challenge (doubletake arms its Digest state on
 * the first 401 and then rides the same challenge on all later requests). Set
 * [digestPasswordProvider] before the first request and this client answers the
 * challenges itself: one retry on the challenged request, then the cached
 * challenge is reused preemptively for the rest of the connection.
 */
class RtspClient(
    private val address: InetAddress,
    private val port: Int,
    private val clientName: String,
    /** Our own session UUID, echoed in the Client-Instance and Session headers. */
    val sessionId: String = UUID.randomUUID().toString(),
) : PairingTransport,
    Closeable {

    /**
     * Resolves the receiver's AirPlay password when a Digest challenge arrives.
     * May suspend (dialog) or throw to abort the session; invoked at most once
     * per connection unless the password is rejected.
     */
    var digestPasswordProvider: (suspend () -> String)? = null

    /**
     * When false, requests carry only CSeq, User-Agent and Content-Type —
     * doubletake's SETUP shape. Identity headers (Client-Instance, DACP-ID,
     * Active-Remote, Client-Name/ID) stay on for pairing and legacy sessions.
     */
    @Volatile
    var sendIdentityHeaders: Boolean = true

    private val lock = Mutex()
    private var socket: Socket? = null
    private var rawInput: InputStream? = null
    private var rawOutput: BufferedOutputStream? = null
    private var encrypted: EncryptedStream? = null
    private var cseq = 0

    /** Digest state, established on the first challenge and reused afterwards. */
    private var digestAuth: DigestAuth? = null
    private var digestChallenge: String? = null
    private var digestRequestCount = 0

    /** Local address the socket bound to; the receiver needs it for the data channels. */
    val localAddress: InetAddress?
        get() = socket?.localAddress

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    suspend fun connect(timeoutMs: Int = CONNECT_TIMEOUT_MS) = withContext(Dispatchers.IO) {
        lock.withLock {
            check(socket == null) { "Already connected" }
            val s = Socket()
            s.tcpNoDelay = true // Control messages are small and latency-sensitive.
            s.soTimeout = READ_TIMEOUT_MS
            s.connect(InetSocketAddress(address, port), timeoutMs)
            socket = s
            rawInput = BufferedInputStream(s.getInputStream())
            rawOutput = BufferedOutputStream(s.getOutputStream())
            Log.i(TAG, "Connected to ${address.hostAddress}:$port from ${s.localAddress?.hostAddress}")
        }
    }

    /**
     * Turns on the ChaCha20-Poly1305 layer. Call immediately after pair-verify M4
     * and before any other request, since the receiver switches at the same point.
     */
    suspend fun enableEncryption(keys: SessionKeys) = withContext(Dispatchers.IO) {
        lock.withLock {
            val input = rawInput ?: error("Not connected")
            val output = rawOutput ?: error("Not connected")
            check(encrypted == null) { "Encryption already enabled" }
            encrypted = EncryptedStream(input, output, keys)
            Log.i(TAG, "Control channel encryption enabled")
        }
    }

    suspend fun send(request: RtspRequest): RtspResponse = withContext(Dispatchers.IO) {
        lock.withLock { sendLocked(request) }
    }

    /**
     * Writes [request] without waiting for a response — for TEARDOWN on stop,
     * where blocking up to the read timeout on the caller's thread (the main
     * service thread) would freeze the UI. The receiver also tears down when
     * the socket closes, so an unread response is harmless.
     */
    fun writeOnly(request: RtspRequest) {
        val out = rawOutput ?: return
        try {
            val wire = withPreemptiveAuth(request)
            val cseq = 0 // the request is terminal; the receiver closes anyway
            out.write(wire.encode(cseq, defaultHeaders()))
            out.flush()
        } catch (e: Exception) {
            Log.d(TAG, "write-only ${request.method} failed: ${e.message}")
        }
    }

    override suspend fun postTlv(path: String, body: ByteArray, headers: Map<String, String>): ByteArray {
        val response = send(
            RtspRequest(
                method = "POST",
                uri = path,
                headers = headers + mapOf("Content-Type" to CONTENT_TYPE_OCTET_STREAM),
                body = body,
            ),
        )
        if (!response.isSuccess) {
            throw RtspException(
                "Receiver returned ${response.statusCode} ${response.reason} for $path",
                response.statusCode,
            )
        }
        return response.body
    }

    private suspend fun sendLocked(request: RtspRequest): RtspResponse {
        var response = transmit(request)
        val challenge = response.authChallenge() ?: return response

        // First challenge on this connection: answer it once and remember the
        // nonce for every later request. A receiver that challenges one request
        // challenges them all, and a mirroring session issues around two dozen.
        digestChallenge = challenge
        val provider = digestPasswordProvider
        val password = if (digestAuth != null) {
            null // Already answering preemptively; a second challenge means rejection.
        } else {
            provider?.invoke()
        } ?: return response // No credential available; let the caller see the 401/470.

        digestAuth = DigestAuth(DIGEST_USERNAME, password)
        Log.d(TAG, "Answering Digest challenge on ${request.method} ${request.uri}")
        response = transmit(request)
        response.authChallenge()?.let {
            digestAuth = null
            digestChallenge = null
            throw DigestRejectedException("${request.method} ${request.uri}: AirPlay password rejected")
        }
        return response
    }

    /** Writes [request] (stamping preemptive Digest auth on it) and reads one response. */
    private fun transmit(request: RtspRequest): RtspResponse {
        val output = rawOutput ?: error("Not connected")
        cseq++

        val wire = withPreemptiveAuth(request).encode(cseq, defaultHeaders())
        Log.i(
            TAG,
            "[HTTP] -> ${request.method} ${request.uri} " +
                "(body=${request.body?.size ?: 0} bytes, encrypted=${encrypted != null}, cseq=$cseq)",
        )
        if (request.method == "SETUP") {
            // Plaintext pre-encryption bytes, for byte-level diffing against
            // a working session on the same receiver.
            Log.i(TAG, "SETUP-PLAINTEXT=${wire.joinToString("") { "%02x".format(it) }}")
        }

        val cipher = encrypted
        if (cipher != null) {
            cipher.write(wire)
        } else {
            output.write(wire)
            output.flush()
        }

        val response = readResponse()
        Log.i(
            TAG,
            "[READ] <- ${response.statusCode} ${response.reason} " +
                "content-length=${response.body?.size ?: 0} cseq=${response["CSeq"]}",
        )

        // A mismatched CSeq means the stream desynced; nothing after this is trustworthy.
        response["CSeq"]?.toIntOrNull()?.let { echoed ->
            if (echoed != cseq) {
                throw RtspException("CSeq mismatch: sent $cseq, receiver echoed $echoed")
            }
        }
        return response
    }

    /** Stamps the established Digest authorization onto [request], if any. */
    private fun withPreemptiveAuth(request: RtspRequest): RtspRequest {
        val auth = digestAuth ?: return request
        val challenge = digestChallenge ?: return request
        if (request.headers.keys.any { it.equals("Authorization", ignoreCase = true) }) return request
        digestRequestCount++
        return request.withHeader(
            "Authorization",
            auth.authorization(challenge, request.method, request.uri, digestRequestCount),
        )
    }

    private fun readResponse(): RtspResponse {
        val cipher = encrypted
        val source: InputStream = if (cipher != null) {
            FrameReassemblingStream(cipher)
        } else {
            rawInput ?: error("Not connected")
        }

        val statusLine = source.readLine()
            ?: throw RtspException("Receiver closed the connection before replying")
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2 || (!parts[0].startsWith("RTSP") && !parts[0].startsWith("HTTP"))) {
            throw RtspException("Malformed status line: '$statusLine'")
        }
        val statusCode = parts[1].toIntOrNull()
            ?: throw RtspException("Malformed status code in '$statusLine'")
        val reason = parts.getOrNull(2)?.trim().orEmpty()

        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = source.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue // Tolerate the odd malformed continuation line.
            headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }

        val contentLength = headers.entries
            .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) source.readExactly(contentLength) else ByteArray(0)

        return RtspResponse(statusCode, reason, headers, body)
    }

    private fun defaultHeaders(): Map<String, String> {
        if (!sendIdentityHeaders) return linkedMapOf("User-Agent" to USER_AGENT)
        return linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Client-Instance" to sessionId,
            "DACP-ID" to sessionId,
            "Active-Remote" to activeRemote,
            "X-Apple-Client-Name" to clientName,
            "X-Apple-Client-ID" to sessionId,
        )
    }

    /** Numeric token the receiver uses to address DACP remote-control requests back at us. */
    private val activeRemote: String =
        (sessionId.hashCode().toLong() and 0xFFFFFFFFL).toString()

    override fun close() {
        runCatching { rawOutput?.flush() }
        runCatching { socket?.close() }
        socket = null
        rawInput = null
        rawOutput = null
        encrypted = null
        Log.i(TAG, "Control connection closed")
    }

    private companion object {
        const val TAG = "RtspClient"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 15_000
        const val CONTENT_TYPE_OCTET_STREAM = "application/octet-stream"
        const val USER_AGENT = "AirPlay/935.7.1"

        /** The username mirroring receivers expect in a Digest response. */
        const val DIGEST_USERNAME = "AirPlay"
    }
}

/** The receiver answered our Digest response with another challenge: bad password. */
class DigestRejectedException(message: String) : RtspException(message, 401)

/**
 * Presents [EncryptedStream]'s frames as a byte stream, so the RTSP parser does
 * not need to know where frame boundaries fall. Frames and RTSP messages are
 * unrelated: one response can span several frames, and one frame can hold the
 * tail of one message plus the head of the next.
 */
private class FrameReassemblingStream(private val stream: EncryptedStream) : InputStream() {
    private var buffer = ByteArray(0)
    private var position = 0

    override fun read(): Int {
        if (!fill()) return -1
        return buffer[position++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val available = buffer.size - position
        val count = minOf(len, available)
        buffer.copyInto(b, off, position, position + count)
        position += count
        return count
    }

    override fun available(): Int = buffer.size - position

    private fun fill(): Boolean {
        while (position >= buffer.size) {
            buffer = stream.readFrame()
            position = 0
            if (buffer.isEmpty()) return false
        }
        return true
    }
}

/** Reads one CRLF-terminated line. Returns null at end of stream. */
internal fun InputStream.readLine(): String? {
    val out = ByteArrayOutputStream()
    while (true) {
        val b = read()
        if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.UTF_8.name())
        if (b == '\n'.code) {
            val bytes = out.toByteArray()
            val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) {
                bytes.size - 1
            } else {
                bytes.size
            }
            return String(bytes, 0, end, Charsets.UTF_8)
        }
        out.write(b)
    }
}
