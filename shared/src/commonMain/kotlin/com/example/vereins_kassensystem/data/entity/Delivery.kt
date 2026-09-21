package com.example.vereins_kassensystem.data.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.platform.nowMillis

/**
 * A delivery — one Kassabon, with its lines and a photo of the receipt itself.
 *
 * Goods receipts used to be single, unrelated rows, which does not match how stock
 * actually arrives: someone comes back from the wholesaler with one receipt covering beer,
 * soda and crisps. Grouping them means the cellar movement and the money leaving the club
 * account are the same record, which is what the financial side needs later.
 *
 * The photo is kept because a booked line is a claim and the receipt is the evidence. When
 * the treasurer asks in November what the 214 euro in July was, the answer should not
 * depend on anyone's memory.
 */
@Entity(
    tableName = "deliveries",
    indices = [Index("timestamp")]
)
data class Delivery(
    @PrimaryKey val id: String = Ids.new(),

    /** Who it came from — "Metro", "Getränke Huber". */
    val supplier: String = "",

    /**
     * What the receipt says in total.
     *
     * Kept separately from the sum of the lines: a receipt often carries things the club
     * does not track as stock, and a total that silently disagrees with the lines is
     * information, not an error to hide.
     */
    val receiptTotal: Double? = null,

    /**
     * Photo of the Kassabon, as a content or file URI. Gilt nur auf dem Gerät, das es
     * aufgenommen hat, und wird deshalb nicht abgeglichen.
     */
    val photoUri: String? = null,

    /** Schlüssel des Fotos auf dem Server (Spezifikation 5.5); damit sehen es auch die anderen Geräte. */
    val photoKey: String? = null,

    val note: String? = null,
    val timestamp: Long = nowMillis(),
    @Embedded val sync: SyncMeta = SyncMeta()
)
