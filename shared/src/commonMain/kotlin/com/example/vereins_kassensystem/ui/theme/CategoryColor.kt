package com.example.vereins_kassensystem.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.absoluteValue

/**
 * Colour coding for product categories — the cheapest speed win on the sales grid.
 * Scanning for "the green ones" beats reading eight tile captions.
 *
 * Picked from a curated set rather than generated from a hue wheel: free-form hues drift
 * out of the palette and land on unreadable pairs. Each entry is checked in both themes
 * (every one clears AA as a filled chip with white text, and against the dark surface).
 *
 * Assignment is by name hash, so a category keeps its colour across restarts and devices
 * without anything being stored.
 */
data class CategoryAccent(val light: Color, val dark: Color)

// Bewusst ohne Grün, Blau, Messing, Bernstein und Rot: Diese fünf tragen Bedeutung, und ein
// grüner Punkt an „Getränke“ läse sich als Geld. Die Kategorie bekommt Farben, die nichts sagen.
private val CategoryAccents = listOf(
    CategoryAccent(Color(0xFF7A3F72), Color(0xFFE2B3DA)), // pflaume
    CategoryAccent(Color(0xFF0E6F7A), Color(0xFF86D3DE)), // petrol
    CategoryAccent(Color(0xFF5E48B5), Color(0xFFBDB2F0)), // violett
    CategoryAccent(Color(0xFFA3326F), Color(0xFFF2A9CF)), // magenta
    CategoryAccent(Color(0xFF5A5F6B), Color(0xFFB7BCC7)), // schiefer
    CategoryAccent(Color(0xFF4A4F8C), Color(0xFFB4B8E8))  // tinte-blau
)

/**
 * The accent for a category name, stable for a given name.
 *
 * Uses [String.hashCode] rather than a random or list-position pick so the same category
 * is the same colour everywhere it appears — grid, filter chip, receipt line — and stays
 * that colour when categories are added or renamed around it.
 */
@Composable
@ReadOnlyComposable
fun categoryColor(category: String): Color {
    if (category.isBlank()) return MaterialTheme.colorScheme.onSurfaceVariant
    // toLong() first: abs(Int.MIN_VALUE) is still negative and would blow the index.
    val index = (category.lowercase().hashCode().toLong().absoluteValue % CategoryAccents.size).toInt()
    val accent = CategoryAccents[index]
    return if (isDarkPalette()) accent.dark else accent.light
}

/**
 * Whether the active scheme is the dark one. Derived from surface luminance rather than
 * threaded through as a parameter, so components stay callable from previews.
 */
@Composable
@ReadOnlyComposable
fun isDarkPalette(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f
