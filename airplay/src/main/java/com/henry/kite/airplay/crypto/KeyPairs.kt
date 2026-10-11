package com.henry.kite.airplay.crypto

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/** Long-term Ed25519 identity for this Android device, persisted after pair-setup. */
class Ed25519Identity(private val privateKey: Ed25519PrivateKeyParameters) {
    val publicKey: ByteArray get() = privateKey.generatePublicKey().encoded
    val seed: ByteArray get() = privateKey.encoded

    fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, privateKey)
        update(message, 0, message.size)
        generateSignature()
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): Ed25519Identity {
            val generator = Ed25519KeyPairGenerator().apply {
                init(Ed25519KeyGenerationParameters(random))
            }
            val pair = generator.generateKeyPair()
            return Ed25519Identity(pair.private as Ed25519PrivateKeyParameters)
        }

        /** Restores an identity from its 32-byte seed. */
        fun fromSeed(seed: ByteArray): Ed25519Identity {
            require(seed.size == Ed25519PrivateKeyParameters.KEY_SIZE) {
                "Ed25519 seed must be 32 bytes, got ${seed.size}"
            }
            return Ed25519Identity(Ed25519PrivateKeyParameters(seed, 0))
        }
    }
}

object Ed25519Verify {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
        Ed25519Signer().run {
            init(false, Ed25519PublicKeyParameters(publicKey, 0))
            update(message, 0, message.size)
            verifySignature(signature)
        }
    }.getOrDefault(false)
}

/** Ephemeral X25519 key pair, generated fresh for every pair-verify exchange. */
class X25519Ephemeral private constructor(private val privateKey: X25519PrivateKeyParameters) {
    val publicKey: ByteArray get() = privateKey.generatePublicKey().encoded

    fun agree(peerPublicKey: ByteArray): ByteArray {
        require(peerPublicKey.size == X25519PublicKeyParameters.KEY_SIZE) {
            "X25519 public key must be 32 bytes, got ${peerPublicKey.size}"
        }
        val agreement = X25519Agreement()
        agreement.init(privateKey)
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), shared, 0)
        return shared
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): X25519Ephemeral {
            val generator = X25519KeyPairGenerator().apply {
                init(X25519KeyGenerationParameters(random))
            }
            val pair = generator.generateKeyPair()
            return X25519Ephemeral(pair.private as X25519PrivateKeyParameters)
        }
    }
}
