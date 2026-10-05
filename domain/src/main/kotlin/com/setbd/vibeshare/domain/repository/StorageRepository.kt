package com.setbd.vibeshare.domain.repository

import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.DuplicatePolicy

/**
 * Storage gate + destination resolution used by the receiver side of the engine.
 * Implemented in :storage; pure JVM counterparts are unit-tested directly.
 */
interface StorageRepository {

    /** Bytes available at the active receive destination, or -1 when unknown. */
    suspend fun availableBytes(): Long

    /**
     * Verifies the destination can hold [requiredBytes] before receiving starts.
     * Returns [com.setbd.vibeshare.core.error.AppError.InsufficientStorage] when short.
     */
    suspend fun ensureCanFit(requiredBytes: Long): VibeResult<Unit>

    /** Sanitizes a display filename into a safe on-disk name (path-traversal protection). */
    fun safeFileName(raw: String): String

    /** True when [fileName] already exists at the destination. */
    suspend fun exists(fileName: String): Boolean

    /** Applies the duplicate policy to produce a conflict-free final name. */
    suspend fun resolveDuplicate(fileName: String, policy: DuplicatePolicy): DuplicateResolution
}

sealed class DuplicateResolution {
    /** Write as the given final name (replaces existing when allowed). */
    data class WriteAs(val finalName: String, val replaceExisting: Boolean) : DuplicateResolution()

    /** Receiver chose/configured skip: no temp file will be accepted. */
    data object Skip : DuplicateResolution()
}
