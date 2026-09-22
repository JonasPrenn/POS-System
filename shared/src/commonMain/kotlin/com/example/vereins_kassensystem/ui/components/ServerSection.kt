package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.example.vereins_kassensystem.data.sync.PairingResult
import com.example.vereins_kassensystem.data.sync.SyncEngine
import com.example.vereins_kassensystem.data.sync.SyncProblem
import com.example.vereins_kassensystem.data.sync.SyncStatus
import com.example.vereins_kassensystem.platform.LocalPlatform
import com.example.vereins_kassensystem.platform.VdDate
import com.example.vereins_kassensystem.ui.icons.VdIcons
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import com.example.vereins_kassensystem.ui.theme.VereinsColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Server und Abgleich, in den Einstellungen.
 *
 * Ungekoppelt: Adresse, Kopplungscode, ein Name für das Gerät. Kein Passwort — den Code
 * erzeugt der Administrator am Server, er gilt zehn Minuten und genau einmal
 * (Spezifikation 5.2).
 *
 * Gekoppelt: was der Kassier wissen will. Wann zuletzt abgeglichen wurde, wie viel noch
 * wartet, und ein Knopf, der es jetzt versucht. Hat der Server das Gerät abgemeldet, steht
 * hier auch der Weg zurück — ein neuer Code, ohne dass die wartenden Buchungen verloren gehen.
 *
 * [onBackup] sichert den Stand dieses Geräts, bevor es den des Servers übernimmt, und
 * meldet, ob das gelang; [onMessage] zeigt eine kurze Rückmeldung.
 */
