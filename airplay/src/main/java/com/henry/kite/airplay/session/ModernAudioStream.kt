package com.henry.kite.airplay.session

import android.util.Log
import com.henry.kite.airplay.crypto.OriginalChaCha20Poly1305
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Sender for the modern screen-audio RTP stream (doubletake audio.go): ALAC
 * frames over UDP as independent PT 0x60 packets, each transmitted three times
 * (`[N-2, N-1, N]` — receivers count on the repeats to fill the frame rate).
 * The codec is ALAC because that is what doubletake's default build sends to
 * macOS — its AAC-ELD path is an opt-in that has never produced a
 * verified-sound session against a real receiver. The RFC 2198 compound form
 * (PT 0x61) only applies to receivers advertising feature bit 61, which macOS
 * does not. Repeats replay the exact original datagram, so a repeated frame
 * also repeats its nonce.
 *
 * Every packet appends its 8-byte nonce (little-endian packet counter) after
 * the tag; the AAD is the RTP timestamp + SSRC (header[4:12]), and the seal
 * itself runs under that same counter nonce. The RTP timestamp advances 352
 * samples per frame from a random 32-bit epoch.
 * TimeAnnounce sync packets (PT 0xd7) map the RTP position onto the receiver's
 * PTP timeline. A history ring answers the receiver's 0xd5 retransmit requests
 * with 0xd6 responses on the control socket.
 */
