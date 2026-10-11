package com.henry.kite.airplay.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SRP-6a client for HomeKit Accessory Protocol pair-setup.
 *
 * Parameters are RFC 5054 group 3072 with SHA-512, which is what HAP mandates.
 * The receiver (Apple TV) plays the SRP server; the shared password is the
 * 4-digit PIN shown on its screen.
 *
 * Notation follows RFC 5054, with Apple's deviations as proven by doubletake
 * (the Go sender known to work against real Apple receivers) and
 * shairport-sync's pair_ap (which accepts pairings from real iPhones):
 *   N, g  group modulus and generator
 *   k     multiplier = H(N | PAD(g))          — PAD(384) here
 *   x     H(s | H(I | ":" | P))
 *   A     g^a           client public
 *   B                   server public (received)
 *   u     H(PAD(A) | PAD(B))                — PAD(384) here
 *   S     (B - k*g^x)^(a + u*x)
 *   K     H(S)                              — S in minimal big-endian bytes
 *   M1    H(H(N) xor H(g) | H(I) | s | A | B | K)
 *         — H(g) over g's minimal bytes (0x05), and s, A, B, salt all raw/minimal
 *   M2    H(A | M1 | K)
 */
class Srp6aClient(private val random: SecureRandom = SecureRandom()) {
    private val digest: MessageDigest get() = MessageDigest.getInstance("SHA-512")

    private var a: BigInteger = BigInteger.ZERO
    private var aPub: BigInteger = BigInteger.ZERO

    /** Username; HAP pair-setup always uses the literal "Pair-Setup". */
    var identity: String = "Pair-Setup"
        private set

    private var salt: ByteArray = ByteArray(0)
    private var sessionKey: ByteArray = ByteArray(0)
    private var proofM1: ByteArray = ByteArray(0)

    /** K = H(S). 64 bytes. Feed this into [HkdfSha512] to derive transport keys. */
    val sharedSecret: ByteArray get() = sessionKey

    /** Generates the client key pair and returns A, to be sent as kTLVType_PublicKey. */
    fun generatePublicKey(): ByteArray {
        // RFC 5054 recommends |a| >= 256 bits; HAP implementations use 32 bytes.
        val bytes = ByteArray(32).also(random::nextBytes)
        a = BigInteger(1, bytes)
        aPub = G.modPow(a, N)
        return aPub.toUnsignedBytes(N_BYTE_LEN)
    }

    /**
     * Consumes the server's salt and public key, derives the session key, and
     * returns M1 (kTLVType_Proof) for pair-setup M3.
     */
    fun computeProof(salt: ByteArray, serverPublic: ByteArray, pin: String): ByteArray {
        require(aPub.signum() != 0) { "generatePublicKey() must be called first" }
        this.salt = salt

        val b = BigInteger(1, serverPublic)
        require(b.mod(N).signum() != 0) { "Server sent B ≡ 0 (mod N); aborting" }

        val paddedA = aPub.toUnsignedBytes(N_BYTE_LEN)
        val paddedB = b.toUnsignedBytes(N_BYTE_LEN)

        val k = BigInteger(1, hash(N.toUnsignedBytes(N_BYTE_LEN), G.toUnsignedBytes(N_BYTE_LEN)))
        val u = BigInteger(1, hash(paddedA, paddedB))
        val x = BigInteger(1, hash(salt, hash("$identity:$pin".toByteArray())))

        // S = (B - k*g^x)^(a + u*x) mod N
        val base = b.subtract(k.multiply(G.modPow(x, N)).mod(N)).mod(N)
        val s = base.modPow(a.add(u.multiply(x)), N)

        // Apple's CoreUtils hashes S, the salt, A and B in their minimal
        // big-endian form here — RFC 5054's PAD() applies only to k and u
        // (pair_ap/pair.c, the implementation proven against real devices).
        sessionKey = hash(s.toMinimalBytes())

        // doubletake (proven against Apple receivers) hashes the generator in
        // its minimal byte form — a single 0x05 — not PAD(384). PAD applies
        // only to k and u; hashing the padded form here makes every proof fail
        // with AUTHENTICATION regardless of the password.
        val hn = hash(N.toUnsignedBytes(N_BYTE_LEN))
        val hg = hash(G.toMinimalBytes())
        val hxor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }

        // The salt enters the proof exactly as received (doubletake joins the
        // raw bytes); stripping leading zeros would change the digest.
        proofM1 = hash(
            hxor,
            hash(identity.toByteArray()),
            salt,
            aPub.toMinimalBytes(),
            b.toMinimalBytes(),
            sessionKey,
        )
        return proofM1
    }

    /** Verifies the server's M2 from pair-setup M4. */
    fun verifyServerProof(serverProof: ByteArray): Boolean {
        val expected = hash(aPub.toMinimalBytes(), proofM1, sessionKey)
        return expected.constantTimeEquals(serverProof)
    }

    private fun hash(vararg parts: ByteArray): ByteArray = digest.apply { parts.forEach(::update) }.digest()

    companion object {
        private const val N_BYTE_LEN = 384

        /** RFC 5054 group 3072 generator. */
        private val G = BigInteger.valueOf(5)

        /** RFC 5054 group 3072 modulus (= RFC 3526 3072-bit MODP prime). */
        private val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
                "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
                "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
                "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
                "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
                "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
                "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
                "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
                "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
    }
}

/** The minimal big-endian magnitude, without PAD(). Apple hashes this form. */
internal fun BigInteger.toMinimalBytes(): ByteArray {
    val raw = toByteArray()
    return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
}

/** Left-pads the magnitude to [length] bytes, as RFC 5054's PAD() requires. */
internal fun BigInteger.toUnsignedBytes(length: Int): ByteArray {
    val raw = toByteArray()
    val src = if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
    require(src.size <= length) { "Value wider than $length bytes" }
    return ByteArray(length).also { src.copyInto(it, length - src.size) }
}

internal fun ByteArray.constantTimeEquals(other: ByteArray): Boolean {
    if (size != other.size) return false
    var diff = 0
    for (i in indices) diff = diff or (this[i].toInt() xor other[i].toInt())
    return diff == 0
}
