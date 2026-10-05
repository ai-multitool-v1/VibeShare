package com.setbd.vibeshare.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.setbd.vibeshare.app.transfer.TransferCoordinator
import com.setbd.vibeshare.core.Constants
import com.setbd.vibeshare.core.Formats
import com.setbd.vibeshare.discovery.AndroidDiscoveryRepository
import com.setbd.vibeshare.discovery.auto.AutoTransportSelector
import com.setbd.vibeshare.transfer.engine.SenderSession
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * Hidden developer/debug screen (spec section 25). Revealed by tapping the
 * version row seven times in Settings → About. Shows transport state and
 * engine diagnostics — never secrets.
 */
@Composable
fun DevScreen(
    onBack: () -> Unit,
    viewModel: DevViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(
                "Developer",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))

        DevCard("Transport") {
            DevRow("Resolved mode", state.resolvedMode)
            DevRow("Local Wi-Fi available", state.localWifi.toString())
            DevRow("Wi-Fi Direct supported", state.p2pSupported.toString())
            DevRow("Local IPv4", state.localIp)
        }
        DevCard("Connection") {
            DevRow("Lifecycle", state.lifecycle)
            DevRow("Hosting", state.hosting.toString())
            DevRow("Receiver port", state.port.toString())
            DevRow("Active sender sessions", state.activeSessions.toString())
        }
        DevCard("Engine") {
            DevRow("Protocol version", state.protocolVersion.toString())
            DevRow("Chunk size", "${state.chunkSizeKiB} KiB")
            DevRow("Max frame payload", Formats.bytes(Constants.MAX_FRAME_PAYLOAD_BYTES.toLong()))
            DevRow("Throughput (smoothed)", Formats.speed(state.currentThroughputBps))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DevCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DevRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

class DevViewModel(
    coordinator: TransferCoordinator,
    discovery: AndroidDiscoveryRepository,
    transportSelector: AutoTransportSelector,
    settingsRepository: com.setbd.vibeshare.domain.repository.SettingsRepository,
) : ViewModel() {

    data class Diagnostics(
        val resolvedMode: String = "—",
        val localWifi: Boolean = false,
        val p2pSupported: Boolean = false,
        val localIp: String = "—",
        val lifecycle: String = "—",
        val hosting: Boolean = false,
        val port: Int = 0,
        val activeSessions: Int = 0,
        val protocolVersion: Int = Constants.PROTOCOL_VERSION,
        val chunkSizeKiB: Int = Constants.DEFAULT_CHUNK_SIZE_BYTES / 1024,
        val currentThroughputBps: Long = 0,
    )

    private val _state = MutableStateFlow(Diagnostics())
    val state: StateFlow<Diagnostics> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = Diagnostics(
                resolvedMode = kotlinx.coroutines.runBlocking { discovery.resolveAutoMode() }.name,
                localWifi = transportSelector.hasLocalWifi(),
                p2pSupported = discovery.wifiDirectSupported,
                localIp = localIpv4() ?: "—",
                lifecycle = coordinator.activeConnectionState.value.name,
                hosting = coordinator.hosting.value.active,
                port = coordinator.hosting.value.port,
                activeSessions = 0,
                chunkSizeKiB = 256,
            )
        }
        // Live refresh of throughput and session counters while the screen is open.
        viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1000)
                val latest = _state.value
                _state.value = latest.copy(
                    hosting = coordinator.hosting.value.active,
                    port = coordinator.hosting.value.port,
                    lifecycle = coordinator.activeConnectionState.value.name,
                    currentThroughputBps = coordinator.senders.value.maxOfOrNull { it.speedBps } ?: 0L,
                )
            }
        }
    }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()
}
