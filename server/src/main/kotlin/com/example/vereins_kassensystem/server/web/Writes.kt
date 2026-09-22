package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.db.execute
import com.example.vereins_kassensystem.server.db.query
import com.example.vereins_kassensystem.server.db.queryOne
import com.example.vereins_kassensystem.ui.format.Money
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

class MemberCategoryOption(val id: UUID, val name: String, val limit: Double)

/** Wie eine Aufladung bezahlt wurde. `BANK` kennt nur die Verwaltung: die Überweisung nach der Abrechnung. */
enum class TopUpKind(val label: String, val paymentType: String) {
    BANK("Überweisung", "BANK"), CASH("Bar", "CASH"), CARD("Karte", "CARD")
}

/**
 * Was die Verwaltung in synchronisierte Tabellen schreibt — Mitglieder und Buchungen auf den
 * Deckel. **Alles über [Database.write]**: dieselbe Advisory-Sperre wie beim Abgleich, damit
 * die Sequenznummern in Vergabereihenfolge sichtbar werden und kein Tablet eine Zeile
 * übersieht (CLAUDE.md, „Sequenznummern entstehen unter einer Sperre"). Den Rest macht der
 * Trigger `touch_sync`; die Tablets holen sich die Zeilen beim nächsten Abgleich.
 *
 * Gebucht wird in genau der Form, in der die App bucht (`AppRepository.adjustMemberBalance`):
 * Sentinel-Produkt, Betrag mit Vorzeichen im Preis, Menge 1. Das Protokoll entsteht in
 * derselben Transaktion — eine Buchung ohne Eintrag gibt es nicht.
 */
class Writes(private val db: Database) {

    fun categories(): List<MemberCategoryOption> = db.read { c ->
        c.query("SELECT id, name, negative_balance_limit FROM member_categories WHERE NOT deleted ORDER BY lower(name)") {
            MemberCategoryOption(it.getObject("id", UUID::class.java), it.getString("name"), it.getDouble("negative_balance_limit"))
        }
    }

    fun createMember(by: WebUser, name: String, nickname: String, categoryId: UUID?): UUID {
        val clean = cleanName(name)
        val vulgo = cleanNickname(nickname)
        return db.write { c ->
            if (c.queryOne("SELECT 1 FROM members WHERE NOT deleted AND lower(name) = lower(?)", clean) { true } == true) {
                throw AccountProblem("„$clean“ gibt es schon. Zwei Deckel auf denselben Namen verwechselt an der Theke jeder.")
            }
            val id = UUID.fromString(Ids.new())
            c.execute("INSERT INTO members (id, name, nickname, category_id) VALUES (?, ?, ?, ?)", id, clean, vulgo, existingCategory(c, categoryId))
            AuditLog.record(c, by.id, by.displayName, "member.create", clean)
            id
        }
    }

    fun updateMember(by: WebUser, id: UUID, name: String, nickname: String, categoryId: UUID?) {
        val clean = cleanName(name)
        val vulgo = cleanNickname(nickname)
        db.write { c ->
            val before = c.queryOne("SELECT name FROM members WHERE id = ? AND NOT deleted FOR UPDATE", id) { it.getString("name") }
                ?: throw AccountProblem("Dieses Mitglied gibt es nicht mehr.")
            if (c.queryOne("SELECT 1 FROM members WHERE NOT deleted AND id <> ? AND lower(name) = lower(?)", id, clean) { true } == true) {
                throw AccountProblem("„$clean“ gibt es schon.")
            }
            c.execute("UPDATE members SET name = ?, nickname = ?, category_id = ? WHERE id = ?", clean, vulgo, existingCategory(c, categoryId), id)
            AuditLog.record(c, by.id, by.displayName, "member.update", clean, if (before != clean) "vorher „$before“" else "Kategorie")
        }
    }

    /**
     * Löschen nach Austritt (Konzept 4.1): weich, wie die App es tut — die Tablets nehmen das Mitglied
     * beim nächsten Abgleich aus der Liste. Die Buchungen bleiben mit dem Namen als Schnappschuss, weil
     * sie der Aufbewahrungspflicht unterliegen; das Profil (Adresse, E-Mail) geht sofort. Ein Deckel,
     * der nicht auf null steht, lässt sich nicht löschen — das Geld verschwände sonst mit dem Namen.
     */
    fun deleteMember(by: WebUser, id: UUID) {
        db.write { c ->
            val row = c.queryOne("SELECT m.name, b.balance FROM members m JOIN member_balances b ON b.member_id = m.id WHERE m.id = ? AND NOT m.deleted FOR UPDATE OF m", id) { it.getString("name") to it.getDouble("balance") }
                ?: throw AccountProblem("Dieses Mitglied gibt es nicht mehr.")
            if (kotlin.math.abs(row.second) >= 0.005) throw AccountProblem("Der Deckel von ${row.first} steht auf ${euro(row.second)}. Erst ausgleichen — Aufladung oder Korrektur —, dann löschen.")
            c.execute("UPDATE members SET deleted = true, deleted_at = now() WHERE id = ?", id)
            c.execute("DELETE FROM member_profiles WHERE member_id = ?", id)
            AuditLog.record(c, by.id, by.displayName, "member.delete", row.first, "Profil gelöscht, Buchungen bleiben")
        }
    }

