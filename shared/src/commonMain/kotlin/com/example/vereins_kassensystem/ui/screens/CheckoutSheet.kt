package com.example.vereins_kassensystem.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.displayName
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.ui.components.AmountInput
import com.example.vereins_kassensystem.ui.components.MoneyText
import com.example.vereins_kassensystem.ui.components.NumericKeypad
import com.example.vereins_kassensystem.ui.components.PayButton
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.theme.MoneyLarge
import com.example.vereins_kassensystem.ui.theme.MoneyMedium
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import kotlin.math.ceil
import com.example.vereins_kassensystem.ui.icons.VdIcons

/** Which payment route the user picked. Null while still choosing. */
private enum class PayMode { Cash, Card, Balance }

/**
 * The checkout.
 *
 * Two things changed from the old dialog. Cash is counted on an on-screen keypad instead
 * of the soft keyboard — the keyboard covered half the dialog, put digits on small keys,
 * and moved its decimal separator with the layout. And the change due is the largest
 * thing on screen, because that is the number being read aloud across a counter.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CheckoutDialog(
    categories: List<MemberCategory>,
    cartTotal: Double,
    topUpAmount: Double,
    tipAmount: Double,
    selectedMember: Member?,
    onDismiss: () -> Unit,
    onSetTipAmount: (Double) -> Unit,
    onCheckout: (String) -> Unit
) {
    val total = cartTotal + topUpAmount + tipAmount
    var mode by remember { mutableStateOf<PayMode?>(null) }
    var cashGiven by remember { mutableStateOf("") }

    val cashGivenValue = Money.parse(cashGiven) ?: 0.0
    val change = (cashGivenValue - total).coerceAtLeast(0.0)
    val cashCovers = cashGivenValue >= total

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        // Capped rather than fillMaxWidth: on a tablet an unbounded dialog stretches the
        // keypad into absurdly wide keys and pushes the change display off the bottom.
        modifier = Modifier
            .padding(Spacing.xl)
            .widthIn(max = 520.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mode != null) {
                    IconButton(onClick = {
                        mode = null
                        onSetTipAmount(0.0)
                        cashGiven = ""
                    }) {
                        Icon(VdIcons.ArrowBack, contentDescription = "Zurück")
                    }
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(
                    text = when (mode) {
                        null -> "Zahlung wählen"
                        PayMode.Cash -> "Barzahlung"
                        PayMode.Card -> "Kartenzahlung"
                        PayMode.Balance -> "Vom Deckel"
                    },
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                TotalHeadline(total)
                Spacer(Modifier.height(Spacing.xl))

                when (mode) {
                    null -> PaymentChoice(
                        cartTotal = cartTotal,
                        topUpAmount = topUpAmount,
                        selectedMember = selectedMember,
                        categories = categories,
                        onPick = { mode = it }
                    )

                    PayMode.Cash -> CashPane(
                        cashGiven = cashGiven,
                        given = cashGivenValue,
                        change = change,
                        total = total,
                        onCashGivenChange = { cashGiven = it }
                    )

                    PayMode.Card -> TipPane(
                        base = cartTotal + topUpAmount,
                        tipAmount = tipAmount,
                        onSetTipAmount = onSetTipAmount
                    )

                    PayMode.Balance -> Text(
                        text = "Der Betrag wird direkt vom Deckel des Mitglieds abgezogen.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        },
        confirmButton = {
            if (mode != null) {
                PayButton(
                    amount = total,
                    onClick = {
                        onCheckout(
                            when (mode) {
                                PayMode.Cash -> "CASH"
                                PayMode.Card -> "CARD"
                                else -> "MEMBER_BALANCE"
                            }
                        )
                    },
                    enabled = mode != PayMode.Cash || cashCovers,
                    label = "Abschließen"
                )
            }
        },
        dismissButton = {}
    )
}

@Composable
private fun TotalHeadline(total: Double) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Column(
            modifier = Modifier.padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Gesamtbetrag", style = MaterialTheme.typography.labelMedium)
            MoneyText(amount = total, style = MoneyLarge)
        }
    }
}

@Composable
private fun PaymentChoice(
    cartTotal: Double,
    topUpAmount: Double,
    selectedMember: Member?,
    categories: List<MemberCategory>,
    onPick: (PayMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        PaymentTile(
            label = "Bar",
            icon = VdIcons.Payments,
            onClick = { onPick(PayMode.Cash) },
            modifier = Modifier.weight(1f),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer
        )
        PaymentTile(
            label = "Karte",
            icon = VdIcons.CreditCard,
            onClick = { onPick(PayMode.Card) },
            modifier = Modifier.weight(1f),
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }

    if (selectedMember != null) {
        Spacer(Modifier.height(Spacing.md))

        val limit = categories.find { it.id == selectedMember.categoryId }?.negativeBalanceLimit ?: 0.0
        // A top-up is money coming in; paying for it out of the same balance is circular.
        val canUseBalance = (selectedMember.balance - cartTotal) >= limit && topUpAmount == 0.0

        Surface(
            onClick = { if (canUseBalance) onPick(PayMode.Balance) },
            enabled = canUseBalance,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = if (canUseBalance) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (canUseBalance) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(VdIcons.AccountBalanceWallet, contentDescription = null)
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Deckel · ${selectedMember.displayName}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Guthaben ${Money.format(selectedMember.balance)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!canUseBalance) {
                    Icon(VdIcons.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (!canUseBalance) {
            Text(
                text = if (topUpAmount > 0.0) {
                    "Eine Aufladung kann nicht vom Guthaben bezahlt werden."
                } else {
                    "Guthaben reicht nicht: Limit ${Money.format(limit)}."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs)
            )
        }
    }
}

@Composable
private fun PaymentTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 96.dp),
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(Spacing.sm))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Counting cash. Quick denominations cover most sales in one tap; the keypad handles the
 * rest. "Passend" fills in the exact total, which is what most people hand over.
 */
