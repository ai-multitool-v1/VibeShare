package com.setbd.vibeshare.domain.model

import java.security.SecureRandom

/** Metadata for one file being sent or received. */
data class TransferFile(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val sha256: String? = null,
) {
    companion object {
        fun newId(): String = randomFileId()
        private val random = SecureRandom()
        private const val HEX = "0123456789abcdef"
        private fun randomFileId(length: Int = 16): String = buildString {
            repeat(length) { append(HEX[random.nextInt(16)]) }
        }
    }
}

enum class TransferDirection { SEND, RECEIVE }

/** File-level state machine, mirrored on both sides. */
enum class TransferFileState {
    PENDING,
    ACCEPTED,
    TRANSFERRING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    SKIPPED,
    FAILED,
    CANCELLED,
}
