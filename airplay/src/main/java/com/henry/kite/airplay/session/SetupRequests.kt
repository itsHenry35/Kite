package com.henry.kite.airplay.session

import com.henry.kite.airplay.plist.BinaryPlistWriter
import com.henry.kite.airplay.plist.PlistValue
import com.henry.kite.airplay.plist.plistDict
import com.henry.kite.airplay.rtsp.RtspRequest

/**
 * Builders for the RTSP bodies that open a mirroring session.
 *
 * Keys are the ones receivers read; RPiPlay and UxPlay parse `streams`,
 * `timingPort`, `isMedia`, `latencyMin` and `latencyMax` out of these plists.
 */
object SetupRequests {
    const val CONTENT_TYPE_PLIST = "application/x-apple-binary-plist"

    /** Stream type 110 is mirrored video; 96 is buffered audio. */
    const val STREAM_TYPE_VIDEO = 110
    const val STREAM_TYPE_AUDIO = 96

    /**
     * First SETUP: announces the session, our timing port, and the stream keys.
     *
     * The receiver only initializes its mirror pipeline when this body carries
     * BOTH `eiv` and `ekey` as DATA; without them UxPlay/RPiPlay respond to the
     * later stream SETUP with "Mirroring not initialized at SETUP".
     *
     * `ekey` is a FairPlay-wrapped AES key the receiver unwraps with the keymsg
     * we sent during `/fp-setup`. The sender computes the same key via
     * (legacy path) and feeds it to [StreamCipher.forAudioKey].
     */
    fun sessionSetup(
        deviceId: String,
        sessionUuid: String,
        clientName: String,
        timingPort: Int,
        eiv: ByteArray,
        ekey: ByteArray,
    ): RtspRequest {
        val body = plistDict {
            "deviceID" to deviceId
            "sessionUUID" to sessionUuid
            "name" to clientName
            "model" to CLIENT_MODEL
            "sourceVersion" to SOURCE_VERSION
            "macAddress" to deviceId
            "osName" to "Android"
            "osVersion" to osVersion()
            "timingProtocol" to "NTP" // UxPlay requires NTP for mirroring.
            "timingPort" to timingPort
            "isScreenMirroringSession" to true
            "senderSupportsRelay" to false
            "statusFlags" to STATUS_FLAGS
            "eiv" to eiv
            "ekey" to ekey
        }
        return RtspRequest(
            method = "SETUP",
            uri = "rtsp://$deviceId/$sessionUuid",
            headers = mapOf("Content-Type" to CONTENT_TYPE_PLIST),
            body = BinaryPlistWriter.write(body),
        )
    }

    /**
     * Second SETUP: asks for the video stream itself. The reply carries the
     * `dataPort` that [MirrorStream] connects to.
     *
     * `streamConnectionID` is echoed into the stream key derivation, so the same
     * value has to reach [StreamCipher.forAudioKey].
     */
    fun videoStreamSetup(
        deviceId: String,
        sessionUuid: String,
        streamConnectionId: Long,
        width: Int,
        height: Int,
        frameRate: Int,
        latencyMs: Int = DEFAULT_LATENCY_MS,
    ): RtspRequest {
        val stream = plistDict {
            "type" to STREAM_TYPE_VIDEO
            "streamConnectionID" to streamConnectionId
            "latencyMs" to latencyMs
            "timestampInfo" to PlistValue.Array(
                listOf(
                    plistDict {
                        "name" to "SampleNtpTime"
                        "networkTimeTimelineID" to 0
                    },
                ),
            )
            "width" to width
            "height" to height
            "refreshRate" to (1.0 / frameRate)
        }
        val body = plistDict {
            "streams" to PlistValue.Array(listOf(stream))
        }
        return RtspRequest(
            method = "SETUP",
            uri = "rtsp://$deviceId/$sessionUuid",
            headers = mapOf("Content-Type" to CONTENT_TYPE_PLIST),
            body = BinaryPlistWriter.write(body),
        )
    }

