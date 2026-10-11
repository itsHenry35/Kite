package com.henry.kite.airplay.session

import android.util.Base64
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher

/**
 * Wraps the RAOP audio AES key with Apple's AirPlay RSA public key.
 *
 * Classic AirPlay (AirTunes v1/v2) audio is AES-128-CBC encrypted with a key the
 * *sender* generates; the receiver learns it from the SDP `a=rsaaeskey:` line,
 * where the key arrives RSA-OAEP-encrypted under the well-known AirPlay public
 * key — every AirPlay receiver ships the matching private key. The IV itself is
 * sent in the clear as `a=aesiv:`.
 *
 * The 256-byte modulus below is the standard RAOP key (2048-bit, exponent 65537)
 * shared by every open-source AirPlay sender (shairport-sync-adjacent projects,
 * pulseaudio's module-raop).
 */
object RaopRsa {

    /** RSA-OAEP encrypts [aesKey] and returns the value for `a=rsaaeskey:` (base64). */
    fun wrapAesKey(aesKey: ByteArray): String = Base64.encodeToString(encryptOaep(aesKey), Base64.NO_WRAP)

    /** The AES IV goes into the SDP unencrypted (base64). */
    fun encodeIv(iv: ByteArray): String = Base64.encodeToString(iv, Base64.NO_WRAP)

    private fun encryptOaep(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(OAEP_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey(), SecureRandom())
        return cipher.doFinal(data)
    }

    private fun publicKey(): PublicKey {
        val modulus = Base64.decode(MODULUS_BASE64, Base64.DEFAULT)
        val exponent = Base64.decode(EXPONENT_BASE64, Base64.DEFAULT)
        val spec = RSAPublicKeySpec(java.math.BigInteger(1, modulus), java.math.BigInteger(1, exponent))
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    private const val OAEP_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"
    private const val EXPONENT_BASE64 = "AQAB"

    private const val MODULUS_BASE64 =
        "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC" +
            "5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDR" +
            "KSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuB" +
            "OitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJ" +
            "Q+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnh" +
            "imNVvYFZeCXg/IdTQ+x4IRdiXNv5hEew=="
}
