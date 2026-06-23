package com.aipay.listener.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aipay.listener.R
import com.aipay.listener.data.ApiClient
import com.aipay.listener.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 前台保活服务。
 *
 * 启动时：
 * 1. 创建 RealtimeClient，通过 Supabase Realtime 与网页「支付监听」页通信
 * 2. 启动 10s 定时 HTTP 心跳，让「演示页」秒级感知手机在线状态
 */
class KeepAliveService : Service() {
    private var realtimeClient: RealtimeClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("AiPay转发器运行中")
                .setContentText("正在保持后台运行，到账通知由系统通知监听权限捕获")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )

        // 启动 Realtime 连接（与网页端「支付监听」页实时通信）
        val settingsRepository = SettingsRepository(this)
        realtimeClient = RealtimeClient(this, settingsRepository).also {
            it.start()
            RealtimeClient.instance = it  // 注册单例，供 PayNotificationListener 使用
        }

        // 启动 HTTP 心跳：每 10s 上报一次，让「演示页」通过 KV 秒级检测手机在线
        scope.launch {
            val apiClient = ApiClient()
            while (true) {
                try {
                    val settings = settingsRepository.settings.first()
                    if (settings.apiKey.isNotBlank()) {
                        val uptime = System.currentTimeMillis() / 1000
                        apiClient.heartbeat(settings, uptime)
                    }
                } catch (_: Exception) { /* 心跳失败不影响主功能 */ }
                delay(10_000) // 10s 间隔
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        realtimeClient?.stop()
        realtimeClient = null
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "监听保活", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "aipay_keep_alive"
        const val NOTIFICATION_ID = 1001
    }
}
