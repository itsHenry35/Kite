package com.henry.kite.airplay.discovery

import java.net.InetAddress

/**
 * A receiver found on the network, built from one `_airplay._tcp` or
 * `_raop._tcp` service record.
 */
data class AirPlayReceiver(
    /** Friendly name from the service instance, e.g. "Living Room". */
    val name: String,
    val address: InetAddress,
    val port: Int,
    /** `deviceid` TXT key: the receiver's MAC address as a string. Stable identity. */
    val deviceId: String?,
    /** `model` TXT key, e.g. "AppleTV14,1" or "UxPlay". */
    val model: String?,
    /** `srcvers` TXT key: the AirPlay source version, e.g. "770.8.1". */
    val sourceVersion: String?,
    /** `pi` TXT key: public identifier, a UUID. */
    val publicIdentifier: String?,
    /** `pk` TXT key: the receiver's long-term Ed25519 public key, hex encoded. */
    val publicKeyHex: String?,
    val features: FeatureFlags,
    /** True for `_raop._tcp` instances: classic AirPlay audio-only, no mirroring. */
    val isAudioOnly: Boolean = false,
    /** True when the same endpoint ALSO advertises `_raop._tcp` (audio-only casting). */
    val hasAudioOnlyService: Boolean = false,
    /** Every TXT key, kept so unparsed fields stay inspectable while debugging. */
    val txtRecords: Map<String, String> = emptyMap(),
) {
    /** Stable key for persisting credentials; falls back to host:port if no deviceid. */
    val stableId: String get() = deviceId ?: "${address.hostAddress}:$port"

    val displayModel: String get() = model ?: "Unknown model"

    /** Whether this receiver can accept a screen mirroring session at all. */
    val supportsMirroring: Boolean get() = !isAudioOnly && features.supportsScreenMirroring

    /**
     * Folds two mDNS services that share one endpoint (a device advertising
     * both `_airplay` and `_raop` on the same port — every Mac does) into one
     * receiver. Metadata from the mirroring side wins; the audio-only flag
     * survives only when BOTH sides are audio-only.
     */
    fun mergedWith(other: AirPlayReceiver): AirPlayReceiver = copy(
        name = if (isAudioOnly && !other.isAudioOnly) other.name else name,
        deviceId = deviceId ?: other.deviceId,
        model = model ?: other.model,
        sourceVersion = sourceVersion ?: other.sourceVersion,
        publicIdentifier = publicIdentifier ?: other.publicIdentifier,
        publicKeyHex = publicKeyHex ?: other.publicKeyHex,
        features = FeatureFlags(features.raw or other.features.raw),
        isAudioOnly = isAudioOnly && other.isAudioOnly,
        hasAudioOnlyService = hasAudioOnlyService || other.isAudioOnly || other.hasAudioOnlyService,
        txtRecords = if (txtRecords.size >= other.txtRecords.size) txtRecords else other.txtRecords,
    )

    /**
     * `pw` TXT key, or the password bit (0x4) of `sf`: the receiver wants an
     * AirPlay password before any RTSP traffic succeeds.
     */
    val requiresPassword: Boolean
        get() = txtRecords["pw"]?.equals("true", ignoreCase = true)
            ?: (txtRecords["sf"]?.toIntOrNull(16)?.and(SF_PASSWORD_FLAG) != 0)

    /** `cn` TXT key: advertised audio codecs. 0 = PCM/L16, 1 = ALAC, 2 = AAC-LC. */
    val supportsPcmAudio: Boolean
        get() = txtRecords["cn"]?.split(',')?.any { it.trim() == CODEC_PCM } ?: true

    /** Which pairing path [com.henry.kite.airplay.session.AirPlaySession] should take. */
    val pairingPath: PairingPath
        get() = when {
            features.supportsUnifiedPairSetup -> PairingPath.HAP
            features.supportsLegacyPairing -> PairingPath.LEGACY
            else -> PairingPath.NONE
        }

    override fun toString(): String = "$name ($displayModel) at ${address.hostAddress}:$port via $pairingPath" +
        if (isAudioOnly) " [audio only]" else ""
}

enum class PairingPath {
    /** pair-setup / pair-verify with SRP6a-SHA512, Ed25519 and ChaCha20-Poly1305. */
    HAP,

    /** Older SHA-1 SRP flow. Kept for detection; not the path this project targets. */
    LEGACY,

    /** Receiver advertises no pairing at all, e.g. a stock UxPlay build. */
    NONE,
}

private const val SF_PASSWORD_FLAG = 0x4
private const val CODEC_PCM = "0"
