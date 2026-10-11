package com.henry.kite.airplay.session

import android.util.Log
import com.henry.kite.airplay.discovery.AirPlayReceiver
import com.henry.kite.airplay.rtsp.DigestAuth
import com.henry.kite.airplay.rtsp.RtspClient
import com.henry.kite.airplay.rtsp.RtspException
import com.henry.kite.airplay.rtsp.RtspRequest
import com.henry.kite.airplay.rtsp.RtspResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** The receiver wants a password and none was supplied. The UI shows its dialog. */
class PasswordRequiredException(val receiver: AirPlayReceiver) : Exception("Receiver requires an AirPlay password")

/**
 * Classic AirPlay (AirTunes v1) audio-only session, spoken by AirPort Express,
 * old Apple TVs and shairport-sync style receivers.
 *
 * Flow: OPTIONS → (auth-setup) → ANNOUNCE (SDP) → SETUP (Transport) → RECORD →
 * FLUSH, then a raw UDP RTP stream of 352-sample L16 frames, AES-128-CBC
 * encrypted with a sender-generated key the receiver learns from the
 * RSA-wrapped SDP line.
 *
 * The RTSP connection stays open for the whole session; closing it is the
 * receiver's cue to stop. The details that keep real hardware happy — the
 * auth-setup preamble, the FLUSH with RTP-Info, and the one-second sync beats
 * on the receiver's control port — are the ones the proven pyatv sender uses.
 */
class RaopSession(private val receiver: AirPlayReceiver, private val credentialStore: CredentialStore) : Closeable {

    private val client = RtspClient(receiver.address, receiver.port, clientName = "Kite")
    private val timingServer = TimingServer()
    private var requestCount = 0
    private var sessionUri: String = ""

    /** Carries the RTSP keep-alive for as long as the session is up. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var feedbackJob: Job? = null

    /** UDP sender; non-null once SETUP resolved the receiver's audio port. */
    var audioStream: RaopAudioStream? = null
        private set

    /** Created once, reused across password retries so nothing leaks. */
    private var controlSocket: DatagramSocket? = null

    /** Supplied by [connect]; consulted by [sendAuthorized] on a fresh 401. */
    private var passwordProvider: (suspend () -> String?)? = null

    suspend fun connect(passwordProvider: suspend () -> String?): RaopAudioStream {
        // Reconnect-safe: the password retry loop calls connect() again on the
        // same instance, and the TCP connection (and sockets below) survive it.
        if (!client.isConnected) client.connect()
        this.passwordProvider = passwordProvider

        sendAuthorized("OPTIONS", "*")
        sendAuthSetupIfNeeded()

        val random = SecureRandom()
        val aesKey = ByteArray(16).also(random::nextBytes)
        val aesIv = ByteArray(16).also(random::nextBytes)

        // pyatv (and iTunes) address the session by our own IP without a port;
        // the receiver only checks that every request uses the same URI.
        val localAddress = client.localAddress?.hostAddress ?: "0.0.0.0"
        sessionUri = "rtsp://$localAddress/${client.sessionId}"
        sendAuthorized("ANNOUNCE", sessionUri) {
            header("Content-Type", "application/sdp")
            body(buildSdp(localAddress, aesKey, aesIv))
        }

        if (timingServer.port == 0) timingServer.start()
        val control = controlSocket ?: DatagramSocket().also { controlSocket = it }
        val transport = "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;" +
            "control_port=${control.localPort};timing_port=${timingServer.port}"
        val setup = sendAuthorized("SETUP", sessionUri) {
            header("Transport", transport)
        }
        val serverPort = setupTransportPort(setup, "server_port")
            ?: throw RtspException("SETUP reply had no server_port: ${setup["Transport"]}")
        val receiverControlPort = setupTransportPort(setup, "control_port") ?: 0
        val sessionHeader = setup["Session"]

        sendAuthorized("RECORD", sessionUri) {
            header("Range", "npt=0-")
            if (sessionHeader != null) header("Session", sessionHeader)
        }

        val stream = RaopAudioStream(
            address = receiver.address,
            port = serverPort,
            receiverControlPort = receiverControlPort,
            controlSocket = control,
            cipher = AudioCipher(aesKey, aesIv),
        )
        stream.prime()
        audioStream = stream

        // pyatv caps the setup with a FLUSH carrying the stream's first sequence
        // and timestamp; AirPort Express anchors its jitter buffer on it.
        sendAuthorized("FLUSH", sessionUri) {
            header("Range", "npt=0-")
            if (sessionHeader != null) header("Session", sessionHeader)
            header("RTP-Info", "seq=0;rtptime=${RaopAudioStream.FIRST_TIMESTAMP}")
        }

        startFeedbackLoop()
        return stream
    }

