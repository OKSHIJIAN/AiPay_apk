package com.aipay.listener.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
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

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        payRepository = PayRepository(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val channel = packageToChannel(sbn.packageName) ?: return

        scope.launch {
            val settings = settingsRepository.settings.first()
            if (!settings.monitoringEnabled) {
                Log.w("AiPay", "通知到达但监听未开启 channel=$channel")
                return@launch
            }
            if (channel == "wechat" && !settings.listenWechat) {
                Log.w("AiPay", "微信监听已关闭，跳过")
                return@launch
            }
            if (channel == "alipay" && !settings.listenAlipay) {
                Log.w("AiPay", "支付宝监听已关闭，跳过")
                return@launch
            }

            val extras = sbn.notification.extras
            val title = extras.textValue(Notification.EXTRA_TITLE)
            val text = extras.textValue(Notification.EXTRA_TEXT)
            val bigText = extras.textValue(Notification.EXTRA_BIG_TEXT)
            val raw = buildRawNotification(sbn.packageName, extras)

            Log.d("AiPay", "=== 通知到达 === channel=$channel | title=$title | text=$text")
            Log.d("AiPay", "raw=$raw")

            val displayText = text.ifBlank { bigText }

            // 调试模式：无条件记入日志（金额为 0 表示未匹配），方便排查
            if (settings.debugMode) {
                payRepository.createDebugLog(channel, title, displayText, raw)
            }

            val amount = AmountParser.parseAmount(channel, raw, settings.minAmount)
            Log.d("AiPay", "解析结果: amount=$amount | debugMode=${settings.debugMode} | minAmount=${settings.minAmount}")

            if (amount == null) {
                return@launch
            }

            val logId = payRepository.createLog(channel, amount, title, displayText, raw)
            val ok = payRepository.report(logId)
            if (!ok) payRepository.enqueueRetry(logId, attempt = 1)
        }
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
