package com.example.vereins_kassensystem.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.vereins_kassensystem.data.dao.CategoryDao
import com.example.vereins_kassensystem.data.dao.MemberDao
import com.example.vereins_kassensystem.data.dao.ProductDao
import com.example.vereins_kassensystem.data.dao.StockDao
import com.example.vereins_kassensystem.data.dao.StockEntryDao
import com.example.vereins_kassensystem.data.dao.TransactionDao
import com.example.vereins_kassensystem.data.entity.ContainerType
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.data.entity.ProductComponent
import com.example.vereins_kassensystem.data.entity.ProductVariant
import com.example.vereins_kassensystem.data.entity.StockEntry
import com.example.vereins_kassensystem.data.entity.StockItem
import com.example.vereins_kassensystem.data.entity.TappedContainer
import com.example.vereins_kassensystem.data.entity.Transaction

@Database(
    entities = [
        Product::class,
        ProductVariant::class,
        Member::class,
        Transaction::class,
        MemberCategory::class,
        StockEntry::class,
        StockItem::class,
        ContainerType::class,
        TappedContainer::class,
        ProductComponent::class
    ],
    version = 9,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun memberDao(): MemberDao
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun stockEntryDao(): StockEntryDao
    abstract fun stockDao(): StockDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Bulk stock, goods receipts and the note on a transaction. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE products ADD COLUMN stockMode TEXT NOT NULL DEFAULT 'PIECE'")
                db.execSQL("ALTER TABLE products ADD COLUMN stockUnit TEXT NOT NULL DEFAULT 'Stk'")
                db.execSQL("ALTER TABLE products ADD COLUMN containerSize REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE products ADD COLUMN containerLoss REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE products ADD COLUMN servingSize REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE products ADD COLUMN fullContainers INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE products ADD COLUMN openContainerRemaining REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE products ADD COLUMN minServingsLevel INTEGER NOT NULL DEFAULT 20")
                db.execSQL("ALTER TABLE product_variants ADD COLUMN servingSize REAL")
                db.execSQL("ALTER TABLE transactions ADD COLUMN note TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS stock_entries (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        productId INTEGER NOT NULL, productName TEXT NOT NULL,
                        quantity REAL NOT NULL, unitLabel TEXT NOT NULL, totalCost REAL,
                        note TEXT, source TEXT NOT NULL DEFAULT 'MANUAL', timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_entries_productId ON stock_entries(productId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_entries_timestamp ON stock_entries(timestamp)")
            }
        }

        /**
         * Splits Produkt from Lagerartikel, so one product can be drawn from several.
         *
         * Every existing product gets a stock item of the same name **carrying the same
         * id**, plus a one-to-one recipe. That means the Verein is running again the
         * moment the app updates, with only the genuinely mixed items — beer, soda,
         * Radler — left to set up by hand. Matching on the id rather than the name also
         * survives two products being called the same thing.
         *
         * Products lose their stock columns entirely rather than keeping them around
         * unused: two competing stock numbers on one row is exactly the kind of thing
         * that quietly disagrees six months later.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rebuilding `products` re-points the variants foreign key; defer it so
                // the drop does not cascade the variants away mid-migration.
                db.execSQL("PRAGMA defer_foreign_keys = TRUE")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS stock_items (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL, unit TEXT NOT NULL,
                        tracking TEXT NOT NULL, simpleQuantity REAL NOT NULL, minLevel REAL NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS container_types (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        stockItemId INTEGER NOT NULL, label TEXT NOT NULL,
                        nominalSize REAL NOT NULL, initialYieldEstimate REAL NOT NULL,
                        fullCount INTEGER NOT NULL,
                        FOREIGN KEY(stockItemId) REFERENCES stock_items(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_container_types_stockItemId ON container_types(stockItemId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tapped_containers (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        containerTypeId INTEGER NOT NULL, drawn REAL NOT NULL,
                        openedAt INTEGER NOT NULL, closedAt INTEGER, closeReason TEXT,
                        discardedVolume REAL NOT NULL, note TEXT,
                        FOREIGN KEY(containerTypeId) REFERENCES container_types(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tapped_containers_containerTypeId ON tapped_containers(containerTypeId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tapped_containers_openedAt ON tapped_containers(openedAt)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS product_components (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        productId INTEGER NOT NULL, stockItemId INTEGER NOT NULL,
                        quantityPerUnit REAL NOT NULL,
                        FOREIGN KEY(productId) REFERENCES products(id) ON DELETE CASCADE,
                        FOREIGN KEY(stockItemId) REFERENCES stock_items(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_product_components_productId ON product_components(productId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_product_components_stockItemId ON product_components(stockItemId)")

                // One stock item per product, same id, carrying over what it had.
                db.execSQL(
                    """
                    INSERT INTO stock_items (id, name, unit, tracking, simpleQuantity, minLevel)
                    SELECT id, name,
                           CASE WHEN stockMode = 'BULK' THEN stockUnit ELSE 'Stk' END,
                           CASE WHEN stockMode = 'BULK' THEN 'CONTAINER' ELSE 'SIMPLE' END,
                           CASE WHEN stockMode = 'BULK' THEN 0.0 ELSE stockQuantity END,
                           CASE WHEN stockMode = 'BULK'
                                THEN minServingsLevel * (CASE WHEN servingSize > 0 THEN servingSize ELSE 1.0 END)
                                ELSE minStockLevel END
                    FROM products
                    """.trimIndent()
                )

                // Draught products keep their keg size; the old fixed loss becomes the
                // starting estimate that real measurements will replace.
                db.execSQL(
                    """
                    INSERT INTO container_types (stockItemId, label, nominalSize, initialYieldEstimate, fullCount)
                    SELECT id,
                           CAST(CAST(containerSize AS INTEGER) AS TEXT) || ' ' || stockUnit,
                           containerSize,
                           MAX(containerSize - containerLoss, 0.0),
                           fullContainers
                    FROM products
                    WHERE stockMode = 'BULK' AND containerSize > 0
                    """.trimIndent()
                )

                // A keg already on tap keeps what was left in it.
                db.execSQL(
                    """
                    INSERT INTO tapped_containers (containerTypeId, drawn, openedAt, discardedVolume)
                    SELECT c.id, MAX(c.initialYieldEstimate - p.openContainerRemaining, 0.0),
                           ${System.currentTimeMillis()}, 0.0
                    FROM products p JOIN container_types c ON c.stockItemId = p.id
                    WHERE p.stockMode = 'BULK' AND p.openContainerRemaining <> 0.0
                    """.trimIndent()
                )

                // Every product gets a one-to-one recipe against its own new item.
                db.execSQL(
                    """
                    INSERT INTO product_components (productId, stockItemId, quantityPerUnit)
                    SELECT id, id, 1.0 FROM products
                    """.trimIndent()
                )

                // Goods receipts move from products to stock items; the ids line up.
                db.execSQL(
                    """
                    CREATE TABLE stock_entries_new (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        stockItemId INTEGER NOT NULL, itemName TEXT NOT NULL,
                        quantity REAL NOT NULL, unitLabel TEXT NOT NULL, totalCost REAL,
                        note TEXT, source TEXT NOT NULL DEFAULT 'MANUAL', timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO stock_entries_new (id, stockItemId, itemName, quantity, unitLabel, totalCost, note, source, timestamp)
                    SELECT id, productId, productName, quantity, unitLabel, totalCost, note, source, timestamp FROM stock_entries
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE stock_entries")
                db.execSQL("ALTER TABLE stock_entries_new RENAME TO stock_entries")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_entries_stockItemId ON stock_entries(stockItemId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_entries_timestamp ON stock_entries(timestamp)")

                // Products shed their stock columns.
                db.execSQL(
                    """
                    CREATE TABLE products_new (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL, price REAL NOT NULL, category TEXT NOT NULL,
                        imageUrl TEXT, hasVariants INTEGER NOT NULL, servingSize REAL NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO products_new (id, name, price, category, imageUrl, hasVariants, servingSize)
                    SELECT id, name, price, category, imageUrl, hasVariants,
                           CASE WHEN servingSize > 0 THEN servingSize ELSE 1.0 END
                    FROM products
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE products")
                db.execSQL("ALTER TABLE products_new RENAME TO products")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vereins_kassensystem_db"
                )
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9)
                    // Backstop for pre-7 development builds only; 7 -> 8 -> 9 have real
                    // paths and will not drop anyone's balance.
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
