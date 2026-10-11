package com.henry.kite.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import android.media.AudioFormat as AndroidAudioFormat

/**
 * Captures playback audio and emits AAC-ELD frames.
 *
 * Audio comes from [AudioPlaybackCaptureConfiguration], which reuses the same
 * [MediaProjection] grant the screen capture uses — no extra permission, but the
 * kernel of it is a policy decision by each app: only `USAGE_MEDIA` and
 * `USAGE_GAME` playback is capturable, and an app that sets
 * `setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)` stays silent. System sounds
 * and calls never arrive. A silent stream is therefore normal, not a failure.
 *
 * AAC-ELD (compression type 8) is what the modern AirPlay mirroring stream
 * uses. The profile is software-only on many devices, which is fine at this
 * bit rate.
 */
class AudioEncoder(private val projection: MediaProjection, private val format: AudioFormat = AudioFormat()) :
    AudioFrameSource {

    private var codec: MediaCodec? = null
    private var record: AudioRecord? = null
    private var callbackThread: HandlerThread? = null

    @Volatile
    private var isCapturing = false

    /**
     * Starts capture and emits every encoded frame. Cancelling the collector
     * tears the pipeline down.
     */
    @SuppressLint("MissingPermission") // Playback capture is authorised by the projection.
    override fun frames(): Flow<EncodedAudioFrame> = callbackFlow {
        val thread = HandlerThread("kite-audio-encoder").apply { start() }
        callbackThread = thread
        val handler = Handler(thread.looper)

        val mediaFormat = MediaFormat.createAudioFormat(
            MIME_AAC,
            format.sampleRate,
            format.channelCount,
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectELD,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, format.bitRate)
            // Enough for one ELD frame of stereo 16-bit PCM, with room to spare.
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }

        val encoder = MediaCodec.createEncoderByType(MIME_AAC)
        codec = encoder

        encoder.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // Input is pulled from AudioRecord on the reader thread below.
                    pendingInputs.add(index)
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0) {
                            aacFramesEmitted++
                            if (aacFramesEmitted % 100 == 1L) {
                                Log.d(TAG, "aac frame #$aacFramesEmitted bytes=${info.size} flags=${info.flags}")
                            }
                            trySend(toFrame(buffer, info))
                        }
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Audio output buffer $index went away", e)
                    } finally {
                        runCatching { codec.releaseOutputBuffer(index, false) }
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Audio encoder error (recoverable=${e.isRecoverable})", e)
                    close(e)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.i(TAG, "Audio encoder output format: $format")
                }
            },
            handler,
        )

        var readerThread: Thread? = null
        try {
            encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val audioRecord = createAudioRecord()
            record = audioRecord
            audioRecord.startRecording()

            isCapturing = true
            readerThread = thread(name = "kite-audio-reader") { pumpAudio(encoder, audioRecord) }

            Log.i(
                TAG,
                "Capturing audio ${format.sampleRate}Hz x${format.channelCount} " +
                    "AAC-ELD at ${format.bitRate / 1000} kbps",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            close(e)
        }

        awaitClose {
            isCapturing = false
            runCatching { readerThread?.join(READER_JOIN_TIMEOUT_MS) }
            releaseResources()
        }
    }

    /** Indices the codec handed us, waiting for PCM. */
    private val pendingInputs = java.util.concurrent.ConcurrentLinkedQueue<Int>()

    /**
     * Moves PCM from [AudioRecord] into the encoder.
     *
     * `AudioRecord.read` blocks, so this cannot live on the codec callback
     * handler without stalling the encoder's output.
     */
    private fun pumpAudio(encoder: MediaCodec, audioRecord: AudioRecord) {
        val frameBytes = format.channelCount * BYTES_PER_SAMPLE * AudioFormat.SAMPLES_PER_FRAME
        val pcm = ByteArray(frameBytes)

        while (isCapturing) {
            val read = runCatching { audioRecord.read(pcm, 0, pcm.size) }.getOrElse { error ->
                Log.w(TAG, "AudioRecord read failed", error)
                break
            }
            if (read <= 0) continue

            // Continuous PCM level probe: distinguishes "capture works but the
            // receiver rejects the stream" from "capture returns silence"
            // (unticked share-audio consent, or apps opting out of capture).
            pcmPeak = maxOf(pcmPeak, peakAmplitude(pcm, read))
            pcmFramesRead++
            if (pcmFramesRead % 100 == 1L) {
                Log.d(TAG, "pcm frame #$pcmFramesRead peak=$pcmPeak")
                pcmPeak = 0
            }

            val index = pendingInputs.poll() ?: continue
            val filled = runCatching {
                val input = encoder.getInputBuffer(index) ?: return@runCatching false
                input.clear()
                input.put(pcm, 0, read)
                encoder.queueInputBuffer(index, 0, read, presentationTimeUs(), 0)
                true
            }.getOrElse { error ->
                Log.w(TAG, "Queueing audio input failed", error)
                false
            }
            if (!filled) break
        }

        // Signal end of stream so the encoder drains cleanly.
        runCatching {
            pendingInputs.poll()?.let { index ->
                encoder.queueInputBuffer(index, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
    }

    private var samplesSubmitted = 0L

    /** Peak PCM amplitude since the last report; exposes silent capture. */
    private var pcmPeak = 0
    private var pcmFramesRead = 0L
    private var aacFramesEmitted = 0L

    /** Largest absolute 16-bit sample in the buffer. */
    private fun peakAmplitude(pcm: ByteArray, length: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val magnitude = if (sample < 0) -sample else sample
            if (magnitude > peak) peak = magnitude
            i += BYTES_PER_SAMPLE
        }
        return peak
    }

    /**
     * Timestamps derived from the sample count rather than the wall clock.
     *
     * Playback capture delivers a continuous stream, so counting samples keeps
     * the audio timeline monotonic and drift-free relative to its own rate.
     */
    private fun presentationTimeUs(): Long {
        val us = samplesSubmitted * 1_000_000L / format.sampleRate
        samplesSubmitted += AudioFormat.SAMPLES_PER_FRAME
        return us
    }

    private fun createAudioRecord(): AudioRecord {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val channelMask = if (format.channelCount == 1) {
            AndroidAudioFormat.CHANNEL_IN_MONO
        } else {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        }

        val audioFormat = AndroidAudioFormat.Builder()
            .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(format.sampleRate)
            .setChannelMask(channelMask)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            format.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MIN_BUFFER_FLOOR_BYTES)

        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            // Several ELD frames of slack, so a scheduling hiccup does not drop audio.
            .setBufferSizeInBytes(minBuffer * BUFFER_SLACK)
            .build()
    }

    private fun toFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo): EncodedAudioFrame {
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val bytes = ByteArray(info.size).also(buffer::get)
        return EncodedAudioFrame(
            data = bytes,
            presentationTimeUs = info.presentationTimeUs,
            isCodecConfig = isConfig,
        )
    }

    private fun releaseResources() {
        runCatching { record?.stop() }
        runCatching { record?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { callbackThread?.quitSafely() }
        pendingInputs.clear()
        record = null
        codec = null
        callbackThread = null
    }

    override fun close() {
        isCapturing = false
        releaseResources()
    }

    private companion object {
        const val TAG = "AudioEncoder"
        const val MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC
        const val BYTES_PER_SAMPLE = 2
        const val MAX_INPUT_SIZE = 8 * 1024
        const val MIN_BUFFER_FLOOR_BYTES = 4 * 1024
        const val BUFFER_SLACK = 4
        const val READER_JOIN_TIMEOUT_MS = 500L
    }
}
