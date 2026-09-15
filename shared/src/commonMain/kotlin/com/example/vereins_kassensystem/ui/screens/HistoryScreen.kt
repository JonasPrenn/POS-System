package com.example.vereins_kassensystem.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.data.entity.Transaction
import com.example.vereins_kassensystem.ui.components.EmptyState
import com.example.vereins_kassensystem.ui.components.MoneyText
import com.example.vereins_kassensystem.ui.components.VdTopBar
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.theme.MoneyMedium
import com.example.vereins_kassensystem.ui.theme.MoneySmall
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.viewmodel.SalesViewModel
import com.example.vereins_kassensystem.platform.VdDate
import com.example.vereins_kassensystem.ui.icons.VdIcons

@Composable
fun HistoryScreen(viewModel: SalesViewModel) {
    val transactions by viewModel.allTransactions.collectAsState()

    val grouped = remember(transactions) {
        transactions.groupBy { it.transactionGroupId }
            .values
            .sortedByDescending { it.firstOrNull()?.timestamp ?: 0L }
    }

    Scaffold(topBar = { VdTopBar(title = "Historie") }) { padding ->
        if (grouped.isEmpty()) {
            EmptyState(
                icon = VdIcons.ReceiptLong,
                title = "Noch keine Buchungen",
                supportingText = "Abgeschlossene Verkäufe erscheinen hier.",
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
                items(grouped, key = { it.first().transactionGroupId }) { group ->
                    TransactionGroupItem(group)
                }
            }
        }
    }
}

@Composable
private fun TransactionGroupItem(items: List<Transaction>) {
    var expanded by remember { mutableStateOf(false) }
    val first = items.first()

    val total = items.sumOf { it.price * it.quantity - it.discountAmount }
    
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Surface(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            val isBalanceMovement = items.all { it.productCategory == "Guthaben" }

            Row(verticalAlignment = Alignment.CenterVertically) {
                PaymentBadge(first.paymentType, isBalanceMovement)
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = first.memberName ?: "Barverkauf",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    Text(
                        text = VdDate.dayAndTime(first.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // The reason a balance moved is the point of recording it, so it is
                    // shown on the collapsed row rather than hidden behind the chevron.
                    first.note?.takeIf { it.isNotBlank() }?.let { note ->
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2
                        )
                    }
                }
                MoneyText(amount = total, style = MoneyMedium)
                Icon(
                    imageVector = VdIcons.ExpandMore,
                    contentDescription = if (expanded) "Zuklappen" else "Aufklappen",
                    modifier = Modifier
                        .padding(start = Spacing.sm)
                        .size(20.dp)
                        .rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = Spacing.md)) {
                    HorizontalDivider(
                        modifier = Modifier.padding(bottom = Spacing.sm),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    items.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.productName, style = MaterialTheme.typography.bodyMedium)
                                if (item.quantity > 1) {
                                    Text(
                                        text = "${item.quantity} × ${Money.format(item.price)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            MoneyText(
                                amount = item.price * item.quantity - item.discountAmount,
                                style = MoneySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Payment type as an icon on its own colour: cash pine, card harbor, the Deckel brass —
 * the same three the rest of the app uses for those ideas.
 */
@Composable
private fun PaymentBadge(paymentType: String, isBalanceMovement: Boolean = false) {
    val icon: ImageVector
    val container: Color
    val content: Color
    if (isBalanceMovement) {
        // A top-up or correction is about the Deckel, not about how a sale was rung up.
        icon = VdIcons.AccountBalanceWallet
        container = MaterialTheme.colorScheme.secondaryContainer
        content = MaterialTheme.colorScheme.onSecondaryContainer
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = container,
            contentColor = content
        ) {
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, contentDescription = "Guthabenbewegung", modifier = Modifier.size(18.dp))
            }
        }
        return
    }
    when (paymentType) {
        "CARD" -> {
            icon = VdIcons.CreditCard
            container = MaterialTheme.colorScheme.tertiaryContainer
            content = MaterialTheme.colorScheme.onTertiaryContainer
        }
        "MEMBER_BALANCE" -> {
            icon = VdIcons.AccountBalanceWallet
            container = MaterialTheme.colorScheme.secondaryContainer
            content = MaterialTheme.colorScheme.onSecondaryContainer
        }
        else -> {
            icon = VdIcons.Payments
            container = MaterialTheme.colorScheme.primaryContainer
            content = MaterialTheme.colorScheme.onPrimaryContainer
        }
    }

    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = container,
        contentColor = content
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = when (paymentType) {
                    "CARD" -> "Kartenzahlung"
                    "MEMBER_BALANCE" -> "Vom Deckel"
                    else -> "Barzahlung"
                },
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
