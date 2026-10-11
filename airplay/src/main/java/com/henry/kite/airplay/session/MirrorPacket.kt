package com.henry.kite.airplay.session

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 128-byte header in front of every buffer on the mirroring data connection.
 *
 * Layout read straight off the receiver (UxPlay `raop_rtp_mirror.c`):
 *
 * | offset | size | field                                                |
 * |--------|------|------------------------------------------------------|
 * | 0      | 4    | payload length, after encryption (LE int)            |
 * | 4      | 2    | payload type (0 video, 1 codec config, 2 heartbeat)  |
 * | 6      | 2    | payload option / sub-flag                            |
 * | 8      | 8    | NTP-style timestamp (LE long)                        |
 * | 16     | 4    | width_0  (float, = encoded width)                    |
 * | 20     | 4    | height_0 (float)                                     |
 * | 40     | 4    | source width (float)                                 |
 * | 44     | 4    | source height (float)                                |
 * | 48     | 4    | unknown width (float, set to encoded width)          |
 * | 52     | 4    | unknown height (float)                               |
 * | 56     | 4    | display width (float)                                |
 * | 60     | 4    | display height (float)                               |
 *
 * All floats are little-endian. Offsets 16/20/40/44/56/60 are read by
 * `byteutils_get_float` in the receiver's codec-config branch; 48/52 are read
 * and logged but unused for decoding.
 */
object MirrorPacket {
    const val HEADER_SIZE = 128

    /** Video access unit, AVCC length-prefixed. */
    const val TYPE_VIDEO = 0

    /** Codec config: an `avcC` record, sent unencrypted. */
    const val TYPE_CODEC_CONFIG = 1

    /** Heartbeat the receiver tolerates with an empty payload. */
    const val TYPE_HEARTBEAT = 2

    /**
     * Header for a video access unit.
     *
     * @param payloadSize encrypted payload length; the receiver reads that many
     *   bytes off the socket before decrypting.
     * @param timestampNtp monotonic clock encoded as (seconds << 32) | fraction.
     */
    fun videoHeader(payloadSize: Int, timestampNtp: Long): ByteArray = header(payloadSize, TYPE_VIDEO) { buffer ->
        buffer.putLong(8, timestampNtp)
    }

    /**
     * Header for the codec config packet.
     *
     * The receiver requires this packet's timestamp to equal the following IDR
     * frame's timestamp, or it discards the SPS/PPS and never decodes anything.
     * [MirrorStream] enforces that pairing.
     */
    fun codecConfigHeader(
        payloadSize: Int,
        timestampNtp: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        displayWidth: Int = sourceWidth,
        displayHeight: Int = sourceHeight,
    ): ByteArray = header(payloadSize, TYPE_CODEC_CONFIG) { buffer ->
        buffer.putLong(8, timestampNtp)
        buffer.putFloat(16, sourceWidth.toFloat())
        buffer.putFloat(20, sourceHeight.toFloat())
        buffer.putFloat(40, sourceWidth.toFloat())
        buffer.putFloat(44, sourceHeight.toFloat())
        buffer.putFloat(48, displayWidth.toFloat())
        buffer.putFloat(52, displayHeight.toFloat())
        buffer.putFloat(56, displayWidth.toFloat())
        buffer.putFloat(60, displayHeight.toFloat())
    }

    /**
     * Modern (CoreUtils) codec-config frame (doubletake sendCodecFrame):
     * unencrypted avcC, payload option 0x16 selects the H.264 generic format
     * description, and the width/height floats describe the encoded raster.
     */
    fun modernCodecConfigHeader(payloadSize: Int, timestampNtp: Long, sourceWidth: Int, sourceHeight: Int): ByteArray =
        header(payloadSize, TYPE_CODEC_CONFIG) { buffer ->
            buffer.put(6, 0x16)
            buffer.put(7, 0x01)
            buffer.putLong(8, timestampNtp)
            buffer.putFloat(16, sourceWidth.toFloat())
            buffer.putFloat(20, sourceHeight.toFloat())
            buffer.putFloat(40, sourceWidth.toFloat())
            buffer.putFloat(44, sourceHeight.toFloat())
            buffer.putFloat(56, sourceWidth.toFloat())
            buffer.putFloat(60, sourceHeight.toFloat())
        }

    /** Modern heartbeat: doubletake's data-channel keepalive shape. */
    fun modernHeartbeatHeader(): ByteArray = header(0, TYPE_HEARTBEAT) { buffer ->
        buffer.put(6, 0x1e)
    }

    fun heartbeatHeader(): ByteArray = header(0, TYPE_HEARTBEAT) {}

