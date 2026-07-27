package com.example.vereins_kassensystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vereins_kassensystem.data.entity.*
import com.example.vereins_kassensystem.data.entity.Transaction
import com.example.vereins_kassensystem.data.dao.ProductWithVariants
import com.example.vereins_kassensystem.data.repository.AppRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

data class CartItem(
    val product: Product,
    val variant: ProductVariant? = null,
    val quantity: Int = 1,
    val discountPercent: Double = 0.0,
    val fixedDiscount: Double = 0.0
) {
    val finalPrice: Double
        get() = ((variant?.price ?: product.price) * (1.0 - discountPercent / 100.0)) - fixedDiscount
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
        cartItems.sumOf { it.finalPrice * it.quantity } + topUp + tip
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    private val _checkoutError = MutableSharedFlow<String>()
    val checkoutError = _checkoutError.asSharedFlow()

    fun addToCart(product: Product, variant: ProductVariant? = null) {
        if (product.trackInventory && product.stockQuantity <= 0) {
            viewModelScope.launch {
                _checkoutError.emit("Produkt nicht mehr auf Lager!")
            }
            return
        }

        val currentCart = _cart.value.toMutableList()
        val index = currentCart.indexOfFirst { it.product.id == product.id && it.variant?.id == variant?.id }
        
        if (index != -1) {
            val currentQty = currentCart[index].quantity
            if (product.trackInventory && currentQty + 1 > product.stockQuantity) {
                viewModelScope.launch {
                    _checkoutError.emit("Nicht genügend Bestand auf Lager!")
                }
                return
            }
            currentCart[index] = currentCart[index].copy(quantity = currentQty + 1)
        } else {
            currentCart.add(CartItem(product, variant))
        }
        _cart.value = currentCart.toList()
    }

    fun removeFromCart(product: Product, variant: ProductVariant? = null) {
        val currentCart = _cart.value.toMutableList()
        val index = currentCart.indexOfFirst { it.product.id == product.id && it.variant?.id == variant?.id }
        if (index != -1) {
            val existingItem = currentCart[index]
            if (existingItem.quantity > 1) {
                currentCart[index] = existingItem.copy(quantity = existingItem.quantity - 1)
            } else {
                currentCart.removeAt(index)
            }
        }
        _cart.value = currentCart.toList()
    }

    fun applyDiscount(product: Product, variant: ProductVariant? = null, percent: Double = 0.0, fixed: Double = 0.0) {
        val currentCart = _cart.value.toMutableList()
        val index = currentCart.indexOfFirst { it.product.id == product.id && it.variant?.id == variant?.id }
        if (index != -1) {
            currentCart[index] = currentCart[index].copy(discountPercent = percent, fixedDiscount = fixed)
            _cart.value = currentCart.toList()
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
                _checkoutError.emit("Guthaben nicht ausreichend (Limit: ${String.format(Locale.getDefault(), "%.2f", limit)} €)")
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

            // Update Stock
            if (item.product.trackInventory) {
                val newStock = item.product.stockQuantity - item.quantity
                repository.updateProduct(item.product.copy(stockQuantity = newStock))
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
