package com.setbd.vibeshare.transfer.connection

import com.setbd.vibeshare.core.Constants
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.transfer.crypto.SessionCrypto
import com.setbd.vibeshare.transfer.protocol.Frame
import com.setbd.vibeshare.transfer.protocol.FrameCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.SecretKey

/**
 * A VTP connection over TCP. Handles framing, optional AES-GCM encryption,
 * and single-writer send serialization. Not thread-safe for concurrent receives:
 * the owner runs one read loop per connection.
 */
class VtpConnection private constructor(
    private val socket: Socket,
    private val maxPayloadBytes: Int,
) {
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
    private val sendLock = Any()

    @Volatile
    private var sessionKey: SecretKey? = null

    val isOpen: Boolean get() = !socket.isClosed && socket.isConnected

    fun remoteAddress(): String = try {
        socket.inetAddress?.hostAddress ?: "unknown"
    } catch (t: Throwable) {
        "unknown"
    }

    fun enableEncryption(key: SecretKey) {
        sessionKey = key
    }

    fun encryptionEnabled(): Boolean = sessionKey != null

    /**
     * Sends one frame. Once encryption is enabled, every non-empty payload is
     * AES-GCM encrypted; empty-payload control frames are sent in clear (they
     * reveal no secrets and keep heartbeat/pause handling simple).
     */
    fun send(frame: Frame) {
        val key = sessionKey
        synchronized(sendLock) {
            if (key != null && frame.payload.isNotEmpty()) {
                val header = buildHeader(frame.type.id, encrypted = true)
                val encrypted = SessionCrypto.encrypt(key, frame.payload, header)
                val encFrame = Frame(frame.type, encrypted).also { it.encrypted = true }
                FrameCodec.write(output, encFrame)
            } else {
                frame.encrypted = key != null
                FrameCodec.write(output, frame)
            }
        }
    }

    /** Blocking receive. Returns null on clean connection close. */
    fun receive(): Frame? {
        val frame = FrameCodec.read(input, maxPayloadBytes) ?: return null
        val key = sessionKey
        if (frame.encrypted) {
            if (key == null) {
                VibeLog.w(TAG, "Received encrypted frame without session key; dropping")
                throw com.setbd.vibeshare.transfer.protocol.ProtocolException("Unexpected encrypted frame")
            }
            val header = buildHeader(frame.type.id, encrypted = true)
            frame.payload = SessionCrypto.decrypt(key, frame.payload, header)
        }
        return frame
    }

    fun setReadTimeout(ms: Int) {
        runCatching { socket.soTimeout = ms }
    }

    fun close() {
        runCatching { socket.close() }
    }

    private fun buildHeader(typeId: Int, encrypted: Boolean): ByteArray {
        val header = ByteArray(Frame.HEADER_SIZE)
        Frame.MAGIC.copyInto(header)
        header[4] = Frame.VERSION.toByte()
        header[5] = typeId.toByte()
        header[6] = if (encrypted) Frame.FLAG_ENCRYPTED.toByte() else 0
        return header
    }

    companion object {
        private const val TAG = "VtpConnection"

        /** Opens a client connection with the configured connect timeout. */
        fun connect(host: String, port: Int, timeoutMs: Int = Constants.CONNECT_TIMEOUT_MS): VtpConnection {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            return VtpConnection(socket, Constants.MAX_FRAME_PAYLOAD_BYTES)
        }

        /** Wraps an accepted server-side socket. */
        fun accepted(socket: Socket): VtpConnection {
            socket.tcpNoDelay = true
            return VtpConnection(socket, Constants.MAX_FRAME_PAYLOAD_BYTES)
        }
    }
}

