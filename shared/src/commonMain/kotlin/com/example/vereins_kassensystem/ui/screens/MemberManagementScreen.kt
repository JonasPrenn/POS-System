package com.example.vereins_kassensystem.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.example.vereins_kassensystem.ui.components.EmptyState
import com.example.vereins_kassensystem.ui.components.MemberChip
import com.example.vereins_kassensystem.ui.components.RowMenuItem
import com.example.vereins_kassensystem.ui.components.VdIconAction
import com.example.vereins_kassensystem.ui.components.VdRowMenu
import com.example.vereins_kassensystem.ui.components.VdTopBar
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.theme.Spacing
import kotlinx.coroutines.launch
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.viewmodel.MemberViewModel
import com.example.vereins_kassensystem.ui.icons.VdIcons
import com.example.vereins_kassensystem.platform.rememberTextFileReader
import com.example.vereins_kassensystem.platform.rememberTextFileWriter

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

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val importer = rememberTextFileReader { csv ->
        scope.launch { viewModel.importMembersFromCsv(csv) }
    }
    val exporter = rememberTextFileWriter(
        suggestedName = "mitglieder.csv",
        content = { viewModel.exportMembersToCsv() }
    ) { ok ->
        scope.launch { snackbarHostState.showSnackbar(if (ok) "Export erfolgreich" else "Export fehlgeschlagen") }
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

    // The allowance is a property of the member's category, and the balance colour is
    // meaningless without it: "−8 €" is fine on a €20 Deckel and over the line on a €5 one.
    val limitByCategory: Map<Long, Double> = remember(categories) {
        categories.associate { it.id to it.negativeBalanceLimit }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            VdTopBar(
                title = "Mitglieder",
                subtitle = if (members.isEmpty()) null else "${members.size} Mitglieder",
                actions = {
                    IconButton(onClick = { importer.open() }) {
                        Icon(VdIcons.FileUpload, contentDescription = "Mitglieder importieren")
                    }
                    IconButton(onClick = { exporter.open() }) {
                        Icon(VdIcons.FileDownload, contentDescription = "Mitglieder exportieren")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(VdIcons.Add, contentDescription = null) },
                text = { Text("Neu") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Mitglied suchen") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                leadingIcon = { Icon(VdIcons.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(VdIcons.Clear, contentDescription = "Suche löschen")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.small
            )

            when {
                members.isEmpty() -> EmptyState(
                    icon = VdIcons.Groups,
                    title = "Noch keine Mitglieder",
                    supportingText = "Wer einen Deckel führen soll, braucht hier einen Eintrag.",
                    actionLabel = "Mitglied anlegen",
                    onAction = { showAddDialog = true },
                    modifier = Modifier.weight(1f)
                )

                filteredMembers.isEmpty() -> EmptyState(
                    icon = VdIcons.SearchOff,
                    title = "Keine Treffer",
                    supportingText = "Kein Mitglied enthält „$searchQuery“.",
                    modifier = Modifier.weight(1f)
                )

                else -> LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = Spacing.sm,
                        bottom = Spacing.xxl
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(filteredMembers, key = { it.id }) { member ->
                        MemberItem(
                            member = member,
                            negativeLimit = member.categoryId?.let { limitByCategory[it] } ?: 0.0,
                            onEdit = { memberToEdit = it },
                            onDelete = { viewModel.deleteMember(it) },
                            onTopUp = { memberToTopUp = it },
                            onClick = { onMemberClick(member) }
                        )
                    }
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

/**
 * A member and their Deckel, on the management screen.
 *
 * The row itself starts a sale for that member, which is what the screen is mostly used
 * for. Topping up keeps its own button because it is the second-most-common thing that
 * happens here; renaming and deleting move into the overflow. The balance colour comes
 * from [MemberChip], so it reads the same way here as it does at the till.
 */
@Composable
fun MemberItem(
    member: Member,
    negativeLimit: Double,
    onEdit: (Member) -> Unit,
    onDelete: (Member) -> Unit,
    onTopUp: (Member) -> Unit,
    onClick: () -> Unit
) {
    MemberChip(
        member = member,
        negativeLimit = negativeLimit,
        onClick = onClick,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                VdIconAction(
                    icon = VdIcons.AddCard,
                    contentDescription = "Guthaben aufladen für ${member.name}",
                    onClick = { onTopUp(member) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
                VdRowMenu(
                    items = listOf(
                        RowMenuItem("Bearbeiten", VdIcons.Edit) { onEdit(member) },
                        RowMenuItem("Löschen", VdIcons.Delete, destructive = true) {
                            onDelete(member)
                        }
                    ),
                    contentDescription = "Aktionen für ${member.name}"
                )
            }
        }
    )
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
                Spacer(modifier = Modifier.height(Spacing.lg))
                
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
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
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
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = "Aktuelles Guthaben: ${Money.format(member.balance)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
                        text = "Neues Guthaben: ${Money.format(member.balance + parsed)}",
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
