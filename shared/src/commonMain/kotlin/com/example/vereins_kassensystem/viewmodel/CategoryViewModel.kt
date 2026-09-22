package com.example.vereins_kassensystem.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.vereins_kassensystem.data.entity.MemberCategory
import com.example.vereins_kassensystem.data.repository.AppRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
