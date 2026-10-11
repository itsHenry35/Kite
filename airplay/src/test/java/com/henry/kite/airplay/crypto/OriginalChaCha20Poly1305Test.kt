package com.henry.kite.airplay.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden vectors generated with github.com/aead/chacha20poly1305 — the exact
 * implementation the receiver-facing captures interoperate with (doubletake
 * `newAudioChaCha64AEAD`). They lock down the draft-agl MAC layout
 * (aad | pad16 | ct | pad16 | le64 sizes), the counter-0 Poly1305 key, the
 * counter-1 payload stream, and 8-byte little-endian nonce handling.
 *
 * Both bugs these guard against pass every self-consistent test an
 * implementation can write and still fail every real receiver: an omitted aad
 * padding block changes every tag, and a fixed seal nonce only diverges from
 * the wire nonce the receiver actually uses.
 */
class OriginalChaCha20Poly1305Test {

    private val key = ByteArray(32) { 0x42 }
    private val aad = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x00, 0x00, 0x00, 0x00)

    private fun pattern(length: Int) = ByteArray(length) { ((it * 7 + 3) and 0xFF).toByte() }

    private fun le64(value: Long) = ByteArray(8).also { nonce ->
        for (i in 0 until 8) nonce[i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun alacSizedFrameUnderNonceZero() {
        val sealed = OriginalChaCha20Poly1305(key, le64(0)).seal(aad, pattern(352))
        assertEquals(
            "5a6cf53302921ef9d7e31d64a871cdd4cddd2551250339b8547fb6158b7ef513" +
                "6045d912a5de0e21f16a80691772d33f6567a8a553b79372f8e482f871bbc3c5" +
                "79479808f7e6c0e3f7201278099167b61c393c9b1044957e6f2774cfb9fcc2b2" +
                "d28e368a6498261761eb7782c292d0904f4ab1c54333f6a6960a510acb79b0e1" +
                "5851f4966db93f975b2d0684a719d9a106f78cc3dcbba7f5e9f31c1a2437a015" +
                "98ee560f345abd18c5c1faf4b6899594afb208eb1c176399810dded7652e7669" +
                "4a6ea7bea1bb21dbd714abe55aae125a99ad51ab5b6a6f253e54e51de1912410" +
                "b91fa140cd4fa59c7694f504836cdef10de305c1b8d0bdd50675ca134a74e470" +
                "16e67a59a73cfe410ad2eda5d96bc03a27c66d4bbae5b9cc4001c07b5e8a7109" +
                "55fe9c1a51d25ee5d75607461d8c50af72e0e08d185a20bad47c1426a1fa3aae" +
                "e9dd59e7ffbe4abd2ae7d9879303523f26cc59d5c1f127c2b19c8ec30446241c" +
                "dfe2b96b4f9a82462fc6b7135360f1b6",
            sealed.hex(),
        )
    }

    @Test
    fun nonceCounterChangesTheSeal() {
        // The wire nonce is a per-frame counter; the receiver decrypts with the
        // trailing counter value, so sealing it as a constant breaks every
        // frame after the first.
        val sealed = OriginalChaCha20Poly1305(key, le64(1)).seal(aad, pattern(352))
        assertEquals(
            "8e82b1061c7c749a92095ea116814ecddb26681589b52108e8624a12919cfdb6" +
                "3015585cd9ab4f1caad5d16cc3378b40ebd01d50c3f3f5fffc17892f4225a3cb" +
                "01f8de1cb4646e624973f032aa0a02686038d63b44273bce5f2e0e4680d85067" +
                "d71cb9ab43bacdaeac0ed82c8b5fdfed876be1059fb5f16524253c04e02cdff0" +
                "7288c1a07c13444b707efd72f6236fe91dae57541172ecdb37d9652899499d11" +
                "39de594e9816cdd288b93cb9184f1adaf5e21666d5045d94eaeed1cdab2abccd" +
                "aa079574e7999b5a02e603e0a0e6b8a0af1d7e45e755ec0a4e85c0fb291daa56" +
                "c74e08f3f7836ff1ebd6af32bb53a6d1aa43e83bbf7e13d7d62223ffa305c1ca" +
                "349729fb267cd1bb200b22618cbb38ca5fbeed4d98494bf7eb4301acf2a688f4" +
                "71f14cc1e635063a5821072e526bdb17e00f8e287fddf89c139e2a23fb9e5f5b" +
                "d7f9d4435ba7041b2ac719e6da22a909415f7cf40c3760bb958a67ba18844d19" +
                "1bc1ac531b91d1c114771d68a6349edb",
            sealed.hex(),
        )
    }

    @Test
    fun unalignedPlaintextPadsToTheBoundary() {
        // 100 bytes: the ciphertext padding branch, with a non-trivial counter.
        val sealed = OriginalChaCha20Poly1305(key, le64(0x0BAD)).seal(aad, pattern(100))
        assertEquals(
            "f2409bf88113eebd0a226d7c00b4321953d0f4a02a47634b9e2939d5e859d2b3" +
                "6b6864cc57073ea03126ad34efce3aa50791798ce341cabf72bab6e05d79b6ce" +
                "00ce66f194e0ff29bcbe487c5603783216e8662c263a0df21a93f6c49050941f" +
                "12f27a58d7fb7103ed4442c0f6725167928aedef",
            sealed.hex(),
        )
    }

    @Test
    fun shortPayloadSharesTheKeystreamPrefix() {
        val sealed = OriginalChaCha20Poly1305(key, le64(0)).seal(aad, pattern(5))
        assertEquals("5a6cf53302fee76a7175a804b572c7b9d7301f82d3", sealed.hex())
    }
}
