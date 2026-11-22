package com.filetransfer.coui.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filetransfer.coui.settings.SettingsStore
import com.filetransfer.coui.transfer.TransferStore
import com.filetransfer.coui.ui.glass.GlassDialog
import com.filetransfer.coui.ui.glass.GlassTopBar
import com.filetransfer.coui.ui.glass.glassLite
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings = SettingsStore.get()
    val store = TransferStore.get()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val deviceName by settings.deviceNameFlow.collectAsStateWithLifecycle()
    val autoAccept by settings.autoAcceptFlow.collectAsStateWithLifecycle()
    val treeUri by settings.downloadTreeUriFlow.collectAsStateWithLifecycle()

    var showNameDialog by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val ok = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            }.isSuccess
            settings.updateDownloadTreeUri(uri.toString())
            scope.launch {
                snackbar.showSnackbar(if (ok) "已设置保存目录" else "目录已设置（授权可能受限）")
            }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            GlassTopBar(
                title = "设置",
                navigation = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("本机") }
            item {
                SettingCard {
                    SettingRow(
                        title = "设备名称",
                        subtitle = deviceName,
                        onClick = { showNameDialog = true },
                    )
                    SettingRow(
                        title = "设备 ID",
                        subtitle = settings.deviceId,
                    )
                }
            }

            item { SectionTitle("传输") }
            item {
                SettingCard {
                    SettingRow(
                        title = "自动接受",
                        subtitle = "来自任意设备的文件直接接收",
                        trailing = {
                            Switch(checked = autoAccept, onCheckedChange = { settings.updateAutoAccept(it) })
                        },
                    )
                    SettingRow(
                        title = "保存目录",
                        subtitle = folderLabel(treeUri) ?: "应用私有 Download 目录",
                        onClick = { pickFolder.launch(null) },
                    )
                    if (treeUri.isNotBlank()) {
                        SettingRow(
                            title = "恢复默认目录",
                            subtitle = "改回应用私有 Download 目录",
                            onClick = {
                                settings.updateDownloadTreeUri("")
                                scope.launch { snackbar.showSnackbar("已恢复默认保存目录") }
                            },
                        )
                    }
                    SettingRow(
                        title = "清空传输历史",
                        subtitle = "删除已完成/失败/取消的记录",
                        onClick = {
                            store.clearHistory()
                            scope.launch { snackbar.showSnackbar("已清空传输历史") }
                        },
                    )
                }
            }

            item { SectionTitle("关于") }
            item {
                SettingCard {
                    SettingRow("版本", com.filetransfer.coui.BuildConfig.VERSION_NAME)
                    SettingRow(
                        title = "开源仓库",
                        subtitle = "github.com/bacosta-d/Mosend",
                        onClick = { openUrl(context, REPO_URL) },
                    )
                    SettingRow("作者", "酷安@赵信金")
                    SettingRow("协议", "局域网 UDP 发现 + TCP 直传")
                }
            }
        }
    }

    if (showNameDialog) {
        var input by remember { mutableStateOf(deviceName) }
        GlassDialog(
            onDismissRequest = { showNameDialog = false },
            title = "修改设备名称",
            dismissText = "取消",
            confirmText = "保存",
            onConfirm = {
                settings.updateDeviceName(input)
                showNameDialog = false
            },
            content = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}

private const val REPO_URL = "https://github.com/bacosta-d/Mosend"

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** 从 SAF tree uri 中提取人类可读的目录名 */
private fun folderLabel(treeUri: String): String? {
    if (treeUri.isBlank()) return null
    return runCatching {
        val uri = Uri.parse(treeUri)
        val docId = uri.lastPathSegment ?: return null
        val raw = docId.substringAfterLast(':')
        val name = if (raw.isBlank()) {
            Uri.decode(docId).substringAfterLast(':').ifBlank { "所选目录" }
        } else raw
        if (name.isBlank()) "所选目录" else name
    }.getOrNull() ?: "所选目录"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp),
    )
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().glassLite(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.outline)
        }
    }
}
