package com.example.vereins_kassensystem.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.vereins_kassensystem.data.dao.CategoryDao
import com.example.vereins_kassensystem.data.dao.MemberDao
import com.example.vereins_kassensystem.data.dao.ProductDao
import com.example.vereins_kassensystem.data.dao.StockEntryDao
import com.example.vereins_kassensystem.data.dao.TransactionDao
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductVariant
import com.example.vereins_kassensystem.data.entity.StockEntry
import com.example.vereins_kassensystem.data.entity.Transaction

@Database(
    entities = [
        Product::class,
        ProductVariant::class,
        Member::class,
        Transaction::class,
        MemberCategory::class,
        StockEntry::class
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun memberDao(): MemberDao
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun stockEntryDao(): StockEntryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Adds bulk-stock tracking, goods receipts and the note on a transaction.
         *
         * Written out properly rather than left to the destructive fallback: these tables
         * hold member balances, which are real money owed to and by real people. Dropping
         * them on an app update would mean asking the Verein to reconstruct every Deckel
         * from memory.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN stockMode TEXT NOT NULL DEFAULT 'PIECE'")
                db.execSQL("ALTER TABLE products ADD COLUMN stockUnit TEXT NOT NULL DEFAULT 'Stk'")
                db.execSQL("ALTER TABLE products ADD COLUMN containerSize REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE products ADD COLUMN containerLoss REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE products ADD COLUMN servingSize REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE products ADD COLUMN fullContainers INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE products ADD COLUMN openContainerRemaining REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE products ADD COLUMN minServingsLevel INTEGER NOT NULL DEFAULT 20")

                db.execSQL("ALTER TABLE product_variants ADD COLUMN servingSize REAL")

                db.execSQL("ALTER TABLE transactions ADD COLUMN note TEXT")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS stock_entries (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        productId INTEGER NOT NULL,
                        productName TEXT NOT NULL,
                        quantity REAL NOT NULL,
                        unitLabel TEXT NOT NULL,
                        totalCost REAL,
                        note TEXT,
                        source TEXT NOT NULL DEFAULT 'MANUAL',
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_entries_productId ON stock_entries(productId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_entries_timestamp ON stock_entries(timestamp)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vereins_kassensystem_db"
                )
                    .addMigrations(MIGRATION_7_8)
                    // Backstop for the pre-7 development versions only; 7 -> 8 now has a
                    // real path and will not drop anyone's balance.
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