    /**
     * Pre-authenticates AirPort Express / Sonos hardware.
     *
     * owntone found that these refuse ANNOUNCE with 403 unless an `/auth-setup`
     * exchange happens first. We post the no-encryption handshake — flag 0x01
     * followed by the static Curve25519 public key every AirPlay sender ships —
     * and ignore the answer: the point is the round trip, not the key material.
     */
    private suspend fun sendAuthSetupIfNeeded() {
        val model = receiver.model ?: return
        if (!model.startsWith("AirPort") && !model.startsWith("Sonos")) return
        val body = ByteArray(1 + AUTH_SETUP_PUBKEY.size).also {
            it[0] = AUTH_SETUP_FLAG.toByte()
            AUTH_SETUP_PUBKEY.copyInto(it, 1)
        }
        runCatching {
            client.send(
                RtspRequest(
                    method = "POST",
                    uri = "/auth-setup",
                    headers = mapOf("Content-Type" to CONTENT_TYPE_OCTET_STREAM),
                    body = body,
                ),
            )
        }.onFailure { Log.w(TAG, "auth-setup failed; proceeding anyway", it) }
    }

    private fun buildSdp(localAddress: String, aesKey: ByteArray, aesIv: ByteArray): ByteArray {
        if (!receiver.supportsPcmAudio) {
            // ALAC is the other universal codec but has no Android encoder, so
            // announce L16 regardless and let the receiver fail loudly if it
            // genuinely cannot decode PCM.
            Log.w(TAG, "Receiver does not advertise cn=0 (PCM); announcing L16 anyway")
        }
        val sdp = buildString {
            append("v=0\r\n")
            append("o=iTunes ${client.sessionId.hashCode()} 0 IN IP4 $localAddress\r\n")
            append("s=iTunes\r\n")
            append("c=IN IP4 ${receiver.address.hostAddress}\r\n")
            append("t=0 0\r\n")
            append("m=audio 0 RTP/AVP 96\r\n")
            append("a=rtpmap:96 L16/44100/2\r\n")
            append("a=fmtp:96 $FRAMES_PER_PACKET 0 16 40 10 14 2 255 0 0 $SAMPLE_RATE\r\n")
            append("a=rsaaeskey:").append(RaopRsa.wrapAesKey(aesKey)).append("\r\n")
            append("a=aesiv:").append(RaopRsa.encodeIv(aesIv)).append("\r\n")
        }
        return sdp.toByteArray(Charsets.UTF_8)
    }

    /** Auth header from the last Digest round, if one is in play. */
    private var pendingAuth: DigestAuth? = null
    private var pendingChallenge: String? = null

