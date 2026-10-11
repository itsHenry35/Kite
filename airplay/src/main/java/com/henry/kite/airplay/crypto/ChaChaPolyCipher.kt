package com.henry.kite.airplay.crypto

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

/**
 * ChaCha20-Poly1305 as HAP uses it: a 32-byte key and a 12-byte nonce made of
 * four zero bytes followed by a little-endian counter or an ASCII literal.
 *
 * BouncyCastle is used rather than javax.crypto because ChaCha20-Poly1305 only
 * reached the platform provider in API 28 with subtly different nonce handling,
 * and pair-setup needs the exact HAP layout.
 */
object ChaChaPolyCipher {
    const val TAG_LEN = 16
    const val NONCE_LEN = 12

    /** Nonce from an ASCII literal such as "PS-Msg05", right-aligned in 12 bytes. */
    fun nonce(literal: String): ByteArray {
        val ascii = literal.toByteArray()
        require(ascii.size <= NONCE_LEN) { "Nonce literal too long: $literal" }
        return ByteArray(NONCE_LEN).also { ascii.copyInto(it, NONCE_LEN - ascii.size) }
    }

    /** Nonce from a stream counter: 4 zero bytes + 8-byte little-endian counter. */
    fun nonce(counter: Long): ByteArray {
        val out = ByteArray(NONCE_LEN)
        var v = counter
        for (i in 4 until NONCE_LEN) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return out
    }

    /** Returns ciphertext with the 16-byte Poly1305 tag appended. */
    fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray =
        process(true, key, nonce, plaintext, aad)

    /** Expects ciphertext with the tag appended; throws if the tag does not verify. */
    fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray =
        process(false, key, nonce, ciphertext, aad)

    private fun process(
        forEncryption: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == 32) { "ChaCha20-Poly1305 needs a 32-byte key, got ${key.size}" }
        require(nonce.size == NONCE_LEN) { "Nonce must be $NONCE_LEN bytes, got ${nonce.size}" }

        val engine = ChaCha20Poly1305()
        engine.init(forEncryption, AEADParameters(KeyParameter(key), TAG_LEN * 8, nonce, aad))
        val out = ByteArray(engine.getOutputSize(input.size))
        val written = engine.processBytes(input, 0, input.size, out, 0)
        val total = written + engine.doFinal(out, written)
        return if (total == out.size) out else out.copyOf(total)
    }
}
