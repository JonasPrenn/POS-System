package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.example.vereins_kassensystem.ui.theme.ClubTheme
import com.example.vereins_kassensystem.ui.theme.Spacing

/**
 * A titled block of settings.
 *
 * Settings was five blocks built three different ways: two composed sections on
 * `surfaceContainer`, and three elevated `Card`s carrying a shadow the rest of the app
 * does not use. Elevation is the odd one out here — this palette separates surfaces by
 * tone, so a raised card reads as a different material rather than a heading.
 *
 * The header mark is tinted with the club accent by default. Section icons are chrome,
 * not signal, and the fixed palette's meanings (pine confirms, ember warns, red destroys)
 * stay reserved for the things that actually do those jobs.
 */
@Composable
fun VdSection(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    iconTint: Color = ClubTheme.accent,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentSpacing: Dp = Spacing.md,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null, // the title beside it says the same thing
                    tint = iconTint
                )
                Spacer(Modifier.width(Spacing.md))
                Text(text = title, style = MaterialTheme.typography.titleMedium)
            }

            Spacer(Modifier.height(Spacing.lg))

            Column(verticalArrangement = Arrangement.spacedBy(contentSpacing), content = content)
        }
    }
}
