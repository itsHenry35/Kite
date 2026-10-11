package com.henry.kite.airplay.plist

/**
 * Reads `bplist00`. Receivers reply to SETUP with one of these, carrying the
 * stream ids and data ports the sender then connects to.
 */
object BinaryPlistReader {

    class MalformedPlistException(message: String) : Exception(message)

    fun read(data: ByteArray): PlistValue {
        if (data.size < HEADER_LEN + TRAILER_LEN) {
            throw MalformedPlistException("Too short to be a binary plist: ${data.size} bytes")
        }
        val magic = data.copyOfRange(0, 6).decodeToString()
        if (magic != "bplist") {
            throw MalformedPlistException("Not a binary plist, magic was '$magic'")
        }

        val trailerStart = data.size - TRAILER_LEN
        val offsetSize = data[trailerStart + 6].toInt() and 0xFF
        val refSize = data[trailerStart + 7].toInt() and 0xFF
        val objectCount = data.readBigEndian(trailerStart + 8, 8).toInt()
        val topObject = data.readBigEndian(trailerStart + 16, 8).toInt()
        val offsetTableStart = data.readBigEndian(trailerStart + 24, 8).toInt()

        if (offsetSize !in 1..8 || refSize !in 1..8) {
            throw MalformedPlistException("Bad trailer widths: offset=$offsetSize ref=$refSize")
        }
        if (objectCount <= 0 || topObject >= objectCount) {
            throw MalformedPlistException("Bad object table: count=$objectCount top=$topObject")
        }

        val offsets = IntArray(objectCount) { i ->
            val at = offsetTableStart + i * offsetSize
            if (at + offsetSize > trailerStart) {
                throw MalformedPlistException("Offset table runs past the trailer")
            }
            data.readBigEndian(at, offsetSize).toInt()
        }

        return Parser(data, offsets, refSize).parse(topObject, depth = 0)
    }

    private class Parser(private val data: ByteArray, private val offsets: IntArray, private val refSize: Int) {
        fun parse(index: Int, depth: Int): PlistValue {
            if (depth > MAX_DEPTH) {
                throw MalformedPlistException("Nesting deeper than $MAX_DEPTH; refusing")
            }
            if (index !in offsets.indices) {
                throw MalformedPlistException("Object reference $index out of range")
            }
            var at = offsets[index]
            if (at !in data.indices) {
                throw MalformedPlistException("Object $index points outside the buffer")
            }

            val marker = data[at].toInt() and 0xFF
            val type = marker and 0xF0
            val nibble = marker and 0x0F
            at++

            return when (type) {
                0x00 -> when (nibble) {
                    0x00 -> PlistValue.Str("")

                    // null; AirPlay never sends one
                    0x08 -> PlistValue.Bool(false)

                    0x09 -> PlistValue.Bool(true)

                    0x0F -> PlistValue.Str("")

                    // fill byte
                    else -> throw MalformedPlistException("Unknown singleton 0x%02X".format(marker))
                }

                0x10 -> {
                    val size = 1 shl nibble
                    val raw = data.readBigEndian(at, size)
                    // Only the 8-byte form is signed.
                    PlistValue.Integer(if (size == 8) raw else raw and mask(size))
                }

                0x20 -> when (nibble) {
                    2 -> PlistValue.Real(
                        java.lang.Float.intBitsToFloat(data.readBigEndian(at, 4).toInt()).toDouble(),
                    )

                    3 -> PlistValue.Real(java.lang.Double.longBitsToDouble(data.readBigEndian(at, 8)))

                    else -> throw MalformedPlistException("Unsupported real width 2^$nibble")
                }

                0x40 -> {
                    val (count, next) = readCount(nibble, at)
                    PlistValue.Data(slice(next, count))
                }

                0x50 -> {
                    val (count, next) = readCount(nibble, at)
                    PlistValue.Str(slice(next, count).toString(Charsets.US_ASCII))
                }

                0x60 -> {
                    val (count, next) = readCount(nibble, at)
                    PlistValue.Str(slice(next, count * 2).toString(Charsets.UTF_16BE))
                }

                0x80 -> PlistValue.Integer(data.readBigEndian(at, nibble + 1))

                // UID

                0xA0, 0xC0 -> {
                    val (count, next) = readCount(nibble, at)
                    PlistValue.Array(
                        List(count) { i -> parse(readRef(next + i * refSize), depth + 1) },
                    )
                }

                0xD0 -> {
                    val (count, next) = readCount(nibble, at)
                    val valuesStart = next + count * refSize
                    val entries = linkedMapOf<String, PlistValue>()
                    for (i in 0 until count) {
                        val key = parse(readRef(next + i * refSize), depth + 1)
                            .asString()
                            ?: throw MalformedPlistException("Dictionary key is not a string")
                        entries[key] = parse(readRef(valuesStart + i * refSize), depth + 1)
                    }
                    PlistValue.Dict(entries)
                }

                else -> throw MalformedPlistException("Unknown marker 0x%02X".format(marker))
            }
        }

        /** Returns the count and the offset just past it. */
        private fun readCount(nibble: Int, at: Int): Pair<Int, Int> {
            if (nibble != 0x0F) return nibble to at
            val intMarker = data[at].toInt() and 0xFF
            if (intMarker and 0xF0 != 0x10) {
                throw MalformedPlistException("Expected an int-typed count marker")
            }
            val size = 1 shl (intMarker and 0x0F)
            return data.readBigEndian(at + 1, size).toInt() to (at + 1 + size)
        }

        private fun readRef(at: Int): Int = data.readBigEndian(at, refSize).toInt()

        private fun slice(from: Int, length: Int): ByteArray {
            if (length < 0 || from + length > data.size) {
                throw MalformedPlistException("Object claims $length bytes past the buffer end")
            }
            return data.copyOfRange(from, from + length)
        }

        private fun mask(size: Int): Long = if (size >= 8) -1L else (1L shl (size * 8)) - 1
    }

    private fun ByteArray.readBigEndian(at: Int, size: Int): Long {
        if (at < 0 || at + size > this.size) {
            throw MalformedPlistException("Read of $size bytes at $at is out of bounds")
        }
        var value = 0L
        for (i in 0 until size) value = (value shl 8) or (this[at + i].toLong() and 0xFF)
        return value
    }

    private const val HEADER_LEN = 8
    private const val TRAILER_LEN = 32
    private const val MAX_DEPTH = 32
}
