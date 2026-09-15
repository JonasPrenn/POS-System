package com.example.vereins_kassensystem.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.vereins_kassensystem.platform.nowMillis

@Entity(
    tableName = "transactions",
    indices = [
        Index("memberId"),
        Index("timestamp")
    ]
)
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionGroupId: String, // Group items from same checkout
    val memberId: Long?, // Null for cash sales
    val memberName: String?, // Snapshot of member name at time of transaction
    val productId: Long,
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
    val note: String? = null
)
