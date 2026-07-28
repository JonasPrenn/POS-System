package com.example.vereins_kassensystem.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.ui.format.Money
import kotlinx.coroutines.launch
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.viewmodel.MemberViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberManagementScreen(
    viewModel: MemberViewModel,
    onMemberClick: (Member) -> Unit
) {
    val members by viewModel.allMembers.collectAsState()
    val categories by viewModel.allCategories.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var memberToEdit by remember { mutableStateOf<Member?>(null) }
    var memberToTopUp by remember { mutableStateOf<Member?>(null) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    viewModel.importMembersFromCsv(stream)
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            scope.launch {
                context.contentResolver.openOutputStream(it)?.use { stream ->
                    viewModel.exportMembersToCsv(stream)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.importStatus.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }
    
    var searchQuery by remember { mutableStateOf("") }
    val filteredMembers = remember(searchQuery, members) {
        if (searchQuery.isBlank()) {
            members
        } else {
            members.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Mitgliederverwaltung") },
                actions = {
                    IconButton(onClick = { importLauncher.launch("text/*") }) {
                        Icon(Icons.Default.FileUpload, contentDescription = "Import")
                    }
                    IconButton(onClick = { exportLauncher.launch("mitglieder.csv") }) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Export")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Neu") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Mitglied suchen...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Löschen")
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small
                )
            }
            
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredMembers, key = { it.id }) { member ->
                    MemberItem(
                        member = member,
                        onEdit = { memberToEdit = it },
                        onDelete = { viewModel.deleteMember(it) },
                        onTopUp = { memberToTopUp = it },
                        onClick = { onMemberClick(member) }
                    )
                }
            }
        }

        if (showAddDialog) {
            MemberDialog(
                categories = categories,
                onDismiss = { showAddDialog = false },
                onConfirm = { name, categoryId ->
                    viewModel.insertMember(Member(name = name, categoryId = categoryId))
                    showAddDialog = false
                }
            )
        }

        if (memberToEdit != null) {
            MemberDialog(
                member = memberToEdit,
                categories = categories,
                onDismiss = { memberToEdit = null },
                onConfirm = { name, categoryId ->
                    viewModel.updateMember(memberToEdit!!.copy(name = name, categoryId = categoryId))
                    memberToEdit = null
                }
            )
        }

        if (memberToTopUp != null) {
            TopUpDialog(
                member = memberToTopUp!!,
                onDismiss = { memberToTopUp = null },
                onConfirm = { amount, reason, paymentType ->
                    viewModel.adjustBalance(memberToTopUp!!, amount, reason, paymentType)
                    memberToTopUp = null
                }
            )
        }
    }
}

@Composable
fun MemberItem(
    member: Member, 
    onEdit: (Member) -> Unit, 
    onDelete: (Member) -> Unit, 
    onTopUp: (Member) -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = member.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            
            Spacer(Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = Money.format(member.balance),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (member.balance < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                )
            }
            
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalIconButton(
                    onClick = { onTopUp(member) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.AddCard, contentDescription = "Top Up", modifier = Modifier.size(20.dp))
                }
                FilledTonalIconButton(
                    onClick = { onEdit(member) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(20.dp))
                }
                FilledTonalIconButton(
                    onClick = { onDelete(member) },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberDialog(
    member: Member? = null,
    categories: List<MemberCategory>,
    onDismiss: () -> Unit,
    onConfirm: (String, Long?) -> Unit
) {
    var name by remember { mutableStateOf(member?.name ?: "") }
    var selectedCategoryId by remember { mutableStateOf<Long?>(member?.categoryId) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (member == null) "Mitglied hinzufügen" else "Mitglied bearbeiten") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = categories.find { it.id == selectedCategoryId }?.name ?: "Keine",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Kategorie") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Keine") },
                            onClick = {
                                selectedCategoryId = null
                                expanded = false
                            }
                        )
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    selectedCategoryId = category.id
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, selectedCategoryId) },
                enabled = name.isNotBlank()
            ) {
                Text("Speichern")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}

/** How the money for a top-up actually arrived — or that it did not. */
enum class TopUpKind(val label: String, val paymentType: String, val defaultReason: String) {
    CASH("Bar", "CASH", "Bar an der Kasse erhalten"),
    CARD("Karte", "CARD", "Per Karte erhalten"),
    CORRECTION("Korrektur", "CORRECTION", "");

    val isPayment: Boolean get() = this != CORRECTION
}

/**
 * Crediting a Deckel from the Mitglieder screen.
 *
 * Both the route the money took and a reason are required. Previously this dialog asked
 * only for an amount and moved the balance directly, so a Deckel could gain fifty euro
 * with nothing written down anywhere — impossible to reconcile against the cash box, and
 * impossible to answer "who put that there?" a week later. A correction is still allowed,
 * including a negative one, but it has to say what it is.
 */
@Composable
fun TopUpDialog(
    member: Member,
    onDismiss: () -> Unit,
    onConfirm: (amount: Double, reason: String, paymentType: String) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(TopUpKind.CASH) }
    var reason by remember { mutableStateOf(TopUpKind.CASH.defaultReason) }

    val parsed = Money.parse(amount)
    // Payments must bring money in; only a correction may be negative.
    val amountValid = parsed != null && parsed != 0.0 && (kind == TopUpKind.CORRECTION || parsed > 0.0)
    val reasonValid = reason.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guthaben · ${member.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Aktuelles Guthaben: ${Money.format(member.balance)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopUpKind.entries.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = {
                                // Swap in the matching default reason unless it was edited.
                                if (reason == kind.defaultReason) reason = option.defaultReason
                                kind = option
                            },
                            label = { Text(option.label) },
                            shape = MaterialTheme.shapes.small
                        )
                    }
                }

                if (kind.isPayment) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 20, 50).forEach { preset ->
                            FilterChip(
                                selected = amount == preset.toString(),
                                onClick = { amount = preset.toString() },
                                label = { Text("$preset €") },
                                shape = MaterialTheme.shapes.small
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(if (kind == TopUpKind.CORRECTION) "Betrag (+/−)" else "Betrag (€)") },
                    isError = amount.isNotBlank() && !amountValid,
                    supportingText = {
                        if (amount.isNotBlank() && !amountValid) {
                            Text(
                                if (kind == TopUpKind.CORRECTION) "Bitte einen Betrag ungleich 0 eingeben."
                                else "Eine Aufladung muss größer als 0 sein."
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Grund") },
                    isError = !reasonValid,
                    supportingText = {
                        Text(
                            if (!reasonValid) "Pflichtfeld – erscheint in der Historie."
                            else "Erscheint in der Transaktionshistorie."
                        )
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )

                if (amountValid) {
                    Text(
                        text = "Neues Guthaben: ${Money.format(member.balance + parsed!!)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(parsed ?: 0.0, reason.trim(), kind.paymentType) },
                enabled = amountValid && reasonValid
            ) {
                Text(if (kind == TopUpKind.CORRECTION) "Korrigieren" else "Aufladen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}
