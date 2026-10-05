package com.setbd.vibeshare.app.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.setbd.vibeshare.app.util.StateColors
import com.setbd.vibeshare.core.Formats
import com.setbd.vibeshare.domain.model.InstalledApp
import com.setbd.vibeshare.domain.repository.AppsRepository
import com.setbd.vibeshare.ui.theme.VibeColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * Installed-app sharing (spec section 9): searchable, multi-selectable app
 * list; APK export happens on send. Split/App Bundle apps are flagged and
 * explained instead of silently failing.
 */
@Composable
fun AppsScreen(
    onBack: () -> Unit,
    viewModel: AppsViewModel = koinViewModel(),
) {
    val state by viewModel.ui.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Share apps",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQuery,
            label = { Text("Search installed apps") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::selectAll) { Text("Select all") }
            OutlinedButton(onClick = viewModel::clearSelection) { Text("Clear") }
            Button(
                onClick = onBack,
                enabled = state.selected.isNotEmpty(),
            ) { Text("Send ${state.selected.size}") }
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.filtered, key = { it.packageName }) { app ->
                AppRow(
                    app = app,
                    selected = app.packageName in state.selected,
                    onToggle = { viewModel.toggle(app) },
                )
            }
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, selected: Boolean, onToggle: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) VibeColors.Violet.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(app.appName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "v${app.versionName} · ${Formats.bytes(app.apkSizeBytes)}" +
                        if (app.isSplit) " · split APK (single-file share unavailable)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (app.isSplit) StateColors.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
    }
}

class AppsViewModel(private val appsRepository: AppsRepository) : ViewModel() {

    data class UiState(
        val apps: List<InstalledApp> = emptyList(),
        val query: String = "",
        val selected: Set<String> = emptySet(),
    ) {
        val filtered: List<InstalledApp>
            get() = if (query.isBlank()) apps
            else apps.filter { it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(apps = appsRepository.listInstalledApps())
        }
    }

    fun onQuery(query: String) {
        _ui.value = _ui.value.copy(query = query)
    }

    fun toggle(app: InstalledApp) {
        val selected = _ui.value.selected.toMutableSet()
        if (!selected.add(app.packageName)) selected.remove(app.packageName)
        _ui.value = _ui.value.copy(selected = selected)
    }

    fun selectAll() {
        _ui.value = _ui.value.copy(selected = _ui.value.filtered.map { it.packageName }.toSet())
    }

    fun clearSelection() {
        _ui.value = _ui.value.copy(selected = emptySet())
    }
}
