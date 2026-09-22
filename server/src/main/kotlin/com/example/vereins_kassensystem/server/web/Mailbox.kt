package com.example.vereins_kassensystem.server.web

import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.FlagTerm
import java.security.MessageDigest
import java.time.Instant
import java.util.Properties

/** Der IMAP-Zugang zum Postfach des Vereins. Leer: kein Empfang. Benutzer und Passwort leer: die vom Versand. */
class Imap(val host: String, val port: Int, val user: String, val password: String, val folder: String, val enabled: Boolean) {
    val configured get() = host.isNotBlank() && user.isNotBlank()
}

class MailAttachment(val fileName: String, val contentType: String, val bytes: ByteArray)

class IncomingMail(val messageId: String, val from: String, val subject: String, val receivedAt: Instant, val attachments: List<MailAttachment>)

/** Der Empfangsweg, austauschbar: im Betrieb IMAP, im Test ein Postfach zum Befüllen. */
interface Mailbox {
    /** Die ungelesenen Mails; jede wird als gelesen markiert, sobald sie hier zurückgegeben ist. Wirft bei Verbindungsfehlern. */
    fun fetchUnseen(imap: Imap): List<IncomingMail>
}

/** Jakarta Mail über IMAPS — die Mails bleiben im Postfach, nur die Lesemarke wird gesetzt. */
object ImapMailbox : Mailbox {
    override fun fetchUnseen(imap: Imap): List<IncomingMail> {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", imap.host)
            put("mail.imaps.port", imap.port.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "15000")
            put("mail.imaps.timeout", "30000")
        }
        val store = Session.getInstance(props).getStore("imaps")
        store.connect(imap.host, imap.port, imap.user, imap.password)
        try {
            val folder = store.getFolder(imap.folder.ifBlank { "INBOX" })
            folder.open(Folder.READ_WRITE)
            try {
                val result = ArrayList<IncomingMail>()
                for (message in folder.search(FlagTerm(Flags(Flags.Flag.SEEN), false))) {
                    val mime = message as? MimeMessage ?: continue
                    val from = (mime.from?.firstOrNull() as? InternetAddress)?.address.orEmpty().lowercase()
                    val attachments = ArrayList<MailAttachment>()
                    collect(mime, attachments)
                    val received = mime.receivedDate?.toInstant() ?: mime.sentDate?.toInstant() ?: Instant.now()
                    val id = mime.messageID?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackId(from, mime.subject.orEmpty(), received)
                    result += IncomingMail(id, from, mime.subject.orEmpty(), received, attachments)
                    mime.setFlag(Flags.Flag.SEEN, true)
                }
                return result
            } finally {
                folder.close(false)
            }
        } finally {
            store.close()
        }
    }

    /** Anhänge in allen Ebenen einer Mail: Rechnungen kommen als PDF, mit oder ohne Disposition. */
    private fun collect(part: Part, into: MutableList<MailAttachment>) {
        val content = runCatching { part.content }.getOrNull()
        if (content is Multipart) {
            for (i in 0 until content.count) collect(content.getBodyPart(i), into)
            return
        }
        val name = runCatching { part.fileName }.getOrNull().orEmpty()
        val type = part.contentType.orEmpty().substringBefore(';').trim().lowercase()
        val isAttachment = Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) || name.isNotEmpty()
        if (isAttachment && (type == "application/pdf" || type == "application/octet-stream" || name.lowercase().endsWith(".pdf"))) {
            val bytes = part.inputStream.use { it.readAllBytes() }
            into += MailAttachment(name.ifEmpty { "rechnung.pdf" }, if (name.lowercase().endsWith(".pdf")) "application/pdf" else type, bytes)
        }
    }

    private fun fallbackId(from: String, subject: String, at: Instant): String =
        "<" + MessageDigest.getInstance("SHA-256").digest("$from|$subject|$at".toByteArray()).joinToString("") { "%02x".format(it) }.take(40) + "@vereinsdeckel>"
}

/** Für die Tests: ein Postfach, das man befüllt; jede Mail kommt genau einmal zurück. */
class FakeMailbox : Mailbox {
    val pending = java.util.concurrent.CopyOnWriteArrayList<IncomingMail>()
    var failWith: String? = null
    override fun fetchUnseen(imap: Imap): List<IncomingMail> {
        failWith?.let { throw java.io.IOException(it) }
        val out = pending.toList()
        pending.clear()
        return out
    }
}
