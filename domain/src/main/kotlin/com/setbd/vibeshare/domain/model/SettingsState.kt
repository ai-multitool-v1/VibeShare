package com.setbd.vibeshare.domain.model

/** Appearance options (spec section 20). */
enum class ThemeMode { SYSTEM, DARK, AMOLED, LIGHT }

/** Immutable snapshot of user settings backed by DataStore. */
data class SettingsState(
    val transferMode: TransferMode = TransferMode.AUTO,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val deviceName: String = "",
    val deviceVisible: Boolean = true,
    val autoAcceptTrusted: Boolean = false,
    val resumeEnabled: Boolean = true,
    val keepScreenAwake: Boolean = true,
    val encryptionEnabled: Boolean = true,
    val chunkSizeKiB: Int = 256,
    val downloadTreeUri: String = "",
    val defaultDuplicatePolicy: DuplicatePolicy = DuplicatePolicy.ASK,
    val updaterEndpoint: String = "",
    val trustedDeviceIds: Set<String> = emptySet(),
    val developerMode: Boolean = false,
)
