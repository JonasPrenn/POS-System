package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.vereins_kassensystem.ui.theme.ClubTheme
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.ThemeMode

/**
 * Light or dark, as a real choice rather than a straight follow of the system.
 *
 * A tablet mounted behind the bar wants to be pinned to dark for the evening whatever the
 * device thinks, and to light for a midday beer garden where the screen is being read in
 * direct sun.
 */
@Composable
fun AppearanceSection(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Contrast,
                    contentDescription = null,
                    tint = ClubTheme.accent
                )
                Spacer(Modifier.width(Spacing.md))
                Text("Darstellung", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(Spacing.lg))

            val options = listOf(
                ThemeMode.SYSTEM to "System",
                ThemeMode.LIGHT to "Hell",
                ThemeMode.DARK to "Dunkel"
            )

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}
