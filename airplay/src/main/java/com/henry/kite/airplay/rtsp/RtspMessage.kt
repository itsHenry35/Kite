package com.henry.kite.airplay.rtsp

/** An RTSP request. AirPlay uses RTSP/1.0 syntax over TCP 7000. */
data class RtspRequest(
    val method: String,
    val uri: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) {
    /** Returns a copy with [name] overriding any existing value. */
    fun withHeader(name: String, value: String): RtspRequest =
        copy(headers = LinkedHashMap(headers).apply { put(name, value) })

    fun encode(cseq: Int, defaultHeaders: Map<String, String>): ByteArray {
        val head = StringBuilder()
        head.append("$method $uri RTSP/1.0\r\n")
        head.append("CSeq: $cseq\r\n")

        val merged = LinkedHashMap(defaultHeaders).apply { putAll(headers) }
        merged.forEach { (name, value) -> head.append("$name: $value\r\n") }

        if (body != null) {
            head.append("Content-Length: ${body.size}\r\n")
        }
        head.append("\r\n")

        val headBytes = head.toString().toByteArray(Charsets.UTF_8)
        return if (body == null) headBytes else headBytes + body
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RtspRequest) return false
        return method == other.method && uri == other.uri &&
            headers == other.headers && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String = "$method $uri (${body?.size ?: 0} byte body)"
}

data class RtspResponse(
    val statusCode: Int,
    val reason: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    val isSuccess: Boolean get() = statusCode in 200..299

    /** 401 with a Digest challenge is how password-protected receivers answer. */
    val isUnauthorized: Boolean get() = statusCode == 401

    /**
     * The Digest challenge carried by a 401/470 response, or null. 470
     * Connection Authorization Required is the same gate with a different
     * number — macOS uses both spellings.
     */
    fun authChallenge(): String? {
        if (statusCode != 401 && statusCode != 470) return null
        val value = this["WWW-Authenticate"] ?: return null
        return value.trim().takeIf { it.startsWith("Digest", ignoreCase = true) }
    }

    /** Header lookup is case-insensitive; receivers are not consistent about case. */
    operator fun get(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RtspResponse) return false
        return statusCode == other.statusCode && reason == other.reason &&
            headers == other.headers && body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + reason.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }

    override fun toString(): String = "$statusCode $reason (${body.size} byte body)"
}

open class RtspException(message: String, val statusCode: Int? = null) : Exception(message)
