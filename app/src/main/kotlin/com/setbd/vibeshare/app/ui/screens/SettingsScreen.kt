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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.setbd.vibeshare.app.updater.UpdateManager
import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.DuplicatePolicy
import com.setbd.vibeshare.domain.model.SettingsState
import com.setbd.vibeshare.domain.model.ThemeMode
import com.setbd.vibeshare.domain.model.TransferMode
import com.setbd.vibeshare.domain.repository.SettingsRepository
import com.setbd.vibeshare.ui.theme.VibeColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** Settings (spec section 20): transfer, appearance, security, device, about. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

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
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }

        SettingsGroup("Transfer mode") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TransferMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.transferMode == mode,
                        onClick = { viewModel.setMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = TransferMode.entries.size),
                    ) { Text(mode.label()) }
                }
            }
            Text(
                "Auto picks the best available transport and falls back gracefully.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsGroup("Appearance") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.setTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                    ) { Text(mode.label()) }
                }
            }
        }

        SettingsGroup("Device") {
            OutlinedTextField(
                value = settings.deviceName,
                onValueChange = { viewModel.setDeviceName(it) },
                label = { Text("Device name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SettingSwitch("Visible to nearby devices", settings.deviceVisible) { viewModel.setVisible(it) }
        }

        SettingsGroup("Transfer") {
            SettingSwitch("Auto-accept trusted devices", settings.autoAcceptTrusted) { viewModel.setAutoAccept(it) }
            SettingSwitch("Resume interrupted transfers", settings.resumeEnabled) { viewModel.setResume(it) }
            SettingSwitch("Keep screen awake during transfer", settings.keepScreenAwake) { viewModel.setKeepAwake(it) }
            SettingSwitch("Encrypt transfers (AES-256-GCM)", settings.encryptionEnabled) { viewModel.setEncryption(it) }
        }

        SettingsGroup("Duplicates") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val policies = listOf(DuplicatePolicy.ASK, DuplicatePolicy.REPLACE, DuplicatePolicy.KEEP_BOTH, DuplicatePolicy.SKIP)
                policies.forEachIndexed { index, policy ->
                    SegmentedButton(
                        selected = settings.defaultDuplicatePolicy == policy,
                        onClick = { viewModel.setDuplicatePolicy(policy) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = policies.size),
                    ) { Text(policy.label()) }
                }
            }
        }

        SettingsGroup("About") {
            Text("VibeShare ${viewModel.versionName} · SETBD", style = MaterialTheme.typography.titleSmall)
            Text(
                "Your files stay on your devices. No cloud. No tracking. Core transfer works fully offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { viewModel.checkForUpdate() }) { Text("Check for updates") }
                Spacer(Modifier.padding(4.dp))
                Text(
                    when {
                        updateState.checking -> "Checking…"
                        updateState.manifest != null && updateState.readyToInstall == null ->
                            "New version ${updateState.manifest?.versionName} available"
                        updateState.readyToInstall != null -> "Update downloaded & verified"
                        updateState.message != null -> updateState.message ?: ""
                        else -> "Version up to date"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (updateState.manifest != null && updateState.manifest?.versionCode ?: 0 > viewModel.versionCode) {
                Text(
                    updateState.manifest?.releaseNotes ?: "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { viewModel.downloadUpdate() },
                    enabled = updateState.readyToInstall == null && !updateState.checking,
                ) { Text("Download update (${viewModel.sizeLabel()})") }
            }
            if (updateState.readyToInstall != null) {
                Button(onClick = { viewModel.installUpdate() }) { Text("Install update") }
            }
            updateState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = viewModel::onVersionTap,
                content = { Text("Build ${viewModel.versionName} (v${viewModel.versionCode})") },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun TransferMode.label(): String = when (this) {
    TransferMode.AUTO -> "Auto"
    TransferMode.WIFI_DIRECT -> "Wi-Fi Direct"
    TransferMode.LOCAL_WIFI -> "Local Wi-Fi"
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.DARK -> "Dark"
    ThemeMode.AMOLED -> "AMOLED"
    ThemeMode.LIGHT -> "Light"
}

private fun DuplicatePolicy.label(): String = when (this) {
    DuplicatePolicy.ASK -> "Ask"
    DuplicatePolicy.REPLACE -> "Replace"
    DuplicatePolicy.KEEP_BOTH -> "Keep both"
    DuplicatePolicy.SKIP -> "Skip"
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = VibeColors.Violet)
    Spacer(Modifier.height(8.dp))
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val updateManager: UpdateManager,
    private val devModeSink: DevModeSink,
) : ViewModel() {

    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsState())

    val updateState = updateManager.state

    val versionCode: Int = BuildVersion.CODE
    val versionName: String = BuildVersion.NAME

    fun setMode(mode: TransferMode) = launchSet { settingsRepository.setTransferMode(mode) }
    fun setTheme(mode: ThemeMode) = launchSet { settingsRepository.setThemeMode(mode) }
    fun setDeviceName(name: String) = launchSet { settingsRepository.setDeviceName(name) }
    fun setVisible(visible: Boolean) = launchSet { settingsRepository.setDeviceVisible(visible) }
    fun setAutoAccept(enabled: Boolean) = launchSet { settingsRepository.setAutoAcceptTrusted(enabled) }
    fun setResume(enabled: Boolean) = launchSet { settingsRepository.setResumeEnabled(enabled) }
    fun setKeepAwake(enabled: Boolean) = launchSet { settingsRepository.setKeepScreenAwake(enabled) }
    fun setEncryption(enabled: Boolean) = launchSet { settingsRepository.setEncryptionEnabled(enabled) }
    fun setDuplicatePolicy(policy: DuplicatePolicy) = launchSet { settingsRepository.setDefaultDuplicatePolicy(policy) }

    fun checkForUpdate() {
        viewModelScope.launch {
            val endpoint = settings.value.updaterEndpoint.ifBlank { UpdateManager.DEFAULT_ENDPOINT }
            updateManager.check(endpoint)
        }
    }

    fun downloadUpdate() {
        val manifest = updateState.value.manifest ?: return
        viewModelScope.launch { updateManager.download(manifest) }
    }

    fun installUpdate() {
        updateState.value.readyToInstall?.let { updateManager.install(it) }
    }

    fun sizeLabel(): String = updateState.value.manifest?.let { updateManager.sizeLabel(it) } ?: "—"

    /** Hidden developer mode: 7 taps on the version row (like platform settings). */
    fun onVersionTap() {
        devModeSink.tapCount += 1
        if (devModeSink.tapCount >= 7) {
            devModeSink.tapCount = 0
            viewModelScope.launch { settingsRepository.setDeveloperMode(!settings.value.developerMode) }
        }
    }

    private fun launchSet(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

/** Shared build info holder (kept simple for JVM-testability of logic). */
object BuildVersion {
    const val CODE: Int = 1
    const val NAME: String = "1.0.0"
}

/** Holds the dev-mode tap counter across configuration changes (process-wide). */
class DevModeSink {
    var tapCount: Int = 0
}
