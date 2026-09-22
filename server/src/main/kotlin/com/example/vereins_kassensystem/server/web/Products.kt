package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.db.execute
import com.example.vereins_kassensystem.server.db.query
import com.example.vereins_kassensystem.server.db.queryOne
import com.example.vereins_kassensystem.ui.format.Money
import java.sql.Connection
import java.util.UUID

class VariantLine(val id: UUID, val name: String, val price: Double, val servingSize: Double?)

class ComponentLine(val id: UUID, val stockItemId: UUID, val stockItem: String, val unit: String, val quantityPerUnit: Double)

class ProductLine(
    val id: UUID, val name: String, val price: Double, val category: String, val servingSize: Double,
    val variants: List<VariantLine>, val components: List<ComponentLine>,
    /** Verkäufe der letzten 90 Tage, alle Geräte — damit man sieht, was überhaupt noch läuft. */
    val soldRecently: Int,
) {
    val hasVariants: Boolean get() = variants.isNotEmpty()
}

class StockItemOption(val id: UUID, val name: String, val unit: String)

/**
 * Das Sortiment vom Schreibtisch aus (Konzept 4.1, Phase 2): Produkte, Varianten, Rezepturen,
 * dazu die Mitgliederkategorien mit ihren Limits. Alles davon führt die App auch selbst; hier
 * ist es dieselbe Zeile in derselben synchronisierten Tabelle, geschrieben über
 * [Database.write] — die Tablets holen es beim nächsten Abgleich, und ein Preis, der um 18:00
 * auf dem Tablet und um 18:01 hier geändert wird, folgt der Regel „letzter Schreibvorgang
 * gewinnt" wie zwischen zwei Tablets.
 */
class Products(private val db: Database) {

    fun list(): List<ProductLine> = db.read { c ->
        val variants = c.query("SELECT id, product_id, name, price, serving_size FROM product_variants WHERE NOT deleted ORDER BY name") {
            it.getObject("product_id", UUID::class.java) to VariantLine(it.getObject("id", UUID::class.java), it.getString("name"), it.getDouble("price"), it.getBigDecimal("serving_size")?.toDouble())
        }.groupBy({ it.first }, { it.second })
        val components = c.query(
            """
            SELECT pc.id, pc.product_id, pc.stock_item_id, pc.quantity_per_unit, s.name, s.unit
            FROM product_components pc JOIN stock_items s ON s.id = pc.stock_item_id WHERE NOT pc.deleted AND NOT s.deleted ORDER BY s.name
            """.trimIndent()
        ) {
            it.getObject("product_id", UUID::class.java) to ComponentLine(it.getObject("id", UUID::class.java), it.getObject("stock_item_id", UUID::class.java), it.getString("name"), it.getString("unit"), it.getDouble("quantity_per_unit"))
        }.groupBy({ it.first }, { it.second })
        c.query(
            """
            SELECT p.id, p.name, p.price, p.category, p.serving_size,
                   (SELECT COALESCE(SUM(t.quantity), 0) FROM transactions t
                     WHERE t.product_ref = p.id AND NOT t.deleted AND NOT t.is_refund AND t.occurred_at > now() - interval '90 days') AS sold
            FROM products p WHERE NOT p.deleted ORDER BY p.category, p.name
            """.trimIndent()
        ) {
            val id = it.getObject("id", UUID::class.java)
            ProductLine(id, it.getString("name"), it.getDouble("price"), it.getString("category"), it.getDouble("serving_size"), variants[id].orEmpty(), components[id].orEmpty(), it.getInt("sold"))
        }
    }

    fun stockItems(): List<StockItemOption> = db.read { c ->
        c.query("SELECT id, name, unit FROM stock_items WHERE NOT deleted ORDER BY name") { StockItemOption(it.getObject("id", UUID::class.java), it.getString("name"), it.getString("unit")) }
    }

