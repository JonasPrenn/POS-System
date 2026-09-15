package com.example.vereins_kassensystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.data.repository.AppRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

class MemberViewModel(private val repository: AppRepository) : ViewModel() {

    private val _importStatus = MutableSharedFlow<String>()
    val importStatus = _importStatus.asSharedFlow()

    val allMembers: StateFlow<List<Member>> = repository.allMembers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allCategories: StateFlow<List<MemberCategory>> = repository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun insertMember(member: Member) = viewModelScope.launch {
        repository.insertMember(member)
    }

    fun updateMember(member: Member) = viewModelScope.launch {
        repository.updateMember(member)
    }

    fun deleteMember(member: Member) = viewModelScope.launch {
        repository.deleteMember(member)
    }

    /**
     * Credits or debits a Deckel from the Mitglieder screen.
     *
     * [reason] and [paymentType] are not optional. Crediting a balance used to move the
     * number with nothing written down, so money appeared on a Deckel that could not be
     * reconciled against the cash box afterwards. Every movement now lands in the
     * transaction history with its reason attached.
     */
    fun adjustBalance(
        member: Member,
        amount: Double,
        reason: String,
        paymentType: String
    ) = viewModelScope.launch {
        repository.adjustMemberBalance(member, amount, reason, paymentType)
    }

    fun insertCategory(category: MemberCategory) = viewModelScope.launch {
        repository.insertCategory(category)
    }

    fun updateCategory(category: MemberCategory) = viewModelScope.launch {
        repository.updateCategory(category)
    }

    fun deleteCategory(category: MemberCategory) = viewModelScope.launch {
        repository.deleteCategory(category)
    }

    /**
     * Liest Mitglieder aus einer Semikolon-Datei: `Name;Mitgliedergruppe`, erste Zeile
     * Kopfzeile. Zeichenkette statt Strom, siehe `platform/FileExchange.kt`.
     */
    suspend fun importMembersFromCsv(csv: String) {
        try {
            var count = 0
            for (line in csv.lineSequence().drop(1)) {
                val parts = line.split(";")
                if (parts.size < 2) continue
                val name = parts[0].trim()
                val categoryName = parts[1].trim()
                if (name.isEmpty()) continue

                val categoryId = if (categoryName.isNotEmpty()) {
                    val cat = repository.getCategoryByName(categoryName)
                    cat?.id ?: repository.insertCategory(MemberCategory(name = categoryName, negativeBalanceLimit = 0.0))
                } else null

                repository.insertMember(Member(name = name, categoryId = categoryId))
                count++
            }
            _importStatus.emit("Erfolgreich $count Mitglieder importiert")
        } catch (e: Exception) {
            _importStatus.emit("Fehler beim Import: ${e.message}")
        }
    }

    /** Alle Mitglieder als Semikolon-Datei, Kopfzeile inklusive. Das Schreiben übernimmt der Bildschirm. */
    fun exportMembersToCsv(): String = buildString {
        append("Name;Mitgliedergruppe\n")
        val categories = allCategories.value
        allMembers.value.forEach { member ->
            val categoryName = categories.find { it.id == member.categoryId }?.name ?: ""
            append("${member.name};$categoryName\n")
        }
    }
}

class MemberViewModelFactory(private val repository: AppRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
        require(modelClass == MemberViewModel::class) { "Unbekanntes ViewModel: $modelClass" }
        @Suppress("UNCHECKED_CAST")
        return MemberViewModel(repository) as T
    }
}
