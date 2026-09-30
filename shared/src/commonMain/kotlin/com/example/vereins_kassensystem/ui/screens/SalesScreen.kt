package com.example.vereins_kassensystem.ui.screens

import androidx.compose.runtime.produceState
import com.example.vereins_kassensystem.ui.components.MemberSelectionDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.vereins_kassensystem.data.dao.ProductWithVariants
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.displayName
import com.example.vereins_kassensystem.ui.theme.VereinsColors
import com.example.vereins_kassensystem.data.entity.matches
import com.example.vereins_kassensystem.data.entity.ProductVariant
import com.example.vereins_kassensystem.ui.components.EmptyState
import com.example.vereins_kassensystem.ui.components.CategoryFilterRow
import com.example.vereins_kassensystem.ui.components.MemberAvatar
import com.example.vereins_kassensystem.ui.components.MoneyText
import com.example.vereins_kassensystem.ui.components.PayButton
import com.example.vereins_kassensystem.ui.components.ProductTile
import com.example.vereins_kassensystem.ui.components.TileAction
import com.example.vereins_kassensystem.ui.components.TrailingChip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.example.vereins_kassensystem.ui.components.QuantityStepper
import com.example.vereins_kassensystem.ui.components.VdTopBar
import com.example.vereins_kassensystem.ui.format.Money
import com.example.vereins_kassensystem.ui.theme.MoneyMedium
import com.example.vereins_kassensystem.ui.theme.MoneySmall
import com.example.vereins_kassensystem.ui.theme.Spacing
import com.example.vereins_kassensystem.ui.theme.balanceColor
import com.example.vereins_kassensystem.viewmodel.CartItem
import com.example.vereins_kassensystem.viewmodel.CashState
import com.example.vereins_kassensystem.viewmodel.CashViewModel
import com.example.vereins_kassensystem.ui.components.OpenCashDialog
import com.example.vereins_kassensystem.ui.theme.TouchTarget
import com.example.vereins_kassensystem.viewmodel.SalesViewModel
import com.example.vereins_kassensystem.ui.icons.VdIcons
import com.example.vereins_kassensystem.platform.LocalPlatform

