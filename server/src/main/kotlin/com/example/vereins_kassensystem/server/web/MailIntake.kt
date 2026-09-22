package com.example.vereins_kassensystem.server.web

import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.server.db.Database
import com.example.vereins_kassensystem.server.db.execute
import com.example.vereins_kassensystem.server.db.query
import com.example.vereins_kassensystem.server.db.queryOne
import com.example.vereins_kassensystem.server.media.ReceiptStore
import org.slf4j.LoggerFactory
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class IntakeRow(
    val id: UUID, val messageId: String, val sender: String, val subject: String, val receivedAt: Instant,
    val fileKey: String?, val fileName: String, val status: String, val documentId: UUID?, val note: String,
)

class PollResult(val fetched: Int, val recorded: Int, val error: String?)

/**
 * Rechnungen per E-Mail (Konzept 4.4): Das Postfach des Vereins wird abgefragt, jede Mail mit
 * PDF wird festgehalten. Kommt sie von einem Absender, den der Kassier einmal freigegeben hat,
 * wird sie gleich ein Beleg — Kopfdaten aus der Datei, die gemerkten Positionen sofort gebucht,
 * der Rest wartet als Vorschlag am Beleg. Von einem unbekannten Absender wartet die Mail im
 * Posteingang, bis der Kassier sie übernimmt oder ablehnt; beim Übernehmen wird die Adresse
 * freigegeben. Nichts davon bucht ohne eine Zeile im Protokoll, und nichts wird zweimal
 * eingelesen: Die Message-ID ist eindeutig, ein Beleg mit derselben Nummer schlägt fehl und
 * steht als solcher da.
 */
