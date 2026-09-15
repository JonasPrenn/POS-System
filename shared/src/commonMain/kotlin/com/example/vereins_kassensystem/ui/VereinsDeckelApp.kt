package com.example.vereins_kassensystem.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.vereins_kassensystem.AppGraph
import com.example.vereins_kassensystem.platform.LocalPlatform
import com.example.vereins_kassensystem.ui.navigation.Destination
import com.example.vereins_kassensystem.ui.navigation.NavLayout
import com.example.vereins_kassensystem.ui.navigation.VereinsDeckelNavigation
import com.example.vereins_kassensystem.ui.screens.AnalyticsScreen
import com.example.vereins_kassensystem.ui.screens.HistoryScreen
import com.example.vereins_kassensystem.ui.screens.HomeScreen
import com.example.vereins_kassensystem.ui.screens.InventoryScreen
import com.example.vereins_kassensystem.ui.screens.MemberCategoryManagementScreen
import com.example.vereins_kassensystem.ui.screens.MemberManagementScreen
import com.example.vereins_kassensystem.ui.screens.ProductManagementScreen
import com.example.vereins_kassensystem.ui.screens.SalesScreen
import com.example.vereins_kassensystem.ui.screens.SettingsScreen
import com.example.vereins_kassensystem.ui.theme.ClubIdentity
import com.example.vereins_kassensystem.ui.theme.ThemeMode
import com.example.vereins_kassensystem.ui.theme.VereinsDeckelTheme
import com.example.vereins_kassensystem.viewmodel.AnalyticsViewModel
import com.example.vereins_kassensystem.viewmodel.InventoryViewModel
import com.example.vereins_kassensystem.viewmodel.MemberViewModel
import com.example.vereins_kassensystem.viewmodel.ProductViewModel
import com.example.vereins_kassensystem.viewmodel.SalesViewModel

/**
 * Die Wurzel der Oberfläche, auf beiden Plattformen dieselbe.
 *
 * Der Host — MainActivity unter Android, der ComposeUIViewController unter iOS — reicht
 * nur den [AppGraph] herein. Thema, Navigation und die ViewModels entstehen hier; die
 * Hosts kennen keinen Bildschirm mehr beim Namen.
 */
@Composable
fun VereinsDeckelApp(graph: AppGraph) {
    CompositionLocalProvider(LocalPlatform provides graph.platform) {
        val settings = graph.settingsRepository
        val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
        val clubIdentity by settings.clubIdentity.collectAsState(initial = ClubIdentity())

        VereinsDeckelTheme(themeMode = themeMode, clubIdentity = clubIdentity) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                // Breite, nicht Ausrichtung: Ein Tablet im Hochformat will die Leiste
                // an der Seite, ein Telefon quer hat trotzdem keinen Platz dafür. Die
                // Grenze ist die von WindowSizeClass.Compact.
                BoxWithConstraints {
                    val navLayout = if (maxWidth < 600.dp) NavLayout.BottomBar else NavLayout.Rail
                    AppNavigation(graph, navLayout)
                }
            }
        }
    }
}

@Composable
private fun AppNavigation(graph: AppGraph, navLayout: NavLayout) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Alle ViewModels hängen am Wurzel-Owner, nicht an einzelnen Zielen: So übersteht
    // ein halb gefüllter Warenkorb den Abstecher in die Historie, und die Listen der
    // Verwaltung müssen nicht bei jedem Wechsel neu geladen werden.
    val repository = graph.repository
    val salesViewModel: SalesViewModel = viewModel { SalesViewModel(repository) }
    val productViewModel: ProductViewModel = viewModel { ProductViewModel(repository) }
    val memberViewModel: MemberViewModel = viewModel { MemberViewModel(repository) }
    val analyticsViewModel: AnalyticsViewModel = viewModel { AnalyticsViewModel(repository) }
    val inventoryViewModel: InventoryViewModel = viewModel { InventoryViewModel(repository) }

    VereinsDeckelNavigation(
        layout = navLayout,
        currentRoute = currentRoute,
        onNavigate = { navController.navigateToDestination(it) }
    ) {
        NavHost(
            navController = navController,
            startDestination = Destination.Sales.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Destination.Sales.route) {
                SalesScreen(viewModel = salesViewModel)
            }
            composable(Destination.Dashboard.route) {
                HomeScreen(
                    onNavigateToSales = { navController.navigateToDestination(Destination.Sales) },
                    onNavigateToHistory = { navController.navigateToDestination(Destination.History) },
                    onNavigateToProducts = { navController.navigateToDestination(Destination.Products) },
                    onNavigateToMembers = { navController.navigateToDestination(Destination.Members) },
                    analyticsViewModel = analyticsViewModel,
                    productViewModel = productViewModel,
                    memberViewModel = memberViewModel,
                    inventoryViewModel = inventoryViewModel
                )
            }
            composable(Destination.History.route) {
                HistoryScreen(viewModel = salesViewModel)
            }
            composable(Destination.Products.route) {
                ProductManagementScreen(viewModel = productViewModel)
            }
            composable(Destination.Inventory.route) {
                InventoryScreen(viewModel = inventoryViewModel)
            }
            composable(Destination.Members.route) {
                MemberManagementScreen(
                    viewModel = memberViewModel,
                    onMemberClick = { member ->
                        salesViewModel.selectMember(member)
                        navController.navigateToDestination(Destination.Sales)
                    }
                )
            }
            composable(Destination.Categories.route) {
                MemberCategoryManagementScreen(viewModel = memberViewModel)
            }
            composable(Destination.Analytics.route) {
                AnalyticsScreen(viewModel = analyticsViewModel)
            }
            composable(Destination.Settings.route) {
                SettingsScreen(
                    settingsRepository = graph.settingsRepository,
                    backupRepository = graph.backupRepository
                )
            }
        }
    }
}

/**
 * Switching top-level destinations should not stack them.
 *
 * Without this, tapping Verkauf → Historie → Verkauf leaves three entries on the back
 * stack and Back walks through the tab history instead of leaving. Saving and restoring
 * state keeps a half-built cart intact across a detour to look something up.
 */
private fun NavHostController.navigateToDestination(destination: Destination) {
    navigate(destination.route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
