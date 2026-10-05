package com.setbd.vibeshare.app.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.setbd.vibeshare.app.service.TransferForegroundService
import com.setbd.vibeshare.app.transfer.TransferCoordinator
import com.setbd.vibeshare.app.util.QrImages
import com.setbd.vibeshare.app.util.StateColors
import com.setbd.vibeshare.core.Formats
import com.setbd.vibeshare.domain.model.ConnectionLifecycle
import com.setbd.vibeshare.domain.model.DuplicateDecision
import com.setbd.vibeshare.domain.model.DuplicatePolicy
import com.setbd.vibeshare.domain.model.IncomingProgress
import com.setbd.vibeshare.domain.model.TransferMode
import com.setbd.vibeshare.discovery.AndroidDiscoveryRepository
import com.setbd.vibeshare.discovery.auto.AutoTransportSelector
import com.setbd.vibeshare.pairing.handshake.PendingApprovalBus
import com.setbd.vibeshare.ui.components.PulsingDot
import com.setbd.vibeshare.ui.components.TransferProgressRow
import com.setbd.vibeshare.ui.theme.VibeColors
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * Receive flow: hosts a session, shows session QR + 6-digit PIN, approves
 * incoming devices, and displays real incoming progress (never simulated).
 */
@Composable
fun ReceiveScreen(
    onBack: () -> Unit,
    viewModel: ReceiveViewModel = koinViewModel(),
) {
    val hosting by viewModel.hosting.collectAsState()
    val incoming by viewModel.incoming.collectAsState()
    val approval by viewModel.pendingApproval.collectAsState()
    val duplicate by viewModel.pendingDuplicate.collectAsState()

    LaunchedEffect(Unit) { viewModel.startHosting() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Receive",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onBack) { Text("Done") }
        }
        Spacer(Modifier.height(18.dp))

        if (!hosting.active) {
            NoticeCard(
                text = "Could not start the receiver. Check that Wi-Fi is on and try again.",
                tint = StateColors.error,
            )
        } else {
            QrCard(hosting)
            Spacer(Modifier.height(16.dp))
            PinCard(hosting.session?.pin ?: "------")
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(color = StateColors.ok, label = "Waiting for a sender", size = 9.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Ready · mode: ${viewModel.resolvedModeName()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        incoming?.let { progress ->
            when (progress.state) {
                ConnectionLifecycle.COMPLETED -> {
                    Spacer(Modifier.height(18.dp))
                    NoticeCard(
                        text = "Transfer complete. Files saved to your VibeShare folder.",
                        tint = StateColors.ok,
                    )
                }
                ConnectionLifecycle.PAIRING,
                ConnectionLifecycle.CONNECTING,
                ConnectionLifecycle.TRANSFERRING,
                ConnectionLifecycle.PAUSED,
                -> {
                    Spacer(Modifier.height(18.dp))
                    IncomingCard(progress)
                }
                ConnectionLifecycle.FAILED -> {
                    Spacer(Modifier.height(18.dp))
                    IncomingCard(progress)
                }
                else -> Unit
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    approval?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.reject() },
            title = { Text("Pairing request") },
            text = { Text("${request.peer.name} wants to connect to this device.\nApprove?") },
            confirmButton = { Button(onClick = { viewModel.approve() }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { viewModel.reject() }) { Text("Reject") } },
        )
    }

    duplicate?.let { decision ->
        AlertDialog(
            onDismissRequest = { viewModel.decideDuplicate(DuplicatePolicy.KEEP_BOTH) },
            title = { Text("File already exists") },
            text = { Text("“${decision.fileName}” is already in the destination. What should we do?") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { viewModel.decideDuplicate(DuplicatePolicy.REPLACE) }) { Text("Replace") }
                    TextButton(onClick = { viewModel.decideDuplicate(DuplicatePolicy.SKIP) }) { Text("Skip") }
                    Button(onClick = { viewModel.decideDuplicate(DuplicatePolicy.KEEP_BOTH) }) { Text("Keep both") }
                }
            },
        )
    }
}

@Composable
private fun QrCard(hosting: TransferCoordinator.HostingState) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Scan to connect", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "On the sending device: VibeShare → SEND → scan this code",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            val qr = hosting.qrPayload?.encode()?.let { QrImages.render(it) }
            if (qr != null) {
                Image(
                    bitmap = qr,
                    contentDescription = "Pairing QR code with a temporary session token, valid for five minutes",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(240.dp),
                )
            } else {
                Box(
                    Modifier.size(240.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("QR unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Expires automatically · nothing is uploaded anywhere",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PinCard(pin: String) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Or enter this PIN on the sender", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                pin.chunked(2).joinToString(" "),
                style = MaterialTheme.typography.headlineLarge,
                color = VibeColors.Cyan,
                letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Valid for 5 minutes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IncomingCard(progress: IncomingProgress) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(
                    color = if (progress.state == ConnectionLifecycle.FAILED) StateColors.error else StateColors.busy,
                    label = progress.state.name,
                    size = 9.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text("Receiving from ${progress.peerName}", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(10.dp))
            TransferProgressRow(
                title = progress.currentFileName.ifBlank { "Preparing…" },
                subtitle = "${Formats.bytes(progress.bytesDone)} / ${Formats.bytes(progress.bytesTotal)} · " +
                    "${Formats.speed(progress.speedBps)} · ${Formats.eta(progress.etaSeconds)}",
                percent = progress.percent,
                tint = VibeColors.Cyan,
            )
            if (progress.state == ConnectionLifecycle.FAILED) {
                Text(
                    progress.error ?: "Transfer failed",
                    color = StateColors.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun NoticeCard(text: String, tint: androidx.compose.ui.graphics.Color) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.12f)),
    ) {
        Text(text, Modifier.padding(18.dp), color = tint, style = MaterialTheme.typography.titleSmall)
    }
}

/** Receive flow state holder. */
class ReceiveViewModel(
    private val coordinator: TransferCoordinator,
    private val discovery: AndroidDiscoveryRepository,
    private val transportSelector: AutoTransportSelector,
    private val appContext: android.content.Context,
) : ViewModel() {

    val hosting: StateFlow<TransferCoordinator.HostingState> = coordinator.hosting
    val incoming: StateFlow<IncomingProgress?> = coordinator.incoming
    val pendingApproval: StateFlow<PendingApprovalBus.Request?> = coordinator.pendingApproval
    val pendingDuplicate: StateFlow<DuplicateDecision?> = coordinator.pendingDuplicate

    private var resolvedMode: TransferMode = TransferMode.AUTO

    fun startHosting() {
        if (coordinator.hosting.value.active) return
        resolvedMode = if (transportSelector.hasLocalWifi()) TransferMode.LOCAL_WIFI else TransferMode.WIFI_DIRECT
        val host = if (resolvedMode == TransferMode.LOCAL_WIFI) localWifiAddress() else null
        coordinator.startHosting(resolvedMode, host)
        TransferForegroundService.start(appContext)
    }

    fun resolvedModeName(): String = resolvedMode.name.lowercase().replace('_', ' ')

    fun approve() {
        val request = coordinator.pendingApproval.value ?: return
        coordinator.approveIncoming(request, approved = true)
    }

    fun reject() {
        val request = coordinator.pendingApproval.value ?: return
        coordinator.approveIncoming(request, approved = false)
    }

    fun decideDuplicate(policy: DuplicatePolicy) {
        val pending = coordinator.pendingDuplicate.value ?: return
        coordinator.respondDuplicate(pending.copy(chosen = policy))
    }

    private fun localWifiAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    override fun onCleared() {
        runCatching { coordinator.stopHosting() }
    }
}
