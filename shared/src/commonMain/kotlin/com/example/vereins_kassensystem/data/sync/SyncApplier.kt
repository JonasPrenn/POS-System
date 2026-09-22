package com.example.vereins_kassensystem.data.sync

import com.example.vereins_kassensystem.data.AppDatabase
import kotlinx.serialization.json.JsonObject

/**
 * Schreibt, was der Server sagt — ohne dass daraus wieder ein Auftrag an den Server wird.
 *
 * Der einzige Weg in die Datenbank, der am Repository vorbeiführt, und mit Absicht: Eine
 * gezogene Zeile ist keine Änderung dieses Geräts. Sie ersetzt die lokale Zeile ganz,
 * einschließlich der Löschmarke und des `updated_at`, auf dem die nächste eigene Änderung
 * aufsetzt. Was nur dieses Gerät weiß und nie über den Draht ging, bleibt dabei stehen:
 * der Pfad eines Lieferscheinfotos und die Marke `local` einer Buchung — der Server schickt
 * jede hochgeladene Zeile beim nächsten Ziehen zurück, und ohne die Marke zählte die
 * Kassenlade danach ihre eigenen Barverkäufe nicht mehr.
 */
class SyncApplier(database: AppDatabase) {

    private val syncDao = database.syncDao()
    private val deliveryDao = database.deliveryDao()

    suspend fun apply(entity: String, row: JsonObject, deleted: Boolean) {
        when (entity) {
            SyncTables.CATEGORIES -> syncDao.upsertCategory(RowCodec.decodeCategory(row, deleted))
            SyncTables.MEMBERS -> syncDao.upsertMember(RowCodec.decodeMember(row, deleted))
            SyncTables.PRODUCTS -> syncDao.upsertProduct(RowCodec.decodeProduct(row, deleted))
            SyncTables.VARIANTS -> syncDao.upsertVariant(RowCodec.decodeVariant(row, deleted))
            SyncTables.STOCK_ITEMS -> syncDao.upsertStockItem(RowCodec.decodeStockItem(row, deleted))
            SyncTables.CONTAINER_TYPES -> syncDao.upsertContainerType(RowCodec.decodeContainerType(row, deleted))
            SyncTables.COMPONENTS -> syncDao.upsertComponent(RowCodec.decodeComponent(row, deleted))
            SyncTables.DELIVERIES -> {
                // Der Pfad des Fotos gilt nur hier und steht in keiner Serverzeile; er bleibt.
                val decoded = RowCodec.decodeDelivery(row, deleted, photoUri = null)
                val local = deliveryDao.getDelivery(decoded.id)
                val keepsPhoto = local?.photoUri != null && local.photoKey == decoded.photoKey
                syncDao.upsertDelivery(if (keepsPhoto) decoded.copy(photoUri = local?.photoUri) else decoded)
            }
            SyncTables.STOCK_ENTRIES -> syncDao.upsertStockEntry(RowCodec.decodeStockEntry(row, deleted))
            SyncTables.TAPPED -> syncDao.upsertTapped(RowCodec.decodeTapped(row, deleted))
            SyncTables.TRANSACTIONS -> {
                val decoded = RowCodec.decodeTransaction(row, deleted)
                syncDao.upsertTransaction(if (syncDao.isLocalTransaction(decoded.id) == true) decoded.copy(local = true) else decoded)
            }
            SyncTables.STOCK_DRAWS -> syncDao.upsertStockDraw(RowCodec.decodeStockDraw(row, deleted))
            SyncTables.CASH_SESSIONS -> syncDao.upsertCashSession(RowCodec.decodeCashSession(row, deleted))
            SyncTables.CASH_MOVEMENTS -> syncDao.upsertCashMovement(RowCodec.decodeCashMovement(row, deleted))
            // Eine Tabelle, die diese App-Version nicht kennt: übergehen, nicht stolpern.
            else -> Unit
        }
    }
}
