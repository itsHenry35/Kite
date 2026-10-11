package com.henry.kite.airplay.pairing

import com.henry.kite.airplay.crypto.Ed25519Identity
import com.henry.kite.airplay.crypto.Ed25519Verify
import com.henry.kite.airplay.crypto.X25519Ephemeral
import com.henry.kite.airplay.rtsp.RtspClient
import com.henry.kite.airplay.rtsp.RtspRequest
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The original binary AirPlay pairing (doubletake `rawPairSetup` /
 * `rawPairVerify`) — no SRP, no TLV8, and afterwards the control channel stays
 * plaintext. Receivers classified as non-modern are probed with this first.
 *
 * pair-setup: POST our 32-byte Ed25519 public key, expect the receiver's
 * 32-byte Ed25519 public key back.
 *
 * pair-verify (raw binary, NOT TLV8):
 *
 *   V1 (68):  flags(4)=0x01 + X25519_pub(32) + Ed25519_pub(32)
 *   V2 (96):  server_X25519_pub(32) + AES-CTR(server_sig, offset 0)(64)
 *   V3 (68):  flags(4)=0x00 + AES-CTR(client_sig, offset 64)(64)
 *   V4:       empty 200 OK
 *
 * AES-CTR key derivation is plain SHA-512, not HKDF:
 *
 *   key = SHA-512("Pair-Verify-AES-Key" || x25519_secret)[:16]
 *   iv  = SHA-512("Pair-Verify-AES-IV"  || x25519_secret)[:16]
 *
 * The server signature covers `server_X25519 || client_X25519` and is checked
 * against the receiver's long-term key from /info; ours covers the reverse.
 */
class RawPairing(
    private val client: RtspClient,
    /** The receiver's long-term Ed25519 public key, from GET /info. */
    private val serverPublicKey: ByteArray,
    /** Whether to ask for the PD-mixed stream key (receivers without feature bit 27). */
    private val mixStreamKey: Boolean,
) {

    class Result(val credentials: PairingCredentials, val sharedSecret: ByteArray)

    private var identity: Ed25519Identity = Ed25519Identity.generate()

    /** Full pairing on a fresh connection: pair-setup then pair-verify. */
    suspend fun setupAndVerify(): Result {
        val response = client.send(
            rawRequest(PairingTransport.PAIR_SETUP, identity.publicKey),
        )
        if (!response.isSuccess) {
            throw PairingException("Receiver returned ${response.statusCode} for raw pair-setup")
        }
        if (response.body.size != 32) {
            throw PairingException("Raw pair-setup returned ${response.body.size} bytes, expected 32")
        }
        return verify(response.body)
    }

    /** Re-verification on a later connection with the stored long-term identity. */
    suspend fun verifyOnly(stored: PairingCredentials): Result {
        identity = Ed25519Identity.fromSeed(stored.clientSeed)
        return verify(serverPublicKey, stored)
    }

    private suspend fun verify(serverEd25519Key: ByteArray, existing: PairingCredentials? = null): Result {
        val ephemeral = X25519Ephemeral.generate()

        // V1: flags(4) + X25519 pub + Ed25519 pub
        val v1 = ByteArray(68).also {
            it[0] = 0x01
            ephemeral.publicKey.copyInto(it, 4)
            identity.publicKey.copyInto(it, 36)
        }
        val v2 = client.send(rawRequest(PairingTransport.PAIR_VERIFY, v1))
        requireSuccess(v2, "raw pair-verify V1")
        if (v2.body.size != 96) {
            throw PairingException("Raw pair-verify V2 returned ${v2.body.size} bytes, expected 96")
        }

        val serverEphemeral = v2.body.copyOfRange(0, 32)
        val sharedSecret = ephemeral.agree(serverEphemeral)
        val aesKey = sha512DeriveKey("Pair-Verify-AES-Key", sharedSecret)
        val aesIv = sha512DeriveKey("Pair-Verify-AES-IV", sharedSecret)

        // Server signature, encrypted at CTR offset 0, over server||client ephemeral.
        val serverSig = ctr(aesKey, aesIv).update(v2.body.copyOfRange(32, 96))
        val serverSigMessage = serverEphemeral + ephemeral.publicKey
        if (!Ed25519Verify.verify(serverEd25519Key, serverSigMessage, serverSig)) {
            throw PairingException("Raw pair-verify: receiver signature did not verify")
        }

        // Our signature, encrypted at CTR offset 64, over client||server ephemeral.
        val clientSig = identity.sign(ephemeral.publicKey + serverEphemeral)
        val ctrContinued = ctr(aesKey, aesIv)
        ctrContinued.update(ByteArray(64)) // advance the keystream past the server signature
        val encryptedSig = ctrContinued.update(clientSig)

        val v3 = ByteArray(68).also { encryptedSig.copyInto(it, 4) }
        val v4 = client.send(rawRequest(PairingTransport.PAIR_VERIFY, v3))
        requireSuccess(v4, "raw pair-verify V3")

        val credentials = existing ?: PairingCredentials(
            clientId = "",
            clientSeed = identity.seed,
            receiverPublicKey = serverEd25519Key,
            receiverId = "",
            protocol = PairingProtocol.RAW,
        )
        return Result(credentials, sharedSecret)
    }

    private fun requireSuccess(response: com.henry.kite.airplay.rtsp.RtspResponse, what: String) {
        if (!response.isSuccess) {
            throw PairingException("Receiver returned ${response.statusCode} for $what")
        }
    }

    private fun rawRequest(path: String, body: ByteArray) = RtspRequest(
        method = "POST",
        uri = path,
        headers = buildMap {
            put("Content-Type", "application/octet-stream")
            put("X-Apple-ProtocolVersion", "1")
            if (mixStreamKey) put(PairingTransport.HEADER_PD, "1")
        },
        body = body,
    )

    private fun ctr(key: ByteArray, iv: ByteArray): Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    private fun sha512DeriveKey(salt: String, secret: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(salt.toByteArray(Charsets.US_ASCII))
        digest.update(secret)
        return digest.digest().copyOf(16)
    }
}
