package com.henry.kite.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Golden checks mirrored from doubletake's own alac_test.go: the first bytes of
 * a verbatim and a compressed frame, and the exact verbatim frame size. Receivers
 * dispatch on the first byte (0x20 = stereo channel-pair element), so a wrong
 * header would make every frame silently dropped.
 */
class AlacEncoderTest {

    @Test
    fun `verbatim frame starts with the CPE header and has the exact size`() {
        val out = ByteArray(AlacEncoder.MAX_FRAME_BYTES)
        val n = AlacEncoder().encode(out, independentNoisePcm())
        println("DEBUG verbatim n=$n")

        // tag(3)=1, instance(4)=0, unused(12)=0, hasSize(1)=1, extra(2)=0,
        // verbatim(1)=1 → first 23 bits = 001 0000 0000 0000 0000 0001
        assertEquals("200012000002c0", out.take(7).toHex())
        // 23 header bits + 32 sample count + 11264 data bits + 3 end bits.
        val expectedSize = (23 + 32 + 352 * 2 * 16 + 3 + 7) / 8
        assertEquals(expectedSize, n)
    }

    @Test
    fun `compressed frame starts with the CPE header and explicit-size flags`() {
        val out = ByteArray(AlacEncoder.MAX_FRAME_BYTES)
        val n = AlacEncoder().encode(out, stereoSinePcm())

        assertEquals(0x20, out[0].toInt() and 0xFF)
        // Element flags after 3+4+12 bits: hasSize=1, extra=0, verbatim=0 → 8.
        val flags = (((out[2].toInt() and 0xFF) shl 8) or (out[3].toInt() and 0xFF)) ushr 9 and 0xf
        assertEquals(8, flags)
        assertEquals("200010000002c0", out.take(7).toHex())
        // Compressed frames must stay under the receivers' 1023-byte block limit.
        assertTrue("frame too large: $n", n < 1024)
    }

    @Test
    fun `silence encodes below the redundancy block limit`() {
        val out = ByteArray(AlacEncoder.MAX_FRAME_BYTES)
        val n = AlacEncoder().encode(out, ByteArray(352 * 2 * 2))
        assertTrue(n in 1 until 1024)
        assertEquals(0x20, out[0].toInt() and 0xFF)
    }

    @Test
    fun `correlated noise compresses below the redundancy block limit`() {
        val out = ByteArray(AlacEncoder.MAX_FRAME_BYTES)
        val n = AlacEncoder().encode(out, noisePcm())
        assertTrue(n in 1 until 1024)
        assertEquals(0x20, out[0].toInt() and 0xFF)
    }

    /** Left and right channels are independent full-scale noise: incompressible. */
    private fun independentNoisePcm(): ByteArray {
        val pcm = ByteArray(352 * 2 * 2)
        var state = 0x4d595df4L
        for (sample in 0 until 352) {
            state = (state * 1664525 + 1013904223) and 0xFFFFFFFFL
            // First sample zero so the header bits are deterministic (like the
            // doubletake golden vector's 440 Hz sine, which starts at zero).
            val value = if (sample == 0) 0 else (state shr 16).toInt()
            writeLe16(pcm, sample * 4, value)
            writeLe16(pcm, sample * 4 + 2, value)
            state = (state * 1664525 + 1013904223) and 0xFFFFFFFFL
            writeLe16(pcm, sample * 4 + 2, (state shr 16).toInt())
        }
        return pcm
    }

    @Test
    fun `transient content stays under the redundancy block limit`() {
        val pcm = ByteArray(352 * 2 * 2)
        for (sample in 0 until 352) {
            val left = if (sample % 20 < 10) 20000 else -20000
            val right = if (sample % 20 < 10) -10000 else 10000
            writeLe16(pcm, sample * 4, left)
            writeLe16(pcm, sample * 4 + 2, right)
        }
        val out = ByteArray(AlacEncoder.MAX_FRAME_BYTES)
        val n = AlacEncoder().encode(out, pcm)
        assertTrue("transient frame too large: $n", n < 1024)
    }

    private fun sinePcm(): ByteArray {
        val pcm = ByteArray(352 * 2 * 2)
        for (i in 0 until 352) {
            val sample = (sin(2 * PI * 440.0 * i / 44100) * 16000).toInt().toShort().toInt()
            for (ch in 0 until 2) {
                writeLe16(pcm, (i * 2 + ch) * 2, sample)
            }
        }
        return pcm
    }

    private fun stereoSinePcm(): ByteArray {
        val pcm = ByteArray(352 * 2 * 2)
        for (i in 0 until 352) {
            val left = (sin(2 * PI * 440.0 * i / 44100) * 16000).toInt()
            val right = (sin(2 * PI * 659.25 * i / 44100) * 12000).toInt()
            writeLe16(pcm, i * 4, left)
            writeLe16(pcm, i * 4 + 2, right)
        }
        return pcm
    }

    private fun noisePcm(): ByteArray {
        val pcm = ByteArray(352 * 2 * 2)
        var state = 0x4d595df4L
        for (sample in 0 until 352) {
            state = (state * 1664525 + 1013904223) and 0xFFFFFFFFL
            val value = (state shr 16).toInt()
            // Identical on both channels: stereo decorrelation removes it all.
            writeLe16(pcm, sample * 4, value)
            writeLe16(pcm, sample * 4 + 2, value)
        }
        return pcm
    }

    private fun writeLe16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun List<Byte>.toHex(): String = joinToString("") { "%02x".format(it) }
}
