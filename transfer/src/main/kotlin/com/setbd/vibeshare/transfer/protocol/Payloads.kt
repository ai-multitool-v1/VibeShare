package com.setbd.vibeshare.transfer.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HelloPayload(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val appVersion: String,
    val protocolVersion: Int,
)

@Serializable
data class PairInitPayload(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String,
    val appVersion: String,
    /** "TOKEN" (QR) or "PIN" pairing. */
    val credentialKind: String,
    /** Raw credential for PIN pairing (6 digits). Null for token proof. */
    val pin: String? = null,
    /** Client nonce for token-proof pairing. */
    val nonce: String? = null,
    /** HMAC-SHA256(token, nonce) as hex for token pairing. */
    val proof: String? = null,
    /** Base64 X.509 ECDH P-256 public key of the client. */
    val ecdhPublicKey: String? = null,
)

@Serializable
data class PairOkPayload(
    val sessionId: String,
    val deviceName: String,
    val deviceId: String,
    /** Server nonce, binds the handshake transcript. */
    val nonce: String,
    /** Base64 X.509 ECDH P-256 public key of the host. */
    val ecdhPublicKey: String,
)

@Serializable
data class PairRejectPayload(
    val reasonCode: Int,
    val message: String,
)

@Serializable
data class FileMetaPayload(
    val fileId: String,
    val index: Int,
    val count: Int,
    val name: String,
    val size: Long,
    val mime: String,
    val sha256: String? = null,
    /** Total session size for storage pre-check. */
    val sessionTotalBytes: Long,
)

@Serializable
data class FileAckPayload(
    val fileId: String,
    /** Bytes already stored from a previous interrupted attempt (resume offset). */
    val resumeOffset: Long = 0L,
    val completed: Boolean = false,
    val finalName: String? = null,
)

@Serializable
data class FileRejectPayload(
    val fileId: String,
    val reasonCode: Int,
    val message: String,
)

@Serializable
data class ChunkAckPayload(
    val fileId: String,
    val bytesReceived: Long,
)

@Serializable
data class FileDonePayload(
    val fileId: String,
    val sha256: String,
)

@Serializable
data class TransferCompletePayload(
    val files: Int,
    val totalBytes: Long,
    val durationMs: Long,
)

@Serializable
data class ErrorPayload(
    val code: Int,
    val message: String,
)

/** JSON codec for all structured payloads. Chunk payloads are raw bytes for speed. */
object Payloads {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    inline fun <reified T> encode(value: T): ByteArray =
        json.encodeToString(kotlinx.serialization.serializer<T>(), value).toByteArray(Charsets.UTF_8)

    inline fun <reified T> decode(bytes: ByteArray): T =
        json.decodeFromString(kotlinx.serialization.serializer<T>(), bytes.toString(Charsets.UTF_8))
}
