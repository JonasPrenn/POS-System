package com.example.vereins_kassensystem

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.activity.viewModels
import com.sumup.merchant.reader.api.SumUpAPI
import com.sumup.merchant.reader.api.SumUpLogin
import com.sumup.merchant.reader.api.SumUpPayment
import com.example.vereins_kassensystem.ui.screens.*
import com.example.vereins_kassensystem.ui.theme.VereinsDeckelTheme
import com.example.vereins_kassensystem.viewmodel.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.UUID

/**
 * Main Activity for the VereinsDeckel app.
 * Handles Navigation setup and Material 3 Theme application.
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

        // Construct a descriptive title since the SDK 7.0.0 might not support itemized carts natively in the builder
        val itemDescriptions = cartItems.asSequence().map { item ->
            val name = if (item.variant != null) "${item.product.name} (${item.variant.name})" else item.product.name
            "${item.quantity}x $name"
        }.toMutableList()
        
        if (topUp > 0.0) itemDescriptions.add("Aufladung")
        if (tip > 0.0) itemDescriptions.add("Trinkgeld (App)")

        val paymentBuilder = SumUpPayment.builder()
            .total(BigDecimal.valueOf(amount))
            .currency(SumUpPayment.Currency.EUR)
            .title(itemDescriptions.joinToString(", ").take(128))
            .foreignTransactionId(UUID.randomUUID().toString())
            .skipSuccessScreen()
            .tipOnCardReader() // Enable tipping on terminal

        val payment = paymentBuilder.build()
        SumUpAPI.checkout(this, payment, sumupCheckoutRequest)
    }

    private fun launchSumUpLogin() {
        val app = application as KassenApplication
        val settingsRepository = app.settingsRepository
        lifecycleScope.launch {
            val affiliateKey = settingsRepository.sumUpAffiliateKey.first()
            if ((affiliateKey.isNotEmpty()) && (affiliateKey != "YOUR_AFFILIATE_KEY")) {
                SumUpAPI.openLoginActivity(this@MainActivity, SumUpLogin.builder(affiliateKey).build(), sumupLoginRequest)
            } else {
                android.widget.Toast.makeText(this@MainActivity, "Bitte erst Affiliate Key in den Einstellungen speichern", android.widget.Toast.LENGTH_LONG).show()
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. Enable full edge-to-edge support for modern Android aesthetics.
        enableEdgeToEdge()
        
        setContent {
            // 2. Apply the custom Material 3 theme.
            VereinsDeckelTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                    val scope = rememberCoroutineScope()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route

                    val app = application as KassenApplication
                    val repository = app.repository
                    val settingsRepository = app.settingsRepository
                    val productViewModel: ProductViewModel = viewModel(factory = ProductViewModelFactory(repository))
                    val memberViewModel: MemberViewModel = viewModel(factory = MemberViewModelFactory(repository))
                    val analyticsViewModel: AnalyticsViewModel = viewModel(factory = AnalyticsViewModelFactory(repository))

                    LaunchedEffect(Unit) {
                        val affiliateKey = settingsRepository.sumUpAffiliateKey.first()
                        if (!SumUpAPI.isLoggedIn() && affiliateKey.isNotEmpty() && affiliateKey != "YOUR_AFFILIATE_KEY") {
                            SumUpAPI.openLoginActivity(this@MainActivity, SumUpLogin.builder(affiliateKey).build(), sumupLoginRequest)
                        }
                    }

                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            ModalDrawerSheet(
                                drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                                drawerContainerColor = MaterialTheme.colorScheme.surface,
                                drawerContentColor = MaterialTheme.colorScheme.onSurface
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.primary)
                                        .padding(vertical = 40.dp, horizontal = 24.dp)
                                ) {
                                    Column {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.Storefront,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSecondary,
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = "VereinsDeckel",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Text(
                                            text = "Club POS System",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                                    NavigationDrawerItem(
                                        label = { Text("Dashboard", fontWeight = FontWeight.Bold) },
                                        selected = currentRoute == "dashboard",
                                        icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                                        onClick = {
                                            navController.navigate("dashboard") {
                                                popUpTo("dashboard") { inclusive = true }
                                            }
                                            scope.launch { drawerState.close() }
                                        },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    NavigationDrawerItem(
                                        label = { Text("Verkauf (POS)") },
                                        selected = currentRoute == "sales",
                                        icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
                                        onClick = {
                                            navController.navigate("sales")
                                            scope.launch { drawerState.close() }
                                        },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    NavigationDrawerItem(
                                        label = { Text("Historie") },
                                        selected = currentRoute == "history",
                                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                        onClick = {
                                            navController.navigate("history")
                                            scope.launch { drawerState.close() }
                                        },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp))
                                    Text(
                                        "Verwaltung",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                                    )

                                    NavigationDrawerItem(
                                        label = { Text("Produkte") },
                                        selected = currentRoute == "products",
                                        icon = { Icon(Icons.Default.Inventory, contentDescription = null) },
                                        onClick = {
                                            navController.navigate("products")
                                            scope.launch { drawerState.close() }
                                        },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    NavigationDrawerItem(
                                        label = { Text("Mitglieder") },
                                        selected = currentRoute == "members",
                                        icon = { Icon(Icons.Default.People, contentDescription = null) },
                                        onClick = {
                                            navController.navigate("members")
                                            scope.launch { drawerState.close() }
                                        },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    NavigationDrawerItem(
                                        label = { Text("Kategorien") },
                                        selected = currentRoute == "categories",
                                        icon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                                        onClick = {
                                            navController.navigate("categories")
                                            scope.launch { drawerState.close() }
                                        },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp, horizontal = 12.dp))

                                    NavigationDrawerItem(
                                        label = { Text("Auswertung") },
                                        selected = currentRoute == "analytics",
                                        icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                                        onClick = {
                                            navController.navigate("analytics")
                                            scope.launch { drawerState.close() }
                                        },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    NavigationDrawerItem(
                                        label = { Text("Einstellungen") },
                                        selected = currentRoute == "settings",
                                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                        onClick = {
                                            navController.navigate("settings")
                                            scope.launch { drawerState.close() }
                                        },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }
                            }
                        }
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = "dashboard",
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable("dashboard") {
                                HomeScreen(
                                    onNavigateToSales = { navController.navigate("sales") },
                                    onNavigateToHistory = { navController.navigate("history") },
                                    onNavigateToProducts = { navController.navigate("products") },
                                    onNavigateToMembers = { navController.navigate("members") },
                                    onOpenDrawer = { scope.launch { drawerState.open() } },
                                    analyticsViewModel = analyticsViewModel,
                                    productViewModel = productViewModel,
                                    memberViewModel = memberViewModel
                                )
                            }
                            composable("sales") {
                                SalesScreen(
                                    viewModel = salesViewModel,
                                    onOpenDrawer = { scope.launch { drawerState.open() } },
                                    onCardPayment = { amount -> launchSumUpPayment(amount) },
                                )
                            }
                            composable("history") {
                                HistoryScreen(
                                    viewModel = salesViewModel,
                                    onOpenDrawer = { scope.launch { drawerState.open() } }
                                )
                            }
                            composable("products") {
                                ProductManagementScreen(
                                    viewModel = productViewModel,
                                    onOpenDrawer = { scope.launch { drawerState.open() } }
                                )
                            }
                            composable("members") {
                                MemberManagementScreen(
                                    viewModel = memberViewModel,
                                    onOpenDrawer = { scope.launch { drawerState.open() } },
                                    onMemberClick = { member ->
                                        salesViewModel.selectMember(member)
                                        navController.navigate("sales") {
                                            popUpTo("sales") { inclusive = true }
                                        }
                                    }
                                )
                            }
                            composable("categories") {
                                MemberCategoryManagementScreen(
                                    viewModel = memberViewModel,
                                    onOpenDrawer = { scope.launch { drawerState.open() } }
                                )
                            }
                            composable("analytics") {
                                AnalyticsScreen(
                                    viewModel = analyticsViewModel,
                                    onOpenDrawer = { scope.launch { drawerState.open() } }
                                )
                            }
                            composable("settings") {
                                SettingsScreen(
                                    settingsRepository = settingsRepository,
                                    backupRepository = app.backupRepository,
                                    onOpenDrawer = { scope.launch { drawerState.open() } },
                                    onSumUpLogin = { launchSumUpLogin() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