    fun create(by: WebUser, name: String, price: String, category: String, servingSize: String): UUID {
        val clean = cleanName(name); val cents = cleanPrice(price); val cat = cleanCategory(category); val size = cleanSize(servingSize) ?: 1.0
        return db.write { c ->
            if (c.queryOne("SELECT 1 FROM products WHERE NOT deleted AND lower(name) = lower(?)", clean) { true } == true) throw AccountProblem("„$clean“ gibt es schon.")
            val id = UUID.fromString(Ids.new())
            c.execute("INSERT INTO products (id, name, price, category, has_variants, serving_size) VALUES (?, ?, ?, ?, false, ?)", id, clean, cents, cat, size)
            AuditLog.record(c, by.id, by.displayName, "product.create", clean, "${euro(cents)} · ${cat.ifBlank { "ohne Kategorie" }}")
            id
        }
    }

    fun update(by: WebUser, id: UUID, name: String, price: String, category: String, servingSize: String) {
        val clean = cleanName(name); val cents = cleanPrice(price); val cat = cleanCategory(category); val size = cleanSize(servingSize) ?: 1.0
        db.write { c ->
            val before = existing(c, id)
            if (c.queryOne("SELECT 1 FROM products WHERE NOT deleted AND id <> ? AND lower(name) = lower(?)", id, clean) { true } == true) throw AccountProblem("„$clean“ gibt es schon.")
            c.execute("UPDATE products SET name = ?, price = ?, category = ?, serving_size = ? WHERE id = ?", clean, cents, cat, size, id)
            val changes = listOfNotNull(
                if (before.name != clean) "vorher „${before.name}“" else null,
                if (before.price != cents) "Preis vorher ${euro(before.price)}" else null,
                if (before.category != cat) "Kategorie vorher „${before.category}“" else null,
            )
            AuditLog.record(c, by.id, by.displayName, "product.update", clean, changes.joinToString(" · ").ifBlank { "Ausschankgröße" })
        }
    }

    /** Aus dem Sortiment nehmen: weich gelöscht, mit Varianten und Rezeptur; die Buchungen behalten den Namen als Schnappschuss. */
    fun retire(by: WebUser, id: UUID) = db.write { c ->
        val before = existing(c, id)
        c.execute("UPDATE products SET deleted = true, deleted_at = now() WHERE id = ?", id)
        c.execute("UPDATE product_variants SET deleted = true, deleted_at = now() WHERE product_id = ? AND NOT deleted", id)
        c.execute("UPDATE product_components SET deleted = true, deleted_at = now() WHERE product_id = ? AND NOT deleted", id)
        AuditLog.record(c, by.id, by.displayName, "product.retire", before.name, "")
    }

    fun addVariant(by: WebUser, productId: UUID, name: String, price: String, servingSize: String) {
        val clean = cleanName(name); val cents = cleanPrice(price); val size = cleanSize(servingSize)
        db.write { c ->
            val product = existing(c, productId)
            if (c.queryOne("SELECT 1 FROM product_variants WHERE NOT deleted AND product_id = ? AND lower(name) = lower(?)", productId, clean) { true } == true) throw AccountProblem("Die Variante „$clean“ gibt es schon.")
            c.execute("INSERT INTO product_variants (id, product_id, name, price, serving_size) VALUES (?, ?, ?, ?, ?)", UUID.fromString(Ids.new()), productId, clean, cents, size)
            c.execute("UPDATE products SET has_variants = true WHERE id = ? AND NOT has_variants", productId)
            AuditLog.record(c, by.id, by.displayName, "variant.create", "${product.name} · $clean", euro(cents))
        }
    }

    fun updateVariant(by: WebUser, productId: UUID, variantId: UUID, name: String, price: String, servingSize: String) {
        val clean = cleanName(name); val cents = cleanPrice(price); val size = cleanSize(servingSize)
        db.write { c ->
            val product = existing(c, productId)
            val before = c.queryOne("SELECT name, price FROM product_variants WHERE id = ? AND product_id = ? AND NOT deleted FOR UPDATE", variantId, productId) { it.getString("name") to it.getDouble("price") }
                ?: throw AccountProblem("Diese Variante gibt es nicht mehr.")
            c.execute("UPDATE product_variants SET name = ?, price = ?, serving_size = ? WHERE id = ?", clean, cents, size, variantId)
            AuditLog.record(c, by.id, by.displayName, "variant.update", "${product.name} · $clean", listOfNotNull(if (before.first != clean) "vorher „${before.first}“" else null, if (before.second != cents) "Preis vorher ${euro(before.second)}" else null).joinToString(" · "))
        }
    }

