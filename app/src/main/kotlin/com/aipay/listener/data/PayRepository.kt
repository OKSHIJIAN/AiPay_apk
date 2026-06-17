package com.aipay.listener.data

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aipay.listener.worker.RetryWorker
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class PayRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(appContext).logDao()
    private val settingsRepository = SettingsRepository(appContext)
    private val api = ApiClient()

    suspend fun createLog(channel: String, amount: Double, title: String, text: String, raw: String): Long =
        dao.insert(
            PaymentLog(
                channel = channel,
                amount = amount,
                status = LogStatus.REPORTING,
                title = title,
                text = text,
                raw = raw
            )
        )

    suspend fun createDebugLog(channel: String, title: String, text: String, raw: String): Long =
        dao.insert(
            PaymentLog(
                channel = channel,
                amount = 0.0,
                status = LogStatus.UNMATCHED,
                title = title,
                text = text,
                raw = raw,
                serverResponse = "调试模式：通知未匹配金额，未上报"
            )
        )

    suspend fun report(logId: Long, retryCount: Int = 0, uptime: Long = 0): Boolean {
        val log = dao.getById(logId) ?: return true
        return runCatching {
            val result = api.notify(settingsRepository.settings.first(), log.amount, log.channel, uptime)
            dao.updateResult(logId, result.status, result.response, retryCount)
            true
        }.getOrElse { error ->
            dao.updateResult(logId, LogStatus.FAILED, error.message ?: error.toString(), retryCount)
            false
        }
    }

    fun enqueueRetry(logId: Long, attempt: Int) {
        val delaySeconds = when (attempt) {
            1 -> 30L
            2 -> 120L
            else -> 600L
        }
        val request = OneTimeWorkRequestBuilder<RetryWorker>()
            .setInputData(workDataOf(RetryWorker.KEY_LOG_ID to logId, RetryWorker.KEY_ATTEMPT to attempt))
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            "retry-report-$logId-$attempt",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
