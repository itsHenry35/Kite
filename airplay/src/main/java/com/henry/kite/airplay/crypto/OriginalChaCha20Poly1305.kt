package com.henry.kite.airplay.crypto

import org.bouncycastle.crypto.macs.Poly1305
import org.bouncycastle.crypto.params.KeyParameter

/**
 * The original (draft-agl) ChaCha20-Poly1305 AEAD used by AirPlay screen audio
 * ("chacha20-poly1305-64x64"): an 8-byte nonce, a 64-bit block counter, and the
 * Poly1305 key taken from the counter-0 block. Neither Android's nor Bouncy
 * Castle's IETF-only AEADs produce this layout, so the core function lives here.
 */
class OriginalChaCha20Poly1305(private val key: ByteArray, private val nonce: ByteArray) {

    init {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == 8) { "Original ChaCha20 nonce must be 8 bytes" }
    }

    /** Seals [plaintext] under [aad]; returns ciphertext with the 16-byte tag appended. */
    fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray = sealRegion(aad, 0, plaintext)

    /**
     * Variant for compound audio payloads whose leading bytes stay clear
     * (doubletake buildAudioPacketWithClearPrefix): only [plaintext] from
     * [clearPrefix] onward is... in this port [plaintext] must already be the
     * protected region; the AAD covers the same header bytes either way.
     */
    fun sealRegion(aad: ByteArray, clearPrefix: Int, plaintext: ByteArray): ByteArray {
        require(clearPrefix in 0..plaintext.size) { "clearPrefix out of range" }
        val protected = plaintext.copyOfRange(clearPrefix, plaintext.size)
        // RFC 2198 keeps its block headers clear; the encrypted region is the
        // marker + frames laid out in [plaintext] from clearPrefix onward.
        val out = ByteArray(clearPrefix + protected.size + 16)
        plaintext.copyInto(out, 0, 0, clearPrefix)
        chaCha20XorInto(
            out,
            clearPrefix,
            plaintext,
            clearPrefix,
            protected.size,
            key,
            nonce,
            1,
        )

        // draft-agl MAC input: aad | zero-pad to 16 | CIPHERTEXT | zero-pad to
        // 16 | le64(aad.size) | le64(ct.size). All three details are
        // load-bearing and none are guessable from this code alone: the tag
        // covers the ciphertext (not the plaintext), the aad region is padded
        // to the boundary just like the ciphertext, and the sizes are the
        // unpadded lengths. The reference construction is
        // aead/chacha20poly1305 — what the receivers interoperate with.
        val aadPad = pad16(aad.size)
        val cipherPad = pad16(protected.size)
        val macData = ByteArray(aad.size + aadPad + protected.size + cipherPad + 16)
        aad.copyInto(macData, 0)
        out.copyInto(macData, aad.size + aadPad, clearPrefix, clearPrefix + protected.size)
        writeLe64(macData, macData.size - 16, aad.size.toLong())
        writeLe64(macData, macData.size - 8, protected.size.toLong())

        val macKey = ByteArray(32)
        chaCha20Xor(macKey, ByteArray(32), key, nonce, 0)
        val tag = Poly1305().apply { init(KeyParameter(macKey)) }.run {
            update(macData, 0, macData.size)
            ByteArray(16).also { doFinal(it, 0) }
        }
        tag.copyInto(out, clearPrefix + protected.size)
        return out
    }

    private fun pad16(n: Int) = (16 - (n % 16)) % 16

    private fun writeLe64(dst: ByteArray, pos: Int, value: Long) {
        for (i in 0 until 8) dst[pos + i] = (value ushr (8 * i)).toByte()
    }

    private companion object {
        fun chaCha20XorInto(
            out: ByteArray,
            outOffset: Int,
            input: ByteArray,
            inputOffset: Int,
            length: Int,
            key: ByteArray,
            nonce: ByteArray,
            initialCounter: Long,
        ) {
            val state = IntArray(16)
            state[0] = 0x61707865
            state[1] = 0x3320646e
            state[2] = 0x79622d32
            state[3] = 0x6b206574
            for (i in 0 until 8) {
                state[4 + i] = littleEndianInt(key, i * 4)
            }
            state[12] = initialCounter.toInt()
            state[13] = ((initialCounter shr 32) and 0xFFFFFFFFL).toInt()
            state[14] = littleEndianInt(nonce, 0)
            state[15] = littleEndianInt(nonce, 4)

            var counter = initialCounter
            var done = 0
            while (done < length) {
                val block = chaCha20Block(state, counter)
                val n = minOf(64, length - done)
                for (i in 0 until n) {
                    out[outOffset + done + i] =
                        (input[inputOffset + done + i].toInt() xor (block[i].toInt() and 0xFF)).toByte()
                }
                counter++
                done += n
            }
        }

        fun chaCha20Xor(out: ByteArray, input: ByteArray, key: ByteArray, nonce: ByteArray, initialCounter: Long) {
            chaCha20XorInto(out, 0, input, 0, input.size, key, nonce, initialCounter)
        }

        fun chaCha20Block(state: IntArray, counter: Long): ByteArray {
            val x = state.copyOf()
            val counterLo = (counter and 0xFFFFFFFFL).toInt()
            val counterHi = ((counter shr 32) and 0xFFFFFFFFL).toInt()
            x[12] = counterLo
            x[13] = counterHi
            repeat(10) {
                quarterRound(x, 0, 4, 8, 12)
                quarterRound(x, 1, 5, 9, 13)
                quarterRound(x, 2, 6, 10, 14)
                quarterRound(x, 3, 7, 11, 15)
                quarterRound(x, 0, 5, 10, 15)
                quarterRound(x, 1, 6, 11, 12)
                quarterRound(x, 2, 7, 8, 13)
                quarterRound(x, 3, 4, 9, 14)
            }
            // The feed-forward adds THIS block's counter words back, not the
            // initial counter: state[12..13] still holds the initial value, so
            // adding it would skew word 12 of every block past the first by
            // (counter - initialCounter) — a corruption invisible to any
            // payload shorter than 113 bytes.
            val out = ByteArray(64)
            for (i in 0 until 16) {
                val base = when (i) {
                    12 -> counterLo
                    13 -> counterHi
                    else -> state[i]
                }
                val v = x[i] + base
                out[i * 4] = v.toByte()
                out[i * 4 + 1] = (v ushr 8).toByte()
                out[i * 4 + 2] = (v ushr 16).toByte()
                out[i * 4 + 3] = (v ushr 24).toByte()
            }
            return out
        }

        fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]
            x[d] = (x[d] xor x[a]).rotateLeft(16)
            x[c] += x[d]
            x[b] = (x[b] xor x[c]).rotateLeft(12)
            x[a] += x[b]
            x[d] = (x[d] xor x[a]).rotateLeft(8)
            x[c] += x[d]
            x[b] = (x[b] xor x[c]).rotateLeft(7)
        }

        fun littleEndianInt(src: ByteArray, offset: Int): Int =
            (src[offset].toInt() and 0xFF) or ((src[offset + 1].toInt() and 0xFF) shl 8) or
                ((src[offset + 2].toInt() and 0xFF) shl 16) or ((src[offset + 3].toInt() and 0xFF) shl 24)
    }
}
