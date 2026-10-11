package com.henry.kite.airplay.session

import android.util.Log
import com.henry.kite.airplay.crypto.ChaChaPolyCipher
import com.henry.kite.airplay.crypto.HkdfSha512
import com.henry.kite.airplay.pairing.SessionKeys
import com.henry.kite.airplay.plist.BinaryPlistReader
import com.henry.kite.airplay.plist.asDict
import com.henry.kite.airplay.plist.asLong
import com.henry.kite.airplay.plist.asString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The receiver's event channel (doubletake event_channel.go): after the control
 * SETUP the receiver publishes an `eventPort` and the sender connects to it.
 * The channel carries RTSP-style receiver commands inside HAP-encrypted frames,
 * with the key directions REVERSED relative to the control channel — receiver
 * commands arrive under the Events-Write key and sender acknowledgements go
 * back under the Events-Read key.
 *
 * The only command that affects us is `updateTimingPeerInfo`, which re-anchors
 * the PTP clock after a timing-peer change; everything else is acknowledged
 * with an empty 200 so the receiver keeps the session healthy.
 */
class EventChannel private constructor(
    private val socket: Socket,
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream,
    private val keys: SessionKeys,
    private val clock: MirrorClock,
    private val scope: CoroutineScope,
) : java.io.Closeable {
    private var writeCounter = 0L
    private var readCounter = 0L

    private fun writeFrame(plaintext: ByteArray) {
        var offset = 0
        while (offset < plaintext.size) {
            val length = minOf(MAX_FRAME, plaintext.size - offset)
            val sizeBytes = byteArrayOf(
                (length and 0xFF).toByte(),
                ((length shr 8) and 0xFF).toByte(),
            )
            val sealed = ChaChaPolyCipher.seal(
                keys.writeKey,
                ChaChaPolyCipher.nonce(writeCounter),
                plaintext.copyOfRange(offset, offset + length),
                sizeBytes,
            )
            output.write(sizeBytes)
            output.write(sealed)
            offset += length
            writeCounter++
        }
        output.flush()
    }

    /** Reads one HAP frame; returns the plaintext or null at end of stream. */
    private fun readFrame(): ByteArray? {
        val sizeBytes = ByteArray(2)
        if (input.read(sizeBytes) < 2) return null
        val size = (sizeBytes[0].toInt() and 0xFF) or ((sizeBytes[1].toInt() and 0xFF) shl 8)
        if (size < 1 || size > MAX_FRAME) throw IllegalStateException("Invalid event frame size $size")
        val sealed = ByteArray(size + TAG_LEN)
        if (input.read(sealed) < sealed.size) return null
        return ChaChaPolyCipher.open(
            keys.readKey,
            ChaChaPolyCipher.nonce(readCounter),
            sealed,
            sizeBytes,
        )
    }

    override fun close() {
        runCatching { socket.close() }
        scope.cancel()
    }

    private fun serve() {
        scope.launch {
            try {
                serveLoop()
            } catch (e: Exception) {
                Log.w(TAG, "Event channel ended: ${e.message}")
            } finally {
                runCatching { close() }
            }
        }
    }

    private fun serveLoop() {
        val pending = StringBuilder()
        while (true) {
            val plain = readFrame() ?: return
            pending.append(String(plain, Charsets.ISO_8859_1))
            val request = readRequest(pending) ?: continue
            pending.setLength(0)
            pending.append(request.trailing)

            Log.d(TAG, "<- ${request.method} ${request.path} (CSeq ${request.cseq})")
            if (request.clockId != null) {
                clock.reanchorTimeline(request.clockId)
            }
            writeFrame(
                "RTSP/1.0 200 OK\r\nCSeq: ${request.cseq}\r\nContent-Length: 0\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1),
            )
        }
    }

    private class ParsedRequest(
        val method: String,
        val path: String,
        val cseq: Long,
        val clockId: Long?,
        val trailing: String,
    )

    /**
     * Pulls one complete RTSP-style request off [pending]; null while the
     * buffer holds less than a full request. Anything after the blank line (a
     * piped-in next request) is carried back in [ParsedRequest.trailing].
     */
    private fun readRequest(pending: StringBuilder): ParsedRequest? {
        val text = pending.toString()
        val headerEnd = text.indexOf("\r\n\r\n")
        if (headerEnd < 0) return null
        val headerBlock = text.substring(0, headerEnd)
        val bodyStart = headerEnd + 4

        val lines = headerBlock.split("\r\n")
        val requestLine = lines.firstOrNull() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        var cseq = 0L
        var contentLength = 0
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            when (line.substring(0, colon).trim().lowercase()) {
                "cseq" -> cseq = line.substring(colon + 1).trim().toLongOrNull() ?: 0L
                "content-length" -> contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
            }
        }
        if (text.length < bodyStart + contentLength) return null
        val body = text.substring(bodyStart, bodyStart + contentLength)
        val trailing = text.substring(bodyStart + contentLength)

        var clockId: Long? = null
        if (contentLength > 0) {
            runCatching {
                val dict = BinaryPlistReader.read(body.toByteArray(Charsets.ISO_8859_1)).asDict()
                if (dict?.get("type").asString() == "updateTimingPeerInfo") {
                    clockId = dict?.get("value").asDict()?.get("ClockID")?.asLong()
                }
            }
        }
        return ParsedRequest(parts[0], parts[1], cseq, clockId, trailing)
    }

    companion object {
        private const val TAG = "EventChannel"
        private const val MAX_FRAME = 0xFFFF
        private const val TAG_LEN = 16

        /**
         * Connects to the receiver's event port and starts the serve loop that
         * acknowledges receiver commands. Returns null when the receiver
         * published no event port.
         */
        fun connect(
            hostAddress: java.net.InetAddress,
            eventPort: Int,
            sharedSecret: ByteArray,
            clock: MirrorClock,
        ): EventChannel? {
            if (eventPort <= 0) return null
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(hostAddress, eventPort), 3_000)

            // Key directions are intentionally reversed (see class doc).
            val keys = SessionKeys(
                sharedSecret = sharedSecret,
                writeKey = HkdfSha512.derive(sharedSecret, "Events-Salt", "Events-Read-Encryption-Key"),
                readKey = HkdfSha512.derive(sharedSecret, "Events-Salt", "Events-Write-Encryption-Key"),
            )
            val channel = EventChannel(
                socket,
                BufferedInputStream(socket.getInputStream()),
                BufferedOutputStream(socket.getOutputStream()),
                keys,
                clock,
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
            channel.serve()
            Log.i(TAG, "Connected to receiver event port $eventPort (encrypted)")
            return channel
        }
    }
}
