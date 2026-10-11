package com.henry.kite.airplay.session

import com.henry.kite.airplay.plist.BinaryPlistReader
import com.henry.kite.airplay.plist.asArray
import com.henry.kite.airplay.plist.asBool
import com.henry.kite.airplay.plist.asData
import com.henry.kite.airplay.plist.asDict
import com.henry.kite.airplay.plist.asDouble
import com.henry.kite.airplay.plist.asInt
import com.henry.kite.airplay.plist.asLong
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The stream SETUP bodies are parsed back here so a field that silently drifts
 * (a compression type the receiver rejects, a frame length that no longer
 * matches the RTP timestamp step) fails in a test instead of as silent audio
 * on real receivers. The ct value once went out as 0x80=128, which no receiver
 * accepts; these assertions pin the wire shape.
 */
class SetupRequestsTest {

    @Before
    fun pinOsVersion() {
        // android.os.Build is unavailable on the JVM.
        SetupRequests.osVersionOverride = "17"
    }

    @Test
    fun `legacy audio setup declares ALAC with the matching frame length`() {
        val body = BinaryPlistReader.read(
            SetupRequests.audioStreamSetup(
                deviceId = "02:00:00:00:00:01",
                sessionUuid = "uuid",
                streamConnectionId = 42L,
                controlPort = 5000,
            ).body!!,
        ).asDict()

        val stream = body?.get("streams").asArray()?.first().asDict()
        assertEquals(2L, stream?.get("ct")?.asLong()) // ALAC
        assertEquals(352L, stream?.get("spf")?.asLong()) // must equal the RTP timestamp step
        assertEquals(1L shl 18, stream?.get("audioFormat")?.asLong())
        assertEquals(5000, stream?.get("controlPort")?.asInt())
        assertEquals(42L, stream?.get("streamConnectionID")?.asLong())
        assertEquals(false, stream?.get("isMedia")?.asBool())
        assertEquals(true, stream?.get("usingScreen")?.asBool())
    }

    @Test
    fun `legacy video setup carries the encoded geometry`() {
        val body = BinaryPlistReader.read(
            SetupRequests.videoStreamSetup(
                deviceId = "02:00:00:00:00:01",
                sessionUuid = "uuid",
                streamConnectionId = 7L,
                width = 1072,
                height = 2400,
                frameRate = 120,
            ).body!!,
        ).asDict()

        val stream = body?.get("streams").asArray()?.first().asDict()
        assertEquals(110L, stream?.get("type")?.asLong())
        assertEquals(7L, stream?.get("streamConnectionID")?.asLong())
        assertEquals(1072, stream?.get("width")?.asInt())
        assertEquals(2400, stream?.get("height")?.asInt())
        // refreshRate is the frame PERIOD in seconds (1/120 = 0.008333…), as on the wire.
        assertEquals(1.0 / 120, stream?.get("refreshRate")?.asDouble() ?: 0.0, 1e-9)
    }

    @Test
    fun `modern audio setup declares ALAC with the ChaCha stream key`() {
        val shk = ByteArray(32) { it.toByte() }
        val body = BinaryPlistReader.read(
            ModernSetupRequests.modernAudioSetup(
                streamConnectionId = 9L,
                shk = shk,
                controlPort = 6000,
                uri = "rtsp://1.2.3.4:7000/9",
            ).body!!,
        ).asDict()

        val stream = body?.get("streams").asArray()?.first().asDict()
        assertEquals(2L, stream?.get("ct")?.asLong()) // ALAC — doubletake's default for macOS
        assertEquals(352L, stream?.get("spf")?.asLong())
        assertEquals(1L shl 18, stream?.get("audioFormat")?.asLong())
        assertEquals(2L, stream?.get("redundantAudio")?.asLong())
        assertEquals(shk.size, stream?.get("shk")?.asData()?.size)
        val connections = stream?.get("streamConnections").asDict()
        assertEquals(
            true,
            connections?.get("streamConnectionTypeRTP").asDict()
                ?.get("streamConnectionKeyUseStreamEncryptionKey")?.asBool(),
        )
        assertEquals(
            6000,
            connections?.get("streamConnectionTypeRTCP").asDict()?.get("streamConnectionKeyPort")?.asInt(),
        )
    }

    @Test
    fun `legacy session setup carries the FairPlay key material at the root`() {
        val eiv = ByteArray(16) { 1 }
        val ekey = ByteArray(72) { 2 }
        val body = BinaryPlistReader.read(
            SetupRequests.sessionSetup(
                deviceId = "02:00:00:00:00:01",
                sessionUuid = "uuid",
                clientName = "Test",
                timingPort = 4000,
                eiv = eiv,
                ekey = ekey,
            ).body!!,
        ).asDict()

        assertEquals(16, body?.get("eiv")?.asData()?.size)
        assertEquals(72, body?.get("ekey")?.asData()?.size)
        assertEquals(4000, body?.get("timingPort")?.asInt())
        assertEquals(true, body?.get("isScreenMirroringSession")?.asBool())
    }
}
