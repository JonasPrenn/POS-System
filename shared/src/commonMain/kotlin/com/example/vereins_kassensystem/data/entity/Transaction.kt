package com.example.vereins_kassensystem.data.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.platform.nowMillis

@Entity(
    tableName = "transactions",
    indices = [
        Index("memberId"),
        Index("timestamp")
    ]
)
data class Transaction(
    @PrimaryKey val id: String = Ids.new(),
    val transactionGroupId: String, // Group items from same checkout
    val memberId: String?, // Null for cash sales
    val memberName: String?, // Snapshot of member name at time of transaction

    /**
     * Das verkaufte Produkt — oder einer der festen Schlüssel aus `Ledger` für
     * Guthabenbewegung, manuellen Betrag und Trinkgeld, die kein Produkt sind.
     */
    val productId: String,
    val productName: String,
    val productCategory: String, // To group by category in analytics
    val price: Double,
    val quantity: Int,
    val discountAmount: Double = 0.0,
    val paymentType: String = "CASH", // "CASH", "CARD", "MEMBER_BALANCE", "TOPUP_*"
    val timestamp: Long = nowMillis(),
    val isRefund: Boolean = false,

    /**
     * Why this booking happened, in the operator's own words.
     *
     * Required for balance top-ups made from the Mitglieder screen: crediting a Deckel
     * outside the till used to change the balance with nothing written down, so money
     * appeared from nowhere and could not be reconciled against the cash box. Every
     * top-up now carries a reason and lands here.
     */
    val note: String? = null,

    /** Von diesem Gerät gebucht — nicht vom Server gezogen. Die Kassenlade zählt nur das. Geht nicht über den Draht. */
    @ColumnInfo(defaultValue = "0") val local: Boolean = false,
    @Embedded val sync: SyncMeta = SyncMeta()
)