    /**
     * Header for one encrypted video access unit on the modern data channel
     * (doubletake sendFrame): [5] flags the IDR so the receiver knows a decode
     * point is present, and [40:48] carries the receiver's PTP timeline so the
     * frame lands on the clock the session was anchored to.
     */
    fun modernVideoHeader(payloadSize: Int, timestampNtp: Long, keyframe: Boolean, timelineId: Long): ByteArray =
        header(payloadSize, TYPE_VIDEO) { buffer ->
            buffer.put(5, if (keyframe) 0x10 else 0x00)
            buffer.putLong(8, timestampNtp)
            buffer.putLong(40, timelineId)
        }

    private inline fun header(payloadSize: Int, type: Int, fill: (ByteBuffer) -> Unit): ByteArray {
        require(payloadSize >= 0) { "Negative payload size: $payloadSize" }
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, payloadSize)
        buffer.putShort(4, type.toShort())
        buffer.putShort(6, 0) // payload option; zero in every capture seen so far
        fill(buffer)
        return buffer.array()
    }
}

/**
 * Produces the mirroring-header timestamp.
 *
 * The receiver (`raop_ntp_timestamp_to_nano_seconds`, with epoch adjustment
 * disabled) treats the high 32 bits as whole seconds and the low 32 bits as a
 * binary fraction. It then computes a constant offset from the first packet and
 * applies it to every later one, so only monotonicity matters here — the epoch
 * does not. Using the monotonic clock keeps the value small and avoids the
 * unsigned underflow that a 1900-based epoch would cause.
 */
class MirrorClock {

    // PTP anchor (doubletake mediaClock): maps the receiver's advertised
    // timeline onto our monotonic clock. Set from the control SETUP response.
    private val ptpLock = Any()
    private var ptpAnchorTimestamp: Long = 0
    private var ptpAnchorMonoNanos: Long = 0
    private var ptpTimelineId: Long = 0
    private var ptpActive = false

    /**
     * Anchors the receiver's PTP timeline: [anchorTimestamp] (compact NTP, the
     * receiver's clock at the moment the request finished processing) observed
     * locally at [anchorMonoNanos] (System.nanoTime scale).
     */
    fun configurePtpAnchor(anchorTimestamp: Long, timelineId: Long, anchorMonoNanos: Long) {
        synchronized(ptpLock) {
            ptpAnchorTimestamp = anchorTimestamp
            ptpTimelineId = timelineId
            ptpAnchorMonoNanos = anchorMonoNanos
            ptpActive = true
        }
    }

    /** The receiver's advertised PTP timeline (ClockID), or 0 for the local clock. */
    fun ptpTimelineId(): Long = synchronized(ptpLock) { if (ptpActive) ptpTimelineId else 0 }

    /**
     * Re-anchors the timeline to a new ClockID (doubletake
     * updateTimingPeerInfo): the accumulated receiver-time since the last
     * anchor carries over, so the clock never jumps.
     */
    fun reanchorTimeline(timelineId: Long) {
        synchronized(ptpLock) {
            if (!ptpActive || timelineId == ptpTimelineId) return
            ptpTimelineId = timelineId
        }
    }

    fun now(): Long = fromMonotonicNanos(System.nanoTime())

    /**
     * MediaCodec presentation timestamps are on the same monotonic clock as
     * [System.nanoTime], just in microseconds.
     */
    fun fromPresentationTimeUs(presentationTimeUs: Long): Long = fromMonotonicNanos(presentationTimeUs * 1_000L)

    private fun compact(monoNanos: Long): Long {
        val seconds = java.lang.Math.floorDiv(monoNanos, NANOS_PER_SECOND)
        val nanos = monoNanos - seconds * NANOS_PER_SECOND
        val fraction = nanos * FRACTION_SCALE / NANOS_PER_SECOND
        return (seconds shl 32) or (fraction and 0xFFFFFFFFL)
    }

    private fun fromMonotonicNanos(monoNanos: Long): Long {
        // The anchor is already compact 32.32; only the ELAPSED nanos since the
        // anchor get compacted. Re-compacting the whole sum would multiply the
        // timestamp by 2^32/1e9 (frames land hours in the receiver's future).
        val (active, base, baseNanos) = synchronized(ptpLock) {
            Triple(ptpActive, ptpAnchorTimestamp, ptpAnchorMonoNanos)
        }
        return if (active) base + compactDelta(monoNanos - baseNanos) else compact(monoNanos)
    }

    /** Compact 32.32 representation of a (possibly negative) nanosecond span. */
    private fun compactDelta(nanos: Long): Long {
        val seconds = java.lang.Math.floorDiv(nanos, NANOS_PER_SECOND)
        val fracNanos = nanos - seconds * NANOS_PER_SECOND
        val fraction = fracNanos * FRACTION_SCALE / NANOS_PER_SECOND
        return (seconds shl 32) or (fraction and 0xFFFFFFFFL)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val FRACTION_SCALE = 4_294_967_296L // 2^32
    }
}
