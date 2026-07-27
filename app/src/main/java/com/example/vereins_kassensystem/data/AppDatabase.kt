package com.example.vereins_kassensystem.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.vereins_kassensystem.data.dao.CategoryDao
import com.example.vereins_kassensystem.data.dao.MemberDao
import com.example.vereins_kassensystem.data.dao.ProductDao
import com.example.vereins_kassensystem.data.dao.TransactionDao
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductVariant
import com.example.vereins_kassensystem.data.entity.Transaction
import com.example.vereins_kassensystem.BuildConfig

@Database(
    entities = [Product::class, ProductVariant::class, Member::class, Transaction::class, MemberCategory::class],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun memberDao(): MemberDao
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                if (BuildConfig.DEBUG) {
                    // context.deleteDatabase("vereins_kassensystem_db") // Uncomment if you want to reset DB every time in debug
                }
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vereins_kassensystem_db"
                )
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