    /**
     * Sends [method], handling the 401 Digest challenge dance.
     *
     * [block] builds the request eagerly — bodies here are small, and the only
     * expensive one (ANNOUNCE's RSA) is built once for each of its two attempts.
     */
    private suspend fun sendAuthorized(
        method: String,
        uri: String,
        block: RtspRequestBuilder.() -> Unit = {},
    ): RtspResponse {
        val builder = RtspRequestBuilder(method, uri).apply(block)

        fun request(authorization: String?): RtspRequest {
            val base = RtspRequest(method = method, uri = uri, headers = builder.headers, body = builder.body)
            return if (authorization != null) base.withHeader("Authorization", authorization) else base
        }

        val challenge = pendingChallenge
        val auth = pendingAuth
        if (challenge != null && auth != null) {
            requestCount++
            val response = client.send(request(auth.authorization(challenge, method, uri, requestCount)))
            if (!response.isUnauthorized) return response
            // Stored password rejected: fall through so the user gets asked.
        }

        val first = client.send(request(null))
        if (!first.isUnauthorized) return first

        val newChallenge = first["WWW-Authenticate"]
            ?: throw RtspException("Receiver returned 401 without a challenge", 401)
        if (!newChallenge.startsWith("Digest", ignoreCase = true)) {
            throw RtspException(
                "Receiver wants '${newChallenge.takeWhile { it != ' ' }}' auth; only Digest is supported",
                401,
            )
        }
        val password = runCatching { credentialStore.loadPassword(receiver.stableId) }.getOrNull()
            ?: passwordProvider?.invoke()?.also {
                // Only reached with a fresh dialog answer; keep it for next time.
                runCatching { credentialStore.savePassword(receiver.stableId, it) }
            }
            ?: throw PasswordRequiredException(receiver)

        val digest = DigestAuth(DIGEST_USERNAME, password)
        requestCount++
        val retry = client.send(request(digest.authorization(newChallenge, method, uri, requestCount)))
        if (retry.isUnauthorized) {
            // A rejected password must not survive: the next attempt has to ask
            // the user again instead of looping on a known-bad value.
            runCatching { credentialStore.forgetPassword(receiver.stableId) }
            throw RtspException("AirPlay password rejected", 401)
        }
        pendingAuth = digest
        pendingChallenge = newChallenge
        return retry
    }

    /** Pulls [key] (e.g. `server_port`) out of the SETUP reply's Transport header. */
    private fun setupTransportPort(response: RtspResponse, key: String): Int? {
        val transport = response["Transport"] ?: return null
        return transport.split(';')
            .firstOrNull { it.trim().startsWith("$key=") }
            ?.substringAfter('=')?.trim()?.toIntOrNull()
    }

    /**
     * Keeps AirTunes v1 receivers from timing the session out — pyatv sends the
     * same beat every 25 seconds once RECORD has gone out. A failure here is
     * not fatal; the receiver decides when to give up.
     */
    private fun startFeedbackLoop() {
        feedbackJob?.cancel()
        feedbackJob = scope.launch {
            while (isActive && client.isConnected) {
                delay(FEEDBACK_INTERVAL_MS)
                if (!isActive || !client.isConnected) break
                runCatching { client.send(SetupRequests.feedback()) }
                    .onFailure { Log.w(TAG, "RAOP feedback failed", it) }
            }
        }
    }

    override fun close() {
        feedbackJob?.cancel()
        scope.cancel()
        runCatching {
            if (client.isConnected && sessionUri.isNotEmpty()) {
                kotlinx.coroutines.runBlocking {
                    client.send(RtspRequest("TEARDOWN", sessionUri))
                }
            }
        }
        audioStream?.close()
        audioStream = null
        timingServer.close()
        client.close()
        Log.i(TAG, "RAOP session closed")
    }

