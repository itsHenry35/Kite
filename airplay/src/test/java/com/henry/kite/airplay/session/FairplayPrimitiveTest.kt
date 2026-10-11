package com.henry.kite.airplay.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** Layer-by-layer vectors from doubletake's fairplay_vectors_test.go. */
class FairplayPrimitiveTest {

    private fun block(): IntArray = IntArray(64) { (it * 3 + 1) and 0xFF }

    @Test
    fun `modified md5 compression`() {
        val key = IntArray(16) { it and 0xFF }
        val words = FairplayMd5.compress(
            FairplayMd5.wordsFromLittleEndian(key),
            block(),
            FairplayMd5.Mutation.KDF,
        )
        // doubletake serializes this vector little-endian per word.
        val modified = IntArray(16)
        for (i in 0 until 4) {
            modified[i * 4] = words[i] and 0xFF
            modified[i * 4 + 1] = (words[i] shr 8) and 0xFF
            modified[i * 4 + 2] = (words[i] shr 16) and 0xFF
            modified[i * 4 + 3] = (words[i] shr 24) and 0xFF
        }
        assertEquals("f6f728cb5a4397b675664f9291b859aa", modified.toHex())
    }

    @Test
    fun `sap hash`() {
        assertEquals("75498a4e218773030e9cdf04f0c49367", FairplaySap.hash(block()).toHex())
    }

    @Test
    fun `message decrypt and encrypt per mode`() {
        val vectors = listOf(
            "b66a3295ffa6b56e02ed1b3d67fef74b90fe148570de65e6773669126a49" +
                "05d8405644cae0b2f5ed6109c099c7aea7398dac8d623fbd69b87242b374" +
                "d98f89502bb5a63e29c46a8ed0e98466966191ec1e6c8675087fde21337d" +
                "b1c8fab4c21db824026335f6fc37e2e5b6f53357d06994bd383d6029a0af" +
                "f654fb1521bcdde4",
            "0f95c6ddc8987eda18577da2db074e7c04715af8b3914a73be1b3d6c1119" +
                "53017ee0a39dfcab3e0d57f2f9fbd59c5e18101788c2ab8e3cbb403bcb48" +
                "b53f3e5bf74f949e79fa5ca679df4bfcb33a69b1442675d03f948fe5bd0c" +
                "5ffb64b73a5ab58f46d6baae097b599624147c2487991163ecffc4d96624" +
                "0f9526346a10fdb0",
            "40f18751b44d733e0aa0416401a7d3f40375fad3ce56900602578bca1466" +
                "0909820e6ef3a5e943cafef5370f72c52177d9b82278b414811201a3d992" +
                "02bedcca26a4d1ad08bc2669f4bae6ca54b8a120d0425edb6082f51f5aec" +
                "db547bfdb319099c9ea2729ae6a1c4480827ce9991e273843cf1c7d74ebb" +
                "ebc2657659bcea9f",
            "70a3c30edf0e1dfa1785ce4336ed547062672a47f714a0c1f89a83d95691" +
                "103dfe5cf653d4cb8299793faf33fd0d4482ef5333b41ab094a90e1baf99" +
                "6bcf4989783f6918397fbacddaf00a2b97556dd8099841578bc5eb144491" +
                "2b47298eaf356fdd6701bb3f64e725a80eb4c6f3556195de35c93e7cc703" +
                "bdd24351468e9847",
        )
        for (mode in 0 until 4) {
            val message = IntArray(164)
            message[12] = mode
            for (i in 16 until 144) message[i] = (i * 5 + 7) and 0xFF
            val decrypted = IntArray(128)
            FairplayMessage.decryptMessage(message, decrypted)
            assertEquals("mode $mode decrypt", vectors[mode], decrypted.toHex())
            val encrypted = IntArray(128)
            FairplayMessage.encryptMessage(mode, decrypted, encrypted)
            assertEquals("mode $mode encrypt", message.copyOfRange(16, 144).toHex(), encrypted.toHex())
        }
    }

    @Test
    fun `decrypt inverts encrypt`() {
        val plaintext = IntArray(128) { (it * 11 + 3) and 0xFF }
        for (mode in 0 until 4) {
            val encrypted = IntArray(128)
            FairplayMessage.encryptMessage(mode, plaintext, encrypted)
            val decrypted = IntArray(128)
            FairplayMessage.decryptMessage(
                IntArray(144).also {
                    it[12] = mode
                    encrypted.copyInto(it, 16)
                },
                decrypted,
            )
            assertEquals("mode $mode", plaintext.toHex(), decrypted.toHex())
        }
    }

    private fun IntArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
