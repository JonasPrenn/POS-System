package com.example.vereins_kassensystem.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * One radius per role, so same-role things stop looking different on different screens.
 *
 *   extraSmall  badges, quantity pills, Bestandszähler
 *   small       Eingabefelder, Tasten des Ziffernfelds, Zählfelder
 *   medium      Kacheln, Karten, Listenzeilen
 *   large       Dialoge, Spalten, große Flächen
 *   extraLarge  Oberkante der unteren Schublade
 *
 * Kassen-Standard: kleinere Radien als Materials Vorgabe, dafür sind Knöpfe und Filter
 * ganz rund ([Pill]). Die Pille ist das eine Zeichen für „hier kann man tippen“.
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/** Knöpfe, Filter-Pillen, Zustandsmarken. */
val Pill: Shape = CircleShape
