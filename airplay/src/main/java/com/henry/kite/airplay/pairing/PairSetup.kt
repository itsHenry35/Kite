package com.henry.kite.airplay.pairing

import android.util.Log
import com.henry.kite.airplay.crypto.ChaChaPolyCipher
import com.henry.kite.airplay.crypto.Ed25519Identity
import com.henry.kite.airplay.crypto.Ed25519Verify
import com.henry.kite.airplay.crypto.HkdfSha512
import com.henry.kite.airplay.crypto.Srp6aClient
import java.util.UUID

/**
 * HAP pair-setup, the one-time exchange that trades the PIN on the receiver's
 * screen for a permanent Ed25519 key pair.
 *
 * Six messages, three round trips:
 *
 *   M1 -> method=PairSetup, state=1
 *   M2 <- salt, server public key B
 *   M3 -> client public key A, SRP proof M1        (PIN goes in here)
 *   M4 <- SRP proof M2
 *   M5 -> encrypted { our id, our Ed25519 public key, our signature }
 *   M6 <- encrypted { receiver id, receiver public key, receiver signature }
 *
 * Usage is two-step because the PIN is not known until the receiver displays it:
 * call [begin], show the PIN prompt, then call [complete].
 *
 * [hkpVersion] selects the X-Apple-HKP generation: type 3 for legacy receivers
 * whose configured password doubles as the SRP secret, type 5 for receivers
 * that display a code — those also require the screen-capture ACL inside M5,
 * which declares our identity is used for screen capture (doubletake:
 * "current receivers reject a screen-capture identity that omits it").
 */