class MailIntake(
    private val db: Database, private val receipts: ReceiptStore, private val purchases: Purchases,
    private val settings: VereinSettings, private val mailbox: Mailbox, private val zone: ZoneId,
) {
    private val log = LoggerFactory.getLogger(MailIntake::class.java)

    /** Abrufen und verarbeiten; das Ergebnis steht danach in den Einstellungen, damit die Seite es zeigen kann. */
    fun poll(now: Instant = Instant.now()): PollResult {
        val values = settings.load()
        val imap = values.imap.let { if (it.user.isBlank() && it.password.isBlank()) Imap(it.host, it.port, values.smtp.user, values.smtp.password, it.folder, it.enabled) else it }
        if (!imap.configured) return PollResult(0, 0, "Kein Postfach eingerichtet — unter Einstellungen den IMAP-Zugang eintragen.")
        return try {
            val mails = mailbox.fetchUnseen(imap)
            val recorded = process(mails, values, now)
            settings.notePoll(now, null)
            PollResult(mails.size, recorded, null)
        } catch (e: Exception) {
            val message = "Abruf fehlgeschlagen: ${e.message?.lineSequence()?.firstOrNull() ?: e::class.simpleName}"
            log.warn("Posteingang: {}", message)
            settings.notePoll(now, message)
            PollResult(0, 0, message)
        }
    }

    /** Ohne Postfach, für die Tests und für den Abruf selbst: Mails festhalten, bekannte Absender gleich verarbeiten. */
    fun process(mails: List<IncomingMail>, values: VereinSettings.Values = settings.load(), now: Instant = Instant.now()): Int {
        var recorded = 0
        for (mail in mails) {
            val known = db.read { c -> c.queryOne("SELECT 1 FROM mail_intake WHERE message_id = ?", mail.messageId) { true } ?: false }
            if (known) continue
            val pdf = mail.attachments.firstOrNull { it.contentType == "application/pdf" || it.fileName.lowercase().endsWith(".pdf") }
            val id = UUID.fromString(Ids.new())
            if (pdf == null || pdf.bytes.size > receipts.maxBytes) {
                db.transaction { c ->
                    c.execute("INSERT INTO mail_intake (id, message_id, sender, subject, received_at, status, note) VALUES (?, ?, ?, ?, ?, 'NOFILE', ?)",
                        id, mail.messageId, mail.from.take(200), mail.subject.take(200), Timestamp.from(mail.receivedAt), if (pdf == null) "kein PDF im Anhang" else "Anhang größer als 20 MB")
                }
                recorded++; continue
            }
            val fileKey = receipts.store(pdf.bytes, "pdf")
            db.transaction { c ->
                c.execute("INSERT INTO mail_intake (id, message_id, sender, subject, received_at, file_key, file_name, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'NEW')",
                    id, mail.messageId, mail.from.take(200), mail.subject.take(200), Timestamp.from(mail.receivedAt), fileKey, pdf.fileName.take(120))
            }
            recorded++
            if (senderTrusted(mail.from)) runCatching { accept(WebUser.SYSTEM, id, trust = false, values = values, now = now) }
                .onFailure { log.warn("Posteingang: Beleg aus {} nicht angelegt: {}", mail.from, it.message) }
        }
        return recorded
    }

    fun rows(limit: Int = 40): List<IntakeRow> = db.read { c ->
        c.query("SELECT * FROM mail_intake ORDER BY (status = 'NEW') DESC, received_at DESC LIMIT ?", limit) { it.row() }
    }

    fun row(id: UUID): IntakeRow? = db.read { c -> c.queryOne("SELECT * FROM mail_intake WHERE id = ?", id) { it.row() } }

    fun openCount(): Int = db.read { c -> c.queryOne("SELECT COUNT(*) AS n FROM mail_intake WHERE status = 'NEW'") { it.getInt("n") } ?: 0 }

    fun senderTrusted(address: String): Boolean = db.read { c ->
        c.queryOne("SELECT trusted FROM mail_senders WHERE address = ?", address.trim().lowercase()) { it.getBoolean("trusted") } ?: false
    }

    fun trustedSenders(): List<Pair<String, String>> = db.read { c ->
        c.query("SELECT m.address, COALESCE(s.name, '') AS supplier FROM mail_senders m LEFT JOIN suppliers s ON s.id = m.supplier_id WHERE m.trusted ORDER BY m.address") { it.getString("address") to it.getString("supplier") }
    }

    /**
     * Aus der Mail wird ein Beleg: Kopf aus der Datei (der Lieferant des Absenders gewinnt, wenn er
     * bekannt ist), dann die gemerkten Positionen — und nur die. Was der Leser nur vermutet, wartet
     * am Beleg auf den Kassier. Mit [trust] wird der Absender für das nächste Mal freigegeben.
     */
    fun accept(by: WebUser, id: UUID, trust: Boolean, values: VereinSettings.Values = settings.load(), now: Instant = Instant.now()): UUID {
        val row = row(id) ?: throw AccountProblem("Diese Mail gibt es nicht mehr.")
        if (row.status != "NEW" || row.fileKey == null) throw AccountProblem("Diese Mail ist schon verarbeitet.")
        val stored = receipts.find(row.fileKey) ?: throw AccountProblem("Die Datei zur Mail fehlt.")
        val bytes = java.nio.file.Files.readAllBytes(stored.path)
        val supplierOfSender = db.read { c -> c.queryOne("SELECT s.name FROM mail_senders m JOIN suppliers s ON s.id = m.supplier_id WHERE m.address = ?", row.sender) { it.getString("name") } }
        val read = InvoiceReader.read(bytes, purchases.suppliers().map { it.name }, values.name, now.atZone(zone).toLocalDate())
        val supplier = supplierOfSender ?: read.supplier ?: row.sender.substringAfter('@').substringBefore('.').replaceFirstChar { it.uppercase() }
        val head = Purchases.Head(
            supplier = supplier, number = read.number.orEmpty(), date = read.date ?: now.atZone(zone).toLocalDate(), dueDate = read.dueDate,
            gross = read.gross, vat = read.vat ?: 0.0, payment = Payment.OPEN, paidAt = null,
            note = "per E-Mail von ${row.sender}${row.subject.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}".take(500),
        )
        val documentId = try {
            purchases.saveHead(by, null, head, row.fileKey)
        } catch (e: AccountProblem) {
            db.transaction { c -> c.execute("UPDATE mail_intake SET status = 'FAILED', note = ? WHERE id = ?", e.message.orEmpty().take(300), id) }
            throw e
        }
        val doc = purchases.document(documentId)!!
        val suggestions = purchases.suggest(read, doc, purchases.stockChoices(), purchases.containerSizes())
        // Automatisch nur, was gemerkt ist oder ein bekanntes Pfandgebinde — nie, was der Leser nur vermutet.
        val sure = suggestions.filter { !it.done && it.mapping != null && (it.learned || it.mapping.depositKindId != null) }
        val booked = if (sure.isEmpty()) 0 else purchases.applyLines(by, documentId, sure.map { sg ->
            val m = sg.mapping!!
            Purchases.ChosenLine(sg.key, sg.line.description, sg.line.quantity, m.itemId, m.containerTypeId, m.accountId, sg.quantity, sg.line.total, m.depositKindId, null, sg.line.delivered, sg.line.returned)
        })
        val pending = suggestions.size - sure.size
        val note = when {
            !read.hasText -> "Datei ohne Textebene — Belegdaten prüfen, Positionen von Hand."
            suggestions.isEmpty() -> "Kopf aus der Datei; keine Positionen erkannt."
            else -> "$booked von ${suggestions.size} Positionen automatisch gebucht" + (if (pending > 0) ", $pending warten am Beleg" else "") + "."
        }
        db.transaction { c ->
            c.execute("UPDATE mail_intake SET status = 'DONE', document_id = ?, note = ? WHERE id = ?", documentId, note, id)
            if (trust) c.execute("INSERT INTO mail_senders (address, supplier_id, trusted) VALUES (?, ?, true) ON CONFLICT (address) DO UPDATE SET supplier_id = EXCLUDED.supplier_id, trusted = true", row.sender, doc.supplierId)
            AuditLog.record(c, by.id, by.displayName, "mail.accept", "${doc.supplier} ${doc.number}".trim(), listOfNotNull(row.sender, if (trust) "Absender freigegeben" else null).joinToString(" · "))
        }
        return documentId
    }

    fun reject(by: WebUser, id: UUID) = db.transaction { c ->
        val row = c.queryOne("SELECT sender, subject FROM mail_intake WHERE id = ? AND status = 'NEW'", id) { it.getString("sender") to it.getString("subject") } ?: return@transaction
        c.execute("UPDATE mail_intake SET status = 'REJECTED' WHERE id = ?", id)
        AuditLog.record(c, by.id, by.displayName, "mail.reject", row.first, row.second)
    }

    fun untrust(by: WebUser, address: String) = db.transaction { c ->
        c.execute("UPDATE mail_senders SET trusted = false WHERE address = ?", address.trim().lowercase())
        AuditLog.record(c, by.id, by.displayName, "mail.untrust", address.trim().lowercase())
    }

    private fun java.sql.ResultSet.row() = IntakeRow(
        getObject("id", UUID::class.java), getString("message_id"), getString("sender"), getString("subject"), getTimestamp("received_at").toInstant(),
        getString("file_key"), getString("file_name"), getString("status"), getObject("document_id", UUID::class.java), getString("note"),
    )
}
