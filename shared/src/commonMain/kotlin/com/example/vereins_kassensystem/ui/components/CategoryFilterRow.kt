package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import com.example.vereins_kassensystem.ui.theme.Pill
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.Stroke
import com.example.vereins_kassensystem.ui.theme.VereinsColors
import com.example.vereins_kassensystem.ui.theme.categoryColor

/**
 * Category chips above the sales grid.
 *
 * The grid previously had no filter and no search, so finding one product in a full
 * catalogue meant scanning every tile. Each chip carries its category's colour dot, the
 * same one the tiles use, so the filter and the grid teach each other.
 *
 * @param selected null means "Alle" — no filter.
 * @param trailing ein Chip ganz am Ende, der keine Kategorie ist — im Verkauf „Ausgeblendet“.
 *   Ist er gewählt, ist es weder „Alle“ noch eine Kategorie.
 */
@Composable
fun CategoryFilterRow(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    trailing: TrailingChip? = null
) {
    if (categories.isEmpty() && trailing == null) return

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        item {
            FilterPill(
                label = "Alle",
                selected = selected == null && trailing?.selected != true,
                onClick = { onSelect(null) },
                height = TouchTarget.sales
            )
        }
        items(categories, key = { it }) { category ->
            FilterPill(
                label = category,
                selected = selected == category,
                onClick = { onSelect(if (selected == category) null else category) },
                dot = categoryColor(category),
                height = TouchTarget.sales
            )
        }
        // Immer der letzte: Er steht außerhalb der Kategorien.
        if (trailing != null) {
            // Ein Schlüssel, den keine Kategorie tragen kann: Sonst stießen „Ausgeblendet“ und eine gleichnamige Kategorie zusammen.
            item(key = "\u0000trailing") {
                FilterPill(label = trailing.label, selected = trailing.selected, onClick = trailing.onClick, height = TouchTarget.sales)
            }
        }
    }
}

/**
 * Eine Filter-Pille. Gewählt ist sie in Tinte gefüllt, sonst weiß mit Haarlinie — dieselbe
 * Sprache wie die Auswahl in der Verwaltung. Die Kategorie behält ihren Punkt, damit Filter und
 * Raster einander erklären.
 */
@Composable
fun FilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    enabled: Boolean = true,
    height: Dp = FilterPillHeight
) = FilterPill(
    selected = selected,
    onClick = onClick,
    label = { Text(label) },
    modifier = modifier,
    dot = dot,
    enabled = enabled,
    height = height
)

/** [FilterPill] mit eigenem Inhalt, etwa einem Betrag. */
@Composable
fun FilterPill(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    enabled: Boolean = true,
    height: Dp = FilterPillHeight
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { ProvideTextStyle(MaterialTheme.typography.labelLarge, label) },
        modifier = modifier.heightIn(min = height),
        enabled = enabled,
        shape = Pill,
        leadingIcon = dot?.let {
            {
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = CircleShape,
                    color = it,
                    border = if (selected) BorderStroke(Stroke.hairline, MaterialTheme.colorScheme.inverseOnSurface) else null,
                    content = {}
                )
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedContainerColor = MaterialTheme.colorScheme.inverseSurface,
            selectedLabelColor = MaterialTheme.colorScheme.inverseOnSurface
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = VereinsColors.hairline,
            selectedBorderColor = Color.Transparent,
            borderWidth = Stroke.hairline
        )
    )
}

/** Höher als Materials 32 dp. In Dialogen reicht das; über dem Verkaufsraster gilt [TouchTarget.sales]. */
private val FilterPillHeight = 44.dp

/** Der Chip am Ende der Reihe, der keine Kategorie ist. */
data class TrailingChip(val label: String, val selected: Boolean, val onClick: () -> Unit)
