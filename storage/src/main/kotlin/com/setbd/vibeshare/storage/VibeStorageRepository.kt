package com.setbd.vibeshare.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.core.error.AppError
import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.DuplicatePolicy
import com.setbd.vibeshare.domain.repository.DuplicateResolution
import com.setbd.vibeshare.domain.repository.StorageRepository
import java.io.File

/**
 * Android implementation of destination checks: safe filenames, existence,
 * duplicate resolution and capacity pre-checks. Applies to both the default
 * app-private destination and a user-selected SAF tree.
 */
class VibeStorageRepository(private val context: Context) : StorageRepository {

    /** Default receive folder: app-specific external storage (no permission needed). */
    fun defaultReceivedDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "Received").apply { mkdirs() }
    }

    fun receivedDirFromTree(treeUri: String): DocumentFile? {
        if (treeUri.isBlank()) return null
        return runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        }.getOrNull()?.takeIf { it.canWrite() }
    }

    override suspend fun availableBytes(): Long {
        val tree = receivedDirFromTree(currentTreeUri)
        if (tree != null) {
            // SAF volumes: approximate with the primary external volume (documented limitation).
            return usableSpaceApproximation()
        }
        return defaultReceivedDir().usableSpace
    }

    private var currentTreeUri: String = ""

    fun setCurrentTreeUri(uri: String) {
        currentTreeUri = uri
    }

    /** Read access for the incoming-storage pipeline. */
    fun currentTree(): String = currentTreeUri

    private fun usableSpaceApproximation(): Long =
        Environment.getExternalStorageDirectory()?.usableSpace ?: -1L

    override suspend fun ensureCanFit(requiredBytes: Long): VibeResult<Unit> {
        val available = availableBytes()
        if (available >= 0 && available < requiredBytes) {
            return VibeResult.failure(AppError.InsufficientStorage(requiredBytes, available))
        }
        return VibeResult.success(Unit)
    }

    override fun safeFileName(raw: String): String {
        // Strip path separators and control characters; keep unicode letters.
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .ifBlank { "file" }
        val name = File(cleaned).name // belt-and-braces against traversal
        return if (name.length > 180) {
            val dot = name.lastIndexOf('.')
            if (dot > 0) name.take(180 - (name.length - dot)) + name.substring(dot) else name.take(180)
        } else name
    }

    override suspend fun exists(fileName: String): Boolean {
        val safe = safeFileName(fileName)
        val tree = receivedDirFromTree(currentTreeUri)
        return if (tree != null) {
            tree.findFile(safe) != null
        } else {
            File(defaultReceivedDir(), safe).exists()
        }
    }

    override suspend fun resolveDuplicate(fileName: String, policy: DuplicatePolicy): DuplicateResolution {
        val safe = safeFileName(fileName)
        if (!exists(safe)) return DuplicateResolution.WriteAs(safe, replaceExisting = false)
        return when (policy) {
            DuplicatePolicy.SKIP -> DuplicateResolution.Skip
            DuplicatePolicy.REPLACE -> DuplicateResolution.WriteAs(safe, replaceExisting = true)
            DuplicatePolicy.KEEP_BOTH -> DuplicateResolution.WriteAs(untakenName(safe), replaceExisting = false)
            DuplicatePolicy.ASK -> DuplicateResolution.WriteAs(safe, replaceExisting = false)
        }
    }

    /** Generates "name (1).ext", "name (2).ext", ... until untaken. */
    private suspend fun untakenName(original: String): String {
        val dot = original.lastIndexOf('.')
        val base = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""
        var counter = 1
        while (true) {
            val candidate = "$base ($counter)$ext"
            if (!exists(candidate)) return candidate
            counter++
            if (counter > 9999) return "$base-${System.currentTimeMillis()}$ext"
        }
    }

    companion object {
        private const val TAG = "StorageRepo"
    }
}
