package com.example.vereins_kassensystem.server.web

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.util.Properties

/** Eine E-Mail mit Anhang — Abrechnung oder Erinnerung. */
class Mail(val to: String, val subject: String, val text: String, val attachmentName: String?, val attachment: ByteArray?)

/** Der Versandweg, austauschbar: im Betrieb SMTP, im Test ein Postausgang zum Nachsehen. */
interface Mailer {
    /** Null bei Erfolg, sonst der Grund in Worten. */
    fun send(smtp: Smtp, mail: Mail): String?
}

/** Jakarta Mail über den SMTP-Zugang des Vereins, mit STARTTLS, wie ihn jeder Anbieter hergibt. */
object SmtpMailer : Mailer {
    override fun send(smtp: Smtp, mail: Mail): String? {
        if (!smtp.configured) return "Kein SMTP-Zugang eingerichtet — unter Einstellungen eintragen."
        return try {
            val props = Properties().apply {
                put("mail.smtp.host", smtp.host)
                put("mail.smtp.port", smtp.port.toString())
                put("mail.smtp.auth", (smtp.user.isNotBlank()).toString())
                put("mail.smtp.starttls.enable", smtp.startTls.toString())
                put("mail.smtp.starttls.required", smtp.startTls.toString())
                put("mail.smtp.ssl.enable", (smtp.port == 465).toString())
                put("mail.smtp.connectiontimeout", "10000")
                put("mail.smtp.timeout", "20000")
                put("mail.smtp.writetimeout", "20000")
            }
            val session = if (smtp.user.isNotBlank()) Session.getInstance(props, object : jakarta.mail.Authenticator() {
                override fun getPasswordAuthentication() = jakarta.mail.PasswordAuthentication(smtp.user, smtp.password)
            }) else Session.getInstance(props)
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(smtp.from))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(mail.to))
                setSubject(mail.subject, "UTF-8")
                val body = MimeMultipart().apply {
                    addBodyPart(MimeBodyPart().apply { setText(mail.text, "UTF-8") })
                    if (mail.attachment != null) addBodyPart(MimeBodyPart().apply {
                        dataHandler = jakarta.activation.DataHandler(ByteArrayDataSource(mail.attachment, "application/pdf"))
                        fileName = mail.attachmentName ?: "abrechnung.pdf"
                    })
                }
                setContent(body)
            }
            Transport.send(message)
            null
        } catch (e: Exception) {
            "Versand fehlgeschlagen: ${e.message?.lineSequence()?.firstOrNull() ?: e::class.simpleName}"
        }
    }
}

/** Für die Tests und zum Ausprobieren ohne Server: merkt sich, was verschickt worden wäre. */
class OutboxMailer : Mailer {
    val sent = java.util.concurrent.CopyOnWriteArrayList<Mail>()
    override fun send(smtp: Smtp, mail: Mail): String? {
        if (!smtp.configured) return "Kein SMTP-Zugang eingerichtet — unter Einstellungen eintragen."
        sent += mail
        return null
    }
}
