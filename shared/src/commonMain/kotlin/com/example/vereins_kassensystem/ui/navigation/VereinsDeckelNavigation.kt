package com.example.vereins_kassensystem.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.ui.theme.ClubTheme
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import com.example.vereins_kassensystem.ui.icons.VdIcons

/**
 * How navigation is presented at this window size.
 *
 * Chosen from the window size class rather than `Configuration.ORIENTATION_LANDSCAPE`,
 * which the old code used — orientation says nothing useful about a tablet in portrait,
 * a foldable, or an app in split-screen, all of which a counter device runs into.
 */
enum class NavLayout { BottomBar, Rail }

/**
 * The navigation frame.
 *
 * Replaces the modal drawer, which cost an open, a scan and a tap for every screen
 * change. On a tablet the rail is simply always there; on a phone the three selling
 * destinations sit in the thumb's reach and the management screens move to an overflow
 * sheet, because a seven-item bottom bar is unusable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VereinsDeckelNavigation(
    layout: NavLayout,
    currentRoute: String?,
    onNavigate: (Destination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var showMoreSheet by remember { mutableStateOf(false) }
    val current = Destination.fromRoute(currentRoute)

    if (showMoreSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMoreSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            ManagementSheet(
                current = current,
                onSelect = {
                    showMoreSheet = false
                    onNavigate(it)
                }
            )
        }
    }

    when (layout) {
        NavLayout.Rail -> Row(modifier = modifier.fillMaxSize()) {
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Start + WindowInsetsSides.Vertical
                    )
                )
            ) {
                Spacer(Modifier.height(Spacing.sm))
                // The rail has room for everything, so nothing hides behind an overflow.
                Destination.primary.forEach { destination ->
                    RailItem(destination, current == destination) { onNavigate(destination) }
                }
                // Explicit width: HorizontalDivider fills max width by default, which
                // stretches the rail's column across the whole screen and leaves the
                // content pane nothing to occupy.
                HorizontalDivider(
                    modifier = Modifier
                        .width(48.dp)
                        .padding(vertical = Spacing.md),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Destination.management.forEach { destination ->
                    RailItem(destination, current == destination) { onNavigate(destination) }
                }
            }
            Surface(
                modifier = Modifier.weight(1f).fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                content = content
            )
        }

        NavLayout.BottomBar -> Column(modifier = modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                color = MaterialTheme.colorScheme.background,
                content = content
            )
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Destination.primary.forEach { destination ->
                    NavigationBarItem(
                        selected = current == destination,
                        onClick = { onNavigate(destination) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ClubTheme.onAccent,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = ClubTheme.accent
                        )
                    )
                }
                NavigationBarItem(
                    selected = current?.group == DestinationGroup.Management,
                    onClick = { showMoreSheet = true },
                    icon = { Icon(VdIcons.MoreHoriz, contentDescription = null) },
                    label = { Text("Mehr") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = ClubTheme.onAccent,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = ClubTheme.accent
                    )
                )
            }
        }
    }
}

@Composable
private fun RailItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(destination.icon, contentDescription = null) },
        label = { Text(destination.label, textAlign = TextAlign.Center) },
        colors = NavigationRailItemDefaults.colors(
            selectedIconColor = ClubTheme.onAccent,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = ClubTheme.accent
        )
    )
}

/** The management destinations, for phones where they will not fit in the bar. */
@Composable
private fun ManagementSheet(
    current: Destination?,
    onSelect: (Destination) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xxl)
    ) {
        Text(
            text = "Verwaltung",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = Spacing.md)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.height(220.dp)
        ) {
            items(Destination.management, key = { it.route }) { destination ->
                Surface(
                    onClick = { onSelect(destination) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (current == destination) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = if (current == destination) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(TouchTarget.sales)
                            .padding(horizontal = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(destination.icon, contentDescription = null)
                        Spacer(Modifier.width(Spacing.md))
                        Text(destination.label, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}
