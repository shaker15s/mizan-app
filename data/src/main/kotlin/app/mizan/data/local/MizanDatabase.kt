package app.mizan.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ExecutionEntity::class,
        ReceiptEntity::class,
        AuditEventEntity::class,
        ReconciliationEntity::class,
        OrderEntity::class,
        CustomerEntity::class,
        StockEntity::class,
        SyncEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class MizanDatabase : RoomDatabase() {
    abstract fun dao(): MizanDao

    companion object {
        fun create(context: Context): MizanDatabase = Room.databaseBuilder(
            context.applicationContext,
            MizanDatabase::class.java,
            "mizan.db",
        )
            .addMigrations(MIGRATION_1_2)
            .build()
    }
}
