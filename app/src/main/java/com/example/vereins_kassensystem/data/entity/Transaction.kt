package com.example.vereins_kassensystem.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val paymentType: String = "CASH", // "CASH", "CARD", "MEMBER_BALANCE"
    val timestamp: Long = System.currentTimeMillis(),
    val isRefund: Boolean = false
)
