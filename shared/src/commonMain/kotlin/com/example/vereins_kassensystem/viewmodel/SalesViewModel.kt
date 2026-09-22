package com.example.vereins_kassensystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vereins_kassensystem.data.Ledger
import com.example.vereins_kassensystem.data.entity.*
import com.example.vereins_kassensystem.data.entity.Transaction
import com.example.vereins_kassensystem.data.dao.ProductWithVariants
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.data.stock.Inventory
import com.example.vereins_kassensystem.data.stock.StockItemState
import com.example.vereins_kassensystem.ui.format.Money
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.example.vereins_kassensystem.platform.Ids
import com.example.vereins_kassensystem.platform.PaymentProcessor
import com.example.vereins_kassensystem.platform.PaymentResult

data class CartItem(
    val product: Product,
    val variant: ProductVariant? = null,
    val quantity: Int = 1,
    val discountPercent: Double = 0.0,
    val fixedDiscount: Double = 0.0,
    /**
     * Identifies this line for the whole time it is in the cart.
     *
     * Lines used to be addressed by `product.id`, which every manual item shares
     * (`-2L`) — so two manual amounts produced duplicate keys in the cart list, and
     * editing one of them hit whichever came first.
     */
    val lineId: String = Ids.new()
) {
    val finalPrice: Double
        get() = ((variant?.price ?: product.price) * (1.0 - discountPercent / 100.0)) - fixedDiscount

    /** What this line contributes to the total, after its discount. */
    val lineTotal: Double get() = finalPrice * quantity

    val displayName: String
        get() = if (variant != null) "${product.name} (${variant.name})" else product.name

    val hasDiscount: Boolean get() = discountPercent > 0.0 || fixedDiscount > 0.0
}

class SalesViewModel(private val repository: AppRepository) : ViewModel() {

    private val _cart = MutableStateFlow<List<CartItem>>(emptyList())
    val cart: StateFlow<List<CartItem>> = _cart.asStateFlow()

    /**
     * Gemerkt wird der Schlüssel, nicht das Mitglied: Der Saldo ist hergeleitet und ändert
     * sich, sobald eine Theke bucht — auch die andere. Ein festgehaltenes Objekt zeigte
     * den Stand vom Antippen.
     */
    private val _selectedMemberId = MutableStateFlow<String?>(null)

    private val _topUpAmount = MutableStateFlow(0.0)
    val topUpAmount: StateFlow<Double> = _topUpAmount.asStateFlow()

    private val _tipAmount = MutableStateFlow(0.0)
    val tipAmount: StateFlow<Double> = _tipAmount.asStateFlow()

