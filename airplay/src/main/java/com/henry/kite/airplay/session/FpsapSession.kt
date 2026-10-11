package com.henry.kite.airplay.session

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * FairPlay SAP v3 handshake (doubletake fpsap.go): the exchange modern
 * receivers validate structurally — a fixed FPLY m1, the receiver's m2 with a
 * mode and encrypted SAP, our m3 carrying the local SAP plus the white-box
 * exchange tail, and the ekey record that wraps the stream key.
 *
 * All byte values are Int in 0..255 per the project's porting convention.
 */
internal class FpsapSession(private val random: SecureRandom = SecureRandom()) {

    private val localSap = IntArray(128)
    private var remoteSap = IntArray(128)
    private var m3 = IntArray(164)
    private var hasM3 = false

    init {
        // The local SAP begins with 00 01; entropy fills the remaining 126
        // bytes (Go: io.ReadFull(entropy, localSap[2:])).
        localSap[1] = 1
        val entropy = ByteArray(126)
        random.nextBytes(entropy)
        for (i in entropy.indices) localSap[2 + i] = entropy[i].toInt() and 0xFF
    }

    /** The fixed capability record the receiver validates before answering. */
    fun message1(): IntArray {
        val m1 = newRecord(1, M1_PAYLOAD.size)
        M1_PAYLOAD.copyInto(m1, 12)
        return m1
    }

    /** Consumes the receiver's m2 and builds the 164-byte m3. */
    fun exchangeM3(m2: IntArray): IntArray {
        validateRecord(m2, messageType = 2, payloadLength = 130, what = "m2")
        check(m2[12] == 2) { "invalid m2 payload marker ${m2[12]}" }
        val mode = m2[13]
        require(mode < FairplayMessage.MESSAGE_IV.size) { "m2 selected unsupported mode $mode" }

        val record = newRecord(3, 152)
        record[12] = mode
        M3_LABEL.copyInto(record, 13)
        val body = IntArray(128)
        FairplayMessage.encryptMessage(mode, localSap, body)
        body.copyInto(record, 16)

        val m2Ciphertext = m2.copyOfRange(14, 142)
        val decrypted = IntArray(128)
        val message = IntArray(144)
        message[12] = mode
        m2Ciphertext.copyInto(message, 16)
        FairplayMessage.decryptMessage(message, decrypted)
        remoteSap = decrypted

        val tail = exchangeForSap(localSap, remoteSap)
        tail.copyInto(record, 144)
        m3 = record
        hasM3 = true
        return record.copyOf()
    }

    /** The receiver's m4 echoes the 20-byte tail we placed in m3. */
    fun confirmM4(m4: IntArray) {
        check(hasM3) { "m3 has not been generated" }
        validateRecord(m4, messageType = 4, payloadLength = 20, what = "m4")
        check(m4.size == 32) { "invalid m4 length ${m4.size}" }
        for (i in 0 until 20) {
            check(m4[12 + i] == m3[144 + i]) { "m4 confirmation does not match m3" }
        }
    }

