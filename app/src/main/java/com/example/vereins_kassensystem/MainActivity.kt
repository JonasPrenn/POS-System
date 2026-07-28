package com.example.vereins_kassensystem

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.vereins_kassensystem.ui.navigation.Destination
import com.example.vereins_kassensystem.ui.navigation.NavLayout
import com.example.vereins_kassensystem.ui.navigation.VereinsDeckelNavigation
import com.example.vereins_kassensystem.ui.screens.*
import com.example.vereins_kassensystem.ui.theme.ClubIdentity
import com.example.vereins_kassensystem.ui.theme.ThemeMode
import com.example.vereins_kassensystem.ui.theme.VereinsDeckelTheme
import com.example.vereins_kassensystem.viewmodel.*
import com.sumup.merchant.reader.api.SumUpAPI
import com.sumup.merchant.reader.api.SumUpLogin
import com.sumup.merchant.reader.api.SumUpPayment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.UUID

/**
 * Hosts navigation and the theme. Screen composition lives in [VereinsDeckelApp]; this
 * class keeps only what needs the Activity itself — the SumUp SDK handshake, which is
 * still `startActivityForResult`-based.
 */
class MainActivity : ComponentActivity() {

    private val salesViewModel: SalesViewModel by viewModels {
        SalesViewModelFactory((application as KassenApplication).repository)
    }

    private val sumupLoginRequest = 1000
    private val sumupCheckoutRequest = 1001

    private fun launchSumUpPayment(amount: Double) {
        val cartItems = salesViewModel.cart.value
        val topUp = salesViewModel.topUpAmount.value
        val tip = salesViewModel.tipAmount.value

        // The 7.0.0 builder has no itemised cart, so the line items become the title.
        val itemDescriptions = cartItems.asSequence().map { item ->
            val name = if (item.variant != null) "${item.product.name} (${item.variant.name})" else item.product.name
            "${item.quantity}x $name"
        }.toMutableList()

        if (topUp > 0.0) itemDescriptions.add("Aufladung")
        if (tip > 0.0) itemDescriptions.add("Trinkgeld (App)")

        val payment = SumUpPayment.builder()
            .total(BigDecimal.valueOf(amount))
            .currency(SumUpPayment.Currency.EUR)
            .title(itemDescriptions.joinToString(", ").take(128))
            .foreignTransactionId(UUID.randomUUID().toString())
            .skipSuccessScreen()
            .tipOnCardReader()
            .build()

        SumUpAPI.checkout(this, payment, sumupCheckoutRequest)
    }

    private fun launchSumUpLogin() {
        val settingsRepository = (application as KassenApplication).settingsRepository
        lifecycleScope.launch {
            val affiliateKey = settingsRepository.sumUpAffiliateKey.first()
            if (affiliateKey.isNotEmpty() && affiliateKey != "YOUR_AFFILIATE_KEY") {
                SumUpAPI.openLoginActivity(
                    this@MainActivity,
                    SumUpLogin.builder(affiliateKey).build(),
                    sumupLoginRequest
                )
            } else {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Bitte erst Affiliate Key in den Einstellungen speichern",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            sumupCheckoutRequest -> {
                if (data != null) {
                    val responseCode = data.getIntExtra(SumUpAPI.Response.RESULT_CODE, -1)
                    val message = data.getStringExtra(SumUpAPI.Response.MESSAGE)

                    when (responseCode) {
                        SumUpAPI.Response.ResultCode.SUCCESSFUL -> {
                            salesViewModel.checkout("CARD")
                            android.widget.Toast.makeText(this, "Zahlung erfolgreich!", android.widget.Toast.LENGTH_LONG).show()
                        }
                        SumUpAPI.Response.ResultCode.ERROR_TRANSACTION_FAILED -> {
                            android.widget.Toast.makeText(this, "Zahlung fehlgeschlagen: $message", android.widget.Toast.LENGTH_LONG).show()
                        }
                        else -> {
                            android.widget.Toast.makeText(this, message ?: "Zahlung abgebrochen oder Fehler ($responseCode)", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            sumupLoginRequest -> {
                if (SumUpAPI.isLoggedIn()) {
                    android.widget.Toast.makeText(this, "Erfolgreich eingeloggt", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val app = application as KassenApplication
            val settingsRepository = app.settingsRepository
            val themeMode by settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val clubIdentity by settingsRepository.clubIdentity.collectAsState(initial = ClubIdentity())

            // Width, not orientation: a tablet in portrait still wants the rail, and a
            // phone in landscape still does not have room for one.
            val widthClass = calculateWindowSizeClass(this).widthSizeClass
            val navLayout = if (widthClass == WindowWidthSizeClass.Compact) {
                NavLayout.BottomBar
            } else {
                NavLayout.Rail
            }

            VereinsDeckelTheme(themeMode = themeMode, clubIdentity = clubIdentity) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    VereinsDeckelApp(
                        app = app,
                        salesViewModel = salesViewModel,
                        navLayout = navLayout,
                        onCardPayment = ::launchSumUpPayment,
                        onSumUpLogin = ::launchSumUpLogin
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VereinsDeckelApp(
    app: KassenApplication,
    salesViewModel: SalesViewModel,
    navLayout: NavLayout,
    onCardPayment: (Double) -> Unit,
    onSumUpLogin: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val repository = app.repository
    val productViewModel: ProductViewModel = viewModel(factory = ProductViewModelFactory(repository))
    val memberViewModel: MemberViewModel = viewModel(factory = MemberViewModelFactory(repository))
    val analyticsViewModel: AnalyticsViewModel = viewModel(factory = AnalyticsViewModelFactory(repository))
    val inventoryViewModel: InventoryViewModel = viewModel(factory = InventoryViewModelFactory(repository))

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
                SalesScreen(
                    viewModel = salesViewModel,
                    onCardPayment = onCardPayment
                )
            }
            composable(Destination.Dashboard.route) {
                HomeScreen(
                    onNavigateToSales = { navController.navigateToDestination(Destination.Sales) },
                    onNavigateToHistory = { navController.navigateToDestination(Destination.History) },
                    onNavigateToProducts = { navController.navigateToDestination(Destination.Products) },
                    onNavigateToMembers = { navController.navigateToDestination(Destination.Members) },
                    analyticsViewModel = analyticsViewModel,
                    productViewModel = productViewModel,
                    memberViewModel = memberViewModel
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
                    settingsRepository = app.settingsRepository,
                    backupRepository = app.backupRepository,
                    onSumUpLogin = onSumUpLogin
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