    private companion object {
        const val TAG = "RaopSession"
        const val DIGEST_USERNAME = "iTunes"
        const val CONTENT_TYPE_OCTET_STREAM = "application/octet-stream"
        const val SAMPLE_RATE = 44100
        const val FRAMES_PER_PACKET = 352
        const val FEEDBACK_INTERVAL_MS = 25_000L
        const val AUTH_SETUP_FLAG = 0x01

        /** Static Curve25519 public key owntone ships for the auth-setup dance. */
        val AUTH_SETUP_PUBKEY = byteArrayOf(
            0x59.toByte(), 0x02.toByte(), 0xED.toByte(), 0xE9.toByte(),
            0x0D.toByte(), 0x4E.toByte(), 0xF2.toByte(), 0xBD.toByte(),
            0x4C.toByte(), 0xB6.toByte(), 0x8A.toByte(), 0x63.toByte(),
            0x30.toByte(), 0x03.toByte(), 0x82.toByte(), 0x07.toByte(),
            0xA9.toByte(), 0x4D.toByte(), 0xBD.toByte(), 0x50.toByte(),
            0xD8.toByte(), 0xAA.toByte(), 0x46.toByte(), 0x5B.toByte(),
            0x5D.toByte(), 0x8C.toByte(), 0x01.toByte(), 0x2A.toByte(),
            0x0C.toByte(), 0x7E.toByte(), 0x1D.toByte(), 0x4E.toByte(),
        )
    }
}

/** Builder sugar so the request construction reads like the protocol. */
class RtspRequestBuilder internal constructor(val method: String, val uri: String) {
    internal val headers = linkedMapOf<String, String>()
    internal var body: ByteArray? = null

    fun header(name: String, value: String) {
        headers[name] = value
    }

    fun body(value: ByteArray) {
        body = value
    }
}

/**
 * Sends the audio RTP stream to the receiver's `server_port`.
 *
 * Frames are 352-sample stereo L16 chunks (little-endian input, big-endian on
 * the wire per RFC 3551), AES-CBC encrypted with a per-packet IV reset — the
 * same rule the mirroring audio path uses, so [AudioCipher] is shared.
 *
 * Timing follows the pyatv sender: the media clock is anchored at the current
 * NTP time, wire timestamps start at [FIRST_TIMESTAMP] (the latency), and a
 * sync packet goes to the receiver's control port every second. AirPort Express
 * class hardware does not buffer — some do not play at all — without those
 * beats.
 */
