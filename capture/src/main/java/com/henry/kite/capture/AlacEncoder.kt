package com.henry.kite.capture

/**
 * Pure-software ALAC encoder for the screen-audio transport: one explicitly
 * sized 352-sample stereo 16-bit frame per call, matching AirPlay's ct=2
 * (spf=352) shape.
 *
 * Ported from omarroth/doubletake `internal/airplay/alac_compressed.go` and the
 * verbatim writer in its `audio.go` (LGPL-3.0, see LICENSE). The frame always
 * starts with the ALAC channel-pair element header (first byte 0x20), which is
 * what Apple-compatiable receivers validate on.
 *
 * The encoder tries four stereo-decorrelation weights plus the uncorrelated
 * form, keeps the smallest compressed result, and falls back to the verbatim
 * (uncompressed) element when noise makes that cheaper.
 */
class AlacEncoder {

    private val pcm = Array(CHANNELS) { IntArray(FRAME_SAMPLES) }
    private val coded = Array(CHANNELS) { IntArray(FRAME_SAMPLES) }
    private val residual = Array(CHANNELS) { IntArray(FRAME_SAMPLES) }
    private val candidate = IntArray(FRAME_SAMPLES)
    private val alternate = ByteArray(MAX_FRAME_BYTES)

    /**
     * Encodes one [pcmBytes] frame (352 samples, interleaved stereo S16LE,
     * 1408 bytes) into [out]; returns the encoded length. The frame is at most
     * [MAX_FRAME_BYTES] bytes, far below the receivers' 1023-byte redundancy
     * block limit for ordinary content.
     */
    fun encode(out: ByteArray, pcmBytes: ByteArray): Int {
        require(pcmBytes.size == FRAME_SAMPLES * CHANNELS * 2) {
            "ALAC encoder received a non-352-sample stereo PCM frame"
        }
        require(out.size >= MAX_FRAME_BYTES) { "ALAC encoder output buffer is too small" }

        for (sample in 0 until FRAME_SAMPLES) {
            val offset = sample * CHANNELS * 2
            pcm[0][sample] = leInt16(pcmBytes, offset)
            pcm[1][sample] = leInt16(pcmBytes, offset + 2)
        }

        var encodedBytes = encodeStereoMode(out, 0)
        for (weight in 1..(1 shl STEREO_SHIFT)) {
            val candidateBytes = encodeStereoMode(alternate, weight)
            if (candidateBytes < encodedBytes) {
                System.arraycopy(alternate, 0, out, 0, candidateBytes)
                encodedBytes = candidateBytes
            }
        }

        // Noise can cost more than ALAC's verbatim element. Keep the smaller valid
        // representation while ordinary audio uses the compressed form.
        val verbatimBytes =
            (3 + 4 + 12 + 4 + 32 + FRAME_SAMPLES * CHANNELS * SAMPLE_BITS + 3 + 7) / 8
        if (encodedBytes >= verbatimBytes) {
            return encodeVerbatim(out, pcmBytes)
        }
        return encodedBytes
    }

    private fun encodeStereoMode(out: ByteArray, weight: Int): Int {
        for (sample in 0 until FRAME_SAMPLES) {
            val left = pcm[0][sample]
            val right = pcm[1][sample]
            if (weight == 0) {
                coded[0][sample] = left
                coded[1][sample] = right
                continue
            }
            val difference = left - right
            coded[0][sample] = right + ((difference * weight) shr STEREO_SHIFT)
            coded[1][sample] = difference
        }

        val predictorOrders = IntArray(CHANNELS)
        for (channel in 0 until CHANNELS) {
            predictorOrders[channel] = selectPredictor(
                coded[channel],
                residual[channel],
                CODED_CHANNEL_BITS,
            )
        }

        val writer = BitWriter(out)
        writer.write(1, 3) // TYPE_CPE
        writer.write(0, 4) // elementInstanceTag
        writer.write(0, 12)
        writer.write(8, 4) // explicit sample count, no shifted bytes, compressed
        writer.write(FRAME_SAMPLES.toLong(), 32)
        writer.write(STEREO_SHIFT.toLong(), 8)
        writer.write(weight.toLong(), 8)
        writePredictorHeader(writer, predictorOrders[0])
        writePredictorHeader(writer, predictorOrders[1])
        writeEntropyBlock(writer, residual[0], CODED_CHANNEL_BITS)
        writeEntropyBlock(writer, residual[1], CODED_CHANNEL_BITS)
        writer.write(7, 3) // TYPE_END
        return writer.flush()
    }

