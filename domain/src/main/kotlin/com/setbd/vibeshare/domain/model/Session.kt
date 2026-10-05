package com.setbd.vibeshare.domain.model

/** A short-lived pairing/share session created by the host device. */
data class ShareSession(
    val sessionId: String,
    val token: String,
    val pin: String,
    val mode: TransferMode,
    val host: String?,
    val port: Int,
    val createdAtMs: Long,
    val expiresAtMs: Long,
)

/** History entry persisted in Room (spec section 12). */
data class HistoryEntry(
    val id: Long = 0L,
    val direction: TransferDirection,
    val peerName: String,
    val fileNames: String,
    val fileCount: Int,
    val totalBytes: Long,
    val success: Boolean,
    val durationMs: Long,
    val averageSpeedBps: Long,
    val timestampMs: Long,
)
