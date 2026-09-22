package com.example.vereins_kassensystem.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.ui.components.CashSection
import com.example.vereins_kassensystem.ui.components.MoneyStatTile
import com.example.vereins_kassensystem.ui.components.MoneyText
import com.example.vereins_kassensystem.ui.components.StatTile
import com.example.vereins_kassensystem.ui.components.VdListRow
import com.example.vereins_kassensystem.ui.components.VdTopBar
import com.example.vereins_kassensystem.ui.components.WarningBanner
import com.example.vereins_kassensystem.ui.theme.ClubTheme
import com.example.vereins_kassensystem.ui.theme.MoneySmall
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import com.example.vereins_kassensystem.viewmodel.AnalyticsViewModel
import com.example.vereins_kassensystem.viewmodel.CashViewModel
import com.example.vereins_kassensystem.viewmodel.InventoryViewModel
import com.example.vereins_kassensystem.viewmodel.MemberViewModel
import com.example.vereins_kassensystem.viewmodel.ProductViewModel
import com.example.vereins_kassensystem.platform.nowMillis
import com.example.vereins_kassensystem.platform.VdDate
import com.example.vereins_kassensystem.ui.icons.VdIcons

/**
 * The at-a-glance screen. Deliberately not the start destination any more — a volunteer
 * opening the app during a rush wants the till, not a summary.
 */
@Composable
fun HomeScreen(
    onNavigateToSales: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToProducts: () -> Unit,
    onNavigateToMembers: () -> Unit,
    analyticsViewModel: AnalyticsViewModel,
    productViewModel: ProductViewModel,
    memberViewModel: MemberViewModel,
    inventoryViewModel: InventoryViewModel,
    cashViewModel: CashViewModel
) {
    val cash by cashViewModel.state.collectAsState()
    val summary by analyticsViewModel.summary.collectAsState()
    val products by productViewModel.allProductsWithVariants.collectAsState()
    val members by memberViewModel.allMembers.collectAsState()

    val inventoryRows by inventoryViewModel.rows.collectAsState()
    val lowStock = remember(inventoryRows) { inventoryRows.filter { it.isLow || it.isNegative } }
    val today = remember { VdDate.weekdayAndDate(nowMillis()) }
    val clubName = ClubTheme.name

    Scaffold(
        topBar = {
            VdTopBar(
                title = clubName.ifBlank { "Übersicht" },
                subtitle = today
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    MoneyStatTile(
                        label = "Umsatz heute",
                        amount = summary.totalSales,
                        icon = VdIcons.Payments,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    StatTile(
                        label = "Transaktionen",
                        value = summary.transactionCount.toString(),
                        icon = VdIcons.ReceiptLong,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                CashSection(
                    state = cash,
                    members = members,
                    onOpen = cashViewModel::open,
                    onMove = cashViewModel::move,
                    onClose = cashViewModel::close
                )
            }

            if (lowStock.isNotEmpty()) {
                item {
                    WarningBanner(
                        title = "Lagerbestand niedrig",
                        supportingText = if (lowStock.size == 1) {
                            "${lowStock.first().item.name} geht zur Neige."
                        } else {
                            "${lowStock.size} Produkte gehen zur Neige."
                        },
                        onClick = onNavigateToProducts
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    QuickAction(
                        label = "Neuer Verkauf",
                        icon = VdIcons.AddShoppingCart,
                        onClick = onNavigateToSales,
                        modifier = Modifier.weight(1f)
                    )
                    QuickAction(
                        label = "Mitglieder",
                        icon = VdIcons.PersonAdd,
                        onClick = onNavigateToMembers,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Text(
                    text = "Verein",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }

            item {
                VdListRow(
                    title = "Aktive Mitglieder",
                    leading = { StatIcon(VdIcons.Groups) },
                    trailing = { Text(members.size.toString(), style = MoneySmall) }
                )
            }
            item {
                VdListRow(
                    title = "Produkte im Sortiment",
                    leading = { StatIcon(VdIcons.Inventory2) },
                    trailing = { Text(products.size.toString(), style = MoneySmall) }
                )
            }
            item {
                VdListRow(
                    title = "Trinkgeld heute",
                    leading = { StatIcon(VdIcons.Favorite) },
                    trailing = { MoneyText(amount = summary.totalTips) }
                )
            }
            item {
                VdListRow(
                    title = "Vollständige Historie",
                    supportingText = "Alle Buchungen ansehen",
                    leading = { StatIcon(VdIcons.ReceiptLong) },
                    onClick = onNavigateToHistory
                )
            }

            item { Spacer(Modifier.height(Spacing.xl)) }
        }
    }
}

@Composable
private fun StatIcon(icon: ImageVector) {
    Surface(
        modifier = Modifier.size(36.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun QuickAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = TouchTarget.sales),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = ClubTheme.accent)
            Spacer(Modifier.width(Spacing.md))
            Text(text = label, style = MaterialTheme.typography.titleSmall)
        }
    }
}
