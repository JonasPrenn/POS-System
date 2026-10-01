package com.example.vereins_kassensystem.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * VereinsDeckel-Palette, seit Oktober 2026: der Kassen-Standard, mit SumUp als Maßstab.
 *
 * Weiße Flächen auf warmem Hellgrau, Tinte als Grundstimme. Farbe steht nur dort, wo sie
 * etwas bedeutet, und jede Farbe hat genau eine Aufgabe:
 *
 *  - Tinte     alltägliche Hauptaktionen, Auswahl, Navigation ohne Vereinsfarbe
 *  - Grün      Geld und Bestätigung: Bezahlen, Einnahmen, ein Saldo im Plus, „gebucht“
 *  - Messing   der Deckel: das gewählte Mitglied, Aufladung, Trinkgeld
 *  - Blau      Karte und Auswertung
 *  - Bernstein Aufmerksamkeit: Bestand niedrig, Saldo nahe am Limit — siehe [ExtendedColors]
 *  - Rot       Zerstörung und Fehlschlag, sonst nie
 *
 * Gemessen, nicht geschätzt: Jedes Paar aus Schrift und Grund liegt über 4,5:1 (WCAG AA),
 * die meisten über 6:1. [Stone500] als Rand von Eingabefeldern liegt bei 3,4:1, darüber
 * entscheidet die 3:1-Schwelle für Bedienelemente. [Stone200] ist eine reine Trennlinie.
 *
 * Messing und Bernstein liegen weit genug auseinander, dass sie nebeneinander nicht verwechselt
 * werden: Messing ist ein olivstichiges Gold, Bernstein ein Orange. Rot ist karminrot, damit es
 * sich wiederum vom Orange abhebt.
 *
 * Die Werte stehen ein zweites Mal in `server/src/main/resources/web/app.css`. Wer hier
 * eine Farbe ändert, zieht sie dort nach.
 */

// ------------------------------------------------------------------ Tinte und Stein
val Ink = Color(0xFF141414)
val White = Color(0xFFFFFFFF)

/** Warme Neutraltöne, hell. 50 ist der Grund, auf dem die weißen Flächen liegen. */
val Stone50 = Color(0xFFF4F3EF)
val Stone100 = Color(0xFFEDECE7)
val Stone150 = Color(0xFFE6E4DE)
val Stone200 = Color(0xFFDEDCD6)
val Stone500 = Color(0xFF8F8B83)
val Stone600 = Color(0xFF6B6862)
val Stone700 = Color(0xFF5E5B55)

/** Warme Neutraltöne, dunkel: der Raum am Abend. 950 ist der Grund. */
val Night950 = Color(0xFF121211)
val Night900 = Color(0xFF1C1B19)
val Night850 = Color(0xFF242321)
val Night800 = Color(0xFF2C2B28)
val Night700 = Color(0xFF3A3835)
val Night500 = Color(0xFF7A766F)
val Night300 = Color(0xFFA8A49C)
val Paper = Color(0xFFF2F1EC)

// ------------------------------------------------------------------- Grün: Geld
val Green = Color(0xFF0B7A3B)
val GreenTint = Color(0xFFE3F2E7)
val GreenDeep = Color(0xFF0B6430)
val GreenNight = Color(0xFF46C46F)
val GreenNightInk = Color(0xFF0C1F12)
val GreenNightTint = Color(0xFF17301F)
val GreenNightSoft = Color(0xFF9FE3B5)

// ---------------------------------------------------------------- Messing: Deckel
val Brass = Color(0xFF76611A)
val BrassTint = Color(0xFFF4EFD9)
val BrassDeep = Color(0xFF5E4D12)
val BrassNight = Color(0xFFD9BE6E)
val BrassNightInk = Color(0xFF221C06)
val BrassNightTint = Color(0xFF34301A)
val BrassNightSoft = Color(0xFFF1E3B8)

// ----------------------------------------------------------- Blau: Karte, Auswertung
val Blue = Color(0xFF1F5CC9)
val BlueTint = Color(0xFFE6EDFB)
val BlueDeep = Color(0xFF1A4FAE)
val BlueNight = Color(0xFF86AEF7)
val BlueNightInk = Color(0xFF0B1A33)
val BlueNightTint = Color(0xFF1A2A47)
val BlueNightSoft = Color(0xFFC9DAFB)

// ------------------------------------------------------------ Bernstein: Achtung
val Amber = Color(0xFFA84B00)
val AmberTint = Color(0xFFFDEBDC)
val AmberDeep = Color(0xFF8A3D00)
val AmberNight = Color(0xFFFF9A4A)
val AmberNightInk = Color(0xFF2B1300)
val AmberNightTint = Color(0xFF452410)
val AmberNightSoft = Color(0xFFFFD4B3)

// ------------------------------------------------------------------ Rot: Fehler
val Red = Color(0xFFB8232F)
val RedTint = Color(0xFFFBE6E7)
val RedDeep = Color(0xFF951B25)
val RedNight = Color(0xFFFF6B76)
val RedNightInk = Color(0xFF3A0D06)
val RedNightTint = Color(0xFF4A1820)
val RedNightSoft = Color(0xFFFFCDD1)
