package com.setbd.vibeshare.domain.model

/** Which local transport a peer was discovered through / should use. */
enum class TransportKind { AUTO, WIFI_DIRECT, LOCAL_WIFI }

/** Lifecycle of device discovery / connection (spec section 22). */
enum class ConnectionLifecycle {
    DISCOVERING,
    PAIRING,
    CONNECTING,
    CONNECTED,
    TRANSFERRING,
    PAUSED,
    RECONNECTING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

/** A peer seen by any discovery transport (NSD on local Wi-Fi, or Wi-Fi P2P). */
data class DiscoveredDevice(
    val deviceId: String,
    val name: String,
    val type: DeviceType = DeviceType.PHONE,
    val appVersion: String = "",
    val protocolVersion: Int = 0,
    val transport: TransportKind = TransportKind.LOCAL_WIFI,
    /** Direct TCP address when the peer is reachable on the current network. */
    val host: String? = null,
    val port: Int = 0,
    /** 0-100 signal quality when the transport exposes it, else null. */
    val signalPercent: Int? = null,
    val paired: Boolean = false,
    val lastSeenMs: Long = 0L,
)

/** User-selectable transfer mode (spec section 4). */
enum class TransferMode { AUTO, WIFI_DIRECT, LOCAL_WIFI }
