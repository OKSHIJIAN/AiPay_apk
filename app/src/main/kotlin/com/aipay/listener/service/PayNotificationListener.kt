package com.aipay.listener.service

import android.app.Notification
import android.os.Bundle
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
        Log.d("AiPay", "=== PayNotificationListener 已创建 ===")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("AiPay", "=== 通知监听服务已连接 ===")
    }

    override fun onListenerDisconnected() {
        Log.d("AiPay", "=== 通知监听服务已断开 ===")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // ★ 第一行日志：在协程之外，确认 onNotificationPosted 是否被调用
        Log.d("AiPay", ">>> onNotificationPosted: pkg=${sbn.packageName} key=${sbn.key}")

        // 打印 extras 中所有 key，帮助诊断
        val allKeys = sbn.notification.extras.keySet().joinToString(", ")
        Log.d("AiPay", ">>> extras keys: [$allKeys]")

        scope.launch {
            try {
                val settings = settingsRepository.settings.first()
                Log.d("AiPay", ">>> monitoringEnabled=${settings.monitoringEnabled} listenWechat=${settings.listenWechat}")

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

                // 1. 先尝试内置微信/支付宝渠道匹配
                val channel = packageToChannel(sbn.packageName)
                if (channel != null) {
                    if (channel == "wechat" && !settings.listenWechat) {
                        Log.d("AiPay", ">>> 跳过：listenWechat=false")
                        return@launch
                    }
                    if (channel == "alipay" && !settings.listenAlipay) {
                        Log.d("AiPay", ">>> 跳过：listenAlipay=false")
                        return@launch
                    }

                    Log.d("AiPay", "=== 通知到达(内置渠道) === channel=$channel | package=${sbn.packageName} | title=$title | text=$text")
                    Log.d("AiPay", "=== raw notification:\n$raw")

                    payRepository.createDebugLog(channel, title, displayText, raw)

                    val amount = AmountParser.parseAmount(channel, raw, settings.minAmount)
                    Log.d("AiPay", "=== parseAmount result: $amount (minAmount=${settings.minAmount})")
                    if (amount != null) {
                        handleMatched(channel, amount, title, displayText, raw)
                    }
                    return@launch
                }

                // 2. 尝试用户自定义模板匹配
                val templates = templateDao.getEnabledOnce()
                if (templates.isEmpty()) {
                    Log.d("AiPay", ">>> 跳过：无自定义模板且包名不匹配内置渠道")
                    return@launch
                }

                Log.d("AiPay", "=== 通知到达(模板匹配) === package=${sbn.packageName} | title=$title | templates=${templates.size}个")

                for (template in templates) {
                    if (!matchesTemplate(title, displayText, template)) continue

                    Log.d("AiPay", "模板匹配成功: ${template.name} (${template.channelName})")

                    payRepository.createDebugLog(template.channelName, title, displayText, raw)

                    val amount = AmountParser.parseAmountWithMarking(
                        raw = raw,
                        amountMarked = template.amountMarked,
                        minAmount = settings.minAmount
                    )

                    if (amount != null) {
                        Log.d("AiPay", "模板金额解析: amount=$amount")
                        handleMatched(template.channelName, amount, title, displayText, raw)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("AiPay", "onNotificationPosted 异常", e)
            }
        }
    }

    private suspend fun handleMatched(channel: String, amount: Double, title: String, displayText: String, raw: String) {
        val logId = payRepository.createLog(channel, amount, title, displayText, raw)
        val ok = payRepository.report(logId)
        if (!ok) payRepository.enqueueRetry(logId, attempt = 1)
    }

    private fun matchesTemplate(title: String, text: String, template: com.aipay.listener.data.NotificationTemplate): Boolean {
        // 标题关键词匹配（必填）
        if (template.titleKeyword.isNotBlank()) {
            val keywords = template.titleKeyword.split(",", "，").map { it.trim() }.filter { it.isNotBlank() }
            if (keywords.isEmpty()) return false
            if (keywords.none { title.contains(it) }) return false
        } else {
            return false
        }

        // 内容关键词匹配（非必填，仅当配置了才匹配）
        if (template.contentKeyword.isNotBlank()) {
            val keywords = template.contentKeyword.split(",", "，").map { it.trim() }.filter { it.isNotBlank() }
            if (keywords.isNotEmpty() && keywords.none { text.contains(it) }) return false
        }

        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

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

        // 提取 MessagingStyle 消息（微信聚合通知的关键数据源）
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

    /** 提取 MessagingStyle 消息中的文本字段（微信聚合通知的关键数据源） */
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
    }
}
