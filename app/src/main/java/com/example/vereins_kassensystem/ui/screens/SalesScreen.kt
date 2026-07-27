package com.example.vereins_kassensystem.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.vereins_kassensystem.data.entity.*
import com.example.vereins_kassensystem.data.dao.ProductWithVariants
import com.example.vereins_kassensystem.viewmodel.SalesViewModel
import com.example.vereins_kassensystem.viewmodel.CartItem
import java.util.*
import kotlin.math.ceil

import android.content.res.Configuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesScreen(
    viewModel: SalesViewModel,
    onCardPayment: (Double) -> Unit
) {
    val allProductsWithVariants by viewModel.allProductsWithVariants.collectAsState()
    val cart by viewModel.cart.collectAsState()
    val totalAmount by viewModel.totalAmount.collectAsState()
    val members by viewModel.allMembers.collectAsState()
    val categories by viewModel.allCategories.collectAsState()
    val selectedMember by viewModel.selectedMember.collectAsState()
    val topUpAmount by viewModel.topUpAmount.collectAsState()
    val tipAmount by viewModel.tipAmount.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.checkoutError.collect { error ->
            snackbarHostState.showSnackbar(error)
        }
    }

    var showVariantDialog by remember { mutableStateOf<ProductWithVariants?>(null) }
    var showCheckoutDialog by remember { mutableStateOf(false) }
    var showTopUpDialog by remember { mutableStateOf<Member?>(null) }
    var showCartDrawer by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!isLandscape) {
                TopAppBar(
                    title = { Text("Verkauf", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { showCartDrawer = true }) {
                            BadgedBox(
                                badge = {
                                    if (cart.isNotEmpty()) {
                                        Badge { Text(cart.sumOf { it.quantity }.toString()) }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.ShoppingCart, contentDescription = "Warenkorb")
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (cart.isNotEmpty() && !isLandscape) {
                Surface(
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Gesamt: ${String.format( "%.2f", totalAmount)} €",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Button(onClick = { showCheckoutDialog = true }) {
                            Text("Bezahlen")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Row(modifier = Modifier.padding(padding)) {
            // Products Section
            Column(
                modifier = Modifier
                    .weight(if (isLandscape) 2f else 1f)
                    .fillMaxHeight()
            ) {
                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Verkauf",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    items(allProductsWithVariants, key = { it.product.id }) { productWithVariants ->
                        ProductGridItem(
                            product = productWithVariants.product,
                            onClick = { 
                                if (productWithVariants.variants.isNotEmpty()) {
                                    showVariantDialog = productWithVariants
                                } else {
                                    viewModel.addToCart(productWithVariants.product)
                                }
                            }
                        )
                    }
                }
            }

            // Landscape Cart
            if (isLandscape) {
                VerticalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxHeight()
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        CartContent(
                            cart = cart,
                            members = members,
                            selectedMember = selectedMember,
                            topUpAmount = topUpAmount,
                            onRemove = { p, v -> viewModel.removeFromCart(p, v) },
                            onClear = { viewModel.clearCart() },
                            onSelectMember = { viewModel.selectMember(it) },
                            onSetTopUpAmount = { viewModel.setTopUpAmount(it) },
                            onAddManualItem = { name, price -> viewModel.addManualItem(name, price) },
                            onApplyDiscount = { p, v, pr, f -> viewModel.applyDiscount(p, v, pr, f) }
                        )
                    }
                    if (cart.isNotEmpty()) {
                        Surface(
                            tonalElevation = 4.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Gesamt: ${String.format( "%.2f", totalAmount)} €",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { showCheckoutDialog = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Bezahlen")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showCartDrawer && !isLandscape) {
            ModalBottomSheet(
                onDismissRequest = { showCartDrawer = false }
            ) {
                CartContent(
                    cart = cart,
                    members = members,
                    selectedMember = selectedMember,
                    topUpAmount = topUpAmount,
                    onRemove = { p, v -> viewModel.removeFromCart(p, v) },
                    onClear = { viewModel.clearCart() },
                    onSelectMember = { viewModel.selectMember(it) },
                    onSetTopUpAmount = { viewModel.setTopUpAmount(it) },
                    onAddManualItem = { name, price -> viewModel.addManualItem(name, price) },
                    onApplyDiscount = { p, v, pr, f -> viewModel.applyDiscount(p, v, pr, f) }
                )
            }
        }

        if (showVariantDialog != null) {
            VariantSelectionDialog(
                productWithVariants = showVariantDialog!!,
                onDismiss = { showVariantDialog = null },
                onVariantSelected = { variant ->
                    viewModel.addToCart(showVariantDialog!!.product, variant)
                    showVariantDialog = null
                }
            )
        }

        if (showCheckoutDialog) {
            CheckoutDialog(
                categories = categories,
                cartTotal = cart.sumOf { it.product.price * it.quantity },
                topUpAmount = topUpAmount,
                tipAmount = tipAmount,
                selectedMember = selectedMember,
                onDismiss = { showCheckoutDialog = false },
                onSetTipAmount = { viewModel.setTipAmount(it) },
                onCheckout = { paymentType ->
                    if (paymentType == "CARD") {
                        onCardPayment(totalAmount)
                    } else {
                        viewModel.checkout(paymentType)
                    }
                    showCheckoutDialog = false
                }
            )
        }

        if (showTopUpDialog != null) {
            ManualItemDialog( // Reusing the dialog structure for top-up too if needed, but the plan was to remove POS topup dialog
                onDismiss = { showTopUpDialog = null },
                onConfirm = { _, amount ->
                    viewModel.topUpBalance(showTopUpDialog!!, amount)
                    showTopUpDialog = null
                }
            )
        }
    }
}

@Composable
fun ProductGridItem(product: Product, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(8.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (product.trackInventory && product.stockQuantity <= product.minStockLevel) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.PriorityHigh,
                                contentDescription = "Low Stock",
                                modifier = Modifier.padding(4.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                Text(
                    text = product.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!product.hasVariants) {
                    Text(
                        text = "${String.format("%.2f", product.price)} €",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Varianten",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.padding(8.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

@Composable
fun CartContent(
    cart: List<CartItem>,
    members: List<Member>,
    selectedMember: Member?,
    topUpAmount: Double,
    onRemove: (Product, ProductVariant?) -> Unit,
    onClear: () -> Unit,
    onSelectMember: (Member?) -> Unit,
    onSetTopUpAmount: (Double) -> Unit,
    onAddManualItem: (String, Double) -> Unit,
    onApplyDiscount: (Product, ProductVariant?, Double, Double) -> Unit
) {
    var showMemberSelection by remember { mutableStateOf(false) }
    var showManualItemDialog by remember { mutableStateOf(false) }
    var showDiscountDialogFor by remember { mutableStateOf<CartItem?>(null) }

    if (showMemberSelection) {
        MemberSelectionDialog(
            members = members,
            onDismiss = { showMemberSelection = false },
            onMemberSelected = { onSelectMember(it) }
        )
    }

    if (showManualItemDialog) {
        ManualItemDialog(
            onDismiss = { showManualItemDialog = false },
            onConfirm = { name, price ->
                onAddManualItem(name, price)
                showManualItemDialog = false
            }
        )
    }

    if (showDiscountDialogFor != null) {
        DiscountDialog(
            cartItem = showDiscountDialogFor!!,
            onDismiss = { showDiscountDialogFor = null },
            onConfirm = { percent, fixed ->
                onApplyDiscount(showDiscountDialogFor!!.product, showDiscountDialogFor!!.variant, percent, fixed)
                showDiscountDialogFor = null
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp, horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Bestellung",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(
                    onClick = { showManualItemDialog = true },
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Manuell")
                }
                FilledTonalIconButton(
                    onClick = onClear,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Leeren")
                }
            }
        }
        
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Cart Items
            items(cart, key = { it.product.id }) { item ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${item.quantity}x",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (item.variant != null) "${item.product.name} (${item.variant.name})" else item.product.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            if (item.discountPercent > 0 || item.fixedDiscount > 0) {
                                Text(
                                    text = "Rabatt angewendet",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        
                        Text(
                            text = "${String.format("%.2f", item.finalPrice * item.quantity)} €",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Black
                        )
                        
                        IconButton(onClick = { showDiscountDialogFor = item }) {
                            Icon(Icons.Default.LocalOffer, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        
                        IconButton(onClick = { onRemove(item.product, item.variant) }) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            
            // Top-up Item
            if (topUpAmount > 0) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AccountBalanceWallet, null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Guthabenaufladung",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${String.format("%.2f", topUpAmount)} €",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Black
                            )
                            IconButton(onClick = { onSetTopUpAmount(0.0) }) {
                                Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // Member Section
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.05f),
            shape = RoundedCornerShape(24.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (selectedMember != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = selectedMember.name.take(1).uppercase(),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = selectedMember.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Guthaben: ${String.format("%.2f", selectedMember.balance)} €",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onSelectMember(null) }) {
                            Icon(Icons.Default.PersonRemove, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(5.0, 10.0, 20.0, 50.0).forEach { amount ->
                            FilledTonalButton(
                                onClick = { onSetTopUpAmount(amount) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("${amount.toInt()}€", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { showMemberSelection = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Icon(Icons.Default.PersonAdd, null)
                        Spacer(Modifier.width(12.dp))
                        Text("Mitglied auswählen", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

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
    val totalAmount = cartTotal + topUpAmount + tipAmount
    var paymentMode by remember { mutableStateOf<String?>(null) } // "CASH", "CARD", "MEMBER_BALANCE"
    
    // Change Calculator state
    var cashGiven by remember { mutableStateOf("") }
    val cashGivenDouble = cashGiven.replace(",", ".").toDoubleOrNull() ?: 0.0
    val change = if (cashGivenDouble >= totalAmount) cashGivenDouble - totalAmount else 0.0

    // Tip selection logic
    var showCustomTipInput by remember { mutableStateOf(false) }
    var customTipValue by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(24.dp).fillMaxWidth().wrapContentHeight(),
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (paymentMode != null) {
                    IconButton(onClick = { 
                        paymentMode = null
                        onSetTipAmount(0.0)
                        cashGiven = ""
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
                Text(
                    text = if (paymentMode == null) "Zahlung wählen" else "Zahlung: ${when(paymentMode) {
                        "CASH" -> "Bar"
                        "CARD" -> "Karte"
                        else -> "Mitglied"
                    }}",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        },
        text = {
            Column {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "Gesamtbetrag", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = "${String.format("%.2f", totalAmount)} €",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                if (paymentMode == null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PaymentMethodTile(
                            label = "Barzahlung",
                            icon = Icons.Default.Payments,
                            onClick = { paymentMode = "CASH" },
                            modifier = Modifier.weight(1f)
                        )
                        PaymentMethodTile(
                            label = "Kartenzahlung",
                            icon = Icons.Default.CreditCard,
                            onClick = { paymentMode = "CARD" },
                            modifier = Modifier.weight(1f),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    
                    if (selectedMember != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        val category = categories.find { it.id == selectedMember.categoryId }
                        val limit = category?.negativeBalanceLimit ?: 0.0
                        val canPay = (selectedMember.balance - cartTotal) >= limit && topUpAmount == 0.0

                        Surface(
                            onClick = { if (canPay) paymentMode = "MEMBER_BALANCE" },
                            enabled = canPay,
                            shape = RoundedCornerShape(24.dp),
                            color = if (canPay) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(40.dp)) {
                                    Icon(Icons.Default.AccountBalanceWallet, null, modifier = Modifier.padding(8.dp), tint = Color.White)
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "Mitglied: ${selectedMember.name}", fontWeight = FontWeight.Bold)
                                    Text(
                                        text = "Guthaben: ${String.format("%.2f", selectedMember.balance)} €",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (!canPay) {
                                    Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        if (topUpAmount > 0) {
                            Text(
                                text = "Aufladung kann nicht mit Guthaben bezahlt werden.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp, start = 8.dp)
                            )
                        }
                    }
                } else if (paymentMode == "CASH") {
                    OutlinedTextField(
                        value = cashGiven,
                        onValueChange = { cashGiven = it },
                        label = { Text("Gegebener Betrag (€)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        shape = RoundedCornerShape(16.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "Rückgeld", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = "${String.format("%.2f", change)} €",
                                style = MaterialTheme.typography.displayMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                } else if (paymentMode == "CARD") {
                    Text(text = "Trinkgeld hinzufügen?", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val baseAmount = cartTotal + topUpAmount
                    if (!showCustomTipInput) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = tipAmount == 0.0,
                                onClick = { onSetTipAmount(0.0) },
                                label = { Text("Kein") },
                                shape = RoundedCornerShape(12.dp)
                            )
                            listOf(0.05, 0.10, 0.15).forEach { percent ->
                                val tip = ceil(baseAmount * percent * 2) / 2.0 // Round to nearest 0.50
                                FilterChip(
                                    selected = tipAmount == tip,
                                    onClick = { onSetTipAmount(tip) },
                                    label = { Text("${(percent * 100).toInt()}% (+${String.format("%.1f", tip)}€)") },
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                            FilterChip(
                                selected = showCustomTipInput,
                                onClick = { showCustomTipInput = true },
                                label = { Text("Andere") },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = customTipValue,
                                onValueChange = { customTipValue = it },
                                label = { Text("Betrag (€)") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                            IconButton(onClick = { 
                                val tip = customTipValue.replace(",", ".").toDoubleOrNull() ?: 0.0
                                onSetTipAmount(tip)
                                showCustomTipInput = false
                            }) {
                                Icon(Icons.Default.Check, contentDescription = "OK", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else if (paymentMode == "MEMBER_BALANCE") {
                    Text("Der Betrag wird direkt vom Guthaben des Mitglieds abgezogen.")
                }
            }
        },
        confirmButton = {
            if (paymentMode != null) {
                Button(
                    onClick = { onCheckout(paymentMode!!) },
                    enabled = paymentMode != "CASH" || cashGivenDouble >= totalAmount,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Zahlung abschließen", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {}
    )
}

@Composable
fun PaymentMethodTile(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(100.dp),
        shape = RoundedCornerShape(24.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun VariantSelectionDialog(
    productWithVariants: ProductWithVariants,
    onDismiss: () -> Unit,
    onVariantSelected: (ProductVariant) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(productWithVariants.product.name, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Bitte eine Variante wählen:", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))
                productWithVariants.variants.forEach { variant ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onVariantSelected(variant) }
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(variant.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${String.format( "%.2f", variant.price)} €",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberSelectionDialog(
    members: List<Member>,
    onDismiss: () -> Unit,
    onMemberSelected: (Member) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredMembers = remember(searchQuery, members) {
        if (searchQuery.isBlank()) {
            members
        } else {
            members.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mitglied auswählen") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Suche") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Löschen")
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(filteredMembers) { member ->
                        ListItem(
                            headlineContent = { Text(member.name) },
                            supportingContent = { Text("Guthaben: ${String.format( "%.2f", member.balance)} €") },
                            modifier = Modifier.clickable {
                                onMemberSelected(member)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Schließen")
            }
        }
    )
}

@Composable
fun ManualItemDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manueller Betrag") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Beschreibung (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Betrag (€)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val priceDouble = price.replace(",", ".").toDoubleOrNull() ?: 0.0
                    val finalName = name.ifBlank { "Manueller Betrag" }
                    onConfirm(finalName, priceDouble)
                },
                enabled = price.isNotBlank()
            ) {
                Text("Hinzufügen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        }
    )
}

@Composable
fun DiscountDialog(
    cartItem: CartItem,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double) -> Unit
) {
    var percent by remember { mutableStateOf(cartItem.discountPercent.toString()) }
    var fixed by remember { mutableStateOf(cartItem.fixedDiscount.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rabatt anwenden", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (cartItem.variant != null) "${cartItem.product.name} (${cartItem.variant.name})" else cartItem.product.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedTextField(
                    value = percent,
                    onValueChange = { percent = it },
                    label = { Text("Prozent (%)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = fixed,
                    onValueChange = { fixed = it },
                    label = { Text("Fixbetrag (€)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val p = percent.replace(",", ".").toDoubleOrNull() ?: 0.0
                val f = fixed.replace(",", ".").toDoubleOrNull() ?: 0.0
                onConfirm(p, f)
            }) {
                Text("Anwenden")
            }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(0.0, 0.0) }) {
                Text("Rabatt entfernen", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
