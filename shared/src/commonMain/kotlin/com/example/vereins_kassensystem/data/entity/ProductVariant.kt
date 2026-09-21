package com.example.vereins_kassensystem.data.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.vereins_kassensystem.platform.Ids

// Seit Schema 11 ohne Fremdschlüssel: Nach einer Änderung bekommt das Produkt auf dem
// Server eine neue Sequenznummer und kann beim Ziehen nach seiner Variante ankommen.
@Entity(
    tableName = "product_variants",
    indices = [Index("productId")]
)
data class ProductVariant(
    @PrimaryKey val id: String = Ids.new(),
    val productId: String,
    val name: String, // e.g., "0.3l", "0.5l"
    val price: Double,

    /**
     * How much of the product's stock one of these draws.
     *
     * For draught beer this is the glass size in litres: a "0,3l" variant draws 0.33,
     * a "0,5l" draws 0.5. Null falls back to the product's own serving size, which is
     * what piece-counted products want.
     */
    val servingSize: Double? = null,

    @Embedded val sync: SyncMeta = SyncMeta()
)
