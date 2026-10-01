package com.example.vereins_kassensystem.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.AppVersion
import com.example.vereins_kassensystem.data.sync.SyncStatus
import com.example.vereins_kassensystem.ui.components.SyncStatusBadge
import com.example.vereins_kassensystem.ui.icons.VdIcons
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.VereinsColors

/**
 * Die App ist älter als die Mindestversion, die der Server verlangt (Systemverwaltung, Hauptadmin).
 * Statt der Kasse nur das, mit dem, was man am Tresen wissen muss: welche Version läuft, welche
 * verlangt ist, und dass nichts verloren geht. Bernstein, nicht Rot — es ist nichts kaputt.
 *
 * Der Abgleich läuft dahinter weiter: Was vor der Sperre gebucht wurde, geht noch hoch, und nimmt
 * der Hauptadmin die Sperre zurück, ist die Kasse mit dem nächsten Abgleich wieder da.
 */
@Composable
fun UpdateRequiredScreen(required: String, status: SyncStatus, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Surface(shape = CircleShape, color = VereinsColors.warningContainer, contentColor = VereinsColors.onWarningContainer) {
                Icon(VdIcons.Lock, contentDescription = null, modifier = Modifier.padding(Spacing.lg).size(40.dp))
            }
            Text("Diese App muss aktualisiert werden", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "Der Verein verlangt mindestens Version $required. Auf diesem Gerät läuft ${AppVersion.LABEL}.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Text(
                "Bis zum Update wird hier nicht kassiert. Es geht nichts verloren: Was schon gebucht ist, bleibt auf dem Gerät und geht weiter an den Server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            SyncStatusBadge(status)
        }
    }
}
