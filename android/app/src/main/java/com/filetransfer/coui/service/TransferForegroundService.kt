package com.filetransfer.coui.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.filetransfer.coui.MainActivity
import com.filetransfer.coui.R
import com.filetransfer.coui.network.DiscoveryService
import com.filetransfer.coui.transfer.TransferEngine
import com.filetransfer.coui.transfer.TransferStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 前台常驻服务：保证退到后台/锁屏时仍能持续发现设备并接收文件。
 */
class TransferForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationManager: NotificationManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(null))
        runCatching { DiscoveryService.get(this).start() }
        runCatching { TransferEngine.get().start() }
        observeTransfers()
        return START_STICKY
    }

    private fun observeTransfers() {
        val store = runCatching { TransferStore.get() }.getOrNull() ?: return
        scope.launch {
            store.transfers.collectLatest { list ->
                val active = list.filter { it.isActive }
                val text = if (active.isEmpty()) {
                    "正在局域网待命，可接收文件"
                } else {
                    val total = active.sumOf { it.progress.toDouble() } / active.size
                    "正在传输 ${active.size} 个文件 · ${(total * 100).toInt()}%"
                }
                notificationManager?.notify(NOTIFICATION_ID, buildNotification(text))
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mosend 传输服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "保持文件传输与设备发现在后台运行"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_transfer)
            .setContentTitle("Mosend")
            .setContentText(statusText ?: "正在局域网待命，可接收文件")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "ft_foreground"
        private const val NOTIFICATION_ID = 1001
    }
}
