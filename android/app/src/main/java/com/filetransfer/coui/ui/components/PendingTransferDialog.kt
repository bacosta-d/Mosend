package com.filetransfer.coui.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filetransfer.coui.transfer.TransferEngine

/**
 * 全局接收确认弹窗：挂在 NavHost 外层，任何页面（会话/设置/传输列表）都能收到提示。
 */
@Composable
fun PendingTransferHost() {
    val engine = remember { TransferEngine.get() }
    val pending by engine.pendingRequests.collectAsStateWithLifecycle()
    val p = pending.firstOrNull() ?: return

    AlertDialog(
        onDismissRequest = { engine.rejectPending(p.request.transferId) },
        title = { Text("收到文件") },
        text = {
            val sizeKb = (p.request.fileSize / 1024).coerceAtLeast(1)
            Text("${p.request.sender.name} 想发送「${p.request.fileName}」（${sizeKb} KB），是否接收？")
        },
        confirmButton = {
            TextButton(onClick = { engine.acceptPending(p.request.transferId) }) { Text("接收") }
        },
        dismissButton = {
            TextButton(onClick = { engine.rejectPending(p.request.transferId) }) { Text("拒绝") }
        },
    )
}
