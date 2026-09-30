package com.example.vereins_kassensystem.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Unter dieser Fensterhöhe wird es eng: ein 8-Zoll-Tablet quer, etwa das Galaxy Tab Active3 mit
 * 960 × 600 dp. Dann rückt zusammen, was sonst übereinander Platz hat — Tastenfelder stehen neben
 * dem Inhalt, der Gesamtbetrag wird Teil der Titelzeile, und die Verwaltung wandert in der
 * Navigation hinter „Mehr“. Gefragt wird die Höhe des Fensters, nicht die Ausrichtung: Ein
 * geteilter Bildschirm ist genauso niedrig.
 */
val CompactWindowHeight = 700.dp

/** Die Größe des Fensters in dp — für Dialoge, die sonst nur ihren eigenen Platz kennen. */
@Composable
fun windowSizeDp(): DpSize {
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { DpSize(size.width.toDp(), size.height.toDp()) }
}
