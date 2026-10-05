package com.setbd.vibeshare.discovery.p2p

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.setbd.vibeshare.core.log.VibeLog
import java.util.concurrent.atomic.AtomicReference

/**
 * Wi-Fi Direct (P2P) transport wrapper (Mode A). Uses the framework
 * WifiP2pManager: peer discovery, group negotiation, and group-owner address
 * resolution so the transfer engine can open a plain TCP socket on the P2P
 * link. All Android-version differences are handled here; callers see a
 * simple API.
 */
class WifiDirectManager(
    private val context: Context,
    private val onPeersChanged: (List<WifiP2pDevice>) -> Unit,
    private val onConnectionChanged: (WifiP2pInfo?) -> Unit,
    private val onStateChanged: (P2pState) -> Unit,
) {
    enum class P2pState { IDLE, DISCOVERING, CONNECTING, CONNECTED, FAILED }

    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    private val channel: WifiP2pManager.Channel? by lazy {
        manager?.initialize(context, context.mainLooper, object : WifiP2pManager.ChannelListener {
            override fun onChannelDisconnected() {
                VibeLog.w(TAG, "P2P channel disconnected")
                onStateChanged(P2pState.FAILED)
            }
        })
    }

    private val lastConnection = AtomicReference<WifiP2pInfo?>(null)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager?.requestPeers(channel) { peers: WifiP2pDeviceList ->
                        onPeersChanged(peers.deviceList.toList())
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    lastConnection.set(info)
                    onConnectionChanged(info)
                    onStateChanged(if (info?.groupFormed == true) P2pState.CONNECTED else P2pState.IDLE)
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) onStateChanged(P2pState.FAILED)
                }
            }
        }
    }

    val isSupported: Boolean get() = manager != null && channel != null

    fun register() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        runCatching { context.registerReceiver(receiver, filter) }
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun startDiscovery() {
        val m = manager ?: return
        val ch = channel ?: return
        onStateChanged(P2pState.DISCOVERING)
        m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                VibeLog.i(TAG, "P2P discovery started")
            }
            override fun onFailure(reason: Int) {
                VibeLog.w(TAG, "P2P discovery failed: $reason")
                onStateChanged(P2pState.FAILED)
            }
        })
    }

    fun stopDiscovery() {
        val m = manager ?: return
        val ch = channel ?: return
        runCatching { m.stopPeerDiscovery(ch, null) }
        onStateChanged(P2pState.IDLE)
    }

    /** Connects to a discovered peer. P2P group formation happens async. */
    fun connect(device: WifiP2pDevice, onResult: (Boolean) -> Unit) {
        val m = manager ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
        }
        onStateChanged(P2pState.CONNECTING)
        m.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onResult(true)
            override fun onFailure(reason: Int) {
                onStateChanged(P2pState.FAILED)
                onResult(false)
            }
        })
    }

    /** Owner address of the formed group (connect to this for TCP). */
    fun groupOwnerAddress(): String? = lastConnection.get()?.groupOwnerAddress?.hostAddress

    /** True when this device should run the receiver server (is group owner). */
    fun isGroupOwner(): Boolean = lastConnection.get()?.isGroupOwner == true

    fun disconnect(onDone: () -> Unit = {}) {
        val m = manager ?: return onDone()
        val ch = channel ?: return onDone()
        runCatching {
            m.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = onDone()
                override fun onFailure(reason: Int) = onDone()
            })
        }
    }

    companion object {
        private const val TAG = "WifiDirect"
    }
}
