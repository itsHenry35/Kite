package com.henry.kite.airplay.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA512 (RFC 5869). HAP derives every transport key this way, always with
 * an ASCII salt/info pair and a 32-byte output for ChaCha20-Poly1305.
 */
object HkdfSha512 {
    private const val ALGORITHM = "HmacSHA512"
    private const val HASH_LEN = 64

    fun derive(inputKeyMaterial: ByteArray, salt: String, info: String, length: Int = 32): ByteArray =
        derive(inputKeyMaterial, salt.toByteArray(), info.toByteArray(), length)

    fun derive(inputKeyMaterial: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LEN)) { "Invalid HKDF output length: $length" }

        // Extract
        val prkMac = Mac.getInstance(ALGORITHM).apply {
            init(SecretKeySpec(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ALGORITHM))
        }
        val prk = prkMac.doFinal(inputKeyMaterial)

        // Expand
        val out = ByteArray(length)
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(prk, ALGORITHM)) }
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - offset)
            previous.copyInto(out, offset, 0, take)
            offset += take
            counter++
        }
        return out
    }
}
