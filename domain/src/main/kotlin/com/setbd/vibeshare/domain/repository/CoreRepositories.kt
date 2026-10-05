package com.setbd.vibeshare.domain.repository

import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.core.error.AppError
import com.setbd.vibeshare.domain.model.DuplicatePolicy
import com.setbd.vibeshare.domain.model.SettingsState
import com.setbd.vibeshare.domain.model.ThemeMode
import com.setbd.vibeshare.domain.model.TransferMode
import kotlinx.coroutines.flow.Flow

/** Preferences persistence (DataStore). */
interface SettingsRepository {
    val settings: Flow<SettingsState>

    suspend fun setTransferMode(mode: TransferMode)
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDeviceName(name: String)
    suspend fun setDeviceVisible(visible: Boolean)
    suspend fun setAutoAcceptTrusted(enabled: Boolean)
    suspend fun setResumeEnabled(enabled: Boolean)
    suspend fun setKeepScreenAwake(enabled: Boolean)
    suspend fun setEncryptionEnabled(enabled: Boolean)
    suspend fun setChunkSizeKiB(sizeKiB: Int)
    suspend fun setDownloadTreeUri(uri: String)
    suspend fun setDefaultDuplicatePolicy(policy: DuplicatePolicy)
    suspend fun setUpdaterEndpoint(url: String)
    suspend fun setDeveloperMode(enabled: Boolean)

    suspend fun addTrustedDevice(deviceId: String)
    suspend fun removeTrustedDevice(deviceId: String)

    suspend fun isDeviceTrusted(deviceId: String): Boolean
}

/** Local transfer history (Room). Never leaves the device. */
interface HistoryRepository {
    val entries: Flow<List<com.setbd.vibeshare.domain.model.HistoryEntry>>

    suspend fun add(entry: com.setbd.vibeshare.domain.model.HistoryEntry)
    suspend fun delete(id: Long)
    suspend fun clearAll()
}
