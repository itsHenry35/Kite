package com.henry.kite.airplay.session

import android.util.Log
import com.henry.kite.airplay.crypto.ChaChaPolyCipher
import com.henry.kite.airplay.crypto.Ed25519Identity
import com.henry.kite.airplay.crypto.HkdfSha512
import com.henry.kite.airplay.crypto.Srp6aClient
import com.henry.kite.airplay.pairing.PairVerify
import com.henry.kite.airplay.pairing.PairingCredentials
import com.henry.kite.airplay.pairing.PairingException
import com.henry.kite.airplay.pairing.PairingProtocol
import com.henry.kite.airplay.pairing.PairingTransport
import com.henry.kite.airplay.pairing.SessionKeys
import com.henry.kite.airplay.pairing.Tlv8
import com.henry.kite.airplay.pairing.TlvType
import com.henry.kite.airplay.rtsp.RtspClient
import com.henry.kite.airplay.rtsp.RtspRequest
import java.util.UUID

/**
 * HAP transient pairing (doubletake `pairSetupTransient`): the silent handshake
 * for modern receivers — macOS talks to doubletake exactly this way, including
 * when "require password" is on, because the password belongs to HTTP Digest
 * and never to SRP.
 *
 * M1 carries State + Method + Flags(0x10); the SRP secret is computed with an
 * *empty* password — not the hidden PIN older receivers used. M5 then swaps
 * Ed25519 identities under the SRP secret, and a regular pair-verify derives
 * the control-channel keys from a fresh X25519 agreement, exactly like a
 * permanent pairing. Nothing is verified against a stored receiver key.
 *
 * Returns null when the receiver refuses any stage; the caller falls back to
 * code pairing on a fresh connection.
 */
class TransientPairSetup(
    private val client: RtspClient,
    /** HKP generation on the wire: 4 for modern receivers, 3 for legacy fallback. */
    private val hkpVersion: String = PairingTransport.HKP_TRANSIENT,
) {

    class Result(val keys: SessionKeys, val credentials: PairingCredentials)

    /** Runs the exchange; every refusal surfaces as null, never as a retry. */
    suspend fun perform(): Result? = try {
        val identity = Ed25519Identity.generate()
        val clientId = UUID.randomUUID().toString()
        val shared = runSetup(identity, clientId)

        // The receiver never shows its long-term key here, so the credentials
        // carry an empty identity and pair-verify checks decryption only.
        val credentials = PairingCredentials(
            clientId = clientId,
            clientSeed = identity.seed,
            receiverPublicKey = ByteArray(0),
            receiverId = "",
            protocol = PairingProtocol.HAP_TRANSIENT,
        )
        Result(PairVerify(client, credentials, PairingProtocol.HAP_TRANSIENT).verify(), credentials)
    } catch (e: PairingException) {
        Log.i(TAG, "Transient pairing refused: ${e.message}")
        null
    }

    /** M1 → M3 → M5 with the empty-password SRP secret; returns the SRP secret. */
    private suspend fun runSetup(identity: Ed25519Identity, clientId: String): ByteArray {
        val m1 = Tlv8.builder()
            .put(TlvType.STATE, STATE_M1)
            .put(TlvType.METHOD, METHOD_PAIR_SETUP)
            .put(TlvType.FLAGS, FLAG_TRANSIENT)
            .build()
        val m2 = exchange(client.send(tlvRequest("/pair-setup", m1.encode(), clientId)))
        val salt = m2[TlvType.SALT]
            ?: throw PairingException("Transient M2 is missing the salt")
        val serverPublicKey = m2[TlvType.PUBLIC_KEY]
            ?: throw PairingException("Transient M2 is missing the server public key")

        // The transient SRP secret is computed with an empty password.
        val srp = Srp6aClient()
        val m3 = Tlv8.builder()
            .put(TlvType.STATE, STATE_M3)
            .put(TlvType.PUBLIC_KEY, srp.generatePublicKey())
            .put(TlvType.PROOF, srp.computeProof(salt, serverPublicKey, EMPTY_SECRET))
            .build()
        exchange(client.send(tlvRequest("/pair-setup", m3.encode(), clientId)))
        exchange(
            client.send(
                tlvRequest("/pair-setup", identityMessage(srp.sharedSecret, identity, clientId).encode(), clientId),
            ),
        )
        return srp.sharedSecret
    }

    /** M5: hand over our identity under the SRP secret, like a permanent pair-setup. */
    private fun identityMessage(shared: ByteArray, identity: Ed25519Identity, clientId: String): Tlv8 {
        val encryptionKey = HkdfSha512.derive(shared, SALT_ENCRYPT, INFO_ENCRYPT)
        val signKey = HkdfSha512.derive(shared, SALT_CONTROLLER, INFO_CONTROLLER)
        val signature = identity.sign(signKey + clientId.toByteArray() + identity.publicKey)
        val subTlv = Tlv8.builder()
            .put(TlvType.IDENTIFIER, clientId)
            .put(TlvType.PUBLIC_KEY, identity.publicKey)
            .put(TlvType.SIGNATURE, signature)
            .build()
        return Tlv8.builder()
            .put(TlvType.STATE, STATE_M5)
            .put(
                TlvType.ENCRYPTED_DATA,
                ChaChaPolyCipher.seal(
                    key = encryptionKey,
                    nonce = ChaChaPolyCipher.nonce(NONCE_M5),
                    plaintext = subTlv.encode(),
                ),
            )
            .build()
    }

    /** Decodes the reply; receiver error TLVs and HTTP failures both throw. */
    private fun exchange(response: com.henry.kite.airplay.rtsp.RtspResponse): Tlv8 {
        if (!response.isSuccess) {
            throw PairingException("Receiver returned ${response.statusCode} for transient pairing")
        }
        val tlv = Tlv8.decode(response.body)
        tlv.error?.let { throw PairingException("Receiver rejected transient pairing: $it", it) }
        return tlv
    }

    private fun tlvRequest(path: String, body: ByteArray, clientId: String) = RtspRequest(
        method = "POST",
        uri = path,
        headers = mapOf(
            "Content-Type" to "application/octet-stream",
            // HKP version 4 marks the transient pairing protocol.
            "X-Apple-HKP" to hkpVersion,
            "X-Apple-Client-ID" to clientId,
        ),
        body = body,
    )

    private companion object {
        const val TAG = "TransientPairSetup"
        const val METHOD_PAIR_SETUP = 0x00
        const val STATE_M1 = 0x01
        const val STATE_M3 = 0x03
        const val STATE_M5 = 0x05
        const val FLAG_TRANSIENT = 0x10
        const val EMPTY_SECRET = ""
        const val SALT_ENCRYPT = "Pair-Setup-Encrypt-Salt"
        const val INFO_ENCRYPT = "Pair-Setup-Encrypt-Info"
        const val SALT_CONTROLLER = "Pair-Setup-Controller-Sign-Salt"
        const val INFO_CONTROLLER = "Pair-Setup-Controller-Sign-Info"
        const val NONCE_M5 = "PS-Msg05"
    }
}
