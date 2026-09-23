package com.example.vereins_kassensystem.platform

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.core.os.BundleCompat
import com.sumup.checkout.core.models.TransactionInfo
import com.sumup.merchant.reader.api.SumUpAPI
import com.sumup.merchant.reader.api.SumUpLogin
import com.sumup.merchant.reader.api.SumUpPayment
import com.sumup.reader.sdk.api.SumUpState
import kotlinx.coroutines.CompletableDeferred
import java.math.BigDecimal
import java.math.RoundingMode

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
 * Trinkgeld: Fragt das Terminal selbst ([asksForTipOnTerminal]), bekommt SumUp
 * `tipOnCardReader()`; sonst geht ein in der App gewähltes Trinkgeld über `tip()` getrennt
 * mit. Was SumUp als Trinkgeld belastet hat, steht danach in der `TransactionInfo` — das
 * bucht die Kasse. Was eine Antwort für den Kassier heißt, steht in [SumUpOutcome].
 *
 * Diese Klasse braucht eine Activity und nicht den Application-Context. Sie wird
 * in `MainActivity.onCreate` erzeugt und in `onDestroy` wieder gelöst, damit ein
 * Drehen des Geräts nicht die alte Activity am Leben hält.
 */
class SumUpPaymentProcessor : PaymentProcessor {

    private var activity: Activity? = null
    private var pending: CompletableDeferred<PaymentResult>? = null
    private var pendingLogin: CompletableDeferred<Result<Unit>>? = null

    /** Das in der App gewählte Trinkgeld der laufenden Zahlung — falls SumUp keins zurückmeldet. */
    private var requestedTip = 0.0

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

    /**
     * Wie das SDK es selbst entscheidet: ein Solo ab Firmware 3.3.12.1 oder ein Solo Lite ab
     * 2.2.1.29, der mit diesem Gerät gekoppelt ist. Vor der ersten Kopplung weiß das SDK es noch
     * nicht — dann bietet die App das Trinkgeld an.
     */
    override suspend fun asksForTipOnTerminal(): Boolean =
        runCatching { SumUpAPI.isLoggedIn() && SumUpAPI.isTipOnCardReaderAvailable() }.getOrDefault(false)

    override suspend fun login(affiliateKey: String): Result<Unit> {
        val act = activity ?: return Result.failure(
            IllegalStateException("Keine Activity — SumUp braucht einen sichtbaren Bildschirm.")
        )
        if (affiliateKey.isBlank()) {
            return Result.failure(IllegalArgumentException("Kein SumUp-Schlüssel hinterlegt."))
        }
        val deferred = CompletableDeferred<Result<Unit>>()
        pendingLogin = deferred
        SumUpAPI.openLoginActivity(act, SumUpLogin.builder(affiliateKey).build(), REQUEST_LOGIN)
        return deferred.await()
    }

    override suspend fun charge(amount: Double, reference: String, tip: Double, tipOnTerminal: Boolean): PaymentResult {
        val act = activity ?: return PaymentResult.Failed("Kartenzahlung nicht möglich: kein sichtbarer Bildschirm. Nichts gebucht, bitte noch einmal.")
        if (!SumUpAPI.isLoggedIn()) return PaymentResult.Failed(SumUpOutcome.NOT_LOGGED_IN)

        val payment = SumUpPayment.builder()
            .total(cents(amount))
            .currency(SumUpPayment.Currency.EUR)
            // Geht als Referenz an SumUp mit, damit sich eine Zahlung im SumUp-Konto
            // später einem Kassiervorgang zuordnen lässt.
            .foreignTransactionId(reference)
            .skipSuccessScreen()
            .apply {
                // Das Terminal fragt den Gast — oder das in der App gewählte Trinkgeld steht
                // getrennt auf dem SumUp-Beleg. `tip()` nimmt SumUp nur ohne `tipOnCardReader()`.
                if (tipOnTerminal) tipOnCardReader() else if (tip > 0.0) tip(cents(tip))
            }
            .build()
        requestedTip = if (tipOnTerminal) 0.0 else tip

        val deferred = CompletableDeferred<PaymentResult>()
        pending = deferred
        SumUpAPI.checkout(act, payment, REQUEST_CHECKOUT)
        return deferred.await()
    }

    /** Wird von `MainActivity.onActivityResult` aufgerufen. Gibt true, wenn zuständig. */
    fun handleActivityResult(requestCode: Int, data: Intent?): Boolean = when (requestCode) {
        REQUEST_CHECKOUT -> {
            val extras = data?.extras
            pending?.complete(
                // Ohne Antwort hat der Kassier den SumUp-Bildschirm mit „Zurück“ verlassen.
                if (extras == null) PaymentResult.Cancelled
                else SumUpOutcome.checkout(
                    code = extras.getInt(SumUpAPI.Response.RESULT_CODE, -1),
                    message = extras.getString(SumUpAPI.Response.MESSAGE),
                    transactionCode = extras.getString(SumUpAPI.Response.TX_CODE),
                    reportedTip = tipOf(extras),
                    requestedTip = requestedTip,
                )
            )
            pending = null
            true
        }

        REQUEST_LOGIN -> {
            val extras = data?.extras
            pendingLogin?.complete(
                SumUpOutcome.login(
                    loggedIn = SumUpAPI.isLoggedIn(),
                    code = extras?.getInt(SumUpAPI.Response.RESULT_CODE, -1),
                    message = extras?.getString(SumUpAPI.Response.MESSAGE),
                )
            )
            pendingLogin = null
            true
        }

        else -> false
    }

    /** Was SumUp als Trinkgeld belastet hat, laut der mitgelieferten `TransactionInfo`. */
    private fun tipOf(extras: Bundle): Double? = runCatching {
        BundleCompat.getParcelable(extras, SumUpAPI.Response.TX_INFO, TransactionInfo::class.java)?.tipAmount
    }.getOrNull()

    private fun cents(amount: Double): BigDecimal = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP)

    private companion object {
        const val REQUEST_LOGIN = 1000
        const val REQUEST_CHECKOUT = 1001
    }
}
