package com.aipay.listener.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PaymentLog::class, Order::class, NotificationTemplate::class], version = 4)
abstract class AppDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao
    abstract fun orderDao(): OrderDao
    abstract fun templateDao(): TemplateDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS notification_templates (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL DEFAULT '',
                        packageName TEXT NOT NULL DEFAULT '',
                        titleKeyword TEXT NOT NULL DEFAULT '',
                        contentKeyword TEXT NOT NULL DEFAULT '',
                        amountRegex TEXT NOT NULL DEFAULT '',
                        outboundKeywords TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        channelName TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 重建表：去掉 packageName / amountRegex / outboundKeywords，新增 amountMarked
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS notification_templates_new (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL DEFAULT '',
                        titleKeyword TEXT NOT NULL DEFAULT '',
                        contentKeyword TEXT NOT NULL DEFAULT '',
                        amountMarked TEXT NOT NULL DEFAULT '',
                        enabled INTEGER NOT NULL DEFAULT 1,
                        channelName TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("""
                    INSERT INTO notification_templates_new 
                    (id, name, titleKeyword, contentKeyword, enabled, channelName, createdAt)
                    SELECT id, name, titleKeyword, contentKeyword, enabled, channelName, createdAt 
                    FROM notification_templates
                """)
                db.execSQL("DROP TABLE notification_templates")
                db.execSQL("ALTER TABLE notification_templates_new RENAME TO notification_templates")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aipay-listener.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
    }
}
