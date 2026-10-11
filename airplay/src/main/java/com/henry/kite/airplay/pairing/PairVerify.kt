package com.henry.kite.airplay.pairing

import android.util.Log
import com.henry.kite.airplay.crypto.ChaChaPolyCipher
import com.henry.kite.airplay.crypto.Ed25519Verify
import com.henry.kite.airplay.crypto.HkdfSha512
import com.henry.kite.airplay.crypto.X25519Ephemeral

/**
 * HAP pair-verify: the per-connection handshake that turns stored credentials
 * into fresh session keys. No PIN, no user interaction, runs on every connect.
 *
 *   M1 -> our ephemeral X25519 public key
 *   M2 <- receiver ephemeral public key, encrypted { its id, its signature }
 *   M3 -> encrypted { our id, our signature }
 *   M4 <- confirmation
 *
 * Each side signs `ownEphemeral || peerId || peerEphemeral` with its long-term
 * Ed25519 key, which is what binds this ephemeral exchange to the identities
 * established during pair-setup.
 *
 * [protocol] selects the framing: HKP generation header, X-Apple-PD for
 * PD-mixed stream keys, and how strictly the receiver's M2 identity is checked.
 * Transient and raw pairings never revealed the receiver's long-term key, so
 * there the successful M2 decryption is itself the proof of authenticity —
 * the same posture doubletake takes.
 */
class PairVerify(
    private val transport: PairingTransport,
    private val credentials: PairingCredentials,
    private val protocol: PairingProtocol = PairingProtocol.HAP_LEGACY,
) {
    // The receiver keys its pairing session on this identifier: it has to be
    // the same value on the wire header and inside M3, exactly as doubletake
    // keeps PairingID consistent across both.
    private val pairingHeaders = buildMap {
        protocol.hkpVersion?.let { put("X-Apple-HKP", it) }
        if (protocol.usesPdMixing) put(PairingTransport.HEADER_PD, "1")
        if (credentials.clientId.isNotEmpty()) put("X-Apple-Client-ID", credentials.clientId)
    }

    suspend fun verify(): SessionKeys {
        val ephemeral = X25519Ephemeral.generate()
        val ourEphemeralPublic = ephemeral.publicKey

        // M1: State + ephemeral public key only — no Method TLV, matching
        // doubletake and the HAP profile (a Method here makes the receiver
        // answer with state 1 instead of V2).
        val m1 = Tlv8.builder()
            .put(TlvType.STATE, STATE_M1)
            .put(TlvType.PUBLIC_KEY, ourEphemeralPublic)
            .build()

        val m2 = exchange(m1, expectedState = STATE_M2)
        val peerEphemeralPublic = m2[TlvType.PUBLIC_KEY]
            ?: throw PairingException("pair-verify M2 is missing the ephemeral public key")
        val sealedPeerId = m2[TlvType.ENCRYPTED_DATA]
            ?: throw PairingException("pair-verify M2 is missing the encrypted payload")
        Log.i(TAG, "[PAIR] pair-verify V1/V2 <- receiver ephemeral key ${peerEphemeralPublic.size}B")

        val sharedSecret = ephemeral.agree(peerEphemeralPublic)
        val encryptionKey = HkdfSha512.derive(sharedSecret, SALT_ENCRYPT, INFO_ENCRYPT)

        verifyReceiver(sealedPeerId, encryptionKey, peerEphemeralPublic, ourEphemeralPublic)

        // M3: prove we still hold the private key the receiver recorded at pair-setup.
        val identity = credentials.identity()
        val signature = identity.sign(
            ourEphemeralPublic + credentials.clientId.toByteArray() + peerEphemeralPublic,
        )
        val subTlv = Tlv8.builder()
            .put(TlvType.IDENTIFIER, credentials.clientId)
            .put(TlvType.SIGNATURE, signature)
            .build()

        val sealed = ChaChaPolyCipher.seal(
            key = encryptionKey,
            nonce = ChaChaPolyCipher.nonce(NONCE_M3),
            plaintext = subTlv.encode(),
        )
        val m3 = Tlv8.builder()
            .put(TlvType.STATE, STATE_M3)
            .put(TlvType.ENCRYPTED_DATA, sealed)
            .build()

        exchange(m3, expectedState = STATE_M4)
        Log.i(TAG, "[PAIR] pair-verify V3/V4 <- session keys derived")

        return SessionKeys(
            sharedSecret = sharedSecret,
            writeKey = HkdfSha512.derive(sharedSecret, SALT_CONTROL, INFO_WRITE),
            readKey = HkdfSha512.derive(sharedSecret, SALT_CONTROL, INFO_READ),
        )
    }

    private fun verifyReceiver(
        sealedPayload: ByteArray,
        encryptionKey: ByteArray,
        peerEphemeralPublic: ByteArray,
        ourEphemeralPublic: ByteArray,
    ) {
        val opened = runCatching {
            ChaChaPolyCipher.open(
                key = encryptionKey,
                nonce = ChaChaPolyCipher.nonce(NONCE_M2),
                ciphertext = sealedPayload,
            )
        }.getOrElse {
            // Wrong key means our stored credentials no longer match this receiver,
            // usually because it was factory reset or unpaired.
            throw PairingException("pair-verify M2 failed to decrypt; re-pairing required")
        }

        val inner = Tlv8.decode(opened)
        val peerId = inner[TlvType.IDENTIFIER]?.decodeToString()
            ?: throw PairingException("pair-verify M2 is missing the receiver identifier")
        val peerSignature = inner[TlvType.SIGNATURE]
            ?: throw PairingException("pair-verify M2 is missing the receiver signature")

        // Without a stored receiver key (transient/raw pairing) the M2 decrypt
        // already proved the peer holds the session key; skip the identity check.
        if (credentials.receiverPublicKey.isEmpty() || credentials.receiverId.isEmpty()) return

        if (peerId != credentials.receiverId) {
            throw PairingException(
                "Receiver identity changed: stored ${credentials.receiverId}, got $peerId",
            )
        }

        val signedData = peerEphemeralPublic + peerId.toByteArray() + ourEphemeralPublic
        if (!Ed25519Verify.verify(credentials.receiverPublicKey, signedData, peerSignature)) {
            throw PairingException("Receiver signature did not verify in pair-verify M2")
        }
    }

    private suspend fun exchange(request: Tlv8, expectedState: Int): Tlv8 {
        val response = Tlv8.decode(
            transport.postTlv(PairingTransport.PAIR_VERIFY, request.encode(), pairingHeaders),
        )
        response.error?.let { error ->
            throw PairingException("Receiver rejected pair-verify: $error", error)
        }
        val state = response.state
        if (state != expectedState) {
            throw PairingException("Expected pair-verify state $expectedState but got $state")
        }
        return response
    }

    private companion object {
        private const val TAG = "PairVerify"

        const val STATE_M1 = 0x01
        const val STATE_M2 = 0x02
        const val STATE_M3 = 0x03
        const val STATE_M4 = 0x04

        const val SALT_ENCRYPT = "Pair-Verify-Encrypt-Salt"
        const val INFO_ENCRYPT = "Pair-Verify-Encrypt-Info"

        // AirPlay reuses the HAP control-channel labels for its RTSP cipher.
        const val SALT_CONTROL = "Control-Salt"
        const val INFO_WRITE = "Control-Write-Encryption-Key"
        const val INFO_READ = "Control-Read-Encryption-Key"

        const val NONCE_M2 = "PV-Msg02"
        const val NONCE_M3 = "PV-Msg03"
    }
}
