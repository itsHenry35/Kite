package com.henry.kite.airplay.session

import android.util.Log
import com.henry.kite.airplay.crypto.ChaChaPolyCipher
import com.henry.kite.airplay.crypto.HkdfSha512
import com.henry.kite.airplay.discovery.AirPlayReceiver
import com.henry.kite.airplay.pairing.PairSetup
import com.henry.kite.airplay.pairing.PairVerify
import com.henry.kite.airplay.pairing.PairingException
import com.henry.kite.airplay.pairing.SessionKeys
import com.henry.kite.airplay.plist.BinaryPlistReader
import com.henry.kite.airplay.plist.BinaryPlistWriter
import com.henry.kite.airplay.plist.PlistValue
import com.henry.kite.airplay.plist.asArray
import com.henry.kite.airplay.plist.asDict
import com.henry.kite.airplay.plist.asInt
import com.henry.kite.airplay.plist.plistDict
import com.henry.kite.airplay.rtsp.EncryptedStream
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
import java.net.Socket
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * AirPlay 2 audio session — the dialect HomePod, Apple TV and AirPlay 2
 * speakers speak, ported from pyatv's proven `airplayv2.py` sender flow
 * (reference sources in `D:\Programming\Projects\_pyatv-ref\raop`).
 *
 * No ANNOUNCE, no FairPlay: pair-verify turns the stored credentials (or a
 * one-time transient handshake) into session keys, the RTSP control channel
 * switches to ChaCha20-Poly1305 framing, and two plist SETUPs bring up the
 * event channel and the audio stream. Audio rides UDP as 352-sample L16
 * frames sealed per packet with ChaCha20-Poly1305, the 8-byte nonce counter
 * appended after the tag.
 *
 * Timing is the same NTP flavour as AirPlay 1 (`timingProtocol: "NTP"`),
 * pyatv's choice — no PTP anywhere in the open sender ecosystem.
 */
