package com.filetransfer.coui.transfer

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.filetransfer.coui.model.ChatMessage
import com.filetransfer.coui.model.Device
import com.filetransfer.coui.model.MessageDirection
import com.filetransfer.coui.model.MessageKind
import com.filetransfer.coui.model.MessageStatus
import com.filetransfer.coui.model.TransferDirection
import com.filetransfer.coui.model.TransferItem
import com.filetransfer.coui.model.TransferStatus
import com.filetransfer.coui.network.DiscoveryConfig
import com.filetransfer.coui.network.DiscoveryService
import com.filetransfer.coui.network.NetworkMessage
import com.filetransfer.coui.network.TransferConfig
import com.filetransfer.coui.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件/消息传输引擎。
 *
 * - 文本消息：像聊天软件一样即时双向投递，无需确认，也不落 txt 文件。
 * - 文件消息：TCP 长连接传输，分片 Base64，接收端落盘后可点击打开。
 */
class TransferEngine private constructor(context: Context) {

    private val TAG = "TransferEngine"
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val store = TransferStore.get()
    private val messages = MessageStore.get()
    private val settings = SettingsStore.get()
    private val discovery = DiscoveryService.get()

    private val sessions = ConcurrentHashMap<String, Session>()

    /** 等待用户确认的接收请求（仅文件） */
    private val _pending = MutableStateFlow<List<PendingReceive>>(emptyList())
    val pendingRequests: StateFlow<List<PendingReceive>> = _pending.asStateFlow()

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    companion object {
        @Volatile private var INSTANCE: TransferEngine? = null

        fun init(context: Context) {
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransferEngine(context).also { INSTANCE = it }
            }
        }

