package com.example.vereins_kassensystem.server.payments

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Eine Aufladung online bezahlen lassen. Benannt nach dem, was der Verein will, nicht nach dem
 * Anbieter: Heute ist es SumUp — dasselbe Konto wie das Kartenterminal an der Bude —, ein zweiter
 * Anbieter wäre eine zweite Umsetzung dieser Schnittstelle.
 *
 * Bezahlt wird immer auf der Seite des Anbieters: Das Mitglied wird hingeschickt und kommt zurück.
 * Kartendaten berühren den Server nie, und die Verwaltung bleibt ohne fremde Skripte.
 */
interface OnlinePayments {
    /** Legt eine Zahlung an; bezahlt wird unter [Checkout.payUrl]. */
    fun create(account: PaymentAccount, request: CheckoutRequest): Checkout

    /** Der Stand beim Anbieter — nur er zählt, nie, was eine Benachrichtigung behauptet. */
    fun fetch(account: PaymentAccount, checkoutId: String): Checkout

    /** Was der Anbieter für dieses Konto außer der Karte freigeschaltet hat: apple_pay, google_pay, eps … */
    fun methods(account: PaymentAccount): List<String>
}

/** Das Konto eines Vereins beim Anbieter. */
class PaymentAccount(val apiKey: String, val merchantCode: String)

/**
 * [notifyUrl] ruft der Anbieter auf, wenn sich etwas tut; auf [returnUrl] kommt das Mitglied
 * nach dem Bezahlen zurück. [reference] ist die id der Aufladung.
 */
class CheckoutRequest(val reference: UUID, val amount: BigDecimal, val description: String, val notifyUrl: String, val returnUrl: String)

class Checkout(
    val id: String,
    val reference: String,
    val status: Status,
    val amount: BigDecimal,
    val currency: String,
    val merchantCode: String,
    val payUrl: String?,
    /** Die Kennung der erfolgreichen Zahlung beim Anbieter, für das Protokoll. */
    val transactionCode: String?,
) {
    enum class Status { PENDING, PAID, FAILED, EXPIRED }
}

/** Was der Anbieter ablehnt oder nicht beantwortet, in Worten. [retry]: später noch einmal versuchen. */
class PaymentProblem(message: String, val retry: Boolean = false) : RuntimeException(message)

/**
 * SumUp, Online-Zahlungen über die Checkout-API mit Bezahlseite bei SumUp (Hosted Checkout):
 * Karte, Apple Pay und Google Pay, für österreichische Konten auch EPS — was davon erscheint,
 * entscheidet SumUp nach dem, was für das Konto freigeschaltet ist.
 * https://developer.sumup.com/online-payments/checkouts/hosted-checkout
 */
class SumUp(
    private val base: String = "https://api.sumup.com",
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : OnlinePayments {

    override fun create(account: PaymentAccount, request: CheckoutRequest): Checkout {
        val body = buildJsonObject {
            put("checkout_reference", request.reference.toString())
            put("amount", JsonPrimitive(request.amount))
            put("currency", "EUR")
            put("merchant_code", account.merchantCode)
            put("description", request.description.take(80))
            put("return_url", request.notifyUrl)
            put("redirect_url", request.returnUrl)
            put("hosted_checkout", buildJsonObject { put("enabled", true) })
        }
        return checkoutOf(send(account, "POST", "/v0.1/checkouts", body.toString()))
    }

    override fun fetch(account: PaymentAccount, checkoutId: String): Checkout =
        checkoutOf(send(account, "GET", "/v0.1/checkouts/${encode(checkoutId)}"))

    override fun methods(account: PaymentAccount): List<String> {
        val answer = send(account, "GET", "/v0.1/merchants/${encode(account.merchantCode)}/payment-methods?amount=10&currency=EUR")
        return answer["available_payment_methods"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
    }

    private fun send(account: PaymentAccount, method: String, path: String, body: String? = null): JsonObject {
        val request = HttpRequest.newBuilder(URI(base + path))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer ${account.apiKey}")
            .header("Accept", "application/json")
            .apply { if (body != null) header("Content-Type", "application/json") }
            .method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw PaymentProblem("SumUp ist gerade nicht erreichbar.", retry = true)
        }
        val json = runCatching { JSON.parseToJsonElement(response.body()).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
        if (response.statusCode() in 200..299) return json
        val code = listOf("error_code", "error_message", "message", "detail").firstNotNullOfOrNull { json[it]?.jsonPrimitive?.contentOrNull }.orEmpty()
        throw when (response.statusCode()) {
            401 -> PaymentProblem("SumUp kennt diesen API-Schlüssel nicht. In SumUp unter Einstellungen, Für Entwickler, API-Schlüssel einen neuen anlegen.")
            403 -> if ("checkout_payments_not_allowed" in code || "FORBIDDEN" in code) {
                PaymentProblem("SumUp lässt für dieses Konto noch keine Online-Zahlungen zu. Beim SumUp-Support freischalten lassen (mit Händlercode).")
            } else PaymentProblem("SumUp verweigert das: $code")
            404 -> PaymentProblem("SumUp kennt diesen Händlercode nicht.")
            in 500..599 -> PaymentProblem("SumUp antwortet mit einem Fehler (${response.statusCode()}).", retry = true)
            else -> PaymentProblem("SumUp lehnt ab (${response.statusCode()}): ${code.ifBlank { "ohne Angabe" }}")
        }
    }

    private fun checkoutOf(json: JsonObject): Checkout {
        fun text(name: String) = json[name]?.jsonPrimitive?.contentOrNull
        return Checkout(
            id = text("id") ?: throw PaymentProblem("SumUp antwortet ohne Checkout-ID.", retry = true),
            reference = text("checkout_reference").orEmpty(),
            // Was diese Fassung nicht kennt, ist nicht bezahlt.
            status = Checkout.Status.entries.firstOrNull { it.name == text("status") } ?: Checkout.Status.PENDING,
            amount = text("amount")?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            currency = text("currency").orEmpty(),
            merchantCode = text("merchant_code").orEmpty(),
            payUrl = text("hosted_checkout_url"),
            transactionCode = text("transaction_code"),
        )
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        fun encode(part: String): String = URLEncoder.encode(part, Charsets.UTF_8).replace("+", "%20")
    }
}
