package com.filetransfer.coui.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class MessageDirection { IN, OUT }

@Serializable
enum class MessageKind { TEXT, FILE }

@Serializable
enum class MessageStatus { PENDING, SENT, DELIVERED, FAILED }

/**
 * 聊天消息。文本与文件都以气泡形式呈现在会话中。
 */
@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val deviceName: String,
    val direction: MessageDirection,
    val kind: MessageKind,
    val text: String? = null,
    val fileName: String? = null,
    val fileSize: Long = 0L,
    val mimeType: String? = null,
    val localPath: String? = null,
    /** 关联的 TransferItem.id，仅文件消息使用 */
    val transferId: String? = null,
    val status: MessageStatus = MessageStatus.SENT,
    val timestamp: Long = System.currentTimeMillis(),
)
