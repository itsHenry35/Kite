package com.henry.kite.airplay.rtsp

import com.henry.kite.airplay.crypto.ChaChaPolyCipher
import com.henry.kite.airplay.pairing.SessionKeys
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * The ChaCha20-Poly1305 framing AirPlay switches on after pair-verify.
 *
 * Each frame is a 2-byte little-endian length, then that many bytes of
 * ciphertext, then a 16-byte tag. The length prefix doubles as the AAD, which is
 * what stops an attacker from re-cutting frame boundaries.
 *
 * Nonces are per-direction counters starting at zero, so read and write must be
 * tracked separately and neither may be reordered.
 */
class EncryptedStream(private val input: InputStream, private val output: OutputStream, keys: SessionKeys) {
    private val writeKey = keys.writeKey
    private val readKey = keys.readKey

    private var writeCounter = 0L
    private var readCounter = 0L

    fun write(plaintext: ByteArray) {
        var offset = 0
        while (offset < plaintext.size) {
            val chunkLen = minOf(MAX_FRAME, plaintext.size - offset)
            val header = byteArrayOf(
                (chunkLen and 0xFF).toByte(),
                ((chunkLen shr 8) and 0xFF).toByte(),
            )
            val sealed = ChaChaPolyCipher.seal(
                key = writeKey,
                nonce = ChaChaPolyCipher.nonce(writeCounter),
                plaintext = plaintext.copyOfRange(offset, offset + chunkLen),
                aad = header,
            )
            writeCounter++
            output.write(header)
            output.write(sealed)
            offset += chunkLen
        }
        output.flush()
    }

    /** Reads and opens exactly one frame. Blocks until the whole frame arrives. */
    fun readFrame(): ByteArray {
        val header = input.readExactly(2)
        val length = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8)
        if (length > MAX_FRAME) {
            throw RtspException("Encrypted frame claims $length bytes, over the $MAX_FRAME cap")
        }
        val payload = input.readExactly(length + ChaChaPolyCipher.TAG_LEN)
        val opened = runCatching {
            ChaChaPolyCipher.open(
                key = readKey,
                nonce = ChaChaPolyCipher.nonce(readCounter),
                ciphertext = payload,
                aad = header,
            )
        }.getOrElse {
            throw RtspException("Frame $readCounter failed to authenticate: ${it.message}")
        }
        readCounter++
        return opened
    }

    private companion object {
        /** HAP caps a frame at 1024 bytes of plaintext. */
        const val MAX_FRAME = 1024
    }
}

internal fun InputStream.readExactly(count: Int): ByteArray {
    val out = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(out, read, count - read)
        if (n < 0) throw EOFException("Connection closed after $read of $count bytes")
        read += n
    }
    return out
}
