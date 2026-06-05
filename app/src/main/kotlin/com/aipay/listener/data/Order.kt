package com.aipay.listener.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "orders")
data class Order(
    @PrimaryKey val orderId: String,
    val amount: Double,
    val status: String, // pending, paid, expired, cancelled
    val channel: String = "", // wechat, alipay
    val createdAt: Long = System.currentTimeMillis(),
    val paidAt: Long? = null,
    val description: String = "",
    val notifyRaw: String = "" // 匹配到的收款通知原始文本
)

object OrderStatus {
    const val PENDING = "待支付"
    const val PAID = "已支付"
    const val EXPIRED = "已过期"
    const val CANCELLED = "已取消"
}
