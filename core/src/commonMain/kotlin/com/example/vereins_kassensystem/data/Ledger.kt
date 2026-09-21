package com.example.vereins_kassensystem.data

/**
 * Die Saldoregel: was eine Buchungszeile mit dem Deckel macht.
 *
 * Der Saldo wird nicht mehr gespeichert, sondern aus den Buchungen abgeleitet
 * (Spezifikation 2.2). Damit App und Server dieselbe Zahl anzeigen, steht die Regel
 * genau einmal in Kotlin — hier — und einmal als SQL-Sicht `member_balances` auf dem
 * Server. Der Servertest prüft, dass beide übereinstimmen.
 *
 * Die Regel folgt der Spezifikation, nicht in jedem Detail dem heutigen
 * `SalesViewModel`: dort zieht ein Verkauf auf den Deckel den vollen Preis ab, obwohl
 * ein Rabatt gespeichert wird, und ein Trinkgeld auf den Deckel wird gar nicht
 * abgezogen. Beides ist mit Schritt 7 zu entscheiden; bis dahin gilt diese Fassung nur
 * auf dem Server.
 */
object Ledger {

    /** Guthabenbewegung — die alte `TOPUP_PRODUCT_ID = -1L`. `price` trägt das Vorzeichen. */
    const val TOPUP_REF = "00000000-0000-0000-0000-000000000000"

    /** Manuell eingetippter Betrag an der Theke — die alte `-2L`. */
    const val MANUAL_REF = "00000000-0000-0000-0000-000000000002"

    /** Trinkgeld — die alte `-3L`. */
    const val TIP_REF = "00000000-0000-0000-0000-000000000003"

    const val MEMBER_BALANCE = "MEMBER_BALANCE"

    /**
     * Wirkung einer Zeile auf den Deckel des Mitglieds, in Euro.
     *
     * Positiv erhöht das Guthaben. Aufladungen zählen unabhängig von der Zahlart; ein
     * Verkauf zählt nur, wenn er auf den Deckel ging, und dann abzüglich Rabatt. Bar und
     * Karte lassen den Deckel unberührt. Eine Stornozeile kehrt das Vorzeichen um.
     */
    fun balanceEffect(
        productRef: String,
        paymentType: String,
        price: Double,
        quantity: Int,
        discountAmount: Double = 0.0,
        isRefund: Boolean = false
    ): Double {
        val effect = when {
            productRef == TOPUP_REF -> price * quantity
            paymentType == MEMBER_BALANCE -> -(price * quantity - discountAmount)
            else -> 0.0
        }
        return if (isRefund) -effect else effect
    }
}
