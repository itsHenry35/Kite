package com.henry.kite.service

import com.henry.kite.airplay.discovery.AirPlayReceiver
import com.henry.kite.airplay.discovery.FeatureFlags
import java.io.Serializable
import java.net.InetAddress

/**
 * Intent-safe copy of [AirPlayReceiver].
 *
 * [InetAddress] is serializable but resolves its hostname on read, which can block.
 * Carrying the raw address bytes and rebuilding with
 * [InetAddress.getByAddress] avoids any lookup.
 */
data class SerializableReceiver(
    val name: String,
    val addressBytes: ByteArray,
    val port: Int,
    val deviceId: String?,
    val model: String?,
    val featuresRaw: Long,
    /** True for `_raop._tcp` audio-only receivers; decides the whole session path. */
    val audioOnly: Boolean = false,
) : Serializable {

    fun toReceiver(): AirPlayReceiver = AirPlayReceiver(
        name = name,
        address = InetAddress.getByAddress(addressBytes),
        port = port,
        deviceId = deviceId,
        model = model,
        sourceVersion = null,
        publicIdentifier = null,
        publicKeyHex = null,
        features = FeatureFlags(featuresRaw),
        isAudioOnly = audioOnly,
        txtRecords = emptyMap(),
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SerializableReceiver) return false
        return name == other.name &&
            addressBytes.contentEquals(other.addressBytes) &&
            port == other.port &&
            deviceId == other.deviceId &&
            model == other.model &&
            featuresRaw == other.featuresRaw
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + addressBytes.contentHashCode()
        result = 31 * result + port
        result = 31 * result + (deviceId?.hashCode() ?: 0)
        result = 31 * result + (model?.hashCode() ?: 0)
        result = 31 * result + featuresRaw.hashCode()
        return result
    }

    companion object {
        private const val serialVersionUID = 1L

        fun from(receiver: AirPlayReceiver): SerializableReceiver = SerializableReceiver(
            name = receiver.name,
            addressBytes = receiver.address.address,
            port = receiver.port,
            deviceId = receiver.deviceId,
            model = receiver.model,
            featuresRaw = receiver.features.raw,
            audioOnly = receiver.isAudioOnly,
        )
    }
}
