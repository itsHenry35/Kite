package com.henry.kite.airplay.discovery

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.henry.kite.airplay.plist.BinaryPlistReader
import com.henry.kite.airplay.plist.asData
import com.henry.kite.airplay.plist.asDict
import com.henry.kite.airplay.plist.asLong
import com.henry.kite.airplay.plist.asString
import com.henry.kite.airplay.rtsp.RtspClient
import com.henry.kite.airplay.rtsp.RtspRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Discovers AirPlay receivers broadcasting the uxplay-style Bluetooth LE
 * beacon: an Apple Continuity manufacturer-data data unit (company 0x004C)
 * carrying `type 0x09 (AirPlay discovery), length 0x08, flags, seed, IPv4
 * (4 bytes), port (2 bytes big-endian)`. Receivers advertise this when the
 * network blocks mDNS multicast.
 *
 * The beacon carries no metadata, so each newly seen endpoint is enriched
 * with one `GET /info` RTSP round trip (features decide the pairing path).
 * Enrichment failures leave a minimal receiver that still shows up in the
 * picker — its connect flow re-probes /info anyway.
 */
object BleBeaconScanner {

    /** Apple's Bluetooth company identifier. */
    private const val APPLE_COMPANY_ID = 0x004C

    /** Apple Continuity data unit type for AirPlay service discovery. */
    private const val AIRPLAY_DATA_UNIT_TYPE = 0x09

    /** True when the platform permissions needed for BLE scanning are granted. */
    fun hasPermission(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Runtime permissions the caller must hold for scanning, per API level. */
    fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * Emits every distinct beacon endpoint as it is discovered, upgrading
     * entries in place once their /info enrichment completes. Completes
     * immediately when Bluetooth is off, absent, or not permitted.
     */
    fun scan(context: Context): Flow<AirPlayReceiver> = callbackFlow {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || scanner == null || !hasPermission(context)) {
            Log.i(TAG, "BLE discovery unavailable (no adapter, off, or permission missing)")
            close()
            return@callbackFlow
        }

        // endpoint → the best-known receiver so far.
        val known = LinkedHashMap<String, AirPlayReceiver>()
        val scope = this

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val placeholder = parseBeacon(result.scanRecord) ?: return
                val key = "${placeholder.address.hostAddress}:${placeholder.port}"
                if (known.containsKey(key)) return // One enrichment per endpoint, ever.
                known[key] = placeholder // Reserved, not shown: a bare beacon has no metadata.
                launch(Dispatchers.IO) {
                    // Only confirmed mirroring receivers make the list at all:
                    // unreachable endpoints (stale beacon IP) and audio-only
                    // advertisements stay hidden rather than showing up as
                    // "mirroring unsupported".
                    val enriched = runCatching { enrich(placeholder, deviceName(context)) }
                        .getOrNull()
                        ?: return@launch
                    if (!enriched.supportsMirroring) return@launch
                    Log.i(TAG, "BLE beacon: $enriched")
                    known[key] = enriched
                    trySend(enriched)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "BLE scan failed: $errorCode")
                close()
            }
        }

        val filter = ScanFilter.Builder()
            .setManufacturerData(APPLE_COMPANY_ID, ByteArray(0))
            .build()
        val settings = android.bluetooth.le.ScanSettings.Builder()
            .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED)
            .build()
        runCatching { scanner.startScan(listOf(filter), settings, callback) }
            .onFailure {
                Log.w(TAG, "BLE scan start failed", it)
                close()
                return@callbackFlow
            }

        awaitClose {
            runCatching { scanner.stopScan(callback) }
        }
    }.conflate()

    /**
     * Reads the AirPlay data unit out of a scan record: manufacturer data for
     * Apple (0x004C) must start with type 0x09 and length 0x08, followed by
     * flags, seed, an IPv4 address, and a big-endian port.
     */
    private fun parseBeacon(record: ScanRecord?): AirPlayReceiver? {
        val data = record?.getManufacturerSpecificData(APPLE_COMPANY_ID) ?: return null
        if (data.size < 10 || data[0] != AIRPLAY_DATA_UNIT_TYPE.toByte() || data[1] != 0x08.toByte()) {
            return null
        }
        val address = InetAddress.getByAddress(data.copyOfRange(4, 8)) as? Inet4Address
            ?: return null
        val port = ((data[8].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
        if (port <= 0) return null
        return minimal(address, port)
    }

    private fun minimal(address: Inet4Address, port: Int): AirPlayReceiver = AirPlayReceiver(
        name = "BLE ${address.hostAddress}",
        address = address,
        port = port,
        deviceId = null,
        model = null,
        sourceVersion = null,
        publicIdentifier = null,
        publicKeyHex = null,
        features = FeatureFlags.NONE,
    )

    /**
     * One GET /info round trip fills in what the beacon cannot carry: name,
     * device id, model, and — most importantly — the feature bits that pick
     * the pairing path. Returns null when the endpoint turns out not to be a
     * usable AirPlay receiver.
     */
    private suspend fun enrich(receiver: AirPlayReceiver, clientName: String): AirPlayReceiver? {
        // Same identity the real session will declare (the user's device name):
        // the receiver remembers the client name from this request and macOS
        // uses it for its "… would like to AirPlay" prompt.
        val client = RtspClient(receiver.address, receiver.port, clientName)
        return try {
            client.connect()
            val response = client.send(
                RtspRequest(
                    "GET",
                    "/info",
                    headers = mapOf("Content-Type" to "application/x-apple-binary-plist"),
                ),
            )
            val dict = response.takeIf { it.isSuccess }?.body
                ?.let { body -> runCatching { BinaryPlistReader.read(body).asDict() }.getOrNull() }
            val features = dict?.get("features")?.asLong()
            if (features == null) {
                null
            } else {
                receiver.copy(
                    name = dict["name"]?.asString() ?: receiver.name,
                    deviceId = dict["deviceid"]?.asString() ?: receiver.deviceId,
                    model = dict["model"]?.asString() ?: receiver.model,
                    sourceVersion = dict["srcvers"]?.asString() ?: receiver.sourceVersion,
                    publicIdentifier = dict["pi"]?.asString() ?: receiver.publicIdentifier,
                    publicKeyHex = dict["pk"]?.asData()?.toHexString() ?: receiver.publicKeyHex,
                    features = FeatureFlags(features),
                )
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    /**
     * The user-visible device name from system settings (e.g. "Henry's Phone"),
     * falling back to the model. Must match what the session declares: receivers
     * remember it per peer for their AirPlay prompts.
     */
    fun deviceName(context: Context): String = android.provider.Settings.Global.getString(
        context.contentResolver,
        "device_name",
    ) ?: Build.MODEL ?: "Android"

    private const val TAG = "BleBeaconScanner"
}
