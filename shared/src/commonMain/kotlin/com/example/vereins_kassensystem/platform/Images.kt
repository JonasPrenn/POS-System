package com.example.vereins_kassensystem.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Lädt ein Foto, auf das ein Verweis aus [rememberPhotoCapture] zeigt.
 *
 * Absichtlich keine Bildbibliothek: Die App zeigt genau ein Bild an, das Belegfoto, und
 * das liegt lokal. Coil 3 hätte das gekonnt, ist aber ab 3.6 mit Kotlin 2.4 gebaut und
 * davor gegen ein älteres Skiko als Compose 1.12 — für ein einziges Bild ist das eine
 * Abhängigkeit, die mehr Versionspflege kostet, als sie spart.
 *
 * [maxSize] begrenzt die längere Kante beim Dekodieren, damit ein 12-Megapixel-Foto
 * nicht als 48 MB Bitmap im Speicher landet. Liefert null, wenn die Datei fehlt oder
 * kein Bild ist; der Bildschirm zeigt dann einfach nichts.
 */
expect suspend fun loadImageBitmap(ref: String, maxSize: Int = 1600): ImageBitmap?

/**
 * Die Bytes eines Belegfotos, für den Upload zum Server (Spezifikation 5.5). [ref] ist
 * derselbe Verweis, den [loadImageBitmap] versteht. Null, wenn die Datei nicht mehr da ist.
 */
expect suspend fun readPhotoBytes(ref: String): ByteArray?

/**
 * Legt ein vom Server geholtes Belegfoto auf diesem Gerät ab und liefert den Verweis, unter
 * dem [loadImageBitmap] es wiederfindet — unter Android eine Datei-URI, unter iOS ein Pfad.
 */
expect suspend fun storeDownloadedPhoto(fileName: String, bytes: ByteArray): String?
