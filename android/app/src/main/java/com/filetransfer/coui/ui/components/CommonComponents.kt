package com.filetransfer.coui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filetransfer.coui.model.Device
import com.filetransfer.coui.model.DeviceType
import com.filetransfer.coui.model.TransferDirection
import com.filetransfer.coui.model.TransferItem
import com.filetransfer.coui.model.TransferStatus
import com.filetransfer.coui.ui.theme.AppShapes
import com.filetransfer.coui.ui.theme.AppTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ 首页汇总

@Composable
fun SummaryBar(deviceCount: Int, activeCount: Int, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryStat(Icons.Filled.Devices, "在线设备", deviceCount.toString())
            SummaryStat(Icons.Filled.InsertDriveFile, "进行中", activeCount.toString())
            Spacer(Modifier.weight(1f))
            Text(
                text = "局域网直连",
                style = AppTypography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryStat(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(value, style = AppTypography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = AppTypography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ 设备卡片

@Composable
fun DeviceTile(
    device: Device,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = AppShapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = deviceTypeIcon(device.deviceType),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = device.name,
                        style = AppTypography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (device.isOnline) {
                        Box(Modifier.size(8.dp).background(Color(0xFF2E7D32), CircleShape))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${device.address} · ${deviceTypeLabel(device.deviceType)}",
                    style = AppTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OnlineBadge(device.isOnline)
        }
    }
}

@Composable
private fun OnlineBadge(online: Boolean) {
    val color = if (online) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline
    Surface(color = color.copy(alpha = 0.12f), shape = AppShapes.small) {
        Text(
            text = if (online) "在线" else "离线",
            style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ------------------------------------------------------------------ 传输卡片

@Composable
fun TransferTile(
    item: TransferItem,
    onCancel: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, AppShapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(fileIcon(item.mimeType), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = AppTypography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${directionLabel(item.direction)} · ${item.formattedSize} · ${item.deviceName}",
                        style = AppTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusBadge(item.status)
            }

            if (item.isActive) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { item.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "${(item.progress * 100).toInt()}%  ${item.bytesTransferred / 1024}KB/${item.fileSize / 1024}KB",
                        style = AppTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "${item.formattedSpeed}",
                            style = AppTypography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (onCancel != null) {
                            Text(
                                text = "取消",
                                style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable { onCancel() },
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = formatTimestamp(item.startTime),
                        style = AppTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (onOpen != null && item.status == TransferStatus.COMPLETED) {
                        Text(
                            text = "打开",
                            style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onOpen() },
                        )
                    }
                    if (item.errorMessage != null) {
                        Text(
                            text = item.errorMessage,
                            style = AppTypography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(160.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: TransferStatus) {
    val (label, color) = when (status) {
        TransferStatus.PENDING -> "等待中" to MaterialTheme.colorScheme.outline
        TransferStatus.CONNECTING -> "连接中" to MaterialTheme.colorScheme.primary
        TransferStatus.WAITING_ACCEPT -> "待确认" to Color(0xFFE65100)
        TransferStatus.TRANSFERRING -> "传输中" to MaterialTheme.colorScheme.primary
        TransferStatus.COMPLETED -> "已完成" to Color(0xFF2E7D32)
        TransferStatus.FAILED -> "失败" to MaterialTheme.colorScheme.error
        TransferStatus.CANCELLED -> "已取消" to MaterialTheme.colorScheme.outline
    }
    Surface(color = color.copy(alpha = 0.12f), shape = AppShapes.small) {
        Text(
            text = label,
            style = AppTypography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ------------------------------------------------------------------ 空状态

@Composable
fun EmptyHint(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = AppTypography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = AppTypography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ------------------------------------------------------------------ 工具

fun deviceTypeIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.PHONE -> Icons.Filled.PhoneAndroid
    DeviceType.TABLET -> Icons.Filled.Tablet
    DeviceType.DESKTOP -> Icons.Filled.Computer
    DeviceType.LAPTOP -> Icons.Filled.Laptop
    DeviceType.UNKNOWN -> Icons.Filled.Devices
}

fun deviceTypeLabel(type: DeviceType): String = when (type) {
    DeviceType.PHONE -> "手机"
    DeviceType.TABLET -> "平板"
    DeviceType.DESKTOP -> "台式机"
    DeviceType.LAPTOP -> "笔记本"
    DeviceType.UNKNOWN -> "未知设备"
}

private fun directionLabel(direction: TransferDirection): String =
    if (direction == TransferDirection.SEND) "发送" else "接收"

fun fileIcon(mimeType: String?): ImageVector = when {
    mimeType == null -> Icons.Filled.InsertDriveFile
    mimeType.startsWith("image/") -> Icons.Filled.Image
    mimeType.startsWith("video/") -> Icons.Filled.VideoFile
    mimeType.startsWith("audio/") -> Icons.Filled.AudioFile
    mimeType.startsWith("text/") -> Icons.Filled.Description
    else -> Icons.Filled.InsertDriveFile
}

private val timeFormatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
fun formatTimestamp(ts: Long): String = timeFormatter.format(Date(ts))
