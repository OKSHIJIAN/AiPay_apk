package com.aipay.listener.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "payment_logs")
data class PaymentLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val channel: String,
    val amount: Double,
    val status: String,
    val title: String,
    val text: String,
    val raw: String,
    val serverResponse: String = "",
    val retryCount: Int = 0
)

object LogStatus {
    const val REPORTING = "上报中"
    const val SUCCESS = "成功"
    const val FAILED = "失败"
    const val UNMATCHED = "未匹配"
}
