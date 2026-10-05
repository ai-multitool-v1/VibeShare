package com.setbd.vibeshare.pairing.handshake

import com.setbd.vibeshare.core.Constants
import com.setbd.vibeshare.core.log.VibeLog
import com.setbd.vibeshare.core.error.AppError
import com.setbd.vibeshare.core.result.VibeResult
import com.setbd.vibeshare.domain.model.DeviceInfo
import com.setbd.vibeshare.domain.model.DeviceType
import com.setbd.vibeshare.domain.model.ShareSession
import com.setbd.vibeshare.pairing.session.SessionRegistry
import com.setbd.vibeshare.transfer.connection.VtpConnection
import com.setbd.vibeshare.transfer.crypto.Hkdf
import com.setbd.vibeshare.transfer.crypto.SessionCrypto
import com.setbd.vibeshare.transfer.engine.AuthenticatedPeer
import com.setbd.vibeshare.transfer.engine.ClientHandshake
import com.setbd.vibeshare.transfer.engine.HandshakeResult
import com.setbd.vibeshare.transfer.engine.ServerAuthenticator
import com.setbd.vibeshare.transfer.protocol.Frame
import com.setbd.vibeshare.transfer.protocol.FrameType
import com.setbd.vibeshare.transfer.protocol.HelloPayload
import com.setbd.vibeshare.transfer.protocol.PairInitPayload
import com.setbd.vibeshare.transfer.protocol.PairOkPayload
import com.setbd.vibeshare.transfer.protocol.PairRejectPayload
import com.setbd.vibeshare.transfer.protocol.Payloads
import java.security.KeyPair
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Credential the joining client presents: QR token proof or 6-digit PIN. */
sealed class PairCredential {
    data class Token(val token: String) : PairCredential()
    data class Pin(val pin: String) : PairCredential()
}

internal fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

internal fun unb64(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)

object RejectCodes {
    const val INVALID = 401
    const val DENIED = 403
    const val EXPIRED = 410
}

/**
 * Client side: HELLO -> PAIR_INIT(proof|pin + ECDH pub) -> PAIR_OK -> encrypted.
 */
class VibeClientHandshake(
    private val credential: PairCredential,
    private val ioDispatcher: CoroutineDispatcher,
    private val encryptionEnabled: Boolean,
) : ClientHandshake {

    override suspend fun perform(connection: VtpConnection, self: DeviceInfo): VibeResult<HandshakeResult> =
        withContext(ioDispatcher) {
            try {
                connection.send(
                    Frame(
                        FrameType.HELLO,
                        Payloads.encode(
                            HelloPayload(
                                deviceId = self.deviceId,
                                deviceName = self.name,
                                deviceType = self.type.name,
                                appVersion = self.appVersion,
                                protocolVersion = Constants.PROTOCOL_VERSION,
                            )
                        ),
                    )
                )
                val peerHelloFrame = connection.receive()
                    ?: return@withContext VibeResult.failure(AppError.CouldNotConnect("peer closed during hello"))
                if (peerHelloFrame.type != FrameType.HELLO) {
                    return@withContext VibeResult.failure(AppError.ProtocolMismatch("expected HELLO, got ${peerHelloFrame.type}"))
                }
                val peerHello = Payloads.decode<HelloPayload>(peerHelloFrame.payload)
                if (peerHello.protocolVersion != Constants.PROTOCOL_VERSION) {
                    return@withContext VibeResult.failure(
                        AppError.ProtocolMismatch("peer protocol ${peerHello.protocolVersion}")
                    )
                }

                val clientKeyPair: KeyPair? = if (encryptionEnabled) SessionCrypto.generateEcdhKeyPair() else null
                val clientNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val nonceHex = Hkdf.toHex(clientNonce)

                val ecdhPubB64 = clientKeyPair?.let { b64(SessionCrypto.encodePublicKey(it.public)) }
                val init = when (credential) {
                    is PairCredential.Token -> PairInitPayload(
                        deviceId = self.deviceId,
                        deviceName = self.name,
                        deviceType = self.type.name,
                        appVersion = self.appVersion,
                        credentialKind = "TOKEN",
                        nonce = nonceHex,
                        proof = Hkdf.toHex(Hkdf.hmac(credential.token.toByteArray(Charsets.UTF_8), clientNonce)),
                        ecdhPublicKey = ecdhPubB64,
                    )
                    is PairCredential.Pin -> PairInitPayload(
                        deviceId = self.deviceId,
                        deviceName = self.name,
                        deviceType = self.type.name,
                        appVersion = self.appVersion,
                        credentialKind = "PIN",
                        pin = credential.pin,
                        ecdhPublicKey = ecdhPubB64,
                    )
                }
                connection.send(Frame(FrameType.PAIR_INIT, Payloads.encode(init)))

                val response = connection.receive()
                    ?: return@withContext VibeResult.failure(AppError.CouldNotConnect("peer closed during pairing"))
                when (response.type) {
                    FrameType.PAIR_REJECT -> {
                        val reject = Payloads.decode<PairRejectPayload>(response.payload)
                        return@withContext VibeResult.failure(reject.toAppError())
                    }
                    FrameType.ERROR -> return@withContext VibeResult.failure(AppError.HandshakeFailed)
                    FrameType.PAIR_OK -> Unit
                    else -> return@withContext VibeResult.failure(AppError.ProtocolMismatch("expected PAIR_OK"))
                }

                val ok = Payloads.decode<PairOkPayload>(response.payload)
                var key: javax.crypto.SecretKey? = null
                if (encryptionEnabled && clientKeyPair != null && ok.ecdhPublicKey.isNotBlank()) {
                    val hostPub = SessionCrypto.decodePublicKey(unb64(ok.ecdhPublicKey))
                    val secret = SessionCrypto.ecdh(clientKeyPair.private, hostPub)
                    val transcript = clientNonce + unb64(ok.nonce)
                    val credentialBytes = when (credential) {
                        is PairCredential.Token -> credential.token.toByteArray(Charsets.UTF_8)
                        is PairCredential.Pin -> credential.pin.toByteArray(Charsets.UTF_8)
                    }
                    key = SessionCrypto.deriveSessionKey(secret, credentialBytes, transcript)
                    connection.enableEncryption(key)
                }
                VibeResult.success(
                    HandshakeResult(
                        sessionId = ok.sessionId,
                        peer = DeviceInfo(
                            deviceId = ok.deviceId,
                            name = ok.deviceName,
                            type = runCatching { DeviceType.valueOf(peerHello.deviceType) }.getOrDefault(DeviceType.PHONE),
                            appVersion = peerHello.appVersion,
                            protocolVersion = peerHello.protocolVersion,
                        ),
                        encryptionKey = key,
                    )
                )
            } catch (io: java.io.IOException) {
                VibeResult.failure(AppError.ConnectionLost(io.message ?: "socket error"))
            } catch (bad: Exception) {
                VibeLog.w(TAG, "Handshake failure", bad)
                VibeResult.failure(AppError.HandshakeFailed)
            }
        }

    private fun PairRejectPayload.toAppError(): AppError = when (reasonCode) {
        RejectCodes.EXPIRED -> AppError.PairingCodeExpired
        RejectCodes.INVALID -> AppError.PairingCodeInvalid
        else -> AppError.PairingRejected
    }

    companion object {
        private const val TAG = "ClientHandshake"
    }
}

