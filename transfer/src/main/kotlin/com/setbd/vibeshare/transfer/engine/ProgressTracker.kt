package com.setbd.vibeshare.transfer.engine

import com.setbd.vibeshare.domain.model.ConnectionLifecycle
import com.setbd.vibeshare.domain.model.ReceiverProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks live progress for one receiver (sender side).
 * Speed is an exponential moving average over ~500 ms samples; average speed
 * and ETA derive from session start time. Never simulated: every number comes
 * from real acknowledged bytes.
 */
class ProgressTracker(
    private val peerId: String,
    private val peerName: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(
        ReceiverProgress(peerId = peerId, peerName = peerName, state = ConnectionLifecycle.CONNECTING)
    )
    val progress: StateFlow<ReceiverProgress> = _state.asStateFlow()

    private var lastSampleMs: Long = clock()
    private var lastBytes: Long = 0L
    private var emaBps: Double = 0.0
    private var startedAtMs: Long = 0L

    fun started() {
        startedAtMs = clock()
        lastSampleMs = startedAtMs
        update { it.copy(state = ConnectionLifecycle.TRANSFERRING) }
    }

    fun update(block: (ReceiverProgress) -> ReceiverProgress) {
        _state.value = block(_state.value)
    }

    /** Call after every acknowledged byte delta; recomputes speeds and ETA. */
    fun onBytesProgressed(
        bytesDone: Long,
        bytesTotal: Long,
        currentFileBytesDone: Long,
        currentFileBytesTotal: Long,
        fileIndex: Int,
        filesDone: Int,
        filesTotal: Int,
        currentFileName: String,
    ) {
        val now = clock()
        val dt = now - lastSampleMs
        if (dt >= 400) {
            val instBps = (bytesDone - lastBytes) * 1000.0 / dt.coerceAtLeast(1)
            emaBps = if (emaBps == 0.0) instBps else emaBps * 0.65 + instBps * 0.35
            lastSampleMs = now
            lastBytes = bytesDone
        }
        update { p ->
            val elapsed = (now - startedAtMs).coerceAtLeast(1)
            val avg = bytesDone * 1000.0 / elapsed
            val remaining = (bytesTotal - bytesDone).coerceAtLeast(0)
            val eta = if (emaBps > 1.0) (remaining / emaBps).toLong() else -1L
            p.copy(
                state = ConnectionLifecycle.TRANSFERRING,
                fileIndex = fileIndex,
                filesDone = filesDone,
                filesTotal = filesTotal,
                currentFileName = currentFileName,
                currentFileBytesDone = currentFileBytesDone,
                currentFileBytesTotal = currentFileBytesTotal,
                bytesDone = bytesDone,
                bytesTotal = bytesTotal,
                speedBps = emaBps.toLong(),
                averageSpeedBps = avg.toLong(),
                etaSeconds = eta,
            )
        }
    }

    fun state(state: ConnectionLifecycle, error: String? = null) {
        update { it.copy(state = state, error = error) }
    }

    fun completed() {
        update { it.copy(state = ConnectionLifecycle.COMPLETED, etaSeconds = 0L) }
    }

    fun snapshot(): ReceiverProgress = _state.value
}
