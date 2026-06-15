package com.aipay.listener.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OrderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(order: Order)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(orders: List<Order>)

    @Query("SELECT * FROM orders ORDER BY createdAt DESC")
    fun all(): Flow<List<Order>>

    @Query("SELECT * FROM orders WHERE createdAt >= :since ORDER BY createdAt DESC")
    fun today(since: Long): Flow<List<Order>>

    @Query("SELECT * FROM orders WHERE orderId = :orderId")
    suspend fun getById(orderId: String): Order?

    @Query("UPDATE orders SET status = :status, paidAt = :paidAt, notifyRaw = :notifyRaw WHERE orderId = :orderId")
    suspend fun markPaid(orderId: String, status: String, paidAt: Long, notifyRaw: String)

    @Query("DELETE FROM orders")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM orders WHERE status = 'pending'")
    fun pendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM orders WHERE status = 'paid'")
    fun paidCount(): Flow<Int>
}
