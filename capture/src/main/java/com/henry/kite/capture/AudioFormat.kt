package com.henry.kite.capture

import kotlinx.coroutines.flow.Flow
import java.io.Closeable

/**
 * Encoder settings for the mirrored audio stream.
 *
 * Every AirPlay audio format seen so far runs at 44.1 kHz stereo, and AAC-ELD
 * frames are always 480 samples — the receiver is told as much in the SETUP
 * `spf` field, so these are fixed rather than negotiable.
 */
data class AudioFormat(
    val sampleRate: Int = SAMPLE_RATE,
    val channelCount: Int = CHANNEL_COUNT,
    val bitRate: Int = DEFAULT_BIT_RATE,
) {
    init {
        require(sampleRate > 0) { "Invalid sample rate: $sampleRate" }
        require(channelCount in 1..2) { "Unsupported channel count: $channelCount" }
    }

    companion object {
        /** AirPlay audio is 44.1 kHz throughout. */
        const val SAMPLE_RATE = 44_100
        const val CHANNEL_COUNT = 2

        /** Samples per AAC-ELD frame; goes out as the SETUP `spf` value. */
        const val SAMPLES_PER_FRAME = 480

        /** Comfortable for stereo music without crowding the video stream. */
        const val DEFAULT_BIT_RATE = 192_000
    }
}

/** One encoded AAC frame handed to the packetiser. */
class EncodedAudioFrame(
    val data: ByteArray,
    /** Presentation time in microseconds, from the encoder's own clock. */
    val presentationTimeUs: Long,
    /** True for the AudioSpecificConfig the encoder emits before any audio. */
    val isCodecConfig: Boolean,
)

/**
 * Anything that turns playback capture into [EncodedAudioFrame]s — the
 * MediaCodec AAC-ELD path and the software ALAC path both fit this shape, so
 * the service can hold either behind one field.
 */
interface AudioFrameSource : Closeable {
    fun frames(): Flow<EncodedAudioFrame>
}
