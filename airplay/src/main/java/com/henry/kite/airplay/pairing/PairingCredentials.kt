package com.henry.kite.airplay.pairing

import com.henry.kite.airplay.crypto.Ed25519Identity

/**
 * The wire protocol a pairing was established with, so later connections run
 * pair-verify with the same framing instead of re-probing (doubletake persists
 * the same choice alongside the identity).
 */
enum class PairingProtocol(val hkpVersion: String?) {
    /** HomeKit type 3 pairing: legacy receivers, the configured password as SRP secret. */
    HAP_LEGACY("3"),

    /** Transient (flags 0x10) pairing with an empty SRP secret. */
    HAP_TRANSIENT("4"),

    /** PIN pairing with a receiver-displayed code and a screen-capture ACL. */
    HAP_SCREEN_CAPTURE("5"),

    /** Original binary AirPlay pairing; the control channel stays plaintext. */
    RAW(null),
    ;

    /** pair-verify carries X-Apple-PD: 1, and the stream key mixes the ECDH secret. */
    val usesPdMixing: Boolean get() = this != RAW && this != HAP_LEGACY

    companion object {
        fun fromStored(value: String?): PairingProtocol = entries.firstOrNull { it.name == value } ?: HAP_LEGACY
    }
}

/**
 * What survives a successful pairing. Persist this per receiver; pair-verify
 * alone is enough on every later connection, so the user only ever types the
 * PIN or password once.
 */
data class PairingCredentials(
    /** Our own client identifier, sent as kTLVType_Identifier. A random UUID string. */
    val clientId: String,
    /** 32-byte Ed25519 seed for our long-term identity. Treat as secret. */
    val clientSeed: ByteArray,
    /**
     * The receiver's long-term Ed25519 public key, learned in pair-setup M6 or
     * from /info. Empty when unknown — transient exchanges may skip the identity
     * reply, and pair-verify then proves the receiver by channel decryption alone.
     */
    val receiverPublicKey: ByteArray,
    /** The receiver's identifier string from pair-setup M6. Empty when unknown. */
    val receiverId: String,
    /** Which exchange produced these credentials; selects pair-verify framing. */
    val protocol: PairingProtocol = PairingProtocol.HAP_LEGACY,
) {
    fun identity(): Ed25519Identity = Ed25519Identity.fromSeed(clientSeed)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingCredentials) return false
        return clientId == other.clientId &&
            clientSeed.contentEquals(other.clientSeed) &&
            receiverPublicKey.contentEquals(other.receiverPublicKey) &&
            receiverId == other.receiverId &&
            protocol == other.protocol
    }

    override fun hashCode(): Int {
        var result = clientId.hashCode()
        result = 31 * result + clientSeed.contentHashCode()
        result = 31 * result + receiverPublicKey.contentHashCode()
        result = 31 * result + receiverId.hashCode()
        result = 31 * result + protocol.hashCode()
        return result
    }

    override fun toString(): String =
        "PairingCredentials(clientId=$clientId, receiverId=$receiverId, protocol=$protocol, seed=<redacted>)"
}

/** Session keys derived by pair-verify; they encrypt the RTSP control channel. */
class SessionKeys(
    /** Shared secret from the X25519 agreement. Also seeds the mirroring stream key. */
    val sharedSecret: ByteArray,
    /** Key for data we send. */
    val writeKey: ByteArray,
    /** Key for data the receiver sends. */
    val readKey: ByteArray,
) {
    override fun toString(): String = "SessionKeys(<redacted>)"
}

class PairingException(message: String, val tlvError: TlvError? = null) : Exception(message)