class RaopAudioStream(
    private val address: InetAddress,
    private val port: Int,
    private val receiverControlPort: Int,
    controlSocket: DatagramSocket,
    private val cipher: AudioCipher,
) : Closeable {

    private val socket = DatagramSocket()
    private val seq = AtomicInteger(0)
    private val ssrc = SecureRandom().nextInt()

    /** Media-clock anchor: frames elapsed at [NTP_UNIX_OFFSET] as of stream start. */
    private val baseNtpSeconds: Long
    private val baseNtpFraction: Long

    private val framesSent = AtomicLong(0)

    @Volatile private var firstPacketSent = false

    @Volatile private var closed = false

    /** Bytes on the wire so far; the service layer turns this into a bitrate. */
    val bytesSent: Long get() = bytesCounter.get()
    private val bytesCounter = AtomicLong(0)

    private val controlThread = Thread({ controlLoop(controlSocket) }, "kite-raop-control").apply {
        isDaemon = true
        start()
    }
    private val syncThread = Thread({ syncLoop() }, "kite-raop-sync").apply {
        isDaemon = true
        start()
    }

    /** Ring of recent packets for retransmit requests. */
    private val recentPackets = ArrayDeque<ByteArray>()
    private val resendLock = Any()

    init {
        val millis = System.currentTimeMillis()
        baseNtpSeconds = millis / 1000 + NTP_UNIX_OFFSET_SECONDS
        baseNtpFraction = millis % 1000 * (1L shl 32) / 1000
    }

    /** Old AirPort Express builds want a run of empty packets before real audio. */
    fun prime() {
        repeat(PRIMING_PACKETS) {
            socket.send(DatagramPacket(ByteArray(0), 0, InetSocketAddress(address, port)))
        }
        Log.i(TAG, "Primed receiver with $PRIMING_PACKETS empty packets")
    }

    /**
     * Encrypts, frames and sends one [FRAME_BYTES]-byte stereo L16 frame. Input
     * is little-endian PCM as produced by AudioRecord.
     */
    fun send(frame: ByteArray) {
        require(frame.size == FRAME_BYTES) { "Expected $FRAME_BYTES bytes, got ${frame.size}" }

        val frames = framesSent.getAndIncrement()
        val timestamp = FIRST_TIMESTAMP + frames * SAMPLES_PER_FRAME

        val rtp = ByteArray(RTP_HEADER_BYTES + FRAME_BYTES)
        val s = seq.getAndIncrement() and 0xFFFF // RTP seq is 16-bit and wraps
        rtp[0] = 0x80.toByte()
        rtp[1] = (if (firstPacketSent) 0xE0 else 0x60).toByte() // marker on first packet
        firstPacketSent = true
        rtp[2] = (s ushr 8 and 0xFF).toByte()
        rtp[3] = (s and 0xFF).toByte()
        writeInt(rtp, 4, timestamp.toInt())
        writeInt(rtp, 8, ssrc)

        // L16 is big-endian on the wire; AudioRecord gives little-endian.
        for (i in 0 until FRAME_BYTES step 2) {
            rtp[RTP_HEADER_BYTES + i] = frame[i + 1]
            rtp[RTP_HEADER_BYTES + i + 1] = frame[i]
        }

        val payload = cipher.encrypt(rtp.copyOfRange(RTP_HEADER_BYTES, rtp.size))
        payload.copyInto(rtp, RTP_HEADER_BYTES)

        socket.send(DatagramPacket(rtp, rtp.size, InetSocketAddress(address, port)))
        bytesCounter.addAndGet(rtp.size.toLong())
        synchronized(resendLock) {
            recentPackets.addLast(rtp)
            if (recentPackets.size > RESEND_CACHE_SIZE) recentPackets.removeFirst()
        }
    }

    /**
     * Re-sends requested packets to wherever the request came from. Modern
     * receivers want each one wrapped in a four-byte `0x80 0xD6 <request seq>`
     * header (UxPlay reads resent packets at offset 4 of the datagram; pyatv
     * builds them the same way); the legacy 'R' flavour gets bare packets.
     *
     * Sync requests ('T' / 0xD4) are drained but not answered: audio-only
     * playback stays tolerable without them, and a wrong sync reply is worse
     * than none.
     */
    private fun controlLoop(controlSocket: DatagramSocket) {
        val buffer = ByteArray(CONTROL_PACKET_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!closed) {
            try {
                controlSocket.receive(packet)
            } catch (e: Exception) {
                if (!closed) Log.w(TAG, "Control receive failed", e)
                return
            }
            when {
                packet.length >= 6 && buffer[0].toInt().toChar() == 'R' -> {
                    val start = readShort(buffer, 2)
                    val count = readShort(buffer, 4)
                    resend(controlSocket, packet, null, start, count)
                }

                packet.length >= 8 && buffer[1].toInt() and 0x7F == RTP_RESEND_REQUEST -> {
                    val requestSeq = readShort(buffer, 2)
                    val start = readShort(buffer, 4)
                    val count = readShort(buffer, 6)
                    resend(controlSocket, packet, requestSeq, start, count)
                }
            }
        }
    }

    private fun resend(
        controlSocket: DatagramSocket,
        request: DatagramPacket,
        requestSeq: Int?,
        startSeq: Int,
        count: Int,
    ) {
        val snapshot = synchronized(resendLock) { recentPackets.toList() }
        var resent = 0
        for (cached in snapshot) {
            val cachedSeq = readShort(cached, 2)
            if (startSeq - cachedSeq in 0 until count) {
                val wire = if (requestSeq == null) {
                    cached
                } else {
                    ByteArray(RESEND_HEADER_BYTES + cached.size).also {
                        it[0] = 0x80.toByte()
                        it[1] = RESEND_REPLY_TYPE.toByte()
                        it[2] = (requestSeq ushr 8).toByte()
                        it[3] = requestSeq.toByte()
                        cached.copyInto(it, RESEND_HEADER_BYTES)
                    }
                }
                runCatching {
                    controlSocket.send(DatagramPacket(wire, wire.size, request.socketAddress))
                }.onSuccess { resent++ }
            }
        }
        if (resent > 0) Log.d(TAG, "Resent $resent of $count requested packets")
    }

    /** Sends the first sync packet immediately, then one every second. */
    private fun syncLoop() {
        var first = true
        while (!closed) {
            if (receiverControlPort > 0) sendSyncPacket(first)
            first = false
            try {
                Thread.sleep(SYNC_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    /**
     * Tells the receiver where the timeline is: NTP `[8:16)` belongs to RTP
     * timestamp `[4:8)`, and `[16:20)` is playing out now. The media clock runs
     * from the NTP anchor ([pyatv] `start_ts = ntp2ts(ntp_now())`); the wire
     * timestamp is the same stream expressed as latency-offset frames.
     */
    private fun sendSyncPacket(first: Boolean) {
        val mediaFrames = framesSent.get() * SAMPLES_PER_FRAME
        val wireTimestamp = FIRST_TIMESTAMP + mediaFrames

        var seconds = baseNtpSeconds + mediaFrames / SAMPLE_RATE
        var fraction = baseNtpFraction + mediaFrames % SAMPLE_RATE * (1L shl 32) / SAMPLE_RATE
        if (fraction >= 1L shl 32) {
            fraction -= 1L shl 32
            seconds += 1
        }

        val packet = ByteArray(SYNC_PACKET_BYTES)
        packet[0] = (if (first) 0x90 else 0x80).toByte()
        packet[1] = SYNC_PACKET_TYPE.toByte()
        packet[2] = 0x00
        packet[3] = 0x07
        writeInt(packet, 4, (wireTimestamp - LATENCY_FRAMES).toInt())
        writeInt(packet, 8, seconds.toInt())
        writeInt(packet, 12, fraction.toInt())
        writeInt(packet, 16, wireTimestamp.toInt())

        runCatching {
            socket.send(
                DatagramPacket(packet, packet.size, InetSocketAddress(address, receiverControlPort)),
            )
        }.onFailure { if (!closed) Log.w(TAG, "Sync packet failed", it) }
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun readShort(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 8) or (source[offset + 1].toInt() and 0xFF)

    override fun close() {
        closed = true
        runCatching { socket.close() }
        runCatching { controlThread.interrupt() }
        runCatching { syncThread.interrupt() }
    }

    companion object {
        private const val TAG = "RaopAudioStream"
        const val SAMPLES_PER_FRAME = 352L
        const val FRAME_BYTES = 352 * 2 * 2 // stereo, 16-bit
        const val RTP_HEADER_BYTES = 12
        const val PRIMING_PACKETS = 16
        const val CONTROL_PACKET_BYTES = 16
        const val RESEND_CACHE_SIZE = 2_000

        private const val SAMPLE_RATE = 44_100L

        /** pyatv's receiver-buffer default: 22050 + one second of frames. */
        const val LATENCY_FRAMES = 22_050L + SAMPLE_RATE

        /** Wire timestamps start at the latency, exactly like pyatv's do. */
        const val FIRST_TIMESTAMP = LATENCY_FRAMES
        private const val NTP_UNIX_OFFSET_SECONDS = 2_208_988_800L
        private const val SYNC_INTERVAL_MS = 1_000L
        private const val SYNC_PACKET_BYTES = 20
        private const val SYNC_PACKET_TYPE = 0xD4
        private const val RTP_RESEND_REQUEST = 0x55
        private const val RESEND_REPLY_TYPE = 0xD6
        private const val RESEND_HEADER_BYTES = 4
    }
}
