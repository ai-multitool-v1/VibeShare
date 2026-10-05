package com.setbd.vibeshare.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.setbd.vibeshare.core.Constants
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.core.error.AppError
import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.DuplicatePolicy
import com.setbd.vibeshare.transfer.model.IncomingFileMeta
import com.setbd.vibeshare.transfer.model.IncomingFileSink
import com.setbd.vibeshare.transfer.model.IncomingStorage
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Receiver-side storage pipeline (spec sections 23-24):
 *
 * 1. capacity pre-check before accepting a session
 * 2. sanitized filenames (path traversal protection)
 * 3. streaming into a `.vibeshare_part` temp file with a live SHA-256 digest
 * 4. atomic finalize: rename within the destination (or streamed copy into a
 *    SAF tree) only AFTER integrity verification succeeds
 * 5. corrupt temps are always discarded, never surfaced as partial files
 */
class AndroidIncomingStorage(
    private val context: Context,
    private val repository: VibeStorageRepository,
    private val duplicatePolicyProvider: suspend () -> DuplicatePolicy,
) : IncomingStorage {

    private fun tempDir(): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "incoming_tmp").apply { mkdirs() }
    }

    override suspend fun checkSessionCapacity(totalBytes: Long): AppError? {
        return when (val result = repository.ensureCanFit(totalBytes)) {
            is VibeResult.Failure -> result.error
            is VibeResult.Success -> null
        }
    }

    override suspend fun openFile(meta: IncomingFileMeta, policy: DuplicatePolicy): IncomingFileSink? {
        val policyToUse = if (policy == DuplicatePolicy.ASK) duplicatePolicyProvider() else policy
        val safeName = repository.safeFileName(meta.name)
        val resolution = repository.resolveDuplicate(safeName, policyToUse)
        if (resolution == com.setbd.vibeshare.domain.repository.DuplicateResolution.Skip) {
            VibeLog.i(TAG, "Skipping duplicate ${meta.name}")
            return null
        }
        val finalName = (resolution as com.setbd.vibeshare.domain.repository.DuplicateResolution.WriteAs).finalName
        val replace = resolution.replaceExisting

        val tempFile = File(tempDir(), "${Constants.TEMP_PART_SUFFIX}.${meta.fileId}.${safeName}")
        // Resume support: reuse existing temp when sizes line up.
        if (tempFile.length() > meta.size) {
            tempFile.delete()
        }
        val digest = MessageDigest.getInstance("SHA-256")

        // Pre-feed digest with bytes already in the temp file (resume).
        if (tempFile.length() > 0) {
            tempFile.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }

        val tree = repository.receivedDirFromTree(repository.currentTree())
        return if (tree != null) {
            SafFileSink(context, tree, tempFile, finalName, replace, digest)
        } else {
            FileFileSink(defaultDir(), tempFile, finalName, replace, digest)
        }
    }

    private fun defaultDir(): File = repository.defaultReceivedDir()

    override fun sessionCompleted(filesCommitted: Int, totalBytes: Long, durationMs: Long) {
        VibeLog.i(TAG, "Session completed: $filesCommitted files, $totalBytes bytes in ${durationMs / 1000}s")
        tempDir().listFiles()?.forEach { leftover ->
            // Clean temps from failed/aborted transfers older than 24h.
            if (System.currentTimeMillis() - leftover.lastModified() > 24 * 3600_000L) leftover.delete()
        }
    }

    override fun sessionFailed(error: String?) {
        VibeLog.w(TAG, "Session failed: $error")
        // Temp files are intentionally kept for resume until cleanup window.
    }

    companion object {
        private const val TAG = "IncomingStorage"
    }
}

/** Sink writing to a plain File destination (default app folder). */
private class FileFileSink(
    private val destinationDir: File,
    private val tempFile: File,
    private val finalName: String,
    private val replaceExisting: Boolean,
    private val digest: MessageDigest,
) : IncomingFileSink {

    private val output = FileOutputStream(tempFile, true)

    override fun currentOffset(): Long = tempFile.length()

    override fun write(data: ByteArray, offset: Int, length: Int) {
        digest.update(data, offset, length)
        output.write(data, offset, length)
    }

    override suspend fun commit(expectedSha256: String?, computedSha256: String): VibeResult<String> {
        runCatching { output.flush() }
        runCatching { output.close() }
        val valid = expectedSha256 == null || expectedSha256.equals(computedSha256, ignoreCase = true)
        if (!valid) {
            tempFile.delete()
            return VibeResult.failure(AppError.ChecksumMismatch)
        }
        val target = File(destinationDir, finalName)
        if (replaceExisting) target.delete()
        return finalize(tempFile, target)
    }

    private fun finalize(temp: File, target: File): VibeResult<String> {
        val moved = temp.renameTo(target)
        val ok = moved || runCatching { temp.copyTo(target, overwrite = true); temp.delete() }.isSuccess
        return if (ok && target.exists()) {
            VibeLog.i("IncomingStorage", "Committed ${target.name} (${target.length()} bytes)")
            VibeResult.success(target.name)
        } else {
            temp.delete()
            VibeResult.failure(AppError.WriteFailed("finalize failed"))
        }
    }

    override fun abort() {
        runCatching { output.close() }
        tempFile.delete()
    }
}

/** Sink writing into a user-selected SAF tree. */
private class SafFileSink(
    private val appContext: Context,
    private val tree: DocumentFile,
    private val tempFile: File,
    private val finalName: String,
    private val replaceExisting: Boolean,
    private val digest: MessageDigest,
) : IncomingFileSink {

    override fun currentOffset(): Long = tempFile.length()

    override fun write(data: ByteArray, offset: Int, length: Int) {
        digest.update(data, offset, length)
        FileOutputStream(tempFile, true).use { it.write(data, offset, length) }
    }

    override suspend fun commit(expectedSha256: String?, computedSha256: String): VibeResult<String> {
        val valid = expectedSha256 == null || expectedSha256.equals(computedSha256, ignoreCase = true)
        if (!valid) {
            tempFile.delete()
            return VibeResult.failure(AppError.ChecksumMismatch)
        }
        if (replaceExisting) {
            tree.findFile(finalName)?.delete()
        }
        val finalDoc = tree.findFile(finalName) ?: tree.createFile(mimeFor(finalName), finalName)
        if (finalDoc == null) {
            tempFile.delete()
            return VibeResult.failure(AppError.WriteFailed("cannot create document"))
        }
        return runCatching {
            appContext.contentResolver.openOutputStream(finalDoc.uri, "w")?.use { out ->
                tempFile.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: return VibeResult.failure(AppError.WriteFailed("output stream null"))
            tempFile.delete()
            VibeResult.success(finalName)
        }.getOrElse {
            tempFile.delete()
            VibeResult.failure(AppError.WriteFailed(it.message ?: "SAF write failed"))
        }
    }

    private fun mimeFor(name: String): String {
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0) name.substring(dot + 1).lowercase() else ""
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    override fun abort() {
        tempFile.delete()
    }
}
