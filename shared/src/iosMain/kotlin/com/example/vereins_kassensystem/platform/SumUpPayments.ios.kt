package com.example.vereins_kassensystem.platform

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

    /**
     * [onResult] bekommt: erfolgreich, Transaktionsnummer beim Anbieter, Fehlertext.
     * Ein Abbruch durch den Kassier kommt als (false, null, null) — kein Fehlertext,
     * weil es keiner ist.
     */
    fun checkout(
        amount: Double,
        reference: String,
        onResult: (Boolean, String?, String?) -> Unit
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

    override suspend fun charge(amount: Double, reference: String): PaymentResult {
        if (!bridge.isLoggedIn()) return PaymentResult.Failed("Nicht bei SumUp angemeldet.")

        val deferred = CompletableDeferred<PaymentResult>()
        bridge.checkout(amount, reference) { ok, transactionId, error ->
            deferred.complete(
                when {
                    ok -> PaymentResult.Success(transactionId)
                    error != null -> PaymentResult.Failed(error)
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

    override suspend fun charge(amount: Double, reference: String): PaymentResult =
        PaymentResult.Failed("Auf diesem Gerät ist kein Kartenterminal eingerichtet.")
}
