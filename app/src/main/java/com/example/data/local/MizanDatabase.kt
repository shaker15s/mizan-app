package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AuditRecordEntity::class,
        ExecutionRecordEntity::class,
        TrustReceiptEntity::class,
        ErpOrderEntity::class,
        ErpStockEntity::class,
        ErpCustomerEntity::class,
        ReconciliationItemEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MizanDatabase : RoomDatabase() {
    abstract fun mizanDao(): MizanDao

    companion object {
        @Volatile
        private var INSTANCE: MizanDatabase? = null

        fun getDatabase(context: Context): MizanDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MizanDatabase::class.java,
                    "mizan_master.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
