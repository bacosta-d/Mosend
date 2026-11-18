package com.filetransfer.coui.model

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class TransferItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val localPath: String? = null,        // 发送端：本地文件路径；接收端：保存路径
    val remotePath: String? = null,       // 对端路径（用于显示）
    val direction: TransferDirection,
    val status: TransferStatus = TransferStatus.PENDING,
    val progress: Float = 0f,             // 0.0 - 1.0
    val bytesTransferred: Long = 0,
    val speedBps: Long = 0,               // bytes per second
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val deviceId: String,                 // 对端设备 ID
    val deviceName: String,               // 对端设备名称
    val errorMessage: String? = null,
    val isText: Boolean = false,          // 是否为文本传输
    val textContent: String? = null,      // 文本内容（当 isText=true 时）
) {
    val isCompleted: Boolean
        get() = status == TransferStatus.COMPLETED || status == TransferStatus.FAILED || status == TransferStatus.CANCELLED

    val isActive: Boolean
        get() = status == TransferStatus.TRANSFERRING || status == TransferStatus.CONNECTING || status == TransferStatus.PENDING

    val formattedSize: String
        get() = formatSize(fileSize)

    val formattedSpeed: String
        get() = formatSpeed(speedBps)

    val formattedProgress: String
        get() = "%.1f%%".format(progress * 100)

    val elapsedTime: Long
        get() = (endTime ?: System.currentTimeMillis()) - startTime

    val eta: Long?
        get() = if (speedBps > 0 && progress > 0 && progress < 1) {
            ((fileSize - bytesTransferred) / speedBps).toLong()
        } else null

    companion object {
        fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
                else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
            }
        }

        fun formatSpeed(bps: Long): String {
            return when {
                bps < 1024 -> "$bps B/s"
                bps < 1024 * 1024 -> String.format("%.1f KB/s", bps / 1024.0)
                bps < 1024 * 1024 * 1024 -> String.format("%.1f MB/s", bps / (1024.0 * 1024))
                else -> String.format("%.1f GB/s", bps / (1024.0 * 1024 * 1024))
            }
        }

        fun formatDuration(millis: Long): String {
            val seconds = millis / 1000
            return when {
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
                else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            }
        }
    }
}

@Serializable
enum class TransferDirection {
    SEND, RECEIVE
}

@Serializable
enum class TransferStatus {
    PENDING,           // 等待开始
    CONNECTING,        // 连接中
    WAITING_ACCEPT,    // 等待对方接受
    TRANSFERRING,      // 传输中
    COMPLETED,         // 完成
    FAILED,            // 失败
    CANCELLED,         // 取消
}

@Serializable
data class TransferRequest(
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val isText: Boolean = false,
    val textContent: String? = null,
    val senderId: String,
    val senderName: String,
)

@Serializable
data class TransferResponse(
    val transferId: String,
    val accepted: Boolean,
    val savePath: String? = null,
    val errorMessage: String? = null,
)

@Serializable
data class TransferProgress(
    val transferId: String,
    val bytesTransferred: Long,
    val speedBps: Long,
)

@Serializable
data class TransferComplete(
    val transferId: String,
    val success: Boolean,
    val errorMessage: String? = null,
)