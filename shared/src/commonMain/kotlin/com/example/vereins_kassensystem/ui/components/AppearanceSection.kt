package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.vereins_kassensystem.ui.theme.ThemeMode
import com.example.vereins_kassensystem.ui.icons.VdIcons

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
    VdSection(
        title = "Darstellung",
        icon = VdIcons.Contrast,
        modifier = modifier
    ) {
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
