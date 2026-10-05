package com.setbd.vibeshare.transfer.model

import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.DuplicatePolicy

/** Metadata of an incoming file announced by the sender. */
data class IncomingFileMeta(
    val fileId: String,
    val index: Int,
    val count: Int,
    val name: String,
    val size: Long,
    val mime: String,
    val declaredSha256: String?,
    val sessionTotalBytes: Long,
)

/**
 * Write-side abstraction implemented by :storage on Android and by a temp-dir
 * implementation in JVM tests. Enforces capacity checks, safe filenames,
 * duplicate policy and atomic finalization (spec sections 23-24).
 */
interface IncomingStorage {
    /**
     * Pre-check before accepting the session. Return the error to reject
     * (e.g. InsufficientStorage) or null to accept.
     */
    suspend fun checkSessionCapacity(totalBytes: Long): com.setbd.vibeshare.core.error.AppError?

    /**
     * Opens (or reopens for resume) a temp sink for one file.
     * Returns null when the file must be skipped (duplicate policy SKIP,
     * or resume target unavailable for restart).
     */
    suspend fun openFile(meta: IncomingFileMeta, policy: DuplicatePolicy): IncomingFileSink?

    fun sessionCompleted(filesCommitted: Int, totalBytes: Long, durationMs: Long)

    fun sessionFailed(error: String?)
}

/**
 * One open temp file on the receiver. Implementations must keep the file in a
 * temporary location until [commit] succeeds (atomic finalize; spec section 23).
 */
interface IncomingFileSink {
    /** Bytes already persisted (temp file length) - used for resume offset. */
    fun currentOffset(): Long

    /** Appends bytes; implementations must also feed their integrity digest. */
    fun write(data: ByteArray, offset: Int, length: Int)

    /**
     * Verifies the streamed digest against the expected sha-256 and atomically
     * moves the temp file to its final destination name. Returns the final name.
     */
    suspend fun commit(expectedSha256: String?, computedSha256: String): VibeResult<String>

    /** Discards the temp file. */
    fun abort()
}
