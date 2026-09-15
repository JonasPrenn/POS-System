package com.example.vereins_kassensystem.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

/**
 * Dekodiert über Skia, das Compose auf iOS ohnehin mitbringt.
 *
 * [maxSize] spielt hier keine Rolle: Belegfotos werden schon beim Aufnehmen auf diese
 * Kante verkleinert (siehe FileExchange.ios.kt), also liegt nie ein 12-Megapixel-Bild
 * auf der Platte.
 */
actual suspend fun loadImageBitmap(ref: String, maxSize: Int): ImageBitmap? = withContext(Dispatchers.Default) {
    runCatching {
        val bytes = NSData.dataWithContentsOfFile(ref)?.toByteArray() ?: return@runCatching null
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}
