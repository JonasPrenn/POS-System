package com.example.vereins_kassensystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vereins_kassensystem.data.entity.*
import com.example.vereins_kassensystem.data.entity.Transaction
import com.example.vereins_kassensystem.data.dao.ProductWithVariants
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.data.stock.Stock
import com.example.vereins_kassensystem.ui.format.Money
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

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
    val lineId: String = UUID.randomUUID().toString()
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

    private val _selectedMember = MutableStateFlow<Member?>(null)
    val selectedMember: StateFlow<Member?> = _selectedMember.asStateFlow()

    private val _topUpAmount = MutableStateFlow(0.0)
    val topUpAmount: StateFlow<Double> = _topUpAmount.asStateFlow()

    private val _tipAmount = MutableStateFlow(0.0)
    val tipAmount: StateFlow<Double> = _tipAmount.asStateFlow()

    val allProductsWithVariants: StateFlow<List<ProductWithVariants>> = repository.allProductsWithVariants
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMembers: StateFlow<List<Member>> = repository.allMembers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        _selectedMember.value = member
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
            id = -2L, // Special ID for manual items
            name = name,
            price = price,
            category = "Manuell"
        )
        val currentCart = _cart.value.toMutableList()
        currentCart.add(CartItem(manualProduct))
        _cart.value = currentCart.toList()
    }

    fun topUpBalance(member: Member, amount: Double) = viewModelScope.launch {
        repository.updateMemberBalance(member.id, amount)
    }

    fun checkout(paymentType: String = "CASH") = viewModelScope.launch {
        val currentCart = _cart.value
        val currentTopUp = _topUpAmount.value
        val currentTip = _tipAmount.value
        val member = _selectedMember.value
        
        if (currentCart.isEmpty() && currentTopUp <= 0.0 && currentTip <= 0.0) return@launch

        val cartTotal = currentCart.sumOf { (it.variant?.price ?: it.product.price) * it.quantity }
        val transactionGroupId = UUID.randomUUID().toString()
        val memberName = member?.name

        if (paymentType == "MEMBER_BALANCE" && member != null) {
            val category = member.categoryId?.let { repository.getCategoryById(it) }
            val limit = category?.negativeBalanceLimit ?: 0.0
            if (member.balance - cartTotal < limit) {
                _checkoutError.emit("Guthaben nicht ausreichend. Limit: ${Money.format(limit)}")
                return@launch
            }
        }

        // 1. Process cart items
        currentCart.forEach { item ->
            val totalItemDiscount = ((item.variant?.price ?: item.product.price) - item.finalPrice) * item.quantity
            val transaction = Transaction(
                transactionGroupId = transactionGroupId,
                memberId = member?.id,
                memberName = memberName,
                productId = item.product.id,
                productName = if (item.variant != null) "${item.product.name} (${item.variant.name})" else item.product.name,
                productCategory = item.product.category,
                price = item.variant?.price ?: item.product.price,
                quantity = item.quantity,
                discountAmount = totalItemDiscount,
                paymentType = paymentType
            )
            repository.insertTransaction(transaction)

            // Piece products lose pieces; draught products lose the poured volume and
            // broach a fresh container when the open one runs dry. See Stock.
            if (item.product.trackInventory) {
                repository.updateProduct(
                    Stock.applySale(item.product, item.variant, item.quantity)
                )
            }
        }

        // 2. Process top-up
        if (currentTopUp > 0.0 && member != null) {
            val topUpTransaction = Transaction(
                transactionGroupId = transactionGroupId,
                memberId = member.id,
                memberName = memberName,
                productId = -1L,
                productName = "Guthabenaufladung",
                productCategory = "Aufladung",
                price = currentTopUp,
                quantity = 1,
                discountAmount = 0.0,
                paymentType = paymentType
            )
            repository.insertTransaction(topUpTransaction)
            repository.updateMemberBalance(member.id, currentTopUp)
        }

        // 3. Process tip
        if (currentTip > 0.0) {
            val tipTransaction = Transaction(
                transactionGroupId = transactionGroupId,
                memberId = member?.id,
                memberName = memberName,
                productId = -3L,
                productName = "Trinkgeld",
                productCategory = "Trinkgeld",
                price = currentTip,
                quantity = 1,
                discountAmount = 0.0,
                paymentType = paymentType
            )
            repository.insertTransaction(tipTransaction)
        }

        // 4. Subtract from balance if payment type is MEMBER_BALANCE
        if (paymentType == "MEMBER_BALANCE" && member != null) {
            repository.updateMemberBalance(member.id, -cartTotal)
        }

        if (member != null) {
            repository.updateMemberLastUsed(member.id)
        }
        
        clearCart()
        selectMember(null)
    }
}

class SalesViewModelFactory(private val repository: AppRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SalesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SalesViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
