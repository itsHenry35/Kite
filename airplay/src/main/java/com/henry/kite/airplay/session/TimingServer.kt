package com.henry.kite.airplay.session

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The AirPlay NTP timing server the receiver polls during mirroring.
 *
 * Without it the receiver's `raop_ntp` client times out, `client_time_received`
 * never flips true, and `raop_ntp_convert_remote_time` returns 0 for every frame
 * — which is why a session can connect fine yet show nothing.
 *
 * Protocol (read off UxPlay `raop_ntp.c`): the receiver sends a 32-byte request
 * with its own timestamps, and the sender replies with a 128-byte packet:
 *
 *   response[8..15]  = server NTP timestamp (receive time)
 *   response[16..23] = echo of request[24..31] (client's send time)
 *   response[24..31] = server NTP timestamp (transmit time)
 *
 * An NTP timestamp here is `(seconds_since_1900 << 32) | binary_fraction`,
 * written big-endian. The server just echoes bytes and stamps its own clock;
 * the receiver does all the offset/delay math.
 */
class TimingServer : AutoCloseable {

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)

    /** The UDP port this server bound to; feed it into the first SETUP's `timingPort`. */
    val port: Int
        get() = socket?.localPort ?: 0

    fun start() {
        check(socket == null) { "Already started" }
        val s = DatagramSocket(0)
        socket = s
        running.set(true)
        thread = Thread({ receiveLoop(s) }, "kite-timing").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Timing server listening on UDP port ${s.localPort}")
    }

    private fun receiveLoop(s: DatagramSocket) {
        val request = ByteArray(REQUEST_SIZE)
        while (running.get()) {
            val packet = DatagramPacket(request, request.size)
            try {
                s.receive(packet)
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Timing receive failed", e)
                break
            }
            val response = buildResponse(request)
            runCatching {
                s.send(DatagramPacket(response, response.size, packet.address, packet.port))
            }.onFailure { Log.w(TAG, "Timing send failed", it) }
        }
    }

    private fun buildResponse(request: ByteArray): ByteArray {
        val nowNs = System.currentTimeMillis() * 1_000_000L
        val response = ByteArray(RESPONSE_SIZE)
        response[0] = request[0] // 0x80: LI/VN/mode, echoed.
        response[1] = request[1] // 0xd2: type, echoed.
        putNtpTimestamp(response, 8, nowNs) // server receive time (t0)
        request.copyInto(response, 16, 24, 32) // echo client send time (t1)
        putNtpTimestamp(response, 24, nowNs) // server transmit time (t2)
        return response
    }

    private fun putNtpTimestamp(buf: ByteArray, offset: Int, nsSince1970: Long) {
        val seconds = nsSince1970 / NANOS_PER_SECOND + SECONDS_1900_TO_1970
        val nanos = nsSince1970 % NANOS_PER_SECOND
        val fraction = (nanos shl 32) / NANOS_PER_SECOND
        ByteBuffer.wrap(buf, offset, 8).order(ByteOrder.BIG_ENDIAN)
            .putInt(seconds.toInt())
            .putInt(fraction.toInt())
    }

    override fun close() {
        running.set(false)
        runCatching { socket?.close() }
        socket = null
        thread = null
        Log.i(TAG, "Timing server stopped")
    }

    private companion object {
        const val TAG = "TimingServer"
        const val REQUEST_SIZE = 32
        const val RESPONSE_SIZE = 128
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val SECONDS_1900_TO_1970 = 2_208_988_800L
    }
}
