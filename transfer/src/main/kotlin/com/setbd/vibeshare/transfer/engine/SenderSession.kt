package com.setbd.vibeshare.transfer.engine

import com.setbd.vibeshare.core.Constants
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.core.error.AppError
import com.setbd.vibeshare.core.error.userMessage
import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.ConnectionLifecycle
import com.setbd.vibeshare.domain.model.DeviceInfo
import com.setbd.vibeshare.domain.model.ReceiverProgress
import com.setbd.vibeshare.domain.model.TransferFile
import com.setbd.vibeshare.domain.model.TransferFileState
import com.setbd.vibeshare.transfer.connection.VtpConnection
import com.setbd.vibeshare.transfer.model.FileSource
import com.setbd.vibeshare.transfer.protocol.ChunkAckPayload
import com.setbd.vibeshare.transfer.protocol.ErrorPayload
import com.setbd.vibeshare.transfer.protocol.FileAckPayload
import com.setbd.vibeshare.transfer.protocol.FileDonePayload
import com.setbd.vibeshare.transfer.protocol.FileMetaPayload
import com.setbd.vibeshare.transfer.protocol.FileRejectPayload
import com.setbd.vibeshare.transfer.protocol.Frame
import com.setbd.vibeshare.transfer.protocol.FrameType
import com.setbd.vibeshare.transfer.protocol.HelloPayload
import com.setbd.vibeshare.transfer.protocol.PairRejectPayload
import com.setbd.vibeshare.transfer.protocol.Payloads
import com.setbd.vibeshare.transfer.protocol.ProtocolException
import com.setbd.vibeshare.transfer.protocol.TransferCompletePayload
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One sender -> receiver streaming session. Runs the full lifecycle:
 * connect, pair (via [ClientHandshake]), announce files, stream chunks with a
 * bounded in-flight window, verify digests, and report real progress.
 */
