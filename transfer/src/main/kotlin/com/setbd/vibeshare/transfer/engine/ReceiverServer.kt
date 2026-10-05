package com.setbd.vibeshare.transfer.engine

import com.setbd.vibeshare.core.coroutines.DispatcherProvider
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.core.error.AppError
import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.transfer.connection.VtpConnection
import com.setbd.vibeshare.transfer.model.IncomingStorage
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Accepts incoming VTP connections and spawns a [ReceiverSession] per peer.
 * The port is fixed (0 = OS-assigned, reported back to advertise in QR/NSD).
 */
class ReceiverServer(
    private val authenticator: ServerAuthenticator,
    private val storage: IncomingStorage,
    private val listener: ReceiverListener,
    private val dispatchers: DispatcherProvider,
    private val readTimeoutMs: Int,
    private val scope: CoroutineScope,
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val sessions = mutableListOf<ReceiverSession>()
    private var acceptJob: Job? = null

    val boundPort: Int get() = serverSocket?.localPort ?: 0

    /** Binds and starts accepting. Returns the bound port. */
    suspend fun start(port: Int = 0): VibeResult<Int> {
        if (running.get()) return VibeResult.success(boundPort)
        return withIo {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            serverSocket = socket
            running.set(true)
            VibeLog.i(TAG, "Receiver server listening on port ${socket.localPort}")
            acceptJob = scope.launch(dispatchers.io) { acceptLoop(socket) }
            VibeResult.success(socket.localPort)
        }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                val connection = VtpConnection.accepted(client)
                scope.launch(dispatchers.io) { handle(connection) }
            } catch (io: Exception) {
                if (running.get()) {
                    VibeLog.w(TAG, "accept() failed: ${io.message}")
                }
                break
            }
        }
    }

    private suspend fun handle(connection: VtpConnection) {
        val auth = authenticator.authenticate(connection)
        when (auth) {
            is VibeResult.Failure -> {
                VibeLog.i(TAG, "Rejected incoming connection: ${auth.error.debugDetail}")
                connection.close()
            }
            is VibeResult.Success -> {
                val session = ReceiverSession(
                    connection = connection,
                    peer = auth.data,
                    storage = storage,
                    listener = listener,
                    readTimeoutMs = readTimeoutMs,
                    ioDispatcher = dispatchers.io,
                )
                synchronized(sessions) { sessions.add(session) }
                session.run()
                synchronized(sessions) { sessions.remove(session) }
            }
        }
    }

    /** Cancels every active receiver session and stops accepting. */
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        acceptJob?.cancel()
        synchronized(sessions) { sessions.forEach { it.cancel() } }
    }

    private suspend fun <T> withIo(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(dispatchers.io) { block() }

    companion object {
        private const val TAG = "ReceiverServer"
    }
}

/** Convenience error for bind failures surfaced to callers. */
fun bindError(detail: String): AppError = AppError.CouldNotConnect(detail)
