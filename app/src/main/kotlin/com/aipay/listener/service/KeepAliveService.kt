package com.aipay.listener.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aipay.listener.R
import com.aipay.listener.data.SettingsRepository

/**
 * 前台保活服务。
 *
 * 启动时创建 RealtimeClient，通过 Supabase Realtime Broadcast
 * 与网页「支付监听」页保持双向通信。
 * 网页发送 request_status → 手机查询本地 Room DB → 返回 phone_data。
 */
class KeepAliveService : Service() {
    private var realtimeClient: RealtimeClient? = null

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

        // 启动 Realtime 连接（与网页端实时通信）
        val settingsRepository = SettingsRepository(this)
        realtimeClient = RealtimeClient(this, settingsRepository).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        realtimeClient?.stop()
        realtimeClient = null
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
