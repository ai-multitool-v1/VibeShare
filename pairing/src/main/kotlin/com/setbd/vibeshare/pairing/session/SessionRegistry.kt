package com.setbd.vibeshare.pairing.session

import com.setbd.vibeshare.core.Constants
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.domain.model.ShareSession
import com.setbd.vibeshare.domain.model.TransferMode
import com.setbd.vibeshare.pairing.token.PairingTokens
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory registry of active host sessions. Sessions live only as long as
 * their TTL, are purged eagerly, and never touch disk: restarting the app
 * invalidates every outstanding token and PIN (fail-safe default).
 */
class SessionRegistry(
    private val clock: () -> Long = System::currentTimeMillis,
    private val tokenTtlMs: Long = Constants.TOKEN_TTL_MS,
) {
    private val sessions = ConcurrentHashMap<String, ShareSession>()

    fun createSession(
        mode: TransferMode,
        host: String?,
        port: Int,
        ttlMs: Long = tokenTtlMs,
        deviceId: String,
        deviceName: String,
    ): ShareSession {
        purgeExpired()
        val session = ShareSession(
            sessionId = newSessionId(),
            token = PairingTokens.generateToken(),
            pin = PairingTokens.generatePin(),
            mode = mode,
            host = host,
            port = port,
            createdAtMs = clock(),
            expiresAtMs = clock() + ttlMs,
        )
        sessions[session.sessionId] = session
        VibeLog.i(TAG, "Created session ${session.sessionId} mode=$mode ttl=${ttlMs / 1000}s")
        return session
    }

    fun findBySessionId(sessionId: String): ShareSession? {
        purgeExpired()
        return sessions[sessionId]?.takeIf { it.expiresAtMs > clock() }
    }

    /** Exact-token lookup used when the QR embeds the session id. */
    fun findByToken(token: String): ShareSession? {
        purgeExpired()
        return sessions.values.firstOrNull { it.token == token && it.expiresAtMs > clock() }
    }

    fun findByPin(pin: String): ShareSession? {
        purgeExpired()
        return sessions.values.firstOrNull { it.pin == pin && it.expiresAtMs > clock() }
    }

    /** Constant-ish proof verification against every live token (registry is small). */
    fun validateTokenProof(nonceHex: String, proofHex: String): ShareSession? {
        purgeExpired()
        val nonce = hexToBytes(nonceHex) ?: return null
        return sessions.values.firstOrNull { session ->
            session.expiresAtMs > clock() && com.setbd.vibeshare.transfer.crypto.Hkdf
                .hmac(session.token.toByteArray(Charsets.UTF_8), nonce)
                .contentEquals(hexToBytes(proofHex))
        }
    }

    fun revoke(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun revokeAll() {
        sessions.clear()
    }

    fun activeSessions(): List<ShareSession> {
        purgeExpired()
        return sessions.values.toList()
    }

    fun purgeExpired() {
        val now = clock()
        sessions.values.removeIf { it.expiresAtMs <= now }
    }

    private fun newSessionId(): String = "s-" + PairingTokens.generateToken().take(10).lowercase()

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0 || hex.isEmpty()) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    companion object {
        private const val TAG = "SessionRegistry"
    }
}