/**
 * Server side: read HELLO, read PAIR_INIT, validate the credential against the
 * [SessionRegistry] (respecting trusted-device auto-accept + explicit approval),
 * answer PAIR_OK and switch to encrypted transport.
 */
class VibeServerAuthenticator(
    private val registry: SessionRegistry,
    private val self: DeviceInfo,
    private val encryptionEnabled: Boolean,
    private val trustedDeviceIds: Set<String> = emptySet(),
    private val autoAcceptTrusted: Boolean = false,
    private val approvalGate: ApprovalGate = ApprovalGate { _, _ -> true },
    private val ioDispatcher: CoroutineDispatcher,
) : ServerAuthenticator {

    fun interface ApprovalGate {
        /** True when the local user approved the incoming pairing. */
        suspend fun requestApproval(peer: DeviceInfo, session: ShareSession): Boolean
    }

    override suspend fun authenticate(connection: VtpConnection): VibeResult<AuthenticatedPeer> =
        withContext(ioDispatcher) {
            try {
                connection.setReadTimeout(Constants.SOCKET_READ_TIMEOUT_MS)
                val helloFrame = connection.receive()
                    ?: return@withContext VibeResult.failure(AppError.CouldNotConnect("empty connection"))
                if (helloFrame.type != FrameType.HELLO) {
                    return@withContext VibeResult.failure(AppError.ProtocolMismatch("expected HELLO"))
                }
                val hello = Payloads.decode<HelloPayload>(helloFrame.payload)
                if (hello.protocolVersion != Constants.PROTOCOL_VERSION) {
                    connection.send(
                        Frame(
                            FrameType.PAIR_REJECT,
                            Payloads.encode(PairRejectPayload(RejectCodes.INVALID, "protocol mismatch")),
                        )
                    )
                    return@withContext VibeResult.failure(AppError.ProtocolMismatch("client protocol ${hello.protocolVersion}"))
                }
                val peer = DeviceInfo(
                    deviceId = hello.deviceId,
                    name = hello.deviceName,
                    type = runCatching { DeviceType.valueOf(hello.deviceType) }.getOrDefault(DeviceType.PHONE),
                    appVersion = hello.appVersion,
                    protocolVersion = hello.protocolVersion,
                )

                // Bidirectional hello so the client can validate protocol version.
                connection.send(
                    Frame(
                        FrameType.HELLO,
                        Payloads.encode(
                            HelloPayload(
                                deviceId = self.deviceId,
                                deviceName = self.name,
                                deviceType = self.type.name,
                                appVersion = self.appVersion,
                                protocolVersion = Constants.PROTOCOL_VERSION,
                            )
                        ),
                    )
                )

                val initFrame = connection.receive()
                    ?: return@withContext VibeResult.failure(AppError.CouldNotConnect("no pairing request"))
                if (initFrame.type != FrameType.PAIR_INIT) {
                    return@withContext VibeResult.failure(AppError.ProtocolMismatch("expected PAIR_INIT"))
                }
                val init = Payloads.decode<PairInitPayload>(initFrame.payload)

                val session: ShareSession = when (init.credentialKind) {
                    "TOKEN" -> {
                        val nonceHex = init.nonce
                        val proofHex = init.proof
                        if (nonceHex.isNullOrBlank() || proofHex.isNullOrBlank()) {
                            return@withContext reject(connection, RejectCodes.INVALID)
                        }
                        registry.validateTokenProof(nonceHex, proofHex)
                            ?: return@withContext reject(connection, RejectCodes.EXPIRED)
                    }
                    "PIN" -> {
                        val pin = init.pin
                        if (pin == null || !com.setbd.vibeshare.pairing.token.PairingTokens.isPlausiblePin(pin)) {
                            return@withContext reject(connection, RejectCodes.INVALID)
                        }
                        registry.findByPin(pin)
                            ?: return@withContext reject(connection, RejectCodes.EXPIRED)
                    }
                    else -> return@withContext reject(connection, RejectCodes.INVALID)
                }

                // Explicit approval for unknown devices unless trusted auto-accept is on.
                val known = peer.deviceId in trustedDeviceIds
                if (!known || !autoAcceptTrusted) {
                    if (!approvalGate.requestApproval(peer, session)) {
                        return@withContext reject(connection, RejectCodes.DENIED)
                    }
                }

                val hostNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val hostKeyPair: KeyPair? =
                    if (encryptionEnabled && !init.ecdhPublicKey.isNullOrBlank()) SessionCrypto.generateEcdhKeyPair() else null

                var key: javax.crypto.SecretKey? = null
                if (hostKeyPair != null) {
                    val clientPub = SessionCrypto.decodePublicKey(unb64(init.ecdhPublicKey!!))
                    val secret = SessionCrypto.ecdh(hostKeyPair.private, clientPub)
                    // The client nonce travels as hex (see PAIR_INIT); decode accordingly.
                    val clientNonceBytes = com.setbd.vibeshare.transfer.crypto.Hkdf.fromHex(init.nonce ?: "")
                        ?: return@withContext reject(connection, RejectCodes.INVALID)
                    val transcript = clientNonceBytes + hostNonce
                    val credentialBytes = when (init.credentialKind) {
                        "PIN" -> init.pin!!.toByteArray(Charsets.UTF_8)
                        else -> session.token.toByteArray(Charsets.UTF_8)
                    }
                    key = SessionCrypto.deriveSessionKey(secret, credentialBytes, transcript)
                }

                connection.send(
                    Frame(
                        FrameType.PAIR_OK,
                        Payloads.encode(
                            PairOkPayload(
                                sessionId = session.sessionId,
                                deviceName = self.name,
                                deviceId = self.deviceId,
                                nonce = b64(hostNonce),
                                ecdhPublicKey = hostKeyPair?.let { b64(SessionCrypto.encodePublicKey(it.public)) } ?: "",
                            )
                        ),
                    )
                )
                key?.let { connection.enableEncryption(it) }

                VibeResult.success(AuthenticatedPeer(session = session, peer = peer, encryptionKey = key))
            } catch (io: java.io.IOException) {
                VibeResult.failure(AppError.ConnectionLost(io.message ?: "socket error"))
            } catch (bad: Exception) {
                VibeLog.w(TAG, "Server auth failure", bad)
                VibeResult.failure(AppError.HandshakeFailed)
            }
        }

    private suspend fun reject(connection: VtpConnection, code: Int): VibeResult<AuthenticatedPeer> {
        runCatching {
            connection.send(
                Frame(
                    FrameType.PAIR_REJECT,
                    Payloads.encode(
                        PairRejectPayload(
                            code,
                            when (code) {
                                RejectCodes.EXPIRED -> "pairing code expired"
                                RejectCodes.INVALID -> "invalid pairing code"
                                else -> "pairing denied"
                            },
                        )
                    ),
                )
            )
        }
        return VibeResult.failure(
            when (code) {
                RejectCodes.EXPIRED -> AppError.PairingCodeExpired
                RejectCodes.INVALID -> AppError.PairingCodeInvalid
                else -> AppError.PairingRejected
            }
        )
    }

    companion object {
        private const val TAG = "ServerAuth"
    }
}
