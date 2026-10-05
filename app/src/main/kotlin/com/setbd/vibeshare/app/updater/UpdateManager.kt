package com.setbd.vibeshare.app.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.setbd.vibeshare.core.Formats
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.core.error.AppError
import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.UpdateManifest
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * In-app updater (spec section 26).
 *
 * - Reads a configurable JSON release manifest endpoint.
 * - Compares versionCode, shows notes/size.
 * - Downloads to cache, verifies sha-256 BEFORE offering install.
 * - Never silently installs: hands the verified APK to the Android installer,
 *   honoring user confirmation and platform restrictions.
 * - Fails safely on checksum mismatch (file discarded).
 */
class UpdateManager(
    private val context: Context,
    private val currentVersionCode: Int,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    data class UpdateState(
        val checking: Boolean = false,
        val manifest: UpdateManifest? = null,
        val downloadPercent: Int = 0,
        val downloadedBytes: Long = 0,
        val message: String? = null,
        val readyToInstall: File? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(endpoint: String): VibeResult<UpdateManifest?> = withContext(ioDispatcher) {
        if (endpoint.isBlank()) return@withContext VibeResult.failure(AppError.UpdateCheckFailed("no endpoint"))
        _state.value = UpdateState(checking = true)
        try {
            val connection = URL(endpoint).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val manifest = parseManifest(body)
                ?: return@withContext VibeResult.failure(AppError.UpdateCheckFailed("invalid manifest").also {
                    _state.value = UpdateState(error = "Invalid update manifest")
                })
            _state.value = UpdateState(manifest = manifest)
            if (manifest.versionCode <= currentVersionCode) {
                _state.value = UpdateState(manifest = manifest, message = "Already on the latest version")
                VibeResult.success(null)
            } else {
                VibeResult.success(manifest)
            }
        } catch (e: Exception) {
            VibeLog.w(TAG, "Update check failed", e)
            _state.value = UpdateState(error = e.message)
            VibeResult.failure(AppError.UpdateCheckFailed(e.message ?: "network error"))
        }
    }

    private fun parseManifest(body: String): UpdateManifest? = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        UpdateManifest(
            versionCode = obj["versionCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null,
            versionName = obj["versionName"]?.jsonPrimitive?.content ?: "",
            apkUrl = obj["apkUrl"]?.jsonPrimitive?.content ?: return null,
            sha256 = obj["sha256"]?.jsonPrimitive?.content ?: return null,
            releaseNotes = obj["releaseNotes"]?.jsonPrimitive?.content ?: "",
            fileSize = obj["fileSize"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
        )
    }.getOrNull()

    suspend fun download(manifest: UpdateManifest): VibeResult<File> = withContext(ioDispatcher) {
        try {
            val target = File(context.cacheDir, "update-${manifest.versionName}.apk")
            val connection = URL(manifest.apkUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val total = manifest.fileSize.takeIf { it > 0 } ?: connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        _state.value = _state.value.copy(
                            downloadPercent = if (total > 0) ((done * 100) / total).toInt() else 0,
                            downloadedBytes = done,
                        )
                    }
                }
            }
            val computed = HkdfHex.toHex(digest.digest())
            if (!computed.equals(manifest.sha256, ignoreCase = true)) {
                target.delete()
                _state.value = _state.value.copy(error = "Checksum mismatch")
                return@withContext VibeResult.failure(AppError.UpdateChecksumMismatch)
            }
            _state.value = _state.value.copy(readyToInstall = target, downloadPercent = 100)
            VibeResult.success(target)
        } catch (e: Exception) {
            VibeLog.w(TAG, "Update download failed", e)
            _state.value = _state.value.copy(error = e.message)
            VibeResult.failure(AppError.UpdateDownloadFailed(e.message ?: "download failed"))
        }
    }

    /**
     * Opens the platform installer for the verified APK. Requires the user's
     * explicit confirmation (and "install unknown apps" consent on modern
     * Android). VibeShare never bypasses this dialog.
     */
    fun install(apk: File): Boolean {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = try {
            FileProvider.getUriForFile(context, authority, apk)
        } catch (e: IllegalArgumentException) {
            VibeLog.w(TAG, "Cannot share APK ${apk.name}", e)
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun sizeLabel(manifest: UpdateManifest): String =
        if (manifest.fileSize > 0) Formats.bytes(manifest.fileSize) else "—"

    companion object {
        private const val TAG = "UpdateManager"

        /**
         * Default release manifest location. Points at the VibeShare repo's
         * raw release.json (see RELEASE.md). Overridable in Settings for
         * self-hosted distribution.
         */
        const val DEFAULT_ENDPOINT: String =
            "https://raw.githubusercontent.com/ai-multitool-v1/VibeShare/main/release.json"
    }
}

private object HkdfHex {
    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            append("0123456789abcdef"[(b.toInt() shr 4) and 0xf])
            append("0123456789abcdef"[b.toInt() and 0xf])
        }
    }
}