    private fun selectPredictor(samples: IntArray, residuals: IntArray, channelBits: Int): Int {
        var bestOrder = PREDICTION_MIN_ORDER
        var bestBits = Int.MAX_VALUE
        for (order in PREDICTION_MIN_ORDER..PREDICTION_MAX_ORDER) {
            predictResiduals(samples, candidate, order)
            val bits = entropyBitCount(candidate, channelBits) + 16 + order * 16
            if (bits < bestBits) {
                bestBits = bits
                bestOrder = order
                System.arraycopy(candidate, 0, residuals, 0, candidate.size)
            }
        }
        return bestOrder
    }

    private fun writePredictorHeader(writer: BitWriter, order: Int) {
        writer.write(PREDICTION_SHIFT.toLong(), 8) // mode=0, denominator shift=4
        writer.write(((4 shl 5) or order).toLong(), 8)
        val coefficients = predictionCoefficients(order)
        for (coefficientIndex in 0 until order) {
            writer.write((coefficients[coefficientIndex].toInt() and 0xFFFF).toLong(), 16)
        }
    }

    private fun entropyBitCount(residuals: IntArray, channelBits: Int): Int {
        // Unsigned 32-bit arithmetic is carried in Long values (0..2^32-1).
        var history = HISTORY_START.toLong()
        var zeroRunAdjustment = 0L
        var encodedBits = 0
        var sample = 0
        while (sample < residuals.size) {
            val parameter = riceParameter(history)
            val divisor = (1L shl parameter.toInt()) - 1
            val folded = foldSigned(residuals[sample])
            val symbol = folded - zeroRunAdjustment
            encodedBits += entropyValueBitCount(symbol, divisor, parameter, channelBits.toLong(), true)
            sample++

            history = (
                (HISTORY_MULTIPLIER * (symbol + zeroRunAdjustment) + history) -
                    ((HISTORY_MULTIPLIER * history) shr HISTORY_FRACTION_BITS)
                ) and 0xFFFFFFFFL
            if (symbol > HISTORY_CLAMP) {
                history = HISTORY_CLAMP
            }
            zeroRunAdjustment = 0

            if (history * 4 >= HISTORY_ZERO_THRESHOLD || sample == residuals.size) {
                continue
            }
            val zeroParameter = (
                (history.toInt().countLeadingZeroBits() - 24).toLong() and 0xFFFFFFFFL
                ) + ((history + 16) shr 6)
            val zeroDivisor = ((1L shl zeroParameter.toInt()) - 1) and ((1L shl RICE_LIMIT.toInt()) - 1)
            var run = 0L
            while (sample < residuals.size && residuals[sample] == 0) {
                run++
                sample++
            }
            encodedBits += entropyValueBitCount(run, zeroDivisor, zeroParameter, 16L, false)
            if (run < 0xffffL) {
                zeroRunAdjustment = 1
            }
            history = 0
        }
        return encodedBits
    }

    private fun entropyValueBitCount(
        value: Long,
        divisor: Long,
        parameter: Long,
        escapeBits: Long,
        omitUnitSuffix: Boolean,
    ): Int {
        val prefix = value / divisor
        if (prefix >= ENTROPY_ESCAPE_PREFIX) {
            return ENTROPY_ESCAPE_PREFIX.toInt() + escapeBits.toInt()
        }
        var bits = prefix.toInt() + 1
        if (omitUnitSuffix && parameter == 1L) {
            return bits
        }
        return if (value - prefix * divisor == 0L) {
            bits + parameter.toInt() - 1
        } else {
            bits + parameter.toInt()
        }
    }

