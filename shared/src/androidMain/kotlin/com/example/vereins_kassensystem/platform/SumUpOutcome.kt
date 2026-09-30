package com.example.vereins_kassensystem.platform

import com.example.vereins_kassensystem.ui.format.Money
import com.sumup.merchant.reader.api.SumUpAPI

/**
 * Was eine Antwort von SumUp für die Kasse heißt — in Worten, die der Kassier am Samstagabend
 * versteht, und mit dem, was er jetzt tun kann. Jede Antwort außer „erfolgreich“ wird angezeigt;
 * SumUps eigener Text steht in Klammern dahinter, damit sich eine Störung später zuordnen lässt.
 *
 * Nur Zahlen und Text: Die Codes sind Konstanten des SDK, zur Laufzeit wird nichts davon geladen.
 * So läuft der Test auf der JVM, ohne Terminal und ohne Android.
 */
object SumUpOutcome {

    const val NOT_LOGGED_IN = "Nicht bei SumUp angemeldet: unter Einstellungen → Kartenzahlung anmelden. Nichts gebucht."

    /**
     * Das Ergebnis einer Kartenzahlung. Das Trinkgeld ist, was SumUp als belastet meldet
     * ([reportedTip], am Terminal gewählt oder aus der App mitgegeben); meldet SumUp keins, gilt
     * das in der App gewählte ([requestedTip]).
     */
    fun checkout(code: Int, message: String?, transactionCode: String?, reportedTip: Double?, requestedTip: Double): PaymentResult =
        if (code == SumUpAPI.Response.ResultCode.SUCCESSFUL) PaymentResult.Success(transactionCode, Money.cents(reportedTip ?: requestedTip))
        else PaymentResult.Failed(checkoutMessage(code, message))

    fun checkoutMessage(code: Int, message: String?): String = withSumUpText(
        when (code) {
            SumUpAPI.Response.ResultCode.ERROR_TRANSACTION_FAILED ->
                "Kartenzahlung nicht durchgegangen — abgelehnt oder am Terminal abgebrochen. Nichts gebucht: noch einmal versuchen oder anders bezahlen."
            SumUpAPI.Response.ResultCode.ERROR_GEOLOCATION_REQUIRED ->
                "SumUp braucht den Standort: am Tablet einschalten und der App erlauben. Nichts gebucht."
            SumUpAPI.Response.ResultCode.ERROR_INVALID_PARAM ->
                "SumUp lehnt die Angaben zur Zahlung ab. Nichts gebucht."
            SumUpAPI.Response.ResultCode.ERROR_INVALID_TOKEN ->
                "SumUp-Anmeldung abgelaufen: unter Einstellungen → Kartenzahlung neu anmelden. Nichts gebucht."
            SumUpAPI.Response.ResultCode.ERROR_NO_CONNECTIVITY ->
                "Kein Internet: SumUp braucht eine Verbindung für die Kartenzahlung. Nichts gebucht."
            SumUpAPI.Response.ResultCode.ERROR_PERMISSION_DENIED ->
                "SumUp fehlt eine Berechtigung (Bluetooth oder Standort): in den Android-Einstellungen der App erlauben. Nichts gebucht."
            SumUpAPI.Response.ResultCode.ERROR_NOT_LOGGED_IN -> NOT_LOGGED_IN
            SumUpAPI.Response.ResultCode.ERROR_DUPLICATE_FOREIGN_TX_ID ->
                "SumUp kennt diesen Vorgang schon. Nichts gebucht: bitte noch einmal bezahlen."
            SumUpAPI.Response.ResultCode.ERROR_INVALID_AFFILIATE_KEY ->
                "Der SumUp-Schlüssel ist ungültig: dort prüfen, wo er eingetragen ist — in der Verwaltung oder unter Einstellungen. Nichts gebucht."
            SumUpAPI.Response.ResultCode.ERROR_INVALID_AMOUNT_DECIMALS ->
                "SumUp lehnt den Betrag ab (Nachkommastellen). Nichts gebucht."
            SumUpAPI.Response.ResultCode.ERROR_API_LEVEL_TOO_LOW ->
                "Diese Android-Version ist für SumUp zu alt. Nichts gebucht."
            SumUpAPI.Response.ResultCode.ERROR_CARD_READER_SETTINGS_OFF ->
                "Bluetooth oder Standort ist aus: für das Kartenterminal einschalten. Nichts gebucht."
            SumUpAPI.Response.ResultCode.ERROR_UNKNOWN_TRANSACTION_STATUS ->
                "SumUp weiß nicht, ob die Zahlung durchging: erst im SumUp-Konto nachsehen, dann erneut kassieren. Nichts gebucht."
            else -> "SumUp meldet eine Störung (Code $code). Nichts gebucht."
        },
        message
    )

    /** Das Ergebnis der Anmeldung. Ohne Antwort hat der Kassier den Anmeldebildschirm verlassen. */
    fun login(loggedIn: Boolean, code: Int?, message: String?): Result<Unit> = when {
        loggedIn -> Result.success(Unit)
        code == null -> Result.failure(IllegalStateException("SumUp-Anmeldung abgebrochen."))
        else -> Result.failure(IllegalStateException(loginMessage(code, message)))
    }

    fun loginMessage(code: Int, message: String?): String = withSumUpText(
        when (code) {
            SumUpAPI.Response.ResultCode.ERROR_INVALID_AFFILIATE_KEY ->
                "Der SumUp-Schlüssel ist ungültig: dort prüfen, wo er eingetragen ist — in der Verwaltung oder unter Einstellungen."
            SumUpAPI.Response.ResultCode.ERROR_NO_CONNECTIVITY -> "Kein Internet: Die Anmeldung bei SumUp braucht eine Verbindung."
            SumUpAPI.Response.ResultCode.ERROR_API_LEVEL_TOO_LOW -> "Diese Android-Version ist für SumUp zu alt."
            else -> "SumUp-Anmeldung fehlgeschlagen (Code $code)."
        },
        message
    )

    private fun withSumUpText(text: String, message: String?): String =
        if (message.isNullOrBlank()) text else "$text (SumUp: ${message.trim()})"
}
