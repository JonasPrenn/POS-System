package com.example.vereins_kassensystem.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Android-Fassung der Dateiauswahl.
 *
 * Alles läuft über Activity-Result-Verträge, wie es vorher in den vier Bildschirmen
 * selbst stand. Die Rückrufe der Bildschirme werden mit rememberUpdatedState gehalten,
 * damit ein Launcher, der über mehrere Neuzeichnungen lebt, immer den aktuellen
 * Rückruf trifft und nicht den vom ersten Aufbau.
 */

@Composable
actual fun rememberTextFileReader(onText: (String) -> Unit): FilePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnText by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                }.getOrNull()
            }
            if (text != null) currentOnText(text)
        }
    }
    return remember(launcher) { FilePicker { launcher.launch("text/*") } }
}

@Composable
actual fun rememberTextFileWriter(
    suggestedName: String,
    content: () -> String,
    onDone: (Boolean) -> Unit
): FilePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentContent by rememberUpdatedState(content)
    val currentOnDone by rememberUpdatedState(onDone)
    // Der beim Öffnen eingefrorene Inhalt, bis der Systemdialog zurückkommt.
    val pending = remember { arrayOfNulls<String>(1) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val text = pending[0]
        pending[0] = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.bufferedWriter().use { it.write(text) }
                    } != null
                }.getOrDefault(false)
            }
            currentOnDone(ok)
        }
    }
    return remember(launcher) {
        FilePicker {
            pending[0] = currentContent()
            launcher.launch(suggestedName)
        }
    }
}

@Composable
actual fun rememberPhotoCapture(onCaptured: (String) -> Unit): FilePicker {
    val context = LocalContext.current
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val pending = remember { arrayOfNulls<Uri>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pending[0]
        pending[0] = null
        if (ok && uri != null) currentOnCaptured(uri.toString())
    }
    return remember(launcher) {
        FilePicker {
            val uri = receiptPhotoUri(context, newReceiptPhotoFile(context))
            pending[0] = uri
            launcher.launch(uri)
        }
    }
}

@Composable
actual fun rememberPhotoPicker(onPicked: (String) -> Unit): FilePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copy = withContext(Dispatchers.IO) {
                runCatching {
                    val file = newReceiptPhotoFile(context)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    } ?: return@runCatching null
                    receiptPhotoUri(context, file)
                }.getOrNull()
            }
            if (copy != null) currentOnPicked(copy.toString())
        }
    }
    return remember(launcher) {
        FilePicker {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
}

@Composable
actual fun rememberBackupDestinationPicker(onChosen: (String?) -> Unit): FilePicker {
    val context = LocalContext.current
    val currentOnChosen by rememberUpdatedState(onChosen)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Ohne die dauerhafte Berechtigung wäre der Ordner nach dem nächsten Start
        // wieder unerreichbar, und die nächtliche Sicherung liefe ins Leere.
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        currentOnChosen(uri.toString())
    }
    return remember(launcher) { FilePicker { launcher.launch(null) } }
}

@Composable
actual fun rememberBackupFileReader(onBytes: (ByteArray) -> Unit): FilePicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnBytes by rememberUpdatedState(onBytes)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes != null) currentOnBytes(bytes)
        }
    }
    return remember(launcher) { FilePicker { launcher.launch(arrayOf("*/*")) } }
}

/**
 * Belegfotos liegen im eigenen Dateiordner, nicht in der Galerie: So sind sie von den
 * Sicherungsregeln der App erfasst und verschwinden mit ihr, statt die Fotomediathek des
 * Vereinstablets mit Kassabons zu füllen. `file_paths.xml` gibt genau diesen Ordner an
 * den FileProvider frei.
 */
private fun receiptsDir(context: Context): File = File(context.filesDir, "belege").apply { mkdirs() }

private fun newReceiptPhotoFile(context: Context): File =
    File(receiptsDir(context), "bon_${nowMillis()}.jpg")

private fun receiptPhotoUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
