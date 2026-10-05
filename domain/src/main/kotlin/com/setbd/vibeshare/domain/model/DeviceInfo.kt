package com.setbd.vibeshare.domain.model

/** Identity of a VibeShare device on the wire and in the UI. */
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val type: DeviceType = DeviceType.PHONE,
    val appVersion: String = "",
    val protocolVersion: Int = 0,
)

enum class DeviceType { PHONE, TABLET, DESKTOP, OTHER }

fun DeviceType.friendlyName(): String = when (this) {
    DeviceType.PHONE -> "Phone"
    DeviceType.TABLET -> "Tablet"
    DeviceType.DESKTOP -> "Desktop"
    DeviceType.OTHER -> "Device"
}
