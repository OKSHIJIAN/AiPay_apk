package com.aipay.listener.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountParserTest {
    @Test
    fun parsesAlipayTitleWithoutSeparators() {
        assertEquals(
            9.90,
            AmountParser.parseAmount("alipay", "你已成功收款9.90元（老顾客消费）") ?: -1.0,
            0.001
        )
    }

    @Test
    fun parsesWechatPaymentArrived() {
        assertEquals(
            9.88,
            AmountParser.parseAmount("wechat", "微信支付收款到账9.88元") ?: -1.0,
            0.001
        )
    }

    @Test
    fun parsesWechatGroupedNotificationWithoutCapturingCount() {
        assertEquals(
            9.90,
            AmountParser.parseAmount(
                "wechat",
                "title=微信收款助手\ntext=[4条]微信收款助手: 微信支付收款9.90元(朋友到店)"
            ) ?: -1.0,
            0.001
        )
    }

    @Test
    fun parsesSuccessfulCollectionWithSpaces() {
        assertEquals(
            9.88,
            AmountParser.parseAmount("alipay", "成功收款 9.88 元") ?: -1.0,
            0.001
        )
    }

    @Test
    fun parsesArrivedTransferWithYuanSymbol() {
        assertEquals(
            10.00,
            AmountParser.parseAmount("alipay", "你有一笔转账已到账¥10.00") ?: -1.0,
            0.001
        )
    }

    @Test
    fun ignoresOutboundTransfer() {
        assertNull(AmountParser.parseAmount("alipay", "转账给张三 5.00元"))
    }

    @Test
    fun appliesMinimumAmount() {
        assertNull(AmountParser.parseAmount("wechat", "微信支付收款到账0.50元", minAmount = 1.0))
    }
}
