package com.example.vereins_kassensystem.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.Product
import com.example.vereins_kassensystem.ui.theme.MoneyLarge
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.ThemeMode
import com.example.vereins_kassensystem.ui.theme.VereinsDeckelTheme
import com.example.vereins_kassensystem.ui.icons.VdIcons

/*
 * Design QA lives here: one gallery, rendered in both themes side by side in the
 * Android Studio preview pane. Anything that looks wrong in dark usually shows up in
 * the pair before it ever reaches a device.
 */

private val SampleBeer = Product(
    id = 1, name = "Weißbier 0,5l", price = 4.20, category = "Getränke",
    hasVariants = false, servingSize = 0.5
)
private val SampleLowStock = Product(
    id = 2, name = "Radler", price = 3.80, category = "Getränke",
    hasVariants = false, servingSize = 0.5
)
private val SampleVariants = Product(
    id = 3, name = "Pommes", price = 0.0, category = "Küche",
    hasVariants = true
)
private val SampleMember = Member(id = 1, name = "Maria Bauer", balance = 23.50, categoryId = 1)
private val SampleMemberOwing = Member(id = 2, name = "Jonas Prenn", balance = -18.00, categoryId = 1)

@Composable
private fun Gallery() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            SectionLabel("Money voice")
            MoneyText(amount = 1284.50, style = MoneyLarge)

            SectionLabel("Stat tiles")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                MoneyStatTile(
                    label = "Umsatz heute",
                    amount = 1284.50,
                    icon = VdIcons.Payments,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Transaktionen",
                    value = "87",
                    icon = VdIcons.ReceiptLong,
                    modifier = Modifier.weight(1f)
                )
            }

            SectionLabel("Product tiles")
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                ProductTile(SampleBeer, onClick = {}, modifier = Modifier.weight(1f))
                ProductTile(SampleLowStock, onClick = {}, servingsLeft = 3, modifier = Modifier.weight(1f))
                ProductTile(SampleVariants, onClick = {}, modifier = Modifier.weight(1f))
            }

            SectionLabel("Category filter")
            CategoryFilterRow(
                categories = listOf("Getränke", "Küche", "Snacks", "Alkoholfrei"),
                selected = "Getränke",
                onSelect = {},
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel("Member — in credit, and over the limit")
            MemberChip(SampleMember, negativeLimit = -20.0)
            MemberChip(SampleMemberOwing, negativeLimit = -20.0)

            SectionLabel("List row + stepper")
            VdListRow(
                title = "Weißbier 0,5l",
                supportingText = "Getränke",
                leading = { RowBadge("3×") },
                trailing = { MoneyText(amount = 12.60) }
            )
            QuantityStepper(quantity = 3, onIncrease = {}, onDecrease = {})

            SectionLabel("Management row — one frequent action, the rest in the overflow")
            VdListRow(
                title = "Maria Bauer",
                supportingText = "Aktive Mitglieder",
                leading = { RowBadge("MB") },
                onClick = {},
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        VdIconAction(
                            icon = VdIcons.AddCard,
                            contentDescription = "Aufladen",
                            onClick = {},
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        VdRowMenu(
                            items = listOf(
                                RowMenuItem("Bearbeiten", VdIcons.Edit) {},
                                RowMenuItem("Löschen", VdIcons.Delete, destructive = true) {}
                            )
                        )
                    }
                }
            )

            SectionLabel("Section shell")
            VdSection(title = "Backup", icon = VdIcons.Backup) {
                Text(
                    text = "Läuft einmal täglich im Hintergrund.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionLabel("Warning — attention, not failure")
            WarningBanner(
                title = "Lagerwarnung",
                supportingText = "3 Produkte mit niedrigem Bestand",
                onClick = {}
            )

            SectionLabel("Pay button")
            PayButton(amount = 18.00, onClick = {})

            SectionLabel("Keypad")
            NumericKeypad(onDigit = {}, onBackspace = {}, onDecimalSeparator = {})

            SectionLabel("Empty state")
            EmptyState(
                icon = VdIcons.Inventory2,
                title = "Noch keine Produkte",
                supportingText = "Lege dein erstes Produkt an, damit es im Verkauf erscheint.",
                actionLabel = "Produkt anlegen",
                onAction = {},
                modifier = Modifier.height(240.dp)
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm)
    )
}

@Preview(name = "Components — light", showBackground = true, heightDp = 2200)
@Composable
private fun GalleryLightPreview() {
    VereinsDeckelTheme(themeMode = ThemeMode.LIGHT) { Gallery() }
}

@Preview(name = "Components — dark", showBackground = true, heightDp = 2200)
@Composable
private fun GalleryDarkPreview() {
    VereinsDeckelTheme(themeMode = ThemeMode.DARK) { Gallery() }
}
