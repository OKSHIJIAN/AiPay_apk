package com.aipay.listener.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aipay.listener.data.PayRepository

class RetryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val logId = inputData.getLong(KEY_LOG_ID, -1L)
        val attempt = inputData.getInt(KEY_ATTEMPT, 1)
        if (logId <= 0) return Result.failure()

        val repository = PayRepository(applicationContext)
        val ok = repository.report(logId, retryCount = attempt)
        if (!ok && attempt < 3) repository.enqueueRetry(logId, attempt + 1)
        return Result.success()
    }

    companion object {
        const val KEY_LOG_ID = "log_id"
        const val KEY_ATTEMPT = "attempt"
    }
}