        fun get(): TransferEngine = INSTANCE
            ?: throw IllegalStateException("TransferEngine not initialized. Call init() first.")
    }

    // ---------------------------------------------------------------- 生命周期

    fun start() {
        if (running) return
        running = true
        scope.launch { acceptLoop() }
        Log.i(TAG, "TransferEngine started")
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        sessions.values.forEach { runCatching { it.socket.close() } }
        sessions.clear()
    }

    // ---------------------------------------------------------------- 发送：文件

    fun sendFiles(device: Device, files: List<File>): List<String> {
        val ids = mutableListOf<String>()
        files.forEach { file ->
            val id = UUID.randomUUID().toString()
            val mime = FileUtils.guessMime(file.name)
            val item = TransferItem(
                id = id,
                fileName = file.name,
                fileSize = file.length(),
                mimeType = mime,
                localPath = file.absolutePath,
                direction = TransferDirection.SEND,
                status = TransferStatus.PENDING,
                deviceId = device.id,
                deviceName = device.name,
            )
            store.addTransfer(item)
            messages.add(
                ChatMessage(
                    deviceId = device.id,
                    deviceName = device.name,
                    direction = MessageDirection.OUT,
                    kind = MessageKind.FILE,
                    fileName = file.name,
                    fileSize = file.length(),
                    mimeType = mime,
                    localPath = file.absolutePath,
                    transferId = id,
                    status = MessageStatus.PENDING,
                )
            )
            ids += id
            scope.launch { runSendFile(device, item, file) }
        }
        return ids
    }

    // ---------------------------------------------------------------- 发送：文本

    fun sendText(device: Device, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val msgId = UUID.randomUUID().toString()
        messages.add(
            ChatMessage(
                id = msgId,
                deviceId = device.id,
                deviceName = device.name,
                direction = MessageDirection.OUT,
                kind = MessageKind.TEXT,
                text = trimmed,
                status = MessageStatus.PENDING,
            )
        )
        scope.launch { runSendText(device, msgId, trimmed) }
    }

    fun cancel(transferId: String) {
        sessions.remove(transferId)?.let { s -> runCatching { s.socket.close() } }
        store.updateStatus(transferId, TransferStatus.CANCELLED, "Cancelled by user")
        messages.updateByTransferId(transferId) { it.copy(status = MessageStatus.FAILED) }
    }

    // ---------------------------------------------------------------- 接收确认（文件）

    fun acceptPending(transferId: String) {
        val pending = _pending.value.firstOrNull { it.request.transferId == transferId } ?: return
        _pending.update { list -> list.filterNot { it.request.transferId == transferId } }
        scope.launch { runReceiveConfirmed(pending, true) }
    }

    fun rejectPending(transferId: String) {
        val pending = _pending.value.firstOrNull { it.request.transferId == transferId } ?: return
        _pending.update { list -> list.filterNot { it.request.transferId == transferId } }
        scope.launch { runReceiveConfirmed(pending, false) }
    }

    // ---------------------------------------------------------------- 发送实现

    private suspend fun runSendFile(device: Device, item: TransferItem, file: File) {
        val socket = Socket()
        sessions[item.id] = Session(item.id, socket)
        try {
            socket.tcpNoDelay = true
            try { socket.sendBufferSize = 4 * 1024 * 1024 } catch (_: Exception) {}
            store.updateStatus(item.id, TransferStatus.CONNECTING)
            socket.connect(InetSocketAddress(device.address, DiscoveryConfig.FILE_TRANSFER_PORT), TransferConfig.CONNECT_TIMEOUT_MS)
            socket.soTimeout = TransferConfig.ACK_TIMEOUT_MS
            store.updateStatus(item.id, TransferStatus.WAITING_ACCEPT)
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 1024 * 1024))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))

            send(out, NetworkMessage.TransferRequest(
                transferId = item.id,
                fileName = item.fileName,
                fileSize = item.fileSize,
                mimeType = item.mimeType,
                sender = discovery.buildDeviceInfo(),
                isText = false,
            ))
            val ack = read(input)
            if (ack !is NetworkMessage.TransferAck || !ack.accepted) {
                throw IllegalStateException((ack as? NetworkMessage.TransferAck)?.message ?: "no ack")
            }

            socket.soTimeout = TransferConfig.TRANSFER_TIMEOUT_MS
            store.updateStatus(item.id, TransferStatus.TRANSFERRING)
            messages.updateByTransferId(item.id) { it.copy(status = MessageStatus.PENDING) }

            var sent = 0L
            var lastTime = System.currentTimeMillis()
            var lastBytes = 0L
            var ema = 0.0

            fun reportSpeed(now: Long) {
                val dt = (now - lastTime).coerceAtLeast(1)
                val inst = (sent - lastBytes) * 1000.0 / dt
                ema = if (ema <= 0.0) inst else ema * 0.7 + inst * 0.3
                lastTime = now
                lastBytes = sent
                store.updateProgress(item.id, sent.toFloat() / item.fileSize.coerceAtLeast(1), sent, ema.toLong())
            }

            FileInputStream(file).use { fis ->
                if (ack.binary) {
                    // 二进制直传：跳过 Base64 与 JSON 序列化
                    send(out, NetworkMessage.BinaryBegin(item.id))
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val n = fis.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        sent += n
                        val now = System.currentTimeMillis()
                        if (sent - lastBytes >= 1024 * 1024 || now - lastTime >= 300) reportSpeed(now)
                    }
                    out.flush()
                } else {
                    val buffer = ByteArray(TransferConfig.CHUNK_SIZE)
                    while (true) {
                        val n = fis.read(buffer)
                        if (n <= 0) break
                        send(out, NetworkMessage.FileChunk(
                            transferId = item.id,
                            offset = sent,
                            data = Base64.encodeToString(buffer.copyOf(n), Base64.NO_WRAP),
                            last = false,
                        ))
                        sent += n
                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 200) reportSpeed(now)
                    }
                    send(out, NetworkMessage.FileChunk(item.id, sent, "", last = true))
                    send(out, NetworkMessage.TransferFinished(item.id, true))
                }
            }

            store.updateProgress(item.id, 1f, item.fileSize, ema.toLong())
            store.updateStatus(item.id, TransferStatus.COMPLETED, endTime = System.currentTimeMillis())
            messages.updateByTransferId(item.id) { it.copy(status = MessageStatus.SENT) }
        } catch (e: Exception) {
            Log.e(TAG, "sendFile failed", e)
            store.updateStatus(item.id, TransferStatus.FAILED, sendErrorMessage(e), System.currentTimeMillis())
            messages.updateByTransferId(item.id) { it.copy(status = MessageStatus.FAILED) }
        } finally {
            sessions.remove(item.id)
            runCatching { socket.close() }
        }
    }

    private suspend fun runSendText(device: Device, msgId: String, text: String) {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(device.address, DiscoveryConfig.FILE_TRANSFER_PORT), TransferConfig.CONNECT_TIMEOUT_MS)
            socket.soTimeout = TransferConfig.ACK_TIMEOUT_MS
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
            val input = DataInputStream(socket.getInputStream())
            val txId = UUID.randomUUID().toString()

            send(out, NetworkMessage.TransferRequest(
                transferId = txId,
                fileName = "",
                fileSize = text.toByteArray().size.toLong(),
                mimeType = "text/plain",
                sender = discovery.buildDeviceInfo(),
                isText = true,
            ))
            val ack = read(input)
            if (ack !is NetworkMessage.TransferAck || !ack.accepted) {
                throw IllegalStateException((ack as? NetworkMessage.TransferAck)?.message ?: "no ack")
            }
            send(out, NetworkMessage.TextMessage(txId, text, discovery.buildDeviceInfo()))
            send(out, NetworkMessage.TransferFinished(txId, true))
            messages.update(msgId) { it.copy(status = MessageStatus.SENT) }
        } catch (e: Exception) {
            Log.e(TAG, "sendText failed", e)
            messages.update(msgId) { it.copy(status = MessageStatus.FAILED) }
        } finally {
            runCatching { socket.close() }
        }
    }

    // ---------------------------------------------------------------- 接收实现

    private suspend fun acceptLoop() = withContext(Dispatchers.IO) {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(DiscoveryConfig.FILE_TRANSFER_PORT))
            serverSocket = ss
            Log.i(TAG, "Listening on ${ss.localPort}")
            while (running) {
                val socket = try { ss.accept() } catch (e: Exception) { if (running) continue else break }
                scope.launch { handleIncoming(socket) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "acceptLoop stopped", e)
        }
    }

    private suspend fun handleIncoming(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.tcpNoDelay = true
            val input = DataInputStream(socket.getInputStream())
            val first = read(input) ?: return@withContext
            if (first !is NetworkMessage.TransferRequest) return@withContext

            // 文本消息：像聊天一样即时投递，无需确认
            if (first.isText) {
                runReceiveText(first, socket)
                return@withContext
            }

            if (!settings.autoAcceptValue) {
                val item = TransferItem(
                    id = first.transferId,
                    fileName = first.fileName,
                    fileSize = first.fileSize,
                    mimeType = first.mimeType,
                    direction = TransferDirection.RECEIVE,
                    status = TransferStatus.WAITING_ACCEPT,
                    deviceId = first.sender.id,
                    deviceName = first.sender.name,
                )
                store.addTransfer(item)
                _pending.update { it + PendingReceive(first, socket, System.currentTimeMillis()) }
                return@withContext
            }
            runReceiveFile(first, socket)
        } catch (e: Exception) {
            Log.e(TAG, "incoming error", e)
            runCatching { socket.close() }
        }
    }

    private suspend fun runReceiveConfirmed(pending: PendingReceive, accepted: Boolean) {
        try {
            val out = DataOutputStream(pending.socket.getOutputStream())
            if (!accepted) {
                send(out, NetworkMessage.TransferAck(pending.request.transferId, false, "Rejected"))
                pending.socket.close()
                store.updateStatus(pending.request.transferId, TransferStatus.CANCELLED, "Rejected by user")
                return
            }
            runReceiveFile(pending.request, pending.socket)
        } catch (e: Exception) {
            Log.e(TAG, "runReceiveConfirmed error", e)
            runCatching { pending.socket.close() }
        }
    }

    private suspend fun runReceiveText(request: NetworkMessage.TransferRequest, socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val out = DataOutputStream(socket.getOutputStream())
            val input = DataInputStream(socket.getInputStream())
            send(out, NetworkMessage.TransferAck(request.transferId, true))

            var text: String? = null
            loop@ while (true) {
                val msg = read(input) ?: break
                when (msg) {
                    is NetworkMessage.TextMessage -> text = msg.text
                    is NetworkMessage.TransferFinished -> break@loop
                    else -> {}
                }
            }
            if (text != null) {
                messages.add(
                    ChatMessage(
                        deviceId = request.sender.id,
                        deviceName = request.sender.name,
                        direction = MessageDirection.IN,
                        kind = MessageKind.TEXT,
                        text = text,
                        status = MessageStatus.DELIVERED,
                    )
                )
            } else {
                Log.w(TAG, "receiveText: no text received")
            }
        } catch (e: Exception) {
            Log.e(TAG, "receiveText error", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun runReceiveFile(request: NetworkMessage.TransferRequest, socket: Socket) = withContext(Dispatchers.IO) {
        val id = request.transferId
        try {
            socket.tcpNoDelay = true
            try { socket.receiveBufferSize = 4 * 1024 * 1024 } catch (_: Exception) {}
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1024 * 1024))

            if (store.getTransfer(id) == null) {
                store.addTransfer(TransferItem(
                    id = id,
                    fileName = request.fileName,
                    fileSize = request.fileSize,
                    mimeType = request.mimeType,
                    direction = TransferDirection.RECEIVE,
                    status = TransferStatus.CONNECTING,
                    deviceId = request.sender.id,
                    deviceName = request.sender.name,
                ))
            }
            messages.add(
                ChatMessage(
                    deviceId = request.sender.id,
                    deviceName = request.sender.name,
                    direction = MessageDirection.IN,
                    kind = MessageKind.FILE,
                    fileName = request.fileName,
                    fileSize = request.fileSize,
                    mimeType = request.mimeType,
                    transferId = id,
                    status = MessageStatus.PENDING,
                )
            )

            // 声明支持二进制直传；老发送端会忽略该字段继续走 Base64 分片
            send(out, NetworkMessage.TransferAck(transferId = id, accepted = true, binary = true))
            store.updateStatus(id, TransferStatus.TRANSFERRING)

            val target = createTarget(request.fileName)
            val output = BufferedOutputStream(target.opener(), 1024 * 1024)
            var written = 0L
            var finished: NetworkMessage.TransferFinished? = null
            var ok = true

            try {
                val first = read(input)
                if (first is NetworkMessage.BinaryBegin) {
                    val buffer = ByteArray(1024 * 1024)
                    var remaining = request.fileSize
                    var lastTime = System.currentTimeMillis()
                    var lastBytes = 0L
                    var ema = 0.0
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val n = input.read(buffer, 0, toRead)
                        if (n < 0) { ok = false; break }
                        output.write(buffer, 0, n)
                        written += n
                        remaining -= n
                        val now = System.currentTimeMillis()
                        if (written - lastBytes >= 1024 * 1024 || now - lastTime >= 300) {
                            val dt = (now - lastTime).coerceAtLeast(1)
                            val inst = (written - lastBytes) * 1000.0 / dt
                            ema = if (ema <= 0.0) inst else ema * 0.7 + inst * 0.3
                            lastTime = now
                            lastBytes = written
                            store.updateProgress(id, written.toFloat() / request.fileSize.coerceAtLeast(1), written, ema.toLong())
                        }
                    }
                } else {
                    var frame: NetworkMessage? = first
                    var stop = false
                    var lastTime = System.currentTimeMillis()
                    var lastBytes = 0L
                    var ema = 0.0
                    while (frame != null && !stop) {
                        when (frame) {
                            is NetworkMessage.FileChunk -> {
                                if (frame.last) { stop = true; break }
                                val bytes = Base64.decode(frame.data, Base64.NO_WRAP)
                                output.write(bytes)
                                written += bytes.size
                                val now = System.currentTimeMillis()
                                if (now - lastTime >= 200) {
                                    val dt = (now - lastTime).coerceAtLeast(1)
                                    val inst = (written - lastBytes) * 1000.0 / dt
                                    ema = if (ema <= 0.0) inst else ema * 0.7 + inst * 0.3
                                    lastTime = now
                                    lastBytes = written
                                    store.updateProgress(id, written.toFloat() / request.fileSize.coerceAtLeast(1), written, ema.toLong())
                                }
                            }
                            is NetworkMessage.TransferFinished -> { finished = frame; stop = true }
                            is NetworkMessage.CancelTransfer -> throw IllegalStateException("Sender cancelled")
                            else -> {}
                        }
                        if (!stop) frame = read(input)
                    }
                }
            } finally {
                runCatching { output.flush() }
                runCatching { output.close() }
            }

            val success = ok && written >= request.fileSize && (finished?.success != false)
            if (success) {
                val location = target.location
                store.updateStatus(id, TransferStatus.COMPLETED, endTime = System.currentTimeMillis())
                store.updateTransfer(id) { it.copy(localPath = location) }
                messages.updateByTransferId(id) { it.copy(status = MessageStatus.DELIVERED, localPath = location) }
                notifyMedia(location)
            } else {
                // 清理中断产生的空文件/半成品
                if (!target.location.startsWith("content://")) {
                    runCatching { java.io.File(target.location).delete() }
                }
                store.updateStatus(id, TransferStatus.FAILED, finished?.message ?: "传输中断", System.currentTimeMillis())
                messages.updateByTransferId(id) { it.copy(status = MessageStatus.FAILED) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "receiveFile error", e)
            store.updateStatus(id, TransferStatus.FAILED, e.message, System.currentTimeMillis())
            messages.updateByTransferId(id) { it.copy(status = MessageStatus.FAILED) }
        } finally {
            runCatching { socket.close() }
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun sendErrorMessage(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "等待对方确认超时"
        else -> e.message ?: "传输失败"
    }

    private fun send(out: DataOutputStream, message: NetworkMessage) {
        val bytes = json.encodeToString(NetworkMessage.serializer(), message).toByteArray()
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }

    private fun read(input: DataInputStream): NetworkMessage? {
        val len = try { input.readInt() } catch (e: Exception) { return null }
        if (len <= 0 || len > 16 * 1024 * 1024) return null
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return json.decodeFromString(NetworkMessage.serializer(), String(bytes))
    }

    private fun resolveSaveFile(fileName: String): File {
        val baseDir = settings.downloadFolderValue.let { folder ->
            if (folder.isNotBlank()) File(folder) else appContext.getExternalFilesDir("Download") ?: File(appContext.filesDir, "Download")
        }
        baseDir.mkdirs()
        var candidate = File(baseDir, fileName.ifBlank { "received_${System.currentTimeMillis()}" })
        if (!candidate.exists()) return candidate
        val dot = candidate.name.lastIndexOf('.')
        val name = if (dot > 0) candidate.name.substring(0, dot) else candidate.name
        val ext = if (dot > 0) candidate.name.substring(dot) else ""
        var i = 1
        while (candidate.exists()) {
            candidate = File(baseDir, "${name}_$i$ext")
            i++
        }
        return candidate
    }

    private fun notifyMedia(location: String) {
        try {
            if (!location.startsWith("/")) return
            val file = File(location)
            if (file.exists()) {
                android.media.MediaScannerConnection.scanFile(appContext, arrayOf(file.absolutePath), null, null)
            }
        } catch (_: Exception) {}
    }

    /**
     * 创建接收目标：优先使用用户在设置里选定的 SAF 目录，否则落到应用私有 Download 目录。
     */
    private fun createTarget(fileName: String): Target {
        val safeName = fileName.ifBlank { "received_${System.currentTimeMillis()}" }
        val treeUriStr = settings.downloadTreeUriValue
        if (treeUriStr.isNotBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(appContext, Uri.parse(treeUriStr))
                if (tree != null && tree.canWrite()) {
                    val unique = uniqueName(tree, safeName)
                    val mime = FileUtils.guessMime(unique)
                    val doc = tree.createFile(mime, unique)
                    if (doc != null) {
                        val res = appContext.contentResolver
                        // 验证流可用
                        res.openOutputStream(doc.uri, "wt")?.use { } ?: res.openOutputStream(doc.uri)?.use { }
                        return Target(doc.uri.toString()) {
                            res.openOutputStream(doc.uri)
                                ?: throw IllegalStateException("无法写入所选目录")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "SAF target failed, fallback to app dir", e)
            }
        }
        val file = resolveSaveFile(safeName)
        return Target(file.absolutePath) { FileOutputStream(file, false) }
    }

    private fun uniqueName(tree: DocumentFile, name: String): String {
        if (tree.findFile(name) == null) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        var candidate = "${base}_$i$ext"
        while (tree.findFile(candidate) != null) {
            i++
            candidate = "${base}_$i$ext"
        }
        return candidate
    }

    private class Session(val id: String, val socket: Socket)

    private class Target(val location: String, val opener: () -> java.io.OutputStream)

    data class PendingReceive(
        val request: NetworkMessage.TransferRequest,
        val socket: Socket,
        val receivedAt: Long,
    )
}
