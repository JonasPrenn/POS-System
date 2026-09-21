package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.data.sync.SyncStatus
import com.example.vereins_kassensystem.platform.VdDate
import com.example.vereins_kassensystem.ui.icons.VdIcons
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.VereinsColors

/**
 * Ob dieses Gerät mit den anderen abgeglichen ist — immer sichtbar, nie im Weg.
 *
 * Die Spezifikation macht das zur Anforderung (4.4): Ein Kassier, der nicht weiß, dass sein
 * Gerät seit einer Stunde allein arbeitet, belastet einen Deckel, der längst am Limit ist.
 *
 * Bernstein heißt Aufmerksamkeit, nicht Fehler: Offline zu sein ist im Vereinsheim der
 * Normalfall und nichts, was jemand an der Theke beheben könnte. Rot bleibt der Zerstörung
 * vorbehalten. Solange das Gerät nicht gekoppelt ist, erscheint hier nichts.
 */
private class Appearance(val icon: ImageVector, val title: String, val detail: String?, val attention: Boolean)

private fun appearanceOf(status: SyncStatus): Appearance? {
    if (!status.paired) return null
    val waiting = when (status.pending) {
        0 -> null
        1 -> "1 wartet"
        else -> "${status.pending} warten"
    }
    return when {
        status.problem != null -> Appearance(VdIcons.WarningAmber, "Offline", waiting ?: status.lastSyncAt?.let { "seit ${VdDate.timeOfDay(it)}" }, attention = true)
        waiting != null -> Appearance(VdIcons.CloudUpload, waiting, null, attention = false)
        else -> Appearance(VdIcons.Check, "Abgeglichen", status.lastSyncAt?.let(VdDate::timeOfDay), attention = false)
    }
}

private fun describe(status: SyncStatus, look: Appearance): String =
    status.problem?.let { "${it.message}. ${look.detail.orEmpty()}" } ?: listOfNotNull(look.title, look.detail).joinToString(", ")

/** Für die Seitenleiste: Symbol über zwei kurzen Zeilen, wie die Einträge darüber. */
@Composable
fun SyncStatusBadge(status: SyncStatus, modifier: Modifier = Modifier) {
    val look = appearanceOf(status) ?: return
    val tint = if (look.attention) VereinsColors.onWarningContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = describe(status, look) },
        shape = MaterialTheme.shapes.small,
        color = if (look.attention) VereinsColors.warningContainer else Color.Transparent,
        contentColor = tint
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(look.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(look.title, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            look.detail?.let { Text(it, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
        }
    }
}

/** Für das Telefon: eine schmale Zeile über der Leiste, und nur, wenn es etwas zu sagen gibt. */
@Composable
fun SyncStatusLine(status: SyncStatus, modifier: Modifier = Modifier) {
    val look = appearanceOf(status) ?: return
    if (!look.attention && status.pending == 0) return
    Surface(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = describe(status, look) },
        color = if (look.attention) VereinsColors.warningContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (look.attention) VereinsColors.onWarningContainer else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(look.icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(listOfNotNull(look.title, look.detail).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
        }
    }
}
