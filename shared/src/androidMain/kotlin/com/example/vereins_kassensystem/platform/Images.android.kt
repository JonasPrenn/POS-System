package com.example.vereins_kassensystem.platform

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual suspend fun loadImageBitmap(ref: String, maxSize: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = AndroidContextHolder.application.contentResolver
        val uri = Uri.parse(ref)

        // Erst nur die Maße lesen, dann passend verkleinert dekodieren — BitmapFactory
        // kann nur in Zweierpotenzen abtasten, deshalb die Schleife.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }?.asImageBitmap()
    }.getOrNull()
}

actual suspend fun readPhotoBytes(ref: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
        AndroidContextHolder.application.contentResolver.openInputStream(Uri.parse(ref))?.use { it.readBytes() }
    }.getOrNull()
}

actual suspend fun storeDownloadedPhoto(fileName: String, bytes: ByteArray): String? = withContext(Dispatchers.IO) {
    runCatching {
        val directory = java.io.File(AndroidContextHolder.application.filesDir, "belege").apply { mkdirs() }
        val file = java.io.File(directory, fileName)
        file.writeBytes(bytes)
        Uri.fromFile(file).toString()
    }.getOrNull()
}
