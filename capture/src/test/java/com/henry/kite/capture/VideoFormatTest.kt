package com.henry.kite.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Bounds and derivation checks for the encoder configuration. The bit-rate
 * bounds here must stay in lockstep with the settings slider — a mismatch used
 * to crash the process at cast time (settings allowed 200 Mbps while this class
 * still rejected anything over 50).
 */
class VideoFormatTest {

    @Test
    fun `settings maximum bit rate is accepted`() {
        // Regression: this exact value crashed VideoFormat's require() before.
        val format = VideoFormat(1072, 2400, frameRate = 60, bitRate = 200_000_000)
        assertEquals(200_000_000, format.bitRate)
    }

    @Test
    fun `settings minimum bit rate is accepted`() {
        val format = VideoFormat(1072, 2400, bitRate = 2_000_000)
        assertEquals(2_000_000, format.bitRate)
    }

    @Test
    fun `bit rate beyond the bounds is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            VideoFormat(1072, 2400, bitRate = 200_000_001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoFormat(1072, 2400, bitRate = 1_999_999)
        }
    }

    @Test
    fun `forDisplay caps the long edge and aligns to sixteen`() {
        val format = VideoFormat.forDisplay(
            displayWidth = 1072,
            displayHeight = 2400,
            maxLongEdge = 1920,
            frameRate = 60,
        )
        // 2400 > 1920 → scaled to 1920 long edge (853.33 x 1920), aligned down.
        assertEquals(848, format.width) // 853 → aligned to 16
        assertEquals(1920, format.height)
    }

    @Test
    fun `forDisplay keeps native size when unlimited`() {
        val format = VideoFormat.forDisplay(
            displayWidth = 1072,
            displayHeight = 2400,
            maxLongEdge = VideoFormat.LONG_EDGE_UNLIMITED,
            frameRate = 120,
        )
        assertEquals(1072, format.width)
        assertEquals(2400, format.height)
    }

    @Test
    fun `forDisplay honours the explicit bit rate override`() {
        val format = VideoFormat.forDisplay(
            displayWidth = 1072,
            displayHeight = 2400,
            maxLongEdge = VideoFormat.LONG_EDGE_UNLIMITED,
            frameRate = 60,
            bitRate = 150_000_000,
        )
        assertEquals(150_000_000, format.bitRate)
    }

    @Test
    fun `default bit rate scales with pixels and clamps to the bounds`() {
        // 1072x2400@60 ≈ 0.1 bpp → 15.4 Mbps, inside the range untouched.
        assertEquals(15_436_800, VideoFormat.defaultBitRate(1072, 2400, 60))
        // A tiny window clamps up to the minimum.
        assertEquals(2_000_000, VideoFormat.defaultBitRate(160, 240, 30))
        // 4K120 ≈ 99.5 Mbps — derived, not clamped.
        assertEquals(99_532_800, VideoFormat.defaultBitRate(3840, 2160, 120))
        // 8K120 would exceed the maximum; the derived default clamps.
        assertEquals(200_000_000, VideoFormat.defaultBitRate(7680, 4320, 120))
    }
}
