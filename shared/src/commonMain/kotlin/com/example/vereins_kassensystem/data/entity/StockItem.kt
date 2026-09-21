package com.example.vereins_kassensystem.data.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.platform.nowMillis

/*
 * Die Tabellenzeilen des Kellers. Was die Oberfläche sieht — StockItem, ContainerType,
 * TappedContainer mit ihren hergeleiteten Zahlen — sind die Lesemodelle in :core
 * (StockModels.kt); diese Zeilen hier tragen nur noch, was wirklich gespeichert wird.
 *
 * Ohne Fremdschlüssel, wie alle Tabellen seit Schema 11: Beim Ziehen kann ein Kind vor
 * seiner Elternzeile ankommen, und gelöscht wird nur noch weich.
 */

@Entity(tableName = "stock_items")
data class StockItemRow(
    @PrimaryKey val id: String = Ids.new(),
    val name: String,
    val unit: String = "Stk",
    val tracking: StockTracking = StockTracking.SIMPLE,
    val minLevel: Double = 0.0,
    @Embedded val sync: SyncMeta = SyncMeta()
)

@Entity(
    tableName = "container_types",
    indices = [Index("stockItemId")]
)
data class ContainerTypeRow(
    @PrimaryKey val id: String = Ids.new(),
    val stockItemId: String,
    val label: String,
    val nominalSize: Double,
    val initialYieldEstimate: Double,
    @Embedded val sync: SyncMeta = SyncMeta()
)

@Entity(
    tableName = "tapped_containers",
    indices = [Index("containerTypeId"), Index("openedAt")]
)
data class TappedContainerRow(
    @PrimaryKey val id: String = Ids.new(),
    val containerTypeId: String,
    val openedAt: Long = nowMillis(),
    val closedAt: Long? = null,
    val closeReason: ContainerCloseReason? = null,
    val discardedVolume: Double = 0.0,
    val note: String? = null,
    @Embedded val sync: SyncMeta = SyncMeta()
)

/**
 * One line of a product's recipe: how much of a [StockItem] one unit of the product uses.
 *
 * Quantities are **per unit of serving**, and the variant's serving size scales them, so
 * a Radler is defined once as half beer and half soda and every glass size follows:
 *
 * ```
 * Radler = 0,5 Bier + 0,5 Soda
 *   0,3 l glass -> 0,15 Bier + 0,15 Soda
 *   0,5 l glass -> 0,25 Bier + 0,25 Soda
 * ```
 *
 * A plain product is simply a one-line recipe: Bratwurst uses 1 Bratwurst.
 */
@Entity(
    tableName = "product_components",
    indices = [Index("productId"), Index("stockItemId")]
)
data class ProductComponent(
    @PrimaryKey val id: String = Ids.new(),
    val productId: String,
    override val stockItemId: String,

    /** Share of one serving unit taken from this item — 0.5 for half a Radler. */
    override val quantityPerUnit: Double,

    @Embedded val sync: SyncMeta = SyncMeta()
) : RecipeLine

/**
 * Was ein Verkauf dem Keller entnommen hat — anfügend, wie eine Buchung.
 *
 * Früher zog der Verkauf die Menge von einem Zähler ab (`simpleQuantity`, `drawn`). Zwei
 * Geräte, die gleichzeitig zapfen, verlieren so Verbrauch, ohne dass es ein Kassenbon
 * verrät (Spezifikation 2.3). Jetzt entsteht je Rezepturzeile ein Abgang, und der Bestand
 * ist die Summe der Wareneingänge minus die Summe der Abgänge.
 *
 * Die Spezifikation wollte den Verbrauch nachträglich aus Buchung mal Rezeptur herleiten.
 * Das trägt nicht: Die Glasgröße der Variante steht in keiner Buchungszeile, und jede
 * spätere Rezepturänderung schriebe den Verbrauch der Vergangenheit um.
 *
 * Zu welchem angestochenen Gebinde ein Abgang gehört, steht nicht in der Zeile, sondern
 * ergibt sich aus der Zeit: Er zählt zu dem Anstich des Artikels, in dessen Fenster
 * [timestamp] fällt. Verwirft der Server einen doppelten Anstich, zählen die Abgänge des
 * unterlegenen Geräts damit trotzdem zum Fass, das wirklich am Hahn hing.
 */
@Entity(
    tableName = "stock_draws",
    indices = [Index("stockItemId", "timestamp"), Index("transactionId")]
)
data class StockDraw(
    @PrimaryKey val id: String = Ids.new(),
    val stockItemId: String,

    /** Die Buchungszeile, die den Abgang ausgelöst hat; null bei Übernahme und Korrektur. */
    val transactionId: String? = null,

    /** In der Einheit des Lagerartikels. */
    val volume: Double,

    val timestamp: Long = nowMillis(),
    val note: String? = null,
    @Embedded val sync: SyncMeta = SyncMeta()
)