    private fun writeEntropyBlock(writer: BitWriter, residuals: IntArray, channelBits: Int) {
        var history = HISTORY_START.toLong()
        var zeroRunAdjustment = 0L
        var sample = 0
        while (sample < residuals.size) {
            val parameter = riceParameter(history)
            val divisor = (1L shl parameter.toInt()) - 1
            val folded = foldSigned(residuals[sample])
            val symbol = folded - zeroRunAdjustment
            writeEntropyValue(writer, symbol, divisor, parameter, channelBits.toLong(), true)
            sample++

            history = (
                (HISTORY_MULTIPLIER * (symbol + zeroRunAdjustment) + history) -
                    ((HISTORY_MULTIPLIER * history) shr HISTORY_FRACTION_BITS)
                ) and 0xFFFFFFFFL
            if (symbol > HISTORY_CLAMP) {
                history = HISTORY_CLAMP
            }
            zeroRunAdjustment = 0

            if (history * 4 >= HISTORY_ZERO_THRESHOLD || sample == residuals.size) {
                continue
            }
            val zeroParameter = (
                (history.toInt().countLeadingZeroBits() - 24).toLong() and 0xFFFFFFFFL
                ) + ((history + 16) shr 6)
            val zeroDivisor = ((1L shl zeroParameter.toInt()) - 1) and ((1L shl RICE_LIMIT.toInt()) - 1)
            var run = 0L
            while (sample < residuals.size && residuals[sample] == 0) {
                run++
                sample++
            }
            writeEntropyValue(writer, run, zeroDivisor, zeroParameter, 16L, false)
            if (run < 0xffffL) {
                zeroRunAdjustment = 1
            }
            history = 0
        }
    }

    /**
     * Polynomial-prediction residuals with the adaptive coefficient update the
     * ALAC decoder replays, so the residual stream needs no coefficient data
     * beyond the warm-up diff.
     */
    // Kept structurally identical to doubletake's Go original (alacPredictResiduals)
    // so the two can be diffed line by line; splitting it would break that check.
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun predictResiduals(samples: IntArray, residuals: IntArray, order: Int) {
        if (samples.isEmpty()) return
        residuals[0] = samples[0]
        val warmup = minOf(order, samples.size - 1)
        for (sample in 1..warmup) {
            residuals[sample] = narrowSigned(samples[sample] - samples[sample - 1], CODED_CHANNEL_BITS)
        }
        val coefficients = predictionCoefficients(order)
        for (sample in order + 1 until samples.size) {
            val base = samples[sample - order - 1]
            var weightedDifference = 0
            for (tap in 0 until order) {
                weightedDifference += coefficients[tap] * (samples[sample - 1 - tap] - base)
            }
            val prediction = base + ((weightedDifference + PREDICTION_HALF) shr PREDICTION_SHIFT)
            val error = narrowSigned(samples[sample] - prediction, CODED_CHANNEL_BITS)
            residuals[sample] = error
            var remaining = error
            for (tap in order - 1 downTo 0) {
                if (remaining == 0) break
                val difference = base - samples[sample - 1 - tap]
                val direction = when {
                    difference > 0 -> 1
                    difference < 0 -> -1
                    else -> 0
                }
                if (error > 0) {
                    coefficients[tap] = (coefficients[tap] - direction).toShort()
                    remaining -= (order - tap) * ((direction * difference) shr PREDICTION_SHIFT)
                    if (remaining <= 0) break
                } else {
                    coefficients[tap] = (coefficients[tap] + direction).toShort()
                    remaining -= (order - tap) * ((-direction * difference) shr PREDICTION_SHIFT)
                    if (remaining >= 0) break
                }
            }
        }
    }

    private fun predictionCoefficients(order: Int): ShortArray {
        val coefficients = ShortArray(PREDICTION_MAX_ORDER)
        var combination = 1
        for (tap in 0 until order) {
            combination = combination * (order - tap) / (tap + 1)
            var coefficient = combination * PREDICTION_SCALE
            if (tap and 1 != 0) {
                coefficient = -coefficient
            }
            coefficients[tap] = coefficient.toShort()
        }
        return coefficients
    }

    private fun riceParameter(history: Long): Long {
        // Go: bits.Len32((history >> 9) + 3) - 1. History is bounded by
        // HISTORY_CLAMP, so the width fits an Int and the 32-bit length is
        // 32 - leading zeros of that Int.
        val width = (((history shr HISTORY_FRACTION_BITS) + 3).toInt())
        val parameter = (32 - width.countLeadingZeroBits() - 1).toLong()
        return if (parameter > RICE_LIMIT) RICE_LIMIT else parameter
    }

