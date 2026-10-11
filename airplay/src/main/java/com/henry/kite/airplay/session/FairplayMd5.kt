package com.henry.kite.airplay.session

/**
 * FairPlay's modified MD5 compression (doubletake fairplay_md5.go): standard
 * rounds and constants, but message words are read big-endian and the message
 * schedule mutates after round 31 — crypto/Mac cannot express this.
 */
internal object FairplayMd5 {

    enum class Mutation { SWAP, CYCLE, KDF }

    private val SHIFT = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    private val CONSTANT = intArrayOf(
        0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db.toInt(), 0xc1bdceee.toInt(),
        0xf57c0faf.toInt(), 0x4787c62a.toInt(), 0xa8304613.toInt(), 0xfd469501.toInt(),
        0x698098d8.toInt(), 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
        0x6b901122.toInt(), 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821.toInt(),
        0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51.toInt(), 0xe9b6c7aa.toInt(),
        0xd62f105d.toInt(), 0x02441453.toInt(), 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
        0x21e1cde6.toInt(), 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed.toInt(),
        0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9.toInt(), 0x8d2a4c8a.toInt(),
        0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122.toInt(), 0xfde5380c.toInt(),
        0xa4beea44.toInt(), 0x4bdecfa9.toInt(), 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
        0x289b7ec6.toInt(), 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05.toInt(),
        0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8.toInt(), 0xc4ac5665.toInt(),
        0xf4292244.toInt(), 0x432aff97.toInt(), 0xab9423a7.toInt(), 0xfc93a039.toInt(),
        0x655b59c3.toInt(), 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
        0x6fa87e4f.toInt(), 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1.toInt(),
        0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb.toInt(), 0xeb86d391.toInt(),
    )

    internal fun compressNoMutate(state: IntArray, block: IntArray): IntArray = compress(state, block, null)

    /** Compresses one 64-byte block into [state]; returns the new state. */
    fun compress(state: IntArray, block: IntArray): IntArray = compress(state, block, null)

    /** Compresses one 64-byte block into [state]; returns the new state. */
    internal fun compress(state: IntArray, block: IntArray, mutation: Mutation?): IntArray {
        val message = IntArray(16)
        for (i in 0 until 16) {
            message[i] = (
                (block[i * 4] shl 24) or (block[i * 4 + 1] shl 16) or
                    (block[i * 4 + 2] shl 8) or block[i * 4 + 3]
                )
        }

        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        for (round in 0 until 64) {
            val f: Int
            val word: Int
            when {
                round < 16 -> {
                    f = (b and c) or (b.inv() and d)
                    word = round
                }

                round < 32 -> {
                    f = (d and b) or (d.inv() and c)
                    word = (5 * round + 1) and 15
                }

                round < 48 -> {
                    f = b xor c xor d
                    word = (3 * round + 5) and 15
                }

                else -> {
                    f = c xor (b or d.inv())
                    word = (7 * round) and 15
                }
            }

            val sum = a + f + CONSTANT[round] + message[word]
            val rotated = (sum shl SHIFT[round]) or (sum ushr (32 - SHIFT[round]))
            val newB = b + rotated
            a = d
            d = c
            c = b
            b = newB

            if (round == 31 && mutation != null) mutateMessage(message, a, b, c, d, mutation)
        }

        return intArrayOf(state[0] + a, state[1] + b, state[2] + c, state[3] + d)
    }

    private fun mutateMessage(message: IntArray, a: Int, b: Int, c: Int, d: Int, mutation: Mutation) {
        fun swap(i: Int, j: Int) {
            val tmp = message[i]
            message[i] = message[j]
            message[j] = tmp
        }
        when (mutation) {
            Mutation.SWAP -> {
                val indices = intArrayOf(
                    a and 15,
                    b and 15,
                    c and 15,
                    d and 15,
                    (a shr 4) and 15,
                    (b shr 4) and 15,
                    (c shr 4) and 15,
                    (d shr 4) and 15,
                )
                for ((i, j) in indices.withIndex()) swap(i, j)
            }

            Mutation.CYCLE -> {
                val indices = intArrayOf(
                    a and 15,
                    b and 15,
                    c and 15,
                    d and 15,
                    (a shr 4) and 15,
                    (b shr 4) and 15,
                    (c shr 4) and 15,
                    (d shr 4) and 15,
                )
                val first = message[indices[0]]
                for (i in 0 until indices.size - 1) message[indices[i]] = message[indices[i + 1]]
                message[indices[indices.size - 1]] = first
            }

            Mutation.KDF -> {
                swap(a and 15, b and 15)
                swap(c and 15, d and 15)
                var shift = 4
                while (shift <= 12) {
                    swap((a shr shift) and 15, (b shr shift) and 15)
                    shift += 4
                }
            }
        }
    }

    fun wordsFromLittleEndian(input: IntArray): IntArray = IntArray(4) { i ->
        (input[i * 4] and 0xFF) or
            ((input[i * 4 + 1] and 0xFF) shl 8) or
            ((input[i * 4 + 2] and 0xFF) shl 16) or
            ((input[i * 4 + 3] and 0xFF) shl 24)
    }

    fun wordsBigEndian(words: IntArray): IntArray {
        val out = IntArray(16)
        for (i in words.indices) {
            out[i * 4] = (words[i] ushr 24) and 0xFF
            out[i * 4 + 1] = (words[i] ushr 16) and 0xFF
            out[i * 4 + 2] = (words[i] ushr 8) and 0xFF
            out[i * 4 + 3] = words[i] and 0xFF
        }
        return out
    }
}
