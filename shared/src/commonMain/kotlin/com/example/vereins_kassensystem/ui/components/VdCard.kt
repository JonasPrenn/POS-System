package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.SegmentedButtonColors
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.Stroke
import com.example.vereins_kassensystem.ui.theme.VereinsColors

/** Die Haarlinie um eine weiße Fläche. */
@Composable
@ReadOnlyComposable
fun hairline(color: Color = VereinsColors.hairline): BorderStroke = BorderStroke(Stroke.hairline, color)

/** Die Linie um etwas Gewähltes: Tinte, zwei Pixel. */
@Composable
@ReadOnlyComposable
fun selectedOutline(color: Color = MaterialTheme.colorScheme.primary): BorderStroke =
    BorderStroke(Stroke.selected, color)

/**
 * Die Grundfläche des Kassen-Standards: weiß auf dem hellgrauen Grund, eine Haarlinie, kein
 * Schatten. Kacheln, Abschnitte, Kennzahlen und Spalten stehen alle auf ihr, damit sie gleich
 * aussehen.
 */
@Composable
fun VdCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    border: BorderStroke? = hairline(),
    padding: Dp = Spacing.lg,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val inner: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(padding), content = content)
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = color, border = border, content = inner)
    } else {
        Surface(modifier = modifier, shape = shape, color = color, border = border, content = inner)
    }
}

/**
 * Farben für Umschalter (Zeitraum, Hell/Dunkel). Gewählt ist Tinte, wie jede Auswahl — Messing
 * bleibt dem Deckel.
 */
@Composable
fun vdSegmentedColors(): SegmentedButtonColors = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.inverseSurface,
    activeContentColor = MaterialTheme.colorScheme.inverseOnSurface,
    activeBorderColor = MaterialTheme.colorScheme.inverseSurface,
    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
    inactiveBorderColor = MaterialTheme.colorScheme.outline
)

/** Suchfelder sind Pillen auf weißem Grund — wie die Suche der Verwaltung. */
@Composable
fun searchFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
)
