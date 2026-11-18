package com.filetransfer.coui.model

import kotlinx.serialization.Serializable

@Serializable
data class Device(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val deviceType: DeviceType = DeviceType.PHONE,
    val capabilities: List<Capability> = emptyList(),
    val lastSeen: Long = System.currentTimeMillis(),
    val isKnown: Boolean = false,
    val avatarHash: String? = null,
) {
    val url: String
        get() = "http://$address:$port"

    val isOnline: Boolean
        get() = (System.currentTimeMillis() - lastSeen) < 30_000 // 30秒内活跃视为在线

    fun withUpdatedSeen(): Device = copy(lastSeen = System.currentTimeMillis())
}

@Serializable
enum class DeviceType {
    PHONE, TABLET, DESKTOP, LAPTOP, UNKNOWN
}

@Serializable
enum class Capability {
    FILE_TRANSFER, TEXT_TRANSFER, CLIPBOARD_SYNC, SCREEN_MIRROR
}

@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val deviceType: DeviceType,
    val capabilities: List<Capability>,
    val version: String,
    val avatarHash: String? = null,
)