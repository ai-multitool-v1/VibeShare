package com.setbd.vibeshare.core.error

/**
 * Central error taxonomy. Every error is mapped to a short, human-friendly message
 * (spec section 32: never show raw stack traces to users).
 */
sealed class AppError(open val debugDetail: String? = null) {

    // ---- Connection / transport ----
    data class WifiDisabled(val detail: String = "") : AppError(detail)
    data class PermissionMissing(val permission: String) : AppError(permission)
    data class ModeUnsupported(val mode: String) : AppError(mode)
    data class CouldNotConnect(val detail: String = "") : AppError(detail)
    data class ConnectionLost(val detail: String = "") : AppError(detail)
    data class ConnectionTimeout(val detail: String = "") : AppError(detail)
    data class PeerDisconnected(val detail: String = "") : AppError(detail)
    data class NetworkUnavailable(val detail: String = "") : AppError(detail)

    // ---- Pairing / session ----
    data object PairingCodeExpired : AppError("pairing token expired")
    data object PairingCodeInvalid : AppError("pairing token invalid")
    data object PairingRejected : AppError("receiver rejected pairing")
    data object SessionExpired : AppError("session expired")
    data class ProtocolMismatch(val detail: String = "") : AppError(detail)
    data object HandshakeFailed : AppError("handshake failed")

    // ---- Transfer ----
    data object TransferCancelled : AppError("transfer cancelled by user")
    data class TransferFailed(val detail: String = "") : AppError(detail)
    data object TransferInterrupted : AppError("transfer interrupted")
    data object ChecksumMismatch : AppError("sha-256 verification failed")
    data class ResumeUnsupported(val detail: String = "") : AppError(detail)
    data class SourceNotReadable(val detail: String = "") : AppError(detail)

    // ---- Storage ----
    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) :
        AppError("required=$requiredBytes available=$availableBytes")

    data object StorageUnavailable : AppError("storage unavailable")
    data class WriteFailed(val detail: String = "") : AppError(detail)

    // ---- APK / apps ----
    data object ApkExtractionUnavailable : AppError("apk extraction unavailable (split app or restricted)")
    data class ApkNotFound(val detail: String = "") : AppError(detail)

    // ---- Updater ----
    data class UpdateCheckFailed(val detail: String = "") : AppError(detail)
    data object UpdateChecksumMismatch : AppError("update apk sha-256 mismatch")
    data class UpdateDownloadFailed(val detail: String = "") : AppError(detail)
    data object AlreadyUpToDate : AppError("already up to date")

    // ---- Discovery ----
    data class DiscoveryFailed(val detail: String = "") : AppError(detail)

    // ---- Catch-all ----
    data class Unknown(val cause: Throwable) : AppError(cause.message)
}
