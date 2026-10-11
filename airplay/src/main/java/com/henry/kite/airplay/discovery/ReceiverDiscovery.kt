package com.henry.kite.airplay.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * Browses `_airplay._tcp.local` (mirroring receivers) and `_raop._tcp.local`
 * (classic AirPlay audio-only devices) via mDNS, plus the uxplay-style BLE
 * beacon (Apple data unit 0x09 carrying an IPv4:port) for networks where
 * multicast is blocked, and emits the merged receiver list on every change.
 *
 * JmDNS is used instead of [android.net.nsd.NsdManager] because NsdManager
 * resolves one service at a time and drops most TXT keys, and `features` decides
 * the entire pairing path.
 *
 * A multicast lock is held for the lifetime of the flow. Without it the Wi-Fi
 * driver filters the multicast group as soon as the screen turns off.
 */
class ReceiverDiscovery(private val context: Context) {

    /** Bumped by [refresh]; restarting the flows re-browses from scratch. */
    private val refreshTrigger = MutableStateFlow(0)

    /**
     * Last known discovery results, retained across flow restarts. Returning to
     * the app re-subscribes these flows; seeding from the snapshots keeps the
     * receiver list from flickering through empty on every re-entry.
     */
    @Volatile
    private var mdnsSnapshot: Map<String, AirPlayReceiver> = emptyMap()

    @Volatile
    private var bleSnapshot: Map<String, AirPlayReceiver> = emptyMap()

