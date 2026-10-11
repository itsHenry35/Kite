package com.henry.kite.airplay.session

import java.io.ByteArrayOutputStream

/**
 * Converts MediaCodec's Annex-B output into the AVCC form the mirroring stream
 * expects: every NAL unit prefixed by its 4-byte big-endian length instead of a
 * `00 00 00 01` start code.
 *
 * This is the direction reversed from what a receiver does. UxPlay's
 * `h264_decode` walks length-prefixed units and rebuilds start codes for its
 * decoder, so a sender has to strip them back out.
 */
object NaluRepacker {

    /** NAL units, without start codes, in stream order. */
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var index = 0
        var unitStart = -1

        while (index < data.size) {
            val prefix = startCodeLengthAt(data, index)
            if (prefix > 0) {
                if (unitStart >= 0 && index > unitStart) {
                    units += data.copyOfRange(unitStart, index)
                }
                index += prefix
                unitStart = index
            } else {
                index++
            }
        }
        if (unitStart in 0 until data.size) {
            units += data.copyOfRange(unitStart, data.size)
        }
        return units
    }

    /** Rewrites an Annex-B buffer as concatenated length-prefixed units. */
    fun toAvcc(annexB: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(annexB.size)
        for (unit in splitAnnexB(annexB)) {
            if (unit.isEmpty()) continue
            out.writeInt32(unit.size)
            out.write(unit)
        }
        return out.toByteArray()
    }

    /**
     * Extracts the SPS and PPS from the encoder's codec-config buffer.
     * They arrive concatenated in one Annex-B blob, SPS first.
     */
    fun parseParameterSets(codecConfig: ByteArray): ParameterSets? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (unit in splitAnnexB(codecConfig)) {
            if (unit.isEmpty()) continue
            when (unit[0].toInt() and 0x1F) {
                NAL_TYPE_SPS -> if (sps == null) sps = unit
                NAL_TYPE_PPS -> if (pps == null) pps = unit
            }
        }
        return if (sps != null && pps != null) ParameterSets(sps, pps) else null
    }

    /** True when this access unit contains an IDR slice. */
    fun containsIdr(annexB: ByteArray): Boolean =
        splitAnnexB(annexB).any { it.isNotEmpty() && (it[0].toInt() and 0x1F) == NAL_TYPE_IDR }

    /** Returns 3 or 4 if a start code begins at [at], otherwise 0. */
    private fun startCodeLengthAt(data: ByteArray, at: Int): Int {
        if (at + 3 > data.size) return 0
        if (data[at] != 0.toByte() || data[at + 1] != 0.toByte()) return 0
        if (data[at + 2] == 1.toByte()) return 3
        if (at + 4 <= data.size && data[at + 2] == 0.toByte() && data[at + 3] == 1.toByte()) return 4
        return 0
    }

    private const val NAL_TYPE_IDR = 5
    private const val NAL_TYPE_SPS = 7
    private const val NAL_TYPE_PPS = 8
}

data class ParameterSets(val sps: ByteArray, val pps: ByteArray) {

    /**
     * Builds the `avcC` decoder configuration record, which is what the mirroring
     * stream's type-1 packet carries as its payload.
     *
     * ISO/IEC 14496-15 §5.2.4.1:
     *   configurationVersion, AVCProfileIndication, profile_compatibility,
     *   AVCLevelIndication, 6 reserved bits + lengthSizeMinusOne,
     *   3 reserved bits + numOfSequenceParameterSets, then the sets.
     */
    fun toAvcDecoderConfig(): ByteArray {
        require(sps.size >= 4) { "SPS too short to contain a profile/level: ${sps.size} bytes" }
        val out = ByteArrayOutputStream()
        out.write(1) // configurationVersion
        out.write(sps[1].toInt()) // AVCProfileIndication
        out.write(sps[2].toInt()) // profile_compatibility
        out.write(sps[3].toInt()) // AVCLevelIndication
        out.write(0xFF) // 111111 + lengthSizeMinusOne = 3 (4-byte lengths)
        out.write(0xE1) // 111 + numOfSequenceParameterSets = 1
        out.writeInt16(sps.size)
        out.write(sps)
        out.write(1) // numOfPictureParameterSets
        out.writeInt16(pps.size)
        out.write(pps)
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParameterSets) return false
        return sps.contentEquals(other.sps) && pps.contentEquals(other.pps)
    }

    override fun hashCode(): Int = 31 * sps.contentHashCode() + pps.contentHashCode()

    override fun toString(): String = "ParameterSets(sps=${sps.size}B, pps=${pps.size}B)"
}

internal fun ByteArrayOutputStream.writeInt16(value: Int) {
    write((value shr 8) and 0xFF)
    write(value and 0xFF)
}

internal fun ByteArrayOutputStream.writeInt32(value: Int) {
    write((value shr 24) and 0xFF)
    write((value shr 16) and 0xFF)
    write((value shr 8) and 0xFF)
    write(value and 0xFF)
}
