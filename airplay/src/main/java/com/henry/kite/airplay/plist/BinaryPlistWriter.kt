package com.henry.kite.airplay.plist

import java.io.ByteArrayOutputStream

/**
 * Writes `bplist00`, the format every AirPlay 2 RTSP body uses.
 *
 * Layout: an 8-byte magic, the object table, an offset table, then a 32-byte
 * trailer that says how wide the offsets and references are.
 *
 * Structurally equal objects are emitted once and referenced everywhere they
 * occur, matching Apple's writer: shared subtrees (the same dict appearing as
 * timingPeerInfo and inside timingPeerList) must not be duplicated.
 */
object BinaryPlistWriter {

    /**
     * One slot in the object table. Containers resolve their children to indices
     * during the walk, so encoding never has to look a value up again.
     */
    private sealed interface Node {
        data class Leaf(val value: PlistValue) : Node
        data class Text(val value: String) : Node
        data class Arr(val refs: List<Int>) : Node
        data class Map(val keyRefs: List<Int>, val valueRefs: List<Int>) : Node
    }

    fun write(root: PlistValue): ByteArray {
        val nodes = mutableListOf<Node>()
        val seen = HashMap<PlistValue, Int>()
        assign(root, nodes, seen)

        val refSize = byteWidth(nodes.size)
        val body = ByteArrayOutputStream()
        body.write(MAGIC)

        val offsets = IntArray(nodes.size)
        nodes.forEachIndexed { index, node ->
            offsets[index] = body.size()
            encode(node, body, refSize)
        }

        val offsetTableStart = body.size()
        val offsetSize = byteWidth(offsetTableStart)
        offsets.forEach { writeBigEndian(body, it.toLong(), offsetSize) }

        // Trailer: 5 unused bytes, sortVersion, offsetIntSize, objectRefSize,
        // numObjects, topObject, offsetTableOffset — exactly 32 bytes. One pad
        // byte more shifts every field for parsers that read the last 32 bytes
        // strictly (Apple's), and they answer 400 to the whole request.
        body.write(ByteArray(5))
        body.write(0)
        body.write(offsetSize)
        body.write(refSize)
        writeBigEndian(body, nodes.size.toLong(), 8)
        writeBigEndian(body, 0L, 8)
        writeBigEndian(body, offsetTableStart.toLong(), 8)

        return body.toByteArray()
    }

    /**
     * Reserves this value's slot, then fills it once its children have slots.
     * Reserving first keeps the root at index 0, which the trailer requires.
     */
    private fun assign(value: PlistValue, nodes: MutableList<Node>, seen: MutableMap<PlistValue, Int>): Int {
        seen[value]?.let { return it }
        val index = nodes.size
        seen[value] = index
        when (value) {
            is PlistValue.Dict -> {
                nodes += PLACEHOLDER
                // Keys come before values in the table, matching Apple's writer.
                val keyRefs = value.entries.keys.map { key ->
                    assign(PlistValue.Str(key), nodes, seen)
                }
                val valueRefs = value.entries.values.map { assign(it, nodes, seen) }
                nodes[index] = Node.Map(keyRefs, valueRefs)
            }

            is PlistValue.Array -> {
                nodes += PLACEHOLDER
                val refs = value.items.map { assign(it, nodes, seen) }
                nodes[index] = Node.Arr(refs)
            }

            is PlistValue.Str -> nodes += Node.Text(value.value)

            else -> nodes += Node.Leaf(value)
        }
        return index
    }

    private fun encode(node: Node, out: ByteArrayOutputStream, refSize: Int) {
        when (node) {
            is Node.Text -> encodeString(node.value, out)

            is Node.Arr -> {
                writeMarkerAndCount(MARKER_ARRAY, node.refs.size, out)
                node.refs.forEach { writeBigEndian(out, it.toLong(), refSize) }
            }

            is Node.Map -> {
                writeMarkerAndCount(MARKER_DICT, node.keyRefs.size, out)
                node.keyRefs.forEach { writeBigEndian(out, it.toLong(), refSize) }
                node.valueRefs.forEach { writeBigEndian(out, it.toLong(), refSize) }
            }

            is Node.Leaf -> when (val value = node.value) {
                is PlistValue.Bool -> out.write(if (value.value) 0x09 else 0x08)

                is PlistValue.Integer -> encodeInteger(value.value, out)

                is PlistValue.Real -> {
                    out.write(0x23) // real, 2^3 = 8 bytes
                    writeBigEndian(out, java.lang.Double.doubleToRawLongBits(value.value), 8)
                }

                is PlistValue.Data -> {
                    writeMarkerAndCount(MARKER_DATA, value.value.size, out)
                    out.write(value.value)
                }

                // Containers and strings never reach here; assign() converts them.
                else -> error("Unexpected leaf: $value")
            }
        }
    }

    private fun encodeInteger(value: Long, out: ByteArrayOutputStream) {
        when {
            // Negatives are always stored as 8 bytes, sign extended.
            value < 0 -> {
                out.write(0x13)
                writeBigEndian(out, value, 8)
            }

            value <= 0xFF -> {
                out.write(0x10)
                writeBigEndian(out, value, 1)
            }

            value <= 0xFFFF -> {
                out.write(0x11)
                writeBigEndian(out, value, 2)
            }

            value <= 0xFFFFFFFFL -> {
                out.write(0x12)
                writeBigEndian(out, value, 4)
            }

            else -> {
                out.write(0x13)
                writeBigEndian(out, value, 8)
            }
        }
    }

    private fun encodeString(value: String, out: ByteArrayOutputStream) {
        if (value.all { it.code < 0x80 }) {
            writeMarkerAndCount(MARKER_ASCII, value.length, out)
            out.write(value.toByteArray(Charsets.US_ASCII))
        } else {
            // UTF-16BE, and the count is code units rather than bytes.
            val bytes = value.toByteArray(Charsets.UTF_16BE)
            writeMarkerAndCount(MARKER_UTF16, bytes.size / 2, out)
            out.write(bytes)
        }
    }

    /** Counts under 15 pack into the marker's low nibble; larger ones follow as an int. */
    private fun writeMarkerAndCount(marker: Int, count: Int, out: ByteArrayOutputStream) {
        if (count < 0x0F) {
            out.write(marker or count)
        } else {
            out.write(marker or 0x0F)
            encodeInteger(count.toLong(), out)
        }
    }

    private fun writeBigEndian(out: ByteArrayOutputStream, value: Long, size: Int) {
        for (shift in (size - 1) downTo 0) {
            out.write(((value shr (shift * 8)) and 0xFF).toInt())
        }
    }

    private fun byteWidth(maxValue: Int): Int = when {
        maxValue <= 0xFF -> 1
        maxValue <= 0xFFFF -> 2
        else -> 4
    }

    private const val MARKER_DATA = 0x40
    private const val MARKER_ASCII = 0x50
    private const val MARKER_UTF16 = 0x60
    private const val MARKER_ARRAY = 0xA0
    private const val MARKER_DICT = 0xD0

    private val MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)
    private val PLACEHOLDER = Node.Leaf(PlistValue.Bool(false))
}
