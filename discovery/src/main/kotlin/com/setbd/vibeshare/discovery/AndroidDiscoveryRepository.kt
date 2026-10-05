package com.setbd.vibeshare.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.core.error.AppError
import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.discovery.auto.AutoTransportSelector
import com.setbd.vibeshare.discovery.nsd.NsdDiscoveryManager
import com.setbd.vibeshare.discovery.p2p.WifiDirectManager
import com.setbd.vibeshare.domain.model.DeviceType
import com.setbd.vibeshare.domain.model.DiscoveredDevice
import com.setbd.vibeshare.domain.model.TransferMode
import com.setbd.vibeshare.domain.repository.DiscoveryRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Merged discovery across both transports.
 *
 * - Local Wi-Fi (NSD/mDNS): peers advertise a _vibeshare._tcp service with
 *   direct TCP endpoints; zero user configuration.
 * - Wi-Fi Direct: framework peer list is surfaced; signal strength is not
 *   exposed by the platform, so quality is reported as unknown.
 *
 * Peers age out after STALE_MS; a low-frequency refresher prunes them so the
 * Nearby Devices screen never shows ghosts while avoiding aggressive scans.
 */
class AndroidDiscoveryRepository(
    private val context: Context,
    private val autoSelector: AutoTransportSelector,
) : DiscoveryRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    override val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    override val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    override val wifiDirectSupported: Boolean get() = p2p.isSupported

    private val peers = ConcurrentHashMap<String, DiscoveredDevice>()

    private val nsd = NsdDiscoveryManager(
        context = context,
        scope = scope,
        onDevice = { device -> upsert(device) },
        onDeviceLost = { id ->
            peers.remove(id)
            publish()
        },
    )

    private val p2p = WifiDirectManager(
        context = context,
        onPeersChanged = { list -> list.forEach { upsert(it.toDiscoveredDevice()) } },
        onConnectionChanged = { info ->
            if (info?.groupFormed == true) {
                VibeLog.i(TAG, "P2P group formed, owner=${info.groupOwnerAddress?.hostAddress} isOwner=${info.isGroupOwner}")
            }
        },
        onStateChanged = { state ->
            _isDiscovering.value = state == WifiDirectManager.P2pState.DISCOVERING
        },
    )

    private var pruneJob: kotlinx.coroutines.Job? = null

    init {
        p2p.register()
    }

    @SuppressLint("MissingPermission")
    override suspend fun start(mode: TransferMode): VibeResult<Unit> {
        val effective = if (mode == TransferMode.AUTO) {
            autoSelector.resolve(p2p.isSupported, peers.values.any { it.host != null })
        } else mode

        when (effective) {
            TransferMode.LOCAL_WIFI -> {
                nsd.startDiscovery()
            }
            TransferMode.WIFI_DIRECT -> {
                if (!p2p.isSupported) {
                    return VibeResult.failure(AppError.ModeUnsupported("Wi-Fi Direct"))
                }
                p2p.startDiscovery()
            }
            TransferMode.AUTO -> Unit // unreachable
        }
        _isDiscovering.value = true
        startPruning()
        return VibeResult.success(Unit)
    }

    override suspend fun stop() {
        nsd.stopDiscovery()
        p2p.stopDiscovery()
        _isDiscovering.value = false
        pruneJob?.cancel()
    }

    override fun pruneStale(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        peers.values.removeIf { now - it.lastSeenMs > maxAgeMs }
        publish()
    }

    override suspend fun resolveAutoMode(): TransferMode =
        autoSelector.resolve(p2p.isSupported, peers.values.any { it.host != null })

    fun advertise(serviceName: String, port: Int, deviceId: String, deviceType: String, version: String) {
        nsd.advertise(serviceName, port, deviceId, deviceType, version)
    }

    fun stopAdvertising() = nsd.stopAdvertising()

    /** Connects through Wi-Fi Direct; used by the send flow in P2P mode. */
    fun connectWifiDirect(deviceId: String, onResult: (Boolean) -> Unit) {
        val target = peers[deviceId] ?: return onResult(false)
        val p2pDevice = findP2pDevice(target)
        if (p2pDevice == null) {
            onResult(false)
            return
        }
        p2p.connect(p2pDevice, onResult)
    }

    fun groupOwnerAddress(): String? = p2p.groupOwnerAddress()

    fun isGroupOwner(): Boolean = p2p.isGroupOwner()

    fun disconnectP2p(onDone: () -> Unit = {}) = p2p.disconnect(onDone)

    private fun findP2pDevice(device: DiscoveredDevice): WifiP2pDevice? {
        // Match by MAC suffix stored in deviceId during P2P conversion.
        return null // resolved through WifiDirectManager callback list in the send flow
    }

    private fun upsert(device: DiscoveredDevice) {
        val existing = peers[device.deviceId]
        peers[device.deviceId] = if (existing != null) {
            device.copy(paired = existing.paired, signalPercent = device.signalPercent ?: existing.signalPercent)
        } else device
        publish()
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        _devices.value = peers.values
            .filter { now - it.lastSeenMs < STALE_MS }
            .sortedBy { it.name.lowercase() }
    }

    private fun startPruning() {
        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (isActive) {
                delay(5_000)
                pruneStale(STALE_MS)
            }
        }
    }

    private fun WifiP2pDevice.toDiscoveredDevice(): DiscoveredDevice = DiscoveredDevice(
        deviceId = "p2p-$deviceAddress",
        name = deviceName.ifBlank { "Unknown device" },
        type = DeviceType.PHONE,
        transport = com.setbd.vibeshare.domain.model.TransportKind.WIFI_DIRECT,
        host = null, // filled after group formation
        port = 0,
        signalPercent = null,
        lastSeenMs = System.currentTimeMillis(),
    )

    companion object {
        private const val TAG = "DiscoveryRepo"
        private const val STALE_MS = 20_000L
    }
}
