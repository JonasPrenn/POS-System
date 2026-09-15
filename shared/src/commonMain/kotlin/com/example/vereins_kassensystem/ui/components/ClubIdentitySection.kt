package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.ui.theme.ClubAccentPresets
import com.example.vereins_kassensystem.ui.theme.ClubIdentity
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import com.example.vereins_kassensystem.ui.theme.contrastingOn
import com.example.vereins_kassensystem.ui.icons.VdIcons

/**
 * Where a Verein makes the app its own.
 *
 * The colour reaches navigation, the club header and member avatars — and stops there.
 * Pay buttons, warnings and errors keep the fixed palette, because a red club must not
 * end up confirming payments in the same colour the app reports failures in. The note
 * under the swatches says so, so the limit reads as a decision rather than an oversight.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClubIdentitySection(
    identity: ClubIdentity,
    onNameChange: (String) -> Unit,
    onAccentChange: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    VdSection(
        title = "Verein",
        icon = VdIcons.Groups,
        modifier = modifier,
        // The section's own colour picker is right below it, so the mark follows the
        // pending choice rather than the theme, which only catches up once it is saved.
        iconTint = identity.accent,
        contentSpacing = Spacing.lg
    ) {
        OutlinedTextField(
            value = identity.name,
            onValueChange = onNameChange,
            label = { Text("Vereinsname") },
            placeholder = { Text("z. B. TSV Beispiel") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.small
        )

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                text = "Vereinsfarbe",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                ClubAccentPresets.forEach { (name, color) ->
                    AccentSwatch(
                        name = name,
                        color = color,
                        selected = color.value == identity.accent.value,
                        onClick = { onAccentChange(color) }
                    )
                }
            }
        }

        Text(
            text = "Die Vereinsfarbe färbt Navigation, Kopfzeile und Mitglieder-Symbole. " +
                "Bezahlen bleibt grün, Warnungen orange und Fehler rot – damit die Farben " +
                "im Verkauf immer dasselbe bedeuten.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AccentSwatch(
    name: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    // The colour name cannot be drawn on a swatch this size, so it becomes the label.
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(TouchTarget.min)
            .semantics {
                contentDescription = name
                this.selected = selected
            },
        shape = CircleShape,
        color = color,
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    imageVector = VdIcons.Check,
                    contentDescription = null,
                    tint = contrastingOn(color),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