    /** Builds the 72-byte ekey record that carries [rawKey] in the SETUP. */
    fun wrapKey(rawKey: ByteArray): ByteArray {
        check(hasM3) { "m3 has not been generated" }
        validateRecord(m3, messageType = 3, payloadLength = 152, what = "m3")
        val mode = m3[12]
        require(mode < FairplayMessage.MESSAGE_IV.size) { "unsupported FairPlay mode $mode" }

        val ekey = ByteArray(72)
        EKEY_HEADER.copyInto(ekey)
        val mask = ByteArray(16).also(random::nextBytes)
        mask.copyInto(ekey, 16)
        ekey[35] = 16 // big-endian raw-key length

        val wrappingKey = deriveWrappingKey(remoteSap, m3)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrappingKey, "AES"))
        val masked = ByteArray(16) { i -> ((rawKey[i].toInt() and 0xFF) xor (ekey[16 + i].toInt() and 0xFF)).toByte() }
        cipher.doFinal(masked).copyInto(ekey, 56)

        val senderSap = IntArray(128)
        FairplayMessage.decryptMessage(m3, senderSap)
        val macKeyBytes = descriptorForSap(senderSap, remoteSap)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(macKeyBytes.toByteRange(0, 20), "HmacSHA1"))
        mac.update(ekey, 0, 36)
        mac.update(rawKey)
        mac.doFinal().copyInto(ekey, 36, 0, 20)
        return ekey
    }

    /**
     * doubletake deriveFairPlayWrappingKey: the AES key that unwraps the
     * masked stream key inside the ekey record.
     */
    private fun deriveWrappingKey(receiverSap: IntArray, m3Record: IntArray): ByteArray {
        val decrypted = IntArray(128)
        FairplayMessage.decryptMessage(m3Record, decrypted)

        val material = IntArray(320)
        var offset = 0
        KDF_PREFIX.copyInto(material, offset)
        offset += KDF_PREFIX.size
        decrypted.copyInto(material, offset)
        offset += decrypted.size
        receiverSap.copyInto(material, offset)
        offset += receiverSap.size
        KDF_SUFFIX.copyInto(material, offset)
        offset += KDF_SUFFIX.size
        material[offset] = 0x80
        putLittleEndianLong(material, material.size - 8, offset.toLong() * 8)

        var state = FairplayMd5.wordsFromLittleEndian(INITIAL_SESSION_KEY)
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
        val words = FairplayMd5.wordsBigEndian(state)
        return ByteArray(16) { words[it].toByte() }
    }

    /** doubletake fpsapDescriptorForSAP + fpsapExchangeForSAP. */
    private fun descriptorForSap(m3Sap: IntArray, m2Sap: IntArray): IntArray {
        val padded = IntArray(320)
        var offset = 0
        DESCRIPTOR_PREFIX.copyInto(padded, offset)
        offset += DESCRIPTOR_PREFIX.size
        m3Sap.copyInto(padded, offset)
        offset += m3Sap.size
        m2Sap.copyInto(padded, offset)
        offset += m2Sap.size
        DESCRIPTOR_SUFFIX.copyInto(padded, offset)
        offset += DESCRIPTOR_SUFFIX.size
        padded[offset] = 0x80
        putLittleEndianLong(padded, padded.size - 8, offset.toLong() * 8)

        var state = FairplayMd5.wordsFromLittleEndian(INITIAL_SESSION_KEY)
        var firstFinal = IntArray(4)
        var blockStart = 0
        while (blockStart < padded.size) {
            val block = padded.copyOfRange(blockStart, blockStart + 64)
            val sapHash = FairplaySap.hash(block)
            val mixed = IntArray(4)
            for (i in 0 until 4) {
                mixed[i] = state[i] + littleEndianWord(sapHash, i * 4)
            }
            var next = FairplayMd5.compress(mixed, block, FairplayMd5.Mutation.CYCLE)
            if (blockStart == padded.size - 64) {
                firstFinal = next.copyOf()
                next = FairplayMd5.compress(next, block, FairplayMd5.Mutation.CYCLE)
            }
            state = next
            blockStart += 64
        }

        val out = IntArray(20)
        out[0] = (firstFinal[0] ushr 24) and 0xFF
        out[1] = (firstFinal[0] ushr 16) and 0xFF
        out[2] = (firstFinal[0] ushr 8) and 0xFF
        out[3] = firstFinal[0] and 0xFF
        FairplayMd5.wordsBigEndian(state).copyInto(out, 4)
        return out
    }

    /** doubletake fpsapMasks. */
    private fun masks(seed: IntArray): Array<IntArray> {
        val state = intArrayOf(0x1d4a4587, 0x92f39fcc.toInt(), 0x1d87d836, 0xcdc86697.toInt())
        val suffix = intArrayOf(
            0x57, 0xd8, 0xee, 0xcb, 0xde, 0xfb, 0xcf, 0x59,
            0x1c, 0x27, 0xa2, 0xcf, 0xbe, 0xb0, 0x89,
        )
        return Array(9) { i ->
            val block = IntArray(64)
            seed.copyInto(block)
            block[20] = i
            suffix.copyInto(block, 21)
            block[36] = 0x80
            putLittleEndianWord(block, 56, 0x320)
            FairplayMd5.wordsBigEndian(FairplayMd5.compress(state, block, FairplayMd5.Mutation.SWAP))
        }
    }

    /** doubletake fpsapDigest32. */
    private fun digest32(left: IntArray, right: IntArray): IntArray {
        val block = IntArray(64)
        left.copyInto(block)
        right.copyInto(block, 16)
        block[32] = 0x80
        putLittleEndianWord(block, 56, 0x100)
        val state = intArrayOf(0xb9f3dcdc.toInt(), 0xfbdc740b.toInt(), 0x60f77f86, 0x51907216)
        return FairplayMd5.wordsBigEndian(FairplayMd5.compress(state, block, FairplayMd5.Mutation.SWAP))
    }

    /** doubletake fpsapFirstNetwork. */
    private fun firstNetwork(masks: Array<IntArray>): IntArray {
        val state = IntArray(16) { FIXED_BLOCK[it] xor FPSAP_FIRST_INPUT_MASK[it] }
        for (bank in 0 until 9) {
            val substituted = IntArray(16)
            for (output in substituted.indices) {
                val input = FIRST_POSITION_MAP[output]
                substituted[output] = FPSAP_FIRST_TABLES.roundSubstitution[bank][input].substitute(state[input])
            }
            mix(FPSAP_FIRST_TABLES, state, substituted)
            for (i in state.indices) state[i] = state[i] xor masks[bank][i]
        }
        val out = IntArray(16)
        for (output in out.indices) {
            val input = FIRST_POSITION_MAP[output]
            out[output] = FPSAP_FIRST_TABLES.finalSubstitution[input].substitute(state[input])
        }
        return out
    }

    /** doubletake fpsapSecondNetwork. */
    private fun secondNetwork(stateIn: IntArray, masks: Array<IntArray>): IntArray {
        val state = stateIn.copyOf()
        for (bank in 8 downTo 0) {
            val substituted = IntArray(16)
            for (output in substituted.indices) {
                val input = SECOND_POSITION_MAP[output]
                substituted[output] = FPSAP_SECOND_TABLES.roundSubstitution[bank][output]
                    .substitute(state[input]) xor masks[bank][output]
            }
            mix(FPSAP_SECOND_TABLES, state, substituted)
        }
        val out = IntArray(16)
        for (output in out.indices) {
            // Go's final loop indexes state by the position-map INPUT here,
            // unlike the substitution table which is indexed by output.
            val input = SECOND_POSITION_MAP[output]
            out[output] = FPSAP_SECOND_TABLES.finalSubstitution[output]
                .substitute(state[input]) xor FPSAP_SECOND_OUTPUT_MASK[output]
        }
        return out
    }

    /** doubletake fpsapMix. */
    private fun mix(tables: FpsapNetworkTables, state: IntArray, substituted: IntArray) {
        for (word in 0 until 4) {
            val offset = word * 4
            for (outputByte in 0 until 4) {
                var mixed = 0
                for (inputByte in 0 until 4) {
                    mixed = mixed xor tables.mixColumns[inputByte][outputByte]
                        .mix(substituted[offset + inputByte])
                }
                state[offset + outputByte] = mixed
            }
        }
    }

    private fun exchangeForSap(m3Sap: IntArray, m2Sap: IntArray): IntArray =
        exchangeSeed(descriptorForSap(m3Sap, m2Sap))

    /** Golden-vector hook: decryptBody + exchangeForSap on raw inputs. */
    internal fun exchangeVector(localSap: IntArray, receiverPayload: IntArray): IntArray {
        val decrypted = IntArray(128)
        val message = IntArray(144)
        message[12] = 3
        receiverPayload.copyInto(message, 16)
        FairplayMessage.decryptMessage(message, decrypted)
        return exchangeForSap(localSap, decrypted)
    }

    /** Golden-vector hook: descriptorForSap on raw inputs. */
    internal fun descriptorVector(localSap: IntArray, receiverPayload: IntArray): IntArray {
        val decrypted = IntArray(128)
        val message = IntArray(144)
        message[12] = 3
        receiverPayload.copyInto(message, 16)
        FairplayMessage.decryptMessage(message, decrypted)
        return descriptorForSap(localSap, decrypted)
    }

    /** doubletake fpsapExchangeSeed. */
    private fun exchangeSeed(seed: IntArray): IntArray {
        val networkMasks = masks(seed)
        val intermediate = firstNetwork(networkMasks)
        val left = digest32(intermediate, FIXED_BLOCK)
        val whiteboxOutput = secondNetwork(left, networkMasks)
        val digest = digest32(left, whiteboxOutput)

        val out = IntArray(20)
        whiteboxOutput.copyInto(out, 0, 0, 4)
        digest.copyInto(out, 4, 0, 16)
        return out
    }

    internal fun debugExchangeSeed(seed: IntArray): Array<IntArray> {
        val networkMasks = masks(seed)
        val intermediate = firstNetwork(networkMasks)
        val left = digest32(intermediate, FIXED_BLOCK)
        val whiteboxOutput = secondNetwork(left, networkMasks)
        val digest = digest32(left, whiteboxOutput)
        return networkMasks + arrayOf(intermediate, left, whiteboxOutput, digest)
    }

    private fun IntArray.toByteRange(start: Int, end: Int): ByteArray {
        val out = ByteArray(end - start)
        for (i in out.indices) out[i] = this[start + i].toByte()
        return out
    }

    companion object {
        private val M1_PAYLOAD = intArrayOf(0x02, 0x00, 3, 0xbb)
        private val M3_LABEL = intArrayOf(0x8f, 0x1a, 0x9c)
        private val DESCRIPTOR_PREFIX = intArrayOf(
            0xa0, 0x44, 0x9c, 0x4d, 0x09, 0xe4, 0xbd, 0x7f, 0x6e,
            0xc5, 0xd0, 0xcc, 0x35, 0x9d, 0xa7, 0x46, 0x7a,
        )
        private val DESCRIPTOR_SUFFIX = intArrayOf(
            0x97, 0xb5, 0x0f, 0x84, 0xe2, 0x15, 0x5a, 0x9c, 0x24,
            0x99, 0x1c, 0xf4, 0x3a, 0x09, 0x63, 0x55, 0x47,
        )
        private val FIXED_BLOCK = intArrayOf(
            0xaf, 0xc2, 0x2b, 0xa0, 0x49, 0xef, 0xfc, 0xfb,
            0xfe, 0x67, 0xac, 0x5e, 0xbe, 0xf6, 0xfb, 0xcb,
        )
        private val FIRST_POSITION_MAP = intArrayOf(0, 5, 10, 15, 4, 9, 14, 3, 8, 13, 2, 7, 12, 1, 6, 11)
        private val SECOND_POSITION_MAP = intArrayOf(0, 13, 10, 7, 4, 1, 14, 11, 8, 5, 2, 15, 12, 9, 6, 3)
        private val INITIAL_SESSION_KEY = intArrayOf(
            0xdc, 0xdc, 0xf3, 0xb9, 0x0b, 0x74, 0xdc, 0xfb,
            0x86, 0x7f, 0xf7, 0x60, 0x16, 0x72, 0x90, 0x51,
        )
        private val KDF_PREFIX = intArrayOf(
            0xfa, 0x9c, 0xad, 0x4d, 0x4b, 0x68, 0x26, 0x8c,
            0x7f, 0xf3, 0x88, 0x99, 0xde, 0x92, 0x2e, 0x95, 0x1e,
        )
        private val KDF_SUFFIX = intArrayOf(
            0xec, 0x4e, 0x27, 0x5e, 0xfd, 0xf2, 0xe8, 0x30,
            0x97, 0xae, 0x70, 0xfb, 0xe0, 0x00, 0x3f, 0x1c, 0x39,
        )
        private val EKEY_HEADER = byteArrayOf(
            'F'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte(), 'Y'.code.toByte(),
            0x01, 0x02, 0x01, 0x00, 0x00, 0x00, 0x00, 0x3c.toByte(), 0x00, 0x00, 0x00, 0x00,
        )

        private fun newRecord(messageType: Int, payloadLength: Int): IntArray {
            val record = IntArray(12 + payloadLength)
            record[0] = 'F'.code
            record[1] = 'P'.code
            record[2] = 'L'.code
            record[3] = 'Y'.code
            record[4] = 3
            record[5] = 1
            record[6] = messageType
            record[7] = 0
            record[8] = (payloadLength ushr 24) and 0xFF
            record[9] = (payloadLength ushr 16) and 0xFF
            record[10] = (payloadLength ushr 8) and 0xFF
            record[11] = payloadLength and 0xFF
            return record
        }

        private fun validateRecord(record: IntArray, messageType: Int, payloadLength: Int, what: String) {
            check(record.size == 12 + payloadLength) { "$what: length ${record.size}, want ${12 + payloadLength}" }
            check(record[0] == 'F'.code && record[1] == 'P'.code && record[2] == 'L'.code && record[3] == 'Y'.code) {
                "$what: invalid magic"
            }
            check(record[4] == 3 && record[5] == 1 && record[6] == messageType && record[7] == 0) {
                "$what: invalid version/type"
            }
            val declared = (record[8] shl 24) or (record[9] shl 16) or (record[10] shl 8) or record[11]
            check(declared == payloadLength) { "$what: declared payload length $declared, want $payloadLength" }
        }

        private fun littleEndianWord(src: IntArray, offset: Int): Int =
            (src[offset] and 0xFF) or ((src[offset + 1] and 0xFF) shl 8) or
                ((src[offset + 2] and 0xFF) shl 16) or ((src[offset + 3] and 0xFF) shl 24)

        private fun putLittleEndianLong(dst: IntArray, offset: Int, value: Long) {
            for (i in 0 until 8) dst[offset + i] = ((value ushr (8 * i)) and 0xFF).toInt()
        }

        private fun putLittleEndianWord(dst: IntArray, offset: Int, value: Int) {
            for (i in 0 until 4) dst[offset + i] = (value ushr (8 * i)) and 0xFF
        }
    }
}
