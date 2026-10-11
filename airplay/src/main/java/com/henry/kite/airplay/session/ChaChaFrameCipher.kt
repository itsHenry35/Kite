package com.henry.kite.airplay.session

import com.henry.kite.airplay.crypto.ChaChaPolyCipher
import com.henry.kite.airplay.crypto.HkdfSha512
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Frame cipher for the modern (PD-paired) mirroring data channel
 * (doubletake `chachaCipher` + `deriveChaChaKey`).
 *
 * Key = HKDF-SHA512(pair-verify ECDH secret, salt = "DataStream-Salt<id>",
 * info = "DataStream-Output-Encryption-Key"), 32 bytes. Each frame is sealed
 * with ChaCha20-Poly1305, the full 128-byte mirroring header as AAD, and a
 * 12-byte nonce laid out for the receiver's 64x64 counter variant:
 * [0,0,0,0] + little-endian uint64 frame counter.
 *
 * Not thread-safe; one writer loop owns it.
 */
class ChaChaFrameCipher(sharedSecret: ByteArray, streamConnectionId: Long) {

    private val key = HkdfSha512.derive(
        inputKeyMaterial = sharedSecret,
        salt = "DataStream-Salt$streamConnectionId",
        info = INFO,
        length = 32,
    )
    private var nonceCounter = 0L

    val overhead: Int get() = 16 // Poly1305 tag

    /** Seals [plaintext] (the AVCC access unit) under [header] as AAD. */
    fun seal(plaintext: ByteArray, header: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(4, nonceCounter)
        }.array()
        nonceCounter++
        return ChaChaPolyCipher.seal(key, nonce, plaintext, header)
    }

    companion object {
        private const val INFO = "DataStream-Output-Encryption-Key"

        /** PTP timeline for the header's [40:48] field: the receiver's ClockID. */
        fun dataStreamSaltPreview(streamConnectionId: Long): String = "DataStream-Salt$streamConnectionId"
    }
}