@Composable
private fun CashPane(
    cashGiven: String,
    given: Double,
    change: Double,
    total: Double,
    onCashGivenChange: (String) -> Unit
) {
    Column {
        // Given and change sit together above the keypad. The change is the number read
        // aloud across the counter, so it must never be the thing that scrolled away.
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Text("Gegeben", style = MaterialTheme.typography.labelMedium)
                    // Formatted, not the raw keystrokes: "2020" and "20,20" look far too
                    // alike on a keypad, and the difference is two thousand euro.
                    MoneyText(
                        amount = given,
                        style = MoneyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Surface(
                modifier = Modifier.weight(1.2f),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Text("Rückgeld", style = MaterialTheme.typography.labelMedium)
                    MoneyText(amount = change, style = MoneyLarge)
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            QuickCash("Passend") { onCashGivenChange(Money.formatPlain(total).replace('.', ',')) }
            listOf(5, 10, 20, 50).forEach { note ->
                QuickCash("$note") { onCashGivenChange(note.toString()) }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        NumericKeypad(
            onDigit = { onCashGivenChange(AmountInput.digit(cashGiven, it)) },
            onBackspace = { onCashGivenChange(AmountInput.backspace(cashGiven)) },
            onDecimalSeparator = { onCashGivenChange(AmountInput.separator(cashGiven)) }
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.QuickCash(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = TouchTarget.min),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/** Tips are offered before handing the terminal over, so the amount is already on it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TipPane(
    base: Double,
    tipAmount: Double,
    onSetTipAmount: (Double) -> Unit
) {
    Column {
        Text("Trinkgeld", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(Spacing.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            FilterChip(
                selected = tipAmount == 0.0,
                onClick = { onSetTipAmount(0.0) },
                label = { Text("Kein") },
                shape = MaterialTheme.shapes.small
            )
            listOf(0.05, 0.10, 0.15).forEach { percent ->
                // Rounded up to the next 50 cents — nobody hands over 1,37 € of tip.
                val tip = ceil(base * percent * 2) / 2.0
                FilterChip(
                    selected = tipAmount == tip,
                    onClick = { onSetTipAmount(tip) },
                    label = { Text("${(percent * 100).toInt()} % · ${Money.format(tip)}") },
                    shape = MaterialTheme.shapes.small
                )
            }
        }
    }
}
