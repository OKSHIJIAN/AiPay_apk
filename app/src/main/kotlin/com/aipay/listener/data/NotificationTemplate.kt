package com.aipay.listener.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AmountMarkData(
    val sampleText: String = "",
    val markedPositions: List<Int> = emptyList(),
    val leftContext: String = "",
    val rightContext: String = ""
)

@Entity(tableName = "notification_templates")
data class NotificationTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val titleKeyword: String,
    val contentKeyword: String,
    val amountMarked: String = "",
    val enabled: Boolean = true,
    val channelName: String,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getAmountMarkData(): AmountMarkData? {
        if (amountMarked.isBlank()) return null
        return runCatching {
            Json.decodeFromString<AmountMarkData>(amountMarked)
        }.getOrNull()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encodeAmountMarkData(data: AmountMarkData): String =
            json.encodeToString(data)
    }
}