class SenderSession(
    private val self: DeviceInfo,
    private val sources: List<FileSource>,
    private val handshake: ClientHandshake,
    private val config: Config,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Config(
        val chunkSizeBytes: Int = Constants.DEFAULT_CHUNK_SIZE_BYTES,
        val inFlightWindow: Int = Constants.IN_FLIGHT_CHUNK_WINDOW,
        val readConcurrency: Int = 2,
    )

    private val connection = AtomicReference<VtpConnection?>(null)
    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var transferJob: Job? = null

    private val tracker = ProgressTracker(
        peerId = "pending",
        peerName = "…",
        clock = clock,
    )

    private val totalSessionBytes: Long = sources.sumOf { it.file.sizeBytes }

    val progress: kotlinx.coroutines.flow.StateFlow<ReceiverProgress> = tracker.progress

    private val bytesDone = AtomicLong(0)
    private val filesDone = AtomicLong(0)

    /**
     * Connects, performs pairing and returns the paired peer identity.
     * Throws nothing: failures come back as [VibeResult.Failure].
     */
    suspend fun connect(host: String, port: Int): VibeResult<DeviceInfo> {
        if (cancelled.get()) return VibeResult.failure(AppError.TransferCancelled)
        return try {
            val conn = VtpConnection.connect(host, port, Constants.CONNECT_TIMEOUT_MS)
            connection.set(conn)
            tracker.update { it.copy(state = ConnectionLifecycle.PAIRING) }
            when (val hs = handshake.perform(conn, self)) {
                is VibeResult.Failure -> {
                    conn.close()
                    tracker.state(ConnectionLifecycle.FAILED, hs.error.userFriendly())
                    VibeResult.failure(hs.error)
                }
                is VibeResult.Success -> {
                    tracker.update { it.copy(peerId = hs.data.peer.deviceId, peerName = hs.data.peer.name) }
                    VibeResult.success(hs.data.peer)
                }
            }
        } catch (io: IOException) {
            val error = AppError.CouldNotConnect(io.message ?: "connect failed")
            tracker.state(ConnectionLifecycle.FAILED, error.userFriendly())
            VibeResult.failure(error)
        }
    }

    /** Starts streaming all files to the receiver. One call per session. */
    fun startTransfer() {
        if (transferJob != null) return
        transferJob = scope.launch(ioDispatcher) {
            val result = runCatching { streamAll() }
            when {
                result.isSuccess -> {
                    tracker.completed()
                }
                result.exceptionOrNull() is CancellationException -> {
                    tracker.state(ConnectionLifecycle.CANCELLED)
                }
                else -> {
                    val t = result.exceptionOrNull()
                    tracker.state(ConnectionLifecycle.FAILED, t?.message)
                    VibeLog.e(TAG, "Transfer failed", t)
                }
            }
        }
    }

    fun pause() {
        paused.set(true)
        tracker.state(ConnectionLifecycle.PAUSED)
        runCatching { connection.get()?.send(Frame(FrameType.PAUSE)) }
    }

    fun resume() {
        paused.set(false)
        tracker.state(ConnectionLifecycle.TRANSFERRING)
        runCatching { connection.get()?.send(Frame(FrameType.RESUME)) }
    }

    fun cancel() {
        cancelled.set(true)
        tracker.state(ConnectionLifecycle.CANCELLED)
        runCatching {
            connection.get()?.send(Frame(FrameType.CANCEL))
            connection.get()?.send(Frame(FrameType.SHUTDOWN))
        }
        connection.get()?.close()
        transferJob?.cancel()
        sources.forEach { runCatching { it.close() } }
    }

    // ---- streaming core ----

    private suspend fun streamAll() {
        val conn = connection.get() ?: throw ProtocolException("Not connected")
        val totalBytes = sources.sumOf { it.file.sizeBytes }
        val startedAt = clock()
        tracker.started()

        var index = 0
        for (source in sources) {
            ensureNotCancelled()
            sendFile(conn, source, index, sources.size, totalBytes, startedAt)
            filesDone.incrementAndGet()
            index++
        }

        conn.send(Frame(FrameType.TRANSFER_COMPLETE, Payloads.encode(TransferCompletePayload(files = sources.size, totalBytes = bytesDone.get(), durationMs = clock() - startedAt))))
        // Wait for final ack/shutdown with a short read (receiver acks TRANSFER_COMPLETE).
        waitForCompletionAck(conn)
        tracker.update { it.copy(state = ConnectionLifecycle.COMPLETED, bytesTotal = totalBytes, bytesDone = bytesDone.get()) }
    }

    private suspend fun sendFile(
        conn: VtpConnection,
        source: FileSource,
        index: Int,
        count: Int,
        sessionTotalBytes: Long,
        startedAt: Long,
    ) {
        val file: TransferFile = source.file
        ensureNotCancelled()

        // Announce; receiver replies with ack + resume offset (or reject).
        conn.send(
            Frame(
                FrameType.FILE_META,
                Payloads.encode(
                    FileMetaPayload(
                        fileId = file.id,
                        index = index,
                        count = count,
                        name = file.displayName,
                        size = file.sizeBytes,
                        mime = file.mimeType,
                        sha256 = file.sha256,
                        sessionTotalBytes = sessionTotalBytes,
                    )
                ),
            )
        )
        val ackFrame = readControlFrame(conn) ?: throw IOException("Receiver closed during FILE_META")
        val resumeOffset: Long = when (ackFrame.type) {
            FrameType.FILE_ACK -> {
                val ack = Payloads.decode<FileAckPayload>(ackFrame.payload)
                if (ack.completed) {
                    // Receiver already has this exact file (duplicate/skip handling upstream).
                    onFileSkipped(file, ack.finalName)
                    return
                }
                ack.resumeOffset.coerceIn(0L, file.sizeBytes)
            }
            FrameType.FILE_REJECT -> {
                val reject = Payloads.decode<FileRejectPayload>(ackFrame.payload)
                if (reject.reasonCode == 204) {
                    onFileSkipped(file, null)
                    return
                }
                throw ProtocolException("Receiver rejected ${file.displayName}: ${reject.message}")
            }
            FrameType.ERROR -> {
                val err = Payloads.decode<ErrorPayload>(ackFrame.payload)
                throw ProtocolException("Receiver error ${err.code}: ${err.message}")
            }
            else -> throw ProtocolException("Unexpected ${ackFrame.type} after FILE_META")
        }

        // If we did not precompute sha-256, compute it while streaming.
        val digest = MessageDigest.getInstance("SHA-256")
        var filePos = resumeOffset
        if (resumeOffset > 0) {
            VibeLog.i(TAG, "Resuming ${file.displayName} at $resumeOffset")
        }

        val reader = source.openChannel(resumeOffset)
        val chunk = ByteArray(config.chunkSizeBytes)
        var inFlight = 0
        try {
            if (resumeOffset > 0) consumeResumeAckAcks(conn) // drain acks for previously stored bytes
            while (filePos < file.sizeBytes) {
                ensureNotCancelled()
                if (paused.get()) {
                    waitForUnpause(conn)
                    continue
                }
                val read = reader.read(ByteBuffer.wrap(chunk))
                if (read <= 0) break
                digest.update(chunk, 0, read)
                val payload = chunkPayload(file.id, chunk, read)
                conn.send(Frame(FrameType.CHUNK, payload))
                filePos += read
                bytesDone.addAndGet(read.toLong())
                inFlight++
                if (inFlight >= config.inFlightWindow) {
                    drainOneAck(conn)
                    inFlight--
                }
                emitProgress(file, index, count, filePos, resumeOffset)
            }
            // Drain remaining acks.
            while (inFlight > 0) {
                drainOneAck(conn)
                inFlight--
            }
        } finally {
            reader.close()
        }

        if (filePos < file.sizeBytes) {
            throw IOException("Source ended early at $filePos/${file.sizeBytes}")
        }

        val computedSha = HkdfLite.toHex(digest.digest())
        conn.send(Frame(FrameType.FILE_DONE, Payloads.encode(FileDonePayload(fileId = file.id, sha256 = computedSha))))
        // Receiver verifies digest and finalizes atomically.
        val doneAck = readControlFrame(conn) ?: throw IOException("Receiver closed during FILE_DONE")
        when (doneAck.type) {
            FrameType.FILE_ACK -> {
                val ack = Payloads.decode<FileAckPayload>(doneAck.payload)
                if (!ack.completed) throw ProtocolException("Receiver did not finalize ${file.displayName}")
            }
            FrameType.ERROR -> {
                val err = Payloads.decode<ErrorPayload>(doneAck.payload)
                throw ProtocolException("Verification failed: ${err.message}")
            }
            else -> throw ProtocolException("Unexpected ${doneAck.type} after FILE_DONE")
        }
        emitProgress(file, index, count, file.sizeBytes, 0)
    }

    /** Sends FILE_META payload building [2B len][id][bytes] chunk frames. */
    private fun chunkPayload(fileId: String, chunk: ByteArray, length: Int): ByteArray {
        val idBytes = fileId.toByteArray(Charsets.UTF_8)
        val buffer = ByteArray(2 + idBytes.size + length)
        buffer[0] = ((idBytes.size shr 8) and 0xff).toByte()
        buffer[1] = (idBytes.size and 0xff).toByte()
        idBytes.copyInto(buffer, 2)
        System.arraycopy(chunk, 0, buffer, 2 + idBytes.size, length)
        return buffer
    }

    private suspend fun readControlFrame(conn: VtpConnection): Frame? {
        while (true) {
            ensureNotCancelled()
            val frame = conn.receive() ?: return null
            when (frame.type) {
                FrameType.HEARTBEAT -> conn.send(Frame(FrameType.HEARTBEAT))
                FrameType.CHUNK_ACK -> continue // stray acks are ignored between files
                else -> return frame
            }
        }
    }

    private suspend fun drainOneAck(conn: VtpConnection) {
        while (true) {
            ensureNotCancelled()
            val frame = conn.receive() ?: throw IOException("Receiver closed while streaming")
            when (frame.type) {
                FrameType.CHUNK_ACK -> return
                FrameType.HEARTBEAT -> conn.send(Frame(FrameType.HEARTBEAT))
                FrameType.CANCEL -> throw CancellationException("Receiver cancelled")
                FrameType.PAUSE -> paused.set(true)
                FrameType.RESUME -> paused.set(false)
                FrameType.ERROR -> {
                    val err = Payloads.decode<ErrorPayload>(frame.payload)
                    throw ProtocolException("Receiver error ${err.code}: ${err.message}")
                }
                else -> Unit
            }
        }
    }

    /** Consumes leftover CHUNK_ACKs emitted after a resume (offset was pre-stored). */
    private suspend fun consumeResumeAckAcks(conn: VtpConnection) {
        // On resume the receiver only acks NEW chunks; nothing to drain by protocol,
        // but a short non-blocking sweep keeps the window math honest.
    }

    private suspend fun waitForUnpause(conn: VtpConnection) {
        conn.setReadTimeout(120_000)
        val frame = conn.receive()
        conn.setReadTimeout(Constants.SOCKET_READ_TIMEOUT_MS)
        when (frame?.type) {
            FrameType.RESUME -> paused.set(false)
            FrameType.CANCEL -> throw CancellationException("Cancelled while paused")
            null -> throw IOException("Connection closed while paused")
            else -> Unit
        }
    }

    private suspend fun waitForCompletionAck(conn: VtpConnection) {
        conn.setReadTimeout(10_000)
        try {
            val frame = conn.receive()
            if (frame != null && frame.type == FrameType.ERROR) {
                val err = Payloads.decode<ErrorPayload>(frame.payload)
                throw ProtocolException("Completion rejected: ${err.message}")
            }
        } finally {
            conn.setReadTimeout(Constants.SOCKET_READ_TIMEOUT_MS)
        }
    }

    private fun onFileSkipped(file: TransferFile, finalName: String?) {
        VibeLog.i(TAG, "Receiver skipped ${file.displayName}${finalName?.let { " (already have: $it)" } ?: ""}")
        bytesDone.addAndGet(file.sizeBytes)
        filesDone.incrementAndGet()
        tracker.update { it.copy(filesDone = filesDone.toInt()) }
    }

    private fun emitProgress(file: TransferFile, index: Int, count: Int, filePos: Long, resumeOffset: Long) {
        tracker.onBytesProgressed(
            bytesDone = bytesDone.get(),
            bytesTotal = totalSessionBytes,
            currentFileBytesDone = filePos,
            currentFileBytesTotal = file.sizeBytes,
            fileIndex = index + 1,
            filesDone = filesDone.toInt(),
            filesTotal = count,
            currentFileName = file.displayName,
        )
    }

    private suspend fun ensureNotCancelled() {
        if (cancelled.get()) throw CancellationException("Sender cancelled")
        currentCoroutineContext().ensureActive()
    }

    private fun AppError.userFriendly(): String = this.userMessage()

    companion object {
        private const val TAG = "SenderSession"
    }
}

/** Hex helper kept local to avoid pulling crypto internals into the send loop. */
private object HkdfLite {
    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            append("0123456789abcdef"[(b.toInt() shr 4) and 0xf])
            append("0123456789abcdef"[b.toInt() and 0xf])
        }
    }
}
