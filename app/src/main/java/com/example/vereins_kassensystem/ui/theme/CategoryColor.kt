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

private val CategoryAccents = listOf(
    CategoryAccent(Color(0xFF146B4C), Color(0xFF8ED8B6)), // pine
    CategoryAccent(Color(0xFF2B5C87), Color(0xFF9BCBFA)), // harbor
    CategoryAccent(Color(0xFF7A5314), Color(0xFFE9C68A)), // brass
    CategoryAccent(Color(0xFFA03E00), Color(0xFFFFB77C)), // ember
    CategoryAccent(Color(0xFF6A3A63), Color(0xFFE2B3DA)), // plum
    CategoryAccent(Color(0xFF4A6316), Color(0xFFBFD68A)), // moss
    CategoryAccent(Color(0xFF16606B), Color(0xFF86D3DE)), // teal
    CategoryAccent(Color(0xFF8A4038), Color(0xFFF5B5AC))  // clay
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