    /**
     * Third SETUP: asks for the audio stream. The reply carries the UDP
     * `dataPort` [AudioStream] sends to.
     *
     * The legacy stream is ALAC (ct=2, 352-sample frames): receivers validate
     * each AAC-ELD frame against the header Apple's encoder emits and drop
     * everything else, while ALAC frames carry the standard channel-pair
     * element every receiver accepts. `controlPort` must be non-zero or the
     * receiver never asks for a resend. `isMedia` false with `usingScreen`
     * true is what a mirroring session looks like, as opposed to AirPlay audio
     * streaming.
     */
    fun audioStreamSetup(
        deviceId: String,
        sessionUuid: String,
        streamConnectionId: Long,
        controlPort: Int,
        latencyMs: Int = DEFAULT_LATENCY_MS,
    ): RtspRequest {
        val stream = plistDict {
            "type" to STREAM_TYPE_AUDIO
            "ct" to COMPRESSION_ALAC
            "spf" to SAMPLES_PER_ALAC_FRAME
            "audioFormat" to AUDIO_FORMAT_ALAC_44100_2
            "controlPort" to controlPort
            "streamConnectionID" to streamConnectionId
            "latencyMs" to latencyMs
            "isMedia" to false
            "usingScreen" to true
        }
        val body = plistDict {
            "streams" to PlistValue.Array(listOf(stream))
        }
        return RtspRequest(
            method = "SETUP",
            uri = "rtsp://$deviceId/$sessionUuid",
            headers = mapOf("Content-Type" to CONTENT_TYPE_PLIST),
            body = BinaryPlistWriter.write(body),
        )
    }

    /** RECORD starts the flow. Sent once both SETUPs have succeeded. */
    fun record(deviceId: String, sessionUuid: String): RtspRequest = RtspRequest(
        method = "RECORD",
        uri = "rtsp://$deviceId/$sessionUuid",
        headers = mapOf(
            "Range" to "npt=0-",
            "Session" to sessionUuid,
            "RTP-Info" to "seq=0;rtptime=0",
        ),
    )

    /** Advertises what the sender can do; some receivers require it before SETUP. */
    fun senderInfo(deviceId: String, sessionUuid: String): RtspRequest {
        val body = plistDict {
            "deviceID" to deviceId
            "sessionUUID" to sessionUuid
            "model" to CLIENT_MODEL
            "osName" to "Android"
            "osVersion" to osVersion()
            "sourceVersion" to SOURCE_VERSION
            "supportsScreenMirroring" to true
        }
        return RtspRequest(
            method = "POST",
            uri = "/audioMode", // Receivers accept sender info on this path.
            headers = mapOf("Content-Type" to CONTENT_TYPE_PLIST),
            body = BinaryPlistWriter.write(body),
        )
    }

    fun teardown(deviceId: String, sessionUuid: String): RtspRequest = RtspRequest(
        method = "TEARDOWN",
        uri = "rtsp://$deviceId/$sessionUuid",
        headers = mapOf("Session" to sessionUuid),
    )

    /**
     * The client "heartbeat". UxPlay expects one every two seconds once RECORD
     * has been sent and drops the connection after 15 seconds without one
     * ("client may be offline"). The receiver only registers its arrival — no
     * body is sent and none comes back.
     */
    fun feedback(): RtspRequest = RtspRequest(method = "POST", uri = "/feedback")

    /** GET /info returns the receiver's own capability plist. */
    fun info(): RtspRequest = RtspRequest(method = "GET", uri = "/info")

    fun options(): RtspRequest = RtspRequest(method = "OPTIONS", uri = "*")

    /**
     * Compression type 2 = ALAC for the legacy stream. These are the AirPlay
     * codec ids every receiver dispatches on (1=PCM, 2=ALAC, 4=AAC-LC,
     * 8=AAC-ELD) — the same ids doubletake's modern SETUP uses for AAC-ELD.
     */
    private const val COMPRESSION_ALAC = 2

