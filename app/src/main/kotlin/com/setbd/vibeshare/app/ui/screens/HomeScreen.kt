package com.setbd.vibeshare.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.setbd.vibeshare.app.util.StateColors
import com.setbd.vibeshare.domain.model.DiscoveredDevice
import com.setbd.vibeshare.domain.model.HistoryEntry
import com.setbd.vibeshare.domain.model.TransferMode
import com.setbd.vibeshare.domain.repository.DiscoveryRepository
import com.setbd.vibeshare.domain.repository.SettingsRepository
import com.setbd.vibeshare.domain.usecase.ManageHistoryUseCase
import com.setbd.vibeshare.ui.components.GlowCard
import com.setbd.vibeshare.ui.components.GradientButton
import com.setbd.vibeshare.ui.components.PulsingDot
import com.setbd.vibeshare.ui.components.RadarView
import com.setbd.vibeshare.ui.components.SectionHeader
import com.setbd.vibeshare.ui.theme.VibeColors
import com.setbd.vibeshare.core.Formats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * Premium dashboard: fast / private / offline communicated immediately,
 * send-receive hero actions, live nearby devices and recent transfers.
 */
@Composable
fun HomeScreen(
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onDevMode: () -> Unit,
    viewModel: HomeViewModel = koinViewModel(),
) {
    val devices by viewModel.devices.collectAsState()
    val recent by viewModel.recent.collectAsState(initial = emptyList())

    LaunchedEffect(Unit) { viewModel.startDiscovery() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "VibeShare",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "SETBD · your files stay on your devices",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onHistory) {
                Icon(Icons.Filled.History, contentDescription = "Transfer history", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GradientButton(
                label = "SEND",
                sublabel = "Fast · direct",
                icon = Icons.Filled.Bolt,
                gradient = listOf(VibeColors.Violet, VibeColors.VioletDeep),
                contentDescription = "Send files to a nearby device",
                modifier = Modifier.weight(1f),
                onClick = onSend,
            )
            GradientButton(
                label = "RECEIVE",
                sublabel = "Wi-Fi · no data",
                icon = Icons.Filled.ArrowDownward,
                gradient = listOf(VibeColors.Cyan, Color(0xFF0091EA)),
                contentDescription = "Receive files from a nearby device",
                modifier = Modifier.weight(1f),
                onClick = onReceive,
            )
        }

        Spacer(Modifier.height(22.dp))
        NearbySection(devices)
        Spacer(Modifier.height(22.dp))
        SectionHeader("Recent transfers") {
            Text(
                "All",
                style = MaterialTheme.typography.labelLarge,
                color = VibeColors.Cyan,
                modifier = Modifier
                    .semantics { contentDescription = "View all transfer history" }
                    .clickableNoRipple(onHistory),
            )
        }
        if (recent.isEmpty()) {
            Text(
                "Transfers you make will appear here. History stays on this device only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            recent.take(3).forEach { entry -> HistoryRow(entry) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NearbySection(devices: List<DiscoveredDevice>) {
    SectionHeader("Nearby devices") {
        Text(
            if (devices.isEmpty()) "scanning…" else "${devices.size} found",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    GlowCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadarView(Modifier.size(64.dp), ringColor = VibeColors.Violet)
            Spacer(Modifier.width(16.dp))
            if (devices.isEmpty()) {
                Text(
                    "Looking for VibeShare devices nearby…\nOpen VibeShare on the other phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    devices.take(4).forEach { DeviceRow(it) }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DiscoveredDevice) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulsingDot(color = StateColors.ok, label = "Online", size = 9.dp)
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = deviceIcon(device.type),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(device.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(device.transport.name.lowercase().replace('_', ' '), device.appVersion)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun deviceIcon(type: com.setbd.vibeshare.domain.model.DeviceType): ImageVector = when (type) {
    com.setbd.vibeshare.domain.model.DeviceType.TABLET -> Icons.Filled.Tablet
    com.setbd.vibeshare.domain.model.DeviceType.DESKTOP -> Icons.Filled.Computer
    else -> Icons.Filled.Smartphone
}

@Composable
private fun HistoryRow(entry: HistoryEntry) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .background(
                        if (entry.success) StateColors.ok.copy(alpha = 0.15f) else StateColors.error.copy(alpha = 0.15f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (entry.direction == com.setbd.vibeshare.domain.model.TransferDirection.SEND) "↑" else "↓", color = if (entry.success) StateColors.ok else StateColors.error)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${entry.fileCount} file${if (entry.fileCount == 1) "" else "s"} · ${entry.peerName}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(entry.timestampMs)) +
                        " · " + Formats.bytes(entry.totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                Formats.speed(entry.averageSpeedBps),
                style = MaterialTheme.typography.labelMedium,
                color = VibeColors.Cyan,
            )
        }
    }
}

/** Home dashboard state holder. */
class HomeViewModel(
    private val discovery: DiscoveryRepository,
    history: ManageHistoryUseCase,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val devices = discovery.devices

    val recent = history.entries

    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.setbd.vibeshare.domain.model.SettingsState())

    fun startDiscovery() {
        viewModelScope.launch { discovery.start(TransferMode.AUTO) }
    }

    override fun onCleared() {
        // Discovery listeners are transport-managed; nothing long-lived to leak.
    }
}
