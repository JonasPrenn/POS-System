package com.example.vereins_kassensystem.platform

import androidx.compose.runtime.Composable

/**
 * Dateien hinein und hinaus, ohne dass der Bildschirm weiß, wie das Betriebssystem das
 * regelt.
 *
 * Die App tauscht über Dateien nur Text aus: Produkt-, Mitglieder- und Lagerlisten als
 * CSV. Der Vertrag reicht deshalb Zeichenketten und keine Ströme durch — die Ströme
 * waren der Grund, warum die ViewModels `java.io.InputStream` in der Signatur trugen und
 * damit an die JVM gebunden waren. Eine CSV mit Vereinsprodukten passt in den Speicher;
 * für etwas anderes ist dieser Weg ohnehin nicht gedacht.
 *
 * Die Sicherung ist der Sonderfall — sie ist eine Binärdatei mit dauerhaft gemerktem
 * Ablageort und geht deshalb über [BackupExchange] und die beiden Auswahl-Composables
 * am Ende statt hier durch.
 */
fun interface FilePicker {
    /** Öffnet die Auswahl des Systems. Kehrt sofort zurück; das Ergebnis kommt per Rückruf. */
    fun open()
}

/**
 * Lässt eine Textdatei auswählen und gibt ihren Inhalt zurück.
 *
 * [onText] wird auf dem Hauptthread aufgerufen. Bricht der Nutzer ab oder lässt sich die
 * Datei nicht lesen, passiert nichts — ein Abbruch ist kein Fehler und verdient keine
 * Meldung.
 */
@Composable
expect fun rememberTextFileReader(onText: (String) -> Unit): FilePicker

/**
 * Lässt einen Ort wählen und schreibt [content] dorthin.
 *
 * Der Inhalt wird beim Öffnen erzeugt und nicht erst beim Bestätigen: Zwischen dem
 * Tippen auf "Export" und dem Bestätigen im Systemdialog können Sekunden liegen, und
 * der Bestand soll der von dem Moment sein, in dem der Nutzer es angestoßen hat.
 *
 * [suggestedName] ist nur ein Vorschlag; beide Plattformen lassen den Nutzer umbenennen,
 * und unter iOS kann die Datei in iCloud landen statt auf dem Gerät. [onDone] meldet,
 * ob geschrieben wurde; bei Abbruch wird es nicht aufgerufen.
 */
@Composable
expect fun rememberTextFileWriter(
    suggestedName: String,
    content: () -> String,
    onDone: (Boolean) -> Unit
): FilePicker

/**
 * Nimmt ein Belegfoto auf und gibt einen Verweis darauf zurück.
 *
 * Der Verweis ist plattformabhängig — unter Android eine Content-URI auf eine Datei im
 * eigenen Dateiordner, unter iOS ein Pfad im App-Container. Er wird deshalb nie zum
 * Server geschickt: Die Spezifikation sieht dafür einen Upload und einen serverseitigen
 * Schlüssel vor, weil eine Android-URI auf einem iPad nichts bedeutet. Anzeigen lässt er
 * sich auf beiden Plattformen über [loadImageBitmap].
 */
@Composable
expect fun rememberPhotoCapture(onCaptured: (String) -> Unit): FilePicker

/**
 * Wie [rememberPhotoCapture], nur aus der Fotomediathek statt von der Kamera.
 *
 * Das Bild wird in den eigenen Ordner kopiert: Was die Galerie herausgibt, darf die App
 * unter Android nur bis zum Ende der aktuellen Activity lesen, ein Verweis darauf in der
 * Datenbank wäre nach dem nächsten Start wertlos.
 */
@Composable
expect fun rememberPhotoPicker(onPicked: (String) -> Unit): FilePicker

/**
 * Sicherung schreiben und einlesen.
 *
 * Getrennt von [FilePicker], weil hier Binärdaten fließen und weil der Zielort dauerhaft
 * gemerkt wird: Unter Android ist das ein Dokumentbaum mit fortbestehender Berechtigung,
 * unter iOS ein per Sicherheits-Scope aufbewahrtes Lesezeichen. Beides muss die
 * Plattform selbst halten — ein Pfad allein reicht auf keiner von beiden.
 */
interface BackupExchange {
    /** Ob schon ein Ablageort gewählt wurde. Ohne ihn kann nicht gesichert werden. */
    suspend fun hasDestination(): Boolean

    /** Menschenlesbarer Ort für die Anzeige in den Einstellungen, oder null. */
    suspend fun destinationLabel(): String?

    suspend fun writeBackup(bytes: ByteArray, fileName: String): Boolean
    suspend fun readBackup(): ByteArray?
}

/**
 * Lässt den Ablageort für Sicherungen wählen.
 *
 * [onChosen] bekommt den plattformeigenen Verweis, den die App in den Einstellungen
 * ablegt — unter Android die Baum-URI, unter iOS das Lesezeichen als Base64. Bei Abbruch
 * bleibt der bisherige Ort unangetastet, der Rückruf kommt dann nicht.
 */
@Composable
expect fun rememberBackupDestinationPicker(onChosen: (String?) -> Unit): FilePicker

/** Lässt eine Sicherungsdatei zum Einspielen auswählen und liefert ihren Inhalt. */
@Composable
expect fun rememberBackupFileReader(onBytes: (ByteArray) -> Unit): FilePicker
