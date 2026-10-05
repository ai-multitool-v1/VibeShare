package com.setbd.vibeshare.domain.model

/** One user-installed application exposed by the Apps category (spec section 9). */
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val apkSizeBytes: Long,
    /** True when the app is a split/App Bundle app: single-file export is impossible. */
    val isSplit: Boolean,
)

/** Resolved APK ready to share. */
data class ExportedApk(
    val packageName: String,
    val appName: String,
    val fileUri: String,
    val fileName: String,
    val sizeBytes: Long,
)

/** Manifest served by the release endpoint consumed by the in-app updater (spec section 26). */
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val releaseNotes: String,
    val fileSize: Long = 0L,
)
