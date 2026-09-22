package com.example.vereins_kassensystem.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.ui.components.EmptyState
import com.example.vereins_kassensystem.ui.components.MoneyStatTile
import com.example.vereins_kassensystem.ui.components.MoneyText
import com.example.vereins_kassensystem.ui.components.RowBadge
import com.example.vereins_kassensystem.ui.components.VdListRow
import com.example.vereins_kassensystem.ui.components.VdTopBar
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.viewmodel.AnalyticsViewModel
import com.example.vereins_kassensystem.viewmodel.DateRange
import com.example.vereins_kassensystem.ui.icons.VdIcons

@Composable
fun AnalyticsScreen(viewModel: AnalyticsViewModel) {
    val summary by viewModel.summary.collectAsState()
    val range by viewModel.dateRange.collectAsState()

    Scaffold(topBar = { VdTopBar(title = "Auswertung") }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    DateRange.entries.forEachIndexed { index, entry ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index, DateRange.entries.size),
                            selected = range == entry,
                            onClick = { viewModel.setDateRange(entry) },
                            label = {
                                Text(
                                    when (entry) {
                                        DateRange.TODAY -> "Heute"
                                        DateRange.LAST_7_DAYS -> "7 Tage"
                                        DateRange.LAST_30_DAYS -> "30 Tage"
                                        DateRange.ALL_TIME -> "Gesamt"
                                    },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    MoneyStatTile(
                        label = "Umsatz",
                        amount = summary.totalSales,
                        icon = VdIcons.Assessment,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    MoneyStatTile(
                        label = "Trinkgeld",
                        amount = summary.totalTips,
                        icon = VdIcons.Favorite,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    MoneyStatTile(
                        label = "Bar",
                        amount = summary.cashSales,
                        icon = VdIcons.Payments,
                        modifier = Modifier.weight(1f)
                    )
                    MoneyStatTile(
                        label = "Karte",
                        amount = summary.cardSales,
                        icon = VdIcons.CreditCard,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Text(
                    text = "Top-Produkte",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }

            if (summary.topProducts.isEmpty()) {
                item {
                    EmptyState(
                        icon = VdIcons.Assessment,
                        title = "Keine Verkäufe",
                        supportingText = "Im gewählten Zeitraum wurde nichts gebucht.",
                        modifier = Modifier.height(220.dp)
                    )
                }
            } else {
                items(summary.topProducts, key = { it.productName }) { product ->
                    VdListRow(
                        title = product.productName,
                        supportingText = "${product.quantity} verkauft",
                        leading = { RowBadge(product.quantity.toString()) },
                        trailing = { MoneyText(amount = product.totalRevenue) }
                    )
                }
            }

            item { Spacer(Modifier.height(Spacing.xl)) }
        }
    }
}