class AirPlay2Session(
    private val receiver: AirPlayReceiver,
    private val credentialStore: CredentialStore,
    /** The user's device name — receivers remember it for their AirPlay prompts. */
    private val clientName: String,
) : Closeable {

    private val client = RtspClient(receiver.address, receiver.port, clientName = clientName)
    private val timingServer = TimingServer()

    /** 32-bit stream identity: SETUP echoes it back as our RTP SSRC. */
    private val streamConnectionId = Random.nextLong(0, 1L shl 32)

    /** The audio SETUP gets its OWN connection id and URI, like the mirror flow. */
    private val audioConnectionId = Random.nextLong(0, 1L shl 32)
    private val sessionUuid = UUID.randomUUID().toString().uppercase()

    private var sessionUri: String = ""
    private var sessionHeader: String? = null
    private var eventSocket: Socket? = null
    private var eventDrain: Thread? = null
    private var control: AudioControlServer? = null

    /** Carries the 2-second feedback beat for as long as the session is up. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var feedbackJob: Job? = null

    var audioStream: AirPlay2AudioStream? = null
        private set

    /** Kept from authenticate so the sender identity derives per pairing. */
    private var activeCredentials: com.henry.kite.airplay.pairing.PairingCredentials? = null

    /**
     * Runs the handshake and returns the audio stream to pump PCM into.
     *
     * [pinProvider] is asked on screen if the receiver has never been paired
     * and refuses transient pairing; returning null aborts.
     */
    suspend fun connect(pinProvider: suspend () -> String?): AirPlay2AudioStream {
        // Reconnect-safe on the same instance after a rejected PIN.
        if (!client.isConnected) client.connect()

        val keys = authenticate(pinProvider)
        client.enableEncryption(keys)

        // macOS 400s SETUPs carrying the identity headers (the mirror flow
        // strips them for the same reason); pairing keeps them, setup does not.
        client.sendIdentityHeaders = false

        timingServer.start()
        val localAddress = client.localAddress?.hostAddress ?: "0.0.0.0"
        sessionUri = "rtsp://$localAddress/$streamConnectionId"

        val eventPort = setupBase(senderDeviceId())
        connectEventChannel(eventPort, keys.sharedSecret)

        val stream = setupAudioStream(keys.sharedSecret)
        audioStream = stream

        client.sendIdentityHeaders = true
        sendRecordAndFlush(stream)
        startFeedbackLoop()
        return stream
    }

    /**
     * Authenticates the control channel.
     *
     * Stored credentials mean pair-verify alone. Otherwise receivers
     * advertising system pairing (bits 43/48 — Apple TV, HomePod) take the
     * transient handshake, whose SRP secret is empty and whose credentials are
     * still stored for later pair-verify-only runs (doubletake does the same);
     * everything else goes straight to code pairing, whose credentials persist.
     */
    private suspend fun authenticate(pinProvider: suspend () -> String?): SessionKeys {
        val stored = runCatching { credentialStore.load(receiver.stableId) }.getOrNull()
        if (stored != null) {
            Log.i(TAG, "Pair-verify with stored credentials for ${receiver.name}")
            activeCredentials = stored
            return PairVerify(client, stored, stored.protocol).verify()
        }

        if (receiver.features.supportsSystemPairing || receiver.features.supportsCoreUtilsPairingAndEncryption) {
            TransientPairSetup(client).perform()?.let { result ->
                Log.i(TAG, "Transient pairing with ${receiver.name} succeeded")
                runCatching { credentialStore.save(receiver.stableId, result.credentials, receiver.name) }
                activeCredentials = result.credentials
                return result.keys
            }
            // Failed transient poisons the receiver's pairing state; restart
            // on a clean connection, pair-pin-start first — that request is
            // what makes the receiver display its code.
            Log.i(TAG, "Transient pairing unavailable; reconnecting for code pairing")
            runCatching { client.close() }
            client.connect()
            runCatching { client.send(RtspRequest("POST", "/pair-pin-start")) }
        }

        // Code is on the receiver's screen already; collect it, then exchange.
        val pin = pinProvider()
            ?: throw PairingException("Pairing cancelled before the PIN was entered")
        val setup = PairSetup(client)
        setup.begin()
        val credentials = setup.complete(pin.trim())
        val keys = PairVerify(client, credentials, credentials.protocol).verify()
        runCatching { credentialStore.save(receiver.stableId, credentials, receiver.name) }
        Log.i(TAG, "Paired with ${receiver.name}; credentials stored")
        return keys
    }

    /**
     * First SETUP: session identity and timing (pyatv `_setup_base`). The
     * reply names the event port to open. Declares a screen-mirroring session
     * — macOS only accepts the screen-audio stream descriptor inside one.
     */
    private suspend fun setupBase(senderId: String): Int {
        val body = plistDict {
            "deviceID" to senderId
            "sessionUUID" to sessionUuid
            "timingPort" to timingServer.port
            "timingProtocol" to "NTP"
            "isScreenMirroringSession" to true
            "isMultiSelectAirPlay" to true
            "groupContainsGroupLeader" to false
            "macAddress" to senderId
            "model" to SENDER_MODEL
            "name" to clientName
            "osBuildVersion" to SENDER_OS_BUILD
            "osName" to SENDER_OS_NAME
            "osVersion" to SENDER_OS_VERSION
            "senderSupportsRelay" to false
            "sourceVersion" to SENDER_SOURCE_VERSION
            "statsCollectionEnabled" to false
        }
        val response = client.send(plistSetup(body)).requireSuccess("SETUP (base)")
        sessionHeader = response["Session"]
        val root = BinaryPlistReader.read(response.body).asDict()
        val port = root?.get("eventPort").asInt() ?: 0
        Log.d(TAG, "SETUP base reply: eventPort=$port, keys=${root?.entries?.keys}")
        return port
    }

    /**
     * Stable per-pairing sender identity (locally-administered MAC form) from
     * the stored credentials. A CONSTANT id made macOS cache whatever name it
     * first saw for this sender — including "Kite" — forever.
     */
    private fun senderDeviceId(): String {
        val hex = (activeCredentials?.clientId ?: UUID.randomUUID().toString())
            .replace("-", "").lowercase().padEnd(12, '0').take(12)
        val first = (hex.substring(0, 2).toInt(16) and 0xFC) or 0x02
        return (listOf("%02x".format(first)) + (1 until 6).map { hex.substring(it * 2, it * 2 + 2) })
            .joinToString(":")
            .uppercase()
    }

    /**
     * Opens the encrypted event channel the receiver pushes status over.
     *
     * The keys are labelled from the receiver's side, so what we write is
     * derived with "Events-Read-Encryption-Key" (pyatv's deliberate reversal).
     * Some receivers open the listener only after replying, so the connect is
     * retried; events themselves are drained and ignored.
     */
    private fun connectEventChannel(port: Int, sharedSecret: ByteArray) {
        if (port <= 0) {
            Log.i(TAG, "Receiver offered no event channel; continuing without it")
            return
        }
        val keys = SessionKeys(
            sharedSecret = sharedSecret,
            writeKey = HkdfSha512.derive(sharedSecret, SALT_EVENTS, INFO_EVENTS_READ),
            readKey = HkdfSha512.derive(sharedSecret, SALT_EVENTS, INFO_EVENTS_WRITE),
        )
        var lastError: Exception? = null
        repeat(EVENT_CONNECT_RETRIES) { attempt ->
            try {
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(receiver.address, port), EVENT_CONNECT_TIMEOUT_MS)
                eventSocket = socket
                eventDrain = thread(name = "kite-ap2-events") {
                    drainEvents(socket, keys)
                }
                Log.i(TAG, "Event channel up on TCP $port")
                return
            } catch (e: Exception) {
                lastError = e
                Log.d(TAG, "Event channel connect attempt ${attempt + 1} failed: ${e.message}")
                runCatching { Thread.sleep(EVENT_CONNECT_RETRY_DELAY_MS) }
            }
        }
        // pyatv treats a dead event channel as fatal; in practice audio still
        // plays, so it is only logged here.
        Log.w(TAG, "Event channel unreachable after $EVENT_CONNECT_RETRIES attempts", lastError)
    }

    private fun drainEvents(socket: Socket, keys: SessionKeys) {
        try {
            val stream = EncryptedStream(socket.getInputStream(), socket.getOutputStream(), keys)
            while (stream.readFrame().isNotEmpty()) {
                // Events are pushed as encrypted frames; none matter for playback.
            }
        } catch (e: Exception) {
            if (eventSocket === socket && !socket.isClosed) {
                Log.d(TAG, "Event channel closed: ${e.message}")
            }
        }
    }

    /**
     * Second SETUP: the audio stream itself (pyatv `setup_audio_stream`).
     * `ct=1` with `spf=352`/`sr=44100` is how pyatv asks for raw L16 PCM;
     * `shk` is a derived key the receiver uses as the audio cipher key.
     */
    // Descriptor build + send + port parsing + volume as one straight-line
    // replay of the proven SETUP shape.
    @Suppress("LongMethod")
    private suspend fun setupAudioStream(sharedSecret: ByteArray): AirPlay2AudioStream {
        val shk = HkdfSha512.derive(sharedSecret, SALT_EVENTS, INFO_EVENTS_WRITE)
        val audioControl = AudioControlServer().also { it.start() }
        control = audioControl

        // The EXACT descriptor shape macOS accepted at 200 during mirroring:
        // screen-audio semantics (usingScreen=true, isMedia=false), ct=2 ALAC,
        // redundantAudio=2, streamConnections layout. The session now declares
        // isScreenMirroringSession=true to match.
        val body = plistDict {
            "streams" to PlistValue.Array(
                listOf(
                    plistDict {
                        "audioFormat" to AUDIO_FORMAT_ALAC
                        "audioMode" to "default"
                        "ct" to COMPRESSION_ALAC
                        "isMedia" to false
                        "latencyMax" to LATENCY_MAX
                        "latencyMin" to LATENCY_MIN
                        "redundantAudio" to REDUNDANT_AUDIO
                        "shk" to shk
                        "spf" to SAMPLES_PER_FRAME
                        "sr" to SAMPLE_RATE
                        "type" to STREAM_TYPE_AUDIO
                        "usingScreen" to true
                        "supportsDynamicStreamID" to true
                        "streamConnectionID" to audioConnectionId
                        "streamConnections" to plistDict {
                            "streamConnectionTypeRTP" to plistDict {
                                "streamConnectionKeyUseStreamEncryptionKey" to true
                            }
                            "streamConnectionTypeRTCP" to plistDict {
                                "streamConnectionKeyPort" to audioControl.port
                            }
                        }
                    },
                ),
            )
        }
        // The mirror flow gives every stream SETUP its own URI + connection id;
        // reusing the session URI/id for a second stream reads as a duplicate
        // to the receiver and draws a 400.
        val audioUri = "rtsp://${receiver.address.hostAddress}:${receiver.port}/$audioConnectionId"
        val response = client.send(
            RtspRequest(
                method = "SETUP",
                uri = audioUri,
                headers = mapOf("Content-Type" to CONTENT_TYPE_PLIST),
                body = BinaryPlistWriter.write(body),
            ),
        ).requireSuccess("SETUP (audio)")
        sessionHeader = response["Session"] ?: sessionHeader

        val root = BinaryPlistReader.read(response.body).asDict()
        val stream = root?.get("streams").asArray()?.firstOrNull().asDict()
        // Accept both reply shapes: modern receivers answer with
        // streamConnections.{RTP,RTCP}.streamConnectionKeyPort, older ones
        // with top-level dataPort/controlPort.
        val connections = stream?.get("streamConnections").asDict()
        val dataPort = connections?.get("streamConnectionTypeRTP").asDict()
            ?.get("streamConnectionKeyPort")?.asInt()
            ?: stream?.get("dataPort").asInt()
            ?: throw RtspException("AirPlay 2 SETUP reply carried no dataPort")
        val receiverControlPort = connections?.get("streamConnectionTypeRTCP").asDict()
            ?.get("streamConnectionKeyPort")?.asInt()
            ?: stream?.get("controlPort").asInt() ?: 0
        Log.i(TAG, "Audio stream up: data UDP $dataPort, receiver control $receiverControlPort")

        return AirPlay2AudioStream(
            address = receiver.address,
            port = dataPort,
            receiverControlPort = receiverControlPort,
            cipherKey = shk,
            ssrc = streamConnectionId.toInt(),
            control = audioControl,
        )
    }

    /** Caps the setup the way pyatv's send_audio does: RECORD, then FLUSH. */
    private suspend fun sendRecordAndFlush(stream: AirPlay2AudioStream) {
        client.send(RtspRequest("RECORD", sessionUri)).requireSuccess("RECORD")
        val flush = RtspRequest(
            method = "FLUSH",
            uri = sessionUri,
            headers = buildMap {
                put("Range", "npt=0-")
                sessionHeader?.let { put("Session", it) }
                put("RTP-Info", "seq=${stream.startingSequence};rtptime=${AirPlay2AudioStream.FIRST_TIMESTAMP}")
            },
        )
        runCatching { client.send(flush) }
            .onFailure { Log.w(TAG, "FLUSH rejected; continuing", it) }
    }

    private fun startFeedbackLoop() {
        feedbackJob?.cancel()
        feedbackJob = scope.launch {
            while (isActive && client.isConnected) {
                delay(FEEDBACK_INTERVAL_MS)
                if (!isActive || !client.isConnected) break
                runCatching { client.send(SetupRequests.feedback()) }
                    .onFailure { Log.w(TAG, "AirPlay 2 feedback failed", it) }
            }
        }
    }

    private fun plistSetup(body: PlistValue.Dict) = RtspRequest(
        method = "SETUP",
        uri = sessionUri,
        headers = mapOf("Content-Type" to CONTENT_TYPE_PLIST),
        body = BinaryPlistWriter.write(body),
    )

    private fun RtspResponse.requireSuccess(what: String): RtspResponse {
        if (!isSuccess) throw RtspException("$what failed: $statusCode $reason", statusCode)
        return this
    }

    override fun close() {
        feedbackJob?.cancel()
        scope.cancel()
        runCatching {
            if (client.isConnected && sessionUri.isNotEmpty()) {
                kotlinx.coroutines.runBlocking {
                    val teardown = sessionHeader?.let {
                        RtspRequest("TEARDOWN", sessionUri, mapOf("Session" to it))
                    } ?: RtspRequest("TEARDOWN", sessionUri)
                    client.send(teardown)
                }
            }
        }
        audioStream?.close()
        audioStream = null
        runCatching { control?.close() }
        control = null
        eventDrain?.interrupt()
        eventDrain = null
        runCatching { eventSocket?.close() }
        eventSocket = null
        timingServer.close()
        client.close()
        Log.i(TAG, "AirPlay 2 session closed")
    }

    private companion object {
        const val TAG = "AirPlay2Session"
        const val CONTENT_TYPE_PLIST = "application/x-apple-binary-plist"

        const val METHOD_PAIR_SETUP = 0x00
        const val STATE_M1 = 0x01
        const val STATE_M2 = 0x02
        const val STATE_M3 = 0x03
        const val STATE_M4 = 0x04

        /** kTLVType_Flags value that asks for transient (PIN-free) pairing. */
        const val FLAG_TRANSIENT = 0x10

        /** The fixed transient PIN the receivers themselves accept. */
        const val TRANSIENT_PIN = "3939"

        const val SALT_CONTROL = "Control-Salt"
        const val INFO_WRITE = "Control-Write-Encryption-Key"
        const val INFO_READ = "Control-Read-Encryption-Key"
        const val SALT_EVENTS = "Events-Salt"
        const val INFO_EVENTS_WRITE = "Events-Write-Encryption-Key"
        const val INFO_EVENTS_READ = "Events-Read-Encryption-Key"

        const val FEEDBACK_INTERVAL_MS = 2_000L
        const val EVENT_CONNECT_RETRIES = 5
        const val EVENT_CONNECT_TIMEOUT_MS = 2_000
        const val EVENT_CONNECT_RETRY_DELAY_MS = 1_000L

        // Stream parameters. Codec/latency values follow the screen-audio
        // descriptor macOS accepts at 200 (ct=1 PCM was rejected at 400).
        const val AUDIO_FORMAT_ALAC = 1L shl 18
        const val COMPRESSION_ALAC = 2
        const val REDUNDANT_AUDIO = 2
        const val STREAM_TYPE_AUDIO = 0x60
        const val SAMPLES_PER_FRAME = 352
        const val SAMPLE_RATE = 44_100
        const val LATENCY_MIN = 0
        const val LATENCY_MAX = 3748

        // Identity pyatv spoofs and receivers accept.
        const val SENDER_DEVICE_ID = "AA:BB:CC:DD:EE:FF"
        const val SENDER_MODEL = "iPhone14,3"
        const val SENDER_OS_NAME = "iPhone OS"
        const val SENDER_OS_VERSION = "16.5"
        const val SENDER_OS_BUILD = "20F66"
        const val SENDER_SOURCE_VERSION = "690.7.1"
    }
}

