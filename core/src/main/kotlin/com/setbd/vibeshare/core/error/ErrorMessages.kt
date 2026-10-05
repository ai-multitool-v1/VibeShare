package com.setbd.vibeshare.core.error

import com.setbd.vibeshare.core.error.AppError

/**
 * Maps internal errors to short, user-friendly sentences (spec section 32).
 * These strings are UI-agnostic; screens render them as-is or override with
 * more contextual copy when needed.
 */
fun AppError.userMessage(): String = when (this) {
    is AppError.WifiDisabled ->
        "Wi-Fi is turned off. Enable Wi-Fi and try again."
    is AppError.PermissionMissing ->
        "VibeShare needs \"$permission\" to find and connect to nearby devices."
    is AppError.ModeUnsupported ->
        "This device does not support the selected transfer mode ($mode)."
    is AppError.CouldNotConnect ->
        "Could not connect to the device."
    is AppError.ConnectionLost ->
        "Connection lost. Retry?"
    is AppError.ConnectionTimeout ->
        "The connection timed out. Make sure both devices stay awake."
    is AppError.PeerDisconnected ->
        "Receiver disconnected. Retry?"
    is AppError.NetworkUnavailable ->
        "No local network available. Connect both devices to the same Wi-Fi or use Wi-Fi Direct."
    is AppError.PairingCodeExpired ->
        "Pairing code expired. Generate a new one."
    is AppError.PairingCodeInvalid ->
        "That pairing code is not valid for this session."
    is AppError.PairingRejected ->
        "The other device declined the pairing request."
    is AppError.SessionExpired ->
        "Session expired. Start a new share."
    is AppError.ProtocolMismatch ->
        "The other device is running an incompatible VibeShare version."
    is AppError.HandshakeFailed ->
        "Secure handshake failed. Start a new pairing."
    is AppError.TransferCancelled ->
        "Transfer cancelled."
    is AppError.TransferFailed ->
        "Transfer failed. $debugDetail"
    is AppError.TransferInterrupted ->
        "Transfer was interrupted. You can resume it."
    is AppError.ChecksumMismatch ->
        "The received file was corrupted during transfer and was discarded."
    is AppError.ResumeUnsupported ->
        "Resume is not possible for this file. Restarting from the beginning."
    is AppError.SourceNotReadable ->
        "VibeShare cannot read this file. Re-select it and try again."
    is AppError.InsufficientStorage ->
        "Not enough storage. Required: ${formatBytes(requiredBytes)} · Available: ${formatBytes(availableBytes)}"
    is AppError.StorageUnavailable ->
        "Storage is unavailable. Check your storage settings."
    is AppError.WriteFailed ->
        "Could not write the file to storage."
    is AppError.ApkExtractionUnavailable ->
        "APK extraction is unavailable for this application."
    is AppError.ApkNotFound ->
        "This app's installation files cannot be accessed."
    is AppError.UpdateCheckFailed ->
        "Update check failed. Check your connection or endpoint."
    is AppError.UpdateChecksumMismatch ->
        "The downloaded update failed integrity verification and was discarded."
    is AppError.UpdateDownloadFailed ->
        "Update download failed."
    is AppError.AlreadyUpToDate ->
        "You are on the latest version."
    is AppError.DiscoveryFailed ->
        "Discovery failed. Toggle Wi-Fi and try again."
    is AppError.Unknown ->
        "Something went wrong. Please try again."
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = "B"
    for (u in units) {
        if (value < 1024) break
        value /= 1024.0
        unit = u
    }
    return "%.1f %s".format(value, unit)
}