class ModernAudioStream(
    private val remoteAddress: InetAddress,
    private val remoteDataPort: Int,
    private val remoteControlPort: Int,
    private val dataSocket: DatagramSocket,
    private val controlSocket: DatagramSocket,
    private val streamKey: ByteArray,
    private val clock: MirrorClock,
    private val timelineId: Long,
) {

    // Mirroring audio RTP uses SSRC=0 (doubletake); the receiver does not
    // correlate audio by SSRC and working captures carry all-zero here.
    private val ssrc = 0
    private val rtpTime = AtomicLong(java.security.SecureRandom().nextLong() and 0xFFFFFFFFL)
    private val seq = AtomicLong(1)
    private val nonceCounter = AtomicLong(0)
    private val latencySamples = 3748

    /** seq → history entry; feeds redundancy blocks and retransmits. */
    private val history = LinkedHashMap<Int, HistoryEntry>()
    private val historyLock = Any()

    /** The last [REDUNDANCY_DEPTH] sealed datagrams, replayed on every frame. */
    private val redundancyWindow = ArrayDeque<ByteArray>()
    private val redundancyLock = Any()

    /** Guards the one-time initial TimeAnnounce at the first real frame. */
    private val announcedFirst = AtomicBoolean(false)

    private val framesSent = AtomicLong(0)
    private val bytesSent = AtomicLong(0)

    /** Bytes/frames actually sent, for the session statistics tick. */
    val statistics: Statistics
        get() = Statistics(bytesSent.get(), framesSent.get())

    data class Statistics(val bytes: Long, val frames: Long)
    private var lastReportAt = 0L

    private class HistoryEntry(val datagram: ByteArray, val frame: ByteArray, val rtpTime: Int)

    fun start() {
        // No TimeAnnounce here: announcing at SETUP time puts the playhead
        // hundreds of milliseconds ahead of the first real frame (encoder and
        // AudioRecord startup), and the receiver then reads the entire stream
        // as already stale and plays nothing. The initial announce goes out
        // with the first frame in [sendDataFrame].
        // The receiver reports missing audio on the control socket; answer
        // 0xd5 requests with 0xd6-wrapped originals from the history ring.
        Thread {
            val buf = ByteArray(1024)
            while (!controlSocket.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                runCatching { controlSocket.receive(packet) }.onFailure { return@Thread }
                answerRetransmit(packet.data, packet.address, packet.port)
            }
        }.apply {
            isDaemon = true
            name = "kite-audio-ctrl"
        }.start()

        // Refresh the RTP↔timeline mapping once per second (doubletake's
        // TimeAnnounce ticker). The single announce at the first frame only
        // seeds the mapping; without the periodic refresh the receiver's
        // playout approximation drifts until it stops rendering.
        Thread {
            while (!controlSocket.isClosed) {
                try {
                    Thread.sleep(SYNC_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@Thread
                }
                runCatching { refreshSync() }
            }
        }.apply {
            isDaemon = true
            name = "kite-audio-sync"
        }.start()
    }

    fun refreshSync() {
        // Before the first frame there is no anchored mapping to refresh.
        if (!announcedFirst.get()) return
        sendSyncPacket(isFirst = false)
    }

    /**
     * TimeAnnounce (doubletake sendSyncPacketAt, PTP variant): maps the audio
     * playhead RTP position onto the receiver's PTP timeline. Bytes 8–16 carry
     * the clock value as PTP NANOSECONDS (doubletake ptpNanoseconds), not the
     * wire's usual 32.32 compact form.
     */
    fun sendSyncPacket(isFirst: Boolean) {
        // rtpTime points at the NEXT frame (getAndAdd reserves each frame's
        // position); the last sent frame is one step back, matching doubletake
        // keeping as.rtpTime on the newest transmitted frame.
        val lastSentRtp = rtpTime.get().toInt() - SAMPLES_PER_FRAME.toInt()
        val packet = ByteArray(28)
        packet[0] = if (isFirst) 0x90.toByte() else 0x80.toByte() // V=2, X=1 on the first
        packet[1] = 0xd7.toByte() // TimeAnnounce, PTP flavour
        packet[2] = 0x00
        packet[3] = 0x04 // seq field is constant 4 in working captures
        val playheadRtp = lastSentRtp - latencySamples
        for (i in 0 until 4) packet[4 + i] = (playheadRtp shr (24 - 8 * i)).toByte()
        val networkTime = clock.now()
        val seconds = networkTime ushr 32
        val fraction = networkTime and 0xFFFFFFFFL
        val ptpNanos = seconds * 1_000_000_000L + (fraction * 1_000_000_000L shr 32)
        for (i in 0 until 8) packet[8 + i] = (ptpNanos ushr (56 - 8 * i)).toByte()
        for (i in 0 until 4) packet[16 + i] = (playheadRtp shr (24 - 8 * i)).toByte()
        for (i in 0 until 8) packet[20 + i] = (timelineId ushr (56 - 8 * i)).toByte()
        controlSocket.send(DatagramPacket(packet, packet.size, remoteAddress, remoteControlPort))
    }

    fun sendDataFrame(frame: ByteArray) {
        // RTP advances one ELD frame per frame (doubletake frameRTP += spf);
        // the first frame uses the random epoch itself.
        val rtp = rtpTime.getAndAdd(SAMPLES_PER_FRAME).toInt()
        val seqNum = seq.getAndIncrement().toInt()

        // Sealed primary (PT 0x60): ChaCha20-Poly1305 64x64, AAD = RTP
        // timestamp + SSRC, nonce appended after the tag. The receiver reads
        // that trailing nonce and decrypts with exactly that value, so the
        // seal must use the same counter — sealing under a fixed nonce leaves
        // every packet after the first failing Poly1305 at the receiver.
        // Kept in the history ring for 0xd5/0xd6 retransmit requests.
        val nonce = ByteArray(8)
        val counter = nonceCounter.getAndIncrement()
        for (i in 0 until 8) nonce[i] = ((counter ushr (8 * i)) and 0xFF).toByte()
        val header = rtpHeader(rtp, seqNum, PRIMARY_PAYLOAD_TYPE.toByte())
        val sealed = OriginalChaCha20Poly1305(streamKey, nonce).seal(
            aad = header.copyOfRange(4, 12),
            plaintext = frame,
        )
        val primary = header + sealed + nonce

        // First real frame: anchor the RTP↔timeline mapping right now, so the
        // announced playhead is ~one latency window ahead of actual arrival
        // (doubletake announces at the first frame's PTS, never before).
        if (announcedFirst.compareAndSet(false, true)) {
            sendSyncPacket(isFirst = true)
        }

        // The newest frame plus up to two previous ones, oldest first, each an
        // independent packet replaying the same bytes (nonce included).
        synchronized(redundancyLock) {
            redundancyWindow.addLast(primary)
            while (redundancyWindow.size > REDUNDANCY_DEPTH) {
                redundancyWindow.removeFirst()
            }
            redundancyWindow.forEach { send(it, remoteDataPort) }
        }
        if (seqNum % 100 == 1) {
            Log.d(TAG, "audio frame seq=$seqNum bytes=${frame.size} rtp=$rtp")
        }
        // doubletake-style periodic sender report: proves frames keep moving
        // and shows the send rate next to the playout lead.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReportAt >= REPORT_INTERVAL_MS) {
            val elapsed = (now - lastReportAt) / 1000.0
            val frames = framesSent.incrementAndGet()
            val bytes = bytesSent.addAndGet(primary.size.toLong())
            if (lastReportAt != 0L) {
                Log.i(
                    TAG,
                    "[AUDIO-TX] frames=$frames seq=$seqNum rtp=$rtp " +
                        "rate=%.1f frames/s, %.0f kB/s payload=%dB"
                            .format(frames / elapsed, bytes / elapsed / 1024.0, frame.size),
                )
            }
            lastReportAt = now
        } else {
            framesSent.incrementAndGet()
            bytesSent.addAndGet(primary.size.toLong())
        }
        synchronized(historyLock) {
            history[seqNum] = HistoryEntry(primary, frame, rtp)
            while (history.size > RETRANSMIT_HISTORY) {
                history.remove(history.keys.first())
            }
        }
    }

    /** Answers a 0xd5 retransmit request with 0xd6-wrapped original datagrams. */
    fun answerRetransmit(request: ByteArray, from: InetAddress, port: Int) {
        if (request.size < 8 || (request[1].toInt() and 0xFF) != 0xD5) return
        val requestSeq = ((request[2].toInt() and 0xFF) shl 8) or (request[3].toInt() and 0xFF)
        val first = ((request[4].toInt() and 0xFF) shl 8) or (request[5].toInt() and 0xFF)
        val count = ((request[6].toInt() and 0xFF) shl 8) or (request[7].toInt() and 0xFF)
        for (offset in 0 until count) {
            val seqNum = (first + offset) and 0xFFFF
            val original = synchronized(historyLock) { history[seqNum]?.datagram }
            val response = if (original != null) {
                // 0xd6 wrapper + the exact original datagram.
                byteArrayOf(0x80.toByte(), 0xD6.toByte()) +
                    ((requestSeq shr 8) and 0xFF).toByte() + (requestSeq and 0xFF).toByte() + original
            } else {
                // History expired: the 8-byte "futile" reply for this sequence
                // tells the receiver that retrying is pointless, and the batch
                // is abandoned — later sequences cannot be served either.
                val futile = ByteArray(8)
                futile[0] = 0x80.toByte()
                futile[1] = 0xD6.toByte()
                futile[2] = ((requestSeq shr 8) and 0xFF).toByte()
                futile[3] = (requestSeq and 0xFF).toByte()
                futile[4] = ((seqNum shr 8) and 0xFF).toByte()
                futile[5] = (seqNum and 0xFF).toByte()
                // The listener thread exits when the socket closes; a request
                // that raced the close must not throw out of that thread.
                runCatching {
                    controlSocket.send(DatagramPacket(futile, futile.size, from, port))
                }
                return
            }
            runCatching {
                controlSocket.send(DatagramPacket(response, response.size, from, port))
            }
        }
    }

    private fun rtpHeader(rtp: Int, seqNum: Int, payloadType: Byte): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = payloadType
        header[2] = ((seqNum shr 8) and 0xFF).toByte()
        header[3] = (seqNum and 0xFF).toByte()
        header[4] = (rtp shr 24).toByte()
        header[5] = (rtp shr 16).toByte()
        header[6] = (rtp shr 8).toByte()
        header[7] = rtp.toByte()
        header[8] = (ssrc shr 24).toByte()
        header[9] = (ssrc shr 16).toByte()
        header[10] = (ssrc shr 8).toByte()
        header[11] = ssrc.toByte()
        return header
    }

    private fun send(packet: ByteArray, remotePort: Int) {
        dataSocket.send(DatagramPacket(packet, packet.size, remoteAddress, remotePort))
    }

    /** Closes both UDP sockets; the control listener thread exits on receive error. */
    fun close() {
        runCatching { dataSocket.close() }
        runCatching { controlSocket.close() }
    }

    companion object {
        private const val TAG = "ModernAudioStream"
        private const val RETRANSMIT_HISTORY = 512
        private const val REPORT_INTERVAL_MS = 5_000L

        /** TimeAnnounce refresh cadence (doubletake refreshes once a second). */
        private const val SYNC_INTERVAL_MS = 1_000L

        /** Frames per redundancy group (doubletake audioRedundancyCount + 1). */
        private const val REDUNDANCY_DEPTH = 3

        /** ALAC frame length; the RTP timestamp step. */
        const val SAMPLES_PER_FRAME = 352L
        private const val PRIMARY_PAYLOAD_TYPE = 0x60
    }
}
