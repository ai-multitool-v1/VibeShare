package com.setbd.vibeshare.transfer.engine

import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.DeviceInfo
import com.setbd.vibeshare.domain.model.ShareSession
import com.setbd.vibeshare.transfer.connection.VtpConnection
import javax.crypto.SecretKey

/** Result of a successful pairing handshake. */
data class HandshakeResult(
    val sessionId: String,
    val peer: DeviceInfo,
    val encryptionKey: SecretKey?,
)

/**
 * Client-side pairing handshake (run by the sender on a fresh connection).
 * Implemented in :pairing with token-proof / PIN credentials.
 */
interface ClientHandshake {
    suspend fun perform(connection: VtpConnection, self: DeviceInfo): VibeResult<HandshakeResult>
}

/** Peer authenticated on the receiver side, with optional session key. */
data class AuthenticatedPeer(
    val session: ShareSession,
    val peer: DeviceInfo,
    val encryptionKey: SecretKey?,
)

/**
 * Server-side pairing validation (run by the receiver for each incoming
 * connection). Implementation enforces approval, trusted-device auto-accept,
 * token expiry and one-use semantics (spec section 6/13).
 */
interface ServerAuthenticator {
    suspend fun authenticate(connection: VtpConnection): VibeResult<AuthenticatedPeer>
}
