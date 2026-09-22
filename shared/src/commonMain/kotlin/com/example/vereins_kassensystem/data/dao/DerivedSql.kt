package com.example.vereins_kassensystem.data.dao

import com.example.vereins_kassensystem.data.Ledger

/*
 * Die hergeleiteten Werte als SQL (Spezifikation 2.2 und 2.3).
 *
 * Als Konstanten, weil mehrere Abfragen sie brauchen und eine Regel, die an zwei Stellen
 * steht, früher oder später an einer geändert wird. Die Saldoregel gibt es trotzdem
 * dreimal — hier, in `Ledger` (Kotlin) und als Sicht `member_balances` auf dem Server —,
 * weil jede der drei Stellen ihre eigene Sprache spricht; `DerivedValuesTest` hält diese
 * gegen `Ledger`, `SyncTest` im Server die Sicht.
 */

/** Wirkung einer Buchungszeile `t` auf den Deckel — dieselbe Regel wie `Ledger.balanceEffect`. */
internal const val BALANCE_EFFECT =
    "(CASE " +
        "WHEN t.productId = '${Ledger.TOPUP_REF}' THEN t.price * t.quantity " +
        "WHEN t.paymentType = '${Ledger.MEMBER_BALANCE}' THEN -(t.price * t.quantity - t.discountAmount) " +
        "ELSE 0 END " +
        "* CASE WHEN t.isRefund THEN -1 ELSE 1 END)"

/**
 * Ein Mitglied mit Saldo und letzter Nutzung. Auf Cent gerundet, damit die Prüfung gegen
 * das Limit nicht an der Gleitkommasumme von 4,20 + 4,20 + … scheitert.
 */
internal const val MEMBER_SELECT =
    "SELECT m.id AS id, m.name AS name, m.nickname AS nickname, m.categoryId AS categoryId, m.blockedReason AS blockedReason, " +
        "ROUND(COALESCE((SELECT SUM($BALANCE_EFFECT) FROM transactions t " +
        "WHERE t.memberId = m.id AND t.deleted = 0), 0), 2) AS balance, " +
        // Korrekturen zählen nicht: Eine Übernahmebuchung ist kein Besuch an der Theke.
        "COALESCE((SELECT MAX(t.timestamp) FROM transactions t " +
        "WHERE t.memberId = m.id AND t.deleted = 0 AND t.paymentType != 'CORRECTION'), 0) AS lastUsedTimestamp " +
        "FROM members m"

/** Ein Lagerartikel mit Stückbestand: Wareneingänge ohne Gebindegröße minus Lagerabgänge. */
internal const val STOCK_ITEM_SELECT =
    "SELECT s.id AS id, s.name AS name, s.unit AS unit, s.tracking AS tracking, s.minLevel AS minLevel, " +
        "ROUND(COALESCE((SELECT SUM(e.quantity) FROM stock_entries e " +
        "WHERE e.stockItemId = s.id AND e.containerTypeId IS NULL AND e.deleted = 0), 0) " +
        "- COALESCE((SELECT SUM(d.volume) FROM stock_draws d " +
        "WHERE d.stockItemId = s.id AND d.deleted = 0), 0), 6) AS simpleQuantity " +
        "FROM stock_items s"

/** Eine Gebindegröße mit vollen Gebinden: Wareneingänge dieser Größe minus Anstiche. */
internal const val CONTAINER_TYPE_SELECT =
    "SELECT c.id AS id, c.stockItemId AS stockItemId, c.label AS label, c.nominalSize AS nominalSize, " +
        "c.initialYieldEstimate AS initialYieldEstimate, " +
        "CAST(ROUND(COALESCE((SELECT SUM(e.quantity) FROM stock_entries e " +
        "WHERE e.containerTypeId = c.id AND e.deleted = 0), 0)) AS INTEGER) " +
        "- (SELECT COUNT(*) FROM tapped_containers t WHERE t.containerTypeId = c.id AND t.deleted = 0) AS fullCount " +
        "FROM container_types c"

/**
 * Ein Anstich mit dem, was seither gezapft wurde: die Lagerabgänge des Artikels im
 * Zeitfenster [openedAt, closedAt). Über die Zeit statt über einen Verweis, siehe StockDraw.
 */
internal const val TAPPED_SELECT =
    "SELECT t.id AS id, t.containerTypeId AS containerTypeId, t.openedAt AS openedAt, t.closedAt AS closedAt, " +
        "t.closeReason AS closeReason, t.discardedVolume AS discardedVolume, t.note AS note, " +
        "COALESCE((SELECT SUM(d.volume) FROM stock_draws d " +
        "WHERE d.deleted = 0 AND d.stockItemId = c.stockItemId AND d.timestamp >= t.openedAt " +
        "AND (t.closedAt IS NULL OR d.timestamp < t.closedAt)), 0) AS drawn " +
        "FROM tapped_containers t JOIN container_types c ON c.id = t.containerTypeId"