    private fun writeEntropyValue(
        writer: BitWriter,
        value: Long,
        divisor: Long,
        parameter: Long,
        escapeBits: Long,
        omitUnitSuffix: Boolean,
    ) {
        val prefix = value / divisor
        if (prefix >= ENTROPY_ESCAPE_PREFIX) {
            repeat(ENTROPY_ESCAPE_PREFIX.toInt()) { writer.write(1, 1) }
            writer.write(value, escapeBits.toInt())
            return
        }
        repeat(prefix.toInt()) { writer.write(1, 1) }
        writer.write(0, 1)
        if (omitUnitSuffix && parameter == 1L) {
            return
        }
        val remainder = value - prefix * divisor
        if (remainder == 0L) {
            writer.write(0, parameter.toInt() - 1)
            return
        }
        writer.write(remainder + 1, parameter.toInt())
    }

    private fun narrowSigned(value: Int, width: Int): Int {
        val shift = 32 - width
        return value shl shift shr shift
    }

    private fun foldSigned(value: Int): Long =
        ((value.toLong() shl 1) and 0xFFFFFFFFL) xor ((value.toLong() shr 31) and 0xFFFFFFFFL)

    /** Verbatim (uncompressed) ALAC element — smaller than compressed for noise. */
    private fun encodeVerbatim(out: ByteArray, pcmBytes: ByteArray): Int {
        val writer = BitWriter(out)
        writer.write(1, 3) // TYPE_CPE for stereo
        writer.write(0, 4) // elementInstanceTag
        writer.write(0, 12) // unused
        writer.write(1, 1) // hasSize
        writer.write(0, 2) // extraBytes (16-bit, no shift)
        writer.write(1, 1) // verbatim
        writer.write(FRAME_SAMPLES.toLong(), 32) // numSamples
        for (sample in 0 until FRAME_SAMPLES * CHANNELS) {
            val offset = sample * 2
            val value = (pcmBytes[offset].toInt() and 0xFF) or
                ((pcmBytes[offset + 1].toInt() and 0xFF) shl 8)
            writer.write(value.toLong(), SAMPLE_BITS)
        }
        writer.write(7, 3) // TYPE_END
        return writer.flush()
    }

    /** MSB-first bit packer, ported from doubletake's audio.go bitWriter. */
    private class BitWriter(private val buf: ByteArray) {
        private var pos = 0
        private var bitBuf: Long = 0
        private var bitPos = 0

        fun write(value: Long, nbits: Int) {
            var remaining = nbits
            var bits = value
            while (remaining > 0) {
                val space = 8 - bitPos
                if (remaining <= space) {
                    bitBuf = bitBuf or ((bits and ((1L shl remaining) - 1)) shl (space - remaining))
                    bitPos += remaining
                    if (bitPos == 8) {
                        buf[pos] = bitBuf.toByte()
                        pos++
                        bitBuf = 0
                        bitPos = 0
                    }
                    return
                }
                val shift = remaining - space
                bitBuf = bitBuf or ((bits shr shift) and ((1L shl space) - 1))
                buf[pos] = bitBuf.toByte()
                pos++
                bitBuf = 0
                bitPos = 0
                remaining = shift
                bits = bits and ((1L shl shift) - 1)
            }
        }

        fun flush(): Int {
            if (bitPos > 0) {
                buf[pos] = bitBuf.toByte()
                pos++
            }
            return pos
        }
    }

    private fun leInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort().toInt()

    companion object {
        const val FRAME_SAMPLES = 352
        const val CHANNELS = 2
        const val SAMPLE_BITS = 16

        /** Output never exceeds this; receivers accept blocks up to 1023 bytes. */
        const val MAX_FRAME_BYTES = 4096

        private const val CODED_CHANNEL_BITS = SAMPLE_BITS + 1

        private const val PREDICTION_SHIFT = 4
        private const val PREDICTION_HALF = 1 shl (PREDICTION_SHIFT - 1)
        private const val PREDICTION_SCALE = 1 shl PREDICTION_SHIFT
        private const val PREDICTION_MIN_ORDER = 1
        private const val PREDICTION_MAX_ORDER = 4

        private const val STEREO_SHIFT = 2

        private const val HISTORY_START = 10L
        private const val HISTORY_MULTIPLIER = 40L
        private const val HISTORY_FRACTION_BITS = 9
        private const val HISTORY_ZERO_THRESHOLD = 1L shl HISTORY_FRACTION_BITS
        private const val HISTORY_CLAMP = 0xffffL
        private const val RICE_LIMIT = 14L
        private const val ENTROPY_ESCAPE_PREFIX = 9L
    }
}
