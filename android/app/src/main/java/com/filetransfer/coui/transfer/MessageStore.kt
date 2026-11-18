package com.filetransfer.coui.transfer

import android.content.Context
import android.util.Log
import com.filetransfer.coui.model.ChatMessage
import com.filetransfer.coui.model.MessageDirection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 会话消息仓库：StateFlow<List<ChatMessage>> + JSON 持久化。
 */
class MessageStore private constructor(context: Context) {

    private val TAG = "MessageStore"
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file = File(appContext.filesDir, "chat_messages.json")

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    companion object {
        @Volatile private var INSTANCE: MessageStore? = null

        fun init(context: Context) {
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MessageStore(context).also { INSTANCE = it }
            }
        }

        fun get(): MessageStore = INSTANCE
            ?: throw IllegalStateException("MessageStore not initialized. Call init() first.")
    }

    init {
        scope.launch { load() }
    }

    fun add(message: ChatMessage) {
        _messages.update { it + message }
        persistAsync()
    }

    fun update(id: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { list -> list.map { if (it.id == id) transform(it) else it } }
        persistAsync()
    }

    fun updateByTransferId(transferId: String, transform: (ChatMessage) -> ChatMessage) {
        _messages.update { list -> list.map { if (it.transferId == transferId) transform(it) else it } }
        persistAsync()
    }

    fun messagesFor(deviceId: String): List<ChatMessage> =
        _messages.value.filter { it.deviceId == deviceId }.sortedBy { it.timestamp }

    /** 每个设备的最后一条消息 */
    fun lastMessageMap(): Map<String, ChatMessage> =
        _messages.value.groupBy { it.deviceId }.mapValues { (_, list) -> list.maxByOrNull { it.timestamp }!! }

    fun unreadCount(deviceId: String): Int =
        _messages.value.count { it.deviceId == deviceId && it.direction == MessageDirection.IN && it.status != com.filetransfer.coui.model.MessageStatus.DELIVERED }

    fun clearConversation(deviceId: String) {
        _messages.update { list -> list.filterNot { it.deviceId == deviceId } }
        persistAsync()
    }

    private fun persistAsync() {
        val snapshot = _messages.value
        scope.launch {
            try {
                file.writeText(json.encodeToString(snapshot))
            } catch (e: Exception) {
                Log.e(TAG, "persist failed", e)
            }
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val text = file.readText()
            if (text.isBlank()) return
            _messages.value = json.decodeFromString<List<ChatMessage>>(text)
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
        }
    }
}
