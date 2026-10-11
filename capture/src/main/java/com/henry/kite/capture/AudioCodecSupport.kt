package com.henry.kite.capture

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * What the device's AAC encoders can actually do.
 *
 * AirPlay mirroring wants AAC-ELD, which receivers advertise as compression type
 * 0x80. Support for the ELD profile is not universal — plenty of Qualcomm parts
 * ship AAC-LC only — so the encoder has to be probed rather than assumed.
 */
object AudioCodecSupport {

    private const val TAG = "AudioCodecSupport"
    private const val MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC

    /** One encoder and the AAC profiles it claims. */
    data class EncoderInfo(val name: String, val isHardwareAccelerated: Boolean, val profiles: Set<Int>) {
        val supportsEld: Boolean get() = CodecProfileLevel.AACObjectELD in profiles
        val supportsLc: Boolean get() = CodecProfileLevel.AACObjectLC in profiles
    }

    /** Every AAC encoder on the device, in the order the framework prefers them. */
    fun aacEncoders(): List<EncoderInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { it.isEncoder && MIME_AAC in it.supportedTypes.map(String::lowercase) }
        .map { info ->
            val profiles = runCatching {
                info.getCapabilitiesForType(MIME_AAC)
                    .profileLevels
                    .map { it.profile }
                    .toSet()
            }.getOrDefault(emptySet())
            EncoderInfo(
                name = info.name,
                isHardwareAccelerated = info.isHardwareAccelerated,
                profiles = profiles,
            )
        }

    /** True when some encoder on this device offers the ELD profile. */
    fun hasEldEncoder(): Boolean = aacEncoders().any { it.supportsEld }

    /** Dumps what was found, so a device's real capabilities land in the log. */
    fun logSupport() {
        val encoders = aacEncoders()
        if (encoders.isEmpty()) {
            Log.w(TAG, "No AAC encoder found at all")
            return
        }
        encoders.forEach { encoder ->
            Log.i(
                TAG,
                "${encoder.name} (${if (encoder.isHardwareAccelerated) "hw" else "sw"}): " +
                    "ELD=${encoder.supportsEld} LC=${encoder.supportsLc} " +
                    "profiles=${encoder.profiles.map(::profileName)}",
            )
        }
        Log.i(TAG, "AAC-ELD available: ${hasEldEncoder()}")
    }

    private fun profileName(profile: Int): String = when (profile) {
        CodecProfileLevel.AACObjectMain -> "Main"
        CodecProfileLevel.AACObjectLC -> "LC"
        CodecProfileLevel.AACObjectSSR -> "SSR"
        CodecProfileLevel.AACObjectLTP -> "LTP"
        CodecProfileLevel.AACObjectHE -> "HE"
        CodecProfileLevel.AACObjectScalable -> "Scalable"
        CodecProfileLevel.AACObjectERLC -> "ER-LC"
        CodecProfileLevel.AACObjectERScalable -> "ER-Scalable"
        CodecProfileLevel.AACObjectLD -> "LD"
        CodecProfileLevel.AACObjectHE_PS -> "HE-PS"
        CodecProfileLevel.AACObjectELD -> "ELD"
        CodecProfileLevel.AACObjectXHE -> "xHE"
        else -> "profile$profile"
    }
}