    /** Samples per ALAC frame, and the RTP timestamp step. */
    private const val SAMPLES_PER_ALAC_FRAME = 352

    /**
     * `audioFormat` bit for ALAC 44.1 kHz stereo (bit 18, doubletake
     * `AudioCodecALAC` audioFormat 0x40000).
     */
    private const val AUDIO_FORMAT_ALAC_44100_2 = 1L shl 18

    private const val CLIENT_MODEL = "Kite1,1"
    private const val SOURCE_VERSION = "770.8.1"

    /**
     * Test seam: `android.os.Build` is unreachable in JVM unit tests, so tests
     * pin the value here; production reads the real OS release.
     */
    internal var osVersionOverride: String? = null

    private fun osVersion(): String = osVersionOverride ?: android.os.Build.VERSION.RELEASE

    /** doubletake modernAirPlaySourceVersion — CoreUtils-class senders. */
    const val MODERN_AIRPLAY_SOURCE_VERSION = "980.71.1"
    internal const val DEFAULT_LATENCY_MS = 100

    /** Bit 2: device is not sleeping and can accept a session right now. */
    private const val STATUS_FLAGS = 0x04
}

/**
 * SETUP bodies for PD-paired (CoreUtils/HAP-encrypted) sessions — macOS class.
 * The shapes follow doubletake's descriptor-only flow: the control SETUP
 * creates the session with no root ekey/eiv, and each stream descriptor
 * carries its own key material.
 */
object ModernSetupRequests {
    const val CONTENT_TYPE_PLIST = SetupRequests.CONTENT_TYPE_PLIST

    private const val STREAM_TYPE_VIDEO = SetupRequests.STREAM_TYPE_VIDEO
    private const val STREAM_TYPE_AUDIO = SetupRequests.STREAM_TYPE_AUDIO
    private const val DEFAULT_LATENCY_MS = SetupRequests.DEFAULT_LATENCY_MS

    /**
     * Modern control SETUP (doubletake `controlPlist`, descriptor-only form).
     *
     * A HAP-encrypted session must NOT carry root ekey/eiv — the stream
     * descriptors reference the keys instead, and macOS answers 400 when the
     * legacy root fields appear. `combinedGetInfoWithControlSetup` makes the
     * receiver return display metadata under the response's `info` key, and
     * `updateSessionRequest=false` marks this as session creation.
     */
    fun modernSessionSetup(
        deviceId: String,
        sessionUuid: String,
        clientName: String,
        timingPeerId: String,
        localAddress: String,
        timingProtocol: String,
        timingPort: Int,
        uri: String,
    ): RtspRequest {
        val timingPeer = plistDict {
            "ID" to timingPeerId
            "SupportsClockPortMatchingOverride" to true
            "DeviceType" to 0
            "Addresses" to PlistValue.Array(listOf(PlistValue.Str(localAddress)))
        }
        val body = plistDict {
            "deviceID" to deviceId
            "macAddress" to deviceId
            "sessionUUID" to sessionUuid
            "sourceVersion" to SetupRequests.MODERN_AIRPLAY_SOURCE_VERSION
            "isScreenMirroringSession" to true
            "timingProtocol" to timingProtocol
            "osBuildVersion" to "13F69"
            "model" to "Linux"
            "name" to clientName
            if (timingProtocol == "NTP") {
                "timingPort" to timingPort
            } else {
                "timingPeerInfo" to timingPeer
                "timingPeerList" to PlistValue.Array(listOf(timingPeer))
            }
            "updateSessionRequest" to false
            "combinedGetInfoWithControlSetup" to true
        }
        return RtspRequest(
            method = "SETUP",
            uri = uri,
            headers = mapOf("Content-Type" to CONTENT_TYPE_PLIST),
            body = BinaryPlistWriter.write(body),
        )
    }

