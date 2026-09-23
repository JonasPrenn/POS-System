package com.example.vereins_kassensystem.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Was die App vom Betriebssystem braucht — und sonst nichts.
 *
 * Alles, was unter Android und iOS verschieden ist, kommt durch dieses Nadelöhr. Der
 * Zuschnitt ist absichtlich fachlich und nicht technisch: [PaymentProcessor] heißt so,
 * weil die App eine Karte belasten will, nicht weil SumUp ein SDK hat. Wer das Terminal
 * wechselt, tauscht eine Implementierung; der Verkaufsbildschirm merkt nichts davon.
 *
 * Absichtlich nicht hier: Datei- und Fotoauswahl. Die brauchen einen Bildschirm, der sie
 * anfordert, und laufen deshalb als `@Composable` über [rememberFilePicker] und
 * Verwandte statt über dieses Objekt.
 */
interface Platform {

    /** "Android 14" oder "iPadOS 18.2" — für Diagnose und die Geräteliste am Server. */
    val description: String

    /** ANDROID oder IOS, für die Gerätekopplung. */
    val kind: PlatformKind

    /** Dauerhafter Schlüssel-Wert-Speicher für Einstellungen. */
    val settings: SettingsStore

    /** Kartenzahlung. Auf Geräten ohne eingerichtetes Terminal [PaymentProcessor.isAvailable] false. */
    val payments: PaymentProcessor

    /** Regelmäßige Sicherung im Hintergrund. */
    val backupScheduler: BackupScheduler
}

enum class PlatformKind { ANDROID, IOS }

/**
 * Der Zugang zur Plattform innerhalb der Oberfläche.
 *
 * `staticCompositionLocalOf`, weil sich der Wert über die Lebenszeit der App nicht
 * ändert — ein wechselnder Wert würde hier jeden Verbraucher neu zeichnen lassen,
 * und das wäre für etwas, das beim Start einmal gesetzt wird, reine Verschwendung.
 */
val LocalPlatform = staticCompositionLocalOf<Platform> {
    error(
        "LocalPlatform wurde nicht gesetzt. Die Plattform wird beim Start vom Host " +
            "bereitgestellt — unter Android in MainActivity, unter iOS in MainViewController."
    )
}

/**
 * Dauerhafte Einstellungen, Schlüssel gegen Wert.
 *
 * Bewusst klein gehalten: Die App speichert hier Vereinsname, Vereinsfarbe, Hell/Dunkel,
 * die API-Adresse und das Gerätetoken. Für mehr wäre die Datenbank zuständig.
 *
 * [putSecret] und [getSecret] landen im Schlüsselbund des Betriebssystems statt in den
 * gewöhnlichen Einstellungen. Dort liegen genau zwei Dinge: der SumUp-Affiliate-Key und
 * das Gerätetoken für den Server. Beides sind Zugangsdaten, und beides hat in einer
 * Sicherungsdatei nichts verloren.
 */
interface SettingsStore {
    suspend fun getString(key: String): String?
    suspend fun putString(key: String, value: String)
    suspend fun remove(key: String)

    suspend fun getSecret(key: String): String?
    suspend fun putSecret(key: String, value: String)
}

/**
 * Kartenzahlung über ein gekoppeltes Terminal.
 *
 * Die beiden SumUp-SDKs sind nicht dasselbe Produkt mit zwei Hüllen: Android arbeitet
 * über einen Activity-Result-Vertrag, iOS über Delegates und einen präsentierten
 * View-Controller. Gemeinsam ist nur, was hier steht — ein Betrag rein, ein Ergebnis
 * raus.
 */
interface PaymentProcessor {

    /** Ob auf diesem Gerät überhaupt kassiert werden kann: SDK vorhanden, Händler angemeldet. */
    suspend fun isAvailable(): Boolean

    /** Öffnet die Anmeldung des Anbieters. Ohne sie schlägt [charge] fehl. */
    suspend fun login(affiliateKey: String): Result<Unit>

    /**
     * Ob das Terminal den Gast selbst nach Trinkgeld fragt — bei SumUp der Solo und der Solo
     * Lite, sobald sie mit diesem Gerät gekoppelt sind. Dann fragt die App nicht; sonst bietet
     * sie das Trinkgeld selbst an.
     */
    suspend fun asksForTipOnTerminal(): Boolean = false

    /**
     * Belastet die Karte mit [amount] — Ware und Aufladung, ohne Trinkgeld.
     *
     * Trinkgeld kommt auf einem von zwei Wegen dazu. Mit [tipOnTerminal] fragt das Terminal den
     * Gast, und [tip] ist 0. Sonst ist [tip] das in der App gewählte Trinkgeld; es geht getrennt
     * an den Anbieter, damit es auf seinem Beleg als Trinkgeld steht. Gebucht wird, was
     * [PaymentResult.Success.tip] meldet — nicht, was die App vorher annahm.
     *
     * [reference] ist die transactionGroupId der Buchung. Sie geht an den Anbieter mit,
     * damit sich eine Zahlung im SumUp-Konto später einem Kassiervorgang zuordnen lässt —
     * ohne sie ist die Zuordnung im Streitfall Handarbeit.
     */
    suspend fun charge(amount: Double, reference: String, tip: Double = 0.0, tipOnTerminal: Boolean = false): PaymentResult
}

sealed interface PaymentResult {
    /** [providerTransactionId] ist die Nummer beim Anbieter, nicht die eigene; [tip] das Trinkgeld, das mit belastet wurde. */
    data class Success(val providerTransactionId: String?, val tip: Double = 0.0) : PaymentResult

    /** Der Kassier hat abgebrochen. Kein Fehler — die Kasse sagt es trotzdem kurz, bucht nichts und vergisst das Trinkgeld. */
    data object Cancelled : PaymentResult

    /** Nicht durchgegangen: [message] sagt dem Kassier, warum, und was er jetzt tun kann. */
    data class Failed(val message: String) : PaymentResult
}

/**
 * Regelmäßige Sicherung.
 *
 * Unter Android ist das WorkManager, unter iOS BGTaskScheduler. Die beiden geben
 * unterschiedlich starke Zusagen — iOS entscheidet selbst, wann und ob ein
 * Hintergrundlauf stattfindet, und knüpft das an das Nutzungsverhalten. Die Oberfläche
 * darf deshalb nicht versprechen, dass täglich gesichert wird; sie zeigt, wann zuletzt
 * gesichert wurde — das weiß das BackupRepository, nicht der Planer.
 */
interface BackupScheduler {
    /**
     * Verbindet den Planer mit der Arbeit, die er auslösen soll; einmal beim Start.
     * Der Planer löst aus, die App weiß, was zu tun ist — unter Android holt sich der
     * Worker die Arbeit selbst über die Application, dort ist das ein Leerlauf.
     */
    fun attach(work: suspend () -> Boolean)

    suspend fun enableDaily()
    suspend fun disable()
}

/**
 * Setzt die Systemleisten passend zum Thema.
 *
 * Unter Android ist das die Statusleiste über `WindowCompat`, unter iOS die Statuszeile
 * über den View-Controller. Als Composable, weil es sich mit dem Thema ändert.
 */
@Composable
expect fun SystemBarsEffect(darkTheme: Boolean)
