package com.example.vereins_kassensystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.vereins_kassensystem.data.entity.Member
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.data.repository.AppRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.InputStream
import java.io.OutputStream

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

    fun updateBalance(memberId: Long, amount: Double) = viewModelScope.launch {
        repository.updateMemberBalance(memberId, amount)
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

    suspend fun importMembersFromCsv(inputStream: InputStream) = withContext(Dispatchers.IO) {
        try {
            inputStream.bufferedReader().use { reader ->
                reader.readLine() // skip header
                var count = 0
                for (line in reader.lineSequence()) {
                    val parts = line.split(";")
                    if (parts.size >= 2) {
                        val name = parts[0].trim()
                        val categoryName = parts[1].trim()
                        
                        if (name.isNotEmpty()) {
                            val categoryId = if (categoryName.isNotEmpty()) {
                                val cat = repository.getCategoryByName(categoryName)
                                cat?.id ?: repository.insertCategory(MemberCategory(name = categoryName, negativeBalanceLimit = 0.0))
                            } else null
                            
                            repository.insertMember(Member(name = name, categoryId = categoryId))
                            count++
                        }
                    }
                }
                _importStatus.emit("Erfolgreich $count Mitglieder importiert")
            }
        } catch (e: Exception) {
            _importStatus.emit("Fehler beim Import: ${e.message}")
        }
    }

    suspend fun exportMembersToCsv(outputStream: OutputStream) = withContext(Dispatchers.IO) {
        try {
            outputStream.bufferedWriter().use { writer ->
                writer.write("Name;Mitgliedergruppe\n")
                val members = allMembers.value
                val categories = allCategories.value
                members.forEach { member ->
                    val categoryName = categories.find { it.id == member.categoryId }?.name ?: ""
                    writer.write("${member.name};$categoryName\n")
                }
                writer.flush()
            }
            _importStatus.emit("Export erfolgreich")
        } catch (e: Exception) {
            _importStatus.emit("Fehler beim Export: ${e.message}")
        }
    }
}

class MemberViewModelFactory(private val repository: AppRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MemberViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MemberViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
