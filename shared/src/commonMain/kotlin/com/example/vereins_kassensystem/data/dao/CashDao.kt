package com.example.vereins_kassensystem.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.data.entity.CashMovement
import com.example.vereins_kassensystem.data.entity.CashSession
import kotlinx.coroutines.flow.Flow

/**
 * Was bar in der Lade landete: Barverkäufe nach Rabatt, Aufladungen in bar, Trinkgeld in
 * bar; Stornos ziehen ab. Nur Buchungen dieses Geräts (`local`) — was ein anderes Tablet
 * verkauft hat, liegt in dessen Lade.
 */
internal const val CASH_IN =
    "COALESCE(SUM(CASE WHEN t.paymentType = 'CASH' THEN " +
        "(CASE WHEN t.productId IN ('${Ledger.TOPUP_REF}', '${Ledger.TIP_REF}') THEN t.price * t.quantity " +
        "ELSE t.price * t.quantity - t.discountAmount END) * CASE WHEN t.isRefund THEN -1 ELSE 1 END " +
        "ELSE 0 END), 0)"

@Dao
interface CashDao {

    @Query("SELECT * FROM cash_sessions WHERE closedAt IS NULL AND deleted = 0 ORDER BY openedAt DESC LIMIT 1")
    fun observeOpenSession(): Flow<CashSession?>

    @Query("SELECT * FROM cash_sessions WHERE closedAt IS NULL AND deleted = 0 ORDER BY openedAt DESC LIMIT 1")
    suspend fun openSession(): CashSession?

    @Query("SELECT * FROM cash_sessions WHERE id = :id")
    suspend fun session(id: String): CashSession?

    @Query("SELECT * FROM cash_sessions WHERE deleted = 0 ORDER BY openedAt DESC LIMIT :limit")
    fun recentSessions(limit: Int): Flow<List<CashSession>>

    @Insert suspend fun insertSession(session: CashSession)

    @Update suspend fun updateSession(session: CashSession)

    @Insert suspend fun insertMovement(movement: CashMovement)

    @Query("SELECT * FROM cash_movements WHERE sessionId = :sessionId AND deleted = 0 ORDER BY timestamp")
    fun observeMovements(sessionId: String): Flow<List<CashMovement>>

    @Query("SELECT * FROM cash_movements WHERE sessionId = :sessionId AND deleted = 0 ORDER BY timestamp")
    suspend fun movements(sessionId: String): List<CashMovement>

    @Query("SELECT $CASH_IN FROM transactions t WHERE t.deleted = 0 AND t.local = 1 AND t.timestamp >= :since AND t.timestamp < :until")
    suspend fun cashInBetween(since: Long, until: Long): Double

    @Query("SELECT $CASH_IN FROM transactions t WHERE t.deleted = 0 AND t.local = 1 AND t.timestamp >= :since")
    fun observeCashInSince(since: Long): Flow<Double>
}