    /**
     * Modern video SETUP (doubletake `streamOnlyPlist` + video descriptor):
     * one type-110 stream whose `shk`/`shiv` are the PD-mixed stream key and
     * IV, and whose `timestampInfo` lists the CoreUtils timestamps the
     * receiver's pipeline expects.
     */
    fun modernVideoSetup(
        streamConnectionId: Long,
        shk: ByteArray,
        shiv: ByteArray,
        uri: String,
        latencyMs: Int = 75, // doubletake latencies.video for a normal-capability link
    ): RtspRequest {
        val stream = plistDict {
            "type" to STREAM_TYPE_VIDEO
            "streamConnectionID" to streamConnectionId
            "latencyMs" to latencyMs
            "timestampInfo" to PlistValue.Array(
                listOf("SubSu", "BePxT", "AfPxT", "BefEn", "EmEnc").map { name ->
                    plistDict { "name" to name }
                },
            )
            "shk" to shk
            "shiv" to shiv
        }
        val body = plistDict {
            "streams" to PlistValue.Array(listOf(stream))
        }
        return RtspRequest(
            method = "SETUP",
            uri = uri,
            headers = mapOf("Content-Type" to CONTENT_TYPE_PLIST),
            body = BinaryPlistWriter.write(body),
        )
    }

    /** RECORD for the modern flow, on the control connection's URI. */
    fun modernRecord(controlUri: String, sessionUuid: String): RtspRequest = RtspRequest(
        method = "RECORD",
        uri = controlUri,
        headers = mapOf(
            "Range" to "npt=0-",
            "Session" to sessionUuid,
            "RTP-Info" to "seq=0;rtptime=0",
        ),
    )

    /**
     * Modern screen-audio stream (doubletake phase-2 audio descriptor). The
     * macOS session pipeline gates continuous video on this stream existing.
     * The codec is ALAC (ct=2, 352-sample frames, audioFormat bit 18) — this
     * is what doubletake's DEFAULT build sends to macOS (AAC-ELD is an opt-in
     * behind a libfdk-aac build tag, so every verified-to-produce-sound path
     * against a real Mac carried ALAC), and macOS advertises ALAC in its
     * screenStream mask. The 32-byte ChaCha stream key rides in `shk`.
     */
    fun modernAudioSetup(streamConnectionId: Long, shk: ByteArray, controlPort: Int, uri: String): RtspRequest {
        val stream = plistDict {
            "type" to STREAM_TYPE_AUDIO
            "streamConnectionID" to streamConnectionId
            "ct" to 2
            "spf" to 352
            "sr" to 44100
            "audioFormat" to (1L shl 18)
            "audioMode" to "default"
            "usingScreen" to true
            "isMedia" to false
            "latencyMin" to 0
            "latencyMax" to 3748
            "redundantAudio" to 2
            "shk" to shk
            "supportsDynamicStreamID" to true
            "streamConnections" to plistDict {
                "streamConnectionTypeRTP" to plistDict {
                    "streamConnectionKeyUseStreamEncryptionKey" to true
                }
                "streamConnectionTypeRTCP" to plistDict {
                    "streamConnectionKeyPort" to controlPort
                }
            }
        }
        val body = plistDict {
            "streams" to PlistValue.Array(listOf(stream))
        }
        return RtspRequest(
            method = "SETUP",
            uri = uri,
            headers = mapOf("Content-Type" to CONTENT_TYPE_PLIST),
            body = BinaryPlistWriter.write(body),
        )
    }

    /**
     * SET_PARAMETER volume=0 (doubletake audioVolumeBody(false)): real senders
     * set the receiver's audio volume after the audio SETUP — twice in captured
     * pcaps — and 0 dB is the sender's fixed choice. Positive dB values are
     * invalid here and current receivers may read them as zero gain.
     */
    fun modernVolume(controlUri: String): RtspRequest = RtspRequest(
        method = "SET_PARAMETER",
        uri = controlUri,
        headers = mapOf("Content-Type" to "text/parameters"),
        body = "volume: 0.000000\r\n".toByteArray(Charsets.US_ASCII),
    )
}
