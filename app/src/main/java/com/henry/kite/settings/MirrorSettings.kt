package com.henry.kite.settings

import com.henry.kite.capture.VideoFormat

/**
 * Encoder resolution cap the user picked.
 *
 * [maxLongEdge] feeds [VideoFormat.forDisplay]; [VideoFormat.LONG_EDGE_UNLIMITED]
 * keeps the display's own resolution, subject only to the multiple-of-16
 * alignment every hardware encoder wants.
 */
enum class Quality(val maxLongEdge: Int) {
    /** The display's native resolution — the sharpest, and the most to encode. */
    NATIVE(VideoFormat.LONG_EDGE_UNLIMITED),
    P1080(1920),
    P720(1280),
    P540(960),
}

/**
 * Frame rate options. Mirroring a mostly-static UI looks fine at 30; 60 costs
 * roughly twice the bit rate for motion that is usually not there.
 */
enum class FrameRate(val fps: Int) {
    FPS24(24),
    FPS30(30),
    FPS60(60),
    FPS90(90),
    FPS120(120),
}

/**
 * One snapshot of everything the settings screen controls.
 *
 * [bitRateMbps] of [BIT_RATE_AUTO] leaves the choice to
 * [VideoFormat.defaultBitRate], which scales with the frame size.
 */
data class MirrorSettings(
    val quality: Quality = Quality.P1080,
    val frameRate: FrameRate = FrameRate.FPS30,
    val bitRateMbps: Int = BIT_RATE_AUTO,
) {
    /** Bits per second for [MirrorService], or 0 to derive it from the size. */
    fun bitRateBps(): Int = if (bitRateMbps == BIT_RATE_AUTO) 0 else bitRateMbps * 1_000_000

    companion object {
        const val BIT_RATE_AUTO = 0

        /** The value picking "manual" starts from. */
        const val BIT_RATE_MANUAL_DEFAULT_MBPS = 30
        const val BIT_RATE_MIN_MBPS = 2
        const val BIT_RATE_MAX_MBPS = 200
    }
}