class PairSetup(
    private val transport: PairingTransport,
    private val clientId: String = UUID.randomUUID().toString(),
    private val hkpVersion: String = PairingTransport.HKP_LEGACY,
    private val screenCaptureAcl: Boolean = false,
) {
    private val srp = Srp6aClient()
    private val identity = Ed25519Identity.generate()

    private var salt: ByteArray? = null
    private var serverPublicKey: ByteArray? = null

    // The receiver keys its pairing session on this identifier; the same value
    // must appear in the wire header, the M5 identity TLV, and later
    // pair-verify (doubletake keeps one PairingID everywhere).
    private val pairingHeaders = mapOf(
        "X-Apple-HKP" to hkpVersion,
        "X-Apple-Client-ID" to clientId,
    )

    /**
     * Sends M1 and consumes M2. On most receivers this is what makes the PIN
     * appear on screen, so it must run before prompting the user.
     */
    suspend fun begin() {
        val m1 = Tlv8.builder()
            .put(TlvType.STATE, STATE_M1)
            .put(TlvType.METHOD, METHOD_PAIR_SETUP)
            .build()

        Log.i(TAG, "[PAIR] pair-setup M1 -> (hkp=$hkpVersion, acl=$screenCaptureAcl)")
        val m2 = exchange(m1, expectedState = STATE_M2)
        val saltValue = m2[TlvType.SALT]
            ?: throw PairingException("pair-setup M2 is missing the salt")
        val serverKeyValue = m2[TlvType.PUBLIC_KEY]
            ?: throw PairingException("pair-setup M2 is missing the server public key")
        salt = saltValue
        serverPublicKey = serverKeyValue
        Log.i(
            TAG,
            "[PAIR] pair-setup M2 <- salt=${saltValue.size}B serverKey=${serverKeyValue.size}B",
        )
    }

    /** Sends M3 and M5, consumes M4 and M6, and returns credentials worth persisting. */
    suspend fun complete(pin: String): PairingCredentials {
        val salt = salt ?: error("begin() must be called before complete()")
        val serverPublicKey = serverPublicKey ?: error("begin() must be called before complete()")
        require(pin.isNotEmpty()) { "PIN must not be empty" }

        // M3: prove we know the PIN.
        val clientPublicKey = srp.generatePublicKey()
        val proof = srp.computeProof(salt, serverPublicKey, pin)
        val m3 = Tlv8.builder()
            .put(TlvType.STATE, STATE_M3)
            .put(TlvType.PUBLIC_KEY, clientPublicKey)
            .put(TlvType.PROOF, proof)
            .build()

        val m4 = exchange(m3, expectedState = STATE_M4)
        val serverProof = m4[TlvType.PROOF]
            ?: throw PairingException("pair-setup M4 is missing the server proof")
        if (!srp.verifyServerProof(serverProof)) {
            // Either the PIN was wrong or something is impersonating the receiver.
            Log.w(TAG, "[PAIR] pair-setup M4 SRP proof FAILED (wrong PIN or impersonation)")
            throw PairingException("Receiver SRP proof did not verify; check the PIN")
        }
        Log.i(TAG, "[PAIR] pair-setup M3/M4 <- SRP proof verified")

        // M5: hand over our long-term identity, encrypted under the SRP secret.
        val encryptionKey = HkdfSha512.derive(srp.sharedSecret, SALT_ENCRYPT, INFO_ENCRYPT)
        val signKey = HkdfSha512.derive(srp.sharedSecret, SALT_CONTROLLER, INFO_CONTROLLER)

        val signature = identity.sign(signKey + clientId.toByteArray() + identity.publicKey)
        val subTlv = Tlv8.builder()
            .put(TlvType.IDENTIFIER, clientId)
            .put(TlvType.PUBLIC_KEY, identity.publicKey)
            .put(TlvType.SIGNATURE, signature)
        if (screenCaptureAcl) {
            subTlv.put(TlvType.ACL, SCREEN_CAPTURE_ACL)
        }

        val sealed = ChaChaPolyCipher.seal(
            key = encryptionKey,
            nonce = ChaChaPolyCipher.nonce(NONCE_M5),
            plaintext = subTlv.build().encode(),
        )
        val m5 = Tlv8.builder()
            .put(TlvType.STATE, STATE_M5)
            .put(TlvType.ENCRYPTED_DATA, sealed)
            .build()

        val m6 = exchange(m5, expectedState = STATE_M6)
        Log.i(TAG, "[PAIR] pair-setup M5/M6 <- receiver identity received")
        return readReceiverIdentity(m6, encryptionKey)
    }

    /** Opens M6 and checks the receiver's self-signature before trusting its key. */
    private fun readReceiverIdentity(m6: Tlv8, encryptionKey: ByteArray): PairingCredentials {
        val sealed = m6[TlvType.ENCRYPTED_DATA]
            ?: throw PairingException("pair-setup M6 is missing the encrypted payload")

        val opened = runCatching {
            ChaChaPolyCipher.open(
                key = encryptionKey,
                nonce = ChaChaPolyCipher.nonce(NONCE_M6),
                ciphertext = sealed,
            )
        }.getOrElse { throw PairingException("pair-setup M6 failed to decrypt: ${it.message}") }

        val inner = Tlv8.decode(opened)
        val receiverId = inner[TlvType.IDENTIFIER]?.decodeToString()
            ?: throw PairingException("pair-setup M6 is missing the receiver identifier")
        val receiverPublicKey = inner[TlvType.PUBLIC_KEY]
            ?: throw PairingException("pair-setup M6 is missing the receiver public key")
        val receiverSignature = inner[TlvType.SIGNATURE]
            ?: throw PairingException("pair-setup M6 is missing the receiver signature")

        val verifyKey = HkdfSha512.derive(srp.sharedSecret, SALT_ACCESSORY, INFO_ACCESSORY)
        val signedData = verifyKey + receiverId.toByteArray() + receiverPublicKey
        if (!Ed25519Verify.verify(receiverPublicKey, signedData, receiverSignature)) {
            throw PairingException("Receiver signature did not verify in pair-setup M6")
        }

        return PairingCredentials(
            clientId = clientId,
            clientSeed = identity.seed,
            receiverPublicKey = receiverPublicKey,
            receiverId = receiverId,
            protocol = when (hkpVersion) {
                PairingTransport.HKP_SCREEN_CAPTURE -> PairingProtocol.HAP_SCREEN_CAPTURE
                else -> PairingProtocol.HAP_LEGACY
            },
        )
    }

    private suspend fun exchange(request: Tlv8, expectedState: Int): Tlv8 {
        val response = Tlv8.decode(
            transport.postTlv(PairingTransport.PAIR_SETUP, request.encode(), pairingHeaders),
        )
        response.error?.let { error ->
            // M1 carries no credentials, so an Authentication error there can
            // only mean the receiver is still throttling earlier failures —
            // macOS runs that clock for about half an hour. Fail fast with
            // the actionable message instead of a misleading code.
            val throttled = error == TlvError.BACKOFF ||
                (error == TlvError.AUTHENTICATION && expectedState == STATE_M2)
            throw PairingException(
                if (throttled) {
                    "Receiver is throttling pairing attempts; toggle its AirPlay receiver or wait before retrying"
                } else {
                    "Receiver rejected pair-setup: $error"
                },
                error,
            )
        }
        return validated(response, expectedState)
    }

    private fun validated(response: Tlv8, expectedState: Int): Tlv8 {
        val state = response.state
        if (state != expectedState) {
            throw PairingException("Expected pair-setup state $expectedState but got $state")
        }
        return response
    }

    private companion object {
        private const val TAG = "PairSetup"

        const val METHOD_PAIR_SETUP = 0x00

        const val STATE_M1 = 0x01
        const val STATE_M2 = 0x02
        const val STATE_M3 = 0x03
        const val STATE_M4 = 0x04
        const val STATE_M5 = 0x05
        const val STATE_M6 = 0x06

        const val SALT_ENCRYPT = "Pair-Setup-Encrypt-Salt"
        const val INFO_ENCRYPT = "Pair-Setup-Encrypt-Info"
        const val SALT_CONTROLLER = "Pair-Setup-Controller-Sign-Salt"
        const val INFO_CONTROLLER = "Pair-Setup-Controller-Sign-Info"
        const val SALT_ACCESSORY = "Pair-Setup-Accessory-Sign-Salt"
        const val INFO_ACCESSORY = "Pair-Setup-Accessory-Sign-Info"

        const val NONCE_M5 = "PS-Msg05"
        const val NONCE_M6 = "PS-Msg06"

        /**
         * OPACK encoding of `{"com.apple.ScreenCapture": true}` — the ACL TLV
         * value for X-Apple-HKP type 5 pairings. doubletake: "current receivers
         * reject a screen-capture identity that omits it".
         */
        val SCREEN_CAPTURE_ACL = byteArrayOf(
            0xe1.toByte(), 0x57,
            'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), '.'.code.toByte(),
            'a'.code.toByte(), 'p'.code.toByte(), 'p'.code.toByte(), 'l'.code.toByte(),
            'e'.code.toByte(), '.'.code.toByte(), 'S'.code.toByte(), 'c'.code.toByte(),
            'r'.code.toByte(), 'e'.code.toByte(), 'e'.code.toByte(), 'n'.code.toByte(),
            'C'.code.toByte(), 'a'.code.toByte(), 'p'.code.toByte(), 't'.code.toByte(),
            'u'.code.toByte(), 'r'.code.toByte(), 'e'.code.toByte(), 0x01,
        )
    }
}
