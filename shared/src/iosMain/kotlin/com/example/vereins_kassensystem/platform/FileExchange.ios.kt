package com.example.vereins_kassensystem.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSISOLatin1StringEncoding
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.writeToFile
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeCommaSeparatedText
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.UniformTypeIdentifiers.UTTypePlainText
import platform.UniformTypeIdentifiers.UTTypeText
import platform.darwin.NSObject

/*
 * iOS-Fassung der Dateiauswahl.
 *
 * Alles läuft über UIDocumentPickerViewController und UIImagePickerController, präsentiert
 * vom View-Controller, in dem Compose lebt. Die Delegates sind Kotlin-Objekte, die
 * NSObject erweitern; iOS hält sie nur schwach, deshalb werden sie mit remember am Leben
 * gehalten, solange der Bildschirm steht.
 *
 * Zum Lesen werden die Auswahlen mit `asCopy = true` geöffnet: Das System legt dann eine
 * Kopie im eigenen Container ab, und der Sicherheits-Scope der fremden Datei spielt keine
 * Rolle mehr. Nur der Sicherungsordner wird ohne Kopie geöffnet, weil er dauerhaft
 * erreichbar bleiben muss — dafür gibt es das Lesezeichen in IosFiles.kt.
 */

private class DocumentPickerDelegate(
    private val onPicked: (List<NSURL>) -> Unit
) : NSObject(), UIDocumentPickerDelegateProtocol {

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onPicked(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) = Unit
}

private class ImagePickerDelegate(
    private val onImage: (UIImage?) -> Unit
) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {

    override fun imagePickerController(
        picker: UIImagePickerController,
        didFinishPickingMediaWithInfo: Map<Any?, *>
    ) {
        val image = didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
        picker.dismissViewControllerAnimated(true, completion = null)
        onImage(image)
    }

    override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
        picker.dismissViewControllerAnimated(true, completion = null)
    }
}

private fun UIViewController.present(picker: UIViewController) {
    presentViewController(picker, animated = true, completion = null)
}

/**
 * Liest eine Textdatei, erst als UTF-8, dann als Latin-1.
 *
 * Tabellenkalkulationen speichern CSV gern in der Windows-Kodierung; UTF-8 würde daran
 * scheitern und "Müller" verlieren. Latin-1 kann jede Bytefolge lesen, deshalb kommt es
 * als Rückfallebene und nicht zuerst.
 */
@OptIn(ExperimentalForeignApi::class)
private fun readText(url: NSURL): String? =
    NSString.stringWithContentsOfURL(url, encoding = NSUTF8StringEncoding, error = null)
        ?: NSString.stringWithContentsOfURL(url, encoding = NSISOLatin1StringEncoding, error = null)

@Composable
actual fun rememberTextFileReader(onText: (String) -> Unit): FilePicker {
    val host = LocalUIViewController.current
    val currentOnText by rememberUpdatedState(onText)
    val delegate = remember {
        DocumentPickerDelegate { urls ->
            val text = urls.firstOrNull()?.let(::readText)
            if (text != null) currentOnText(text)
        }
    }
    return remember(host) {
        FilePicker {
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeCommaSeparatedText, UTTypePlainText, UTTypeText),
                asCopy = true
            )
            picker.delegate = delegate
            host.present(picker)
        }
    }
}

@Composable
actual fun rememberTextFileWriter(
    suggestedName: String,
    content: () -> String,
    onDone: (Boolean) -> Unit
): FilePicker {
    val host = LocalUIViewController.current
    val currentContent by rememberUpdatedState(content)
    val currentOnDone by rememberUpdatedState(onDone)
    // Der Export-Picker kopiert die angebotene Datei an den gewählten Ort und meldet
    // dessen URL zurück; mehr als "es hat geklappt" ist daraus nicht nötig.
    val delegate = remember { DocumentPickerDelegate { urls -> currentOnDone(urls.isNotEmpty()) } }
    return remember(host) {
        FilePicker {
            val path = NSTemporaryDirectory() + suggestedName
            val written = currentContent().encodeToByteArray().toNSData().writeToFile(path, atomically = true)
            if (!written) {
                currentOnDone(false)
                return@FilePicker
            }
            val picker = UIDocumentPickerViewController(
                forExportingURLs = listOf(NSURL.fileURLWithPath(path)),
                asCopy = true
            )
            picker.delegate = delegate
            host.present(picker)
        }
    }
}

