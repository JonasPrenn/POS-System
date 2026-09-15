package com.example.vereins_kassensystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.data.repository.AppRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

class CategoryViewModel(private val repository: AppRepository) : ViewModel() {

    val allCategories: StateFlow<List<MemberCategory>> = repository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun insertCategory(category: MemberCategory) = viewModelScope.launch {
        repository.insertCategory(category)
    }

    fun updateCategory(category: MemberCategory) = viewModelScope.launch {
        repository.updateCategory(category)
    }

    fun deleteCategory(category: MemberCategory) = viewModelScope.launch {
        repository.deleteCategory(category)
    }
}

class CategoryViewModelFactory(private val repository: AppRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
        require(modelClass == CategoryViewModel::class) { "Unbekanntes ViewModel: $modelClass" }
        @Suppress("UNCHECKED_CAST")
        return CategoryViewModel(repository) as T
    }
}
