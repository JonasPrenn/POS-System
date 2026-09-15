package com.example.vereins_kassensystem.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkCreationMinimalBookmark
import platform.Foundation.NSUserDomainMask
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfURL
import platform.posix.memcpy

/*
 * Kleine Helfer für die iOS-Dateiwelt, an einer Stelle statt in jeder Datei neu.
 */

/**
 * Der Documents-Ordner des App-Containers.
 *
 * Nicht Caches oder tmp: iOS räumt beides ohne Vorwarnung leer, wenn der Speicher knapp
 * wird. Datenbank und Belegfotos gehören deshalb hierher.
 */
@OptIn(ExperimentalForeignApi::class)
fun documentsDirectory(): String {
    val url: NSURL = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null
    ) ?: error("Documents-Ordner nicht auffindbar")
    return requireNotNull(url.path) { "Documents-Ordner ohne Pfad" }
}

/** Unterordner für Belegfotos, wird bei Bedarf angelegt. */
@OptIn(ExperimentalForeignApi::class)
fun receiptsDirectory(): String {
    val path = documentsDirectory() + "/belege"
    NSFileManager.defaultManager.createDirectoryAtPath(
        path, withIntermediateDirectories = true, attributes = null, error = null
    )
    return path
}

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
}

/** Liest eine Datei vollständig; null, wenn es sie nicht gibt oder sie nicht lesbar ist. */
fun readFileBytes(url: NSURL): ByteArray? = NSData.dataWithContentsOfURL(url)?.toByteArray()

/**
 * Ein Lesezeichen auf einen vom Nutzer gewählten Ordner, als Base64 zum Ablegen in den
 * Einstellungen.
 *
 * Ein bloßer Pfad überlebt den nächsten Start nicht: Der Zugriff auf einen Ort außerhalb
 * des eigenen Containers ist an den Sicherheits-Scope der Auswahl gebunden, und nur ein
 * Lesezeichen trägt den weiter. Aufgelöst wird es mit [resolveBookmark].
 */
@OptIn(ExperimentalForeignApi::class)
fun bookmarkOf(url: NSURL): String? {
    val access = url.startAccessingSecurityScopedResource()
    try {
        val data = url.bookmarkDataWithOptions(
            options = NSURLBookmarkCreationMinimalBookmark,
            includingResourceValuesForKeys = null,
            relativeToURL = null,
            error = null
        ) ?: return null
        return data.base64EncodedStringWithOptions(0u)
    } finally {
        if (access) url.stopAccessingSecurityScopedResource()
    }
}

/**
 * Löst ein Lesezeichen aus [bookmarkOf] wieder auf.
 *
 * Der Aufrufer muss um jeden Zugriff `startAccessingSecurityScopedResource()` und
 * `stopAccessingSecurityScopedResource()` legen; siehe [withSecurityScope].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun resolveBookmark(base64: String): NSURL? {
    val data = NSData.create(base64EncodedString = base64, options = 0u) ?: return null
    return NSURL.URLByResolvingBookmarkData(
        data,
        options = 0u,
        relativeToURL = null,
        bookmarkDataIsStale = null,
        error = null
    )
}

/** Führt [block] mit geöffnetem Sicherheits-Scope aus und schließt ihn danach wieder. */
inline fun <T> NSURL.withSecurityScope(block: (NSURL) -> T): T {
    val access = startAccessingSecurityScopedResource()
    try {
        return block(this)
    } finally {
        if (access) stopAccessingSecurityScopedResource()
    }
}
