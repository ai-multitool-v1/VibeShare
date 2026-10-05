package com.setbd.vibeshare.transfer.model

import com.setbd.vibeshare.domain.model.DuplicatePolicy
import com.setbd.vibeshare.domain.model.TransferFile
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * [FileSource] over a local file path. Used for APK exports, cached files and
 * extensively by the JVM test suite (loopback transfers).
 */
class LocalFileSource(
    private val path: Path,
    displayName: String? = null,
    mimeType: String = "application/octet-stream",
) : FileSource {

    private var channel: FileChannel? = null

    override val file: TransferFile = TransferFile(
        id = TransferFile.newId(),
        displayName = displayName ?: path.fileName.toString(),
        sizeBytes = runCatching { java.nio.file.Files.size(path) }.getOrElse { 0L },
        mimeType = mimeType,
        sha256 = null,
    )

    override fun openChannel(resumeOffset: Long): FileSource.Reader {
        val opened = FileChannel.open(path, StandardOpenOption.READ)
        channel = opened
        if (resumeOffset > 0) {
            opened.position(resumeOffset)
        }
        return object : FileSource.Reader {
            override fun read(buffer: ByteBuffer): Int = opened.read(buffer)
            override fun close() {
                runCatching { opened.close() }
            }
        }
    }

    override fun close() {
        runCatching { channel?.close() }
        channel = null
    }
}

/** Simple [IncomingStorage] over a local directory - the JVM test twin of the Android pipeline. */
class LocalDirectoryStorage(
    private val destinationDir: Path,
    private val defaultPolicy: com.setbd.vibeshare.domain.model.DuplicatePolicy =
        com.setbd.vibeshare.domain.model.DuplicatePolicy.REPLACE,
    /** When set, overrides the reported usable space (failure simulation). */
    var simulateCapacity: Long? = null,
) : IncomingStorage {

    var lastCapacityError: com.setbd.vibeshare.core.error.AppError? = null

    override suspend fun checkSessionCapacity(totalBytes: Long): com.setbd.vibeshare.core.error.AppError? {
        val usable = simulateCapacity ?: java.nio.file.Files.getFileStore(destinationDir).usableSpace
        return if (usable < totalBytes) {
            com.setbd.vibeshare.core.error.AppError.InsufficientStorage(totalBytes, usable).also {
                lastCapacityError = it
            }
        } else null
    }

    override suspend fun openFile(meta: IncomingFileMeta, policy: DuplicatePolicy): IncomingFileSink? {
        val effective = if (policy == DuplicatePolicy.ASK) defaultPolicy else policy
        val safe = meta.name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifBlank { "file" }
        var finalName = safe
        val finalPath = destinationDir.resolve(finalName)
        if (java.nio.file.Files.exists(finalPath)) {
            when (effective) {
                DuplicatePolicy.SKIP -> return null
                DuplicatePolicy.REPLACE -> java.nio.file.Files.deleteIfExists(finalPath)
                DuplicatePolicy.KEEP_BOTH, DuplicatePolicy.ASK -> {
                    val dot = safe.lastIndexOf('.')
                    val base = if (dot > 0) safe.substring(0, dot) else safe
                    val ext = if (dot > 0) safe.substring(dot) else ""
                    var counter = 1
                    while (java.nio.file.Files.exists(destinationDir.resolve("$base ($counter)$ext"))) counter++
                    finalName = "$base ($counter)$ext"
                }
            }
        }
        // Deterministic temp name (name+size) so interrupted sessions resume.
        val tempKey = safe.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + meta.size
        val tempFile = File(destinationDir.toFile(), ".vtp-$tempKey.part")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
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
        val output = java.io.FileOutputStream(tempFile, true)
        return object : IncomingFileSink {
            override fun currentOffset(): Long = tempFile.length()
            override fun write(data: ByteArray, offset: Int, length: Int) {
                digest.update(data, offset, length)
                output.write(data, offset, length)
            }
            override suspend fun commit(expectedSha256: String?, computedSha256: String): com.setbd.vibeshare.core.result.VibeResult<String> {
                runCatching { output.flush(); output.close() }
                val valid = expectedSha256 == null || expectedSha256.equals(computedSha256, ignoreCase = true)
                if (!valid) {
                    tempFile.delete()
                    return com.setbd.vibeshare.core.result.VibeResult.failure(
                        com.setbd.vibeshare.core.error.AppError.ChecksumMismatch
                    )
                }
                val target = destinationDir.resolve(finalName)
                if (!tempFile.renameTo(target.toFile())) {
                    java.nio.file.Files.move(tempFile.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                return com.setbd.vibeshare.core.result.VibeResult.success(finalName)
            }
            override fun abort() {
                runCatching { output.close() }
                tempFile.delete()
            }
        }
    }

    override fun sessionCompleted(filesCommitted: Int, totalBytes: Long, durationMs: Long) = Unit

    override fun sessionFailed(error: String?) = Unit
}
