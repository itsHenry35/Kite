package com.henry.kite.airplay.session

import android.util.Log
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlin.concurrent.thread

/**
 * The audio control socket.
 *
 * SETUP has to advertise a non-zero `controlPort`, because that is where the
 * receiver sends resend requests for audio packets it missed. Requests are
 * answered here: [onResend] returns the cached wire packets and this class
 * wraps each one in the retransmit header every receiver expects and sends it
 * back to the address the request came from — UxPlay reads resent packets off
 * its *control* socket, not the audio one. A late duplicate is free for a UDP
 * audio stream; a hole makes the receiver's decoder glitch until the next
 * frame repeat.
 *
 * Request layout (UxPlay `raop_rtp.c`, `raop_rtp_resend_callback`):
 * `[0]=0x80, [1]=0xD5, [2:4]=request seq, [4:6]=first missing seq, [6:8]=count`.
 * Reply layout (UxPlay `raop_rtp.c` retransmit branch, pyatv `stream_client`):
 * `0x80 0xD6 <request seq>` followed by the original packet verbatim.
 */
class AudioControlServer : Closeable {

    /**
     * Called on the control thread for every resend request; returns the
     * cached wire packets to re-send, wrapped and addressed by this server.
     * Wired to [AudioStream.resend] by the session; null simply counts requests.
     */
    var onResend: ((startSeq: Int, count: Int) -> List<ByteArray>)? = null

    private var socket: DatagramSocket? = null
    private var listener: Thread? = null

    @Volatile
    private var isRunning = false

    private var resendRequests = 0L

    /** UDP port to advertise in SETUP. Valid once [start] has returned. */
    val port: Int
        get() = socket?.localPort ?: error("Audio control server is not running")

    fun start() {
        check(socket == null) { "Already started" }
        val channel = DatagramSocket()
        socket = channel
        isRunning = true

        listener = thread(name = "kite-audio-control") {
            val buffer = ByteArray(PACKET_BUFFER_BYTES)
            while (isRunning) {
                val packet = DatagramPacket(buffer, buffer.size)
                val received = runCatching { channel.receive(packet) }.isSuccess
                if (!received) break // Socket closed, or the read failed for good.
                onControlPacket(packet)
            }
        }
        Log.i(TAG, "Audio control server listening on UDP port ${channel.localPort}")
    }

    private fun onControlPacket(packet: DatagramPacket) {
        if (packet.length < RESEND_PACKET_BYTES) return
        // Payload type lives in the low 7 bits of byte 1; 0x55 is "resend".
        val payloadType = packet.data[1].toInt() and 0x7F
        if (payloadType != PAYLOAD_TYPE_RESEND) return

        resendRequests++
        val data = packet.data
        val requestSeq = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val startSeq = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val count = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        val cached = onResend?.invoke(startSeq, count).orEmpty()
        cached.forEach { reply(packet, requestSeq, it) }
        // One line per burst would flood the log on a lossy network.
        if (resendRequests % RESEND_LOG_INTERVAL == 0L) {
            Log.d(TAG, "$resendRequests audio resend requests so far")
        }
    }

    private fun reply(request: DatagramPacket, requestSeq: Int, original: ByteArray) {
        val wrapped = ByteArray(RESEND_HEADER_BYTES + original.size)
        wrapped[0] = 0x80.toByte()
        wrapped[1] = REPLY_PAYLOAD_TYPE.toByte()
        wrapped[2] = (requestSeq ushr 8).toByte()
        wrapped[3] = requestSeq.toByte()
        original.copyInto(wrapped, RESEND_HEADER_BYTES)
        runCatching {
            socket?.send(DatagramPacket(wrapped, wrapped.size, request.socketAddress))
        }
    }

    override fun close() {
        isRunning = false
        runCatching { socket?.close() }
        runCatching { listener?.join(JOIN_TIMEOUT_MS) }
        socket = null
        listener = null
        if (resendRequests > 0) {
            Log.i(TAG, "Audio control server closed after $resendRequests resend requests")
        }
    }

    private companion object {
        const val TAG = "AudioControlServer"
        const val PACKET_BUFFER_BYTES = 1024
        const val RESEND_PACKET_BYTES = 8
        const val PAYLOAD_TYPE_RESEND = 0x55
        const val REPLY_PAYLOAD_TYPE = 0xD6
        const val RESEND_HEADER_BYTES = 4
        const val RESEND_LOG_INTERVAL = 100L
        const val JOIN_TIMEOUT_MS = 300L
    }
}
