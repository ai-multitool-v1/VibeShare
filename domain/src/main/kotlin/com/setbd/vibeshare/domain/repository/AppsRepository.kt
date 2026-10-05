package com.setbd.vibeshare.domain.repository

import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.ExportedApk
import com.setbd.vibeshare.domain.model.InstalledApp

/** Installed-app discovery and APK export (spec section 9). */
interface AppsRepository {
    /** Lists launchable, user-visible applications with metadata. */
    suspend fun listInstalledApps(includeSystemUpdates: Boolean = true): List<InstalledApp>

    /**
     * Exports the base APK of [packageName] into a shareable location.
     * Returns [com.setbd.vibeshare.core.error.AppError.ApkExtractionUnavailable]
     * for split/App Bundle apps or when extraction is restricted.
     */
    suspend fun exportApk(packageName: String): VibeResult<ExportedApk>
}