/**
 * The UDP socket that carries AirPlay 2 audio.
 *
 * Wire format per pyatv's `send_audio_packet`: a 12-byte RTP header (SSRC is
 * the session's `streamConnectionID`), the L16 frame sealed with
 * ChaCha20-Poly1305 under the `shk` key (AAD = header bytes 4..12, nonce =
 * four zero bytes + 8-byte little-endian counter), then the 8 nonce counter
 * bytes appended after the tag.
 *
 * The one-second sync beats on the receiver's control port use the same
 * packet layout as the AirPlay 1 path; without them AirPort-class hardware
 * never starts playout.
 */
class AirPlay2AudioStream(
    private val address: InetAddress,
    private val port: Int,
    private val receiverControlPort: Int,
    cipherKey: ByteArray,
    private val ssrc: Int,
    control: AudioControlServer,
) : Closeable {

    private val socket = DatagramSocket()
    private val cipherKey = cipherKey.copyOf()
    private val seq = AtomicInteger(Random.nextInt(SEQ_SPACE))

    /** The randomized sequence the stream starts at, echoed in FLUSH's RTP-Info. */
    val startingSequence: Int = seq.get()
    private val outCounter = AtomicLong(0)
    private val framesSent = AtomicLong(0)

    private val baseNtpSeconds: Long
    private val baseNtpFraction: Long

    @Volatile private var closed = false

    /** Bytes on the wire so far; the service layer turns this into a bitrate. */
    val bytesSent: Long get() = bytesCounter.get()
    private val bytesCounter = AtomicLong(0)

    private val syncThread = thread(name = "kite-ap2-sync") { syncLoop() }

    /** Wire packets by sequence number, for answering retransmit requests. */
    private val resendCache = ArrayDeque<ByteArray>()
    private val resendLock = Any()

    init {
        control.onResend = { startSeq, count -> resend(startSeq, count) }
        val millis = System.currentTimeMillis()
        baseNtpSeconds = millis / 1000 + NTP_UNIX_OFFSET_SECONDS
        baseNtpFraction = millis % 1000 * (1L shl 32) / 1000
        Log.i(TAG, "AirPlay 2 audio ready: %s:%d, ssrc=%08x".format(address.hostAddress, port, ssrc))
    }

    /**
     * Encrypts, frames and sends one [FRAME_BYTES]-byte stereo L16 frame.
     * Input is little-endian PCM as produced by AudioRecord.
     */
    fun send(frame: ByteArray) {
        require(frame.size == FRAME_BYTES) { "Expected $FRAME_BYTES bytes, got ${frame.size}" }

        val frames = framesSent.getAndIncrement()
        val timestamp = FIRST_TIMESTAMP + frames * SAMPLES_PER_FRAME
        val s = seq.getAndIncrement() and 0xFFFF
        val counter = outCounter.getAndIncrement()

        val header = ByteArray(RTP_HEADER_BYTES)
        header[0] = 0x80.toByte()
        header[1] = 0x60.toByte()
        header[2] = (s ushr 8).toByte()
        header[3] = s.toByte()
        writeInt(header, 4, timestamp.toInt())
        writeInt(header, 8, ssrc)

        // L16 is big-endian on the wire; AudioRecord gives little-endian.
        val payload = ByteArray(frame.size)
        for (i in 0 until frame.size step 2) {
            payload[i] = frame[i + 1]
            payload[i + 1] = frame[i]
        }

        val sealed = ChaChaPolyCipher.seal(
            key = cipherKey,
            nonce = ChaChaPolyCipher.nonce(counter),
            plaintext = payload,
            aad = header.copyOfRange(4, RTP_HEADER_BYTES),
        )

        val packet = ByteArray(RTP_HEADER_BYTES + sealed.size + NONCE_TAIL_BYTES)
        header.copyInto(packet)
        sealed.copyInto(packet, RTP_HEADER_BYTES)
        writeLongLE(packet, RTP_HEADER_BYTES + sealed.size, counter)

        socket.send(DatagramPacket(packet, packet.size, InetSocketAddress(address, port)))
        bytesCounter.addAndGet(packet.size.toLong())
        synchronized(resendLock) {
            resendCache.addLast(packet)
            if (resendCache.size > RESEND_CACHE_SIZE) resendCache.removeFirst()
        }
    }

    /** Collects cached wire packets for the receiver's retransmit requests. */
    private fun resend(startSeq: Int, count: Int): List<ByteArray> {
        val found = mutableListOf<ByteArray>()
        val snapshot = synchronized(resendLock) { resendCache.toList() }
        for (cached in snapshot) {
            val cachedSeq = ((cached[2].toInt() and 0xFF) shl 8) or (cached[3].toInt() and 0xFF)
            if (startSeq - cachedSeq in 0 until count) found.add(cached)
        }
        return found
    }

    /** First beat immediately, then one per second on the receiver's control port. */
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

    /** Same 20-byte layout as the AirPlay 1 sync beat (pyatv `SyncPacket`). */
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
        packet[3] = 0x07
        writeInt(packet, 4, (wireTimestamp - LATENCY_FRAMES).toInt())
        writeInt(packet, 8, seconds.toInt())
        writeInt(packet, 12, fraction.toInt())
        writeInt(packet, 16, wireTimestamp.toInt())

        runCatching {
            socket.send(DatagramPacket(packet, packet.size, InetSocketAddress(address, receiverControlPort)))
        }.onFailure { if (!closed) Log.w(TAG, "Sync packet failed", it) }
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun writeLongLE(target: ByteArray, offset: Int, value: Long) {
        var v = value
        for (i in 0 until 8) {
            target[offset + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        runCatching { syncThread.interrupt() }
    }

    companion object {
        private const val TAG = "AirPlay2AudioStream"
        private const val SAMPLE_RATE = 44_100L
        const val SAMPLES_PER_FRAME = 352L
        const val FRAME_BYTES = 352 * 2 * 2 // stereo, 16-bit
        const val RTP_HEADER_BYTES = 12
        const val RESEND_CACHE_SIZE = 2_000

        /** Wire timestamps start at the receiver-buffer latency, like pyatv's. */
        const val FIRST_TIMESTAMP = 22_050L + SAMPLE_RATE

        private const val LATENCY_FRAMES = FIRST_TIMESTAMP
        private const val NTP_UNIX_OFFSET_SECONDS = 2_208_988_800L
        private const val SYNC_INTERVAL_MS = 1_000L
        private const val SYNC_PACKET_BYTES = 20
        private const val SYNC_PACKET_TYPE = 0xD4
        private const val NONCE_TAIL_BYTES = 8
        private const val SEQ_SPACE = 0x10000
    }
}
