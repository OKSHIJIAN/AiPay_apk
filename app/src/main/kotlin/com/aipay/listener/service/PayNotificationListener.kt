package com.aipay.listener.service

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.aipay.listener.data.AppDatabase
import com.aipay.listener.data.PayRepository
import com.aipay.listener.data.SettingsRepository
import com.aipay.listener.util.AmountParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PayNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var payRepository: PayRepository
    private lateinit var templateDao: com.aipay.listener.data.TemplateDao

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        payRepository = PayRepository(this)
        templateDao = AppDatabase.get(this).templateDao()
        isServiceCreated = true
        // 某些 ROM 重新授权后只触发 onCreate 不触发 onListenerConnected，在此兜底
        if (!isServiceConnected) {
            isServiceConnected = true
            lastConnectedAt = System.currentTimeMillis()
        }
        Log.d("AiPay", "=== PayNotificationListener 已创建 connected=$isServiceConnected ===")
        DebugLog.i("AiPay", "PayNotificationListener onCreate connected=$isServiceConnected")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isServiceConnected = true
        lastConnectedAt = System.currentTimeMillis()
        Log.d("AiPay", "=== 通知监听服务已连接 ===")
        DebugLog.i("AiPay", "通知监听服务已连接 ✓")
    }

    override fun onListenerDisconnected() {
        isServiceConnected = false
        Log.w("AiPay", "=== 通知监听服务已断开！尝试重新绑定 ===")
        DebugLog.w("AiPay", "通知监听服务已断开！调用 requestRebind…")
        try {
            val ok = requestRebind(ComponentName(this, PayNotificationListener::class.java))
            Log.d("AiPay", "=== requestRebind 结果: $ok")
            DebugLog.i("AiPay", "requestRebind 返回: $ok")
        } catch (e: Exception) {
            Log.e("AiPay", "requestRebind 失败", e)
            DebugLog.e("AiPay", "requestRebind 异常: ${e.message}")
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // 能收到通知说明监听器已连接
        if (!isServiceConnected) {
            isServiceConnected = true
            lastConnectedAt = System.currentTimeMillis()
            Log.d("AiPay", ">>> 通过通知检测到监听器已连接")
        }

        val pkg = sbn.packageName
        Log.d("AiPay", ">>> onNotificationPosted: pkg=$pkg key=${sbn.key}")

        // 打出所有通知（不过滤），帮助诊断
        val allKeys = sbn.notification.extras.keySet().joinToString(", ")
        Log.d("AiPay", ">>> extras keys: [$allKeys]")

        scope.launch {
            try {
                val settings = settingsRepository.settings.first()
                Log.d("AiPay", ">>> monitorEnabled=${settings.monitoringEnabled} wechat=${settings.listenWechat} alipay=${settings.listenAlipay}")

                if (!settings.monitoringEnabled) {
                    Log.d("AiPay", ">>> 跳过：monitoringEnabled=false")
                    return@launch
                }

                val extras = sbn.notification.extras
                val title = extras.textValue(Notification.EXTRA_TITLE)
                val text = extras.textValue(Notification.EXTRA_TEXT)
                val bigText = extras.textValue(Notification.EXTRA_BIG_TEXT)
                val raw = buildRawNotification(sbn.packageName, extras)
                val displayText = text.ifBlank { bigText }

                // 1. 内置微信/支付宝渠道匹配
                val channel = packageToChannel(sbn.packageName)
                if (channel != null) {
                    if (channel == "wechat" && !settings.listenWechat) {
                        DebugLog.i("AiPay", "跳过微信通知（listenWechat=false）")
                        return@launch
                    }
                    if (channel == "alipay" && !settings.listenAlipay) {
                        DebugLog.i("AiPay", "跳过支付宝通知（listenAlipay=false）")
                        return@launch
                    }

                    DebugLog.i("AiPay", "收到${if(channel=="wechat")"微信" else "支付宝"}通知: title=$title text=$text")
                    Log.d("AiPay", "=== raw:\n$raw")

                    payRepository.createDebugLog(channel, title, displayText, raw)

                    val amount = AmountParser.parseAmount(channel, raw, settings.minAmount)
                    Log.d("AiPay", "=== parseAmount: $amount (min=${settings.minAmount})")
                    if (amount != null) {
                        DebugLog.i("AiPay", "金额解析成功: ¥$amount → 上报服务器")
                        handleMatched(channel, amount, title, displayText, raw)
                    } else {
                        DebugLog.w("AiPay", "金额解析失败，通知内容: ${raw.take(200)}")
                    }
                    return@launch
                }

                // 2. 用户自定义模板
                val templates = templateDao.getEnabledOnce()
                if (templates.isEmpty()) {
                    return@launch
                }

                for (template in templates) {
                    if (!matchesTemplate(title, displayText, template)) continue
                    payRepository.createDebugLog(template.channelName, title, displayText, raw)
                    val amount = AmountParser.parseAmountWithMarking(raw, template.amountMarked, settings.minAmount)
                    if (amount != null) {
                        DebugLog.i("AiPay", "模板[${template.name}]金额解析: ¥$amount")
                        handleMatched(template.channelName, amount, title, displayText, raw)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("AiPay", "onNotificationPosted 异常", e)
                DebugLog.e("AiPay", "处理通知异常: ${e.message}")
            }
        }
    }

    private suspend fun handleMatched(channel: String, amount: Double, title: String, displayText: String, raw: String) {
        val logId = payRepository.createLog(channel, amount, title, displayText, raw)
        val uptime = if (lastConnectedAt > 0) (System.currentTimeMillis() - lastConnectedAt) / 1000 else 0
        val ok = payRepository.report(logId, uptime = uptime)
        if (ok) {
            DebugLog.i("AiPay", "上报成功: $channel ¥$amount")
        } else {
            DebugLog.w("AiPay", "上报失败，加入重试队列: $channel ¥$amount")
            payRepository.enqueueRetry(logId, attempt = 1)
        }
    }

    private fun matchesTemplate(title: String, text: String, template: com.aipay.listener.data.NotificationTemplate): Boolean {
        if (template.titleKeyword.isNotBlank()) {
            val keywords = template.titleKeyword.split(",", "，").map { it.trim() }.filter { it.isNotBlank() }
            if (keywords.isEmpty()) return false
            if (keywords.none { title.contains(it) }) return false
        } else {
            return false
        }
        if (template.contentKeyword.isNotBlank()) {
            val keywords = template.contentKeyword.split(",", "，").map { it.trim() }.filter { it.isNotBlank() }
            if (keywords.isNotEmpty() && keywords.none { text.contains(it) }) return false
        }
        return true
    }

    override fun onDestroy() {
        isServiceCreated = false
        isServiceConnected = false
        scope.cancel()
        DebugLog.w("AiPay", "PayNotificationListener onDestroy")
        super.onDestroy()
    }

    // ── 通知原始文本构建（保持不变）──
    private fun buildRawNotification(packageName: String, extras: Bundle): String {
        val parts = mutableListOf("package=$packageName")
        listOf(
            "title" to extras.textValue(Notification.EXTRA_TITLE),
            "text" to extras.textValue(Notification.EXTRA_TEXT),
            "subText" to extras.textValue(Notification.EXTRA_SUB_TEXT),
            "bigText" to extras.textValue(Notification.EXTRA_BIG_TEXT),
            "summaryText" to extras.textValue(Notification.EXTRA_SUMMARY_TEXT)
        ).forEach { (label, value) ->
            if (value.isNotBlank()) parts += "$label=$value"
        }
        extras.textLines(Notification.EXTRA_TEXT_LINES).forEachIndexed { index, line ->
            if (line.isNotBlank()) parts += "textLine[$index]=$line"
        }
        extras.messageBundles(Notification.EXTRA_MESSAGES).forEachIndexed { index, msg ->
            if (msg.isNotBlank()) parts += "message[$index]=$msg"
        }
        extras.messageBundles(Notification.EXTRA_HISTORIC_MESSAGES).forEachIndexed { index, msg ->
            if (msg.isNotBlank()) parts += "historicMessage[$index]=$msg"
        }
        return parts.joinToString("\n")
    }

    private fun Bundle.textValue(key: String): String =
        getCharSequence(key)?.toString().orEmpty()

    private fun Bundle.textLines(key: String): List<String> {
        val raw = get(key) ?: return emptyList()
        return when (raw) {
            is Array<*> -> raw.mapNotNull { it?.toString() }
            is Iterable<*> -> raw.mapNotNull { it?.toString() }
            else -> emptyList()
        }
    }

    private fun Bundle.messageBundles(key: String): List<String> {
        val raw = get(key) ?: return emptyList()
        val array = when (raw) {
            is Array<*> -> raw
            is Iterable<*> -> raw.toList().toTypedArray()
            else -> return emptyList()
        }
        return array.mapNotNull { item ->
            when (item) {
                is Bundle -> item.getCharSequence("text")?.toString()
                is android.app.PendingIntent -> null
                else -> item?.toString()
            }
        }
    }

    companion object {
        @Volatile var isServiceCreated = false
        @Volatile var isServiceConnected = false
        @Volatile var lastConnectedAt: Long = 0

        private val WECHAT_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mm.work"
        )
        private val ALIPAY_PACKAGES = setOf(
            "com.eg.android.AlipayGphone",
            "com.alipay.android.app",
            "hk.alipay.wallet"
        )

        fun packageToChannel(packageName: String): String? = when (packageName) {
            in WECHAT_PACKAGES -> "wechat"
            in ALIPAY_PACKAGES -> "alipay"
            else -> null
        }

        /** 打开系统通知监听设置页（唯一有效的手动修复方式） */
        fun openNotificationSettings(context: android.content.Context) {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                DebugLog.i("AiPay", "已打开系统通知监听设置页，请关闭再开启「AiPay转发器」")
            } catch (e: Exception) {
                DebugLog.e("AiPay", "无法打开通知监听设置: ${e.message}")
            }
        }
    }
}
