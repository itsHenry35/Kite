package com.henry.kite.airplay.discovery

/**
 * The `features` TXT value, which tells a sender what the receiver will accept.
 *
 * The wire format is either a single hex word or two comma-separated words holding
 * the low and high halves of a 64-bit mask, low first:
 *
 *     features=0x5A7FFFF7,0x1E
 *
 * Bit meanings come from community reverse engineering (pyatv's `AirPlayFlags`,
 * the openairplay notes, and the emanuelb AirPlay spec). Bits this project acts on
 * are the ones below; anything unnamed is still available through [raw].
 */
@JvmInline
value class FeatureFlags(val raw: Long) {

    private fun has(bit: Int): Boolean = (raw shr bit) and 1L == 1L

    // Content types
    val supportsVideo: Boolean get() = has(0)
    val supportsPhoto: Boolean get() = has(1)
    val supportsVideoFairPlay: Boolean get() = has(2)
    val supportsVideoVolumeControl: Boolean get() = has(3)
    val supportsVideoHttpLiveStreams: Boolean get() = has(4)

    /** Bit 7. Without this the receiver will not take a mirroring session. */
    val supportsScreenMirroring: Boolean get() = has(7)
    val supportsScreenRotate: Boolean get() = has(8)
    val supportsAudio: Boolean get() = has(9)

    /** Bit 12. AES-GCM stream encryption; present on receivers that also do HAP. */
    val supportsAesGcm: Boolean get() = has(12)
    val hasMfiHardware: Boolean get() = has(14)
    val hasMfiSoftFairPlay: Boolean get() = has(15)

    val receivesAudioPcm: Boolean get() = has(19)
    val receivesAudioAlac: Boolean get() = has(20)
    val receivesAudioAacLc: Boolean get() = has(21)
    val supportsUnencryptedAudio: Boolean get() = has(23)

    /** Bit 26. Third-party receivers set this even when they copy modern bits. */
    val thirdPartyPairing: Boolean get() = has(26)

    /** Bit 27. The pre-tvOS-10.2 SHA-1 SRP flow. */
    val supportsLegacyPairing: Boolean get() = has(27)
    val supportsRaop: Boolean get() = has(30)

    val supportsUnifiedMediaControl: Boolean get() = has(38)
    val supportsBufferedAudio: Boolean get() = has(40)

    /** Bit 41. PTP timing instead of the older NTP-style clock. */
    val supportsPtp: Boolean get() = has(41)

    /** Bit 42. Receiver accepts more than H.264 for mirroring, i.e. HEVC. */
    val supportsScreenMultiCodec: Boolean get() = has(42)
    val supportsSystemPairing: Boolean get() = has(43)
    val supportsHomeKitPairingAndAccessControl: Boolean get() = has(46)

    /** Bit 48. Encrypted control channel after pair-verify. */
    val supportsCoreUtilsPairingAndEncryption: Boolean get() = has(48)

    /** Bit 51. The modern `/pair-setup` + `/pair-verify` endpoints. */
    val supportsUnifiedPairSetupAndMfi: Boolean get() = has(51)

    /**
     * True when the receiver speaks the first-party CoreUtils/HAP profile
     * directly, which selects `X-Apple-HKP: 4/5` pairing and the PD-mixed
     * stream key. Third-party receivers retain HKP type 3 even when they copy
     * the modern feature bits (doubletake's `usesModernPairing`).
     */
    val usesModernPairing: Boolean
        get() = (
            supportsUnifiedMediaControl || supportsSystemPairing ||
                supportsHomeKitPairingAndAccessControl || supportsCoreUtilsPairingAndEncryption
            ) &&
            !thirdPartyPairing && !supportsUnifiedPairSetupAndMfi

    /**
     * True when the HAP path in [PairingPath.HAP] applies. Any one of these bits
     * is enough in practice; receivers are inconsistent about which they set.
     */
    val supportsUnifiedPairSetup: Boolean
        get() = supportsUnifiedPairSetupAndMfi ||
            supportsCoreUtilsPairingAndEncryption ||
            supportsHomeKitPairingAndAccessControl

    /** Names of the bits this class knows about, for the debug sheet in the UI. */
    fun describe(): List<String> = buildList {
        if (supportsVideo) add("video")
        if (supportsPhoto) add("photo")
        if (supportsVideoHttpLiveStreams) add("hls")
        if (supportsScreenMirroring) add("mirroring")
        if (supportsScreenMultiCodec) add("multi-codec")
        if (supportsAudio) add("audio")
        if (supportsRaop) add("raop")
        if (supportsAesGcm) add("aes-gcm")
        if (supportsPtp) add("ptp")
        if (supportsLegacyPairing) add("legacy-pairing")
        if (supportsUnifiedPairSetupAndMfi) add("unified-pair-setup")
        if (supportsCoreUtilsPairingAndEncryption) add("coreutils-pairing")
        if (supportsHomeKitPairingAndAccessControl) add("hap-access-control")
        if (supportsSystemPairing) add("system-pairing")
    }

    override fun toString(): String = "0x%X".format(raw)

    companion object {
        val NONE = FeatureFlags(0L)

        /** Parses `features` in either the single-word or comma-separated form. */
        fun parse(value: String?): FeatureFlags {
            if (value.isNullOrBlank()) return NONE
            val parts = value.split(',').map(String::trim).filter(String::isNotEmpty)
            return when (parts.size) {
                0 -> NONE

                1 -> FeatureFlags(parts[0].toHexLongOrZero())

                // Low word first, high word second.
                else -> FeatureFlags(
                    (parts[1].toHexLongOrZero() shl 32) or (parts[0].toHexLongOrZero() and 0xFFFFFFFFL),
                )
            }
        }

        private fun String.toHexLongOrZero(): Long = removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: 0L
    }
}