    /** Die Sperre (Konzept 4.1): mit Grund, synchronisiert; die Theke zeigt sie und schreibt nicht mehr an. Leerer Grund hebt sie auf. */
    fun blockMember(by: WebUser, id: UUID, reason: String) {
        val clean = reason.trim().replace(Regex("\\s+"), " ").take(120)
        db.write { c ->
            val name = c.queryOne("SELECT name FROM members WHERE id = ? AND NOT deleted FOR UPDATE", id) { it.getString("name") }
                ?: throw AccountProblem("Dieses Mitglied gibt es nicht mehr.")
            c.execute("UPDATE members SET blocked_reason = ? WHERE id = ?", clean.ifEmpty { null }, id)
            if (clean.isEmpty()) AuditLog.record(c, by.id, by.displayName, "member.unblock", name)
            else AuditLog.record(c, by.id, by.displayName, "member.block", name, clean)
        }
    }

    /**
     * Eine Bewegung auf dem Deckel. [bookingId] kommt aus dem Formular: Wer zweimal auf den
     * Knopf drückt oder die Seite neu lädt, schickt denselben Schlüssel, und gebucht wird
     * einmal. True, wenn diese Anfrage gebucht hat.
     */
    fun bookTab(by: WebUser, memberId: UUID, bookingId: UUID, amount: Double, paymentType: String, note: String, now: Instant = Instant.now()): Boolean {
        val cents = Money.cents(amount)
        if (cents == 0.0 || kotlin.math.abs(cents) > MAX_AMOUNT) throw AccountProblem("Der Betrag muss zwischen 0,01 € und ${Money.format(MAX_AMOUNT)} liegen.")
        val reason = note.trim().take(200)
        val correction = paymentType == "CORRECTION"
        if (correction && reason.length < 3) throw AccountProblem("Eine Korrektur braucht einen Grund — der Rechnungsprüfer fragt danach.")
        if (!correction && cents < 0) throw AccountProblem("Eine Aufladung ist positiv. Für Abzüge gibt es die Korrektur.")
        return db.write { c ->
            val name = c.queryOne("SELECT name FROM members WHERE id = ? AND NOT deleted", memberId) { it.getString("name") }
                ?: throw AccountProblem("Dieses Mitglied gibt es nicht mehr.")
            val inserted = c.execute(
                """
                INSERT INTO transactions (id, transaction_group_id, member_id, member_name, product_ref, product_name, product_category,
                                          price, quantity, discount_amount, payment_type, occurred_at, is_refund, note)
                VALUES (?, ?, ?, ?, ?::uuid, ?, 'Guthaben', ?, 1, 0, ?, ?, false, ?) ON CONFLICT (id) DO NOTHING
                """.trimIndent(),
                bookingId, UUID.fromString(Ids.new()), memberId, name, Ledger.TOPUP_REF,
                if (cents >= 0) "Guthabenaufladung" else "Guthabenkorrektur", BigDecimal.valueOf(cents), paymentType, Timestamp.from(now), reason.ifEmpty { null }
            ) > 0
            if (inserted) AuditLog.record(c, by.id, by.displayName, if (correction) "tab.correction" else "tab.topup", name, "${Money.formatSigned(cents)}${if (reason.isNotEmpty()) " · $reason" else ""}")
            inserted
        }
    }

    private fun cleanName(name: String): String =
        name.trim().replace(Regex("\\s+"), " ").take(80).ifEmpty { throw AccountProblem("Bitte einen Namen angeben.") }

    /** Ohne das „v.“ davor — das setzt die Anzeige. */
    private fun cleanNickname(nickname: String): String =
        nickname.trim().removePrefix("v.").removePrefix("vulgo").trim().replace(Regex("\\s+"), " ").take(60)

    private fun existingCategory(c: java.sql.Connection, id: UUID?): UUID? =
        id?.takeIf { c.queryOne("SELECT 1 FROM member_categories WHERE id = ? AND NOT deleted", it) { true } == true }

    companion object {
        const val MAX_AMOUNT = 5_000.0
    }
}
