package com.example.vereins_kassensystem.platform

import android.app.Activity
import android.app.Application
import android.content.Intent
import com.sumup.merchant.reader.api.SumUpAPI
import com.sumup.merchant.reader.api.SumUpLogin
import com.sumup.merchant.reader.api.SumUpPayment
import com.sumup.reader.sdk.api.SumUpState
import kotlinx.coroutines.CompletableDeferred
import java.math.BigDecimal

/**
 * Einmal beim Start der App, vor allem anderen. Liegt hier und nicht in der
 * Application, weil das SDK eine Abhängigkeit von :shared ist und das App-Modul seine
 * Klassen gar nicht sieht.
 */
fun initializeSumUp(application: Application) {
    SumUpState.init(application)
}

/**
 * Kartenzahlung über das SumUp-Android-SDK.
 *
 * Das SDK arbeitet über `startActivityForResult`: Der Aufruf kehrt sofort zurück, das
 * Ergebnis kommt später in `onActivityResult` an. Der Vertrag im geteilten Modul ist
 * dagegen eine `suspend`-Funktion, die das Ergebnis zurückgibt — der Verkaufsbildschirm
 * soll auf ein Ergebnis warten können, ohne zu wissen, wie Android Bildschirme stapelt.
 *
 * Die Brücke dazwischen ist [pending]: Der Aufruf legt ein unerfülltes Versprechen ab,
 * die Activity erfüllt es beim Eintreffen des Ergebnisses.
 *
 * Diese Klasse braucht deshalb eine Activity und nicht den Application-Context. Sie wird
 * in `MainActivity.onCreate` erzeugt und in `onDestroy` wieder gelöst, damit ein
 * Drehen des Geräts nicht die alte Activity am Leben hält.
 */
class SumUpPaymentProcessor : PaymentProcessor {

    private var activity: Activity? = null
    private var pending: CompletableDeferred<PaymentResult>? = null
    private var pendingLogin: CompletableDeferred<Result<Unit>>? = null

    fun attach(activity: Activity) {
        this.activity = activity
    }

    fun detach() {
        activity = null
        // Ein noch offenes Versprechen aufzulösen, statt es hängen zu lassen: Der
        // Aufrufer wartet sonst für immer, und der Warenkorb bliebe blockiert.
        pending?.complete(PaymentResult.Cancelled)
        pending = null
        pendingLogin?.complete(Result.failure(IllegalStateException("Bildschirm verlassen")))
        pendingLogin = null
    }

    override suspend fun isAvailable(): Boolean = SumUpAPI.isLoggedIn()

    override suspend fun login(affiliateKey: String): Result<Unit> {
        val act = activity ?: return Result.failure(
            IllegalStateException("Keine Activity — SumUp braucht einen sichtbaren Bildschirm.")
        )
        if (affiliateKey.isBlank()) {
            return Result.failure(IllegalArgumentException("Kein Affiliate-Key hinterlegt."))
        }
        val deferred = CompletableDeferred<Result<Unit>>()
        pendingLogin = deferred
        SumUpAPI.openLoginActivity(act, SumUpLogin.builder(affiliateKey).build(), REQUEST_LOGIN)
        return deferred.await()
    }

    override suspend fun charge(amount: Double, reference: String): PaymentResult {
        val act = activity ?: return PaymentResult.Failed("Kein sichtbarer Bildschirm.")
        if (!SumUpAPI.isLoggedIn()) return PaymentResult.Failed("Nicht bei SumUp angemeldet.")

        val payment = SumUpPayment.builder()
            .total(BigDecimal.valueOf(amount))
            .currency(SumUpPayment.Currency.EUR)
            // Geht als Referenz an SumUp mit, damit sich eine Zahlung im SumUp-Konto
            // später einem Kassiervorgang zuordnen lässt.
            .foreignTransactionId(reference)
            .skipSuccessScreen()
            .build()

        val deferred = CompletableDeferred<PaymentResult>()
        pending = deferred
        SumUpAPI.checkout(act, payment, REQUEST_CHECKOUT)
        return deferred.await()
    }

    /** Wird von `MainActivity.onActivityResult` aufgerufen. Gibt true, wenn zuständig. */
    fun handleActivityResult(requestCode: Int, data: Intent?): Boolean = when (requestCode) {
        REQUEST_CHECKOUT -> {
            val extras = data?.extras
            val code = extras?.getInt(SumUpAPI.Response.RESULT_CODE, -1) ?: -1
            val message = extras?.getString(SumUpAPI.Response.MESSAGE)
            val txCode = extras?.getString(SumUpAPI.Response.TX_CODE)

            pending?.complete(
                when (code) {
                    SumUpAPI.Response.ResultCode.SUCCESSFUL -> PaymentResult.Success(txCode)
                    // Ein Abbruch durch den Kassier ist kein Fehler und bekommt keine
                    // rote Meldung — er wollte es sich anders überlegen.
                    SumUpAPI.Response.ResultCode.ERROR_TRANSACTION_FAILED ->
                        PaymentResult.Failed(message ?: "Zahlung fehlgeschlagen.")
                    else -> PaymentResult.Cancelled
                }
            )
            pending = null
            true
        }

        REQUEST_LOGIN -> {
            pendingLogin?.complete(
                if (SumUpAPI.isLoggedIn()) Result.success(Unit)
                else Result.failure(IllegalStateException("Anmeldung nicht abgeschlossen."))
            )
            pendingLogin = null
            true
        }

        else -> false
    }

    private companion object {
        const val REQUEST_LOGIN = 1000
        const val REQUEST_CHECKOUT = 1001
    }
}
