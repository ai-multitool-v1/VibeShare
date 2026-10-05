package com.setbd.vibeshare.transfer.engine

import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.core.error.AppError
import com.setbd.vibeshare.core.error.userMessage
import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.ConnectionLifecycle
import com.setbd.vibeshare.domain.model.DuplicatePolicy
import com.setbd.vibeshare.domain.model.IncomingProgress
import com.setbd.vibeshare.domain.model.TransferFileState
import com.setbd.vibeshare.transfer.connection.VtpConnection
import com.setbd.vibeshare.transfer.model.IncomingFileMeta
import com.setbd.vibeshare.transfer.model.IncomingStorage
import com.setbd.vibeshare.transfer.protocol.ChunkAckPayload
import com.setbd.vibeshare.transfer.protocol.ErrorPayload
import com.setbd.vibeshare.transfer.protocol.FileAckPayload
import com.setbd.vibeshare.transfer.protocol.FileDonePayload
import com.setbd.vibeshare.transfer.protocol.FileMetaPayload
import com.setbd.vibeshare.transfer.protocol.FileRejectPayload
import com.setbd.vibeshare.transfer.protocol.Frame
import com.setbd.vibeshare.transfer.protocol.FrameType
import com.setbd.vibeshare.transfer.protocol.PairRejectPayload
import com.setbd.vibeshare.transfer.protocol.Payloads
import com.setbd.vibeshare.transfer.protocol.ProtocolException
import com.setbd.vibeshare.transfer.protocol.TransferCompletePayload
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Listener surface for receiver-side session lifecycle. */
interface ReceiverListener {
    fun onSessionStarted(peerName: String)
    fun onProgress(progress: IncomingProgress)
    fun onFileWaitingApproval(meta: IncomingFileMeta): DuplicatePolicy
    fun onSessionCompleted(files: Int, totalBytes: Long, durationMs: Long)
    fun onSessionFailed(error: String?)
}

/**
 * Receiver-side session handler. One instance per accepted connection.
 * Runs a blocking frame loop on the IO dispatcher; storage work is suspend.
 */