/**
 * Speichert ein aufgenommenes oder gewähltes Bild als Belegfoto und gibt den Pfad zurück.
 *
 * Vorher wird auf 1600 Punkte an der längeren Kante verkleinert: Ein Kassabon ist auch
 * so lesbar, und ein 12-Megapixel-Foto pro Wareneingang würde den Container in einer
 * Saison um ein paar hundert Megabyte anschwellen lassen.
 */
@OptIn(ExperimentalForeignApi::class)
private fun saveReceiptPhoto(image: UIImage, maxSide: Double = 1600.0): String? {
    val (width, height) = image.size.useContents { width to height }
    val scale = minOf(1.0, maxSide / maxOf(width, height))
    val scaled = if (scale < 1.0) {
        val w = width * scale
        val h = height * scale
        UIGraphicsBeginImageContextWithOptions(CGSizeMake(w, h), false, 1.0)
        image.drawInRect(CGRectMake(0.0, 0.0, w, h))
        val result = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        result ?: image
    } else image

    val data = UIImageJPEGRepresentation(scaled, 0.8) ?: return null
    val path = receiptsDirectory() + "/bon_${nowMillis()}.jpg"
    return if (data.writeToFile(path, atomically = true)) path else null
}

@Composable
private fun rememberImagePicker(
    sourceType: UIImagePickerControllerSourceType,
    onSaved: (String) -> Unit
): FilePicker {
    val host = LocalUIViewController.current
    val currentOnSaved by rememberUpdatedState(onSaved)
    val delegate = remember {
        ImagePickerDelegate { image ->
            val path = image?.let(::saveReceiptPhoto)
            if (path != null) currentOnSaved(path)
        }
    }
    return remember(host, sourceType) {
        FilePicker {
            val picker = UIImagePickerController()
            // Der Simulator hat keine Kamera; dort kommt das Bild aus der Mediathek,
            // damit sich der Ablauf trotzdem bis zum Ende durchspielen lässt.
            picker.sourceType =
                if (UIImagePickerController.isSourceTypeAvailable(sourceType)) sourceType
                else UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
            picker.delegate = delegate
            host.present(picker)
        }
    }
}

@Composable
actual fun rememberPhotoCapture(onCaptured: (String) -> Unit): FilePicker =
    rememberImagePicker(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera, onCaptured)

@Composable
actual fun rememberPhotoPicker(onPicked: (String) -> Unit): FilePicker =
    rememberImagePicker(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary, onPicked)

@Composable
actual fun rememberBackupDestinationPicker(onChosen: (String?) -> Unit): FilePicker {
    val host = LocalUIViewController.current
    val currentOnChosen by rememberUpdatedState(onChosen)
    val delegate = remember {
        DocumentPickerDelegate { urls ->
            val bookmark = urls.firstOrNull()?.let(::bookmarkOf)
            if (bookmark != null) currentOnChosen(bookmark)
        }
    }
    return remember(host) {
        FilePicker {
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeFolder),
                asCopy = false
            )
            picker.delegate = delegate
            host.present(picker)
        }
    }
}

@Composable
actual fun rememberBackupFileReader(onBytes: (ByteArray) -> Unit): FilePicker {
    val host = LocalUIViewController.current
    val currentOnBytes by rememberUpdatedState(onBytes)
    val delegate = remember {
        DocumentPickerDelegate { urls ->
            val bytes = urls.firstOrNull()?.let(::readFileBytes)
            if (bytes != null) currentOnBytes(bytes)
        }
    }
    return remember(host) {
        FilePicker {
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeItem),
                asCopy = true
            )
            picker.delegate = delegate
            host.present(picker)
        }
    }
}
