package com.henry.kite.settings

import com.henry.kite.capture.VideoFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The settings slider and the encoder's validation live in different modules
 * and each owns its bounds — nothing in the type system keeps them in sync.
 * These tests are that sync: the slider once offered 200 Mbps while the
 * encoder still rejected anything over 50, crashing at cast time.
 */
class MirrorSettingsTest {

    @Test
    fun `slider bounds match the encoder's accepted range`() {
        assertEquals(
            MirrorSettings.BIT_RATE_MIN_MBPS * 1_000_000,
            VideoFormat.BIT_RATE_MIN_BPS,
        )
        assertEquals(
            MirrorSettings.BIT_RATE_MAX_MBPS * 1_000_000,
            VideoFormat.BIT_RATE_MAX_BPS,
        )
    }

    @Test
    fun `bit rate conversion`() {
        assertEquals(0, MirrorSettings(bitRateMbps = MirrorSettings.BIT_RATE_AUTO).bitRateBps())
        assertEquals(30_000_000, MirrorSettings(bitRateMbps = 30).bitRateBps())
        assertEquals(200_000_000, MirrorSettings(bitRateMbps = 200).bitRateBps())
    }

    @Test
    fun `the widest slider choice survives the encoder validation`() {
        // Building the widest legal format the settings can produce must not throw.
        val widest = MirrorSettings(bitRateMbps = MirrorSettings.BIT_RATE_MAX_MBPS)
        VideoFormat(1072, 2400, frameRate = 120, bitRate = widest.bitRateBps())
    }
}
