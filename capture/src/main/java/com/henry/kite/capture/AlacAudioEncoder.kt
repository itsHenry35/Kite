package com.henry.kite.capture

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.concurrent.thread
import android.media.AudioFormat as AndroidAudioFormat

/**
 * Captures playback audio and emits ALAC frames (ct=2, 352 samples per frame).
 *
 * The legacy AirPlay receivers this app targets validate each ELD frame against
 * the header Apple's encoder emits (first byte 0x8c-0x8e/0x80-0x82) and drop
 * frames produced by other encoders — Android's AAC encoder fails that check.
 * ALAC frames start with the channel-pair element (0x20), which every receiver
 * accepts, and the encoder here is pure software (see [AlacEncoder]), so no
 * device codec capability is involved.
 *
 * Audio comes from [AudioPlaybackCaptureConfiguration] — the same projection
 * grant as the screen capture. Reading is synchronous: ALAC encodes in tens of
 * microseconds per frame, well within the 8 ms a 352-sample frame allows.
 */
class AlacAudioEncoder(private val projection: MediaProjection) : AudioFrameSource {

    private val encoder = AlacEncoder()
    private var record: AudioRecord? = null

    @Volatile
    private var isCapturing = false

    @SuppressLint("MissingPermission") // Playback capture is authorised by the projection.
    @Suppress("LoopWithTooManyJumpStatements") // read-failure, short-read, and channel-closed exits
    override fun frames(): Flow<EncodedAudioFrame> = callbackFlow {
        isCapturing = true

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val audioFormat = AndroidAudioFormat.Builder()
            .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AndroidAudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AndroidAudioFormat.CHANNEL_IN_STEREO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(MIN_BUFFER_FLOOR_BYTES)

        val audioRecord = AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(minBuffer * BUFFER_SLACK)
            .build()
        record = audioRecord
        audioRecord.startRecording()

        val reader = thread(name = "kite-alac-reader") {
            val frameBytes = FRAME_SAMPLES * CHANNELS * 2
            val pcm = ByteArray(frameBytes)
            val frame = ByteArray(AlacEncoder.MAX_FRAME_BYTES)
            var samplesSubmitted = 0L
            while (isCapturing) {
                val read = try {
                    audioRecord.read(pcm, 0, frameBytes)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord read failed", e)
                    break
                }
                if (read < frameBytes) continue

                val encoded = encoder.encode(frame, pcm)
                val data = frame.copyOf(encoded)
                val timestampUs = samplesSubmitted * 1_000_000L / SAMPLE_RATE
                samplesSubmitted += FRAME_SAMPLES
                if (trySend(EncodedAudioFrame(data, timestampUs, false)).isClosed) break
            }
        }

        awaitClose {
            isCapturing = false
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
            record = null
            runCatching { reader.join(500) }
        }
    }

    override fun close() {
        isCapturing = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
    }

    private companion object {
        const val TAG = "AlacAudioEncoder"
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        const val FRAME_SAMPLES = AlacEncoder.FRAME_SAMPLES
        const val MIN_BUFFER_FLOOR_BYTES = 4 * 1024
        const val BUFFER_SLACK = 4
    }
}
