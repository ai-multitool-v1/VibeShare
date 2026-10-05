package com.setbd.vibeshare.domain.repository

import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.core.error.AppError
import com.setbd.vibeshare.domain.model.DiscoveredDevice
import com.setbd.vibeshare.domain.model.TransferMode
import kotlinx.coroutines.flow.StateFlow

/**
 * Discovery facade for all transports (NSD local Wi-Fi + Wi-Fi P2P).
 * Implementations merge peers from every transport into one stream.
 */
interface DiscoveryRepository {
    /** True when Wi-Fi P2P hardware support is present on this device. */
    val wifiDirectSupported: Boolean

    val devices: StateFlow<List<DiscoveredDevice>>
    val isDiscovering: StateFlow<Boolean>

    /** Starts merged discovery for the requested mode. Idempotent. */
    suspend fun start(mode: TransferMode): VibeResult<Unit>

    suspend fun stop()

    /** Drops stale peers from the merged list. */
    fun pruneStale(maxAgeMs: Long)

    /** Resolves which transport auto mode should use right now. */
    suspend fun resolveAutoMode(): TransferMode
}
