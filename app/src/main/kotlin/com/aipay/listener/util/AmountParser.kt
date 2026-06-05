package com.aipay.listener.util

import kotlin.math.round

object AmountParser {
    // 主正则：覆盖标准微信/支付宝通知格式
    val WECHAT_AMOUNT = Regex(
        """(?:微信支付|收款|收到|到账)[\s\S]{0,120}?(?:[¥￥]\s*(\d+(?:\.\d{1,2})?)|(\d+(?:\.\d{1,2})?))(?:\s*元)?"""
    )
    val ALIPAY_AMOUNT = Regex(
        """(?:成功收款|收款|收到|到账)[\s\S]{0,120}?(?:[¥￥]\s*(\d+(?:\.\d{1,2})?)|(\d+(?:\.\d{1,2})?))(?:\s*元)?"""
    )

    // 聚合通知专用正则：处理 "[N条]微信支付: ... ¥X.XX" 格式
    private val WECHAT_AGGREGATE = Regex(
        """\[\d+条\].*?(?:微信支付|收款码.*?到账).*?[¥￥]\s*(\d+(?:\.\d{1,2})?)"""
    )

    private val incomeAmount = Regex(
        """(?:成功收款|微信支付|收款|收到|到账)[\s\S]{0,120}?(?:[¥￥]\s*(\d+(?:\.\d{1,2})?)|(\d+(?:\.\d{1,2})?))(?:\s*元)?"""
    )
    private val outboundKeywords = listOf("转账给", "付款", "支出", "退款")

    fun parseAmount(channel: String, content: String, minAmount: Double = 0.0): Double? {
        val normalizedContent = content.trim()
        if (normalizedContent.isBlank()) return null
        if (outboundKeywords.any { normalizedContent.contains(it) }) return null

        val regex = when (channel) {
            "wechat" -> WECHAT_AMOUNT
            "alipay" -> ALIPAY_AMOUNT
            else -> incomeAmount
        }

        // 尝试主正则
        var raw = regex.find(normalizedContent)?.amountGroup()

        // 主正则失败 → 兜底 incomeAmount 通用正则
        if (raw == null) {
            raw = incomeAmount.find(normalizedContent)?.amountGroup()
        }

        // 微信聚合通知特殊处理：[N条] 前缀的不匹配通用正则，单独处理
        val aggregate = WECHAT_AGGREGATE.find(normalizedContent)?.amountGroup()
        if (aggregate != null) {
            raw = aggregate
        }

        val value = raw?.toDoubleOrNull() ?: return null
        val rounded = round(value * 100.0) / 100.0
        return rounded.takeIf { it >= minAmount }
    }

    fun parseAmountFromParts(title: String?, text: String?, minAmount: Double = 0.0): Double? {
        val content = listOfNotNull(title, text).joinToString("\n")
        return parseAmount("unknown", content, minAmount)
    }

    private fun MatchResult.amountGroup(): String? =
        groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
}
