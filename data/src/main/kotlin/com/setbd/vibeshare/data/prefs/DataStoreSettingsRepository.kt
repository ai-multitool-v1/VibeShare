package com.setbd.vibeshare.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.setbd.vibeshare.domain.model.DuplicatePolicy
import com.setbd.vibeshare.domain.model.SettingsState
import com.setbd.vibeshare.domain.model.ThemeMode
import com.setbd.vibeshare.domain.model.TransferMode
import com.setbd.vibeshare.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.vibeDataStore: DataStore<Preferences> by preferencesDataStore(name = "vibeshare_settings")

/** DataStore-backed settings. All preferences stay on-device. */
class DataStoreSettingsRepository(private val context: Context) : SettingsRepository {

    private object Keys {
        val MODE = stringPreferencesKey("transfer_mode")
        val THEME = stringPreferencesKey("theme_mode")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val VISIBLE = booleanPreferencesKey("device_visible")
        val AUTO_ACCEPT = booleanPreferencesKey("auto_accept_trusted")
        val RESUME = booleanPreferencesKey("resume_enabled")
        val KEEP_AWAKE = booleanPreferencesKey("keep_screen_awake")
        val ENCRYPTION = booleanPreferencesKey("encryption_enabled")
        val CHUNK = intPreferencesKey("chunk_size_kib")
        val DOWNLOAD_TREE = stringPreferencesKey("download_tree_uri")
        val DUP_POLICY = stringPreferencesKey("duplicate_policy")
        val UPDATER_URL = stringPreferencesKey("updater_endpoint")
        val TRUSTED = stringSetPreferencesKey("trusted_devices")
        val DEV_MODE = booleanPreferencesKey("developer_mode")
    }

    override val settings: Flow<SettingsState> = context.vibeDataStore.data.map { prefs ->
        SettingsState(
            transferMode = prefs[Keys.MODE]?.let { runCatching { TransferMode.valueOf(it) }.getOrNull() }
                ?: TransferMode.AUTO,
            themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            deviceName = prefs[Keys.DEVICE_NAME] ?: android.os.Build.MODEL,
            deviceVisible = prefs[Keys.VISIBLE] ?: true,
            autoAcceptTrusted = prefs[Keys.AUTO_ACCEPT] ?: false,
            resumeEnabled = prefs[Keys.RESUME] ?: true,
            keepScreenAwake = prefs[Keys.KEEP_AWAKE] ?: true,
            encryptionEnabled = prefs[Keys.ENCRYPTION] ?: true,
            chunkSizeKiB = prefs[Keys.CHUNK] ?: 256,
            downloadTreeUri = prefs[Keys.DOWNLOAD_TREE] ?: "",
            defaultDuplicatePolicy = prefs[Keys.DUP_POLICY]?.let { runCatching { DuplicatePolicy.valueOf(it) }.getOrNull() }
                ?: DuplicatePolicy.ASK,
            updaterEndpoint = prefs[Keys.UPDATER_URL] ?: "",
            trustedDeviceIds = prefs[Keys.TRUSTED] ?: emptySet(),
            developerMode = prefs[Keys.DEV_MODE] ?: false,
        )
    }

    override suspend fun setTransferMode(mode: TransferMode) =
        edit { it[Keys.MODE] = mode.name }

    override suspend fun setThemeMode(mode: ThemeMode) =
        edit { it[Keys.THEME] = mode.name }

    override suspend fun setDeviceName(name: String) =
        edit { it[Keys.DEVICE_NAME] = name.trim().take(32).ifBlank { android.os.Build.MODEL } }

    override suspend fun setDeviceVisible(visible: Boolean) =
        edit { it[Keys.VISIBLE] = visible }

    override suspend fun setAutoAcceptTrusted(enabled: Boolean) =
        edit { it[Keys.AUTO_ACCEPT] = enabled }

    override suspend fun setResumeEnabled(enabled: Boolean) =
        edit { it[Keys.RESUME] = enabled }

    override suspend fun setKeepScreenAwake(enabled: Boolean) =
        edit { it[Keys.KEEP_AWAKE] = enabled }

    override suspend fun setEncryptionEnabled(enabled: Boolean) =
        edit { it[Keys.ENCRYPTION] = enabled }

    override suspend fun setChunkSizeKiB(sizeKiB: Int) =
        edit { it[Keys.CHUNK] = sizeKiB.coerceIn(16, 1024) }

    override suspend fun setDownloadTreeUri(uri: String) =
        edit { it[Keys.DOWNLOAD_TREE] = uri }

    override suspend fun setDefaultDuplicatePolicy(policy: DuplicatePolicy) =
        edit { it[Keys.DUP_POLICY] = policy.name }

    override suspend fun setUpdaterEndpoint(url: String) =
        edit { it[Keys.UPDATER_URL] = url.trim() }

    override suspend fun setDeveloperMode(enabled: Boolean) =
        edit { it[Keys.DEV_MODE] = enabled }

    override suspend fun addTrustedDevice(deviceId: String) =
        edit { it[Keys.TRUSTED] = (it[Keys.TRUSTED] ?: emptySet()) + deviceId }

    override suspend fun removeTrustedDevice(deviceId: String) =
        edit { it[Keys.TRUSTED] = (it[Keys.TRUSTED] ?: emptySet()) - deviceId }

    override suspend fun isDeviceTrusted(deviceId: String): Boolean =
        settings.first().trustedDeviceIds.contains(deviceId)

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.vibeDataStore.edit(block)
    }
}
