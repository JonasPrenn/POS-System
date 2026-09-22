package com.example.vereins_kassensystem.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.vereins_kassensystem.data.entity.Transaction
import kotlinx.coroutines.flow.Flow

/** Buchungen werden nur angefügt. Eine Korrektur ist eine neue Zeile, kein Ändern und kein Löschen. */
@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions WHERE deleted = 0 ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<Transaction>>

    @Insert
    suspend fun insertTransaction(transaction: Transaction)
}
