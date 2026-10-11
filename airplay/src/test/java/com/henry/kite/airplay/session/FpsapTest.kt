package com.henry.kite.airplay.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Golden vectors lifted from doubletake's fpsap_test.go, proving the Kotlin
 * FairPlay SAP port byte-identical to the Go implementation.
 */
class FpsapTest {

    private fun hex(s: String): IntArray = IntArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16) }

    private fun IntArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun IntArray.toByteArr(): ByteArray = ByteArray(size) { this[it].toByte() }

    /** Test SAP recovered from doubletake's post-m1 emulator snapshot. */
    private val referenceLocalSap = hex(
        "0001e4e3dd688293e6fa66b95ba41768e587c65f750218ff1be21543d573cefb" +
            "087bd36e0c6363c3c8242f4abcfa6d660b801032015405eb4ab04dda7aeff38f" +
            "fb36f4cfa48f0b5d92ae363f68b45925bbe6413ab6bdc4968f548d21e67d20f" +
            "1912b6820e53f1013cde29df7350a9b9fa7c51320aea62d2949786c87642e34ba",
    )

    private val capturedM2 = hex(
        "46504c59030102000000008202034a114c26b77d4e2eec2c8f89fdb653b5b32d3576bc176816d110a14c3f53c08dbb" +
            "936183bfdfe0a4f3c12e85216003b46f738c40c54da6c436d29d1b342d63c7b314309ae79a33bb1787709ef077cbf" +
            "e4190117a3423e270fd1a2eac44da1a7934f59dc681d1b70783f228c4d077c2d495f5285c3bf8df586fc2ebfe17fb" +
            "5b65",
    )

    private fun session(): FpsapSession = FpsapSession(SecureRandom())

    @Test
    fun `m1 is the fixed capability record`() {
        assertEquals("46504c590301010000000004020003bb", session().message1().toHex())
    }

    @Test
    fun `exchange vectors match doubletake`() {
        val cases = listOf(
            "6f627565f3e77f5b5ede91beee7baf92e4241e0b" to IntArray(128),
            "dc2cc74f2ed55484f59f95b96082f0f5c017dd17" to IntArray(128) { 0xff },
            "4b911e48af23d8406368aeafbb61bfcd569e3e55" to capturedM2.copyOfRange(14, 142),
            "9bfb9556b8659c2ac94b7ef9e587d71e159ea624" to IntArray(128).also { it[0] = 0x42 },
            "150d9fa4eb456e73ba48de5779c5c996b16b3b23" to IntArray(128).also { it[63] = 0x42 },
            "a167db30424ff8890d085c0f1c92b2c5cc06fc45" to IntArray(128).also { it[64] = 0x42 },
            "d246ec5e7adc8118994b8df77146529486ac7caf" to IntArray(128).also { it[127] = 0x42 },
        )
        for ((want, payload) in cases) {
            assertEquals(want, session().exchangeVector(referenceLocalSap, payload).toHex())
        }
    }

    @Test
    fun `descriptor vectors match doubletake`() {
        assertEquals(
            "7e38958ffe4ed433743919fe7eb16376afa4eb9e",
            session().descriptorVector(referenceLocalSap, IntArray(128)).toHex(),
        )
        assertEquals(
            "ea46797d726c6a9be43ffa72385ff97ce1c54f1b",
            session().descriptorVector(referenceLocalSap, IntArray(128).also { it[0] = 1 }).toHex(),
        )
    }

    @Test
    fun `session exchange round-trips the wrapped key`() {
        val entropy = IntArray(126) { (it + 1) and 0xFF }
        val m2 = IntArray(142)
        "FPLY".forEachIndexed { i, c -> m2[i] = c.code }
        m2[4] = 3
        m2[5] = 1
        m2[6] = 2
        m2[7] = 0
        m2[8] = 0
        m2[9] = 0
        m2[10] = 0
        m2[11] = 130
        m2[12] = 2
        m2[13] = 3

        val fpsap = FpsapSession(SequentialRandom())
        assertEquals("46504c590301010000000004020003bb", fpsap.message1().toHex())
        val m3 = fpsap.exchangeM3(m2)
        assertEquals(164, m3.size)
        assertEquals("FPLY", String(m3.copyOfRange(0, 4).toByteArr(), Charsets.US_ASCII))

        // The m3 body decrypts back to the session's own SAP (00 01, 01..7e).
        val localSap = IntArray(128)
        localSap[1] = 1
        for (i in 0 until 126) localSap[2 + i] = (i + 1) and 0xFF
        val decryptedSap = IntArray(128)
        FairplayMessage.decryptMessage(m3, decryptedSap)
        assertTrue(decryptedSap.contentEquals(localSap))

        val rawKey = ByteArray(16) { it.toByte() }
        val ekey = fpsap.wrapKey(rawKey)

        // The receiver-side unwrap: derive the wrapping key from (remoteSAP, m3)
        // and decrypt the masked key out of the ekey record.
        val receiverSap = IntArray(128)
        val message = IntArray(144)
        message[12] = 3
        m2.copyOfRange(14, 142).copyInto(message, 16)
        FairplayMessage.decryptMessage(message, receiverSap)
        val wrappingKey = deriveWrappingKeyForTest(receiverSap, m3)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(wrappingKey, "AES"))
        val unwrapped = cipher.doFinal(ekey, 56, 16)
        for (i in rawKey.indices) {
            assertEquals(rawKey[i], ((unwrapped[i].toInt() and 0xFF) xor (ekey[16 + i].toInt() and 0xFF)).toByte())
        }
    }

    /**
     * Mirrors Go's two entropy sources through one stream: the session SAP
     * gets 01..7e (126 bytes), the wrapKey mask gets the 0x5a run after it.
     */
    private class SequentialRandom : SecureRandom() {
        private var used = 0
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) {
                val pos = used + i
                bytes[i] = when {
                    pos < 126 -> (pos + 1).toByte()
                    pos < 142 -> 0x5a
                    else -> 0
                }
            }
            used += bytes.size
        }
    }

    private fun deriveWrappingKeyForTest(receiverSap: IntArray, m3: IntArray): ByteArray {
        // Mirrors unwrapFairPlayKeyForTest in doubletake: recompute the wrapping
        // key through the same KDF the wrap used.
        val decrypted = IntArray(128)
        FairplayMessage.decryptMessage(m3, decrypted)
        val material = IntArray(320)
        var offset = 0
        val prefix = intArrayOf(
            0xfa, 0x9c, 0xad, 0x4d, 0x4b, 0x68, 0x26, 0x8c,
            0x7f, 0xf3, 0x88, 0x99, 0xde, 0x92, 0x2e, 0x95, 0x1e,
        )
        val suffix = intArrayOf(
            0xec, 0x4e, 0x27, 0x5e, 0xfd, 0xf2, 0xe8, 0x30,
            0x97, 0xae, 0x70, 0xfb, 0xe0, 0x00, 0x3f, 0x1c, 0x39,
        )
        prefix.copyInto(material, offset)
        offset += prefix.size
        decrypted.copyInto(material, offset)
        offset += 128
        receiverSap.copyInto(material, offset)
        offset += 128
        suffix.copyInto(material, offset)
        offset += suffix.size
        material[offset] = 0x80
        val lengthBits = offset.toLong() * 8
        for (i in 0 until 8) material[material.size - 8 + i] = ((lengthBits ushr (8 * i)) and 0xFF).toInt()

        var state = FairplayMd5.wordsFromLittleEndian(
            intArrayOf(0xdc, 0xdc, 0xf3, 0xb9, 0x0b, 0x74, 0xdc, 0xfb, 0x86, 0x7f, 0xf7, 0x60, 0x16, 0x72, 0x90, 0x51),
        )
        var blockStart = 0
        while (blockStart < material.size) {
            val block = material.copyOfRange(blockStart, blockStart + 64)
            val hashed = FairplaySap.hash(block)
            val modified = FairplayMd5.compress(state, block, FairplayMd5.Mutation.KDF)
            state = IntArray(4) { word ->
                modified[word] + (
                    hashed[word * 4] or (hashed[word * 4 + 1] shl 8) or
                        (hashed[word * 4 + 2] shl 16) or (hashed[word * 4 + 3] shl 24)
                    )
            }
            blockStart += 64
        }
        return FairplayMd5.wordsBigEndian(state).toByteArr()
    }
}
