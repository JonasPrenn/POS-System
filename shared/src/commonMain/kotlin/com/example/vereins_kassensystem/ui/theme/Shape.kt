package com.example.vereins_kassensystem.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * One radius per role, so same-role things stop looking different on different screens.
 *
 *   extraSmall  badges, quantity pills, category chips
 *   small       text fields, chips, small buttons
 *   medium      cards, list rows, product tiles
 *   large       dialogs, panels, hero cards
 *   extraLarge  bottom sheet top edge
 */
val Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)
