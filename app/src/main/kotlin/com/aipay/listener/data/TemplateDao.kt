package com.aipay.listener.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TemplateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: NotificationTemplate): Long

    @Update
    suspend fun update(template: NotificationTemplate)

    @Delete
    suspend fun delete(template: NotificationTemplate)

    @Query("SELECT * FROM notification_templates ORDER BY createdAt DESC")
    fun all(): Flow<List<NotificationTemplate>>

    @Query("SELECT * FROM notification_templates WHERE enabled = 1 ORDER BY createdAt DESC")
    fun enabled(): Flow<List<NotificationTemplate>>

    @Query("SELECT * FROM notification_templates WHERE enabled = 1 ORDER BY createdAt DESC")
    suspend fun getEnabledOnce(): List<NotificationTemplate>

    @Query("SELECT * FROM notification_templates WHERE id = :id")
    suspend fun getById(id: Long): NotificationTemplate?
}