    // A network switch or reconnect leaves JmDNS bound to the old interface,
    // where it never hears anything again. Restarting the browse is the only
    // recovery, so it happens here automatically on Wi-Fi changes — there is
    // deliberately no manual refresh button. The callback lives as long as the
    // process (application-scoped singleton); there is nothing to unregister.
    init {
        val connectivity = context.applicationContext
            .getSystemService(ConnectivityManager::class.java)
        if (connectivity != null) {
            val wifi = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivity.registerNetworkCallback(
                wifi,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = refresh()

                    override fun onLost(network: Network) = refresh()
                },
            )
        }
    }

    /** Re-runs mDNS browsing and the BLE scan. */
    fun refresh() {
        refreshTrigger.value += 1
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun receivers(): Flow<List<AirPlayReceiver>> = combine(
        refreshTrigger.flatMapLatest { mdnsReceivers() },
        refreshTrigger.flatMapLatest { bleReceivers() },
    ) { mdns, ble ->
        // One endpoint often carries several mDNS services — a device can
        // advertise both _airplay (mirroring) and _raop (audio-only), and
        // TV boxes run several server apps side by side. The list keys on
        // host:port, so dedupe on exactly that; among duplicates prefer
        // the mirroring-capable advertisement (mDNS entries come first, so
        // their richer metadata wins over a bare BLE beacon). Genuinely
        // separate server apps listen on different ports and stay distinct.
        (mdns.values + ble.values)
            .sortedByDescending { it.supportsMirroring }
            .distinctBy { "${it.address.hostAddress}:${it.port}" }
            .sortedBy { it.name.lowercase() }
    }

    /** mDNS browsing, keyed by service instance name. */
    private fun mdnsReceivers(): Flow<Map<String, AirPlayReceiver>> = callbackFlow {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager

        val wifiInfo = runCatching { wifiManager.connectionInfo }
        Log.i(
            TAG,
            "Wi-Fi: ssid=${wifiInfo.getOrNull()?.ssid} " +
                "state=${runCatching { wifiManager.wifiState }.getOrDefault(-1)}",
        )

        val lock = wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
            setReferenceCounted(true)
            acquire()
        }
        Log.i(TAG, "Multicast lock acquired: ${lock.isHeld}")

        /** Keyed by host:port — one entry per physical endpoint; seeded from
         *  the retained snapshot so a re-subscription starts populated. */
        val found = linkedMapOf<String, AirPlayReceiver>().apply { putAll(mdnsSnapshot) }

        /**
         * Instance names of every seen `_raop._tcp` service. JmDNS's resolve
         * for _raop services routinely never fires its resolved callback
         * (observed with macOS and UxPlay), but serviceAdded already carries
         * the identity: the instance name is "<MAC>@<Name>", and that MAC is
         * the same value the `_airplay` TXT's deviceid carries — enough to
         * know the endpoint also offers audio-only casting.
         */
        val raopServiceNames = mutableSetOf<String>()
        var jmdns: JmDNS? = null

        fun publish() {
            val snapshot = found.toMap()
            mdnsSnapshot = snapshot
            trySend(snapshot)
        }

        publish() // Seed the UI with the last known state immediately.

        /**
         * True when any seen `_raop` instance belongs to this endpoint. The
         * _raop instance name is "<MAC>@<Name>" and its MAC equals the
         * `_airplay` TXT deviceid, so a prefix match ties the audio service to
         * the mirroring endpoint even when JmDNS never resolves the _raop
         * record.
         */
        fun endpointHasRaopService(receiver: AirPlayReceiver, resolvedName: String): Boolean {
            // The _raop instance prefix is the MAC without separators
            // ("DE72B27FD155") while the _airplay TXT deviceid carries colons
            // ("DE:72:B2:7F:D1:55") — compare both stripped.
            val deviceId = receiver.deviceId?.replace(":", "") ?: return false
            return raopServiceNames.any { candidate ->
                candidate == resolvedName ||
                    candidate.substringBefore('@', missingDelimiterValue = "\u0000")
                        .equals(deviceId, ignoreCase = true)
            }
        }

        val listener = object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) {
                // TXT records arrive with serviceResolved, so just ask for them.
                Log.i(TAG, "Service added: ${event.name}")
                if (event.type.startsWith("_raop.")) {
                    raopServiceNames.add(event.name)
                }
                event.dns.requestServiceInfo(event.type, event.name, RESOLVE_TIMEOUT_MS)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                Log.i(TAG, "Service removed: ${event.name}")
                if (event.type.startsWith("_raop.")) {
                    raopServiceNames.remove(event.name)
                }
                val receiver = event.info.toReceiver()
                val key = receiver?.let { "${it.address.hostAddress}:${it.port}" } ?: event.name
                if (found.remove(key) != null) publish()
            }

            override fun serviceResolved(event: ServiceEvent) {
                val receiver = event.info.toReceiver()
                if (receiver == null) {
                    Log.w(TAG, "Resolved ${event.name} but no usable IPv4 address; skipping")
                    return
                }
                // JmDNS re-resolves the same service repeatedly, and the
                // address set it reports drifts between a link-local IPv6 and
                // the LAN IPv4. Three guards keep one physical device as one
                // entry: an IPv4 resolution replaces any IPv6-only entry for
                // the same device id, an IPv6 resolution is skipped when we
                // already hold that device's IPv4, and different endpoints
                // with the same id (spoofed devices) still stay separate
                // because the merge below keys on the endpoint.
                val sameDeviceWithIpv4 = receiver.deviceId != null && found.values.any {
                    it.deviceId == receiver.deviceId && it.address is Inet4Address
                }
                if (receiver.address !is Inet4Address && sameDeviceWithIpv4) {
                    return
                }
                if (receiver.address is Inet4Address) {
                    // The LAN IPv4 supersedes any link-local IPv6 entry we
                    // collected first for the same device.
                    found.entries.removeAll {
                        it.value.deviceId == receiver.deviceId &&
                            it.value.address !is Inet4Address &&
                            it.key != "${receiver.address.hostAddress}:${receiver.port}"
                    }
                }
                // One physical device often advertises BOTH `_airplay` and
                // `_raop` on the same port (every Mac does). Key on the
                // endpoint and fold them together, or the list flickers an
                // "audio only" entry depending on which service resolved
                // first.
                val key = "${receiver.address.hostAddress}:${receiver.port}"
                val existing = found[key]
                val withAudioFlag = if (endpointHasRaopService(receiver, event.name)) {
                    receiver.copy(hasAudioOnlyService = true)
                } else {
                    receiver
                }
                val merged = if (existing == null) withAudioFlag else existing.mergedWith(withAudioFlag)
                Log.i(TAG, "Resolved receiver: $merged")
                found[key] = merged
                publish()
            }
        }

        try {
            // JmDNS.create() performs a reverse DNS lookup on the local address,
            // which blocks the thread. Move to IO before touching it. With a VPN
            // up, WifiManager reports no address; resolve via the Wi-Fi network's
            // LinkProperties so JmDNS binds to the right interface either way.
            val address = withContext(Dispatchers.IO) {
                WifiNetworkResolver.find(context).localAddress ?: wifiManager.localInet4Address()
            }
            Log.i(TAG, "Binding JmDNS to local address: $address")
            if (address == null) {
                Log.w(TAG, "No IPv4 Wi-Fi address; JmDNS will bind to the wildcard address")
            }

            val instance = withContext(Dispatchers.IO) { JmDNS.create(address, null) }
            jmdns = instance
            Log.i(TAG, "JmDNS created on interface ${instance.getInterface()}, host ${instance.getHostName()}")

            for (serviceType in SERVICE_TYPES) {
                instance.addServiceListener(serviceType, listener)
                Log.i(TAG, "Listening on $serviceType")
            }
            publish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mDNS browsing", e)
            close(e)
        }

        awaitClose {
            runCatching {
                for (serviceType in SERVICE_TYPES) {
                    jmdns?.removeServiceListener(serviceType, listener)
                }
                jmdns?.close()
            }.onFailure { Log.w(TAG, "Error shutting down JmDNS", it) }
            runCatching { lock.release() }
            raopServiceNames.clear()
        }
    }.conflate()

    /**
     * BLE beacon endpoints, keyed by host:port. The first emission — the last
     * known state, empty on a first run — keeps combine() alive even when
     * Bluetooth is off or not permitted; the scanner's flow completes without
     * emitting anything in that case.
     */
    private fun bleReceivers(): Flow<Map<String, AirPlayReceiver>> = flow {
        val known = LinkedHashMap<String, AirPlayReceiver>().apply { putAll(bleSnapshot) }
        emit(known.toMap())
        BleBeaconScanner.scan(context).collect { receiver ->
            val key = "${receiver.address.hostAddress}:${receiver.port}"
            if (known.put(key, receiver) != receiver) {
                val snapshot = known.toMap()
                bleSnapshot = snapshot
                emit(snapshot)
            }
        }
    }

    private companion object {
        const val TAG = "ReceiverDiscovery"
        val SERVICE_TYPES = arrayOf("_airplay._tcp.local.", "_raop._tcp.local.")
        const val MULTICAST_LOCK_TAG = "kite-mdns"
        const val RESOLVE_TIMEOUT_MS = 3_000L
    }
}

