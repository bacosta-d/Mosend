package com.filetransfer.coui.settings

import android.content.Context
import android.os.Build
import com.filetransfer.coui.FileTransferApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 应用设置（SharedPreferences 持久化 + StateFlow 热更新）。
 */
class SettingsStore private constructor(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---- 设备身份 ----
    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
        ?: UUID.randomUUID().toString().replace("-", "").take(16).also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }

    private val _deviceName = MutableStateFlow(
        prefs.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultName()
    )
    val deviceNameFlow: StateFlow<String> = _deviceName.asStateFlow()
    val deviceName: String get() = _deviceName.value

    private val _autoAccept = MutableStateFlow(prefs.getBoolean(KEY_AUTO_ACCEPT, false))
    val autoAcceptFlow: StateFlow<Boolean> = _autoAccept.asStateFlow()
    val autoAcceptValue: Boolean get() = _autoAccept.value

    private val _downloadFolder = MutableStateFlow(prefs.getString(KEY_DOWNLOAD_FOLDER, "") ?: "")
    val downloadFolderFlow: StateFlow<String> = _downloadFolder.asStateFlow()
    val downloadFolderValue: String get() = _downloadFolder.value

    /** 通过系统文件夹选择器选定的目录（SAF tree uri），优先于 downloadFolderValue */
    private val _downloadTreeUri = MutableStateFlow(prefs.getString(KEY_DOWNLOAD_TREE_URI, "") ?: "")
    val downloadTreeUriFlow: StateFlow<String> = _downloadTreeUri.asStateFlow()
    val downloadTreeUriValue: String get() = _downloadTreeUri.value

    private val _knownDevices = MutableStateFlow(
        prefs.getStringSet(KEY_KNOWN_DEVICES, emptySet())?.toSet() ?: emptySet()
    )
    val knownDevicesFlow: StateFlow<Set<String>> = _knownDevices.asStateFlow()

    fun updateDeviceName(name: String) {
        val v = name.trim()
        if (v.isBlank()) return
        prefs.edit().putString(KEY_DEVICE_NAME, v).apply()
        _deviceName.value = v
    }

    fun updateAutoAccept(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ACCEPT, enabled).apply()
        _autoAccept.value = enabled
    }

    fun updateDownloadFolder(path: String) {
        prefs.edit().putString(KEY_DOWNLOAD_FOLDER, path).apply()
        _downloadFolder.value = path
    }

    fun updateDownloadTreeUri(uri: String) {
        prefs.edit().putString(KEY_DOWNLOAD_TREE_URI, uri).apply()
        _downloadTreeUri.value = uri
    }

    fun markKnown(deviceId: String) {
        val next = _knownDevices.value + deviceId
        prefs.edit().putStringSet(KEY_KNOWN_DEVICES, next).apply()
        _knownDevices.value = next
    }

    fun unmarkKnown(deviceId: String) {
        val next = _knownDevices.value - deviceId
        prefs.edit().putStringSet(KEY_KNOWN_DEVICES, next).apply()
        _knownDevices.value = next
    }

    fun isKnownDevice(deviceId: String): Boolean = _knownDevices.value.contains(deviceId)

    private fun defaultName(): String {
        val m = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return listOf(m, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android Device" }
    }

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_AUTO_ACCEPT = "auto_accept"
        private const val KEY_DOWNLOAD_FOLDER = "download_folder"
        private const val KEY_DOWNLOAD_TREE_URI = "download_tree_uri"
        private const val KEY_KNOWN_DEVICES = "known_devices"

        @Volatile private var INSTANCE: SettingsStore? = null

        fun init(context: Context) {
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SettingsStore(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun get(): SettingsStore {
            val inst = INSTANCE
            if (inst != null) return inst
            // 容错：应用未初始化时用 application context 惰性创建
            return synchronized(this) {
                INSTANCE ?: SettingsStore(FileTransferApplication.instance.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
