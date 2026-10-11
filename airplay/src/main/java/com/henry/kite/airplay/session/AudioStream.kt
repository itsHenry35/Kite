package com.henry.kite.airplay.session

import android.util.Log
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The UDP socket that carries mirrored audio.
 *
 * Unlike the video channel this is datagram-based and unreliable by design: a
 * lost audio packet is skipped rather than recovered, which is why every frame
 * goes out three times.
 *
 * Not thread-safe. One sender keeps the sequence numbers and timestamps
 * monotonic, so all sends go through the session's single audio loop.
 */
class AudioStream(
    private val address: InetAddress,
    private val port: Int,
    private val cipher: AudioCipher,
    /** RTP timestamp step — the codec's samples per frame (ALAC: 352). */
    private val samplesPerFrame: Long = SAMPLES_PER_FRAME,
    /**
     * The receiver's audio control port; clock-sync packets (0xd4) go there.
     * Without them UxPlay-style receivers cannot map our RTP timestamps onto
     * wall-clock time and drop every frame as "invalid ntp_time".
     */
    private val remoteControlPort: Int = 0,
) : Closeable {

    private var socket: DatagramSocket? = null

    private var sequenceNumber = 0

    @Volatile
    private var rtpTimestamp = 0L

    private val closed = AtomicBoolean(false)
    private var syncThread: Thread? = null

    /**
     * The two frames sent alongside the newest one.
     *
     * AAC-ELD expects each frame three times, in the pattern
     * `0 / 0 1 / 0 1 2 / 1 2 3 / …` — decoding ELD to PCM triples the sound
     * frame, and the receiver counts on the repeats to fill it.
     */
    private val recentFrames = ArrayDeque<TimedFrame>(FRAME_REPEATS)

    /** Wire packets by sequence number, for answering retransmit requests. */
    private val resendCache = LinkedHashMap<Int, ByteArray>()

    private val bytesSent = AtomicLong(0)
    private val packetsSent = AtomicLong(0)

    val statistics: Statistics
        get() = Statistics(bytesSent.get(), packetsSent.get())

    data class Statistics(val bytes: Long, val packets: Long)

    private class TimedFrame(val sequenceNumber: Int, val rtpTimestamp: Long, val encryptedPayload: ByteArray)

    fun connect() {
        check(socket == null) { "Already connected" }
        // Unconnected: the receiver may answer from a different source port, and
        // a connected socket would make those replies unreadable.
        socket = DatagramSocket()
        Log.i(TAG, "Audio channel ready, sending to ${address.hostAddress}:$port")
        if (remoteControlPort > 0) startSyncLoop()
    }

    /**
     * One 0xd4 clock-sync per second on the control port, like every real
     * AirPlay audio sender: `[4:8)` pairs an RTP timestamp with the NTP time
     * in `[8:16)`, and `[16:20)` is where the stream will be shortly after
     * (UxPlay: sync_rtp + 77175 for ALAC). Receivers derive audio playout
     * times entirely from this mapping.
     */
    private fun startSyncLoop() {
        syncThread = kotlin.concurrent.thread(name = "kite-audio-sync", isDaemon = true) {
            var first = true
            while (!closed.get()) {
                sendSyncPacket(first)
                first = false
                try {
                    Thread.sleep(SYNC_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    private fun sendSyncPacket(first: Boolean) {
        val packet = ByteArray(SYNC_PACKET_BYTES)
        packet[0] = (if (first) 0x90 else 0x80).toByte()
        packet[1] = SYNC_PACKET_TYPE.toByte()
        packet[2] = 0x00
        packet[3] = 0x04 // The sequence field real senders carry; receivers ignore it.
        val currentTimestamp = rtpTimestamp
        writeInt(packet, 4, currentTimestamp.toInt())
        // NTP seconds count from 1900, exactly like our TimingServer replies and
        // doubletake's ntpBootTimestamp. Receivers subtract the 1900 epoch again:
        // raw Unix seconds underflow their unsigned arithmetic and put the stream
        // 1.8e19 ns in the future, which GStreamer's sync waits for forever.
        val nowMillis = System.currentTimeMillis()
        val ntp = (((nowMillis / 1_000) + NTP_UNIX_OFFSET_SECONDS) shl 32) or
            (((nowMillis % 1_000) * (1L shl 32) / 1_000) and 0xFFFFFFFFL)
        for (i in 0 until 8) {
            packet[8 + i] = (ntp ushr (56 - 8 * i)).toByte()
        }
        writeInt(packet, 16, (currentTimestamp + SYNC_LEAD_SAMPLES).toInt())
        runCatching {
            socket?.send(DatagramPacket(packet, packet.size, address, remoteControlPort))
        }.onFailure { if (!closed.get()) Log.w(TAG, "Sync packet failed", it) }
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    /**
     * Primes the stream before any audio is encoded.
     *
     * The receiver expects a run of 16-byte marker packets while the timeline
     * settles; they advance the sequence number and timestamp exactly as real
     * frames do.
     */
    fun sendPrimingPackets(count: Int = PRIMING_PACKETS) {
        repeat(count) {
            sendFrame(AudioPacket.NO_DATA_MARKER, encrypt = false)
        }
    }

    /** Sends one encoded AAC-ELD frame, with the two before it. */
    fun sendAudioFrame(frame: ByteArray) = sendFrame(frame, encrypt = true)

    private fun sendFrame(payload: ByteArray, encrypt: Boolean) {
        val outgoing = TimedFrame(
            sequenceNumber = sequenceNumber,
            rtpTimestamp = rtpTimestamp,
            encryptedPayload = if (encrypt) cipher.encrypt(payload) else payload,
        )

        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        rtpTimestamp += samplesPerFrame

        if (recentFrames.size == FRAME_REPEATS) recentFrames.removeFirst()
        recentFrames.addLast(outgoing)

        // Oldest first, so the receiver sees ascending sequence numbers.
        recentFrames.forEach(::transmit)
    }

    private fun transmit(frame: TimedFrame) {
        val channel = socket ?: throw IllegalStateException("Audio channel is not open")
        val bytes = AudioPacket.packet(
            sequenceNumber = frame.sequenceNumber,
            rtpTimestamp = frame.rtpTimestamp,
            payload = frame.encryptedPayload,
        )
        cacheForResend(frame.sequenceNumber, bytes)
        runCatching {
            channel.send(DatagramPacket(bytes, bytes.size, address, port))
        }.onSuccess {
            bytesSent.addAndGet(bytes.size.toLong())
            packetsSent.incrementAndGet()
        }.onFailure { error ->
            // A dropped datagram is not fatal; the next frame carries the repeat.
            Log.w(TAG, "Audio packet ${frame.sequenceNumber} not sent", error)
        }
    }

    private fun cacheForResend(sequenceNumber: Int, bytes: ByteArray) {
        synchronized(resendCache) {
            resendCache[sequenceNumber] = bytes
            while (resendCache.size > RESEND_CACHE_SIZE) {
                resendCache.remove(resendCache.keys.first())
            }
        }
    }

    /**
     * Collects the cached wire packets for [count] sequence numbers starting at
     * [startSeq], answering the receiver's control-channel resend request. The
     * caller (the control server) wraps them in the retransmit header and sends
     * them back to the request's source address. Runs on the control thread.
     */
    fun resend(startSeq: Int, count: Int): List<ByteArray> {
        val found = mutableListOf<ByteArray>()
        val snapshot = synchronized(resendCache) { resendCache.toMap() }
        for (offset in 0 until count) {
            snapshot[(startSeq + offset) and 0xFFFF]?.let(found::add)
        }
        return found
    }

    override fun close() {
        closed.set(true)
        syncThread?.interrupt()
        runCatching { socket?.close() }
        socket = null
        recentFrames.clear()
        synchronized(resendCache) { resendCache.clear() }
        Log.i(
            TAG,
            "Audio channel closed after ${packetsSent.get()} packets, ${bytesSent.get() / 1024} KiB",
        )
    }

    private companion object {
        const val TAG = "AudioStream"

        /** Each frame is transmitted this many times. */
        const val FRAME_REPEATS = 3

        /** Default RTP timestamp step (AAC-ELD); the constructor overrides it. */
        const val SAMPLES_PER_FRAME = 480L

        /** Marker packets sent before the first real frame. */
        const val PRIMING_PACKETS = 4

        /** Roughly 16 seconds of audio at 30 frames/s, for retransmit requests. */
        const val RESEND_CACHE_SIZE = 500

        private const val SYNC_PACKET_TYPE = 0xD4
        private const val SYNC_PACKET_BYTES = 20
        private const val SYNC_INTERVAL_MS = 1_000L

        /** NTP timestamps count from 1900, not 1970. */
        private const val NTP_UNIX_OFFSET_SECONDS = 2_208_988_800L

        /** UxPlay's expected next-packet lead for ALAC (441 * 175). */
        private const val SYNC_LEAD_SAMPLES = 77_175L
    }
}