class ReceiverSession(
    private val connection: VtpConnection,
    private val peer: AuthenticatedPeer,
    private val storage: IncomingStorage,
    private val listener: ReceiverListener,
    private val readTimeoutMs: Int,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val running = AtomicBoolean(true)
    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    private val filesTotal = AtomicLong(0)
    private val filesCommitted = AtomicLong(0)
    private val sessionBytes = AtomicLong(0)
    private val sessionBytesTotal = AtomicLong(0)
    private var currentFileName: String = ""

    private var currentSink: com.setbd.vibeshare.transfer.model.IncomingFileSink? = null
    private var currentMeta: IncomingFileMeta? = null
    private var digest: MessageDigest? = null
    private val sessionFileBytes = AtomicLong(0)

    private val tracker = Speedometer(clock)

    suspend fun run(): VibeResult<Unit> = withContext(ioDispatcher) {
        val startedAt = clock()
        listener.onSessionStarted(peer.peer.name)
        try {
            while (running.get()) {
                connection.setReadTimeout(if (paused.get()) 120_000 else readTimeoutMs)
                val frame = connection.receive() ?: break
                when (frame.type) {
                    FrameType.FILE_META -> handleMeta(frame)
                    FrameType.CHUNK -> handleChunk(frame)
                    FrameType.FILE_DONE -> handleFileDone(frame)
                    FrameType.PAUSE -> paused.set(true)
                    FrameType.RESUME -> paused.set(false)
                    FrameType.CANCEL -> {
                        cancelled.set(true)
                        stopSession(ConnectionLifecycle.CANCELLED, null)
                        return@withContext VibeResult.failure(AppError.TransferCancelled)
                    }
                    FrameType.HEARTBEAT -> connection.send(Frame(FrameType.HEARTBEAT))
                    FrameType.SHUTDOWN -> break
                    FrameType.TRANSFER_COMPLETE -> {
                        val payload = Payloads.decode<TransferCompletePayload>(frame.payload)
                        finishSession(payload.durationMs)
                        // Echo so the sender does not wait for a socket timeout.
                        connection.send(Frame(FrameType.TRANSFER_COMPLETE, frame.payload))
                        return@withContext VibeResult.success(Unit)
                    }
                    FrameType.ERROR -> {
                        val payload = Payloads.decode<ErrorPayload>(frame.payload)
                        throw ProtocolException("Peer error ${payload.code}: ${payload.message}")
                    }
                    else -> Unit // ignore unknown/irrelevant frames
                }
            }
            if (cancelled.get()) {
                VibeResult.failure(AppError.TransferCancelled)
            } else {
                finishSession(clock() - startedAt)
                VibeResult.success(Unit)
            }
        } catch (io: IOException) {
            if (cancelled.get()) {
                VibeResult.failure(AppError.TransferCancelled)
            } else {
                failSession(AppError.ConnectionLost(io.message ?: "socket error"))
                VibeResult.failure(AppError.ConnectionLost(io.message ?: "socket error"))
            }
        } catch (proto: ProtocolException) {
            failSession(AppError.ProtocolMismatch(proto.message ?: "protocol error"))
            VibeResult.failure(AppError.ProtocolMismatch(proto.message ?: "protocol error"))
        } catch (tampered: javax.crypto.AEADBadTagException) {
            failSession(AppError.ChecksumMismatch)
            VibeResult.failure(AppError.ChecksumMismatch)
        } finally {
            connection.close()
        }
    }

    private suspend fun handleMeta(frame: Frame) {
        val metaPayload = Payloads.decode<FileMetaPayload>(frame.payload)
        val meta = IncomingFileMeta(
            fileId = metaPayload.fileId,
            index = metaPayload.index,
            count = metaPayload.count,
            name = metaPayload.name,
            size = metaPayload.size,
            mime = metaPayload.mime,
            declaredSha256 = metaPayload.sha256,
            sessionTotalBytes = metaPayload.sessionTotalBytes,
        )
        if (meta.index == 0) {
            storage.checkSessionCapacity(meta.sessionTotalBytes)?.let { error ->
                connection.send(Frame(FrameType.ERROR, Payloads.encode(ErrorPayload(code = 402, message = error.userFriendly()))))
                failSession(error)
                throw ProtocolException("Session rejected: ${error.userFriendly()}")
            }
            filesTotal.set(meta.count.toLong())
            sessionBytesTotal.set(meta.sessionTotalBytes)
        }

        val policy = listener.onFileWaitingApproval(meta)
        val sink = storage.openFile(meta, policy)
        if (sink == null) {
            // Duplicate-policy skip.
            connection.send(Frame(FrameType.FILE_REJECT, Payloads.encode(FileRejectPayload(meta.fileId, 204, "skipped"))))
            sessionBytes.addAndGet(meta.size) // counts toward totals so progress stays sane
            filesCommitted.incrementAndGet()
            return
        }
        currentSink = sink
        currentMeta = meta
        digest = MessageDigest.getInstance("SHA-256")
        currentFileName = meta.name
        sessionFileBytes.set(0)
        connection.send(
            Frame(FrameType.FILE_ACK, Payloads.encode(FileAckPayload(fileId = meta.fileId, resumeOffset = sink.currentOffset())))
        )
    }

    private fun handleChunk(frame: Frame) {
        val sink = currentSink ?: throw ProtocolException("CHUNK before FILE_META")
        val meta = currentMeta ?: throw ProtocolException("CHUNK before FILE_META")
        val payload = frame.payload
        if (payload.size < 3) throw ProtocolException("Chunk payload too small")
        val idLen = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
        if (payload.size < 2 + idLen) throw ProtocolException("Chunk id truncated")
        val chunkId = String(payload, 2, idLen, Charsets.UTF_8)
        if (chunkId != meta.fileId) throw ProtocolException("Chunk for unexpected file $chunkId")
        val dataStart = 2 + idLen
        val length = payload.size - dataStart
        val expected = (meta.size - sink.currentOffset()).coerceAtLeast(0)
        if (length <= 0 || length > expected) {
            throw ProtocolException("Chunk length $length outside expectations (offset ${sink.currentOffset()}, size ${meta.size})")
        }
        digest?.update(payload, dataStart, length)
        sink.write(payload, dataStart, length)
        sessionBytes.addAndGet(length.toLong())
        sessionFileBytes.addAndGet(length.toLong())
        connection.send(
            Frame(FrameType.CHUNK_ACK, Payloads.encode(ChunkAckPayload(meta.fileId, sink.currentOffset())))
        )
        emitProgress()
    }

    private suspend fun handleFileDone(frame: Frame) {
        val sink = currentSink ?: throw ProtocolException("FILE_DONE without open file")
        val meta = currentMeta ?: throw ProtocolException("FILE_DONE without meta")
        val done = Payloads.decode<FileDonePayload>(frame.payload)
        val computed = com.setbd.vibeshare.transfer.crypto.Hkdf.toHex(digest!!.digest())
        when (val result = sink.commit(done.sha256, computed)) {
            is VibeResult.Success -> {
                connection.send(
                    Frame(FrameType.FILE_ACK, Payloads.encode(FileAckPayload(fileId = meta.fileId, completed = true, finalName = result.data)))
                )
                filesCommitted.incrementAndGet()
                currentSink = null
                currentMeta = null
                digest = null
            }
            is VibeResult.Failure -> {
                connection.send(
                    Frame(FrameType.ERROR, Payloads.encode(ErrorPayload(code = 422, message = result.error.userFriendly())))
                )
                sink.abort()
                throw ProtocolException("Commit failed: ${result.error.userFriendly()}")
            }
        }
    }

    private fun emitProgress() {
        val fileTotal = currentMeta?.size ?: 0L
        tracker.observe(sessionBytes.get())
        listener.onProgress(
            IncomingProgress(
                sessionId = peer.session.sessionId,
                peerName = peer.peer.name,
                state = if (paused.get()) ConnectionLifecycle.PAUSED else ConnectionLifecycle.TRANSFERRING,
                fileIndex = (currentMeta?.index ?: 0) + 1,
                filesTotal = filesTotal.toInt().coerceAtLeast(1),
                currentFileName = currentFileName,
                currentFileBytesDone = sessionFileBytes.get(),
                currentFileBytesTotal = fileTotal,
                bytesDone = sessionBytes.get(),
                bytesTotal = sessionBytesTotal.get(),
                speedBps = tracker.speedBps(),
                etaSeconds = tracker.etaSeconds(sessionBytes.get(), sessionBytesTotal.get()),
            )
        )
    }

    private fun finishSession(durationMs: Long) {
        storage.sessionCompleted(filesCommitted.toInt(), sessionBytes.get(), durationMs)
        listener.onSessionCompleted(filesCommitted.toInt(), sessionBytes.get(), durationMs)
    }

    private fun stopSession(state: ConnectionLifecycle, error: String?) {
        running.set(false)
        VibeLog.e("ReceiverSession", "Session stop: state=$state error=$error")
        currentSink?.abort()
        storage.sessionFailed(error)
        listener.onProgress(
            IncomingProgress(
                sessionId = peer.session.sessionId,
                peerName = peer.peer.name,
                state = state,
                error = error,
                filesTotal = filesTotal.toInt().coerceAtLeast(1),
                bytesTotal = sessionBytesTotal.get(),
                bytesDone = sessionBytes.get(),
                currentFileName = currentFileName,
            )
        )
        listener.onSessionFailed(error)
    }

    private fun failSession(error: AppError) {
        stopSession(ConnectionLifecycle.FAILED, error.userFriendly())
    }

    private fun AppError.userFriendly(): String = this.userMessage()

    fun cancel() {
        cancelled.set(true)
        running.set(false)
        connection.close()
    }

    companion object {
        private const val TAG = "ReceiverSession"
    }
}

/** Lightweight speed sampler shared by receiver-side progress reporting. */
class Speedometer(private val clock: () -> Long) {
    private var lastMs = clock()
    private var lastBytes = 0L
    private var ema = 0.0

    fun observe(bytesDone: Long) {
        val now = clock()
        val dt = now - lastMs
        if (dt >= 400) {
            val inst = (bytesDone - lastBytes) * 1000.0 / dt.coerceAtLeast(1)
            ema = if (ema == 0.0) inst else ema * 0.65 + inst * 0.35
            lastMs = now
            lastBytes = bytesDone
        }
    }

    fun speedBps(): Long = ema.toLong()

    fun etaSeconds(done: Long, total: Long): Long {
        val remaining = (total - done).coerceAtLeast(0)
        return if (ema > 1.0) (remaining / ema).toLong() else -1L
    }
}