    val allProductsWithVariants: StateFlow<List<ProductWithVariants>> = repository.allProductsWithVariants
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMembers: StateFlow<List<Member>> = repository.allMembers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedMember: StateFlow<Member?> = combine(_selectedMemberId, repository.allMembers) { id, members ->
        members.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val allCategories: StateFlow<List<MemberCategory>> = repository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTransactions: StateFlow<List<Transaction>> = repository.allTransactions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalAmount: StateFlow<Double> = combine(_cart, _topUpAmount, _tipAmount) { cartItems, topUp, tip ->
        cartItems.sumOf { it.lineTotal } + topUp + tip
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    /** Distinct product categories, for the filter above the sales grid. */
    val productCategories: StateFlow<List<String>> = allProductsWithVariants
        .map { products ->
            products.map { it.product.category }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Total pieces in the cart, for the badge and the total bar. */
    val itemCount: StateFlow<Int> = _cart
        .map { cart -> cart.sumOf { it.quantity } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Stock state per Lagerartikel, rebuilt whenever the cellar changes. */
    private val stockStates: StateFlow<Map<String, StockItemState>> = combine(
        repository.allStockItems,
        repository.allContainerTypes,
        repository.allTappedContainers
    ) { items, types, tapped ->
        items.associate { item ->
            val itemTypes = types.filter { it.stockItemId == item.id }
            item.id to StockItemState(
                item = item,
                containerTypes = itemTypes,
                tapped = tapped.filter { t -> itemTypes.any { it.id == t.containerTypeId } }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * How many of each product could still be made, keyed by product id.
     *
     * Null means nothing is tracked for it. The figure is the minimum across the recipe,
     * so a Radler is limited by whichever of beer or soda runs out first — and it never
     * prevents a sale, it only drives the warning badge.
     */
    val productAvailability: StateFlow<Map<String, Int?>> = combine(
        allProductsWithVariants,
        repository.allComponents,
        stockStates
    ) { products, components, states ->
        products.associate { entry ->
            val recipe = components.filter { it.productId == entry.product.id }
            entry.product.id to Inventory.servingsPossible(recipe, states, entry.product.servingSize)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _checkoutError = MutableSharedFlow<String>()
    val checkoutError = _checkoutError.asSharedFlow()

    /**
     * Adds a product to the cart.
     *
     * A low or empty stock figure never blocks the sale. The number is a best guess about
     * the cellar; the person at the counter is real, and refusing to ring up a beer that
     * is visibly in the fridge would be the app arguing with the room. Stock is allowed
     * to go negative and is surfaced as a warning under Lagerbestand instead.
     */
    fun addToCart(product: Product, variant: ProductVariant? = null) {
        val currentCart = _cart.value.toMutableList()
        val index = currentCart.indexOfFirst { it.product.id == product.id && it.variant?.id == variant?.id }

        if (index != -1) {
            currentCart[index] = currentCart[index].copy(quantity = currentCart[index].quantity + 1)
        } else {
            currentCart.add(CartItem(product, variant))
        }
        _cart.value = currentCart.toList()
    }

    /** One more of an existing line. Also unconstrained by stock — see [addToCart]. */
    fun increaseQuantity(lineId: String) {
        val index = _cart.value.indexOfFirst { it.lineId == lineId }
        if (index == -1) return
        val item = _cart.value[index]
        _cart.value = _cart.value.toMutableList().also {
            it[index] = item.copy(quantity = item.quantity + 1)
        }
    }

    /** One fewer; the line goes when it would reach zero. */
    fun decreaseQuantity(lineId: String) {
        val index = _cart.value.indexOfFirst { it.lineId == lineId }
        if (index == -1) return
        val item = _cart.value[index]
        _cart.value = _cart.value.toMutableList().also {
            if (item.quantity > 1) it[index] = item.copy(quantity = item.quantity - 1) else it.removeAt(index)
        }
    }

    /** Drops the whole line regardless of quantity. */
    fun removeLine(lineId: String) {
        _cart.value = _cart.value.filterNot { it.lineId == lineId }
    }

    fun applyDiscount(lineId: String, percent: Double = 0.0, fixed: Double = 0.0) {
        val index = _cart.value.indexOfFirst { it.lineId == lineId }
        if (index == -1) return
        _cart.value = _cart.value.toMutableList().also {
            it[index] = it[index].copy(discountPercent = percent, fixedDiscount = fixed)
        }
    }

    fun clearCart() {
        _cart.value = emptyList()
        _topUpAmount.value = 0.0
        _tipAmount.value = 0.0
    }

    fun selectMember(member: Member?) {
        _selectedMemberId.value = member?.id
        if (member == null) {
            _topUpAmount.value = 0.0
        }
    }

    fun setTopUpAmount(amount: Double) {
        if (amount == 0.0) {
            _topUpAmount.value = 0.0
        } else {
            _topUpAmount.value += amount
        }
    }

    fun setTipAmount(amount: Double) {
        _tipAmount.value = amount
    }

    fun addManualItem(name: String, price: Double) {
        val manualProduct = Product(
            id = Ledger.MANUAL_REF, // kein Produkt, sondern ein eingetippter Betrag
            name = name,
            price = price,
            category = "Manuell"
        )
        val currentCart = _cart.value.toMutableList()
        currentCart.add(CartItem(manualProduct))
        _cart.value = currentCart.toList()
    }

    /**
     * Kartenzahlung über das Terminal, dann die Buchung.
     *
     * Läuft im Scope des ViewModels und nicht im Bildschirm: Der Kassier dreht das Tablet,
     * während das Terminal wartet, und die Activity darunter wird neu gebaut. Das
     * ViewModel überlebt das, eine Composition nicht — und eine Karte, die belastet wurde,
     * ohne dass die Buchung folgt, ist der teuerste Fehler, den diese App machen kann.
     * Die Referenz entsteht vorab und geht an den Anbieter mit, damit sich die Zahlung
     * im SumUp-Konto dem Kassiervorgang zuordnen lässt.
     */
    fun checkoutByCard(payments: PaymentProcessor) = viewModelScope.launch {
        val reference = Ids.new()
        when (val result = payments.charge(totalAmount.value, reference)) {
            is PaymentResult.Success -> checkout("CARD", reference)
            PaymentResult.Cancelled -> Unit
            is PaymentResult.Failed -> _checkoutError.emit(result.message)
        }
    }

    fun checkout(paymentType: String = "CASH", transactionGroupId: String = Ids.new()) = viewModelScope.launch {
        val currentCart = _cart.value
        val currentTopUp = _topUpAmount.value
        val currentTip = _tipAmount.value
        // Frisch gelesen statt aus dem Zustand: Der Saldo soll der von jetzt sein.
        val member = _selectedMemberId.value?.let { repository.getMember(it) }

        if (currentCart.isEmpty() && currentTopUp <= 0.0 && currentTip <= 0.0) return@launch

        if (paymentType == Ledger.MEMBER_BALANCE && member != null) {
            // Die Sperre kommt aus der Verwaltung und gilt vor dem Limit: Sie steht mit Grund da.
            if (member.isBlocked) {
                _checkoutError.emit("Deckel gesperrt: ${member.blockedReason}. Bar oder Karte geht.")
                return@launch
            }
            val limit = member.categoryId?.let { repository.getCategoryById(it) }?.negativeBalanceLimit ?: 0.0
            // Was der Deckel wirklich trägt: die Positionen nach Rabatt, plus Trinkgeld.
            val charge = currentCart.sumOf { it.lineTotal } + currentTip
            if (Money.cents(member.balance - charge) < limit) {
                _checkoutError.emit("Guthaben nicht ausreichend. Limit: ${Money.format(limit)}")
                return@launch
            }
        }

        // Eine Transaktion im Repository: Positionen, Aufladung, Trinkgeld und Lagerabgänge
        // stehen alle da oder keine. Einen Abzug vom Deckel gibt es nicht mehr — der Saldo
        // ergibt sich aus den Zeilen.
        repository.bookCheckout(
            lines = currentCart.map { item ->
                val unitPrice = item.variant?.price ?: item.product.price
                AppRepository.SaleLine(
                    product = item.product,
                    variant = item.variant,
                    quantity = item.quantity,
                    discount = (unitPrice - item.finalPrice) * item.quantity
                )
            },
            topUp = currentTopUp,
            tip = currentTip,
            member = member,
            paymentType = paymentType,
            transactionGroupId = transactionGroupId
        )

        clearCart()
        selectMember(null)
    }
}
