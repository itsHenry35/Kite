package com.henry.kite.airplay.pairing

import java.io.ByteArrayOutputStream

/** HAP TLV8 type codes used by pair-setup and pair-verify. */
object TlvType {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val RETRY_DELAY = 0x08
    const val CERTIFICATE = 0x09
    const val SIGNATURE = 0x0A
    const val PERMISSIONS = 0x0B

    /** Access-control payload; screen-capture pairings declare their identity use here. */
    const val ACL = 0x12
    const val FRAGMENT_DATA = 0x0C
    const val FRAGMENT_LAST = 0x0D
    const val FLAGS = 0x13
    const val SEPARATOR = 0xFF
}

/** HAP error codes carried in [TlvType.ERROR] (shairport-sync pair-tlv.h numbering). */
enum class TlvError(val code: Int) {
    UNKNOWN(0x01),
    AUTHENTICATION(0x02),
    BACKOFF(0x03),
    MAX_PEERS(0x04),
    MAX_TRIES(0x05),
    UNAVAILABLE(0x06),
    BUSY(0x07),
    ;

    companion object {
        fun of(code: Int): TlvError? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Ordered TLV8 container.
 *
 * Values longer than 255 bytes are split across repeated type codes on write and
 * transparently rejoined on read, per HAP §5.6.2. Ordering is preserved because
 * the spec allows the same type to appear more than once for that reason.
 */
class Tlv8 private constructor(private val entries: List<Pair<Int, ByteArray>>) {
    operator fun get(type: Int): ByteArray? {
        val matches = entries.filter { it.first == type }
        if (matches.isEmpty()) return null
        if (matches.size == 1) return matches[0].second
        val out = ByteArrayOutputStream()
        matches.forEach { out.write(it.second) }
        return out.toByteArray()
    }

    fun getInt(type: Int): Int? = get(type)?.let { bytes ->
        var value = 0
        // TLV8 integers are little-endian.
        for (i in bytes.indices.reversed()) value = (value shl 8) or (bytes[i].toInt() and 0xFF)
        value
    }

    val state: Int? get() = getInt(TlvType.STATE)

    val error: TlvError? get() = getInt(TlvType.ERROR)?.let(TlvError::of)

    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        for ((type, value) in entries) {
            if (value.isEmpty()) {
                out.write(type)
                out.write(0)
                continue
            }
            var offset = 0
            while (offset < value.size) {
                val chunk = minOf(MAX_CHUNK, value.size - offset)
                out.write(type)
                out.write(chunk)
                out.write(value, offset, chunk)
                offset += chunk
            }
        }
        return out.toByteArray()
    }

    override fun toString(): String =
        entries.joinToString(", ", "Tlv8(", ")") { "0x%02X:%d bytes".format(it.first, it.second.size) }

    class Builder {
        private val entries = mutableListOf<Pair<Int, ByteArray>>()

        fun put(type: Int, value: ByteArray): Builder = apply { entries += type to value }

        fun put(type: Int, value: Int): Builder = put(type, byteArrayOf(value.toByte()))

        fun put(type: Int, value: String): Builder = put(type, value.toByteArray())

        fun build(): Tlv8 = Tlv8(entries.toList())
    }

    companion object {
        private const val MAX_CHUNK = 255

        fun builder(): Builder = Builder()

        fun of(vararg pairs: Pair<Int, ByteArray>): Tlv8 = Tlv8(pairs.toList())

        fun decode(data: ByteArray): Tlv8 {
            val entries = mutableListOf<Pair<Int, ByteArray>>()
            var offset = 0
            while (offset + 2 <= data.size) {
                val type = data[offset].toInt() and 0xFF
                val length = data[offset + 1].toInt() and 0xFF
                val start = offset + 2
                val end = start + length
                require(end <= data.size) {
                    "Truncated TLV8: type 0x%02X claims %d bytes, %d remain".format(
                        type,
                        length,
                        data.size - start,
                    )
                }
                entries += type to data.copyOfRange(start, end)
                offset = end
            }
            return Tlv8(entries)
        }
    }
}
