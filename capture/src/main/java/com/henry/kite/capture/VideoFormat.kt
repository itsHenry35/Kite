package com.henry.kite.capture

/** Encoder settings for one mirroring session. */
data class VideoFormat(
    val width: Int,
    val height: Int,
    val frameRate: Int = 30,
    val bitRate: Int = defaultBitRate(width, height, 30),
    /** Seconds between IDR frames. Short keeps recovery fast after packet loss. */
    val keyFrameIntervalSeconds: Int = 2,
) {
    init {
        // H.264 requires even dimensions; most hardware encoders demand multiples of 16.
        require(width > 0 && height > 0) { "Invalid size ${width}x$height" }
        require(frameRate in 1..120) { "Unreasonable frame rate: $frameRate" }
        require(bitRate in BIT_RATE_MIN_BPS..BIT_RATE_MAX_BPS) { "Bit rate out of range: $bitRate" }
    }

    /** Rounds down to a multiple of 16 so no encoder silently rejects the size. */
    fun aligned(): VideoFormat = copy(width = width / 16 * 16, height = height / 16 * 16)

    companion object {
        /** Bounds shared with the settings slider (MirrorSettings.BIT_RATE_*_MBPS). */
        const val BIT_RATE_MIN_BPS = 2_000_000
        const val BIT_RATE_MAX_BPS = 200_000_000

        /**
         * Roughly 0.1 bits per pixel per frame. Mirroring is mostly static UI, so
         * this sits well below what video of the same size would need.
         */
        fun defaultBitRate(width: Int, height: Int, frameRate: Int): Int =
            (width.toLong() * height * frameRate / 10).toInt().coerceIn(BIT_RATE_MIN_BPS, BIT_RATE_MAX_BPS)

        /** Passed as [maxLongEdge] to keep the display's own resolution. */
        const val LONG_EDGE_UNLIMITED = 0

        /**
         * Scales a display down so its long edge is at most [maxLongEdge].
         *
         * [LONG_EDGE_UNLIMITED] keeps the native resolution — only the
         * multiple-of-16 alignment still applies. [bitRate] overrides the
         * size-derived default; null keeps it.
         */
        fun forDisplay(
            displayWidth: Int,
            displayHeight: Int,
            maxLongEdge: Int = 1920,
            frameRate: Int = 30,
            bitRate: Int? = null,
        ): VideoFormat {
            val longEdge = maxOf(displayWidth, displayHeight)
            val scale = if (maxLongEdge != LONG_EDGE_UNLIMITED && longEdge > maxLongEdge) {
                maxLongEdge.toDouble() / longEdge
            } else {
                1.0
            }
            val width = (displayWidth * scale).toInt()
            val height = (displayHeight * scale).toInt()
            // Align first, then derive the bit rate from the size actually encoded.
            val aligned = VideoFormat(
                width = width,
                height = height,
                frameRate = frameRate,
                bitRate = defaultBitRate(width, height, frameRate),
            ).aligned()
            return aligned.copy(
                bitRate = bitRate ?: defaultBitRate(aligned.width, aligned.height, frameRate),
            )
        }
    }
}

/** One encoded access unit handed to the packetiser. */
class EncodedFrame(
    /** H.264 payload. Annex-B start codes for config, AVCC length prefixes otherwise. */
    val data: ByteArray,
    /** Presentation time in microseconds, from the encoder's own clock. */
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean,
    /** True for the SPS/PPS blob the encoder emits before the first real frame. */
    val isCodecConfig: Boolean,
)
