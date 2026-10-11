package com.henry.kite.airplay.pairing

/**
 * How the pairing state machines talk to the receiver.
 *
 * AirPlay carries pairing over the same RTSP connection that later carries SETUP
 * and RECORD, so this is implemented by the RTSP client rather than a standalone
 * HTTP stack. Requests are `POST <path> RTSP/1.0` with
 * `Content-Type: application/octet-stream` and a raw TLV8 body.
 */
interface PairingTransport {
    /**
     * [headers] carries the per-protocol declarations — `X-Apple-HKP` selects the
     * pairing generation and `X-Apple-PD` asks for the PD-mixed stream key — and
     * overrides whatever the transport would default to.
     */
    suspend fun postTlv(path: String, body: ByteArray, headers: Map<String, String> = emptyMap()): ByteArray

    companion object {
        const val PAIR_SETUP = "/pair-setup"
        const val PAIR_VERIFY = "/pair-verify"

        /** HKP pairing generations, as sent in X-Apple-HKP. */
        const val HKP_LEGACY = "3"
        const val HKP_TRANSIENT = "4"

        /** Screen-capture pairing: what a code display on the receiver pairs under. */
        const val HKP_SCREEN_CAPTURE = "5"

        /** Asks the receiver to mix the pair-verify ECDH secret into the stream key. */
        const val HEADER_PD = "X-Apple-PD"
    }
}
