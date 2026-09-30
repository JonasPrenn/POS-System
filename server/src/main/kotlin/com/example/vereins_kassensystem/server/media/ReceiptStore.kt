package com.example.vereins_kassensystem.server.media

import com.example.vereins_kassensystem.platform.Ids
import io.ktor.http.ContentType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Belegfotos (Spezifikation 5.5): Der Wareneingang hält ein Foto des Kassabons. Das Bild
 * liegt als Datei unter [dir]/receipts, die Zeile in `deliveries` trägt nur den Schlüssel.
 *
 * Kein Objektspeicher: Für einen Verein sind das ein paar hundert Fotos im Jahr, und ein
 * Verzeichnis lässt sich mit denselben Mitteln sichern wie die Datenbank (7.2).
 */
class ReceiptStore(dir: Path) {

    class Stored(val path: Path, val contentType: ContentType)

    private val receipts: Path = dir.resolve("receipts").also { Files.createDirectories(it) }

    private val extensions = mapOf(
        ContentType.Image.JPEG to "jpg",
        ContentType.Image.PNG to "png",
        ContentType.parse("image/webp") to "webp",
        ContentType.parse("image/heic") to "heic",
        // Rechnungen aus der Verwaltung kommen meist als PDF; die Geräte holen nur Fotos ab.
        ContentType.Application.Pdf to "pdf",
    )

    private val keyPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp|heic|pdf)")

    val maxBytes: Long = 20L * 1024 * 1024

    /** Null, wenn der Inhaltstyp kein unterstütztes Bild ist. */
    fun extensionFor(contentType: ContentType?): String? =
        contentType?.withoutParameters()?.let { extensions[it] }

    /** Schreibt das Bild und liefert den Schlüssel, der in `deliveries.photo_key` steht. */
    fun store(bytes: ByteArray, extension: String): String {
        val key = "${Ids.new()}.$extension"
        val target = receipts.resolve(key)
        val temp = Files.createTempFile(receipts, "upload-", ".part")
        try {
            Files.write(temp, bytes)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
        return key
    }

    /** Null bei einem Schlüssel, der nicht von uns stammt — auch wenn er `../` enthält. */
    fun find(key: String): Stored? {
        if (!keyPattern.matches(key)) return null
        val path = receipts.resolve(key)
        if (!Files.isRegularFile(path)) return null
        val contentType = extensions.entries.first { it.value == key.substringAfterLast('.') }.key
        return Stored(path, contentType)
    }
}
