package com.example.vereins_kassensystem.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vereins_kassensystem.viewmodel.AnalyticsViewModel
import com.example.vereins_kassensystem.viewmodel.DateRange
import com.example.vereins_kassensystem.viewmodel.ProductSales
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel
) {
    val summary by viewModel.summary.collectAsState()
    val currentRange by viewModel.dateRange.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auswertung", fontWeight = FontWeight.ExtraBold) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Date Filter
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    DateRange.entries.forEachIndexed { index, range ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = DateRange.entries.size),
                            onClick = { viewModel.setDateRange(range) },
                            selected = currentRange == range,
                            label = { 
                                Text(
                                    when(range) {
                                        DateRange.TODAY -> "Heute"
                                        DateRange.LAST_7_DAYS -> "7T"
                                        DateRange.LAST_30_DAYS -> "30T"
                                        DateRange.ALL_TIME -> "Alles"
                                    },
                                    fontSize = 12.sp
                                )
                            }
                        )
                    }
                }
            }

            // Summary Grid
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SummaryCard(
                            modifier = Modifier.weight(1f),
                            title = "Gesamtumsatz",
                            value = "${String.format("%.2f", summary.totalSales)} €",
                            icon = Icons.Default.Assessment,
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        SummaryCard(
                            modifier = Modifier.weight(1f),
                            title = "Trinkgeld",
                            value = "${String.format("%.2f", summary.totalTips)} €",
                            icon = Icons.Default.Favorite,
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SummaryCard(
                            modifier = Modifier.weight(1f),
                            title = "Bar",
                            value = "${String.format("%.2f", summary.cashSales)} €",
                            icon = Icons.Default.Payments,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SummaryCard(
                            modifier = Modifier.weight(1f),
                            title = "Karte",
                            value = "${String.format("%.2f", summary.cardSales)} €",
                            icon = Icons.Default.CreditCard,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Top Products Section
            item {
                Text(
                    text = "Top Produkte",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (summary.topProducts.isEmpty()) {
                item {
                    Text(
                        "Keine Verkäufe im gewählten Zeitraum",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 32.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                items(summary.topProducts) { product ->
                    TopProductItem(product)
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun SummaryCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color
) {
    ElevatedCard(
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(12.dp))
            Text(text = title, style = MaterialTheme.typography.labelMedium, color = contentColor.copy(alpha = 0.7f))
            Text(text = value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = contentColor)
        }
    }
}

@Composable
fun TopProductItem(product: ProductSales) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 0.5.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = product.quantity.toString(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            
            Spacer(Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(text = product.productName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(text = "Menge: ${product.quantity}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            
            Text(
                text = "${String.format("%.2f", product.totalRevenue)} €",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
