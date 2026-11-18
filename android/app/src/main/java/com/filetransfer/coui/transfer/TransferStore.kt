package com.filetransfer.coui.transfer

import android.content.Context
import android.util.Log
import com.filetransfer.coui.model.TransferItem
import com.filetransfer.coui.model.TransferStatus
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
 * 传输记录仓库：内存态 + 文件持久化。
 * 数据持久化到 filesDir/transfer_history.json。
 */
class TransferStore private constructor(context: Context) {

    private val TAG = "TransferStore"
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val historyFile = File(appContext.filesDir, "transfer_history.json")

    private val _transfers = MutableStateFlow<List<TransferItem>>(emptyList())
    val transfers: StateFlow<List<TransferItem>> = _transfers.asStateFlow()

    companion object {
        @Volatile private var INSTANCE: TransferStore? = null

        fun init(context: Context) {
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransferStore(context).also { INSTANCE = it }
            }
        }

        fun get(): TransferStore = INSTANCE
            ?: throw IllegalStateException("TransferStore not initialized. Call init() first.")
    }

    init {
        scope.launch { loadHistory() }
    }

    fun addTransfer(item: TransferItem) {
        _transfers.update { list -> listOf(item) + list.filterNot { it.id == item.id } }
        persistAsync()
    }

    fun updateTransfer(id: String, transform: (TransferItem) -> TransferItem) {
        _transfers.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
        persistAsync()
    }

    fun updateStatus(id: String, status: TransferStatus, errorMessage: String? = null, endTime: Long? = null) {
        updateTransfer(id) {
            it.copy(
                status = status,
                errorMessage = errorMessage ?: it.errorMessage,
                endTime = endTime ?: it.endTime,
            )
        }
    }

    /** 高频进度更新：只改内存，不落盘（状态变化时再持久化）。 */
    fun updateProgress(id: String, progress: Float, bytesTransferred: Long, speedBps: Long) {
        _transfers.update { list ->
            list.map {
                if (it.id == id) it.copy(
                    progress = progress.coerceIn(0f, 1f),
                    bytesTransferred = bytesTransferred,
                    speedBps = speedBps,
                ) else it
            }
        }
    }

    /** 传输记录跟随设备 ID 变化迁移。 */
    fun migrateDevice(fromId: String, toId: String, toName: String) {
        if (fromId == toId) return
        _transfers.update { list ->
            list.map {
                if (it.deviceId == fromId) it.copy(deviceId = toId, deviceName = toName) else it
            }
        }
        persistAsync()
    }

    fun getTransfer(id: String): TransferItem? = _transfers.value.firstOrNull { it.id == id }

    fun activeTransfers(): List<TransferItem> = _transfers.value.filter { it.isActive }

    fun clearHistory() {
        _transfers.update { list -> list.filter { it.isActive } }
        persistAsync()
    }

    fun remove(id: String) {
        _transfers.update { list -> list.filterNot { it.id == id } }
        persistAsync()
    }

    private fun persistAsync() {
        val snapshot = _transfers.value
        scope.launch {
            try {
                historyFile.writeText(json.encodeToString(snapshot))
            } catch (e: Exception) {
                Log.e(TAG, "persist failed", e)
            }
        }
    }

    private fun loadHistory() {
        try {
            if (!historyFile.exists()) return
            val text = historyFile.readText()
            if (text.isBlank()) return
            val loaded = json.decodeFromString<List<TransferItem>>(text)
            // 重启后把未完成的标记为失败
            val normalized = loaded.map {
                if (it.isActive) it.copy(status = TransferStatus.FAILED, errorMessage = "Interrupted") else it
            }
            _transfers.value = normalized
        } catch (e: Exception) {
            Log.e(TAG, "load history failed", e)
        }
    }
}
