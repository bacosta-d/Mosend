package com.filetransfer.coui.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filetransfer.coui.model.ChatMessage
import com.filetransfer.coui.model.Device
import com.filetransfer.coui.model.MessageDirection
import com.filetransfer.coui.model.MessageKind
import com.filetransfer.coui.model.TransferItem
import com.filetransfer.coui.network.DiscoveryService
import com.filetransfer.coui.transfer.FileUtils
import com.filetransfer.coui.transfer.MessageStore
import com.filetransfer.coui.transfer.TransferEngine
import com.filetransfer.coui.transfer.TransferStore
import com.filetransfer.coui.ui.components.DeviceTile
import com.filetransfer.coui.ui.components.EmptyHint
import com.filetransfer.coui.ui.components.SummaryBar
import com.filetransfer.coui.ui.components.TransferTile
import com.filetransfer.coui.ui.glass.GlassSegmentedTabs
import com.filetransfer.coui.ui.glass.glassLite
import com.filetransfer.coui.ui.glass.GlassTopBar
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class ConversationEntry(
    val deviceId: String,
    val name: String,
    val online: Boolean,
    val lastMessage: ChatMessage?,
    val device: Device?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onSettingsClick: () -> Unit,
    onOpenChat: (deviceId: String, name: String) -> Unit,
) {
    val context = LocalContext.current
    val discovery = DiscoveryService.get(context)
    val engine = TransferEngine.get()
    val store = TransferStore.get()
    val messageStore = MessageStore.get()

    val devices by discovery.devices.collectAsStateWithLifecycle()
    val transfers by store.transfers.collectAsStateWithLifecycle()
    val allMessages by messageStore.messages.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 会话列表 = 已发现设备 ∪ 有消息历史的设备
    val conversations = remember(devices, allMessages) {
        val grouped = allMessages.groupBy { it.deviceId }
        val onlineIds = devices.map { it.id }.toSet()
        val list = mutableListOf<ConversationEntry>()
        devices.forEach { d ->
            list += ConversationEntry(
                deviceId = d.id,
                name = d.name,
                online = d.isOnline,
                lastMessage = grouped[d.id]?.maxByOrNull { it.timestamp },
                device = d,
            )
        }
        grouped.keys.filter { it !in onlineIds }.forEach { id ->
            val msgs = grouped[id] ?: return@forEach
            list += ConversationEntry(
                deviceId = id,
                name = msgs.firstOrNull()?.deviceName ?: "未知设备",
                online = false,
                lastMessage = msgs.maxByOrNull { it.timestamp },
                device = null,
            )
        }
        list.sortedWith(
            compareByDescending<ConversationEntry> { it.lastMessage?.timestamp ?: 0L }
                .thenByDescending { it.online }
        )
    }

    // 对方重装/清数据后 deviceId 会变化：把同名旧会话合并到当前设备/最近会话
    LaunchedEffect(devices, allMessages) {
        if (allMessages.isEmpty()) return@LaunchedEffect
        val grouped = allMessages.groupBy { it.deviceId }
        val byName = grouped.entries.groupBy {
            it.value.lastOrNull()?.deviceName?.trim().orEmpty()
        }
        for ((name, entries) in byName) {
            if (name.isBlank() || entries.size <= 1) continue
            val onlineIds = devices.filter { it.isOnline && it.name.trim() == name }.map { it.id }
            val targetId = onlineIds.firstOrNull()
                ?: entries.maxByOrNull { it.value.lastOrNull()?.timestamp ?: 0L }?.key
                ?: continue
            entries.forEach { entry ->
                val oldId = entry.key
                if (oldId == targetId) return@forEach
                // 该旧 ID 仍在线上时不合并，避免把两台同名设备混在一起
                if (devices.any { it.id == oldId && it.isOnline }) return@forEach
                messageStore.migrateDevice(oldId, targetId, name)
                store.migrateDevice(oldId, targetId, name)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            GlassTopBar(
                title = "Mosend",
                subtitle = "${devices.count { it.isOnline }} 台设备在线",
                actions = {
                    IconButton(onClick = { discovery.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("设置") },
                                leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                onClick = { menuOpen = false; onSettingsClick() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            GlassSegmentedTabs(
                options = listOf("会话", "传输"),
                selected = selectedTab,
                onSelect = { selectedTab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (selectedTab == 0) {
                ConversationsTab(
                    conversations = conversations,
                    onOpen = { entry -> onOpenChat(entry.deviceId, entry.name) },
                )
            } else {
                TransfersTab(
                    transfers = transfers,
                    onCancel = { engine.cancel(it) },
                    onOpen = { item ->
                        val ok = FileUtils.open(context, item.localPath, item.mimeType)
                        if (!ok) scope.launch { snackbar.showSnackbar("无法打开文件") }
                    },
                )
            }
        }
    }

}

@Composable
private fun ConversationsTab(
    conversations: List<ConversationEntry>,
    onOpen: (ConversationEntry) -> Unit,
) {
    if (conversations.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyHint(
                icon = Icons.Filled.Devices,
                title = "未发现设备",
                subtitle = "确保其他设备已打开应用并连接同一 Wi-Fi",
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SummaryBar(deviceCount = conversations.count { it.online }, activeCount = 0) }

        items(conversations, key = { it.deviceId }) { entry ->
            ConversationRow(entry = entry, onClick = { onOpen(entry) })
        }
    }
}

@Composable
private fun ConversationRow(entry: ConversationEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().glassLite(24.dp).clickable(onClick = onClick),
        shape = com.filetransfer.coui.ui.theme.AppShapes.large,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    entry.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (entry.online) {
                        Box(Modifier.size(7.dp).background(Color(0xFF2E7D32), CircleShape))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = previewOf(entry.lastMessage) ?: "点击开始对话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.lastMessage != null) {
                Text(
                    text = formatRelative(entry.lastMessage.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

private fun previewOf(message: ChatMessage?): String? {
    if (message == null) return null
    val prefix = if (message.direction == MessageDirection.OUT) "我：" else ""
    return when (message.kind) {
        MessageKind.TEXT -> prefix + (message.text ?: "")
        MessageKind.FILE -> "$prefix[文件] ${message.fileName ?: ""}"
    }
}

@Composable
private fun TransfersTab(
    transfers: List<TransferItem>,
    onCancel: (String) -> Unit,
    onOpen: (TransferItem) -> Unit,
) {
    if (transfers.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyHint(
                icon = Icons.Filled.History,
                title = "暂无文件传输记录",
                subtitle = "在会话中发送或接收文件后会显示在这里",
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(transfers, key = { it.id }) { item ->
            TransferTile(
                item = item,
                onCancel = { onCancel(item.id) },
                onOpen = { onOpen(item) },
            )
        }
    }
}

private val convTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
private fun formatRelative(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        else -> convTimeFmt.format(Date(ts))
    }
}
