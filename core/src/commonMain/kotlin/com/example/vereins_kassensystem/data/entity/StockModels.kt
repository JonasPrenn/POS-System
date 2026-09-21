package com.example.vereins_kassensystem.data.entity

import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.platform.nowMillis

/*
 * Der Keller, wie Oberfläche und Bestandsrechnung ihn sehen.
 *
 * Seit Schema 11 sind das keine Tabellenzeilen mehr, sondern Lesemodelle: simpleQuantity,
 * fullCount und drawn werden nicht gespeichert, sondern aus Wareneingängen, Anstichen und
 * Lagerabgängen hergeleitet (Spezifikation 2.3). Die Zeilen selbst stehen in :shared als
 * StockItemRow, ContainerTypeRow und TappedContainerRow; diese Klassen hier füllt die
 * Abfrage in StockDao. Sie liegen in :core, weil [com.example.vereins_kassensystem.data.stock.Inventory]
 * mit ihnen rechnet und der Server dieselbe Rechnung benutzen soll.
 */

/**
 * How a [StockItem] is held.
 *
 * [SIMPLE] is a plain count — sausages in the freezer, pretzels in the basket.
 *
 * [CONTAINER] is held in vessels that are broached and run dry: kegs of beer, kegs of
 * soda. Several sizes can be in the cellar at once, and how much a vessel really gives
 * up is learned rather than assumed. See [ContainerType].
 */
enum class StockTracking { SIMPLE, CONTAINER }

/** Why a vessel stopped being on tap. */
enum class ContainerCloseReason {
    /** Poured out normally. This is a real yield measurement. */
    EMPTIED,

    /**
     * Went off — stood too long, got warm, foamed out. The rest was thrown away.
     *
     * Deliberately **not** a yield measurement: counting a spoiled keg would teach the
     * app that this size only gives up what happened to be poured before it turned, and
     * every future forecast would be short.
     */
    SPOILED
}

/**
 * A Lagerartikel — something the cellar holds, as opposed to something the counter sells.
 *
 * The two were the same thing until now, which could not describe a Radler: it is one
 * product drawn from two different kegs. Products reach stock through a recipe
 * ([RecipeLine]) instead of owning a stock figure themselves.
 */
data class StockItem(
    val id: String = Ids.new(),
    val name: String,

    /** "l", "kg", "Stk" — what quantities of this item are expressed in. */
    val unit: String = "Stk",

    val tracking: StockTracking = StockTracking.SIMPLE,

    /**
     * On hand, for [StockTracking.SIMPLE] items. May go negative.
     *
     * Hergeleitet: Summe der Wareneingänge minus Summe der Lagerabgänge.
     */
    val simpleQuantity: Double = 0.0,

    /** Warn below this, expressed in [unit]. */
    val minLevel: Double = 0.0
)

/**
 * A size of vessel a [StockItem] arrives in — a 50 l keg, a 30 l keg, a 20 l keg.
 *
 * Several sizes coexist for the same item, and each has its own real yield: a 20 l keg
 * loses proportionally more to tapping and residue than a 50 l one, so they cannot share
 * a single loss figure.
 */
data class ContainerType(
    val id: String = Ids.new(),
    val stockItemId: String,

    /** What people call it: "50 l Fass". */
    val label: String,

    /** What it says on the vessel. */
    val nominalSize: Double,

    /**
     * What one is expected to actually give up, before anything has been observed.
     *
     * Used as a prior with weight 1 rather than as the answer — see `Inventory`. Set it
     * roughly; real emptied kegs will outweigh it within a few deliveries.
     */
    val initialYieldEstimate: Double,

    /**
     * Unopened vessels of this size on hand.
     *
     * Hergeleitet: Wareneingänge dieser Größe minus Anstiche.
     */
    val fullCount: Int = 0
)

/**
 * A vessel that has been broached: what has been drawn from it, and how it ended.
 *
 * Closed rows are the app's memory of what vessels of a size really yield.
 */
data class TappedContainer(
    val id: String = Ids.new(),
    val containerTypeId: String,

    /**
     * Volume drawn so far, in the stock item's unit.
     *
     * Hergeleitet: die Lagerabgänge des Artikels im Zeitfenster dieses Anstichs.
     */
    val drawn: Double = 0.0,

    val openedAt: Long = nowMillis(),
    val closedAt: Long? = null,
    val closeReason: ContainerCloseReason? = null,

    /** Estimated volume thrown away, recorded when closed as [ContainerCloseReason.SPOILED]. */
    val discardedVolume: Double = 0.0,

    val note: String? = null
)

/**
 * Ob das Gebinde noch am Hahn hängt. Als Extension statt als Eigenschaft der Klasse: Room
 * liest diese Klasse aus einem anderen Modul und hielte eine berechnete Eigenschaft dort
 * für eine Spalte.
 */
val TappedContainer.isOpen: Boolean get() = closedAt == null

/**
 * One line of a product's recipe: how much of a [StockItem] one unit of the product uses.
 *
 * Als Interface, weil die Zeile selbst (`ProductComponent`) eine Room-Entität in :shared
 * ist und die Bestandsrechnung von ihr nur diese beiden Angaben braucht.
 */
interface RecipeLine {
    val stockItemId: String

    /** Share of one serving unit taken from this item — 0.5 for half a Radler. */
    val quantityPerUnit: Double
}
