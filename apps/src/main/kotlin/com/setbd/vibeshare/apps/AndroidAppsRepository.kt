package com.setbd.vibeshare.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.core.error.AppError
import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.ExportedApk
import com.setbd.vibeshare.domain.model.InstalledApp
import com.setbd.vibeshare.domain.repository.AppsRepository
import java.io.File

/**
 * Lists launchable applications and exports base APKs for sharing
 * (spec section 9). Split/App Bundle apps are detected and reported as
 * unsupported rather than crashing or exporting broken files.
 *
 * Package visibility on Android 11+ is granted via QUERY_ALL_PACKAGES, which
 * is justified here because app sharing is a core product feature.
 */
class AndroidAppsRepository(private val context: Context) : AppsRepository {

    override suspend fun listInstalledApps(includeSystemUpdates: Boolean): List<InstalledApp> {
        val pm = context.packageManager
        val packages: List<PackageInfo> = runCatching {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        }.getOrElse {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }
        return packages.mapNotNull { info ->
            val app = info.applicationInfo ?: return@mapNotNull null
            val launchable = pm.getLaunchIntentForPackage(info.packageName) != null
            if (!launchable) return@mapNotNull null
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem && !isUpdatedSystem && !includeSystemUpdates) return@mapNotNull null
            val split = !app.splitSourceDirs.isNullOrEmpty()
            InstalledApp(
                packageName = info.packageName,
                appName = app.loadLabel(pm).toString(),
                versionName = info.versionName ?: "",
                versionCode = info.longVersionCode,
                apkSizeBytes = runCatching { File(app.sourceDir ?: "").length() }.getOrDefault(0L),
                isSplit = split,
            )
        }.sortedBy { it.appName.lowercase() }
    }

    override suspend fun exportApk(packageName: String): VibeResult<ExportedApk> {
        val pm = context.packageManager
        val app: ApplicationInfo = runCatching {
            pm.getApplicationInfo(packageName, 0)
        }.getOrElse {
            return VibeResult.failure(AppError.ApkNotFound("package $packageName not visible"))
        }
        if (!app.splitSourceDirs.isNullOrEmpty()) {
            return VibeResult.failure(AppError.ApkExtractionUnavailable)
        }
        val sourcePath = app.sourceDir
        if (sourcePath.isNullOrBlank()) {
            return VibeResult.failure(AppError.ApkNotFound("no source apk"))
        }
        val source = File(sourcePath)
        if (!source.exists() || !source.canRead()) {
            return VibeResult.failure(AppError.ApkExtractionUnavailable)
        }
        val appName = runCatching { app.loadLabel(pm).toString() }.getOrDefault(packageName)
        val exportDir = File(context.cacheDir, "apk_export").apply { mkdirs() }
        val safeLabel = appName.replace(Regex("[^A-Za-z0-9._ -]"), "").trim().ifBlank { packageName }
        val target = File(exportDir, "$safeLabel-$packageName.apk")
        return runCatching {
            if (!target.exists() || target.length() != source.length()) {
                source.copyTo(target, overwrite = true)
            }
            VibeResult.success(
                ExportedApk(
                    packageName = packageName,
                    appName = appName,
                    fileUri = android.net.Uri.fromFile(target).toString(),
                    fileName = target.name,
                    sizeBytes = target.length(),
                )
            )
        }.getOrElse {
            VibeLog.w(TAG, "APK export failed for $packageName", it)
            VibeResult.failure(AppError.ApkExtractionUnavailable)
        }
    }

    companion object {
        private const val TAG = "AppsRepository"
    }
}
