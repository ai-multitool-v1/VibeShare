package com.setbd.vibeshare.domain.usecase

import com.setbd.vibeshare.domain.model.DiscoveredDevice
import com.setbd.vibeshare.domain.model.TransferMode
import com.setbd.vibeshare.domain.repository.DiscoveryRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * Observes nearby VibeShare devices for the selected mode.
 * In AUTO mode, the discovery repository picks the best available transport.
 */
class ObserveNearbyDevicesUseCase(private val discovery: DiscoveryRepository) {

    val devices: StateFlow<List<DiscoveredDevice>> get() = discovery.devices

    suspend fun start(mode: TransferMode) = discovery.start(mode)

    suspend fun stop() = discovery.stop()
}