@Composable
fun ServerSection(
    engine: SyncEngine,
    canBackup: Boolean,
    onBackup: suspend () -> Boolean,
    onMessage: suspend (String) -> Unit,
) {
    val status by engine.status.collectAsState()
    // Ein Scope für den ganzen Abschnitt: Koppeln und Neu-Anmelden tauschen das Formular aus,
    // das sie ausgelöst hat — dessen eigener Scope nähme die Rückmeldung mit.
    val scope = rememberCoroutineScope()
    var needsDecision by remember { mutableStateOf(false) }

    VdSection(title = "Server und Abgleich", icon = VdIcons.CloudUpload) {
        if (status.paired) {
            PairedState(status, engine, scope, onMessage)
        } else {
            PairingForm(
                engine = engine,
                scope = scope,
                initialUrl = status.serverUrl.orEmpty(),
                onResult = { result ->
                    when (result) {
                        is PairingResult.Source -> onMessage("Gekoppelt. ${result.rows} Zeilen gehen jetzt zum Server.")
                        PairingResult.Joined -> onMessage("Gekoppelt. Der Stand des Servers wird übernommen.")
                        PairingResult.NeedsDecision -> needsDecision = true
                        is PairingResult.Failed -> onMessage(result.message)
                    }
                }
            )
        }
    }

    if (needsDecision) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Server und Gerät haben beide Daten") },
            text = {
                Text(
                    "Zusammenführen geht nicht, ohne dass Mitglieder und Produkte doppelt entstehen. " +
                        "Dieses Gerät kann den Stand des Servers übernehmen; was hier liegt, wird dabei ersetzt. " +
                        if (canBackup) "Vorher wird eine Sicherung geschrieben."
                        else "Es ist kein Sicherungsort eingerichtet — die Daten dieses Geräts wären danach weg."
                )
            },
            confirmButton = {
                // Rot, weil hier etwas zerstört wird, und nur hier.
                Button(
                    onClick = {
                        scope.launch {
                            if (canBackup && !onBackup()) {
                                onMessage("Die Sicherung ist fehlgeschlagen. Es wurde nichts ersetzt.")
                                return@launch
                            }
                            engine.adoptServerState()
                            needsDecision = false
                            onMessage("Der Stand des Servers wird übernommen.")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Serverstand übernehmen") }
            },
            dismissButton = {
                TextButton(onClick = {
                    scope.launch {
                        engine.unpair()
                        needsDecision = false
                        onMessage("Kopplung abgebrochen. Auf diesem Gerät hat sich nichts geändert.")
                    }
                }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun PairingForm(engine: SyncEngine, scope: CoroutineScope, initialUrl: String, onResult: suspend (PairingResult) -> Unit) {
    val platform = LocalPlatform.current
    var url by remember { mutableStateOf(initialUrl) }
    var code by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }

    Text(
        text = "Mit einem Server teilen sich mehrere Geräte einen Datenbestand. Ohne ihn arbeitet " +
            "dieses Gerät allein — verkauft wird in beiden Fällen auch ohne Netz.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = url,
        onValueChange = { url = it; checkResult = null },
        label = { Text("Adresse des Servers") },
        placeholder = { Text("https://… oder im Vereinsnetz http://10.0.0.5:8080") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    )
    checkResult?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text("Kopplungscode") },
        placeholder = { Text("8K4M-2QX9") },
        supportingText = { Text("Erzeugt der Administrator am Server; gilt zehn Minuten und einmal.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = label,
        onValueChange = { label = it },
        label = { Text("Name dieses Geräts") },
        placeholder = { Text("Theke links") },
        supportingText = { Text("So erscheint es in der Geräteliste. Leer: ${platform.description}.") },
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedButton(
            onClick = {
                scope.launch {
                    busy = true
                    checkResult = engine.checkServer(url) ?: "Der Server antwortet."
                    busy = false
                }
            },
            enabled = !busy && url.isNotBlank(),
            modifier = Modifier.weight(1f).heightIn(min = TouchTarget.min),
            shape = MaterialTheme.shapes.small
        ) { Text("Prüfen") }
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    onResult(engine.pair(url, code, label))
                    busy = false
                }
            },
            enabled = !busy && url.isNotBlank() && code.isNotBlank(),
            modifier = Modifier.weight(1f).heightIn(min = TouchTarget.min),
            shape = MaterialTheme.shapes.small
        ) {
            Icon(VdIcons.Login, contentDescription = null)
            Spacer(Modifier.width(Spacing.sm))
            Text("Koppeln")
        }
    }
}

@Composable
private fun PairedState(status: SyncStatus, engine: SyncEngine, scope: CoroutineScope, onMessage: suspend (String) -> Unit) {
    var confirmUnpair by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(status.deviceLabel ?: "Dieses Gerät", style = MaterialTheme.typography.titleSmall)
        Text(status.serverUrl.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(
        text = when {
            status.running -> "Gleicht gerade ab …"
            status.lastSyncAt != null -> "Zuletzt abgeglichen: ${VdDate.dayAndTime(status.lastSyncAt)}"
            else -> "Noch nie abgeglichen."
        },
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        text = when (status.pending) {
            0 -> "Nichts wartet. Der Server hat alles, was dieses Gerät gebucht hat."
            1 -> "1 Änderung wartet auf den Server."
            else -> "${status.pending} Änderungen warten auf den Server."
        },
        style = MaterialTheme.typography.bodyMedium
    )
    status.problem?.let { problem -> WarningText("${problem.message}. ${problem.advice}") }
    if (status.problem == SyncProblem.NeedsPairing) ReauthorizeForm(engine, scope, onMessage)
    status.lastRejected?.let { detail ->
        WarningText(
            "Der Server hat eine Änderung dieses Geräts abgelehnt. Sie wurde aussortiert, alles andere ist " +
                "abgeglichen. Das ist ein Fehler im Programm, kein Bedienfehler — bitte weitergeben: $detail"
        )
        TextButton(onClick = { scope.launch { engine.dismissRejected() } }) { Text("Gesehen") }
    }

    // Abgemeldet hilft kein neuer Versuch, nur ein neuer Code — also steht dann nur der eine Knopf da.
    if (status.problem != SyncProblem.NeedsPairing) {
        Button(
            onClick = { engine.requestSync() },
            enabled = !status.running,
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.min),
            shape = MaterialTheme.shapes.small
        ) {
            Icon(VdIcons.CloudUpload, contentDescription = null)
            Spacer(Modifier.width(Spacing.sm))
            Text("Jetzt abgleichen")
        }
    }

    HorizontalDivider()

    OutlinedButton(
        onClick = { confirmUnpair = true },
        modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.min),
        shape = MaterialTheme.shapes.small
    ) { Text("Kopplung lösen") }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("Kopplung lösen?") },
            text = {
                Text(
                    "Die Daten auf diesem Gerät bleiben. Es gleicht danach nicht mehr ab, bis es neu gekoppelt wird." +
                        if (status.pending > 0) " ${status.pending} Änderungen, die noch warten, erreichen den Server dann nicht mehr." else ""
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            engine.unpair()
                            confirmUnpair = false
                            onMessage("Kopplung gelöst.")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("Lösen") }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Abbrechen") } }
        )
    }
}

/**
 * Ein abgemeldetes Gerät meldet sich mit einem frischen Code wieder an. Bewusst nicht über
 * „Kopplung lösen": Das würde die Warteschlange leeren, und was seit der Abmeldung verkauft
 * wurde, käme nie beim Server an.
 */
@Composable
private fun ReauthorizeForm(engine: SyncEngine, scope: CoroutineScope, onMessage: suspend (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text("Neuer Kopplungscode") },
        placeholder = { Text("8K4M-2QX9") },
        supportingText = { Text("Erzeugt der Administrator am Server. Daten und wartende Buchungen dieses Geräts bleiben.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    )
    Button(
        onClick = {
            scope.launch {
                busy = true
                onMessage(engine.reauthorize(code) ?: "Wieder angemeldet. Was gewartet hat, geht jetzt zum Server.")
                busy = false
            }
        },
        enabled = !busy && code.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.min),
        shape = MaterialTheme.shapes.small
    ) {
        Icon(VdIcons.Login, contentDescription = null)
        Spacer(Modifier.width(Spacing.sm))
        Text("Neu anmelden")
    }
}

/** Bernstein: Aufmerksamkeit, kein Fehler. */
@Composable
private fun WarningText(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(VdIcons.WarningAmber, contentDescription = null, tint = VereinsColors.warning)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
