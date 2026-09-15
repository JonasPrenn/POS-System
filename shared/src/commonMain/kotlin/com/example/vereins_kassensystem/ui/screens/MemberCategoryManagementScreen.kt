package com.example.vereins_kassensystem.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.ui.components.EmptyState
import com.example.vereins_kassensystem.ui.components.MoneyText
import com.example.vereins_kassensystem.ui.components.RowMenuItem
import com.example.vereins_kassensystem.ui.components.VdListRow
import com.example.vereins_kassensystem.ui.components.VdRowMenu
import com.example.vereins_kassensystem.ui.components.VdTopBar
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.theme.MoneySmall
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.viewmodel.MemberViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberCategoryManagementScreen(
    viewModel: MemberViewModel
) {
    val categories by viewModel.allCategories.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var categoryToEdit by remember { mutableStateOf<MemberCategory?>(null) }

    Scaffold(
        topBar = {
            VdTopBar(
                title = "Kategorien",
                subtitle = if (categories.isEmpty()) null else "${categories.size} Kategorien"
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
        if (categories.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.Label,
                title = "Noch keine Kategorien",
                supportingText = "Eine Kategorie legt fest, wie weit ein Deckel ins Minus gehen darf.",
                actionLabel = "Kategorie anlegen",
                onAction = { showAddDialog = true },
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(categories, key = { it.id }) { category ->
                    CategoryItem(
                        category = category,
                        onEdit = { categoryToEdit = it },
                        onDelete = { viewModel.deleteCategory(it) }
                    )
                }
                item { Spacer(Modifier.height(Spacing.xxl)) }
            }
        }

        if (showAddDialog) {
            CategoryDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { name, negativeLimit ->
                    viewModel.insertCategory(MemberCategory(name = name, negativeBalanceLimit = negativeLimit))
                    showAddDialog = false
                }
            )
        }

        if (categoryToEdit != null) {
            CategoryDialog(
                category = categoryToEdit,
                onDismiss = { categoryToEdit = null },
                onConfirm = { name, negativeLimit ->
                    viewModel.updateCategory(categoryToEdit!!.copy(name = name, negativeBalanceLimit = negativeLimit))
                    categoryToEdit = null
                }
            )
        }
    }
}

/**
 * A member category and the allowance it grants.
 *
 * The limit used to be painted red on every row. Red is reserved for something having
 * gone wrong, and a category simply having an allowance is not that — an allowance of
 * zero is worth calling out in words instead, since it means the Deckel cannot go into
 * the red at all.
 */
@Composable
fun CategoryItem(
    category: MemberCategory,
    onEdit: (MemberCategory) -> Unit,
    onDelete: (MemberCategory) -> Unit
) {
    val hasAllowance = category.negativeBalanceLimit < 0.0

    VdListRow(
        title = category.name,
        supportingText = if (hasAllowance) "Deckel darf bis hierhin ins Minus" else "Kein Minus erlaubt",
        onClick = { onEdit(category) },
        leading = {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.Label,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                if (hasAllowance) {
                    MoneyText(
                        amount = category.negativeBalanceLimit,
                        style = MoneySmall,
                        signed = true,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "—",
                        style = MoneySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                VdRowMenu(
                    items = listOf(
                        RowMenuItem("Bearbeiten", Icons.Default.Edit) { onEdit(category) },
                        RowMenuItem("Löschen", Icons.Default.Delete, destructive = true) {
                            onDelete(category)
                        }
                    ),
                    contentDescription = "Aktionen für ${category.name}"
                )
            }
        }
    )
}

@Composable
fun CategoryDialog(
    category: MemberCategory? = null,
    onDismiss: () -> Unit,
    onConfirm: (String, Double) -> Unit
) {
    var name by remember { mutableStateOf(category?.name ?: "") }
    var negativeLimit by remember { mutableStateOf(category?.negativeBalanceLimit?.toString() ?: "0.0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (category == null) "Kategorie hinzufügen" else "Kategorie bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small
                )
                OutlinedTextField(
                    value = negativeLimit,
                    onValueChange = { negativeLimit = it },
                    label = { Text("Negatives Limit (€)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small
                )
                Text(
                    "Das Limit gibt an, wie weit das Konto ins Minus gehen darf (z.B. -10.00).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val limit = Money.parse(negativeLimit) ?: 0.0
                    val finalLimit = if (limit > 0) -limit else limit
                    onConfirm(name, finalLimit)
                },
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
