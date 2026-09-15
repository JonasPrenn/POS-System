package com.example.vereins_kassensystem.ui.screens

import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.work.*
import com.example.vereins_kassensystem.data.SettingsRepository
import com.example.vereins_kassensystem.ui.components.AppearanceSection
import com.example.vereins_kassensystem.ui.components.ClubIdentitySection
import com.example.vereins_kassensystem.ui.components.VdSection
import com.example.vereins_kassensystem.ui.components.VdTopBar
import com.example.vereins_kassensystem.ui.theme.ClubIdentity
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.ThemeMode
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import com.example.vereins_kassensystem.data.repository.BackupRepository
import com.example.vereins_kassensystem.worker.BackupWorker
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import com.example.vereins_kassensystem.ui.icons.VdIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    backupRepository: BackupRepository,
    onSumUpLogin: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    
    val sumUpKey by settingsRepository.sumUpAffiliateKey.collectAsState(initial = "")
    var editedKey by remember(sumUpKey) { mutableStateOf(sumUpKey) }

    val clubIdentity by settingsRepository.clubIdentity.collectAsState(initial = ClubIdentity())
    val themeMode by settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val backupUri by settingsRepository.backupUri.collectAsState(initial = null)
    val autoBackupEnabled by settingsRepository.autoBackupEnabled.collectAsState(initial = false)

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            // Take persistable URI permission
            context.contentResolver.takePersistableUriPermission(
                it,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            scope.launch {
                settingsRepository.saveBackupUri(it.toString())
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                val success = backupRepository.restoreBackup(it)
                if (success) {
                    snackbarHostState.showSnackbar("Wiederherstellung erfolgreich. Bitte App neu starten.")
                } else {
                    snackbarHostState.showSnackbar("Fehler bei der Wiederherstellung.")
                }
            }
        }
    }

    LaunchedEffect(autoBackupEnabled, backupUri) {
        if ((autoBackupEnabled) && (backupUri != null)) {
            val backupWorkRequest = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "DailyBackup",
                ExistingPeriodicWorkPolicy.KEEP,
                backupWorkRequest
            )
        } else {
            WorkManager.getInstance(context).cancelUniqueWork("DailyBackup")
        }
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
                        Text("Tägliches Backup", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = if (backupUri == null) {
                                "Erst einen Speicherort wählen."
                            } else {
                                "Läuft einmal täglich im Hintergrund."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Switch(
                        checked = autoBackupEnabled,
                        onCheckedChange = { scope.launch { settingsRepository.setAutoBackupEnabled(it) } },
                        enabled = backupUri != null
                    )
                }

                OutlinedButton(
                    onClick = { folderLauncher.launch(null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = TouchTarget.min),
                    shape = MaterialTheme.shapes.small
                ) {
                    Icon(VdIcons.Folder, contentDescription = null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(if (backupUri != null) "Speicherort ändern" else "Speicherort wählen")
                }

                if (backupUri != null) {
                    Text(
                        text = backupUri!!.toUri().path.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider()

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val success = backupRepository.createBackup(backupUri?.toUri())
                                snackbarHostState.showSnackbar(if (success) "Backup erstellt" else "Fehler beim Backup")
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = TouchTarget.min),
                        shape = MaterialTheme.shapes.small,
                        enabled = backupUri != null
                    ) {
                        Icon(VdIcons.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(Spacing.sm))
                        Text("Sichern")
                    }

                    // Restoring replaces the live database, so it does not get a filled
                    // button beside the harmless one — the two must not look interchangeable.
                    OutlinedButton(
                        onClick = { restoreLauncher.launch("application/zip") },
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
