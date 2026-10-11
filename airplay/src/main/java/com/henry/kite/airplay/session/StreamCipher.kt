package com.henry.kite.airplay.session

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts the mirroring video payload with AES-128-CTR.
 *
 * Derivation matches UxPlay's `mirror_buffer_init_aes` exactly:
 *
 *     key = SHA-512("AirPlayStreamKey" + streamConnectionID + aeskey)[0..15]
 *     iv  = SHA-512("AirPlayStreamIV" + streamConnectionID + aeskey)[0..15]
 *
 * where `aeskey` is the FairPlay-decrypted key (from `ekey`) and
 * `streamConnectionID` is rendered as a decimal string. The CTR counter runs
 * continuously across the whole stream.
 */
class StreamCipher private constructor(private val cipher: Cipher) {
    /** CTR is a stream cipher; one instance preserves the counter. */
    fun encrypt(payload: ByteArray): ByteArray = cipher.update(payload)

    companion object {
        private const val AES_KEY_BYTES = 16

        fun forAudioKey(audioKey: ByteArray, streamConnectionId: Long): StreamCipher {
            require(audioKey.size == AES_KEY_BYTES) { "Audio AES key must be 16 bytes" }
            val key = derive("AirPlayStreamKey", audioKey, streamConnectionId)
            val iv = derive("AirPlayStreamIV", audioKey, streamConnectionId)
            return StreamCipher(
                Cipher.getInstance("AES/CTR/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                },
            )
        }

        private fun derive(label: String, audioKey: ByteArray, streamConnectionId: Long): ByteArray {
            val digest = MessageDigest.getInstance("SHA-512")
            digest.update(label.toByteArray(Charsets.US_ASCII))
            digest.update(streamConnectionId.toString().toByteArray(Charsets.US_ASCII))
            digest.update(audioKey)
            return digest.digest().copyOf(AES_KEY_BYTES)
        }
    }
}
