package com.example.vereins_kassensystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vereins_kassensystem.data.entity.CashMovement
import com.example.vereins_kassensystem.data.entity.CashMovementKind
import com.example.vereins_kassensystem.data.entity.CashSession
import com.example.vereins_kassensystem.data.repository.AppRepository
import com.example.vereins_kassensystem.ui.format.Money
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Die Lade dieses Geräts, wie die Übersicht sie zeigt. */
data class CashState(
    val session: CashSession? = null,
    val movements: List<CashMovement> = emptyList(),
    /** Was bar hereinkam, seit die Schicht offen ist — nur Buchungen dieses Geräts. */
    val cashIn: Double = 0.0,
) {
    val deposits: Double get() = movements.filter { it.kind == CashMovementKind.DEPOSIT }.sumOf { it.amount }
    val withdrawals: Double get() = movements.filter { it.kind == CashMovementKind.WITHDRAWAL }.sumOf { it.amount }

    /** Bardienst ohne Barkasse: Die Theke nimmt kein Bargeld, gezählt wird nichts. */
    val cashless: Boolean get() = session?.cashless == true

    /** Was jetzt in der Lade sein müsste. */
    val expected: Double get() = session?.let { Money.cents(it.openingCount + cashIn + deposits - withdrawals) } ?: 0.0
}

/**
 * Schicht öffnen, Geld entnehmen oder einlegen, Schicht schließen (Konzept 4.5). Der Verkauf
 * hängt nicht daran: Verkauft wird auch ohne offene Schicht — dann fehlt eben die Zählung.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashViewModel(private val repository: AppRepository, private val deviceLabel: suspend () -> String) : ViewModel() {

    val state: StateFlow<CashState> = repository.openCashSession.flatMapLatest { session ->
        if (session == null) flowOf(CashState())
        else combine(repository.cashMovements(session.id), repository.cashInSince(session.openedAt)) { movements, cashIn ->
            CashState(session, movements, cashIn)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CashState())

    /** Bardienst beginnen: mit Barkasse und gezähltem Wechselgeld, oder ohne ([openingCount] null) — dann nimmt die Theke kein Bargeld. */
    fun open(by: String, openingCount: Double?) = viewModelScope.launch {
        repository.openCashSession(openingCount ?: 0.0, by, deviceLabel(), cashless = openingCount == null)
    }

    fun move(kind: CashMovementKind, amount: Double, reason: String, by: String) = viewModelScope.launch {
        val session = state.value.session ?: return@launch
        repository.recordCashMovement(session, kind, amount, reason, by)
    }

    fun close(closingCount: Double?, by: String, note: String?) = viewModelScope.launch {
        val session = state.value.session ?: return@launch
        repository.closeCashSession(session, closingCount, by, note)
    }
}
