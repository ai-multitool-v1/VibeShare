package com.setbd.vibeshare.domain.model

/** Live progress of one receiver from the sender's point of view (spec section 11). */
data class ReceiverProgress(
    val peerId: String,
    val peerName: String,
    val state: ConnectionLifecycle,
    val fileIndex: Int = 0,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val currentFileName: String = "",
    val currentFileBytesDone: Long = 0L,
    val currentFileBytesTotal: Long = 0L,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    /** Exponentially-smoothed current speed. */
    val speedBps: Long = 0L,
    /** Session average speed. */
    val averageSpeedBps: Long = 0L,
    val etaSeconds: Long = -1L,
    val error: String? = null,
) {
    val percent: Int
        get() = if (bytesTotal <= 0L) 0 else ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100)
}

/** Live progress of one incoming session on the receiver's side. */
data class IncomingProgress(
    val sessionId: String,
    val peerName: String,
    val state: ConnectionLifecycle,
    val fileIndex: Int = 0,
    val filesTotal: Int = 0,
    val currentFileName: String = "",
    val currentFileBytesDone: Long = 0L,
    val currentFileBytesTotal: Long = 0L,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val speedBps: Long = 0L,
    val etaSeconds: Long = -1L,
    val error: String? = null,
) {
    val percent: Int
        get() = if (bytesTotal <= 0L) 0 else ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100)
}