    fun removeVariant(by: WebUser, productId: UUID, variantId: UUID) = db.write { c ->
        val product = existing(c, productId)
        val name = c.queryOne("SELECT name FROM product_variants WHERE id = ? AND product_id = ? AND NOT deleted", variantId, productId) { it.getString("name") } ?: return@write
        c.execute("UPDATE product_variants SET deleted = true, deleted_at = now() WHERE id = ?", variantId)
        // Ohne Variante verkauft die Kachel wieder zum Grundpreis.
        if (c.queryOne("SELECT 1 FROM product_variants WHERE product_id = ? AND NOT deleted", productId) { true } != true) c.execute("UPDATE products SET has_variants = false WHERE id = ?", productId)
        AuditLog.record(c, by.id, by.displayName, "variant.remove", "${product.name} · $name", "")
    }

    /** Rezeptur: Was ein Verkauf dem Lagerartikel entnimmt, je Einheit der Ausschankgröße. Ein Artikel steht je Produkt einmal. */
    fun setComponent(by: WebUser, productId: UUID, stockItemId: UUID, quantity: String) {
        val amount = quantity.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it < 1000 } ?: throw AccountProblem("Die Menge je Einheit bitte als Zahl größer 0, etwa 0,5.")
        db.write { c ->
            val product = existing(c, productId)
            val item = c.queryOne("SELECT name FROM stock_items WHERE id = ? AND NOT deleted", stockItemId) { it.getString("name") } ?: throw AccountProblem("Diesen Lagerartikel gibt es nicht mehr.")
            val existing = c.queryOne("SELECT id FROM product_components WHERE product_id = ? AND stock_item_id = ? AND NOT deleted FOR UPDATE", productId, stockItemId) { it.getObject("id", UUID::class.java) }
            if (existing != null) c.execute("UPDATE product_components SET quantity_per_unit = ? WHERE id = ?", amount, existing)
            else c.execute("INSERT INTO product_components (id, product_id, stock_item_id, quantity_per_unit) VALUES (?, ?, ?, ?)", UUID.fromString(Ids.new()), productId, stockItemId, amount)
            AuditLog.record(c, by.id, by.displayName, "component.set", "${product.name} · $item", "$amount je Einheit")
        }
    }

    fun removeComponent(by: WebUser, productId: UUID, componentId: UUID) = db.write { c ->
        val product = existing(c, productId)
        val item = c.queryOne("SELECT s.name FROM product_components pc JOIN stock_items s ON s.id = pc.stock_item_id WHERE pc.id = ? AND pc.product_id = ? AND NOT pc.deleted", componentId, productId) { it.getString("name") } ?: return@write
        c.execute("UPDATE product_components SET deleted = true, deleted_at = now() WHERE id = ?", componentId)
        AuditLog.record(c, by.id, by.displayName, "component.remove", "${product.name} · $item", "")
    }

    // ------------------------------------------------------ Mitgliederkategorien

    class CategoryLine(val id: UUID, val name: String, val limit: Double, val members: Int)

    fun categories(): List<CategoryLine> = db.read { c ->
        c.query("SELECT c.id, c.name, c.negative_balance_limit, (SELECT COUNT(*) FROM members m WHERE m.category_id = c.id AND NOT m.deleted) AS members FROM member_categories c WHERE NOT c.deleted ORDER BY c.name") {
            CategoryLine(it.getObject("id", UUID::class.java), it.getString("name"), it.getDouble("negative_balance_limit"), it.getInt("members"))
        }
    }

    fun createCategory(by: WebUser, name: String, limit: String): UUID {
        val clean = cleanName(name); val cents = cleanLimit(limit)
        return db.write { c ->
            if (c.queryOne("SELECT 1 FROM member_categories WHERE NOT deleted AND lower(name) = lower(?)", clean) { true } == true) throw AccountProblem("„$clean“ gibt es schon.")
            val id = UUID.fromString(Ids.new())
            c.execute("INSERT INTO member_categories (id, name, negative_balance_limit) VALUES (?, ?, ?)", id, clean, cents)
            AuditLog.record(c, by.id, by.displayName, "category.create", clean, "Limit ${euro(cents)}")
            id
        }
    }

    fun updateCategory(by: WebUser, id: UUID, name: String, limit: String) {
        val clean = cleanName(name); val cents = cleanLimit(limit)
        db.write { c ->
            val before = c.queryOne("SELECT name, negative_balance_limit FROM member_categories WHERE id = ? AND NOT deleted FOR UPDATE", id) { it.getString("name") to it.getDouble("negative_balance_limit") }
                ?: throw AccountProblem("Diese Kategorie gibt es nicht mehr.")
            if (c.queryOne("SELECT 1 FROM member_categories WHERE NOT deleted AND id <> ? AND lower(name) = lower(?)", id, clean) { true } == true) throw AccountProblem("„$clean“ gibt es schon.")
            c.execute("UPDATE member_categories SET name = ?, negative_balance_limit = ? WHERE id = ?", clean, cents, id)
            AuditLog.record(c, by.id, by.displayName, "category.update", clean, listOfNotNull(if (before.first != clean) "vorher „${before.first}“" else null, if (before.second != cents) "Limit vorher ${euro(before.second)}" else null).joinToString(" · "))
        }
    }

    /** Nur eine Kategorie ohne Mitglieder lässt sich entfernen — sonst stünden die plötzlich ohne Limit da. */
    fun removeCategory(by: WebUser, id: UUID) = db.write { c ->
        val name = c.queryOne("SELECT name FROM member_categories WHERE id = ? AND NOT deleted FOR UPDATE", id) { it.getString("name") } ?: return@write
        val used = c.queryOne("SELECT COUNT(*) AS n FROM members WHERE category_id = ? AND NOT deleted", id) { it.getInt("n") } ?: 0
        if (used > 0) throw AccountProblem("„$name“ hat noch ${count(used, "Mitglied", "Mitglieder")}. Erst umhängen, dann entfernen.")
        c.execute("UPDATE member_categories SET deleted = true, deleted_at = now() WHERE id = ?", id)
        AuditLog.record(c, by.id, by.displayName, "category.remove", name, "")
    }

    // ------------------------------------------------------------ Innenleben

    private class Existing(val name: String, val price: Double, val category: String)

    private fun existing(c: Connection, id: UUID): Existing =
        c.queryOne("SELECT name, price, category FROM products WHERE id = ? AND NOT deleted FOR UPDATE", id) { Existing(it.getString("name"), it.getDouble("price"), it.getString("category")) }
            ?: throw AccountProblem("Dieses Produkt gibt es nicht mehr.")

    private fun cleanName(name: String): String = name.trim().replace(Regex("\\s+"), " ").take(80).ifEmpty { throw AccountProblem("Ein Name fehlt.") }

    private fun cleanCategory(category: String): String = category.trim().replace(Regex("\\s+"), " ").take(40)

    private fun cleanPrice(price: String): Double {
        val value = Money.parse(price.trim()) ?: throw AccountProblem("Den Preis bitte als Zahl, etwa 4,20.")
        if (value < 0 || value > MAX_PRICE) throw AccountProblem("Der Preis muss zwischen 0 und ${euro(MAX_PRICE)} liegen.")
        return Money.cents(value)
    }

    /** Leer heißt: wie das Produkt (Variante) beziehungsweise 1 (Produkt). */
    private fun cleanSize(text: String): Double? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return t.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= 100 } ?: throw AccountProblem("Die Ausschankgröße bitte als Zahl größer 0, etwa 0,5 — oder leer lassen.")
    }

    /** Ein Limit ist ein Minus oder null: „−50“ heißt bis 50 € anschreiben, „0“ heißt kein Anschreiben. */
    private fun cleanLimit(text: String): Double {
        val value = Money.parse(text.trim().replace('−', '-').ifEmpty { "0" }) ?: throw AccountProblem("Das Limit bitte als Zahl, etwa −50 oder 0.")
        if (value > 0) throw AccountProblem("Ein Limit ist ein Minus oder 0 — wie weit der Deckel ins Minus darf.")
        if (value < -MAX_PRICE) throw AccountProblem("So weit ins Minus lässt niemand anschreiben.")
        return Money.cents(value)
    }

    companion object {
        const val MAX_PRICE = 1000.0
    }
}
