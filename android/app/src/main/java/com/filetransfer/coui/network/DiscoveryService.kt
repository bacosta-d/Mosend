package com.filetransfer.coui.network

import android.content.Context
import android.util.Log
import com.filetransfer.coui.FileTransferApplication
import com.filetransfer.coui.model.Capability
import com.filetransfer.coui.model.Device
import com.filetransfer.coui.model.DeviceInfo
import com.filetransfer.coui.model.DeviceType
import com.filetransfer.coui.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

/**
 * 局域网设备发现服务。
 *
 * 采用 UDP 组播/广播 + 周期性心跳：
 *  - 每 4 秒向所有活跃网卡广播一个 [NetworkMessage.Discover]
 *  - 收到 Discover 时立刻单播回一个 [NetworkMessage.DiscoverResponse]
 *  - 收到 DiscoverResponse 时把对端写入设备表
 *  - 超过 20 秒未收到心跳的设备会被移除
 */
class DiscoveryService private constructor(context: Context) {

    private val TAG = "DiscoveryService"
    private val appContext = context.applicationContext
    private val settings = SettingsStore.get()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val devicesById = ConcurrentHashMap<String, Device>()
    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    private var scope: CoroutineScope? = null
    private var udpJob: Job? = null
    private var heartbeatJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    @Volatile private var running = false

    companion object {
        private const val DISCOVERY_PORT = 38671
        private const val HEARTBEAT_INTERVAL_MS = 4_000L
        private const val DEVICE_TTL_MS = 20_000L

        @Volatile private var INSTANCE: DiscoveryService? = null

        fun get(context: Context = FileTransferApplication.instance): DiscoveryService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: DiscoveryService(context).also { INSTANCE = it }
            }
    }

    fun start() {
        if (running) return
        running = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        udpJob = s.launch { udpLoop() }
        heartbeatJob = s.launch { heartbeatLoop() }
        Log.i(TAG, "Discovery started")
    }

    fun stop() {
        running = false
        udpJob?.cancel()
        heartbeatJob?.cancel()
        scope = null
        try { udpSocket?.close() } catch (_: Exception) {}
        udpSocket = null
        devicesById.clear()
        _devices.value = emptyList()
        Log.i(TAG, "Discovery stopped")
    }

    fun refresh() {
        scope?.launch { sendDiscover() }
    }

    fun buildDeviceInfo(): DeviceInfo {
        val name = settings.deviceName.let { if (it == "device_name" || it.isBlank()) defaultName() else it }
        return DeviceInfo(
            id = settings.deviceId,
            name = name,
            deviceType = DeviceType.PHONE,
            capabilities = listOf(Capability.FILE_TRANSFER, Capability.TEXT_TRANSFER),
            version = "1.0.0",
        )
    }

    private fun defaultName(): String = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()

    private fun ensureSocket(): DatagramSocket {
        udpSocket?.let { if (!it.isClosed) return it }
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true
        socket.bind(InetSocketAddress(DISCOVERY_PORT))
        udpSocket = socket
        return socket
    }

    private suspend fun udpLoop() {
        while (running && scope?.isActive == true) {
            try {
                val socket = ensureSocket()
                val buffer = ByteArray(4096)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet) // blocks
                handlePacket(packet)
            } catch (e: Exception) {
                if (running) {
                    Log.w(TAG, "UDP loop error: ${e.message}")
                    delay(1000)
                    try { udpSocket?.close() } catch (_: Exception) {}
                    udpSocket = null
                }
            }
        }
    }

    private suspend fun heartbeatLoop() {
        // 立即发一次
        sendDiscover()
        while (running && scope?.isActive == true) {
            delay(HEARTBEAT_INTERVAL_MS)
            sendDiscover()
            pruneExpired()
        }
    }

    private fun sendDiscover() {
        val message = NetworkMessage.Discover(buildDeviceInfo())
        val bytes = json.encodeToString(NetworkMessage.serializer(), message).toByteArray()
        val socket = udpSocket ?: return
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.interfaceAddresses) {
                    val b = addr.broadcast ?: continue
                    try {
                        socket.send(DatagramPacket(bytes, bytes.size, b, DISCOVERY_PORT))
                    } catch (e: Exception) {
                        Log.v(TAG, "broadcast fail ${b.hostAddress}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendDiscover error: ${e.message}")
        }
    }

    private fun handlePacket(packet: DatagramPacket) {
        try {
            val text = String(packet.data, 0, packet.length)
            val message = json.decodeFromString(NetworkMessage.serializer(), text)
            val senderIp = packet.address.hostAddress ?: return
            when (message) {
                is NetworkMessage.Discover -> {
                    // 回复
                    val reply = NetworkMessage.DiscoverResponse(buildDeviceInfo(), DISCOVERY_PORT)
                    val replyBytes = json.encodeToString(NetworkMessage.serializer(), reply).toByteArray()
                    try {
                        udpSocket?.send(DatagramPacket(replyBytes, replyBytes.size, packet.address, packet.port))
                    } catch (_: Exception) {}
                    // 对方也可能是设备
                    upsertFromInfo(message.info, senderIp)
                }
                is NetworkMessage.DiscoverResponse -> {
                    upsertFromInfo(message.info, senderIp)
                }
                else -> { /* 其他消息类型在发现阶段忽略 */ }
            }
        } catch (e: Exception) {
            Log.v(TAG, "bad packet: ${e.message}")
        }
    }

    private fun upsertFromInfo(info: DeviceInfo, ip: String) {
        if (info.id == settings.deviceId) return // 忽略自己
        val device = Device(
            id = info.id,
            name = info.name,
            address = ip,
            port = DISCOVERY_PORT,
            deviceType = info.deviceType,
            capabilities = info.capabilities,
            lastSeen = System.currentTimeMillis(),
            isKnown = settings.isKnownDevice(info.id),
            avatarHash = info.avatarHash,
        )
        devicesById[info.id] = device
        publish()
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        var changed = false
        val it = devicesById.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.lastSeen > DEVICE_TTL_MS) {
                it.remove()
                changed = true
            }
        }
        if (changed) publish()
    }

    private fun publish() {
        _devices.value = devicesById.values.sortedWith(
            compareByDescending<Device> { it.isOnline }.thenBy { it.name }
        )
    }
}
