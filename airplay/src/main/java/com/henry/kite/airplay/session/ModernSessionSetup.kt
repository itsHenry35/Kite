package com.henry.kite.airplay.session

import android.util.Log
import com.henry.kite.airplay.discovery.AirPlayReceiver
import com.henry.kite.airplay.pairing.PairingCredentials
import com.henry.kite.airplay.plist.BinaryPlistReader
import com.henry.kite.airplay.plist.asArray
import com.henry.kite.airplay.plist.asBool
import com.henry.kite.airplay.plist.asDict
import com.henry.kite.airplay.plist.asInt
import com.henry.kite.airplay.plist.asLong
import com.henry.kite.airplay.rtsp.RtspClient
import com.henry.kite.airplay.rtsp.RtspException
import com.henry.kite.airplay.rtsp.RtspResponse
import java.util.UUID
import kotlin.random.Random

/**
 * Descriptor-only SETUP sequence for PD-paired (macOS class) receivers,
 * following doubletake's modern flow: the control SETUP creates the session
 * without root ekey/eiv, the video SETUP carries the mixed key inside the
 * stream descriptor, and identity headers stay off the wire.
 */
internal class ModernSessionSetup(
    private val client: RtspClient,
    private val receiver: AirPlayReceiver,
    private val sessionUuid: String,
    private val clientName: String,
    private val clock: MirrorClock,
) {

    class SetupResult(
        /** Null for sound-only sessions: no video SETUP, no data channel. */
        val stream: MirrorStream?,
        val skipRecord: Boolean,
        val controlUri: String,
        /** The audio SETUP's URI — RECORD and SET_PARAMETER share it (doubletake). */
        val audioUri: String,
        val audio: ModernAudioStream,
    )

    var eventChannel: EventChannel? = null
        private set

    /** The audio SETUP's URI — RECORD and SET_PARAMETER share it. */
    var audioUri: String = ""
        private set

    private class ControlResult(
        val response: RtspResponse,
        val timing: String,
        val skipRecord: Boolean,
        val controlUri: String,
        val connectionId: Long,
    )

    suspend fun setup(
        pairing: PairingCredentials,
        fairPlay: FairPlayKeys,
        mixedKey: ByteArray,
        timingPort: Int,
        sharedSecret: ByteArray,
        videoEnabled: Boolean = true,
    ): SetupResult {
        val control = sendControlSetup(pairing, timingPort)
        configureMediaClock(control.response, control.timing)

        val hostAddress = receiver.address.hostAddress
        val port = receiver.port
        val eventPort = runCatching {
            BinaryPlistReader.read(control.response.body).asDict()
                ?.get("eventPort")?.asInt()
        }.getOrNull() ?: 0
        if (eventPort > 0) {
            eventChannel = EventChannel.connect(receiver.address, eventPort, sharedSecret, clock)
        } else {
            Log.i(TAG, "Receiver published no eventPort; continuing without the event channel")
        }

        // Video is phase 3 of the modern session; audio (phase 2) is a valid
        // stopping point — sound-only sessions end here with no video SETUP.
        var stream: MirrorStream? = null
        if (videoEnabled) {
            val videoConnectionId = Random.nextLong(1, Long.MAX_VALUE)
            val videoUri = "rtsp://$hostAddress:$port/$videoConnectionId"
            val streamResponse = client.send(
                ModernSetupRequests.modernVideoSetup(
                    streamConnectionId = videoConnectionId,
                    shk = mixedKey,
                    shiv = fairPlay.eiv,
                    uri = videoUri,
                ),
            ).requireSuccess("SETUP (stream)")

            val dataPort = dataPort(streamResponse)
                ?: throw RtspException("SETUP reply carried no dataPort; cannot open the video channel")
            Log.i(TAG, "Resolved dataPort = $dataPort")
            stream = MirrorStream(
                address = receiver.address,
                port = dataPort,
                cipher = null,
                chacha = ChaChaFrameCipher(sharedSecret, videoConnectionId),
                timelineId = clock.ptpTimelineId(),
                modern = true,
                clock = clock,
            )
        }

        val audio = announceAudio(client)

        client.sendIdentityHeaders = true

        return SetupResult(stream, control.skipRecord, control.controlUri, audioUri, audio)
    }

    /**
     * The macOS session pipeline advances past the first video frame only once
     * the screen-audio stream exists (doubletake always creates it in phase 2,
     * even for silent sessions). Announce it with a random ChaCha stream key,
     * send the initial TimeAnnounce, and keep the sockets for real audio.
     */
    private suspend fun announceAudio(client: RtspClient): ModernAudioStream {
        val audioShk = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val audioControl = java.net.DatagramSocket().apply { reuseAddress = true }
        val audioData = java.net.DatagramSocket()
        // doubletake gives the audio stream its own connection id and shares it
        // between the SETUP URI path and the descriptor's streamConnectionID.
        // RECORD and SET_PARAMETER also share this URI (doubletake sends them
        // all to audioURI) — a volume sent to a different URI than the audio
        // stream's SETUP leaves the stream at whatever gain the receiver
        // defaulted to.
        val audioConnectionId = kotlin.random.Random.nextLong(1, Long.MAX_VALUE)
        audioUri = "rtsp://${receiver.address.hostAddress}:${receiver.port}/$audioConnectionId"
        val audioResponse = client.send(
            ModernSetupRequests.modernAudioSetup(
                streamConnectionId = audioConnectionId,
                shk = audioShk,
                controlPort = audioControl.localPort,
                uri = audioUri,
            ),
        ).requireSuccess("SETUP (audio)")

        val audioPorts = audioPorts(audioResponse)
        val sender = ModernAudioStream(
            remoteAddress = receiver.address,
            remoteDataPort = audioPorts.dataPort,
            remoteControlPort = audioPorts.controlPort,
            dataSocket = audioData,
            controlSocket = audioControl,
            streamKey = audioShk,
            clock = clock,
            timelineId = clock.ptpTimelineId(),
        )
        sender.start()

        // Real senders set the receiver volume after the audio SETUP — twice
        // in captured pcaps, on the audio URI itself. Without this the Mac
        // may start the stream muted.
        repeat(2) {
            client.send(ModernSetupRequests.modernVolume(audioUri))
                .requireSuccess("SET_PARAMETER (volume)")
        }
        Log.i(
            TAG,
            "Screen-audio stream ready: data=" + audioPorts.dataPort + " ctrl=" + audioPorts.controlPort,
        )
        return sender
    }

    /**
     * Sends the control SETUP, trying PTP first and NTP when the receiver
     * refuses. Identity headers stay off: macOS rejects the session form with
     * them present (doubletake sends none).
     */
    private suspend fun sendControlSetup(pairing: PairingCredentials, timingPort: Int): ControlResult {
        client.sendIdentityHeaders = false

        val hostAddress = receiver.address.hostAddress
        val port = receiver.port
        // Stable per-pairing identity: derived from the Ed25519 public key the
        // receiver recorded at pair-setup, so every session on this pairing
        // advertises the same deviceID (like a real sender's WLAN MAC).
        val senderId = senderDeviceId(pairing.identity().publicKey.toHex())

        val controlConnectionId = Random.nextLong(1, Long.MAX_VALUE)
        val controlUri = "rtsp://$hostAddress:$port/$controlConnectionId"
        var controlResponse: RtspResponse? = null
        var controlTiming = ""
        var skipRecord = false
        for (timingProtocol in listOf("PTP", "NTP")) {
            val attempt = client.send(
                ModernSetupRequests.modernSessionSetup(
                    deviceId = senderId,
                    sessionUuid = sessionUuid,
                    clientName = clientName,
                    timingPeerId = UUID.randomUUID().toString(),
                    localAddress = client.localAddress?.hostAddress ?: hostAddress,
                    timingProtocol = timingProtocol,
                    timingPort = timingPort,
                    uri = controlUri,
                ),
            )
            if (attempt.isSuccess) {
                controlResponse = attempt
                controlTiming = timingProtocol
                Log.i(TAG, "Control SETUP accepted with $timingProtocol timing")
                break
            }
            Log.w(
                TAG,
                "Control SETUP rejected with $timingProtocol timing " +
                    "(${attempt.statusCode}); body=${attempt.body.toHex()}",
            )
        }
        val finalControl = controlResponse
            ?: throw RtspException("Receiver rejected control SETUP with both PTP and NTP timing", 400)
        skipRecord = runCatching {
            BinaryPlistReader.read(finalControl.body).asDict()
                ?.get("skipRecord")?.asBool()
        }.getOrNull() ?: false
        return ControlResult(finalControl, controlTiming, skipRecord, controlUri, controlConnectionId)
    }

    /**
     * Anchors the receiver's PTP timeline (doubletake configureFromSetup):
     * ClockID from the response's timingPeerInfo, and the receiver's clock at
     * received+processing from the private timestamp headers. NTP sessions
     * stay on the local clock.
     */
    private fun configureMediaClock(response: RtspResponse, timingProtocol: String) {
        val receivedMs = response["X-Apple-RequestReceivedTimestamp"]?.toLongOrNull()
        val processingMs = response["X-Apple-ProcessingTime"]?.toLongOrNull()
        val clockId = runCatching {
            BinaryPlistReader.read(response.body).asDict()
                ?.get("timingPeerInfo").asDict()?.get("ClockID")?.asLong()
        }.getOrNull()
        val clockUsable = timingProtocol == "PTP" && clockId != null && clockId != 0L
        val headerTimesUsable = receivedMs != null && processingMs != null
        if (!(clockUsable && headerTimesUsable)) {
            Log.w(TAG, "Control SETUP omitted PTP clock info; staying on the local clock")
            return
        }
        val anchorMs = receivedMs + processingMs
        val seconds = anchorMs / 1_000
        val fraction = (anchorMs % 1_000) * 4_294_967_296L / 1_000
        clock.configurePtpAnchor((seconds shl 32) or (fraction and 0xFFFFFFFFL), clockId, System.nanoTime())
        Log.i(TAG, "PTP anchor set: timeline=0x%016x anchor=%dms".format(clockId, anchorMs))
    }

    private fun dataPort(response: RtspResponse): Int? {
        val plist = runCatching { BinaryPlistReader.read(response.body) }.getOrNull() ?: return null
        val stream = plist.asDict()?.get("streams").asArray()?.firstOrNull().asDict() ?: return null
        return stream["dataPort"].asInt()
    }

    /** Receiver-side audio ports from the audio SETUP response's streamConnections. */
    private fun audioPorts(response: RtspResponse): AudioPorts {
        val plist = runCatching { BinaryPlistReader.read(response.body) }.getOrNull()
        val stream = plist?.asDict()?.get("streams").asArray()?.firstOrNull().asDict()
        val connections = stream?.get("streamConnections").asDict()
        val rtp = connections?.get("streamConnectionTypeRTP").asDict()
        val rtcp = connections?.get("streamConnectionTypeRTCP").asDict()
        return AudioPorts(
            dataPort = rtp?.get("streamConnectionKeyPort")?.asInt()
                ?: throw RtspException("Audio SETUP reply carried no RTP dataPort"),
            controlPort = rtcp?.get("streamConnectionKeyPort")?.asInt()
                ?: throw RtspException("Audio SETUP reply carried no RTCP controlPort"),
        )
    }

    private class AudioPorts(val dataPort: Int, val controlPort: Int)

    /** Converts a UUID to a stable locally-administered MAC form (doubletake). */
    private fun senderDeviceId(id: String): String {
        val hex = id.lowercase().replace("-", "").removePrefix("0x")
        if (hex.length < 12) return "02:00:00:00:00:01"
        // 02 prefix = locally administered, unicast.
        val first = (hex.substring(0, 2).toInt(16) and 0xFC) or 0x02
        return (listOf("%02x".format(first)) + (1 until 6).map { hex.substring(it * 2, it * 2 + 2) })
            .joinToString(":")
    }

    private fun RtspResponse.requireSuccess(what: String): RtspResponse {
        if (!isSuccess) throw RtspException("$what failed: $statusCode $reason", statusCode)
        return this
    }

    private companion object {
        const val TAG = "ModernSessionSetup"
    }
}
