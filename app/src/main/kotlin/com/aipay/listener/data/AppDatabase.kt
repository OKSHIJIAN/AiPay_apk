package com.aipay.listener.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PaymentLog::class, Order::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
    abstract fun orderDao(): OrderDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS orders (
                        orderId TEXT NOT NULL PRIMARY KEY,
                        amount REAL NOT NULL,
                        status TEXT NOT NULL,
                        channel TEXT DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        paidAt INTEGER,
                        description TEXT DEFAULT '',
                        notifyRaw TEXT DEFAULT ''
                    )
                """)
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aipay-listener.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
