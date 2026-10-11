package com.henry.kite.airplay.session

/**
 * FairPlay's proprietary SAP hash (doubletake fairplay_sap.go). Not a standard
 * hash. All values are Int in 0..255; every arithmetic op must re-mask because
 * Go's byte arithmetic wraps where Kotlin's would widen.
 */
internal object FairplaySap {

    private val INITIAL_STATE_HASH = intArrayOf(
        0x96, 0x5f, 0xc6, 0x53, 0xf8, 0x46, 0xcc, 0x18, 0xdf, 0xbe,
        0xb2, 0xf8, 0x38, 0x62, 0xec, 0x22, 0x93, 0xd1, 0x20, 0x8f,
    )

    private val INITIAL_MATRIX = intArrayOf(
        0x43, 0x54, 0x62, 0x7a, 0x18, 0xc3, 0xd6, 0xb3, 0x9a, 0x56,
        0xf6, 0x1c, 0x14, 0x3f, 0x0c, 0x1d, 0x3b, 0x36, 0x83, 0xb1,
        0x39, 0x51, 0x4a, 0xaa, 0x09, 0x3e, 0xfe, 0x44, 0xaf, 0xde,
        0xc3, 0x20, 0x9d, 0x42, 0xb8,
    )

    private val SEED = intArrayOf(
        0xed, 0x25, 0xd1, 0xbb, 0xbc, 0x27, 0x9f, 0x02, 0xa2, 0xa9, 0x11,
        0x00, 0x0c, 0xb3, 0x52, 0xc0, 0xbd, 0xe3, 0x1b, 0x49, 0xc7,
    )

    /** Hashes one 64-byte block into 16 output bytes. */
    fun hash(block: IntArray): IntArray {
        require(block.size >= 64) { "SAP hash needs a full 64-byte block" }
        val hash = INITIAL_STATE_HASH.copyOf()
        val matrix = INITIAL_MATRIX.copyOf()
        val aux = IntArray(10)
        val work = IntArray(210)

        // Input is loaded in reversed four-byte groups.
        for (i in work.indices) work[i] = block[(i and 63) xor 3]

        // Go iterates uint32 i over 0..839, so subtractions wrap before the
        // modulo; 2^32 ≡ 46 (mod 210), which is what the wrapped branch adds.
        fun workIndex(i: Int, base: Int): Int {
            val diff = i - base
            return if (diff >= 0) diff % 210 else (diff + 46).mod(210)
        }
        for (i in 0 until 840) {
            val x = work[workIndex(i, 155)]
            val y = work[workIndex(i, 57)]
            val z = work[workIndex(i, 13)]
            val w = work[i % 210]
            work[i % 210] = (rol8(y, 5) + (rol8(z, 3) xor w) - rol8(x, 7)) and 0xFF
        }

        nonlinearCircuit(hash, matrix, aux, work)

        val out = IntArray(16)
        out[0] = aux[0]
        out[1] = aux[1]
        out[2] = aux[2]
        for (i in 3 until 10) out[i + 1] = aux[i]
        for (i in out.indices) out[i] = (out[i] + 0xe1) and 0xFF
        out[3] = 0x3d
        out[11] = 0x3c
        out[10] = out[10] xor (aux[3] xor 133)

        for (i in work.indices) {
            var value = work[i]
            if (i < matrix.size) value = value xor matrix[i]
            if (i < hash.size) value = value xor hash[i]
            out[i and 15] = out[i and 15] xor value
        }

        // Reverse scramble; (i - n) and 15 matches Go's two's-complement AND.
        for (i in 0 until 256) {
            out[i and 15] = out[i and 15] xor (
                rol8(out[(i - 7) and 15], 1) xor
                    rol8(out[(i - 5) and 15], 6) xor
                    rol8(out[(i - 1) and 15], 5)
                )
        }
        return out
    }

    // Mirrors Go's rotateOrZero: a count of 0 yields 0 rather than a rotation,
    // and bits.RotateLeft8 takes the count modulo 8.
    private fun rol8(value: Int, count: Int): Int {
        if (count == 0) return 0
        val v = value and 0xFF
        val k = count and 7
        return ((v shl k) and 0xFF) or (v ushr (8 - k))
    }

