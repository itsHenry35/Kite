package com.henry.kite.airplay.rtsp

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * RTSP Digest authentication for password-protected AirPlay receivers.
 *
 * The receiver answers an unauthenticated request with `401` and a
 * `WWW-Authenticate: Digest realm=..., nonce=...` challenge; the retry carries an
 * `Authorization` header proving the password without sending it. This is the
 * scheme Apple's "require password" AirPlay option and shairport-sync's
 * `--password` both use, with the conventional username "iTunes".
 *
 * Handles both the qop-less form and `qop="auth"`; receivers are split on which
 * they send.
 */
class DigestAuth(private val username: String, private val password: String) {

    /**
     * Builds the Authorization header for [method] [uri] against the raw
     * [challenge] header value. [requestCount] feeds the nonce-count for
     * qop="auth" challenges and can just increase per request.
     */
    fun authorization(challenge: String, method: String, uri: String, requestCount: Int): String {
        val params = parseChallenge(challenge)
        val realm = params["realm"] ?: error("Digest challenge without realm: $challenge")
        val nonce = params["nonce"] ?: error("Digest challenge without nonce: $challenge")
        val qop = params["qop"]

        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("$method:$uri")

        val body = StringBuilder("Digest ")
            .append("username=\"").append(username).append("\", ")
            .append("realm=\"").append(realm).append("\", ")
            .append("nonce=\"").append(nonce).append("\", ")
            .append("uri=\"").append(uri).append("\", ")

        if (qop != null && qop.contains("auth", ignoreCase = true)) {
            val cnonce = cnonce()
            val nc = "%08x".format(requestCount)
            body.append("cnonce=\"").append(cnonce).append("\", ")
                .append("nc=").append(nc).append(", ")
                .append("qop=auth, ")
            body.append("response=\"")
                .append(md5Hex("$ha1:$nonce:$nc:$cnonce:auth:$ha2"))
                .append("\", ")
        } else {
            body.append("response=\"").append(md5Hex("$ha1:$nonce:$ha2")).append("\", ")
        }

        params["opaque"]?.let { body.append("opaque=\"").append(it).append("\", ") }
        body.append("algorithm=MD5")
        return body.toString()
    }

    /** True when this header value is a Digest challenge we can answer. */
    fun canHandle(challenge: String?): Boolean =
        challenge != null && challenge.trim().startsWith("Digest", ignoreCase = true)

    private fun parseChallenge(challenge: String): Map<String, String> {
        val params = linkedMapOf<String, String>()
        // Drop the scheme, then split on commas that are outside quotes.
        val rest = challenge.trim().removePrefix("Digest").removePrefix("digest").trim()
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in rest) {
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    current.append(ch)
                }

                ch == ',' && !inQuotes -> {
                    tokens.add(current.toString())
                    current.clear()
                }

                else -> current.append(ch)
            }
        }
        tokens.add(current.toString())

        for (token in tokens.map { it.trim() }.filter { it.isNotEmpty() }) {
            val eq = token.indexOf('=')
            if (eq <= 0) continue
            val key = token.substring(0, eq).trim()
            var value = token.substring(eq + 1).trim()
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
                value = value.substring(1, value.length - 1)
            }
            params[key] = value
        }
        return params
    }

    private fun cnonce(): String {
        val bytes = ByteArray(CNONCE_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun md5Hex(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.ISO_8859_1))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val CNONCE_BYTES = 8
    }
}
