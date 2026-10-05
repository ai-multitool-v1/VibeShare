package com.setbd.vibeshare.discovery.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.setbd.vibeshare.core.Constants
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.domain.model.DeviceType
import com.setbd.vibeshare.domain.model.DiscoveredDevice
import com.setbd.vibeshare.domain.model.TransportKind
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * NSD/mDNS discovery over the local Wi-Fi network (Mode B).
 * Registers "_vibeshare._tcp." when this device hosts a session and browses
 * for peers otherwise. TXT records carry only non-sensitive identity data.
 */
class NsdDiscoveryManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onDevice: (DiscoveredDevice) -> Unit,
    private val onDeviceLost: (String) -> Unit,
) {
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    private val seen = ConcurrentHashMap<String, DiscoveredDevice>()

    private var registerListener: NsdManager.RegistrationListener? = null
    private var discoverListener: NsdManager.DiscoveryListener? = null
    private var resolveListener: NsdManager.ResolveListener? = null

    val isRegistered: Boolean get() = registerListener != null

    fun advertise(serviceName: String, port: Int, deviceId: String, deviceType: String, version: String) {
        if (registerListener != null) return
        val info = NsdServiceInfo().apply {
            setServiceName("VibeShare · $serviceName")
            setServiceType(Constants.NSD_SERVICE_TYPE)
            setPort(port)
            setAttribute("did", deviceId.take(24))
            setAttribute("dtype", deviceType.take(12))
            setAttribute("ver", version.take(16))
            setAttribute("proto", "${com.setbd.vibeshare.core.Constants.PROTOCOL_VERSION}")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                VibeLog.i(TAG, "Advertised as ${serviceInfo.serviceName}")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                VibeLog.w(TAG, "NSD register failed: $errorCode")
                registerListener = null
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registerListener = listener
        runCatching { nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { registerListener = null; VibeLog.w(TAG, "registerService threw", it) }
    }

    fun stopAdvertising() {
        registerListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
            registerListener = null
        }
    }

    fun startDiscovery() {
        if (discoverListener != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                VibeLog.i(TAG, "NSD discovery started")
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                VibeLog.w(TAG, "NSD start discovery failed: $errorCode")
                discoverListener = null
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.startsWith(Constants.NSD_SERVICE_TYPE.trimEnd('.'))) {
                    resolve(serviceInfo)
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val id = serviceInfo.serviceName.hashCode().toString()
                seen.remove(serviceInfo.serviceName)
                onDeviceLost(id)
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoverListener = listener
        runCatching { nsdManager.discoverServices(Constants.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { discoverListener = null; VibeLog.w(TAG, "discoverServices threw", it) }
    }

    fun stopDiscovery() {
        discoverListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        discoverListener = null
        seen.clear()
    }

    private fun resolve(serviceInfo: NsdServiceInfo) {
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                VibeLog.w(TAG, "Resolve failed: $errorCode")
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val host = info.host?.hostAddress ?: return
                if (info.port <= 0) return
                val attrs = info.attributes ?: emptyMap()
                val deviceId = attrs.txtString("did") ?: info.serviceName.hashCode().toString()
                val device = DiscoveredDevice(
                    deviceId = deviceId,
                    name = info.serviceName.removePrefix("VibeShare · ").ifBlank { "Unknown device" },
                    type = runCatching { DeviceType.valueOf(attrs.txtString("dtype") ?: "PHONE") }
                        .getOrDefault(DeviceType.PHONE),
                    appVersion = attrs.txtString("ver") ?: "",
                    protocolVersion = attrs.txtString("proto")?.toIntOrNull() ?: 0,
                    transport = TransportKind.LOCAL_WIFI,
                    host = host,
                    port = info.port,
                    lastSeenMs = System.currentTimeMillis(),
                )
                seen[info.serviceName] = device
                scope.launch { onDevice(device) }
            }
        }
        resolveListener = resolver
        runCatching { nsdManager.resolveService(serviceInfo, resolver) }
            .onFailure { VibeLog.w(TAG, "resolveService threw", it) }
    }

    private fun Map<String, ByteArray>.txtString(key: String): String? =
        this[key]?.let { String(it, Charsets.UTF_8) }

    companion object {
        private const val TAG = "NsdDiscovery"
    }
}
