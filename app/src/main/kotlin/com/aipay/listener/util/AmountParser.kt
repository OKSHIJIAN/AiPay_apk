package com.aipay.listener.util

import com.aipay.listener.data.AmountMarkData
import kotlin.math.round
import kotlinx.serialization.json.Json

object AmountParser {
    // 主正则：覆盖标准微信/支付宝通知格式
    val WECHAT_AMOUNT = Regex(
        """(?:微信支付|收款|收到|到账)[\s\S]{0,120}?(?:[¥￥]\s*(\d+(?:\.\d{1,2})?)|(\d+(?:\.\d{1,2})?))(?:\s*元)?"""
    )
    val ALIPAY_AMOUNT = Regex(
        """(?:成功收款|收款|收到|到账)[\s\S]{0,120}?(?:[¥￥]\s*(\d+(?:\.\d{1,2})?)|(\d+(?:\.\d{1,2})?))(?:\s*元)?"""
    )

    // 聚合通知专用正则：处理 "[N条]微信支付: ... ¥X.XX" 或 "[N条]...到账¥X.XX" 格式
    // 同时兼容：有/无 ¥ 符号，有/无 元 后缀
    private val WECHAT_AGGREGATE = Regex(
        """\[\d+条\].*?(?:微信支付|收款码.*?到账|个人收款码到账).*?(?:[¥￥]\s*)?(\d+(?:\.\d{1,2})?)(?:\s*元)?"""
    )

    private val incomeAmount = Regex(
        """(?:成功收款|微信支付|收款|收到|到账)[\s\S]{0,120}?(?:[¥￥]\s*(\d+(?:\.\d{1,2})?)|(\d+(?:\.\d{1,2})?))(?:\s*元)?"""
    )
    private val outboundKeywords = listOf("转账给", "付款", "支出", "退款")

    private val markJson = Json { ignoreUnknownKeys = true }

    fun parseAmount(channel: String, content: String, minAmount: Double = 0.0): Double? {
        val normalizedContent = content.trim()
        if (normalizedContent.isBlank()) return null
        if (outboundKeywords.any { normalizedContent.contains(it) }) return null

        // 【关键修复】先检查聚合通知 "[N条]" 格式，优先提取里面的实际金额
        // 避免把 "[9条]" 中的 "9" 误识别为金额
        val aggregate = WECHAT_AGGREGATE.find(normalizedContent)?.amountGroup()
        if (aggregate != null) {
            val value = aggregate.toDoubleOrNull()
            if (value != null) {
                val rounded = round(value * 100.0) / 100.0
                return rounded.takeIf { it >= minAmount }
            }
        }

        // 剥离 [N条] 前缀后，用主正则匹配
        val cleanedContent = normalizedContent.replace(Regex("""\[\d+条\]"""), "")

        val regex = when (channel) {
            "wechat" -> WECHAT_AMOUNT
            "alipay" -> ALIPAY_AMOUNT
            else -> incomeAmount
        }

        // 尝试主正则
        var raw = regex.find(cleanedContent)?.amountGroup()

        // 主正则失败 → 兜底 incomeAmount 通用正则
        if (raw == null) {
            raw = incomeAmount.find(cleanedContent)?.amountGroup()
        }

        val value = raw?.toDoubleOrNull() ?: return null
        val rounded = round(value * 100.0) / 100.0
        return rounded.takeIf { it >= minAmount }
    }

    fun parseAmountFromParts(title: String?, text: String?, minAmount: Double = 0.0): Double? {
        val content = listOfNotNull(title, text).joinToString("\n")
        return parseAmount("unknown", content, minAmount)
    }

    /**
     * 二段式金额提取：先试通用正则，失败则用金额标记锚点法
     */
    fun parseAmountWithMarking(
        raw: String,
        amountMarked: String,
        minAmount: Double = 0.0
    ): Double? {
        val normalizedContent = raw.trim()
        if (normalizedContent.isBlank()) return null
        if (outboundKeywords.any { normalizedContent.contains(it) }) return null

        // 第一段：尝试内置通用正则
        incomeAmount.find(normalizedContent)?.amountGroup()?.toDoubleOrNull()?.let {
            val rounded = round(it * 100.0) / 100.0
            return rounded.takeIf { v -> v >= minAmount }
        }

        // 第二段：使用金额标记锚点法
        if (amountMarked.isBlank()) return null

        val markData = runCatching {
            markJson.decodeFromString<AmountMarkData>(amountMarked)
        }.getOrNull() ?: return null

        val leftCtx = markData.leftContext.takeIf { it.isNotBlank() } ?: return null
        val rightCtx = markData.rightContext.takeIf { it.isNotBlank() } ?: return null

        // 找到 leftContext 的位置
        val leftIdx = normalizedContent.indexOf(leftCtx)
        if (leftIdx < 0) return null

        // 从 leftContext 之后截取文本
        val afterLeft = normalizedContent.substring(leftIdx + leftCtx.length)

        // 在 leftContext 之后找第一个符合条件的数字串
        val numberRegex = Regex("""(\d+(?:\.\d{1,2})?)""")
        for (match in numberRegex.findAll(afterLeft)) {
            val numStr = match.groupValues[1]
            // 检查数字后面是否紧跟 rightContext（允许中间有少量空白/标点）
            val afterNum = afterLeft.substring(match.range.last + 1).trimStart()
            if (afterNum.startsWith(rightCtx) || afterNum.contains(rightCtx)) {
                val value = numStr.toDoubleOrNull() ?: continue
                val rounded = round(value * 100.0) / 100.0
                return rounded.takeIf { it >= minAmount }
            }
        }

        return null
    }

    private fun MatchResult.amountGroup(): String? =
        groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
}
