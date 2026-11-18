package com.filetransfer.coui.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filetransfer.coui.model.ChatMessage
import com.filetransfer.coui.model.Device
import com.filetransfer.coui.model.MessageDirection
import com.filetransfer.coui.model.MessageKind
import com.filetransfer.coui.model.MessageStatus
import com.filetransfer.coui.model.TransferItem
import com.filetransfer.coui.model.TransferStatus
import com.filetransfer.coui.network.DiscoveryService
import com.filetransfer.coui.transfer.FileUtils
import com.filetransfer.coui.transfer.MessageStore
import com.filetransfer.coui.transfer.TransferEngine
import com.filetransfer.coui.transfer.TransferStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    deviceId: String,
    fallbackName: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val discovery = DiscoveryService.get(context)
    val engine = TransferEngine.get()
    val messageStore = MessageStore.get()
    val transferStore = TransferStore.get()

    val devices by discovery.devices.collectAsStateWithLifecycle()
    val allMessages by messageStore.messages.collectAsStateWithLifecycle()
    val transferList by TransferStore.get().transfers.collectAsStateWithLifecycle()
    val transferById = remember(transferList) { transferList.associateBy { it.id } }

    val device: Device? = remember(devices, deviceId) { devices.firstOrNull { it.id == deviceId } }
    val online = device?.isOnline == true
    val displayName = device?.name ?: fallbackName.ifBlank { "未知设备" }
    val messages = remember(allMessages, deviceId) { allMessages.filter { it.deviceId == deviceId }.sortedBy { it.timestamp } }

    var input by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ChatMessage?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val target = device
        if (target == null) {
            scope.launch { snackbar.showSnackbar("对方不在线，无法发送") }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri: Uri -> FileUtils.copyUriToCache(context, uri) }
            }
            if (files.isNotEmpty()) {
                engine.sendFiles(target, files)
            } else {
                snackbar.showSnackbar("读取文件失败")
            }
        }
    }

    val resendMessage: (ChatMessage) -> Unit = { msg ->
        val target = device
        if (target == null || !target.isOnline) {
            scope.launch { snackbar.showSnackbar("对方离线，无法重新发送") }
        } else when (msg.kind) {
            MessageKind.TEXT -> engine.sendText(target, msg.text.orEmpty())
            MessageKind.FILE -> scope.launch {
                val file = withContext(Dispatchers.IO) {
                    val path = msg.localPath
                    when {
                        path.isNullOrBlank() -> null
                        path.startsWith("content://") -> FileUtils.copyUriToCache(context, Uri.parse(path))
                        else -> File(path).takeIf { it.exists() }
                    }
                }
                if (file != null) {
                    engine.sendFiles(target, listOf(file))
                } else {
                    snackbar.showSnackbar("原文件不存在，无法重发")
                }
            }
        }
    }

    // 新消息到达时滚动到底部
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(0) }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (online) "在线" else "离线",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (online) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            ChatInputBar(
                value = input,
                onValueChange = { input = it },
                enabled = online,
                onSend = {
                    val target = device
                    if (target != null && input.isNotBlank()) {
                        engine.sendText(target, input)
                        input = ""
                    }
                },
                onAttach = { filePicker.launch(arrayOf("*/*")) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (!online) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "对方当前离线，消息无法发送",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("开始对话吧", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "文本和文件都会以消息形式发送",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(messages.asReversed(), key = { it.id }) { msg ->
                            MessageBubble(
                                message = msg,
                                transfer = msg.transferId?.let { transferById[it] },
                                canResend = (device?.isOnline == true) &&
                                    (msg.kind == MessageKind.TEXT || !msg.localPath.isNullOrBlank()),
                                onResend = { resendMessage(msg) },
                                onDelete = { deleteTarget = msg },
                                onOpenFile = { path, mime ->
                                    val ok = FileUtils.open(context, path, mime)
                                    if (!ok) scope.launch { snackbar.showSnackbar("无法打开文件") }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除消息") },
            text = { Text("只删除聊天记录，不会删除已接收的文件。") },
            confirmButton = {
                TextButton(onClick = {
                    messageStore.remove(target.id)
                    target.transferId?.let { transferStore.remove(it) }
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
    onAttach: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onAttach, enabled = enabled) {
                Icon(Icons.Filled.AttachFile, contentDescription = "发送文件")
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(if (enabled) "输入消息…" else "对方离线") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                enabled = enabled,
            )
            IconButton(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    transfer: TransferItem?,
    canResend: Boolean,
    onResend: () -> Unit,
    onDelete: () -> Unit,
    onOpenFile: (String?, String?) -> Unit,
) {
    val isOut = message.direction == MessageDirection.OUT
    val bubbleColor = if (isOut) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOut) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val alignment = if (isOut) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isOut) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }
    val canOpen = message.kind == MessageKind.FILE &&
        FileUtils.exists(LocalContext.current, message.localPath)
    var menuOpen by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (canOpen) onOpenFile(message.localPath, message.mimeType) },
                onLongClick = { menuOpen = true },
            ),
        contentAlignment = alignment,
    ) {
        Surface(color = bubbleColor, shape = shape, tonalElevation = 0.dp) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = 280.dp)) {
                when (message.kind) {
                    MessageKind.TEXT -> {
                        Text(message.text ?: "", style = MaterialTheme.typography.bodyLarge, color = textColor)
                    }
                    MessageKind.FILE -> {
                        FileBubbleContent(message = message, transfer = transfer, textColor = textColor)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f),
                    )
                    if (isOut) {
                        Text(
                            text = when {
                                transfer?.status == TransferStatus.WAITING_ACCEPT -> "等待接收"
                                transfer?.status == TransferStatus.CONNECTING -> "连接中"
                                transfer?.status == TransferStatus.FAILED -> "失败"
                                message.status == MessageStatus.PENDING -> "发送中"
                                message.status == MessageStatus.SENT -> "已发送"
                                message.status == MessageStatus.DELIVERED -> "已送达"
                                message.status == MessageStatus.FAILED -> "失败"
                                else -> ""
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = if (transfer?.status == TransferStatus.FAILED || message.status == MessageStatus.FAILED)
                                MaterialTheme.colorScheme.error else textColor.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (message.direction == MessageDirection.OUT) {
                DropdownMenuItem(
                    text = { Text("重新发送") },
                    enabled = canResend,
                    onClick = {
                        menuOpen = false
                        onResend()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun FileBubbleContent(
    message: ChatMessage,
    transfer: TransferItem?,
    textColor: Color,
) {
    val active = transfer?.isActive == true
    val statusText = when {
        transfer?.status == TransferStatus.WAITING_ACCEPT -> "等待对方接收"
        transfer?.status == TransferStatus.CONNECTING -> "连接中"
        transfer?.status == TransferStatus.TRANSFERRING -> "传输中 ${(transfer.progress * 100).toInt()}%"
        transfer?.status == TransferStatus.FAILED -> "失败"
        transfer?.status == TransferStatus.COMPLETED ->
            if (message.direction == MessageDirection.OUT) "已发送" else "已保存 · 点击打开"
        message.status == MessageStatus.FAILED -> "失败"
        message.status == MessageStatus.DELIVERED -> "已保存 · 点击打开"
        message.status == MessageStatus.PENDING -> "等待中"
        else -> ""
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier.size(40.dp).background(textColor.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(fileIcon(message.mimeType), contentDescription = null, tint = textColor, modifier = Modifier.size(22.dp))
            }
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = message.fileName ?: "文件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(formatSize(message.fileSize))
                        if (statusText.isNotBlank()) {
                            append(" · ")
                            append(statusText)
                        }
                        if (transfer?.status == TransferStatus.TRANSFERRING && transfer.speedBps > 0) {
                            append(" · ")
                            append(transfer.formattedSpeed)
                            transfer.eta?.let { eta ->
                                if (eta > 0) append(" · 剩余 ${formatEta(eta)}")
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.75f),
                )
            }
        }
        if (active) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (transfer?.progress ?: 0f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = textColor,
                trackColor = textColor.copy(alpha = 0.2f),
            )
        }
    }
}

private fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}


private fun fileIcon(mime: String?): ImageVector = when {
    mime == null -> Icons.Filled.Description
    mime.startsWith("image/") -> Icons.Filled.Image
    mime.startsWith("video/") -> Icons.Filled.Movie
    mime.startsWith("audio/") -> Icons.Filled.Download
    else -> Icons.Filled.Description
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}

private val bubbleTimeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private fun formatTime(ts: Long): String = bubbleTimeFmt.format(Date(ts))
