package com.example.vereins_kassensystem.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.example.vereins_kassensystem.ui.icons.VdIcons

/**
 * Every place the app can go, in one list.
 *
 * `MainActivity` previously spent roughly 135 lines on eight near-identical
 * `NavigationDrawerItem` blocks, each repeating the same colour arguments. Describing
 * the destinations as data means the bottom bar, the rail and the overflow sheet are
 * three renderings of one source, and adding a screen is one entry.
 */
enum class DestinationGroup { Primary, Management }

enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val group: DestinationGroup
) {
    /*
     * Sales comes first deliberately: it is what the app is for, and it is the screen a
     * volunteer opens when a queue forms. The old start destination was the dashboard.
     */
    Sales("sales", "Verkauf", VdIcons.PointOfSale, DestinationGroup.Primary),
    Dashboard("dashboard", "Übersicht", VdIcons.Dashboard, DestinationGroup.Primary),
    History("history", "Historie", VdIcons.ReceiptLong, DestinationGroup.Primary),

    Products("products", "Produkte", VdIcons.Inventory2, DestinationGroup.Management),
    Inventory("inventory", "Lagerbestand", VdIcons.Warehouse, DestinationGroup.Management),
    Members("members", "Mitglieder", VdIcons.People, DestinationGroup.Management),
    Categories("categories", "Kategorien", VdIcons.Label, DestinationGroup.Management),
    Analytics("analytics", "Auswertung", VdIcons.BarChart, DestinationGroup.Management),
    Settings("settings", "Einstellungen", VdIcons.Settings, DestinationGroup.Management);

    companion object {
        val primary = entries.filter { it.group == DestinationGroup.Primary }
        val management = entries.filter { it.group == DestinationGroup.Management }

        fun fromRoute(route: String?): Destination? = entries.firstOrNull { it.route == route }
    }
}
