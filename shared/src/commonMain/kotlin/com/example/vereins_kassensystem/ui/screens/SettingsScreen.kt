package com.example.vereins_kassensystem.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.vereins_kassensystem.data.SettingsRepository
import com.example.vereins_kassensystem.data.repository.BackupRepository
import com.example.vereins_kassensystem.platform.LocalPlatform
import com.example.vereins_kassensystem.platform.VdDate
import com.example.vereins_kassensystem.platform.rememberBackupDestinationPicker
import com.example.vereins_kassensystem.platform.rememberBackupFileReader
import com.example.vereins_kassensystem.ui.components.AppearanceSection
import com.example.vereins_kassensystem.ui.components.ClubIdentitySection
import com.example.vereins_kassensystem.ui.components.VdSection
import com.example.vereins_kassensystem.ui.components.VdTopBar
import com.example.vereins_kassensystem.ui.icons.VdIcons
import com.example.vereins_kassensystem.ui.theme.ClubIdentity
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.ThemeMode
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    backupRepository: BackupRepository,
    onSumUpLogin: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val backupScheduler = LocalPlatform.current.backupScheduler

    val sumUpKey by settingsRepository.sumUpAffiliateKey.collectAsState(initial = "")
    var editedKey by remember(sumUpKey) { mutableStateOf(sumUpKey) }

    val clubIdentity by settingsRepository.clubIdentity.collectAsState(initial = ClubIdentity())
    val themeMode by settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val backupDestination by settingsRepository.backupDestination.collectAsState(initial = null)
    val autoBackupEnabled by settingsRepository.autoBackupEnabled.collectAsState(initial = false)
    val lastBackupAt by backupRepository.lastBackupAt.collectAsState(initial = null)

    // Der Ort ist ein plattformeigener Verweis; lesbar macht ihn erst die Plattform.
    val destinationLabel by produceState<String?>(initialValue = null, backupDestination) {
        value = backupRepository.destinationLabel()
    }

    val destinationPicker = rememberBackupDestinationPicker { ref ->
        scope.launch { settingsRepository.setBackupDestination(ref) }
    }
    val restorePicker = rememberBackupFileReader { bytes ->
        scope.launch {
            val ok = backupRepository.stageRestore(bytes)
            snackbarHostState.showSnackbar(
                if (ok) "Sicherung bereitgelegt. Bitte die App beenden und neu starten."
                else "Das ist keine VereinsDeckel-Sicherung."
            )
        }
    }

    // Der Planer folgt der Einstellung: unter Android ein WorkManager-Auftrag, unter iOS
    // eine Bitte an das System. Beides lässt sich gefahrlos wiederholen.
    LaunchedEffect(autoBackupEnabled, backupDestination) {
        if (autoBackupEnabled && backupDestination != null) backupScheduler.enableDaily()
        else backupScheduler.disable()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            VdTopBar(title = "Einstellungen")
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = Spacing.lg)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Spacer(Modifier.height(Spacing.xs))

            ClubIdentitySection(
                identity = clubIdentity,
                onNameChange = { scope.launch { settingsRepository.setClubName(it) } },
                onAccentChange = { scope.launch { settingsRepository.setClubAccent(it) } }
            )

            AppearanceSection(
                themeMode = themeMode,
                onThemeModeChange = { scope.launch { settingsRepository.setThemeMode(it) } }
            )

            VdSection(title = "Kartenzahlung", icon = VdIcons.Payments) {
                Text(
                    text = "Der Affiliate Key verbindet die Kasse mit eurem SumUp-Konto. " +
                        "Ohne ihn bleibt nur Bar und Deckel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = editedKey,
                    onValueChange = { editedKey = it },
                    label = { Text("SumUp Affiliate Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = MaterialTheme.shapes.small
                )
                Button(
                    onClick = {
                        scope.launch {
                            settingsRepository.saveSumUpAffiliateKey(editedKey)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TouchTarget.min),
                    shape = MaterialTheme.shapes.small,
                    enabled = editedKey != sumUpKey
                ) {
                    Icon(VdIcons.Save, contentDescription = null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Key speichern")
                }

                HorizontalDivider()

                OutlinedButton(
                    onClick = onSumUpLogin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TouchTarget.min),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(VdIcons.Login, contentDescription = null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text("Bei SumUp anmelden")
                }
            }

            VdSection(title = "Backup", icon = VdIcons.Backup) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TouchTarget.min),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automatisches Backup", style = MaterialTheme.typography.titleSmall)
                        // Kein "täglich": iOS entscheidet selbst, wann ein Hintergrundlauf
                        // stattfindet. Was zählt, ist, wann es zuletzt geklappt hat.
                        val last = lastBackupAt
                        Text(
                            text = when {
                                backupDestination == null -> "Erst einen Speicherort wählen."
                                last != null -> "Zuletzt gesichert: ${VdDate.dayAndTime(last)}"
                                else -> "Läuft im Hintergrund, sobald das Gerät Zeit dafür hat."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Switch(
                        checked = autoBackupEnabled,
                        onCheckedChange = { scope.launch { settingsRepository.setAutoBackupEnabled(it) } },
                        enabled = backupDestination != null
                    )
                }

                OutlinedButton(
                    onClick = { destinationPicker.open() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TouchTarget.min),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(VdIcons.Folder, contentDescription = null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(if (backupDestination != null) "Speicherort ändern" else "Speicherort wählen")
                }

                destinationLabel?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val success = backupRepository.createBackup()
                                snackbarHostState.showSnackbar(if (success) "Backup erstellt" else "Fehler beim Backup")
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = TouchTarget.min),
                        shape = MaterialTheme.shapes.small,
                        enabled = backupDestination != null
                    ) {
                        Icon(VdIcons.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(Spacing.sm))
                        Text("Sichern")
                    }

                    // Restoring replaces the live database, so it does not get a filled
                    // button beside the harmless one — the two must not look interchangeable.
                    OutlinedButton(
                        onClick = { restorePicker.open() },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = TouchTarget.min),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(VdIcons.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(Spacing.sm))
                        Text("Einspielen")
                    }
                }
            }

            // App info: quiet, at the end, where a version number belongs.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.lg, bottom = Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = "VereinsDeckel $APP_VERSION",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "© 2026 Jonas Prenn",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/** Shown at the foot of Settings. Kept next to its only use rather than in a config file. */
private const val APP_VERSION = "1.1.2"
