package com.aipay.listener.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {
    @Insert
    suspend fun insert(log: PaymentLog): Long

    @Query("SELECT * FROM payment_logs WHERE id = :id")
    suspend fun getById(id: Long): PaymentLog?

    @Query("SELECT * FROM payment_logs ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<PaymentLog>>

    @Query("SELECT * FROM payment_logs ORDER BY timestamp DESC")
    fun all(): Flow<List<PaymentLog>>

    @Query("SELECT COUNT(*) FROM payment_logs WHERE timestamp >= :since")
    fun capturedCount(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM payment_logs WHERE timestamp >= :since AND status = :status")
    fun countByStatus(since: Long, status: String): Flow<Int>

    @Query("UPDATE payment_logs SET status = :status, serverResponse = :response, retryCount = :retryCount WHERE id = :id")
    suspend fun updateResult(id: Long, status: String, response: String, retryCount: Int)

    // ── 一次性查询（供 RealtimeClient 使用）──
    @Query("SELECT * FROM payment_logs WHERE timestamp >= :since ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSince(since: Long, limit: Int): List<PaymentLog>

    @Query("SELECT COUNT(*) FROM payment_logs WHERE timestamp >= :since")
    suspend fun getCapturedCountOnce(since: Long): Int

    @Query("SELECT COUNT(*) FROM payment_logs WHERE timestamp >= :since AND status = :status")
    suspend fun getCountByStatusOnce(since: Long, status: String): Int
}
