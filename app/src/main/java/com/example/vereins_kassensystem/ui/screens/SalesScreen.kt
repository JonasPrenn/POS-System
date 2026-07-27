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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    onOpenDrawer: () -> Unit,
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
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "Menü")
                        }
                    },
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
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Default.Menu, contentDescription = "Menü")
                        }
                        Spacer(Modifier.width(8.dp))
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
            .padding(6.dp)
            .aspectRatio(1.1f)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = product.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 20.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (product.trackInventory && product.stockQuantity <= product.minStockLevel) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Niedriger Bestand",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Text(
                    text = product.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    if (!product.hasVariants) {
                        Text(
                            text = "${String.format("%.2f", product.price)} €",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = "Varianten",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    if (product.trackInventory) {
                        Text(
                            text = "Lager: ${product.stockQuantity}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (product.stockQuantity <= product.minStockLevel) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
                        )
                    }
                }
                
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.padding(6.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
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
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Warenkorb",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Row {
                IconButton(onClick = { showManualItemDialog = true }) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = "Manuell", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Leeren", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        
        // Cart Items and Top-up in a scrollable column
        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Cart Items
                    items(cart, key = { it.product.id }) { item ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (item.variant != null) "${item.product.name} (${item.variant.name})" else item.product.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "${item.quantity} x ${String.format( "%.2f", item.variant?.price ?: item.product.price)} €",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (item.discountPercent > 0 || item.fixedDiscount > 0) {
                                            Text(
                                                text = " (-${String.format( "%.2f", (item.variant?.price ?: item.product.price) * item.quantity - item.finalPrice * item.quantity)} €)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    IconButton(
                                        onClick = { showDiscountDialogFor = item },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.LocalOffer,
                                            contentDescription = "Rabatt",
                                            tint = if (item.discountPercent > 0 || item.fixedDiscount > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "${String.format( "%.2f", item.finalPrice * item.quantity)} €",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { onRemove(item.product, item.variant) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.RemoveCircleOutline,
                                            contentDescription = "Entfernen",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                    
                    // Top-up Item in Cart
                    if (topUpAmount > 0) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(
                                            Icons.Default.AccountBalanceWallet,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Guthabenaufladung",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Text(
                                            text = "${String.format( "%.2f", topUpAmount)} €",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        IconButton(
                                            onClick = { onSetTopUpAmount(0.0) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Cancel,
                                                contentDescription = "Entfernen",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        // Member Selection Section
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (selectedMember != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(40.dp)
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
                            Column {
                                Text(
                                    text = selectedMember.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Guthaben: ${String.format( "%.2f", selectedMember.balance)} €",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { onSelectMember(null) }) {
                            Icon(Icons.Default.PersonRemove, contentDescription = "Entfernen", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )
                    
                    Text(
                        text = "Schnell-Aufladung (+)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(5.0, 10.0, 20.0, 50.0).forEach { amount ->
                            Button(
                                onClick = { onSetTopUpAmount(amount) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(0.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Text("${amount.toInt()}€", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { showMemberSelection = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Mitglied für Kauf auswählen", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
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
        title = { Text(if (paymentMode == null) "Zahlungsmethode" else "Zahlung: ${when(paymentMode) {
            "CASH" -> "Bar"
            "CARD" -> "Karte"
            else -> "Mitgliedskonto"
        }}") },
        text = {
            Column {
                Text(
                    text = "Gesamtbetrag: ${String.format( "%.2f", totalAmount)} €",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (topUpAmount > 0 || tipAmount > 0) {
                    val components = mutableListOf<String>()
                    if (cartTotal > 0) components.add("Waren: ${String.format( "%.2f", cartTotal)}€")
                    if (topUpAmount > 0) components.add("Aufladung: ${String.format( "%.2f", topUpAmount)}€")
                    if (tipAmount > 0) components.add("Trinkgeld: ${String.format( "%.2f", tipAmount)}€")
                    Text(
                        text = "(${components.joinToString(" + ")})",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                if (paymentMode == null) {
                    Button(
                        onClick = { paymentMode = "CASH" },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Barzahlung")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { paymentMode = "CARD" },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("Kartenzahlung (SumUp)")
                    }
                    
                    if (selectedMember != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        val category = categories.find { it.id == selectedMember.categoryId }
                        val limit = category?.negativeBalanceLimit ?: 0.0
                        val canPay = (selectedMember.balance - cartTotal) >= limit && topUpAmount == 0.0

                        Text(text = "Mitgliedskonto: ${selectedMember.name}", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = "Guthaben: ${String.format( "%.2f", selectedMember.balance)} € (Limit: ${String.format( "%.2f", limit)} €)",
                            style = MaterialTheme.typography.bodySmall
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { paymentMode = "MEMBER_BALANCE" },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canPay
                        ) {
                            Text("Vom Guthaben abziehen")
                        }
                        if (topUpAmount > 0) {
                            Text(
                                text = "Aufladung kann nicht mit Guthaben bezahlt werden.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        } else if (!canPay) {
                            Text(
                                text = "Limit überschritten! Bitte Guthaben aufladen.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                } else if (paymentMode == "CASH") {
                    OutlinedTextField(
                        value = cashGiven,
                        onValueChange = { cashGiven = it },
                        label = { Text("Gezahlt (€)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "Rückgeld", style = MaterialTheme.typography.labelMedium)
                            Text(
                                text = "${String.format( "%.2f", change)} €",
                                style = MaterialTheme.typography.displayMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                } else if (paymentMode == "CARD") {
                    Text(text = "Trinkgeld hinzufügen?", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val baseAmount = cartTotal + topUpAmount
                    if (!showCustomTipInput) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = tipAmount == 0.0,
                                onClick = { onSetTipAmount(0.0) },
                                label = { Text("Kein") }
                            )
                            if (baseAmount < 10.0) {
                                listOf(1.0, 3.0, 5.0).forEach { tip ->
                                    FilterChip(
                                        selected = tipAmount == tip,
                                        onClick = { onSetTipAmount(tip) },
                                        label = { Text("${tip.toInt()}€") }
                                    )
                                }
                            } else {
                                listOf(0.10, 0.15, 0.20).forEach { percent ->
                                    val tip = ceil(baseAmount * percent * 2) / 2.0 // Round to nearest 0.50
                                    FilterChip(
                                        selected = tipAmount == tip,
                                        onClick = { onSetTipAmount(tip) },
                                        label = { Text("${(percent * 100).toInt()}% (~${String.format( "%.2f", tip)}€)") }
                                    )
                                }
                            }
                            FilterChip(
                                selected = showCustomTipInput,
                                onClick = { showCustomTipInput = true },
                                label = { Text("Andere") }
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = customTipValue,
                                onValueChange = { customTipValue = it },
                                label = { Text("Trinkgeld (€)") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true
                            )
                            IconButton(onClick = { 
                                val tip = customTipValue.replace(",", ".").toDoubleOrNull() ?: 0.0
                                onSetTipAmount(tip)
                                showCustomTipInput = false
                            }) {
                                Icon(Icons.Default.Check, contentDescription = "OK")
                            }
                            IconButton(onClick = { showCustomTipInput = false }) {
                                Icon(Icons.Default.Clear, contentDescription = "Abbrechen")
                            }
                        }
                    }
                } else if (paymentMode == "MEMBER_BALANCE") {
                    Text("Möchtest du den Betrag vom Guthaben abziehen?")
                }
            }
        },
        confirmButton = {
            if (paymentMode != null) {
                Button(
                    onClick = { onCheckout(paymentMode!!) },
                    enabled = paymentMode != "CASH" || cashGivenDouble >= totalAmount
                ) {
                    Text("Bestätigen")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                if (paymentMode != null) {
                    paymentMode = null
                    onSetTipAmount(0.0)
                    cashGiven = ""
                } else {
                    onDismiss()
                }
            }) {
                Text(if (paymentMode != null) "Zurück" else "Abbrechen")
            }
        }
    )
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
