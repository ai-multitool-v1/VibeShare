package com.setbd.vibeshare.discovery.auto

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.net.NetworkCapabilities
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.domain.model.TransferMode

/**
 * Chooses the best transport for AUTO mode (spec section 4) based on
 * hardware support, current connectivity and available transports. Never
 * requires location permission: only Wi-Fi *state* is inspected.
 */
class AutoTransportSelector(private val context: Context) {

    /** True when the device has an active Wi-Fi transport (local network usable). */
    fun hasLocalWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun wifiDirectSupported(): Boolean =
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)

    fun wifiEnabled(): Boolean {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        return wm.isWifiEnabled
    }

    /**
     * Resolution order:
     * 1. Local Wi-Fi when connected to an AP (fastest setup: NSD, no group negotiation)
     * 2. Wi-Fi Direct when supported but no local network
     * 3. Wi-Fi Direct when supported and local scan is expected to run concurrently
     */
    fun resolve(p2pSupported: Boolean, localPeersAvailable: Boolean): TransferMode = when {
        hasLocalWifi() && localPeersAvailable -> TransferMode.LOCAL_WIFI
        hasLocalWifi() -> TransferMode.LOCAL_WIFI
        p2pSupported -> TransferMode.WIFI_DIRECT
        wifiDirectSupported() -> TransferMode.WIFI_DIRECT
        else -> {
            VibeLog.w("AutoSelector", "Neither local Wi-Fi nor P2P available")
            TransferMode.LOCAL_WIFI
        }
    }
}
