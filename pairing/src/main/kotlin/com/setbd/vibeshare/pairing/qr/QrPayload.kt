package com.setbd.vibeshare.pairing.qr

import com.setbd.vibeshare.core.Constants
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Payload encoded into the host QR code. Contains ONLY session-scoped,
 * short-lived data (spec section 6): no permanent secrets, no personal data
 * beyond the chosen device name.
 */
@Serializable
data class QrPayload(
    val v: Int = Constants.PROTOCOL_VERSION,
    val sessionId: String,
    val deviceId: String,
    val deviceName: String,
    val mode: String,
    val host: String? = null,
    val port: Int? = null,
    val token: String,
    val expiresAtMs: Long,
) {
    fun encode(): String = codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(text: String): QrPayload? = runCatching {
            codec.decodeFromString(serializer(), text.trim())
        }.getOrNull()
    }
}
