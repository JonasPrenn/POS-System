package com.example.vereins_kassensystem.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

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
    Sales("sales", "Verkauf", Icons.Default.PointOfSale, DestinationGroup.Primary),
    Dashboard("dashboard", "Übersicht", Icons.Default.Dashboard, DestinationGroup.Primary),
    History("history", "Historie", Icons.AutoMirrored.Filled.ReceiptLong, DestinationGroup.Primary),

    Products("products", "Produkte", Icons.Default.Inventory2, DestinationGroup.Management),
    Members("members", "Mitglieder", Icons.Default.People, DestinationGroup.Management),
    Categories("categories", "Kategorien", Icons.AutoMirrored.Filled.Label, DestinationGroup.Management),
    Analytics("analytics", "Auswertung", Icons.Default.BarChart, DestinationGroup.Management),
    Settings("settings", "Einstellungen", Icons.Default.Settings, DestinationGroup.Management);

    companion object {
        val primary = entries.filter { it.group == DestinationGroup.Primary }
        val management = entries.filter { it.group == DestinationGroup.Management }

        fun fromRoute(route: String?): Destination? = entries.firstOrNull { it.route == route }
    }
}
