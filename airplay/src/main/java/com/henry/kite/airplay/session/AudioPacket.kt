package com.henry.kite.airplay.session

/**
 * Builds the 12-byte RTP header the audio stream uses.
 *
 * Layout, read off UxPlay's `raop_rtp.c`:
 *
 * ```
 * [0]      0x80
 * [1]      0x60  (payload type 96)
 * [2:3]    sequence number,  big-endian u16
 * [4:7]    RTP timestamp,    big-endian u32
 * [8:11]   zero
 * [12:]    payload, AES-CBC encrypted in whole blocks
 * ```
 *
 * The header itself is never encrypted — the receiver reads the sequence number
 * and timestamp before it decrypts anything.
 */
object AudioPacket {

    const val HEADER_BYTES = 12

    /**
     * Payload that stands in for audio before the timeline is running.
     *
     * UxPlay treats a 16-byte packet whose payload is exactly this marker as
     * "no data yet" and drops it without decrypting, while still taking the
     * sequence number and timestamp from the header.
     */
    val NO_DATA_MARKER = byteArrayOf(0x00, 0x68, 0x34, 0x00)

    fun packet(sequenceNumber: Int, rtpTimestamp: Long, payload: ByteArray): ByteArray {
        val packet = ByteArray(HEADER_BYTES + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = PAYLOAD_TYPE_AUDIO
        packet[2] = (sequenceNumber ushr 8 and 0xFF).toByte()
        packet[3] = (sequenceNumber and 0xFF).toByte()
        packet[4] = (rtpTimestamp ushr 24 and 0xFF).toByte()
        packet[5] = (rtpTimestamp ushr 16 and 0xFF).toByte()
        packet[6] = (rtpTimestamp ushr 8 and 0xFF).toByte()
        packet[7] = (rtpTimestamp and 0xFF).toByte()
        // Bytes 8..11 stay zero; the receiver does not read them for mirroring.
        payload.copyInto(packet, HEADER_BYTES)
        return packet
    }

    private const val PAYLOAD_TYPE_AUDIO: Byte = 0x60
}