    // A faithful line-by-line transcription of doubletake's 100-step circuit;
    // splitting it would only obscure the correspondence with the original.
    @Suppress("LongMethod")
    private fun nonlinearCircuit(hash: IntArray, matrix: IntArray, aux: IntArray, work: IntArray) {
        fun hi(i: Int): Int = hash[i % 20]
        fun si(i: Int): Int = SEED[i % 21]
        fun h(i: Int): Int = hash[work[i] % 20]
        fun m(i: Int): Int = matrix[work[i] % 35]
        fun s(i: Int): Int = SEED[work[i] % 21]
        fun ma(i: Int): Int = matrix[aux[i] % 35]
        fun square(v: Int): Int = (v * v) and 0xFF
        fun cube(v: Int): Int = (v * v * v) and 0xFF
        fun majority(a: Int, b: Int, c: Int): Int = a xor ((a xor b) and (a xor c))
        fun selectBits(mask: Int, ifSet: Int, ifClear: Int): Int = ifClear xor ((ifSet xor ifClear) and mask)
        fun wideSeed(input: Int, count: Int): Int =
            if (count == 0) SEED[0] else SEED[((input shl count) or (input ushr (8 - count))) % SEED.size]

        matrix[12] = (0x14 + (selectBits(92, work[64], work[99] / 3) and wideSeed(s(206), 4))) and 0xFF
        work[4] = (2 * square(work[99] / 5)) and 0xFF
        work[153] = (work[153] xor (square(m(203)) * work[190])) and 0xFF
        hash[3] = 0x13 xor ((s(205) shr 1 and 0x10) and 0xFF)
        work[33] = (work[33] - (s(36) and 9.inv())) and 0xFF
        aux[5] = (((m(67) and 2.inv()) or 1 or (h(181) shr 6 and 2) or (hash[3] and 0x10)) - 15) and 0xFF
        matrix[12] = 0x07
        work[2] = (work[2] - 64) and 0xFF
        hash[19] = s(58)
        aux[4] = (92 - m(32)) and 0xFF
        aux[9] = (m(15) + 0x9e) and 0xFF
        work[34] = (work[34] + si(aux[9]) / 5) and 0xFF
        hash[19] = (hash[19] + (0xe6 xor (hi(aux[9]) shr 1 and 0x66))) and 0xFF
        work[15] = (work[15] xor (3 * rol8(work[72], -(s(190)) and 7) - 9 * s(126))) and 0xFF
        hash[15] = (hash[15] xor cube(m(181))) and 0xFF
        matrix[4] = (matrix[4] xor (work[202] / 3)) and 0xFF
        matrix[1] = (matrix[1] + cube(majority((92 - hi(aux[4])) and 0xFF, (work[105]).inv() and 0xFF, 0xc6))) and 0xFF
        hash[19] = (hash[19] xor (((224 or (s(92) and 27)) * m(41) / 3) and 0xFF)) and 0xFF
        work[140] = (work[140] + rol8(92, -(work[5]) and 7)) and 0xFF
        matrix[12] = (matrix[12] + majority((work[4]).inv() and 0xFF xor m(12), work[182], 192)) and 0xFF
        work[36] = (work[36] + 125) and 0xFF
        work[124] = rol8(majority(majority(work[138], hash[15], 74), h(43), 95), 4)
        val auxHash = hi(aux[9])
        aux[1] = (0x4c and (auxHash and ((s(68) shl 1) and 0xFF)).inv()) and 0xFF
        aux[2] = (
            222 - majority(
                ((work[177] + s(79)) shr 1) and 0xFF,
                (3 * work[148] / 5) and 0xFF,
                matrix[1],
            )
            ) and 0xFF
        matrix[16] = (matrix[16] + ((ma(4) and 0x60.inv() or auxHash or 8) - (rol8(work[33], 2) or 128))) and 0xFF
        hash[14] = (hash[14] xor ma(2)) and 0xFF
        work[19] = (
            work[19] + majority(
                rol8(si(h(201)), (m(112) shl 1) and 6),
                ((h(208) and 0x7c.inv()) or (h(164) and 0x7c)) / 5,
                37,
            )
            ) and 0xFF
        matrix[8] = (rol8(140, -square(s(45)) and 7) xor aux[4]) and 0xFF
        work[190] = 56
        work[53] = (((h(83) or 204) / 5).inv()) and 0xFF
        hash[13] = (hash[13] + h(41)) and 0xFF
        hash[10] = majority(ma(4), work[2], aux[2]) / 15
        aux[3] = (92 - square(0x28 or (ma(1) and (0x12 or (s(2) and 4))))) and 0xFF
        val seedBits = si(aux[4])
        matrix[13] = (matrix[13] xor seedBits) and 0xFF
        aux[6] = (92 + square(majority(m(179) - 38, aux[2], 177))) and 0xFF
        val expansionBits = majority((aux[3] + (aux[4] and 74)) and 0xFF, (seedBits).inv() and 0xFF, 121)
        work[47] = (work[47] xor (m(89) + majority(expansionBits xor 0xa6, aux[4], 4))) and 0xFF
        aux[7] = (
            seedBits / 3 - ma(9) -
                (0x14 or (work[151] and (aux[4] and 0x88 or 0x62)) or (aux[4] and 0x22))
            ) and 0xFF
        val expandedSelector = expansionBits xor ((aux[4] and 0xca) shr 1) xor 75
        aux[9] = (
            aux[9] + (
                0x80 or (
                    majority(
                        aux[7],
                        work[151],
                        0x20,
                    ) and 0x64
                    ) or (seedBits and 0x44) or (ma(9) and 0x1b)
                )
            ) and 0xFF
        matrix[33] = (matrix[33] xor work[26]) and 0xFF
        matrix[30] = (((aux[9] / 3 - (aux[4] and 8.inv() or 0x13)) and 0xFF) xor h(122)) and 0xFF
        work[22] = ((m(90) and 0x1b) or 0x44) and 0xFF
        val wide = selectBits(71, matrix[expandedSelector % 35], si(aux[5]))
        matrix[18] = (matrix[18] + ((wide * wide * wide shr 1) and 0xFF)) and 0xFF
        matrix[5] = (matrix[5] - s(92)) and 0xFF
        matrix[18] = (
            matrix[18] xor (
                selectBits(aux[3], ma(3), selectBits(16, m(183), work[41])) *
                    selectBits(expandedSelector, h(59), work[17])
                )
            ) and 0xFF
        matrix[22] = (
            majority(
                selectBits(hash[14] or 28, (work[7] and 28) or 0x82, h(93)),
                rol8(ma(4), rol8(work[11], -(m(28)) and 7) and 7),
                matrix[33],
            ) + 74
            ) and 0xFF
        hash[15] = (hash[15] - majority(majority(aux[3], aux[4], 214), si(h(39) xor 217), aux[6])) and 0xFF

        val hash9 = hi(aux[9])
        val indexedHash = hi(
            (
                ((aux[4] / 3) - (aux[9] or work[22])) xor aux[6] xor
                    (((m(57) or hash9) and (0x52 or (aux[9] and 0x0d))) or ((m(57) and hash9 or aux[9]) and 0x20))
                )
                and 0xFF,
        )
        aux[6] = (square(square(h(99))) or ma(9)) and 0xFF
        aux[1] = (
            aux[1] + rol8(h(151) or s(202), h(50) and 7) +
                majority(
                    h(4),
                    ((selectBits(matrix[16], indexedHash, m(138)) + selectBits(17, work[33], s(39))) / 5) and 0xFF,
                    147,
                )
            ) and 0xFF
        aux[0] = selectBits(
            hash[10] and 7,
            ma(6) and h(209),
            selectBits(0x47, rol8(s(127), ma(6) and 7), (si(ma(5)) shl 1) and 0xFF),
        )
        val selectedSquare = selectBits(198, square(m(14)), h(145) xor aux[0])
        val seed9 = si(aux[9])
        val hash3 = hi(aux[3])
        matrix[2] =
            (matrix[2] + (((hash3 shl 1) and ((work[25] and 0x96) or (seed9 and 8))) or (seed9 and 0x40))) and 0xFF
        matrix[14] = (matrix[14] - selectBits(34, work[97], ma(3) and (aux[0] xor m(100)))) and 0xFF
        work[23] = (
            work[23] xor (
                (
                    majority(
                        majority(s(17), hash3, aux[0]),
                        work[50] / 3,
                        0x76,
                    ) shl 1
                    ) and 0xFF
                )
            ) and 0xFF
        hash[17] = 115
        hash[13] = (((majority(hi(aux[7]), work[10], 82)) shr 1 and 0x68) or (h(39) and 0x17)) and 0xFF
        matrix[33] = (matrix[33] - (work[113] and 9)) and 0xFF
        matrix[28] = (matrix[28] - ((aux[3] and 0x20.inv()) or (work[110] shr 1 and 0x20))) and 0xFF
        work[95] = si(aux[3])
        hash[15] = (
            majority((work[95] - 48) and 0xFF, (work[184]).inv() and 0xFF, 189) and
                cube(majority(aux[7], si(aux[1]), 0xaa))
            ) and 0xFF
        matrix[22] = (matrix[22] + work[183]) and 0xFF
        aux[4] = (aux[4] xor (3 * s(1))) and 0xFF
        aux[5] = (aux[5] + 198 * majority(s(178), ma(1), 209) * h(13) * (s(26) shr 1)) and 0xFF
        aux[8] = selectBits(10, ma(3), ma(9))
        matrix[18] = (matrix[18] - selectBits(hash[15], aux[5] / 15, cube(hi(aux[6]) or 81))) and 0xFF
        aux[1] = (aux[1] + si(hi(aux[1])) / 3 - h(160)) and 0xFF
        hash[16] = (
            147 - majority(
                aux[0],
                majority(s(69), work[172], (aux[2] - selectedSquare + 77) and 0xFF),
                0xc2 or (aux[0] and 5),
            )
            ) and 0xFF
        hash[3] = (hash[3] - wideSeed(majority(s(155), work[105], 141), majority(s(168), h(29), 6) and 7)) and 0xFF
        work[5] = (rol8(0x38, -((h(61) / 5)) and 7) xor ((ma(8).inv() and 0xFF) / 5)) and 0xFF
        work[198] = (work[198] + work[3]) and 0xFF
        val wide2 = 162 or ma(9)
        work[164] = (work[164] + ((wide2 * wide2 / 5) and 0xFF)) and 0xFF
        aux[2] = (
            (majority(rol8(139, -(aux[5]) and 6), hi(aux[3]), 12)) or
                selectBits(95, cube(seed9), hi(aux[7]))
            ) and 0xFF
        matrix[12] = (matrix[12] + ((16 or ((work[103] or 60) and (aux[2] or (work[103] and 32)))) / 3)) and 0xFF
        work[143] = (
            work[143] - (
                0x12 or (
                    selectBits(aux[9], selectBits(matrix[8], work[35], aux[7]), aux[8] / 3) and
                        (0x4d or (work[172] shr 1 and 0x20))
                    )
                )
            ) and 0xFF
        matrix[29] = 162
        hash[15] = (
            hash[15] + majority(
                m(149) xor square(work[43]),
                selectBits(95, h(125), si(aux[1])) shr 1,
                115,
            )
            ) and 0xFF
        aux[9] = (aux[9] - hi(aux[7])) and 0xFF
        hash[7] = (hash[7] - square(rol8(ma(5), -(m(17) * (m(17) and 1))))) and 0xFF
        matrix[8] = (matrix[8] + cube(s(202)) - work[184]) and 0xFF
        hash[16] = ((m(102) shl 1) and 0x84) and 0xFF
        aux[6] = (aux[6] xor (si(aux[7]) shr 1)) and 0xFF
        hash[7] = (hash[7] - (h(191) - selectBits(177, si(si(aux[1])), (s(80) shl 1) and 0xFF))) and 0xFF
        hash[6] = h(119)
        hash[12] = (
            (hi(aux[8]) xor (m(71) + m(15))) and
                majority((work[118] and 0x2c.inv()) or 2, square(hi(aux[9])), 27)
            ) and 0xFF
        val digestIndex = (selectBits(0xa9, (s(57) * 231) and 0xFF, majority(work[32], ma(1), 23)) / 5) and 0xFF
        val seedSample = si(aux[6])
        aux[5] = (
            (
                majority(
                    (seedSample and 0x1c) or (h(82) and 0xa2) or (si(digestIndex) and 0x41),
                    majority(cube(hi(aux[7])), work[82], 92),
                    192,
                )
                ) xor digestIndex
            ) and 0xFF
        matrix[25] = (
            matrix[25] xor (
                2 * hi(aux[9]) * work[5] -
                    (rol8(aux[4], seedSample and 7) and ((aux[3] + 110) and 0xFF))
                )
            ) and 0xFF
    }
}
