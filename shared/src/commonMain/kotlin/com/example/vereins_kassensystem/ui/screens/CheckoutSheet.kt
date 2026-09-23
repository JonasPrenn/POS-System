package com.example.vereins_kassensystem.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.BorderStroke
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

/** Ab dieser Fensterbreite steht im Bardialog das Tastenfeld neben den Beträgen (WindowSizeClass.Expanded). */
private val WideCashBreakpoint = 840.dp

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
    onCheckout: (String) -> Unit,
    // Bardienst ohne Barkasse: „Bar“ bleibt sichtbar, aber aus — mit dem Grund darunter.
    cashAllowed: Boolean = true,
    // Fragt das Kartenterminal selbst nach Trinkgeld, fragt die App nicht.
    tipOnTerminal: Boolean = false
) {
    // In Cent gerechnet: Die Summe der Zeilen trägt Rechenstaub (3 × 4,20 € ergibt
    // 12,600000000000001), und „Passend“ reichte dann nie für „Abschließen“.
    val base = Money.cents(cartTotal + topUpAmount)
    val total = Money.cents(base + tipAmount)
    var mode by remember { mutableStateOf<PayMode?>(null) }
    var cashGiven by remember { mutableStateOf("") }
    // Bar: Das Tastenfeld schreibt in „Gegeben“ oder ins Trinkgeld, je nachdem, welches Feld gewählt ist.
    var tipInput by remember { mutableStateOf("") }
    var editingTip by remember { mutableStateOf(false) }

    // Bar im Querformat: Unter den Beträgen ist kein Platz mehr für das ganze Tastenfeld — auf
    // breiten Geräten steht es deshalb daneben. Gefragt wird die Breite des Fensters, nicht die Lage.
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val wideCash = mode == PayMode.Cash && windowWidth >= WideCashBreakpoint

    val cashGivenValue = Money.cents(Money.parse(cashGiven) ?: 0.0)
    val change = Money.cents(cashGivenValue - total).coerceAtLeast(0.0)
    val cashCovers = cashGivenValue >= total

    // Ein eigener Rahmen im Aussehen des Material-Dialogs: Der AlertDialog wird nie breiter
    // als 560 dp, und im Querformat braucht die Barzahlung die Breite für das Tastenfeld daneben.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            // Capped rather than fillMaxWidth: on a tablet an unbounded dialog stretches the
            // keypad into absurdly wide keys and pushes the change display off the bottom.
            modifier = Modifier
                .padding(Spacing.xl)
                .widthIn(max = if (wideCash) 880.dp else 520.dp),
            shape = AlertDialogDefaults.shape,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column(modifier = Modifier.padding(Spacing.xl)) {
                CompositionLocalProvider(LocalContentColor provides AlertDialogDefaults.titleContentColor) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (mode != null) {
                            IconButton(onClick = {
                                mode = null
                                onSetTipAmount(0.0)
                                cashGiven = ""
                                tipInput = ""
                                editingTip = false
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
                }
                Spacer(Modifier.height(Spacing.lg))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    CompositionLocalProvider(
                        LocalContentColor provides AlertDialogDefaults.textContentColor,
                        LocalTextStyle provides MaterialTheme.typography.bodyMedium
                    ) {
                        TotalHeadline(total)
                        Spacer(Modifier.height(Spacing.lg))

                        when (mode) {
                            null -> PaymentChoice(
                                cashAllowed = cashAllowed,
                                cartTotal = cartTotal,
                                topUpAmount = topUpAmount,
                                selectedMember = selectedMember,
                                categories = categories,
                                onPick = { mode = it }
                            )

                            PayMode.Cash -> CashPane(
                                wide = wideCash,
                                given = cashGivenValue,
                                tip = tipAmount,
                                change = change,
                                editingTip = editingTip,
                                onEditGiven = { editingTip = false },
                                onEditTip = { editingTip = true },
                                onPassend = {
                                    cashGiven = Money.formatPlain(total).replace('.', ',')
                                    editingTip = false
                                },
                                onNote = { note ->
                                    cashGiven = note.toString()
                                    editingTip = false
                                },
                                // „Passt so“: Was über den Einkauf hinaus gegeben wurde, ist Trinkgeld.
                                onChangeAsTip = {
                                    val rest = Money.cents(cashGivenValue - base)
                                    tipInput = Money.formatPlain(rest).replace('.', ',')
                                    onSetTipAmount(rest)
                                    editingTip = false
                                },
                                onKey = { edit ->
                                    if (editingTip) {
                                        tipInput = edit(tipInput)
                                        onSetTipAmount(Money.cents(Money.parse(tipInput) ?: 0.0))
                                    } else {
                                        cashGiven = edit(cashGiven)
                                    }
                                }
                            )

                            PayMode.Card -> when {
                                // Auf eine reine Aufladung gibt es kein Trinkgeld.
                                cartTotal <= 0.0 -> CardNote("Aufladung per Karte — ohne Trinkgeld.")
                                tipOnTerminal -> CardNote("Das Trinkgeld wählt der Gast am Kartenterminal. Gebucht wird, was SumUp meldet.")
                                // Das Terminal fragt nicht selbst (oder ist noch nicht gekoppelt): Die App fragt, auf den Einkauf, nicht auf eine Aufladung.
                                else -> TipPane(
                                    base = cartTotal,
                                    tipAmount = tipAmount,
                                    onSetTipAmount = onSetTipAmount
                                )
                            }

                            PayMode.Balance -> Text(
                                text = "Der Betrag wird direkt vom Deckel des Mitglieds abgezogen.",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
                if (mode != null) {
                    Spacer(Modifier.height(Spacing.xl))
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
            }
        }
    }
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
    cashAllowed: Boolean,
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
            enabled = cashAllowed,
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
    if (!cashAllowed) {
        Text(
            text = "Bardienst ohne Barkasse — an dieser Theke nur Deckel und Karte.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xs)
        )
    }

    if (selectedMember != null) {
        Spacer(Modifier.height(Spacing.md))

        val limit = categories.find { it.id == selectedMember.categoryId }?.negativeBalanceLimit ?: 0.0
        // A top-up is money coming in; paying for it out of the same balance is circular.
        // In Cent verglichen: Mit Rechenstaub in der Summe wäre ein genau reichendes Guthaben zu wenig.
        val canUseBalance = Money.cents(selectedMember.balance - cartTotal) >= limit && topUpAmount == 0.0

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
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 96.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (enabled) container else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant
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
 *
 * Trinkgeld: „Passt so“ ist ein Knopf — das Rückgeld wird Trinkgeld. „Mach neun“ ist ein
 * Betrag: das Feld „Trinkgeld“ antippen, dann schreibt das Tastenfeld dort hinein. Gebucht
 * wird es als Trinkgeld in bar, und die Lade zählt es mit, weil es in ihr liegt.
 */
@Composable
private fun CashPane(
    wide: Boolean,
    given: Double,
    tip: Double,
    change: Double,
    editingTip: Boolean,
    onEditGiven: () -> Unit,
    onEditTip: () -> Unit,
    onPassend: () -> Unit,
    onNote: (Int) -> Unit,
    onChangeAsTip: () -> Unit,
    onKey: ((String) -> String) -> Unit
) {
    val amounts: @Composable () -> Unit = {
        Column {
            // Given and change sit together above the keypad. The change is the number read
            // aloud across the counter, so it must never be the thing that scrolled away.
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                AmountField(
                    label = "Gegeben",
                    amount = given,
                    active = !editingTip,
                    onClick = onEditGiven,
                    modifier = Modifier.weight(1f)
                )
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

            Spacer(Modifier.height(Spacing.sm))

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AmountField(
                    label = "Trinkgeld",
                    amount = tip,
                    active = editingTip,
                    onClick = onEditTip,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onChangeAsTip,
                    enabled = change > 0.0,
                    modifier = Modifier
                        .weight(1.2f)
                        .heightIn(min = TouchTarget.min),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text("Rückgeld als Trinkgeld", textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.height(Spacing.md))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                QuickCash("Passend", onPassend)
                listOf(5, 10, 20, 50).forEach { note ->
                    QuickCash("$note") { onNote(note) }
                }
            }
        }
    }
    val keypad: @Composable () -> Unit = {
        NumericKeypad(
            onDigit = { c -> onKey { current -> AmountInput.digit(current, c) } },
            onBackspace = { onKey { current -> AmountInput.backspace(current) } },
            onDecimalSeparator = { onKey { current -> AmountInput.separator(current) } }
        )
    }

    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            Box(modifier = Modifier.weight(1.2f)) { amounts() }
            Box(modifier = Modifier.weight(1f)) { keypad() }
        }
    } else {
        Column {
            amounts()
            Spacer(Modifier.height(Spacing.md))
            keypad()
        }
    }
}

/** Ein Betrag, in den das Tastenfeld schreibt, wenn er gewählt ist. Der gewählte trägt einen Rahmen. */
@Composable
private fun AmountField(
    label: String,
    amount: Double,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        selected = active,
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        // Eine Stufe heller als der Dialog, damit das Feld als antippbar zu erkennen ist.
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            // Formatted, not the raw keystrokes: "2020" and "20,20" look far too
            // alike on a keypad, and the difference is two thousand euro.
            MoneyText(
                amount = amount,
                style = MoneyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
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

/** Ein Satz zur Kartenzahlung, wo sonst die Trinkgeldwahl stünde. */
@Composable
private fun CardNote(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
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
