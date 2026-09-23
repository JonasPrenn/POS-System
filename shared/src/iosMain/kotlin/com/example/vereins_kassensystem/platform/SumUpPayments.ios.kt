package com.example.vereins_kassensystem.platform

import com.example.vereins_kassensystem.ui.format.Money
import kotlinx.coroutines.CompletableDeferred

/**
 * Kartenzahlung unter iOS.
 *
 * Das SumUp-iOS-SDK ist nicht dasselbe Produkt mit anderer Hülle: Es arbeitet über
 * `SumUpSDK.checkout(_:from:completion:)`, braucht einen präsentierenden
 * View-Controller und liefert das Ergebnis über einen Completion-Block. Dazu kommt, dass
 * es als Framework über CocoaPods oder SPM eingebunden wird und über Bluetooth mit einem
 * physischen Terminal spricht.
 *
 * Diese Klasse bindet nichts davon direkt an. Sie nimmt einen [SumUpBridge] entgegen,
 * den die Swift-Seite umsetzt — aus demselben Grund wie beim Schlüsselbund: Die Anbindung
 * eines Objective-C-Frameworks mit Completion-Blöcken von Kotlin/Native aus ist
 * machbar, aber jede Zeile davon ist eine Fehlerquelle, die sich erst mit echtem
 * Terminal in der Hand zeigt. In Swift ist es der Aufruf aus der SDK-Dokumentation.
 *
 * Die Brücke ist bewusst rückrufbasiert und nicht `suspend`: Swift kennt Kotlins
 * Coroutinen nicht, und ein Completion-Block ist das, was das SDK ohnehin liefert.
 */
interface SumUpBridge {
    fun isLoggedIn(): Boolean
    fun login(affiliateKey: String, onResult: (Boolean, String?) -> Unit)

    /** Ob das gekoppelte Terminal selbst nach Trinkgeld fragt (im iOS-SDK `isTipOnCardReaderAvailable`). */
    fun isTipOnTerminalAvailable(): Boolean

    /**
     * [amount] ohne Trinkgeld. Mit [tipOnTerminal] fragt das Terminal den Gast
     * (`tipOnCardReaderIfAvailable`); sonst geht [tip] getrennt mit (`tipAmount`).
     * [onResult] bekommt: erfolgreich, Transaktionsnummer beim Anbieter, belastetes
     * Trinkgeld, Fehlertext. Ein Abbruch durch den Kassier kommt als (false, null, null, null).
     */
    fun checkout(
        amount: Double,
        tip: Double,
        tipOnTerminal: Boolean,
        reference: String,
        onResult: (Boolean, String?, Double?, String?) -> Unit
    )
}

class IosSumUpPaymentProcessor(private val bridge: SumUpBridge) : PaymentProcessor {

    override suspend fun isAvailable(): Boolean = bridge.isLoggedIn()

    override suspend fun login(affiliateKey: String): Result<Unit> {
        if (affiliateKey.isBlank()) {
            return Result.failure(IllegalArgumentException("Kein Affiliate-Key hinterlegt."))
        }
        val deferred = CompletableDeferred<Result<Unit>>()
        bridge.login(affiliateKey) { ok, error ->
            deferred.complete(
                if (ok) Result.success(Unit)
                else Result.failure(IllegalStateException(error ?: "Anmeldung fehlgeschlagen."))
            )
        }
        return deferred.await()
    }

    override suspend fun asksForTipOnTerminal(): Boolean = bridge.isLoggedIn() && bridge.isTipOnTerminalAvailable()

    override suspend fun charge(amount: Double, reference: String, tip: Double, tipOnTerminal: Boolean): PaymentResult {
        if (!bridge.isLoggedIn()) return PaymentResult.Failed("Nicht bei SumUp angemeldet: unter Einstellungen → Kartenzahlung anmelden. Nichts gebucht.")

        val deferred = CompletableDeferred<PaymentResult>()
        bridge.checkout(amount, if (tipOnTerminal) 0.0 else tip, tipOnTerminal, reference) { ok, transactionId, chargedTip, error ->
            deferred.complete(
                when {
                    ok -> PaymentResult.Success(transactionId, Money.cents(chargedTip ?: if (tipOnTerminal) 0.0 else tip))
                    error != null -> PaymentResult.Failed("Kartenzahlung nicht durchgegangen. Nichts gebucht. (SumUp: $error)")
                    else -> PaymentResult.Cancelled
                }
            )
        }
        return deferred.await()
    }
}

/**
 * Fällt ein, wenn kein Terminal eingerichtet ist.
 *
 * Damit lässt sich die App auf dem Simulator und auf einem iPad ohne Kartenleser
 * vollständig bedienen — Bar und Deckel funktionieren, nur Karte meldet sich sauber ab,
 * statt beim Antippen abzustürzen.
 */
object UnavailablePaymentProcessor : PaymentProcessor {
    override suspend fun isAvailable(): Boolean = false

    override suspend fun login(affiliateKey: String): Result<Unit> =
        Result.failure(IllegalStateException("Auf diesem Gerät ist kein Kartenterminal eingerichtet."))

    override suspend fun charge(amount: Double, reference: String, tip: Double, tipOnTerminal: Boolean): PaymentResult =
        PaymentResult.Failed("Auf diesem Gerät ist kein Kartenterminal eingerichtet. Nichts gebucht.")
}