/** Below this the cart cannot sit beside the grid without squeezing both. */
private val TwoPaneBreakpoint = 720.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesScreen(viewModel: SalesViewModel, cashViewModel: CashViewModel) {
    val payments = LocalPlatform.current.payments
    // Ohne offene Kasse wird nicht kassiert: Statt „Bezahlen“ steht dann „Kasse öffnen“ im Warenkorb.
    val cash by cashViewModel.state.collectAsState()
    val cashOpen = cash.session != null
    val products by viewModel.allProductsWithVariants.collectAsState()
    val categories by viewModel.productCategories.collectAsState()
    val cart by viewModel.cart.collectAsState()
    val itemCount by viewModel.itemCount.collectAsState()
    val total by viewModel.totalAmount.collectAsState()
    val members by viewModel.allMembers.collectAsState()
    val memberCategories by viewModel.allCategories.collectAsState()
    val selectedMember by viewModel.selectedMember.collectAsState()
    val cashBlocked by viewModel.cashBlocked.collectAsState()
    val topUpAmount by viewModel.topUpAmount.collectAsState()
    val tipAmount by viewModel.tipAmount.collectAsState()
    val hiddenIds by viewModel.hiddenProductIds.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.checkoutError.collect { snackbarHostState.showSnackbar(it) }
    }

    var search by remember { mutableStateOf("") }
    // Die Suche ist ein Symbol oben rechts und öffnet sich erst auf Antippen (Wunsch vom 30. September 2026).
    var searchOpen by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    // „Ausgeblendet“: der letzte Chip, keine Kategorie. Ausgeblendetes steht nur dort — verkaufen lässt es sich trotzdem.
    var showHidden by remember { mutableStateOf(false) }
    val hiddenCount = remember(products, hiddenIds) { products.count { it.product.id in hiddenIds } }
    // Wird die gewählte Kategorie leer oder ist nichts mehr ausgeblendet, zurück auf „Alle“.
    LaunchedEffect(categories) { if (selectedCategory != null && selectedCategory !in categories) selectedCategory = null }
    LaunchedEffect(hiddenCount) { if (hiddenCount == 0) showHidden = false }
    var variantFor by remember { mutableStateOf<ProductWithVariants?>(null) }
    var showCheckout by remember { mutableStateOf(false) }
    // Beim Öffnen des Bezahldialogs gefragt: Ein gerade gekoppeltes Terminal zählt beim nächsten Mal.
    val tipOnTerminal by produceState(false, showCheckout, payments) { value = showCheckout && payments.asksForTipOnTerminal() }
    var showCartSheet by remember { mutableStateOf(false) }
    var showOpenCash by remember { mutableStateOf(false) }
    // Geht die Kasse zu, geht auch ein offener Bezahldialog zu — und kommt beim Öffnen nicht von selbst wieder.
    LaunchedEffect(cashOpen) { if (!cashOpen) showCheckout = false }

    val visibleProducts = remember(products, search, selectedCategory, showHidden, hiddenIds) {
        products.filter { entry ->
            val hidden = entry.product.id in hiddenIds
            val inView = if (showHidden) hidden
            else !hidden && (selectedCategory == null || entry.product.category == selectedCategory)
            val matchesSearch = search.isBlank() ||
                entry.product.name.contains(search, ignoreCase = true) ||
                entry.product.category.contains(search, ignoreCase = true)
            inView && matchesSearch
        }
    }
    val productPaneState = ProductPaneState(
        search = search,
        onSearchChange = { search = it },
        searchOpen = searchOpen,
        onSearchOpenChange = { open ->
            searchOpen = open
            if (!open) search = ""
        },
        categories = categories,
        selectedCategory = selectedCategory,
        onSelectCategory = {
            selectedCategory = it
            showHidden = false
        },
        hiddenCount = hiddenCount,
        showHidden = showHidden,
        onShowHidden = {
            showHidden = !showHidden
            if (showHidden) selectedCategory = null
        },
        hiddenIds = hiddenIds,
        onSetHidden = viewModel::setProductHidden
    )

    val cartActions = CartActions(
        onIncrease = viewModel::increaseQuantity,
        onDecrease = viewModel::decreaseQuantity,
        onRemoveLine = viewModel::removeLine,
        onClear = viewModel::clearCart,
        onSelectMember = viewModel::selectMember,
        onSetTopUp = viewModel::setTopUpAmount,
        onAddManual = viewModel::addManualItem,
        onApplyDiscount = { lineId, percent, fixed -> viewModel.applyDiscount(lineId, percent, fixed) }
    )

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            val twoPane = maxWidth >= TwoPaneBreakpoint

            if (twoPane) {
                Row(modifier = Modifier.fillMaxSize()) {
                    ProductPane(
                        products = visibleProducts,
                        state = productPaneState,
                        catalogueEmpty = products.isEmpty(),
                        onProductClick = { entry ->
                            if (entry.variants.isNotEmpty()) variantFor = entry
                            else viewModel.addToCart(entry.product)
                        },
                        modifier = Modifier.weight(1.7f).fillMaxHeight()
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CartPane(
                        cart = cart,
                        total = total,
                        itemCount = itemCount,
                        members = members,
                        selectedMember = selectedMember,
                        memberCategories = memberCategories,
                        topUpAmount = topUpAmount,
                        actions = cartActions,
                        cash = cash,
                        onOpenCash = { showOpenCash = true },
                        onCheckout = { showCheckout = true },
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    ProductPane(
                        products = visibleProducts,
                        state = productPaneState,
                        catalogueEmpty = products.isEmpty(),
                        onProductClick = { entry ->
                            if (entry.variants.isNotEmpty()) variantFor = entry
                            else viewModel.addToCart(entry.product)
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                    // Always present, not only once something is in the cart: a bar that
                    // appears and disappears moves everything under the thumb.
                    TotalBar(
                        total = total,
                        itemCount = itemCount,
                        cashOpen = cashOpen,
                        onOpenCart = { showCartSheet = true },
                        onOpenCash = { showOpenCash = true },
                        onCheckout = { showCheckout = true }
                    )
                }
            }
        }

        if (showCartSheet) {
            ModalBottomSheet(onDismissRequest = { showCartSheet = false }) {
                CartPane(
                    cart = cart,
                    total = total,
                    itemCount = itemCount,
                    members = members,
                    selectedMember = selectedMember,
                    memberCategories = memberCategories,
                    topUpAmount = topUpAmount,
                    actions = cartActions,
                    cash = cash,
                    onOpenCash = {
                        showCartSheet = false
                        showOpenCash = true
                    },
                    onCheckout = {
                        showCartSheet = false
                        showCheckout = true
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                )
            }
        }

        variantFor?.let { entry ->
            VariantSelectionDialog(
                productWithVariants = entry,
                onDismiss = { variantFor = null },
                onVariantSelected = { variant ->
                    viewModel.addToCart(entry.product, variant)
                    variantFor = null
                }
            )
        }

        if (showOpenCash) {
            OpenCashDialog(
                members = members,
                onDismiss = { showOpenCash = false },
                onOpen = { by, openingCount ->
                    cashViewModel.open(by, openingCount)
                    showOpenCash = false
                }
            )
        }

        // Im Bezahldialog geht nichts, solange die Kasse zu ist — er erscheint dann gar nicht erst.
        if (showCheckout && cashOpen) {
            CheckoutDialog(
                categories = memberCategories,
                cartTotal = cart.sumOf { it.lineTotal },
                topUpAmount = topUpAmount,
                tipAmount = tipAmount,
                selectedMember = selectedMember,
                // Geschlossen ohne Bezahlen: Ein gewähltes Trinkgeld gilt nicht für den nächsten Versuch.
                onDismiss = { showCheckout = false; viewModel.setTipAmount(0.0) },
                onSetTipAmount = viewModel::setTipAmount,
                onCheckout = { paymentType ->
                    if (paymentType == "CARD") viewModel.checkoutByCard(payments) else viewModel.checkout(paymentType)
                    showCheckout = false
                },
                cashAllowed = !cashBlocked,
                tipOnTerminal = tipOnTerminal
            )
        }
    }
}

/** Cart callbacks, grouped so the two layouts do not each thread eight lambdas. */
private data class CartActions(
    val onIncrease: (String) -> Unit,
    val onDecrease: (String) -> Unit,
    val onRemoveLine: (String) -> Unit,
    val onClear: () -> Unit,
    val onSelectMember: (Member?) -> Unit,
    val onSetTopUp: (Double) -> Unit,
    val onAddManual: (String, Double) -> Unit,
    val onApplyDiscount: (String, Double, Double) -> Unit
)

/** Was das Produktraster über Suche, Kategorien und Ausgeblendetes wissen muss — in beiden Anordnungen gleich. */
private class ProductPaneState(
    val search: String,
    val onSearchChange: (String) -> Unit,
    val searchOpen: Boolean,
    val onSearchOpenChange: (Boolean) -> Unit,
    val categories: List<String>,
    val selectedCategory: String?,
    val onSelectCategory: (String?) -> Unit,
    val hiddenCount: Int,
    val showHidden: Boolean,
    val onShowHidden: () -> Unit,
    val hiddenIds: Set<String>,
    val onSetHidden: (com.example.vereins_kassensystem.data.entity.Product, Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProductPane(
    products: List<ProductWithVariants>,
    state: ProductPaneState,
    catalogueEmpty: Boolean,
    onProductClick: (ProductWithVariants) -> Unit,
    modifier: Modifier = Modifier
) {
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(state.searchOpen) { if (state.searchOpen) searchFocus.requestFocus() }

    Column(modifier = modifier) {
        // Die Suche als Symbol oben rechts, gleich links neben dem Warenkorb: Das Suchfeld
        // kostete eine ganze Zeile Höhe, die auf einem 8-Zoll-Tablet dem Raster fehlte.
        VdTopBar(
            title = "Verkauf",
            actions = {
                IconButton(onClick = { state.onSearchOpenChange(!state.searchOpen) }) {
                    Icon(
                        if (state.searchOpen) VdIcons.SearchOff else VdIcons.Search,
                        contentDescription = if (state.searchOpen) "Suche schließen" else "Produkt suchen"
                    )
                }
            }
        )

        if (state.searchOpen) {
            OutlinedTextField(
                value = state.search,
                onValueChange = state.onSearchChange,
                placeholder = { Text("Produkt suchen") },
                leadingIcon = { Icon(VdIcons.Search, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { state.onSearchOpenChange(false) }) {
                        Icon(VdIcons.Clear, contentDescription = "Suche schließen")
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .focusRequester(searchFocus)
            )
            Spacer(Modifier.height(Spacing.md))
        }

        CategoryFilterRow(
            categories = state.categories,
            selected = state.selectedCategory,
            onSelect = state.onSelectCategory,
            modifier = Modifier.fillMaxWidth(),
            trailing = if (state.hiddenCount > 0) TrailingChip("Ausgeblendet · ${state.hiddenCount}", state.showHidden, state.onShowHidden) else null
        )

        Spacer(Modifier.height(Spacing.md))

        when {
            catalogueEmpty -> EmptyState(
                icon = VdIcons.PointOfSale,
                title = "Noch keine Produkte",
                supportingText = "Lege unter Produkte dein Sortiment an, damit es hier erscheint."
            )

            products.isEmpty() && state.search.isBlank() && !state.showHidden && state.hiddenCount > 0 -> EmptyState(
                icon = VdIcons.SearchOff,
                title = "Alles ausgeblendet",
                supportingText = "Unter „Ausgeblendet“ stehen sie noch — ein langer Druck blendet ein Produkt wieder ein."
            )

            products.isEmpty() -> EmptyState(
                icon = VdIcons.Search,
                title = "Nichts gefunden",
                supportingText = "Keine Produkte passen zu deiner Suche."
            )

            else -> LazyVerticalGrid(
                // Ab 120 dp je Kachel: vier in einer Reihe auf einem 8-Zoll-Tablet quer, mehr auf größeren.
                columns = GridCells.Adaptive(minSize = 120.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(products, key = { it.product.id }) { entry ->
                    val hidden = entry.product.id in state.hiddenIds
                    ProductTile(
                        product = entry.product,
                        onClick = { onProductClick(entry) },
                        // Langer Druck: aus- oder wieder einblenden, nur auf diesem Gerät.
                        longPress = TileAction(if (hidden) "Einblenden" else "Ausblenden") { state.onSetHidden(entry.product, !hidden) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CartPane(
    cart: List<CartItem>,
    total: Double,
    itemCount: Int,
    members: List<Member>,
    selectedMember: Member?,
    memberCategories: List<com.example.vereins_kassensystem.data.entity.MemberCategory>,
    topUpAmount: Double,
    actions: CartActions,
    cash: CashState,
    onOpenCash: () -> Unit,
    onCheckout: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMemberPicker by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var discountFor by remember { mutableStateOf<CartItem?>(null) }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.lg)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Bestellung",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            FilledTonalIconButton(onClick = { showManualDialog = true }) {
                Icon(VdIcons.Add, contentDescription = "Manueller Betrag")
            }
            Spacer(Modifier.width(Spacing.sm))
            FilledTonalIconButton(
                onClick = actions.onClear,
                enabled = cart.isNotEmpty() || topUpAmount > 0.0,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Icon(VdIcons.DeleteSweep, contentDescription = "Bestellung leeren")
            }
        }

        Spacer(Modifier.height(Spacing.md))

        if (cart.isEmpty() && topUpAmount <= 0.0) {
            EmptyState(
                icon = VdIcons.ShoppingCartCheckout,
                title = "Nichts ausgewählt",
                supportingText = "Tippe links auf ein Produkt.",
                modifier = Modifier.weight(1f)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                items(cart, key = { it.lineId }) { item ->
                    CartLine(
                        item = item,
                        onIncrease = { actions.onIncrease(item.lineId) },
                        onDecrease = { actions.onDecrease(item.lineId) },
                        onDiscount = { discountFor = item }
                    )
                }
                if (topUpAmount > 0.0) {
                    item(key = "top-up") {
                        TopUpLine(amount = topUpAmount, onRemove = { actions.onSetTopUp(0.0) })
                    }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))

        MemberSection(
            selectedMember = selectedMember,
            memberCategories = memberCategories,
            onPick = { showMemberPicker = true },
            onClear = { actions.onSelectMember(null) },
            onTopUp = actions.onSetTopUp
        )

        Spacer(Modifier.height(Spacing.md))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(Spacing.md))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (itemCount == 1) "1 Position" else "$itemCount Positionen",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MoneyText(amount = total, style = MoneyMedium)
            }
        }

        Spacer(Modifier.height(Spacing.md))

        val session = cash.session
        if (session == null) {
            ClosedTill(onOpenCash)
        } else {
            Text(
                text = "Kasse offen · ${session.openedBy} · ${if (session.cashless) "ohne Barkasse" else "mit Barkasse"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.height(Spacing.sm))
            PayButton(
                amount = total,
                onClick = onCheckout,
                enabled = cart.isNotEmpty() || topUpAmount > 0.0
            )
        }
    }

    if (showMemberPicker) {
        MemberSelectionDialog(
            members = members,
            onDismiss = { showMemberPicker = false },
            onMemberSelected = actions.onSelectMember
        )
    }

    if (showManualDialog) {
        ManualItemDialog(
            onDismiss = { showManualDialog = false },
            onConfirm = { name, price ->
                actions.onAddManual(name, price)
                showManualDialog = false
            }
        )
    }

    discountFor?.let { item ->
        DiscountDialog(
            cartItem = item,
            onDismiss = { discountFor = null },
            onConfirm = { percent, fixed ->
                actions.onApplyDiscount(item.lineId, percent, fixed)
                discountFor = null
            }
        )
    }
}

@Composable
private fun CartLine(
    item: CartItem,
    onIncrease: () -> Unit,
    onDecrease: () -> Unit,
    onDiscount: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(start = Spacing.xs)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MoneyText(
                        amount = item.lineTotal,
                        style = MoneySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (item.hasDiscount) {
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = "Rabatt",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            IconButton(onClick = onDiscount) {
                Icon(
                    VdIcons.LocalOffer,
                    contentDescription = "Rabatt für ${item.displayName}",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            QuantityStepper(
                quantity = item.quantity,
                onIncrease = onIncrease,
                onDecrease = onDecrease
            )
        }
    }
}

@Composable
private fun TopUpLine(amount: Double, onRemove: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Guthabenaufladung", style = MaterialTheme.typography.titleSmall)
                MoneyText(amount = amount, style = MoneySmall)
            }
            IconButton(onClick = onRemove) {
                Icon(VdIcons.Clear, contentDescription = "Aufladung entfernen")
            }
        }
    }
}

@Composable
private fun MemberSection(
    selectedMember: Member?,
    memberCategories: List<com.example.vereins_kassensystem.data.entity.MemberCategory>,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onTopUp: (Double) -> Unit
) {
    if (selectedMember == null) {
        OutlinedButton(
            onClick = onPick,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.small
        ) {
            Icon(VdIcons.PersonAdd, contentDescription = null)
            Spacer(Modifier.width(Spacing.sm))
            Text("Mitglied auswählen")
        }
        return
    }

    val limit = memberCategories.find { it.id == selectedMember.categoryId }?.negativeBalanceLimit ?: 0.0

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MemberAvatar(selectedMember.name)
                Spacer(Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(selectedMember.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    MoneyText(
                        amount = selectedMember.balance,
                        style = MoneySmall,
                        color = balanceColor(selectedMember.balance, limit)
                    )
                    // Die Sperre aus der Verwaltung steht hier, bevor jemand „Deckel“ tippt — Bernstein: Aufmerksamkeit, kein Fehler.
                    if (selectedMember.isBlocked) {
                        Text(
                            text = "Deckel gesperrt: ${selectedMember.blockedReason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = VereinsColors.warning,
                            maxLines = 2
                        )
                    }
                }
                IconButton(onClick = onClear) {
                    Icon(
                        VdIcons.PersonRemove,
                        contentDescription = "Mitglied entfernen",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                listOf(5.0, 10.0, 20.0, 50.0).forEach { amount ->
                    Surface(
                        onClick = { onTopUp(amount) },
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "+${amount.toInt()} €",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Unten im Warenkorb, solange die Kasse zu ist: Statt „Bezahlen“ geht es hier zum Öffnen. Der
 * Warenkorb lässt sich trotzdem füllen — wer die Kasse übernimmt, kann die erste Runde schon
 * eintippen. Bernstein: Aufmerksamkeit, kein Fehler.
 */
@Composable
private fun ClosedTill(onOpenCash: () -> Unit) {
    Text(
        text = "Die Kasse ist zu — erst öffnen, dann kassieren.",
        style = MaterialTheme.typography.bodySmall,
        color = VereinsColors.warning
    )
    Spacer(Modifier.height(Spacing.sm))
    Button(
        onClick = onOpenCash,
        modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget.sales),
        shape = MaterialTheme.shapes.medium
    ) {
        Icon(VdIcons.PointOfSale, contentDescription = null)
        Spacer(Modifier.width(Spacing.sm))
        Text("Kasse öffnen", style = MaterialTheme.typography.labelLarge)
    }
}

/** Phone layout: the total and the way into the cart, permanently under the thumb. */
@Composable
private fun TotalBar(
    total: Double,
    itemCount: Int,
    cashOpen: Boolean,
    onOpenCart: () -> Unit,
    onOpenCash: () -> Unit,
    onCheckout: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onOpenCart)
                    .padding(vertical = Spacing.xs)
            ) {
                Text(
                    text = if (itemCount == 1) "1 Position" else "$itemCount Positionen",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MoneyText(amount = total, style = MoneyMedium)
            }
            Spacer(Modifier.width(Spacing.md))
            if (cashOpen) {
                Button(
                    onClick = onCheckout,
                    enabled = itemCount > 0 || total > 0.0,
                    modifier = Modifier.heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Bezahlen", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Button(
                    onClick = onOpenCash,
                    modifier = Modifier.heightIn(min = TouchTarget.sales),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Text("Kasse öffnen", style = MaterialTheme.typography.labelLarge)
                }
            }
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
        title = { Text(productWithVariants.product.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                productWithVariants.variants.forEach { variant ->
                    Surface(
                        onClick = { onVariantSelected(variant) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = variant.name,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            MoneyText(amount = variant.price, style = MoneySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}


@Composable
fun ManualItemDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Double) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    val parsed = Money.parse(price)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manueller Betrag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Beschreibung (optional)") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Betrag (€)") },
                    isError = price.isNotBlank() && parsed == null,
                    supportingText = {
                        if (price.isNotBlank() && parsed == null) Text("Bitte eine Zahl eingeben, z. B. 3,50")
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name.ifBlank { "Manueller Betrag" }, parsed ?: 0.0) },
                enabled = parsed != null && parsed > 0.0
            ) { Text("Hinzufügen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}

@Composable
fun DiscountDialog(
    cartItem: CartItem,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double) -> Unit
) {
    var percent by remember { mutableStateOf(if (cartItem.discountPercent > 0) cartItem.discountPercent.toString() else "") }
    var fixed by remember { mutableStateOf(if (cartItem.fixedDiscount > 0) Money.formatPlain(cartItem.fixedDiscount) else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rabatt") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(cartItem.displayName, style = MaterialTheme.typography.bodyLarge)
                OutlinedTextField(
                    value = percent,
                    onValueChange = { percent = it },
                    label = { Text("Prozent (%)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fixed,
                    onValueChange = { fixed = it },
                    label = { Text("Fixbetrag (€)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(Money.parse(percent) ?: 0.0, Money.parse(fixed) ?: 0.0)
            }) { Text("Anwenden") }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(0.0, 0.0) }) {
                Text("Entfernen", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}
