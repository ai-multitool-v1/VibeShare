package com.setbd.vibeshare.app.ui.screens

import android.Manifest
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.setbd.vibeshare.app.util.StateColors
import com.setbd.vibeshare.app.transfer.TransferCoordinator
import com.setbd.vibeshare.core.Formats
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.discovery.AndroidDiscoveryRepository
import com.setbd.vibeshare.discovery.auto.AutoTransportSelector
import com.setbd.vibeshare.domain.model.DiscoveredDevice
import com.setbd.vibeshare.domain.model.TransferMode
import com.setbd.vibeshare.pairing.handshake.PairCredential
import com.setbd.vibeshare.pairing.qr.QrPayload
import com.setbd.vibeshare.storage.AndroidFileSource
import com.setbd.vibeshare.ui.components.GlowCard
import com.setbd.vibeshare.ui.components.PulsingDot
import com.setbd.vibeshare.ui.components.TransferProgressRow
import com.setbd.vibeshare.ui.theme.VibeColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * Send flow: select files (SAF) or apps, choose a nearby device, pair via QR
 * scan or PIN, then watch real per-receiver progress with pause/resume/cancel.
 */
@Composable
fun SendScreen(
    onBack: () -> Unit,
    onOpenApps: () -> Unit,
    viewModel: SendViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.ui.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val senders by viewModel.senders.collectAsState()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.addFiles(uris)
    }
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { viewModel.onQrScanned(it) }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    LaunchedEffect(Unit) {
        viewModel.startDiscovery()
        permissions.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else null,
                if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
            ).filterNotNull().toTypedArray()
        )
    }

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
                "Send",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onBack) { Text("Close") }
        }
        Spacer(Modifier.height(16.dp))

        // 1. Files
        GlowCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("1 · Choose what to send", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Files")
                }
                OutlinedButton(onClick = onOpenApps) {
                    Icon(Icons.Filled.Apps, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Apps")
                }
            }
            state.files.forEach { file ->
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        file.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        Formats.bytes(file.sizeBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(onClick = { viewModel.removeFile(file.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove ${file.displayName}", Modifier.size(16.dp))
                    }
                }
            }
            if (state.files.isNotEmpty()) {
                Text(
                    "Total: ${Formats.bytes(state.files.sumOf { it.sizeBytes })} · ${state.files.size} item(s)",
                    style = MaterialTheme.typography.labelMedium,
                    color = VibeColors.Cyan,
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // 2. Device
        GlowCard {
            Text("2 · Choose a nearby device", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            if (devices.isEmpty()) {
                Text(
                    "Searching… open VibeShare on the receiving phone (Receive screen) or use its PIN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                devices.forEach { device ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clickableNoRipple { viewModel.selectDevice(device) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PulsingDot(color = StateColors.ok, label = "Online", size = 8.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                device.transport.name.lowercase().replace('_', ' '),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Checkbox(
                            checked = state.selectedDevice?.deviceId == device.deviceId,
                            onCheckedChange = { viewModel.selectDevice(device) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // 3. Pair + send
        GlowCard {
            Text("3 · Pair & send", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        qrScanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Scan the receiver's VibeShare QR")
                                .setBeepEnabled(false)
                                .setOrientationLocked(true)
                        )
                    },
                    enabled = state.canSend,
                ) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scan QR")
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = state.pinInput,
                onValueChange = viewModel::onPinChanged,
                label = { Text("Or type the 6-digit PIN") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { viewModel.send(context) },
                enabled = state.canSend,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.selectedDevice == null) "Select a device to start" else "Send now")
            }
            state.message?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(message, color = StateColors.warn, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (senders.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            GlowCard {
                Text("Transferring", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                senders.forEach { receiver ->
                    TransferProgressRow(
                        title = "${receiver.peerName} — ${receiver.state.name.lowercase()}",
                        subtitle = "${Formats.bytes(receiver.currentFileBytesDone)} / ${Formats.bytes(receiver.currentFileBytesTotal)} · " +
                            "${Formats.speed(receiver.speedBps)} · ${Formats.eta(receiver.etaSeconds)}",
                        percent = receiver.percent,
                        tint = if (receiver.state == com.setbd.vibeshare.domain.model.ConnectionLifecycle.FAILED) StateColors.error else VibeColors.Violet,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.pause(receiver.peerId) }) { Text("Pause") }
                        TextButton(onClick = { viewModel.resume(receiver.peerId) }) { Text("Resume") }
                        TextButton(onClick = { viewModel.cancel(receiver.peerId) }) { Text("Cancel") }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (state.showResumeNotice) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissResumeNotice() },
            title = { Text("Resume transfer?") },
            text = { Text("A previous transfer to this device was interrupted. VibeShare will resume from where it stopped.") },
            confirmButton = { Button(onClick = { viewModel.dismissResumeNotice() }) { Text("Resume") } },
        )
    }
}

/** UI state for the send flow. */
data class SendUiState(
    val files: List<com.setbd.vibeshare.domain.model.TransferFile> = emptyList(),
    val selectedDevice: DiscoveredDevice? = null,
    val pinInput: String = "",
    val scannedPayload: QrPayload? = null,
    val message: String? = null,
    val showResumeNotice: Boolean = false,
) {
    val canSend: Boolean get() = files.isNotEmpty() && selectedDevice != null
}

/** Send flow state holder: files, devices, credentials, active transfers. */
class SendViewModel(
    private val coordinator: TransferCoordinator,
    private val discovery: AndroidDiscoveryRepository,
    private val transportSelector: AutoTransportSelector,
    private val appContext: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(SendUiState())
    val ui: StateFlow<SendUiState> = _ui.asStateFlow()

    val devices = discovery.devices
    val senders = coordinator.senders

    private val uris = mutableListOf<Uri>()
    private var activeTag: String? = null
    private var lastHostEndpoint: Pair<String, Int>? = null

    fun startDiscovery() {
        viewModelScope.launch { discovery.start(TransferMode.AUTO) }
    }

    fun addFiles(newUris: List<Uri>) {
        uris.clear()
        uris.addAll(newUris)
        _ui.value = _ui.value.copy(files = newUris.map { uri -> AndroidFileSource(appContext, uri).file })
    }

    fun addExportedApk(uri: Uri, name: String, size: Long) {
        uris.clear()
        uris.add(uri)
        _ui.value = _ui.value.copy(
            files = listOf(
                com.setbd.vibeshare.domain.model.TransferFile(
                    id = com.setbd.vibeshare.domain.model.TransferFile.newId(),
                    displayName = name,
                    sizeBytes = size,
                    mimeType = "application/vnd.android.package-archive",
                )
            )
        )
    }

    fun removeFile(fileId: String) {
        _ui.value = _ui.value.copy(files = _ui.value.files.filterNot { it.id == fileId })
    }

    fun selectDevice(device: DiscoveredDevice) {
        _ui.value = _ui.value.copy(selectedDevice = device)
    }

    fun onPinChanged(pin: String) {
        _ui.value = _ui.value.copy(pinInput = pin.filter { it.isDigit() }.take(6))
    }

    fun onQrScanned(raw: String) {
        val payload = QrPayload.fromJson(raw)
        _ui.value = if (payload == null) {
            _ui.value.copy(message = "That QR code is not a VibeShare session.")
        } else {
            _ui.value.copy(scannedPayload = payload, message = null)
        }
    }

    fun send(context: Context) {
        val current = _ui.value
        val device = current.selectedDevice ?: return
        val payload = current.scannedPayload

        val credential: PairCredential = when {
            payload != null -> PairCredential.Token(payload.token)
            current.pinInput.length == 6 -> PairCredential.Pin(current.pinInput)
            else -> {
                _ui.value = current.copy(message = "Scan the receiver's QR or type its 6-digit PIN.")
                return
            }
        }

        val qrHost: String? = payload?.host
        val qrPort: Int? = payload?.port
        val nsdHost: String? = device.host
        val nsdPort: Int = device.port

        val endpoint: Pair<String, Int> = when {
            qrHost != null && qrPort != null && qrPort > 0 -> {
                // Local Wi-Fi direct endpoint carried by the QR.
                qrHost to qrPort
            }
            nsdHost != null && nsdPort > 0 -> {
                // Discovered over NSD on the same network.
                nsdHost to nsdPort
            }
            device.transport == com.setbd.vibeshare.domain.model.TransportKind.WIFI_DIRECT -> {
                // Wi-Fi Direct: resolve the group owner after group formation.
                val owner = discovery.groupOwnerAddress()
                if (owner == null) {
                    discovery.connectWifiDirect(device.deviceId) { connected ->
                        VibeLog.i(TAG, "P2P connect result: $connected")
                        _ui.value = _ui.value.copy(
                            message = if (connected) "Wi-Fi Direct connected — tap Send now." else "Wi-Fi Direct connection failed."
                        )
                    }
                    return
                }
                owner to com.setbd.vibeshare.core.Constants.DEFAULT_PORT
            }
            else -> {
                _ui.value = current.copy(message = "No route to that device. Scan its QR while both devices are on the same Wi-Fi.")
                return
            }
        }
        val (host, port) = endpoint

        lastHostEndpoint = endpoint

        val sources = uris.map { uri -> AndroidFileSource(appContext, uri) }
        coordinator.sendTo(
            host = host,
            port = port,
            credential = credential,
            sources = sources,
            onSessionReady = { tag -> activeTag = tag },
        )
        _ui.value = current.copy(message = null)
    }

    fun pause(peerId: String) = activeTag?.let { coordinator.pauseSend(it) }

    fun resume(peerId: String) = activeTag?.let { coordinator.resumeSend(it) }

    fun cancel(peerId: String) {
        activeTag?.let { coordinator.cancelSend(it) }
        activeTag = null
    }

    fun dismissResumeNotice() {
        _ui.value = _ui.value.copy(showResumeNotice = false)
    }

    override fun onCleared() {
        // Sessions keep running in the coordinator/foreground service.
    }

    companion object {
        private const val TAG = "SendVM"
    }
}
