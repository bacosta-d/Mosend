package com.filetransfer.coui.network

import com.filetransfer.coui.model.DeviceInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 局域网协议消息。所有消息通过 kotlinx.serialization 以 JSON 传输。
 *
 * - 发现层：UDP 广播 [Discover] / [DiscoverResponse]
 * - 传输层：TCP 长连接，依次发送 [TransferRequest] → [TransferAck] → 若干 [FileChunk] → [TransferFinished]
 * - 文本消息：直接使用 [TextMessage]
 */
@Serializable
sealed class NetworkMessage {

    @Serializable
    @SerialName("discover")
    data class Discover(val info: DeviceInfo) : NetworkMessage()

    @Serializable
    @SerialName("discover_response")
    data class DiscoverResponse(val info: DeviceInfo, val port: Int) : NetworkMessage()

    /** 发送方发起一次传输请求 */
    @Serializable
    @SerialName("transfer_request")
    data class TransferRequest(
        val transferId: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val sender: DeviceInfo,
        val isText: Boolean = false,
    ) : NetworkMessage()

    /** 接收方对传输请求的批复 */
    @Serializable
    @SerialName("transfer_ack")
    data class TransferAck(
        val transferId: String,
        val accepted: Boolean,
        val message: String? = null,
    ) : NetworkMessage()

    /** 一个文件分片（Base64 编码，兼容 JSON） */
    @Serializable
    @SerialName("file_chunk")
    data class FileChunk(
        val transferId: String,
        val offset: Long,
        val data: String,          // Base64
        val last: Boolean = false,
    ) : NetworkMessage()

    /** 纯文本内容（一次性发送） */
    @Serializable
    @SerialName("text_message")
    data class TextMessage(
        val transferId: String,
        val text: String,
        val sender: DeviceInfo,
    ) : NetworkMessage()

    /** 传输结束确认 */
    @Serializable
    @SerialName("transfer_finished")
    data class TransferFinished(
        val transferId: String,
        val success: Boolean,
        val message: String? = null,
    ) : NetworkMessage()

    /** 取消传输 */
    @Serializable
    @SerialName("cancel_transfer")
    data class CancelTransfer(val transferId: String) : NetworkMessage()
}

object DiscoveryConfig {
    const val DISCOVERY_PORT = 38671
    const val FILE_TRANSFER_PORT = 38672
    const val HEARTBEAT_INTERVAL_MS = 4_000L
    const val DEVICE_TTL_MS = 20_000L
}

object TransferConfig {
    const val CHUNK_SIZE = 64 * 1024          // 64 KiB
    const val MAX_CONCURRENT = 3
    const val CONNECT_TIMEOUT_MS = 10_000
    const val ACK_TIMEOUT_MS = 30_000
}