private fun ServiceInfo.toReceiver(): AirPlayReceiver? {
    // Prefer IPv4: some receivers advertise a link-local IPv6 that needs a scope id.
    val address: InetAddress = inet4Addresses.firstOrNull()
        ?: inetAddresses.firstOrNull()
        ?: return null

    val txt = propertyNames.asSequence().mapNotNull { key ->
        getPropertyString(key)?.let { key to it }
    }.toMap()

    // RAOP instances are named "<mac>@<Name>"; the AirPlay ones are just "<Name>".
    val isAudioOnly = type.startsWith("_raop.")
    val name = name.substringAfter('@', name)

    return AirPlayReceiver(
        name = name,
        address = address,
        port = port,
        deviceId = txt["deviceid"],
        model = txt["am"] ?: txt["model"],
        sourceVersion = txt["srcvers"],
        publicIdentifier = txt["pi"],
        publicKeyHex = txt["pk"],
        features = FeatureFlags.parse(txt["features"]),
        isAudioOnly = isAudioOnly,
        txtRecords = txt,
    )
}

/** JmDNS needs the interface address to bind to; guessing wrong means no responses. */
private fun WifiManager.localInet4Address(): InetAddress? {
    @Suppress("DEPRECATION")
    val ip = connectionInfo.ipAddress
    if (ip == 0) return null // Not on Wi-Fi, or an IPv6-only network. Let JmDNS pick.
    val bytes = byteArrayOf(
        (ip and 0xFF).toByte(),
        (ip shr 8 and 0xFF).toByte(),
        (ip shr 16 and 0xFF).toByte(),
        (ip shr 24 and 0xFF).toByte(),
    )
    return runCatching { Inet4Address.getByAddress(bytes) }.getOrNull()
}

/**
 * Wi-Fi network + its IPv4 address, read through ConnectivityManager. With a
 * VPN up, [WifiManager.connectionInfo] reports no address and multicast routes
 * through the tunnel — this lookup goes straight to the Wi-Fi network's
 * LinkProperties instead.
 */
object WifiNetworkResolver {

    data class WifiNetwork(val network: android.net.Network?, val localAddress: InetAddress?)

    /** Finds the non-VPN Wi-Fi/Ethernet network and its IPv4 address; null when absent. */
    fun find(context: Context): WifiNetwork {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            ?: return WifiNetwork(null, null)
        val localNetworks = cm.allNetworks.filter { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@filter false
            val local = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
            local && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
        }
        val withAddress = localNetworks.firstNotNullOfOrNull { network ->
            cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<java.net.Inet4Address>()
                ?.firstOrNull()
                ?.let { WifiNetwork(network, it) }
        }
        return withAddress ?: WifiNetwork(null, null)
    }

    @Volatile
    private var appContext: android.content.Context? = null

    /** Called once from the app so [boundTo] can reach ConnectivityManager. */
    fun init(context: android.content.Context) {
        appContext = context.applicationContext
    }
}
